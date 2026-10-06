package cn.qaiu.vx.core.dnode;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNode 控制面：节点 WebSocket 接入 + 隧道复用。
 * 默认不启动，由配置 dnode-server.enabled 打开。
 */
public final class DNodeServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DNodeServer.class);
    private static final DNodeServer INSTANCE = new DNodeServer();

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final String DEFAULT_PATH = "/ws/node";

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger tidSeq = new AtomicInteger();
    private final Map<String, NodeSlot> nodes = new ConcurrentHashMap<>();
    private final Map<Integer, DNodeTunnel> tunnels = new ConcurrentHashMap<>();
    private final Map<String, GeoEntry> geoCache = new ConcurrentHashMap<>();

    private volatile Vertx vertx;
    private volatile HttpServer httpServer;
    private volatile HttpClient geoClient;
    private volatile JsonObject conf = new JsonObject();
    private volatile boolean fallbackDirect = true;
    private volatile String wsPath = DEFAULT_PATH;
    private volatile String secret = "";
    private volatile String adminToken = "";
    private final long startMs = System.currentTimeMillis();

    private DNodeServer() {
    }

    public static DNodeServer get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return started.get();
    }

    public boolean hasNodes() {
        return !nodes.isEmpty();
    }

    public boolean isFallbackDirect() {
        return fallbackDirect;
    }

    private volatile Promise<Void> listenPromise;

    /**
     * 幂等启动。多 verticle 实例并发调用时只有第一次会绑定端口。
     * 返回的 Future 在 listen 成功（或未启用）后完成，便于随后启动 dnode-client。
     */
    public Future<Void> start(Vertx vertx, JsonObject conf) {
        if (conf == null || !conf.getBoolean("enabled", false)) {
            return Future.succeededFuture();
        }
        synchronized (this) {
            if (listenPromise != null) {
                return listenPromise.future();
            }
            listenPromise = Promise.promise();
        }
        if (!started.compareAndSet(false, true)) {
            return listenPromise.future();
        }
        this.vertx = vertx;
        this.conf = conf;
        this.fallbackDirect = conf.getBoolean("fallback-direct", true);
        this.wsPath = StringUtils.defaultIfBlank(conf.getString("path"), DEFAULT_PATH);
        this.secret = StringUtils.defaultString(conf.getString("secret"));
        this.adminToken = StringUtils.defaultString(conf.getString("admin-token"));
        int port = conf.getInteger("port", 9000);

        this.geoClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(4000)
                .setIdleTimeout(8));

        HttpServerOptions options = new HttpServerOptions()
                .setMaxWebSocketFrameSize(64 * 1024)
                .setMaxWebSocketMessageSize(2 * 1024 * 1024)
                .setTcpNoDelay(true);
        this.httpServer = vertx.createHttpServer(options);
        this.httpServer.requestHandler(this::handleHttp);
        this.httpServer.listen(port, ar -> {
            if (ar.succeeded()) {
                LOGGER.info("DNode server started on port {} path={} fallbackDirect={}",
                        port, wsPath, fallbackDirect);
                listenPromise.tryComplete();
            } else {
                started.set(false);
                LOGGER.error("Failed to start DNode server: {}", ar.cause().toString());
                listenPromise.tryFail(ar.cause());
            }
        });
        return listenPromise.future();
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        for (DNodeTunnel t : tunnels.values()) {
            t.close(false);
        }
        tunnels.clear();
        for (NodeSlot n : nodes.values()) {
            try {
                n.ws.close();
            } catch (Exception ignored) {
            }
        }
        nodes.clear();
        if (httpServer != null) {
            httpServer.close();
        }
        if (geoClient != null) {
            geoClient.close();
        }
    }

    /**
     * 通过下游节点建立到 host:port 的 TCP 隧道。
     */
    public Future<DNodeTunnel> openTunnel(String host, int port, String clientIp) {
        Promise<DNodeTunnel> promise = Promise.promise();
        if (!isActive() || nodes.isEmpty()) {
            promise.fail("no available node");
            return promise.future();
        }
        pickNode(clientIp).onComplete(pick -> {
            if (pick.failed() || StringUtils.isBlank(pick.result())) {
                promise.fail("no available node");
                return;
            }
            String connId = pick.result();
            NodeSlot node = nodes.get(connId);
            if (node == null || node.ws.isClosed()) {
                promise.fail("node gone");
                return;
            }
            int tid = nextTid();
            DNodeTunnel tunnel = new DNodeTunnel(tid, connId);
            tunnel.dstHost = host;
            tunnel.dstPort = port;
            tunnel.clientIp = clientIp;
            tunnel.nodeIp = node.realIp;
            tunnel.ws = node.ws;
            tunnel.nodeContext = node.context;
            tunnels.put(tid, tunnel);
            tunnel.freedHook = () -> freeTunnel(tid);
            node.load.incrementAndGet();
            node.totalTunnels.incrementAndGet();

            try {
                Buffer frame = DNodeProtocol.packConnectReq(tid, host, port);
                node.context.runOnContext(v -> {
                    if (!node.ws.isClosed()) {
                        node.ws.writeBinaryMessage(frame);
                    }
                });
            } catch (Exception e) {
                freeTunnel(tid);
                promise.fail(e);
                return;
            }

            long timerId = vertx.setTimer(CONNECT_TIMEOUT_MS, id -> {
                if (!tunnel.ready.get() && !tunnel.isClosed()) {
                    tunnel.connectPromise.tryFail("timeout");
                    freeTunnel(tid);
                }
            });
            tunnel.connectPromise.future().onComplete(ar -> {
                vertx.cancelTimer(timerId);
                if (ar.succeeded()) {
                    promise.complete(ar.result());
                } else {
                    freeTunnel(tid);
                    promise.fail(ar.cause());
                }
            });
        });
        return promise.future();
    }

    void freeTunnel(int tid) {
        DNodeTunnel t = tunnels.remove(tid);
        if (t == null) {
            return;
        }
        NodeSlot n = nodes.get(t.connId);
        if (n != null) {
            n.load.updateAndGet(v -> Math.max(0, v - 1));
            n.totalTx.addAndGet(t.txBytes);
            n.totalRx.addAndGet(t.rxBytes);
        }
        t.close(false);
    }

    private int nextTid() {
        return tidSeq.updateAndGet(i -> i >= Integer.MAX_VALUE - 1 ? 1 : i + 1);
    }

    private void handleHttp(HttpServerRequest req) {
        String path = req.path();
        if (path == null) {
            path = "/";
        }
        String upgrade = req.getHeader("Upgrade");
        if (wsPath.equals(path) && upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim())) {
            acceptNode(req);
            return;
        }
        if (HttpMethod.GET.equals(req.method()) && "/api/stats".equals(path)) {
            json(req.response(), 200, statsJson());
            return;
        }
        if (path.startsWith("/api/admin/")) {
            handleAdmin(req, path);
            return;
        }
        req.response().setStatusCode(404).end("Not Found");
    }

    private void acceptNode(HttpServerRequest req) {
        if (StringUtils.isNotBlank(secret)) {
            String token = bearerOrQuery(req, "token");
            if (!secret.equals(token)) {
                req.response().setStatusCode(401).end("Unauthorized");
                return;
            }
        }
        req.toWebSocket().onComplete(ar -> {
            if (ar.failed()) {
                LOGGER.warn("DNode websocket upgrade failed: {}", ar.cause().toString());
                return;
            }
            ServerWebSocket ws = ar.result();
            String connId = UUID.randomUUID().toString().replace("-", "");
            NodeSlot node = new NodeSlot();
            node.connId = connId;
            node.ws = ws;
            node.context = vertx.getOrCreateContext();
            node.deviceId = firstNonBlank(headerOrQuery(req, "X-Node-ID", "x-node-id"), connId);
            node.platform = firstNonBlank(headerOrQuery(req, "X-Platform", "x-platform"), "unknown");
            node.version = firstNonBlank(headerOrQuery(req, "X-Version", "x-version"), "");
            node.isDefault = isTruthy(headerOrQuery(req, "X-Default-Node", "x-default-node"));
            node.realIp = resolveRealIp(req);
            nodes.put(connId, node);
            LOGGER.info("DNode online conn={} platform={} ip={} default={}",
                    connId.substring(0, 8), node.platform, node.realIp, node.isDefault);
            lookupGeo(node.realIp).onSuccess(geo -> node.geo = geo);

            ws.binaryMessageHandler(buf -> onNodeFrame(buf));
            ws.textMessageHandler(txt -> {
                try {
                    JsonObject msg = new JsonObject(txt);
                    if ("ping".equals(msg.getString("type"))) {
                        ws.writeTextMessage(new JsonObject().put("type", "pong").encode());
                    }
                } catch (Exception ignored) {
                }
            });
            ws.closeHandler(v -> unregister(connId));
            ws.exceptionHandler(e -> {
                LOGGER.debug("DNode ws error conn={}: {}", connId.substring(0, 8), e.toString());
                unregister(connId);
            });
        });
    }

    private void unregister(String connId) {
        NodeSlot n = nodes.remove(connId);
        if (n == null) {
            return;
        }
        LOGGER.info("DNode offline conn={} ip={} tunnels={}",
                connId.substring(0, 8), n.realIp, n.totalTunnels.get());
        for (DNodeTunnel t : tunnels.values()) {
            if (connId.equals(t.connId)) {
                t.close(false);
                freeTunnel(t.id);
            }
        }
    }

    private void onNodeFrame(Buffer raw) {
        DNodeProtocol.Frame frame = DNodeProtocol.unpack(raw);
        if (frame == null) {
            return;
        }
        DNodeTunnel t = tunnels.get(frame.tid);
        if (t == null) {
            return;
        }
        switch (frame.type) {
            case DNodeProtocol.TYPE_CONNECT_OK -> t.onConnected();
            case DNodeProtocol.TYPE_CONNECT_FAIL -> {
                String reason = frame.payload.toString(StandardCharsets.UTF_8);
                LOGGER.debug("DNode CONNECT_FAIL tid={} reason={}", Integer.toHexString(frame.tid), reason);
                t.onConnectFail(reason);
                freeTunnel(frame.tid);
            }
            case DNodeProtocol.TYPE_DATA -> t.onData(frame.payload);
            case DNodeProtocol.TYPE_CLOSE -> {
                t.close(false);
                freeTunnel(frame.tid);
            }
            default -> {
            }
        }
    }

    private Future<String> pickNode(String clientIp) {
        if (nodes.isEmpty()) {
            return Future.succeededFuture(null);
        }
        Promise<String> promise = Promise.promise();
        lookupGeo(clientIp).onComplete(ar -> {
            Geo geo = ar.succeeded() ? ar.result() : Geo.EMPTY;
            String chosen = null;
            int bestTier = Integer.MAX_VALUE;
            int bestLoad = Integer.MAX_VALUE;
            for (NodeSlot n : nodes.values()) {
                if (n.ws.isClosed()) {
                    continue;
                }
                int tier = tierOf(geo, n);
                int load = n.load.get();
                if (tier < bestTier || (tier == bestTier && load < bestLoad)) {
                    bestTier = tier;
                    bestLoad = load;
                    chosen = n.connId;
                }
            }
            promise.complete(chosen);
        });
        return promise.future();
    }

    private static int tierOf(Geo client, NodeSlot n) {
        Geo ng = n.geo;
        if (client != null && ng != null) {
            if (StringUtils.isNotBlank(client.city) && client.city.equals(ng.city)) {
                return 0;
            }
            if (StringUtils.isNotBlank(client.province) && client.province.equals(ng.province)) {
                return 1;
            }
        }
        return n.isDefault ? 2 : 3;
    }

    private Future<Geo> lookupGeo(String ip) {
        String norm = normalizeIp(ip);
        if (StringUtils.isBlank(norm) || isPrivateIp(norm)) {
            return Future.succeededFuture(Geo.LAN);
        }
        GeoEntry cached = geoCache.get(norm);
        if (cached != null && System.currentTimeMillis() - cached.ts < 86_400_000L) {
            return Future.succeededFuture(cached.geo);
        }
        Promise<Geo> promise = Promise.promise();
        RequestOptions opts = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("whois.pconline.com.cn")
                .setPort(443)
                .setSsl(true)
                .setURI("/ipJson.shtml?ip=" + norm + "&json=true")
                .setTimeout(4000);
        geoClient.request(opts)
                .compose(req -> req.send())
                .onSuccess(resp -> resp.body().onComplete(bodyAr -> {
                    Geo geo = Geo.EMPTY;
                    if (bodyAr.succeeded()) {
                        geo = parsePconline(bodyAr.result());
                    }
                    if (geo == Geo.EMPTY || (StringUtils.isBlank(geo.city) && StringUtils.isBlank(geo.province))) {
                        lookupIpApi(norm, promise);
                        return;
                    }
                    geoCache.put(norm, new GeoEntry(geo));
                    promise.complete(geo);
                }))
                .onFailure(err -> lookupIpApi(norm, promise));
        return promise.future();
    }

    private void lookupIpApi(String ip, Promise<Geo> promise) {
        RequestOptions opts = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("ip-api.com")
                .setPort(80)
                .setSsl(false)
                .setURI("/json/" + ip + "?lang=zh-CN&fields=status,country,regionName,city,isp")
                .setTimeout(4000);
        geoClient.request(opts)
                .compose(req -> req.send())
                .onSuccess(resp -> resp.body().onComplete(bodyAr -> {
                    Geo geo = Geo.EMPTY;
                    if (bodyAr.succeeded()) {
                        geo = parseIpApi(bodyAr.result());
                    }
                    geoCache.put(ip, new GeoEntry(geo));
                    promise.tryComplete(geo);
                }))
                .onFailure(err -> promise.tryComplete(Geo.EMPTY));
    }

    private static Geo parsePconline(Buffer body) {
        try {
            String text = body.toString(Charset.forName("GBK"));
            JsonObject json = new JsonObject(text);
            String city = stripSuffix(json.getString("city", ""));
            String pro = stripSuffix(json.getString("pro", ""));
            String isp = StringUtils.defaultString(json.getString("addr"));
            if (StringUtils.isBlank(city) && StringUtils.isBlank(pro)) {
                return Geo.EMPTY;
            }
            return new Geo(city, pro, "中国", isp);
        } catch (Exception e) {
            return Geo.EMPTY;
        }
    }

    private static Geo parseIpApi(Buffer body) {
        try {
            JsonObject json = body.toJsonObject();
            if (!"success".equals(json.getString("status"))) {
                return Geo.EMPTY;
            }
            return new Geo(
                    stripSuffix(json.getString("city", "")),
                    stripSuffix(json.getString("regionName", "")),
                    json.getString("country", ""),
                    json.getString("isp", "")
            );
        } catch (Exception e) {
            return Geo.EMPTY;
        }
    }

    private static String stripSuffix(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[市省区县自治州盟]$", "").trim();
    }

    private void handleAdmin(HttpServerRequest req, String path) {
        if (!checkAdmin(req)) {
            json(req.response(), 401, new JsonObject().put("error", "Unauthorized"));
            return;
        }
        if (HttpMethod.GET.equals(req.method()) && "/api/admin/nodes".equals(path)) {
            json(req.response(), 200, statsJson());
            return;
        }
        if (HttpMethod.POST.equals(req.method()) && "/api/admin/nodes/kick-all".equals(path)) {
            int n = nodes.size();
            for (NodeSlot slot : nodes.values()) {
                kick(slot);
            }
            json(req.response(), 200, new JsonObject().put("ok", true).put("kicked", n));
            return;
        }
        if (HttpMethod.POST.equals(req.method()) && path.startsWith("/api/admin/node/") && path.endsWith("/kick")) {
            String id = path.substring("/api/admin/node/".length(), path.length() - "/kick".length());
            NodeSlot slot = findNode(id);
            if (slot == null) {
                json(req.response(), 404, new JsonObject().put("error", "node not found"));
                return;
            }
            kick(slot);
            json(req.response(), 200, new JsonObject().put("ok", true).put("conn_id", slot.connId));
            return;
        }
        json(req.response(), 404, new JsonObject().put("error", "not found"));
    }

    private NodeSlot findNode(String id) {
        if (nodes.containsKey(id)) {
            return nodes.get(id);
        }
        NodeSlot found = null;
        for (NodeSlot n : nodes.values()) {
            if (n.connId.startsWith(id) || n.deviceId.startsWith(id)) {
                if (found != null) {
                    return null;
                }
                found = n;
            }
        }
        return found;
    }

    private void kick(NodeSlot slot) {
        try {
            slot.ws.close((short) 1001, "kicked by admin");
        } catch (Exception ignored) {
        }
    }

    private boolean checkAdmin(HttpServerRequest req) {
        if (StringUtils.isBlank(adminToken)) {
            return false;
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)
                && adminToken.equals(auth.substring(7).trim())) {
            return true;
        }
        return adminToken.equals(req.getParam("token"));
    }

    private JsonObject statsJson() {
        JsonObject detail = new JsonObject();
        long tx = 0;
        long rx = 0;
        for (NodeSlot n : nodes.values()) {
            tx += n.totalTx.get();
            rx += n.totalRx.get();
            JsonObject geo = new JsonObject();
            if (n.geo != null) {
                geo.put("city", n.geo.city).put("province", n.geo.province)
                        .put("isp", n.geo.isp).put("country", n.geo.country);
            }
            detail.put(n.connId, new JsonObject()
                    .put("load", n.load.get())
                    .put("real_ip", n.realIp)
                    .put("device_id", n.deviceId)
                    .put("platform", n.platform)
                    .put("is_default", n.isDefault)
                    .put("since", n.since / 1000)
                    .put("total_tunnels", n.totalTunnels.get())
                    .put("total_tx", n.totalTx.get())
                    .put("total_rx", n.totalRx.get())
                    .mergeIn(geo));
        }
        return new JsonObject()
                .put("nodes", nodes.size())
                .put("tunnels", tunnels.size())
                .put("uptime_seconds", (System.currentTimeMillis() - startMs) / 1000)
                .put("total_tx", tx)
                .put("total_rx", rx)
                .put("detail", detail);
    }

    private static void json(HttpServerResponse resp, int code, JsonObject body) {
        resp.setStatusCode(code)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .putHeader("Access-Control-Allow-Origin", "*")
                .end(body.encode());
    }

    private static String bearerOrQuery(HttpServerRequest req, String queryKey) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return StringUtils.defaultString(req.getParam(queryKey));
    }

    private static String headerOrQuery(HttpServerRequest req, String header, String query) {
        String v = req.getHeader(header);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        return req.getParam(query);
    }

    private static String resolveRealIp(HttpServerRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            return normalizeIp(xff.split(",")[0].trim());
        }
        String q = req.getParam("x-real-ip");
        if (StringUtils.isNotBlank(q)) {
            return normalizeIp(q);
        }
        return req.remoteAddress() == null ? "" : req.remoteAddress().host();
    }

    public static String normalizeIp(String raw) {
        if (raw == null) {
            return "";
        }
        String ip = raw.trim();
        if (ip.startsWith("[") && ip.contains("]")) {
            ip = ip.substring(1, ip.indexOf(']'));
        } else if (ip.contains(".") && ip.contains(":")) {
            ip = ip.split(":")[0];
        }
        if (ip.toLowerCase(Locale.ROOT).startsWith("::ffff:") && ip.contains(".")) {
            ip = ip.substring(7);
        }
        return ip;
    }

    static boolean isPrivateIp(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isTruthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
    }

    private static final class NodeSlot {
        String connId;
        ServerWebSocket ws;
        io.vertx.core.Context context;
        String deviceId = "";
        String platform = "";
        String version = "";
        String realIp = "";
        boolean isDefault;
        Geo geo;
        final long since = System.currentTimeMillis();
        final AtomicInteger load = new AtomicInteger();
        final AtomicInteger totalTunnels = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong totalTx = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong totalRx = new java.util.concurrent.atomic.AtomicLong();
    }

    static final class Geo {
        static final Geo EMPTY = new Geo("", "", "", "");
        static final Geo LAN = new Geo("局域网", "", "LAN", "");
        final String city;
        final String province;
        final String country;
        final String isp;

        Geo(String city, String province, String country, String isp) {
            this.city = city == null ? "" : city;
            this.province = province == null ? "" : province;
            this.country = country == null ? "" : country;
            this.isp = isp == null ? "" : isp;
        }
    }

    private static final class GeoEntry {
        final Geo geo;
        final long ts = System.currentTimeMillis();

        GeoEntry(Geo geo) {
            this.geo = geo;
        }
    }
}
