package cn.qaiu.vx.core.verticle;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.json.JsonObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static cn.qaiu.vx.core.verticle.ProxyFixture.AUTH;
import static cn.qaiu.vx.core.verticle.TestSupport.*;
import static org.junit.Assert.*;

/**
 * 端到端：真实代理 + 本地上游。传输量大于测试 JVM 堆（surefire 设为 128MB），校验 SHA-256。
 */
public class ProxyTransferTest {

    /** 大于测试 JVM 的 -Xmx128m */
    static final long BIG = 192L * 1024 * 1024;
    static final long STALL_TOTAL = 128L * 1024 * 1024;
    /** 停顿期间允许代理链路缓冲的上限（含双方内核套接字缓冲） */
    static final long STALL_BOUND = 48L * 1024 * 1024;

    static ProxyFixture proxy;
    static MockUpstream http;
    static MockUpstream raw;
    static MockUpstream tls;
    static ListAppender<ILoggingEvent> logs;
    static Path tmp;

    @BeforeClass
    public static void setUp() throws Exception {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        tmp = Files.createTempDirectory("nfd-proxy-test");
        http = new MockUpstream(MockUpstream.Mode.HTTP, null);
        raw = new MockUpstream(MockUpstream.Mode.RAW, null);
        tls = new MockUpstream(MockUpstream.Mode.HTTP, ProxyFixture.serverTls(tmp));
        proxy = ProxyFixture.start(new JsonObject());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        try {
            for (ILoggingEvent e : logs.list) {
                String text = e.getFormattedMessage();
                assertFalse("event loop blocked: " + text, text.contains("has been blocked"));
                assertFalse("password leaked to log: " + text, text.contains(ProxyFixture.PASS));
                assertFalse("unhandled exception: " + text + " " + e.getThrowableProxy(),
                        text.contains("Unhandled exception") || text.startsWith("Stream reset"));
            }
        } finally {
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
            proxy.close();
            http.close();
            raw.close();
            tls.close();
        }
    }

    @After
    public void allSlotsReleased() throws Exception {
        assertTrue("active connections not released: " + HttpProxyVerticle.activeConnections(),
                waitUntil(() -> HttpProxyVerticle.activeConnections() == 0, 10_000));
        http.reset();
        raw.reset();
        tls.reset();
    }

    // ---------- 明文 HTTP 转发 ----------

