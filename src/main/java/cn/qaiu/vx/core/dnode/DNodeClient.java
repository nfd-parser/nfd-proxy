package cn.qaiu.vx.core.dnode;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNode 客户端：作为出口节点接入远端控制面。
 * 默认不启动，由配置 dnode-client.enabled 打开。
 */
public final class DNodeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DNodeClient.class);
    private static final DNodeClient INSTANCE = new DNodeClient();
    private static final int[] RECONNECT_DELAYS = {2, 4, 8, 16, 30, 60};
    private static final int MAX_CONCURRENCY = 50;
    private static final String PLATFORM = "java";
    private static final String VERSION = "1.0-java";

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<Integer, TunnelWorker> tunnels = new ConcurrentHashMap<>();

    private volatile Vertx vertx;
    private volatile HttpClient wsHttpClient;
    private volatile NetClient netClient;
    private volatile WebSocket webSocket;
    private volatile io.vertx.core.Context wsContext;
    private volatile String serverUrl;
    private volatile String nodeId;
    private volatile String secret = "";
    private volatile boolean defaultNode;
    private volatile boolean sslInsecure;
    private volatile int reconnectAttempt;
    private volatile long pingTimerId = -1;

    private DNodeClient() {
    }

    public static DNodeClient get() {
        return INSTANCE;
    }

    public void start(Vertx vertx, JsonObject conf) {
        if (conf == null || !conf.getBoolean("enabled", false)) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        this.vertx = vertx;
        this.serverUrl = StringUtils.trimToEmpty(conf.getString("server"));
        this.secret = StringUtils.defaultString(conf.getString("secret"));
        this.defaultNode = conf.getBoolean("default-node", false);
        this.sslInsecure = conf.getBoolean("ssl-insecure", false);
        this.nodeId = StringUtils.trimToEmpty(conf.getString("node-id"));
        if (StringUtils.isBlank(nodeId)) {
            nodeId = loadOrCreateNodeId();
        }
        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            LOGGER.error("DNode client server url invalid: {}", serverUrl);
            started.set(false);
            return;
        }
        this.netClient = vertx.createNetClient(new NetClientOptions()
                .setConnectTimeout(10_000)
                .setTcpNoDelay(true)
                .setTrustAll(true));
        this.running.set(true);
        LOGGER.info("DNode client starting node_id={} server={}", shortId(nodeId), serverUrl);
        connect();
    }

    public void stop() {
        running.set(false);
        if (!started.compareAndSet(true, false)) {
            return;
        }
        cancelPing();
        closeTunnels();
        if (webSocket != null) {
            try {
                webSocket.close();
            } catch (Exception ignored) {
            }
        }
        if (wsHttpClient != null) {
            wsHttpClient.close();
        }
        if (netClient != null) {
            netClient.close();
        }
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(serverUrl);
        } catch (Exception e) {
            LOGGER.error("DNode client bad url: {}", serverUrl);
            return;
        }
        boolean ssl = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (ssl ? 443 : 80);
        String path = StringUtils.defaultIfBlank(uri.getRawPath(), "/ws/node");
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }

        if (wsHttpClient != null) {
            wsHttpClient.close();
        }
        wsHttpClient = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(ssl)
                .setTrustAll(sslInsecure)
                .setVerifyHost(!sslInsecure)
                .setConnectTimeout(15_000)
                .setIdleTimeout(0)
                .setMaxWebSocketFrameSize(64 * 1024)
                .setMaxWebSocketMessageSize(2 * 1024 * 1024));

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
                .setHost(uri.getHost())
                .setPort(port)
                .setURI(path)
                .setSsl(ssl)
                .setAllowOriginHeader(false)
                .addHeader("X-Node-ID", nodeId)
                .addHeader("X-Platform", PLATFORM)
                .addHeader("X-Version", VERSION)
                .addHeader("X-Default-Node", defaultNode ? "true" : "false");
        if (StringUtils.isNotBlank(secret)) {
            opts.addHeader("Authorization", "Bearer " + secret);
        }

        wsHttpClient.webSocket(opts, ar -> {
            if (ar.failed()) {
                LOGGER.warn("DNode client connect failed: {}", ar.cause().toString());
                scheduleReconnect();
                return;
            }
            WebSocket ws = ar.result();
            this.webSocket = ws;
            this.wsContext = vertx.getOrCreateContext();
            this.reconnectAttempt = 0;
            LOGGER.info("DNode client connected node_id={} server={}", shortId(nodeId), serverUrl);
            startPing(ws);
            ws.binaryMessageHandler(this::onFrame);
            ws.textMessageHandler(txt -> {
                try {
                    JsonObject msg = new JsonObject(txt);
                    if ("pong".equals(msg.getString("type"))) {
                        LOGGER.debug("DNode client heartbeat pong");
                    }
                } catch (Exception ignored) {
                }
            });
            ws.closeHandler(v -> {
                LOGGER.info("DNode client disconnected");
                cancelPing();
                closeTunnels();
                scheduleReconnect();
            });
            ws.exceptionHandler(e -> {
                LOGGER.debug("DNode client ws error: {}", e.toString());
                try {
                    ws.close();
                } catch (Exception ignored) {
                }
            });
        });
    }

    private void startPing(WebSocket ws) {
        cancelPing();
        pingTimerId = vertx.setPeriodic(25_000, id -> {
            if (ws != null && !ws.isClosed()) {
                ws.writeTextMessage(new JsonObject().put("type", "ping").encode());
            }
        });
    }

    private void cancelPing() {
        if (pingTimerId >= 0) {
            vertx.cancelTimer(pingTimerId);
            pingTimerId = -1;
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        int delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)];
        reconnectAttempt++;
        LOGGER.warn("DNode client reconnect in {}s (attempt {})", delay, reconnectAttempt);
        vertx.setTimer(delay * 1000L, id -> connect());
    }

    private void onFrame(Buffer raw) {
        DNodeProtocol.Frame frame = DNodeProtocol.unpack(raw);
        if (frame == null) {
            return;
        }
        switch (frame.type) {
            case DNodeProtocol.TYPE_CONNECT_REQ -> handleConnectReq(frame);
            case DNodeProtocol.TYPE_DATA -> {
                TunnelWorker w = tunnels.get(frame.tid);
                if (w != null) {
                    w.feed(frame.payload);
                }
            }
            case DNodeProtocol.TYPE_CLOSE -> {
                TunnelWorker w = tunnels.remove(frame.tid);
                if (w != null) {
                    w.close();
                }
            }
            default -> {
            }
        }
    }

    private void handleConnectReq(DNodeProtocol.Frame frame) {
        DNodeProtocol.ConnectTarget target = DNodeProtocol.parseConnectReq(frame.payload);
        if (target == null) {
            send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_CONNECT_FAIL,
                    "bad connect payload".getBytes(StandardCharsets.UTF_8)));
            return;
        }
        if (inflight.get() >= MAX_CONCURRENCY) {
            send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_CONNECT_FAIL,
                    "max concurrency".getBytes(StandardCharsets.UTF_8)));
            return;
        }
        LOGGER.info("DNode client new tunnel tid={} -> {}:{} active={}",
                Integer.toHexString(frame.tid), target.host, target.port, inflight.get() + 1);
        inflight.incrementAndGet();
        netClient.connect(target.port, target.host, ar -> {
            if (ar.failed()) {
                inflight.decrementAndGet();
                send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_CONNECT_FAIL,
                        String.valueOf(ar.cause()).getBytes(StandardCharsets.UTF_8)));
                return;
            }
            NetSocket sock = ar.result();
            TunnelWorker worker = new TunnelWorker(frame.tid, sock);
            tunnels.put(frame.tid, worker);
            send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_CONNECT_OK));
            sock.handler(buf -> send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_DATA, buf)));
            sock.closeHandler(v -> {
                tunnels.remove(frame.tid);
                inflight.updateAndGet(i -> Math.max(0, i - 1));
                send(DNodeProtocol.pack(frame.tid, DNodeProtocol.TYPE_CLOSE));
            });
            sock.exceptionHandler(e -> sock.close());
        });
    }

    private void send(Buffer frame) {
        WebSocket ws = this.webSocket;
        io.vertx.core.Context ctx = this.wsContext;
        if (ws == null || ws.isClosed()) {
            return;
        }
        Runnable write = () -> {
            if (!ws.isClosed()) {
                ws.writeBinaryMessage(frame);
            }
        };
        if (ctx == null) {
            write.run();
            return;
        }
        ctx.runOnContext(v -> write.run());
    }

    private void closeTunnels() {
        for (TunnelWorker w : tunnels.values()) {
            w.close();
        }
        tunnels.clear();
        inflight.set(0);
    }

    private String loadOrCreateNodeId() {
        Path path = Path.of("node_config.json");
        try {
            if (Files.exists(path)) {
                JsonObject json = new JsonObject(Files.readString(path));
                String id = json.getString("node_id");
                if (StringUtils.isNotBlank(id)) {
                    return id;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("read node_config.json failed: {}", e.toString());
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        try {
            JsonObject json = new JsonObject().put("node_id", id).put("server_url", serverUrl);
            Files.writeString(path, json.encodePrettily());
            LOGGER.info("DNode client wrote {}", path.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.debug("write node_config.json failed: {}", e.toString());
        }
        return id;
    }

    private static String shortId(String id) {
        return id != null && id.length() >= 8 ? id.substring(0, 8) : String.valueOf(id);
    }

    private static final class TunnelWorker {
        final int tid;
        final NetSocket socket;
        final AtomicBoolean closed = new AtomicBoolean();

        TunnelWorker(int tid, NetSocket socket) {
            this.tid = tid;
            this.socket = socket;
        }

        void feed(Buffer data) {
            if (!closed.get() && data != null && data.length() > 0) {
                socket.write(data);
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
