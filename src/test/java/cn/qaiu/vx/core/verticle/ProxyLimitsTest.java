package cn.qaiu.vx.core.verticle;

import cn.qaiu.vx.core.Deploy;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.qaiu.vx.core.verticle.TestSupport.*;
import static org.junit.Assert.*;

public class ProxyLimitsTest {

    @After
    public void released() throws Exception {
        assertTrue(waitUntil(() -> HttpProxyVerticle.activeConnections() == 0, 10_000));
    }

    @Test
    public void proxyIsDeployedOnEventLoop() {
        assertEquals(ThreadingModel.EVENT_LOOP, Deploy.proxyDeploymentOptions(4).getThreadingModel());
    }

    @Test(timeout = 30_000)
    public void idleTunnelIsClosedAfterIdleTimeout() throws Exception {
        try (MockUpstream raw = new MockUpstream(MockUpstream.Mode.RAW, null);
             ProxyFixture proxy = ProxyFixture.start(new JsonObject().put("idleTimeout", 2))) {
            try (Socket s = proxy.tunnel(raw.port())) {
                s.setSoTimeout(15_000);
                long t0 = System.currentTimeMillis();
                int r;
                try {
                    r = s.getInputStream().read();
                } catch (java.net.SocketException reset) {
                    r = -1;
                }
                long waited = System.currentTimeMillis() - t0;
                assertEquals(-1, r);
                assertTrue("closed after " + waited + "ms", waited >= 1_500 && waited < 10_000);
            }
            assertTrue(waitUntil(() -> raw.live.get() == 0, 5_000));
        }
    }

    @Test(timeout = 30_000)
    public void activeTunnelIsNotCutByIdleTimeout() throws Exception {
        try (MockUpstream raw = new MockUpstream(MockUpstream.Mode.RAW, null);
             ProxyFixture proxy = ProxyFixture.start(new JsonObject().put("idleTimeout", 2))) {
            try (Socket s = proxy.tunnel(raw.port())) {
                s.setSoTimeout(15_000);
                s.getOutputStream().write('P');
                byte[] payload = new byte[8];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (i + 1);
                    s.getOutputStream().write(payload[i]);
                    s.getOutputStream().flush();
                    Thread.sleep(700);
                }
                assertEquals('P', s.getInputStream().read());
                assertArrayEquals(payload, s.getInputStream().readNBytes(8));
            }
        }
    }

    @Test(timeout = 30_000)
    public void connectionLimitRejectsWith503AndRecovers() throws Exception {
        try (MockUpstream raw = new MockUpstream(MockUpstream.Mode.RAW, null);
             ProxyFixture proxy = ProxyFixture.start(new JsonObject().put("maxConnections", 2))) {
            Socket a = proxy.tunnel(raw.port());
            Socket b = proxy.tunnel(raw.port());
            try {
                assertEquals(503, ProxyFixture.connectStatus(proxy, raw.port()));
            } finally {
                a.close();
            }
            assertTrue(waitUntil(() -> HttpProxyVerticle.activeConnections() == 1, 5_000));
            try (Socket c = proxy.tunnel(raw.port())) {
                assertNotNull(c);
            } finally {
                b.close();
            }
        }
    }

    @Test(timeout = 30_000)
    public void noUsernameKeepsOpenProxyBehaviour() throws Exception {
        try (MockUpstream http = new MockUpstream(MockUpstream.Mode.HTTP, null);
             ProxyFixture proxy = ProxyFixture.start(new JsonObject().putNull("username").putNull("password"))) {
            try (Socket s = proxy.open()) {
                s.setSoTimeout(10_000);
                s.getOutputStream().write(("GET http://127.0.0.1:" + http.port() + "/tiny HTTP/1.1\r\nHost: x\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                InputStream in = s.getInputStream();
                Map<String, String> h = new LinkedHashMap<>();
                assertEquals(200, readStatus(in, h));
                assertEquals("tiny-ok", readSmallBody(in, h));
            }
        }
    }

    @Test
    public void basicAuthParsing() {
        assertTrue(HttpProxyVerticle.checkBasicAuth(ProxyFixture.basic("u:p:with:colon"), "u", "p:with:colon"));
        assertTrue(HttpProxyVerticle.checkBasicAuth(ProxyFixture.basic("u:"), "u", ""));
        assertFalse(HttpProxyVerticle.checkBasicAuth(ProxyFixture.basic("u"), "u", ""));
        assertFalse(HttpProxyVerticle.checkBasicAuth(null, "u", "p"));
        assertFalse(HttpProxyVerticle.checkBasicAuth("Basic", "u", "p"));
        assertFalse(HttpProxyVerticle.checkBasicAuth("Digest abc", "u", "p"));
    }
}