    @Test(timeout = 120_000)
    public void httpUploadLargerThanHeap() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/upload", "Content-Length: " + BIG));
            MessageDigest md = sha256();
            writeGenerated(out, 0, BIG, md);
            out.flush();
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals(hex(md.digest()) + " " + BIG + " cl", readSmallBody(s.getInputStream(), h));
        }
    }

    @Test(timeout = 120_000)
    public void httpDownloadLargerThanHeap() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(60_000);
            s.getOutputStream().write(httpHead("GET", "/download?n=" + BIG));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals(String.valueOf(BIG), h.get("content-length"));
            MessageDigest md = sha256();
            assertEquals(BIG, hashBody(s.getInputStream(), h, md));
            assertEquals(sha256Generated(BIG), hex(md.digest()));
        }
    }

    @Test(timeout = 60_000)
    public void httpChunkedUploadAndDownload() throws Exception {
        long n = 6L * 1024 * 1024 + 123;
        try (Socket s = proxy.open()) {
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/upload", "Transfer-Encoding: chunked"));
            MessageDigest md = sha256();
            byte[] buf = new byte[10_000];
            long pos = 0;
            while (pos < n) {
                int len = (int) Math.min(buf.length, n - pos);
                fill(buf, len, pos);
                md.update(buf, 0, len);
                out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(buf, 0, len);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                pos += len;
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals(hex(md.digest()) + " " + n + " chunked", readSmallBody(s.getInputStream(), h));
        }
        try (Socket s = proxy.open()) {
            s.setSoTimeout(30_000);
            s.getOutputStream().write(httpHead("GET", "/chunked?n=" + n + "&chunk=65536"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            MessageDigest md = sha256();
            assertEquals(n, hashBody(s.getInputStream(), h, md));
            assertEquals(sha256Generated(n), hex(md.digest()));
        }
    }

    @Test(timeout = 30_000)
    public void httpRangeHeadAndMultipart() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("GET", "/range", "Range: bytes=1000-1999"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(206, readStatus(s.getInputStream(), h));
            assertEquals("bytes 1000-1999/10000000", h.get("content-range"));
            byte[] body = s.getInputStream().readNBytes(1000);
            byte[] expect = new byte[1000];
            for (int i = 0; i < 1000; i++) {
                expect[i] = at(1000 + i);
            }
            assertArrayEquals(expect, body);
        }
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("HEAD", "/download?n=1000"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals("1000", h.get("content-length"));
            s.setSoTimeout(500);
            try {
                assertEquals(-1, s.getInputStream().read());
            } catch (SocketTimeoutException expected) {
                // 连接保持着但没有正文，也正确
            }
        }
        byte[] prefix = ("--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] suffix = "\r\n--b--\r\n".getBytes(StandardCharsets.ISO_8859_1);
        long n = 3L * 1024 * 1024;
        try (Socket s = proxy.open()) {
            s.setSoTimeout(20_000);
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/upload", "Content-Type: multipart/form-data; boundary=b",
                    "Content-Length: " + (prefix.length + n + suffix.length)));
            MessageDigest md = sha256();
            out.write(prefix);
            md.update(prefix);
            writeGenerated(out, 0, n, md);
            out.write(suffix);
            md.update(suffix);
            out.flush();
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertTrue(readSmallBody(s.getInputStream(), h).startsWith(hex(md.digest())));
        }
    }

    @Test(timeout = 30_000)
    public void httpExpectContinueIsRelayed() throws Exception {
        long n = 256 * 1024;
        try (Socket s = proxy.open()) {
            s.setSoTimeout(3_000);
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/expect", "Content-Length: " + n, "Expect: 100-continue"));
            out.flush();
            Map<String, String> interim = new LinkedHashMap<>();
            assertEquals("client must receive 100 before sending body", 100,
                    readStatus(s.getInputStream(), interim));
            s.setSoTimeout(15_000);
            MessageDigest md = sha256();
            writeGenerated(out, 0, n, md);
            out.flush();
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals(hex(md.digest()) + " " + n, readSmallBody(s.getInputStream(), h));
        }
    }

    @Test(timeout = 30_000)
    public void httpKeepAliveReusesClientConnection() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            for (int i = 0; i < 3; i++) {
                s.getOutputStream().write(httpHeadKeepAlive("GET", "/download?n=300000"));
                Map<String, String> h = new LinkedHashMap<>();
                assertEquals(200, readStatus(s.getInputStream(), h));
                MessageDigest md = sha256();
                assertEquals(300000, hashBody(s.getInputStream(), h, md));
                assertEquals(sha256Generated(300000), hex(md.digest()));
            }
        }
    }

    @Test(timeout = 60_000)
    public void httpSlowReceiverIsBackpressuredThenCompletes() throws Exception {
        try (Socket s = proxy.open()) {
            s.setReceiveBufferSize(64 * 1024);
            s.setSoTimeout(30_000);
            s.getOutputStream().write(httpHead("GET", "/gated-download?n=" + STALL_TOTAL));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertTrue(http.started.await(5, TimeUnit.SECONDS));
            Thread.sleep(3_000);
            long buffered = http.produced.get();
            assertTrue("upstream produced " + buffered + " while client stalled", buffered < STALL_BOUND);
            MessageDigest md = sha256();
            assertEquals(STALL_TOTAL, hashBody(s.getInputStream(), h, md));
            assertEquals(sha256Generated(STALL_TOTAL), hex(md.digest()));
        }
    }

    @Test(timeout = 60_000)
    public void httpSlowUpstreamReaderBackpressuresUpload() throws Exception {
        http.closeGate();
        try (Socket s = proxy.open()) {
            s.setSendBufferSize(64 * 1024);
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/gated-upload", "Content-Length: " + STALL_TOTAL));
            out.flush();
            AtomicLong written = new AtomicLong();
            AtomicReference<String> digest = new AtomicReference<>();
            Thread writer = startWriter(out, STALL_TOTAL, written, digest);
            assertTrue(http.started.await(5, TimeUnit.SECONDS));
            Thread.sleep(3_000);
            long buffered = written.get();
            assertTrue("client pushed " + buffered + " while upstream stalled", buffered < STALL_BOUND);
            http.gate.countDown();
            writer.join(30_000);
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            assertEquals(digest.get() + " " + STALL_TOTAL, readSmallBody(s.getInputStream(), h));
        }
    }

    @Test(timeout = 30_000)
    public void httpClientCancelStopsUpstream() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("GET", "/cancel"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            s.getInputStream().readNBytes(128 * 1024);
            s.setSoLinger(true, 0);
        }
        long closedAt = System.currentTimeMillis();
        assertTrue("upstream kept writing after client cancel",
                waitUntil(() -> http.writeFailedAtMs.get() > 0, 10_000));
        assertTrue(http.writeFailedAtMs.get() - closedAt < 10_000);
    }

    @Test(timeout = 30_000)
    public void httpClientAbortedChunkedUploadIsNotCompletedUpstream() throws Exception {
        int before = http.completedUploads.get();
        int abortedBefore = http.abortedUploads.get();
        try (Socket s = proxy.open()) {
            OutputStream out = s.getOutputStream();
            out.write(httpHead("POST", "/upload", "Transfer-Encoding: chunked"));
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < 16; i++) {
                fill(chunk, chunk.length, (long) i * chunk.length);
                out.write((Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(chunk);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
            Thread.sleep(200);
            s.setSoLinger(true, 0);
        }
        assertTrue(waitUntil(() -> http.abortedUploads.get() != abortedBefore
                || http.completedUploads.get() != before, 10_000));
        assertEquals("aborted upload must not reach upstream as a complete body",
                before, http.completedUploads.get());
    }

    @Test(timeout = 30_000)
    public void httpUpstreamDisconnectEndsClientResponse() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("GET", "/cut-download"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            long got = 0;
            try {
                got = hashStream(s.getInputStream(), sha256(), 1024 * 1024);
            } catch (IOException ignored) {
                // 连接被重置也算正确结束
            }
            assertTrue("truncated body must not look complete: " + got, got < 1024 * 1024);
        }
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("GET", "/cut-chunked"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            boolean completed;
            try {
                hashChunked(s.getInputStream(), null);
                completed = true;
            } catch (IOException | NumberFormatException truncated) {
                completed = false;
            }
            assertFalse("cut chunked body must not end with a terminating chunk", completed);
        }
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            long total = 512L * 1024 * 1024;
            out.write(httpHead("POST", "/cut-upload", "Content-Length: " + total));
            long wrote = 0;
            boolean stopped = false;
            try {
                for (; wrote < total; wrote += 1024 * 1024) {
                    writeGenerated(out, wrote, 1024 * 1024, null);
                }
                out.flush();
            } catch (IOException e) {
                stopped = true;
            }
            // 上游断开后代理应关闭客户端连接，而不是把剩余的请求体全部读完丢弃
            assertTrue("client kept uploading into a dead upstream: " + wrote, stopped && wrote < total);
        }
    }

    // ---------- CONNECT 透明隧道 ----------

    @Test(timeout = 120_000)
    public void connectUploadLargerThanHeapDoesNotDeadlock() throws Exception {
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            out.write('U');
            writeLong(out, BIG);
            MessageDigest md = sha256();
            writeGenerated(out, 0, BIG, md);
            out.flush();
            InputStream in = s.getInputStream();
            assertEquals('K', in.read());
            byte[] digest = in.readNBytes(32);
            assertEquals(BIG, readLong(in));
            assertArrayEquals(md.digest(), digest);
        }
    }

    @Test(timeout = 120_000)
    public void connectDownloadLargerThanHeap() throws Exception {
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSoTimeout(60_000);
            s.getOutputStream().write('D');
            writeLong(s.getOutputStream(), BIG);
            s.getOutputStream().flush();
            MessageDigest md = sha256();
            assertEquals(BIG, hashStream(s.getInputStream(), md, BIG));
            assertEquals(sha256Generated(BIG), hex(md.digest()));
        }
    }

    @Test(timeout = 60_000)
    public void connectFirstBytesAfterEstablishedAreNotLost() throws Exception {
        for (int i = 0; i < 50; i++) {
            try (Socket s = proxy.tunnel(raw.port())) {
                s.setSoTimeout(10_000);
                OutputStream out = s.getOutputStream();
                out.write('P');
                byte[] payload = {1, 2, 3, 4, 5, 6, 7, (byte) i};
                out.write(payload);
                out.flush();
                assertEquals("iteration " + i, 'P', s.getInputStream().read());
                assertArrayEquals(payload, s.getInputStream().readNBytes(8));
            }
        }
    }

    @Test(timeout = 120_000)
    public void connectTlsUploadAndDownloadLargerThanHeap() throws Exception {
        try (SSLSocket ssl = ProxyFixture.clientTls(proxy.tunnel(tls.port()), tls.port())) {
            ssl.setSoTimeout(60_000);
            OutputStream out = ssl.getOutputStream();
            out.write(("POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " + BIG
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            MessageDigest md = sha256();
            writeGenerated(out, 0, BIG, md);
            out.flush();
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(ssl.getInputStream(), h));
            assertEquals(hex(md.digest()) + " " + BIG + " cl", readSmallBody(ssl.getInputStream(), h));
        }
        try (SSLSocket ssl = ProxyFixture.clientTls(proxy.tunnel(tls.port()), tls.port())) {
            ssl.setSoTimeout(60_000);
            ssl.getOutputStream().write(("GET /download?n=" + BIG + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(ssl.getInputStream(), h));
            MessageDigest md = sha256();
            assertEquals(BIG, hashBody(ssl.getInputStream(), h, md));
            assertEquals(sha256Generated(BIG), hex(md.digest()));
        }
    }

    @Test(timeout = 60_000)
    public void connectSlowReceiverIsBackpressuredThenCompletes() throws Exception {
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setReceiveBufferSize(64 * 1024);
            s.setSoTimeout(30_000);
            s.getOutputStream().write('G');
            writeLong(s.getOutputStream(), STALL_TOTAL);
            s.getOutputStream().flush();
            assertTrue(raw.started.await(5, TimeUnit.SECONDS));
            Thread.sleep(3_000);
            long buffered = raw.produced.get();
            assertTrue("upstream produced " + buffered + " while client stalled", buffered < STALL_BOUND);
            MessageDigest md = sha256();
            assertEquals(STALL_TOTAL, hashStream(s.getInputStream(), md, STALL_TOTAL));
            assertEquals(sha256Generated(STALL_TOTAL), hex(md.digest()));
        }
    }

    @Test(timeout = 60_000)
    public void connectSlowUpstreamReaderBackpressuresUpload() throws Exception {
        raw.closeGate();
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSendBufferSize(64 * 1024);
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write('S');
            writeLong(out, STALL_TOTAL);
            out.flush();
            AtomicLong written = new AtomicLong();
            AtomicReference<String> digest = new AtomicReference<>();
            Thread writer = startWriter(out, STALL_TOTAL, written, digest);
            assertTrue(raw.started.await(5, TimeUnit.SECONDS));
            Thread.sleep(3_000);
            long buffered = written.get();
            assertTrue("client pushed " + buffered + " while upstream stalled", buffered < STALL_BOUND);
            raw.gate.countDown();
            writer.join(30_000);
            InputStream in = s.getInputStream();
            assertEquals('K', in.read());
            byte[] d = in.readNBytes(32);
            assertEquals(STALL_TOTAL, readLong(in));
            assertEquals(digest.get(), hex(d));
        }
    }

    @Test(timeout = 30_000)
    public void connectClientCancelStopsUpstream() throws Exception {
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write('C');
            s.getOutputStream().flush();
            s.getInputStream().readNBytes(128 * 1024);
            s.setSoLinger(true, 0);
        }
        assertTrue("upstream kept writing after client cancel",
                waitUntil(() -> raw.writeFailedAtMs.get() > 0, 10_000));
        assertTrue(waitUntil(() -> raw.live.get() == 0, 5_000));
    }

    @Test(timeout = 30_000)
    public void connectUpstreamDisconnectClosesClient() throws Exception {
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write('X');
            s.getOutputStream().flush();
            assertEquals(-1, s.getInputStream().read());
        }
    }

    /**
     * Vert.x 4 的 NetSocket 不支持 TCP 半关闭：客户端关写后整条隧道关闭。
     * 这里固定该行为：上游能读到完整数据与 EOF，资源被释放，不会挂住。
     */
    @Test(timeout = 30_000)
    public void connectHalfCloseTearsDownTunnelAndReleases() throws Exception {
        long n = 1024 * 1024;
        try (Socket s = proxy.tunnel(raw.port())) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            out.write('H');
            writeGenerated(out, 0, n, null);
            out.flush();
            s.shutdownOutput();
            assertTrue(waitUntil(() -> raw.halfCloseRead.get() >= 0, 10_000));
            assertEquals(n, raw.halfCloseRead.get());
            try {
                s.getInputStream().readAllBytes();
            } catch (IOException ignored) {
                // 隧道关闭时可能是 RST
            }
        }
        assertTrue(waitUntil(() -> raw.live.get() == 0, 5_000));
    }

    @Test(timeout = 60_000)
    public void connectChurnReleasesEverything() throws Exception {
        for (int i = 0; i < 40; i++) {
            try (Socket s = proxy.tunnel(raw.port())) {
                s.setSoTimeout(10_000);
                s.getOutputStream().write('D');
                writeLong(s.getOutputStream(), 256 * 1024);
                MessageDigest md = sha256();
                assertEquals(256 * 1024, hashStream(s.getInputStream(), md, 256 * 1024));
            }
        }
        assertTrue(waitUntil(() -> raw.live.get() == 0, 5_000));
    }

    @Test(timeout = 30_000)
    public void connectToClosedPortReturns502() throws Exception {
        assertEquals(502, ProxyFixture.connectStatus(proxy, freePort()));
    }

    // ---------- 认证 ----------

    @Test(timeout = 30_000)
    public void authRejectsMissingWrongAndMalformedCredentials() throws Exception {
        assertEquals(403, tinyStatus(null));
        assertEquals(403, tinyStatus(ProxyFixture.basic(ProxyFixture.USER + ":wrong")));
        assertEquals(403, tinyStatus(ProxyFixture.basic("onlyuser")));
        assertEquals(403, tinyStatus(ProxyFixture.basic("x")));
        assertEquals(403, tinyStatus(ProxyFixture.basic(ProxyFixture.USER)));
        assertEquals(403, tinyStatus(ProxyFixture.basic(":" + ProxyFixture.PASS)));
        assertEquals(403, tinyStatus(ProxyFixture.basic(ProxyFixture.USER + ":" + ProxyFixture.PASS + "x")));
        assertEquals(403, tinyStatus("Bearer abc"));
        assertEquals(403, tinyStatus("Basic !!!notbase64"));
        assertEquals(200, tinyStatus(AUTH));
        assertEquals(200, tinyStatus("basic " + AUTH.substring(6)));
        for (ILoggingEvent e : logs.list) {
            assertFalse(e.getFormattedMessage().contains(ProxyFixture.PASS));
        }
    }

    @Test(timeout = 30_000)
    public void connectRequiresAuthToo() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(("CONNECT 127.0.0.1:" + raw.port() + " HTTP/1.1\r\nHost: x\r\n"
                    + "Proxy-Authorization: " + ProxyFixture.basic("nocolon") + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(403, readStatus(s.getInputStream(), new LinkedHashMap<>()));
        }
    }

    @Test(timeout = 30_000)
    public void hopByHopProxyAuthorizationIsNotForwarded() throws Exception {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(httpHead("GET", "/echo-headers"));
            Map<String, String> h = new LinkedHashMap<>();
            assertEquals(200, readStatus(s.getInputStream(), h));
            String echoed = readSmallBody(s.getInputStream(), h);
            assertFalse(echoed.toLowerCase().contains("proxy-authorization"));
        }
    }

    // ---------- helpers ----------

    private static int tinyStatus(String auth) throws IOException {
        try (Socket s = proxy.open()) {
            s.setSoTimeout(10_000);
            StringBuilder sb = new StringBuilder("GET http://127.0.0.1:" + http.port() + "/tiny HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(http.port()).append("\r\n");
            if (auth != null) {
                sb.append("Proxy-Authorization: ").append(auth).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            s.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            return readStatus(s.getInputStream(), new LinkedHashMap<>());
        }
    }

    private static byte[] httpHead(String method, String path, String... extra) {
        return head(method, path, "close", extra);
    }

    private static byte[] httpHeadKeepAlive(String method, String path) {
        return head(method, path, "keep-alive");
    }

    private static byte[] head(String method, String path, String connection, String... extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" http://127.0.0.1:").append(http.port()).append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1:").append(http.port()).append("\r\n");
        sb.append("Proxy-Authorization: ").append(AUTH).append("\r\n");
        Arrays.stream(extra).forEach(x -> sb.append(x).append("\r\n"));
        sb.append("Connection: ").append(connection).append("\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Thread startWriter(OutputStream out, long n, AtomicLong written, AtomicReference<String> digest) {
        Thread t = new Thread(() -> {
            MessageDigest md = sha256();
            byte[] buf = new byte[64 * 1024];
            long pos = 0;
            try {
                while (pos < n) {
                    int len = (int) Math.min(buf.length, n - pos);
                    fill(buf, len, pos);
                    md.update(buf, 0, len);
                    out.write(buf, 0, len);
                    pos += len;
                    written.set(pos);
                }
                out.flush();
                digest.set(hex(md.digest()));
            } catch (IOException ignored) {
            }
        }, "test-writer");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
