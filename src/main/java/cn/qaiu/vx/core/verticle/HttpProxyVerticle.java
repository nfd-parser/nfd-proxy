package cn.qaiu.vx.core.verticle;

import cn.qaiu.vx.core.dnode.DNodeClient;
import cn.qaiu.vx.core.dnode.DNodeServer;
import cn.qaiu.vx.core.dnode.DNodeTunnel;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
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
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    static final int DEFAULT_MAX_CONNECTIONS = 1024;

    /** 所有实例共享：正在转发的 CONNECT 隧道 + HTTP 请求数 */
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private HttpClient httpClient;
    private NetClient netClient;

    private JsonObject proxyPreConf;
    private JsonObject proxyServerConf;
    private int maxConnections;

    public static int activeConnections() {
        return ACTIVE.get();
    }

    @Override
    public void start(Promise<Void> startPromise) {
        JsonObject global = (JsonObject) vertx.sharedData().getLocalMap(LOCAL).get(GLOBAL_CONFIG);
        proxyServerConf = global.getJsonObject("proxy-server");
        proxyPreConf = global.getJsonObject("proxy-pre");
        Integer serverPort = proxyServerConf.getInteger("port");
        int idleTimeout = proxyServerConf.getInteger("idleTimeout", DEFAULT_IDLE_TIMEOUT_SECONDS);
        int connectTimeout = proxyServerConf.getInteger("connectTimeout", DEFAULT_CONNECT_TIMEOUT_MS);
        maxConnections = proxyServerConf.getInteger("maxConnections", DEFAULT_MAX_CONNECTIONS);
        String host = StringUtils.defaultIfBlank(proxyServerConf.getString("host"), "0.0.0.0");

        ProxyOptions proxyOptions = buildUpstreamProxy(proxyPreConf);

        HttpClientOptions httpClientOptions = new HttpClientOptions()
                .setMaxPoolSize(64)
                .setMaxWaitQueueSize(256)
                .setKeepAlive(true)
                .setConnectTimeout(connectTimeout)
                .setIdleTimeout(idleTimeout)
                .setTcpKeepAlive(true)
                .setTcpNoDelay(true)
                .setTrustAll(true);
        if (proxyOptions != null) {
            httpClientOptions.setProxyOptions(proxyOptions);
        }
        httpClient = vertx.createHttpClient(httpClientOptions);

        // 认证走 Proxy-Authorization；明文端口不启用 TLS ClientAuth（与原运行行为一致）
        HttpServer server = vertx.createHttpServer(new HttpServerOptions()
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setIdleTimeout(idleTimeout));
        server.requestHandler(this::handleClientRequest);

        NetClientOptions netClientOptions = new NetClientOptions()
                .setConnectTimeout(connectTimeout)
                .setIdleTimeout(idleTimeout)
                .setTcpKeepAlive(true)
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

        server.listen(serverPort, host, ar -> {
            if (ar.succeeded()) {
                LOGGER.info("HTTP Proxy server started on port {}", ar.result().actualPort());
                startPromise.complete();
            } else {
                LOGGER.error("Failed to start HTTP Proxy server: {}", ar.cause().toString());
                startPromise.fail(ar.cause());
            }
        });
    }

    /**
     * 占用一个转发名额；超出 maxConnections 时返回 null。返回的 Runnable 可重复调用，只释放一次。
     */
    private Runnable acquire() {
        if (ACTIVE.incrementAndGet() > maxConnections) {
            ACTIVE.decrementAndGet();
            return null;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                ACTIVE.decrementAndGet();
            }
        };
    }

    private void handleConnectRequest(HttpServerRequest clientRequest) {
        HostPort target = parseConnectTarget(clientRequest.uri());
        if (target == null) {
            clientRequest.response().setStatusCode(400).end("Bad Request: Invalid URI format");
            return;
        }
        Runnable release = acquire();
        if (release == null) {
            rejectBusy(clientRequest);
            return;
        }
        clientRequest.pause();
        Context ctx = vertx.getOrCreateContext();
        if (routeViaDnode(clientRequest, target, true, ctx, release)) {
            return;
        }
        directConnect(clientRequest, target, ctx, release);
    }

    private void directConnect(HttpServerRequest clientRequest, HostPort target, Context ctx, Runnable release) {
        // 隧道两端必须在请求所在的同一 event loop 上，否则会重新引入跨线程锁竞争
        onContext(ctx, () -> netClient.connect(target.port, target.host, connectionAttempt -> {
            if (connectionAttempt.succeeded()) {
                NetSocket targetSocket = connectionAttempt.result();
                clientRequest.toNetSocket().onComplete(clientSocketAttempt -> {
                    if (clientSocketAttempt.succeeded()) {
                        pipeSockets(clientSocketAttempt.result(), targetSocket, release);
                    } else {
                        LOGGER.debug("Failed to upgrade client connection to socket: {}",
                                clientSocketAttempt.cause().toString());
                        targetSocket.close();
                        release.run();
                    }
                });
            } else {
                LOGGER.debug("Failed to connect to target: {}", connectionAttempt.cause().toString());
                release.run();
                failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
            }
        }));
    }

    private void upgradeConnectToTunnel(HttpServerRequest clientRequest, DNodeTunnel tunnel, Context ctx,
                                        Runnable release) {
        onContext(ctx, () -> clientRequest.toNetSocket().onComplete(ar -> {
            if (ar.failed()) {
                LOGGER.debug("Failed to upgrade client connection to socket: {}", ar.cause().toString());
                tunnel.close();
                release.run();
                return;
            }
            pipeSocketAndTunnel(ar.result(), tunnel, ctx, release);
        }));
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
        String username = proxyServerConf.getString("username");
        if (StringUtils.isBlank(username)) {
            return true;
        }
        String password = StringUtils.defaultString(proxyServerConf.getString("password"));
        if (checkBasicAuth(clientRequest.headers().get("Proxy-Authorization"), username, password)) {
            return true;
        }
        LOGGER.info("proxy auth failed from {}",
                clientRequest.remoteAddress() == null ? "-" : clientRequest.remoteAddress().host());
        clientRequest.response().setStatusCode(403).end();
        return false;
    }

    static boolean checkBasicAuth(String header, String username, String password) {
        if (header == null) {
            return false;
        }
        String value = header.trim();
        if (value.length() < 6 || !value.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(value.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        boolean userOk = MessageDigest.isEqual(
                decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8),
                username.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(
                decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
        return userOk & passOk;
    }

    private void handleHttpRequest(HttpServerRequest clientRequest) {
        HostPort target = resolveHttpTarget(clientRequest);
        if (target == null) {
            clientRequest.response().setStatusCode(400).end("Host header is missing");
            return;
        }
        Runnable release = acquire();
        if (release == null) {
            rejectBusy(clientRequest);
            return;
        }
        clientRequest.response().endHandler(v -> release.run());
        clientRequest.pause();
        Context ctx = vertx.getOrCreateContext();
        if (routeViaDnode(clientRequest, target, false, ctx, release)) {
            return;
        }
        forwardHttpDirect(clientRequest, target, release);
    }

    private void rejectBusy(HttpServerRequest clientRequest) {
        LOGGER.warn("proxy busy, active={} max={}", ACTIVE.get(), maxConnections);
        clientRequest.response()
                .setStatusCode(503)
                .putHeader(HttpHeaders.CONNECTION, "close")
                .end("Service Unavailable: too many connections");
    }

    private static void onContext(Context ctx, Runnable task) {
        if (Vertx.currentContext() == ctx) {
            task.run();
        } else {
            ctx.runOnContext(v -> task.run());
        }
    }

    /**
     * @return true 表示已接手（走节点或已返回 502），false 表示调用方应本机直连
     */
    private boolean routeViaDnode(HttpServerRequest clientRequest, HostPort target, boolean connect,
                                  Context ctx, Runnable release) {
        DNodeServer dnode = DNodeServer.get();
        if (!dnode.isActive()) {
            return false;
        }
        if (!dnode.hasNodes()) {
            if (dnode.isFallbackDirect()) {
                return false;
            }
            release.run();
            failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
            return true;
        }
        dnode.openTunnel(target.host, target.port, resolveClientIp(clientRequest))
                .onSuccess(tunnel -> {
                    if (connect) {
                        upgradeConnectToTunnel(clientRequest, tunnel, ctx, release);
                    } else {
                        onContext(ctx, () -> forwardHttpViaTunnel(clientRequest, target, tunnel, release));
                    }
                })
                .onFailure(err -> {
                    LOGGER.debug("DNode {} fail {}:{} : {}", connect ? "CONNECT" : "HTTP",
                            target.host, target.port, err.toString());
                    if (dnode.isFallbackDirect()) {
                        if (connect) {
                            directConnect(clientRequest, target, ctx, release);
                        } else {
                            onContext(ctx, () -> forwardHttpDirect(clientRequest, target, release));
                        }
                    } else {
                        onContext(ctx, () -> {
                            release.run();
                            failGateway(clientRequest, "Bad Gateway: Unable to connect to target");
                        });
                    }
                });
        return true;
    }

    /**
     * release 除了挂在 response endHandler 上，还要在每个失败出口显式调用：
     * 客户端 RST 后 Vert.x 4.5.6 只回调 response exceptionHandler，之后 close() 不再触发 endHandler/closeHandler。
     */
    private void forwardHttpDirect(HttpServerRequest clientRequest, HostPort target, Runnable release) {
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
                    clientResp.closeHandler(v -> {
                        upstream.reset();
                        release.run();
                    });
                    upstream.response()
                            .onSuccess(upstreamResp -> {
                                if (clientResp.ended() || clientResp.closed()) {
                                    upstreamResp.request().connection().close();
                                    release.run();
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
                                relay(upstreamResp, clientResp).onFailure(err -> {
                                    LOGGER.debug("relay response failed: {}", err.toString());
                                    upstream.reset();
                                    safeClose(clientResp);
                                    release.run();
                                });
                            })
                            .onFailure(err -> {
                                LOGGER.debug("upstream response failed: {}", err.toString());
                                failGateway(clientRequest, "Bad Gateway: Unable to reach target");
                                release.run();
                            });

                    if (isExpectContinue(clientRequest)) {
                        // 等上游决定是否接收正文，再把 100 转给客户端
                        upstream.continueHandler(v -> {
                            if (!clientResp.headWritten() && !clientResp.closed()) {
                                clientResp.writeContinue();
                            }
                        });
                        upstream.sendHead();
                    }
                    relay(clientRequest, upstream).onFailure(err -> {
                        LOGGER.debug("relay request failed: {}", err.toString());
                        upstream.reset();
                        failGateway(clientRequest, "Bad Gateway: Unable to reach target");
                        release.run();
                    });
                })
                .onFailure(err -> {
                    LOGGER.warn("Failed to send proxy request: {}", err.toString());
                    failGateway(clientRequest, "Bad Gateway: Request failed");
                    release.run();
                });
    }

    /**
     * 带背压的单向转发，替代 ReadStream.pipeTo：目标流被对端 reset/关闭后，
     * PipeImpl 会在同一回调里对已结束的流调用 writeQueueFull() 并抛出 IllegalStateException。
     * 这里对每次写做保护，失败只上报一次，并把剩余数据丢弃。
     */
    static Future<Void> relay(ReadStream<Buffer> src, WriteStream<Buffer> dst) {
        Promise<Void> done = Promise.promise();
        src.pause();
        src.handler(buf -> {
            if (done.future().isComplete()) {
                return;
            }
            try {
                dst.write(buf).onFailure(done::tryFail);
                if (dst.writeQueueFull()) {
                    src.pause();
                    dst.drainHandler(v -> src.resume());
                }
            } catch (IllegalStateException e) {
                done.tryFail(e);
            }
        });
        src.endHandler(v -> {
            if (done.future().isComplete()) {
                return;
            }
            try {
                dst.end().onComplete(done);
            } catch (IllegalStateException e) {
                done.tryFail(e);
            }
        });
        src.exceptionHandler(done::tryFail);
        dst.exceptionHandler(done::tryFail);
        done.future().onFailure(err -> {
            try {
                src.handler(null);
                src.resume();
            } catch (IllegalStateException ignored) {
                // 源流已结束
            }
        });
        src.resume();
        return done.future();
    }

    private static boolean isExpectContinue(HttpServerRequest req) {
        String expect = req.getHeader(HttpHeaders.EXPECT);
        return expect != null && "100-continue".equalsIgnoreCase(expect.trim());
    }

    private void forwardHttpViaTunnel(HttpServerRequest clientRequest, HostPort target, DNodeTunnel tunnel,
                                      Runnable release) {
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
        tunnel.closeHandler(v -> ctx.runOnContext(x -> {
            bridge.onTunnelClose();
            release.run();
        }));
        clientResp.closeHandler(v -> tunnel.close());
        clientResp.exceptionHandler(err -> tunnel.close());
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

    private void pipeSockets(NetSocket client, NetSocket target, Runnable release) {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeBoth = () -> {
            if (closed.compareAndSet(false, true)) {
                client.close();
                target.close();
                release.run();
            }
        };
        pump(client, target, closeBoth);
        pump(target, client, closeBoth);
    }

    private static void pump(NetSocket from, NetSocket to, Runnable closeBoth) {
        from.handler(data -> {
            to.write(data);
            if (to.writeQueueFull()) {
                from.pause();
                to.drainHandler(v -> from.resume());
            }
        });
        // closeHandler 在对端断开时立即触发，此时暂停中的 from 可能还有未投递的数据；
        // endHandler 在缓冲数据全部交给 handler 之后才触发
        from.endHandler(v -> closeBoth.run());
        from.exceptionHandler(e -> closeBoth.run());
    }

    private void pipeSocketAndTunnel(NetSocket client, DNodeTunnel tunnel, Context ctx, Runnable release) {
        client.handler(tunnel::write);
        client.closeHandler(v -> {
            tunnel.close();
            release.run();
        });
        client.exceptionHandler(e -> client.close());
        tunnel.handler(buf -> ctx.runOnContext(v -> client.write(buf)));
        tunnel.closeHandler(v -> ctx.runOnContext(x -> {
            client.close();
            release.run();
        }));
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
        // 关闭连接，否则 Vert.x 会继续读完并丢弃客户端剩余的请求体
        resp.setStatusCode(502)
                .putHeader(HttpHeaders.CONNECTION, "close")
                .end(msg)
                .onComplete(v -> req.connection().close());
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
