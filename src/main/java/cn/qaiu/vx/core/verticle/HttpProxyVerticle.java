package cn.qaiu.vx.core.verticle;

import cn.qaiu.vx.core.dnode.DNodeClient;
import cn.qaiu.vx.core.dnode.DNodeServer;
import cn.qaiu.vx.core.dnode.DNodeTunnel;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

import static cn.qaiu.vx.core.util.ConfigConstant.GLOBAL_CONFIG;
import static cn.qaiu.vx.core.util.ConfigConstant.LOCAL;

/**
 * HTTP/HTTPS 代理。可选通过 DNode 把隧道转到下游节点。
 */
public class HttpProxyVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpProxyVerticle.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "proxy-connection"
    );

    private HttpClient httpClient;
    private NetClient netClient;

    private JsonObject proxyPreConf;
    private JsonObject proxyServerConf;

    @Override
    public void start() {
        JsonObject global = (JsonObject) vertx.sharedData().getLocalMap(LOCAL).get(GLOBAL_CONFIG);
        proxyServerConf = global.getJsonObject("proxy-server");
        proxyPreConf = global.getJsonObject("proxy-pre");
        Integer serverPort = proxyServerConf.getInteger("port");

        ProxyOptions proxyOptions = buildUpstreamProxy(proxyPreConf);

        HttpClientOptions httpClientOptions = new HttpClientOptions()
                .setMaxPoolSize(64)
                .setKeepAlive(true)
                .setConnectTimeout(15000)
                .setTcpNoDelay(true)
                .setTrustAll(true);
        if (proxyOptions != null) {
            httpClientOptions.setProxyOptions(proxyOptions);
        }
        httpClient = vertx.createHttpClient(httpClientOptions);

        // 认证走 Proxy-Authorization；明文端口不启用 TLS ClientAuth（与原运行行为一致）
        HttpServer server = vertx.createHttpServer(new HttpServerOptions().setTcpNoDelay(true));
        server.requestHandler(this::handleClientRequest);

        NetClientOptions netClientOptions = new NetClientOptions()
                .setConnectTimeout(15000)
                .setTcpNoDelay(true)
                .setTrustAll(true);
        if (proxyOptions != null) {
            netClientOptions.setProxyOptions(proxyOptions);
        }
        netClient = vertx.createNetClient(netClientOptions);

        JsonObject dnodeServerConf = global.getJsonObject("dnode-server");
        JsonObject dnodeClientConf = global.getJsonObject("dnode-client");
        DNodeServer.get().start(vertx, dnodeServerConf).onComplete(ar -> {
            if (ar.failed()) {
                LOGGER.error("DNode server failed: {}", ar.cause().toString());
            }
            DNodeClient.get().start(vertx, dnodeClientConf);
        });

        server.listen(serverPort, ar -> {
            if (ar.succeeded()) {
                LOGGER.info("HTTP Proxy server started on port {}", serverPort);
            } else {
                LOGGER.error("Failed to start HTTP Proxy server: {}", ar.cause().toString());
            }
        });
    }

    private void handleConnectRequest(HttpServerRequest clientRequest) {
        HostPort target = parseConnectTarget(clientRequest.uri());
        if (target == null) {
            clientRequest.response().setStatusCode(400).end("Bad Request: Invalid URI format");
            return;
        }
        clientRequest.pause();
        if (routeViaDnode(clientRequest, target, true)) {
            return;
        }
        directConnect(clientRequest, target);
    }

    private void directConnect(HttpServerRequest clientRequest, HostPort target) {
        netClient.connect(target.port, target.host, connectionAttempt -> {
            if (connectionAttempt.succeeded()) {
                NetSocket targetSocket = connectionAttempt.result();
                clientRequest.toNetSocket().onComplete(clientSocketAttempt -> {
                    if (clientSocketAttempt.succeeded()) {
                        pipeSockets(clientSocketAttempt.result(), targetSocket);
                    } else {
                        LOGGER.debug("Failed to upgrade client connection to socket: {}",
                                clientSocketAttempt.cause().toString());
                        targetSocket.close();
                    }
                });
            } else {
                LOGGER.debug("Failed to connect to target: {}", connectionAttempt.cause().toString());
                failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
            }
        });
    }

    private void upgradeConnectToTunnel(HttpServerRequest clientRequest, DNodeTunnel tunnel) {
        clientRequest.toNetSocket().onComplete(ar -> {
            if (ar.failed()) {
                LOGGER.debug("Failed to upgrade client connection to socket: {}", ar.cause().toString());
                tunnel.close();
                return;
            }
            pipeSocketAndTunnel(ar.result(), tunnel);
        });
    }

    private void handleClientRequest(HttpServerRequest clientRequest) {
        LOGGER.debug("source: {}, target: {}",
                clientRequest.remoteAddress() == null ? "-" : clientRequest.remoteAddress().toString(),
                clientRequest.uri());
        if (!authorize(clientRequest)) {
            return;
        }
        if (clientRequest.method() == HttpMethod.CONNECT) {
            handleConnectRequest(clientRequest);
        } else {
            handleHttpRequest(clientRequest);
        }
    }

    /**
     * 兼容原逻辑：配置了 username 时校验 Proxy-Authorization，失败仍返回 403。
     */
    private boolean authorize(HttpServerRequest clientRequest) {
        if (!proxyServerConf.containsKey("username") ||
                StringUtils.isBlank(proxyServerConf.getString("username"))) {
            return true;
        }
        String s = clientRequest.headers().get("Proxy-Authorization");
        if (s == null) {
            clientRequest.response().setStatusCode(403).end();
            return false;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(s.replace("Basic ", "")), StandardCharsets.UTF_8);
            String[] split = decoded.split(":");
            if (split.length > 1) {
                String username = proxyServerConf.getString("username");
                String password = proxyServerConf.getString("password");
                if (!split[0].equals(username) || !split[1].equals(password)) {
                    LOGGER.info("-----auth failed------\nusername: {}\npassword: {}", username, password);
                    clientRequest.response().setStatusCode(403).end();
                    return false;
                }
            }
        } catch (Exception e) {
            clientRequest.response().setStatusCode(403).end();
            return false;
        }
        return true;
    }

    private void handleHttpRequest(HttpServerRequest clientRequest) {
        HostPort target = resolveHttpTarget(clientRequest);
        if (target == null) {
            clientRequest.response().setStatusCode(400).end("Host header is missing");
            return;
        }
        clientRequest.pause();
        if (routeViaDnode(clientRequest, target, false)) {
            return;
        }
        forwardHttpDirect(clientRequest, target);
    }

    /**
     * @return true 表示已接手（走节点或已返回 502），false 表示调用方应本机直连
     */
    private boolean routeViaDnode(HttpServerRequest clientRequest, HostPort target, boolean connect) {
        DNodeServer dnode = DNodeServer.get();
        if (!dnode.isActive()) {
            return false;
        }
        if (!dnode.hasNodes()) {
            if (dnode.isFallbackDirect()) {
                return false;
            }
            failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
            return true;
        }
        dnode.openTunnel(target.host, target.port, resolveClientIp(clientRequest))
                .onSuccess(tunnel -> {
                    if (connect) {
                        upgradeConnectToTunnel(clientRequest, tunnel);
                    } else {
                        forwardHttpViaTunnel(clientRequest, target, tunnel);
                    }
                })
                .onFailure(err -> {
                    LOGGER.debug("DNode {} fail {}:{} : {}", connect ? "CONNECT" : "HTTP",
                            target.host, target.port, err.toString());
                    if (dnode.isFallbackDirect()) {
                        if (connect) {
                            directConnect(clientRequest, target);
                        } else {
                            forwardHttpDirect(clientRequest, target);
                        }
                    } else {
                        failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
                    }
                });
        return true;
    }

    private void forwardHttpDirect(HttpServerRequest clientRequest, HostPort target) {
        HttpServerResponse clientResp = clientRequest.response();
        RequestOptions options = new RequestOptions()
                .setMethod(clientRequest.method())
                .setHost(target.host)
                .setPort(target.port)
                .setURI(originForm(clientRequest.uri()))
                .setSsl(target.ssl)
                .setTimeout(0);

        httpClient.request(options)
                .onSuccess(upstream -> {
                    copyRequestHeaders(clientRequest, upstream.headers());
                    if (clientRequest.getHeader(HttpHeaders.CONTENT_LENGTH) == null
                            && mayHaveBody(clientRequest.method())) {
                        upstream.setChunked(true);
                    }
                    upstream.response()
                            .onSuccess(upstreamResp -> {
                                if (clientResp.ended() || clientResp.closed()) {
                                    upstreamResp.request().connection().close();
                                    return;
                                }
                                clientResp.setStatusCode(upstreamResp.statusCode());
                                if (upstreamResp.statusMessage() != null) {
                                    clientResp.setStatusMessage(upstreamResp.statusMessage());
                                }
                                copyResponseHeaders(upstreamResp.headers(), clientResp);
                                if (upstreamResp.getHeader(HttpHeaders.CONTENT_LENGTH) == null) {
                                    clientResp.setChunked(true);
                                }
                                upstreamResp.exceptionHandler(err -> {
                                    LOGGER.debug("upstream response error: {}", err.toString());
                                    safeClose(clientResp);
                                });
                                upstreamResp.pipeTo(clientResp).onFailure(err -> {
                                    LOGGER.debug("pipe response failed: {}", err.toString());
                                    safeClose(clientResp);
                                });
                            })
                            .onFailure(err -> {
                                LOGGER.debug("upstream response failed: {}", err.toString());
                                failGateway(clientRequest, "Bad Gateway: Unable to reach target");
                            });

                    clientRequest.exceptionHandler(err -> {
                        LOGGER.debug("client request error: {}", err.toString());
                        upstream.reset();
                    });
                    clientRequest.pipeTo(upstream).onFailure(err -> {
                        LOGGER.debug("pipe request failed: {}", err.toString());
                        upstream.reset();
                        failGateway(clientRequest, "Bad Gateway: Unable to reach target");
                    });
                })
                .onFailure(err -> {
                    LOGGER.warn("Failed to send proxy request: {}", err.toString());
                    failGateway(clientRequest, "Bad Gateway: Request failed");
                });
    }

    private void forwardHttpViaTunnel(HttpServerRequest clientRequest, HostPort target, DNodeTunnel tunnel) {
        HttpServerResponse clientResp = clientRequest.response();
        try {
            tunnel.write(buildHttpRequestHead(clientRequest, target));
        } catch (Exception e) {
            tunnel.close();
            failGateway(clientRequest, "Bad Gateway: Unable to reach target");
            return;
        }
        Context ctx = vertx.getOrCreateContext();
        HttpResponseBridge bridge = new HttpResponseBridge(clientResp, tunnel);
        tunnel.handler(buf -> ctx.runOnContext(v -> bridge.accept(buf)));
        tunnel.closeHandler(v -> ctx.runOnContext(x -> bridge.onTunnelClose()));
        clientRequest.handler(tunnel::write);
        clientRequest.exceptionHandler(err -> tunnel.close());
        clientRequest.resume();
    }

    private static Buffer buildHttpRequestHead(HttpServerRequest req, HostPort target) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(req.method().name()).append(' ')
                .append(originForm(req.uri())).append(" HTTP/1.1\r\n");
        boolean hasHost = false;
        for (var header : req.headers()) {
            String key = header.getKey();
            if (isHopByHop(key)) {
                continue;
            }
            if ("host".equalsIgnoreCase(key)) {
                hasHost = true;
            }
            sb.append(key).append(": ").append(header.getValue()).append("\r\n");
        }
        if (!hasHost) {
            sb.append("Host: ").append(target.host);
            int def = target.ssl ? 443 : 80;
            if (target.port != def) {
                sb.append(':').append(target.port);
            }
            sb.append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return Buffer.buffer(sb.toString(), StandardCharsets.UTF_8.name());
    }

    private void pipeSockets(NetSocket a, NetSocket b) {
        pump(a, b);
        pump(b, a);
    }

    private void pump(NetSocket from, NetSocket to) {
        from.handler(data -> {
            to.write(data);
            if (to.writeQueueFull()) {
                from.pause();
                to.drainHandler(v -> from.resume());
            }
        });
        from.closeHandler(v -> to.close());
        from.exceptionHandler(e -> to.close());
    }

    private void pipeSocketAndTunnel(NetSocket client, DNodeTunnel tunnel) {
        Context ctx = vertx.getOrCreateContext();
        client.handler(buf -> {
            tunnel.write(buf);
            if (client.writeQueueFull()) {
                client.pause();
                client.drainHandler(v -> client.resume());
            }
        });
        client.closeHandler(v -> tunnel.close());
        client.exceptionHandler(e -> tunnel.close());
        tunnel.handler(buf -> ctx.runOnContext(v -> {
            if (!client.writeQueueFull()) {
                client.write(buf);
            } else {
                client.write(buf);
                client.pause();
                client.drainHandler(x -> client.resume());
            }
        }));
        tunnel.closeHandler(v -> ctx.runOnContext(x -> client.close()));
    }

    private void copyRequestHeaders(HttpServerRequest from, io.vertx.core.MultiMap to) {
        from.headers().forEach(header -> {
            if (!isHopByHop(header.getKey())) {
                to.add(header.getKey(), header.getValue());
            }
        });
    }

    private void copyResponseHeaders(io.vertx.core.MultiMap from, HttpServerResponse to) {
        from.forEach(header -> {
            if (!isHopByHop(header.getKey())) {
                to.headers().add(header.getKey(), header.getValue());
            }
        });
    }

    private static boolean isHopByHop(String name) {
        return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean mayHaveBody(HttpMethod method) {
        return method != HttpMethod.GET && method != HttpMethod.HEAD
                && method != HttpMethod.OPTIONS && method != HttpMethod.TRACE;
    }

    private void failGateway(HttpServerRequest req, String msg) {
        HttpServerResponse resp = req.response();
        if (resp.ended() || resp.closed() || resp.headWritten()) {
            resp.close();
            return;
        }
        resp.setStatusCode(502).end(msg);
    }

    private static void safeClose(HttpServerResponse resp) {
        if (resp.ended() || resp.closed()) {
            return;
        }
        try {
            if (resp.headWritten()) {
                resp.close();
            } else {
                resp.setStatusCode(502).end("Bad Gateway: Unable to reach target");
            }
        } catch (Exception e) {
            resp.close();
        }
    }

    private String resolveClientIp(HttpServerRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            return DNodeServer.normalizeIp(xff.split(",")[0].trim());
        }
        return req.remoteAddress() == null ? "" : req.remoteAddress().host();
    }

    static HostPort parseConnectTarget(String uri) {
        if (StringUtils.isBlank(uri)) {
            return null;
        }
        String raw = uri.trim();
        try {
            if (raw.startsWith("[")) {
                int end = raw.indexOf(']');
                if (end < 0) {
                    return null;
                }
                String host = raw.substring(1, end);
                int port = 443;
                if (end + 1 < raw.length() && raw.charAt(end + 1) == ':') {
                    port = Integer.parseInt(raw.substring(end + 2));
                }
                return new HostPort(host, port, true);
            }
            int colon = raw.lastIndexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) {
                return null;
            }
            String host = raw.substring(0, colon);
            int port = Integer.parseInt(raw.substring(colon + 1));
            return new HostPort(host, port, port == 443);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static HostPort resolveHttpTarget(HttpServerRequest req) {
        String hostHeader = req.getHeader("Host");
        String uriStr = req.uri();
        String host = null;
        int port = -1;
        boolean ssl = false;
        try {
            if (uriStr != null && (uriStr.startsWith("http://") || uriStr.startsWith("https://"))) {
                URI uri = URI.create(uriStr);
                host = uri.getHost();
                port = uri.getPort();
                ssl = "https".equalsIgnoreCase(uri.getScheme());
            }
        } catch (Exception ignored) {
        }
        HostPort fromHeader = parseHostHeader(hostHeader, ssl);
        if (fromHeader != null) {
            if (host == null) {
                host = fromHeader.host;
            }
            if (port <= 0) {
                port = fromHeader.port;
            }
            ssl = ssl || fromHeader.ssl;
        }
        if (StringUtils.isBlank(host)) {
            return null;
        }
        if (port <= 0) {
            port = ssl ? 443 : 80;
        }
        return new HostPort(host, port, ssl);
    }

    static HostPort parseHostHeader(String hostHeader, boolean sslHint) {
        if (StringUtils.isBlank(hostHeader)) {
            return null;
        }
        String raw = hostHeader.trim();
        try {
            if (raw.startsWith("[")) {
                int end = raw.indexOf(']');
                if (end < 0) {
                    return null;
                }
                String host = raw.substring(1, end);
                int port = -1;
                if (end + 1 < raw.length() && raw.charAt(end + 1) == ':') {
                    port = Integer.parseInt(raw.substring(end + 2));
                }
                boolean ssl = sslHint || port == 443;
                if (port <= 0) {
                    port = ssl ? 443 : 80;
                }
                return new HostPort(host, port, ssl);
            }
            int colon = raw.lastIndexOf(':');
            if (colon > 0 && raw.indexOf(':') == colon) {
                String host = raw.substring(0, colon);
                int port = Integer.parseInt(raw.substring(colon + 1));
                return new HostPort(host, port, sslHint || port == 443);
            }
            return new HostPort(raw, sslHint ? 443 : 80, sslHint);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String originForm(String uriString) {
        if (StringUtils.isBlank(uriString)) {
            return "/";
        }
        if (uriString.startsWith("/") && !uriString.startsWith("//")) {
            return uriString;
        }
        try {
            URI uri = URI.create(uriString);
            String path = uri.getRawPath();
            if (StringUtils.isBlank(path)) {
                path = "/";
            }
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }
            return path;
        } catch (Exception e) {
            return uriString;
        }
    }

    static ProxyOptions buildUpstreamProxy(JsonObject proxyPreConf) {
        if (proxyPreConf == null) {
            return null;
        }
        String host = firstNonBlank(proxyPreConf.getString("host"), proxyPreConf.getString("ip"));
        if (StringUtils.isBlank(host)) {
            return null;
        }
        JsonObject po = proxyPreConf.copy();
        po.put("host", host);
        String type = po.getString("type");
        if (StringUtils.isNotBlank(type)) {
            po.put("type", type.toUpperCase(Locale.ROOT));
        }
        try {
            ProxyOptions options = new ProxyOptions(po);
            if (options.getType() == null) {
                options.setType(ProxyType.HTTP);
            }
            return options;
        } catch (Exception e) {
            LOGGER.warn("Invalid proxy-pre config: {}", e.toString());
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
    }

    /**
     * 从 URL 中提取端口号
     *
     * @param urlString URL 字符串
     * @return 提取的端口号，如果没有指定端口，则返回默认端口
     */
    public static int extractPortFromUrl(String urlString) {
        try {
            URI uri = new URI(urlString);
            int port = uri.getPort();
            if (port == -1) {
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    port = 443;
                } else {
                    port = 80;
                }
            }
            return port;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public void stop() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (netClient != null) {
            netClient.close();
        }
    }

    static final class HostPort {
        final String host;
        final int port;
        final boolean ssl;

        HostPort(String host, int port, boolean ssl) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
        }
    }

    /**
     * 将节点隧道上的 HTTP/1.1 响应解析回客户端 HttpServerResponse。
     */
    static final class HttpResponseBridge {
        private final HttpServerResponse resp;
        private final DNodeTunnel tunnel;
        private Buffer acc = Buffer.buffer();
        private boolean headersDone;
        private boolean ended;
        private long remaining = -1;
        private Dechunker dechunker;

        HttpResponseBridge(HttpServerResponse resp, DNodeTunnel tunnel) {
            this.resp = resp;
            this.tunnel = tunnel;
        }

        void accept(Buffer data) {
            if (ended || data == null || data.length() == 0) {
                return;
            }
            if (!headersDone) {
                acc.appendBuffer(data);
                int idx = indexOf(acc, CRLFCRLF);
                if (idx < 0) {
                    return;
                }
                Buffer headerPart = acc.getBuffer(0, idx);
                Buffer rest = acc.length() > idx + 4 ? acc.getBuffer(idx + 4, acc.length()) : Buffer.buffer();
                acc = Buffer.buffer();
                if (!applyHeaders(headerPart)) {
                    fail();
                    return;
                }
                headersDone = true;
                if (rest.length() > 0) {
                    acceptBody(rest);
                }
            } else {
                acceptBody(data);
            }
        }

        void onTunnelClose() {
            if (ended) {
                return;
            }
            if (!headersDone) {
                fail();
                return;
            }
            endResp();
        }

        private boolean applyHeaders(Buffer headerPart) {
            String text = headerPart.toString(StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n");
            if (lines.length == 0) {
                return false;
            }
            String[] status = lines[0].split(" ", 3);
            if (status.length < 2) {
                return false;
            }
            int code;
            try {
                code = Integer.parseInt(status[1]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (resp.ended() || resp.closed()) {
                return false;
            }
            resp.setStatusCode(code);
            if (status.length > 2) {
                resp.setStatusMessage(status[2]);
            }
            boolean chunked = false;
            Long contentLength = null;
            for (int i = 1; i < lines.length; i++) {
                int sep = lines[i].indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String k = lines[i].substring(0, sep).trim();
                String v = lines[i].substring(sep + 1).trim();
                if ("transfer-encoding".equalsIgnoreCase(k) && v.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                    continue;
                }
                if (isHopByHop(k)) {
                    continue;
                }
                if ("content-length".equalsIgnoreCase(k)) {
                    try {
                        contentLength = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
                resp.headers().add(k, v);
            }
            if (chunked) {
                remaining = -2;
                resp.setChunked(true);
                dechunker = new Dechunker(resp, this::endResp);
            } else if (contentLength != null) {
                remaining = contentLength;
                if (remaining == 0) {
                    endResp();
                }
            } else {
                remaining = -1;
                resp.setChunked(true);
            }
            return true;
        }

        private void acceptBody(Buffer data) {
            if (ended) {
                return;
            }
            if (remaining == -2 && dechunker != null) {
                dechunker.feed(data);
                return;
            }
            if (remaining == -1) {
                resp.write(data);
                return;
            }
            int len = data.length();
            if (len >= remaining) {
                resp.end(data.getBuffer(0, (int) remaining));
                ended = true;
                tunnel.close();
            } else {
                resp.write(data);
                remaining -= len;
            }
        }

        private void endResp() {
            if (ended) {
                return;
            }
            ended = true;
            if (!resp.ended() && !resp.closed()) {
                resp.end();
            }
            tunnel.close();
        }

        private void fail() {
            if (ended) {
                return;
            }
            ended = true;
            if (!resp.ended() && !resp.closed() && !resp.headWritten()) {
                resp.setStatusCode(502).end("Bad Gateway: Unable to reach target");
            } else {
                resp.close();
            }
            tunnel.close();
        }
    }

    private static final byte[] CRLFCRLF = new byte[]{'\r', '\n', '\r', '\n'};

    static int indexOf(Buffer buf, byte[] needle) {
        int max = buf.length() - needle.length;
        outer:
        for (int i = 0; i <= max; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (buf.getByte(i + j) != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static final class Dechunker {
        private final HttpServerResponse resp;
        private final Runnable onComplete;
        private Buffer buf = Buffer.buffer();
        private int need = -1;

        Dechunker(HttpServerResponse resp, Runnable onComplete) {
            this.resp = resp;
            this.onComplete = onComplete;
        }

        void feed(Buffer in) {
            buf.appendBuffer(in);
            while (true) {
                if (need < 0) {
                    int lineEnd = indexOf(buf, new byte[]{'\r', '\n'});
                    if (lineEnd < 0) {
                        return;
                    }
                    String sizeLine = buf.getString(0, lineEnd, "US-ASCII").trim();
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) {
                        sizeLine = sizeLine.substring(0, semi).trim();
                    }
                    int size;
                    try {
                        size = Integer.parseInt(sizeLine, 16);
                    } catch (NumberFormatException e) {
                        onComplete.run();
                        return;
                    }
                    buf = sliceFrom(buf, lineEnd + 2);
                    if (size == 0) {
                        onComplete.run();
                        return;
                    }
                    need = size + 2;
                }
                if (buf.length() < need) {
                    return;
                }
                Buffer chunk = buf.getBuffer(0, need - 2);
                buf = sliceFrom(buf, need);
                if (!resp.ended() && !resp.closed()) {
                    resp.write(chunk);
                }
                need = -1;
            }
        }

        private static Buffer sliceFrom(Buffer src, int offset) {
            if (offset >= src.length()) {
                return Buffer.buffer();
            }
            return src.getBuffer(offset, src.length());
        }
    }
}
