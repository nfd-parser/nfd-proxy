package cn.qaiu.vx.core.verticle;

import cn.qaiu.vx.core.Deploy;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.qaiu.vx.core.util.ConfigConstant.GLOBAL_CONFIG;
import static cn.qaiu.vx.core.util.ConfigConstant.LOCAL;

/**
 * 以生产相同的部署参数（{@link Deploy#proxyDeploymentOptions}）启动代理。
 */
final class ProxyFixture implements AutoCloseable {

    static final String USER = "nfduser";
    static final String PASS = "S3cret-pass-for-test";
    static final String AUTH = basic(USER + ":" + PASS);

    final Vertx vertx;
    final int port;

    private ProxyFixture(Vertx vertx, int port) {
        this.vertx = vertx;
        this.port = port;
    }

    static ProxyFixture start(JsonObject proxyServerOverrides) throws Exception {
        int port = TestSupport.freePort();
        JsonObject proxyServer = new JsonObject()
                .put("port", port)
                .put("host", "127.0.0.1")
                .put("username", USER)
                .put("password", PASS)
                .mergeIn(proxyServerOverrides);
        JsonObject global = new JsonObject()
                .put("proxy-server", proxyServer)
                .put("dnode-server", new JsonObject().put("enabled", false))
                .put("dnode-client", new JsonObject().put("enabled", false));
        Vertx vertx = Vertx.vertx();
        vertx.sharedData().getLocalMap(LOCAL).put(GLOBAL_CONFIG, global);
        vertx.deployVerticle(HttpProxyVerticle.class, Deploy.proxyDeploymentOptions(2))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        return new ProxyFixture(vertx, port);
    }

    static String basic(String userPass) {
        return "Basic " + Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
    }

    Socket open() throws IOException {
        return TestSupport.connect(port);
    }

    /** 发起 CONNECT，成功后返回已建立隧道的套接字。 */
    Socket tunnel(int targetPort) throws IOException {
        Socket s = open();
        s.setSoTimeout(15_000);
        s.getOutputStream().write(("CONNECT 127.0.0.1:" + targetPort + " HTTP/1.1\r\nHost: 127.0.0.1:" + targetPort
                + "\r\nProxy-Authorization: " + AUTH + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
        Map<String, String> h = new LinkedHashMap<>();
        int status = TestSupport.readStatus(s.getInputStream(), h);
        if (status != 200) {
            s.close();
            throw new IOException("CONNECT returned " + h.get(":status-line"));
        }
        return s;
    }

    static int connectStatus(ProxyFixture proxy, int targetPort) throws IOException {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(15_000);
            s.getOutputStream().write(("CONNECT 127.0.0.1:" + targetPort + " HTTP/1.1\r\nHost: 127.0.0.1:"
                    + targetPort + "\r\nProxy-Authorization: " + AUTH + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return TestSupport.readStatus(s.getInputStream(), new LinkedHashMap<>());
        }
    }

    @Override
    public void close() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    static SSLContext serverTls(Path dir) throws Exception {
        Path ks = dir.resolve("upstream.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=127.0.0.1", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    static SSLSocket clientTls(Socket tunnel, int port) throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(tunnel, "127.0.0.1", port, true);
        ssl.startHandshake();
        return ssl;
    }
}
