package cn.qaiu.vx.core.verticle;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static cn.qaiu.vx.core.verticle.TestSupport.*;

/**
 * 本地上游：HTTP 模式按路径分发，RAW 模式首字节为命令（用于 CONNECT 隧道），可选 TLS。
 */
final class MockUpstream implements AutoCloseable {

    enum Mode { HTTP, RAW }

    final AtomicLong produced = new AtomicLong();
    final AtomicLong consumed = new AtomicLong();
    final AtomicInteger live = new AtomicInteger();
    final AtomicLong writeFailedAtMs = new AtomicLong(-1);
    final AtomicLong halfCloseRead = new AtomicLong(-1);
    final AtomicLong halfCloseEofAtMs = new AtomicLong(-1);
    final AtomicInteger completedUploads = new AtomicInteger();
    final AtomicInteger abortedUploads = new AtomicInteger();
    volatile CountDownLatch gate = new CountDownLatch(0);
    volatile CountDownLatch started = new CountDownLatch(1);

    private final ServerSocket server;
    private final Mode mode;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mock-upstream");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    MockUpstream(Mode mode, SSLContext tls) throws IOException {
        this.mode = mode;
        ServerSocketFactory f = tls == null ? ServerSocketFactory.getDefault() : tls.getServerSocketFactory();
        this.server = f.createServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        pool.execute(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    void reset() {
        produced.set(0);
        consumed.set(0);
        writeFailedAtMs.set(-1);
        halfCloseRead.set(-1);
        halfCloseEofAtMs.set(-1);
        gate = new CountDownLatch(0);
        started = new CountDownLatch(1);
    }

    void closeGate() {
        gate = new CountDownLatch(1);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                live.incrementAndGet();
                pool.execute(() -> {
                    try {
                        if (mode == Mode.HTTP) {
                            handleHttp(s);
                        } else {
                            handleRaw(s);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        live.decrementAndGet();
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void handleRaw(Socket s) throws Exception {
        s.setSoTimeout(60_000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        int cmd = in.read();
        switch (cmd) {
            case 'U' -> {
                long n = readLong(in);
                MessageDigest md = sha256();
                long got = hashStream(in, md, n);
                out.write('K');
                out.write(md.digest());
                writeLong(out, got);
                out.flush();
            }
            case 'D' -> {
                long n = readLong(in);
                writeGenerated(out, 0, n, null);
                out.flush();
            }
            case 'G' -> {
                long n = readLong(in);
                started.countDown();
                writeCounted(out, n);
            }
            case 'S' -> {
                long n = readLong(in);
                started.countDown();
                gate.await(30, TimeUnit.SECONDS);
                MessageDigest md = sha256();
                long got = hashCounted(in, md, n);
                out.write('K');
                out.write(md.digest());
                writeLong(out, got);
                out.flush();
            }
            case 'C' -> {
                started.countDown();
                slowWrite(out);
            }
            case 'X' -> s.close();
            case 'H' -> {
                MessageDigest md = sha256();
                long n = hashStream(in, md, Long.MAX_VALUE);
                halfCloseRead.set(n);
                halfCloseEofAtMs.set(System.currentTimeMillis());
                out.write(("HALF " + n + " " + hex(md.digest())).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            case 'P' -> {
                byte[] payload = in.readNBytes(8);
                out.write('P');
                out.write(payload);
                out.flush();
            }
            default -> {
            }
        }
    }

    private void handleHttp(Socket s) throws Exception {
        s.setSoTimeout(60_000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        while (true) {
            String req = readLine(in);
            if (req == null || req.isEmpty()) {
                return;
            }
            String[] parts = req.split(" ");
            String method = parts[0];
            String uri = parts[1];
            Map<String, String> headers = readHeaders(in);
            String path = uri;
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            boolean keepAlive = !"close".equalsIgnoreCase(headers.getOrDefault("connection", ""));
            if (!dispatch(s, method, path, query, headers, in, out) || !keepAlive) {
                return;
            }
        }
    }

    /** @return true 表示连接可继续复用 */
    private boolean dispatch(Socket s, String method, String path, String query, Map<String, String> headers,
                             InputStream in, OutputStream out) throws Exception {
        if ("HEAD".equals(method)) {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return true;
        }
        switch (path) {
            case "/tiny" -> {
                simple(out, 200, "tiny-ok");
                return true;
            }
            case "/echo-headers" -> {
                StringBuilder sb = new StringBuilder();
                headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
                simple(out, 200, sb.toString());
                return true;
            }
            case "/upload" -> {
                MessageDigest md = sha256();
                long n;
                try {
                    n = consumeBody(in, headers, md);
                } catch (IOException e) {
                    abortedUploads.incrementAndGet();
                    throw e;
                }
                completedUploads.incrementAndGet();
                simple(out, 200, hex(md.digest()) + " " + n + " " + framing(headers));
                return true;
            }
            case "/expect" -> {
                if ("100-continue".equalsIgnoreCase(headers.getOrDefault("expect", ""))) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                MessageDigest md = sha256();
                long n = consumeBody(in, headers, md);
                simple(out, 200, hex(md.digest()) + " " + n);
                return true;
            }
            case "/download" -> {
                long n = qlong(query, "n");
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + n + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                writeGenerated(out, 0, n, null);
                out.flush();
                return true;
            }
            case "/chunked" -> {
                long n = qlong(query, "n");
                int chunk = (int) qlong(query, "chunk");
                out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                byte[] buf = new byte[chunk];
                long pos = 0;
                while (pos < n) {
                    int len = (int) Math.min(chunk, n - pos);
                    fill(buf, len, pos);
                    out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(buf, 0, len);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    pos += len;
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return true;
            }
            case "/range" -> {
                String spec = headers.get("range").toLowerCase(Locale.ROOT).replace("bytes=", "");
                long a = Long.parseLong(spec.split("-")[0]);
                long b = Long.parseLong(spec.split("-")[1]);
                long len = b - a + 1;
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                        + "\r\nContent-Range: bytes " + a + "-" + b + "/10000000\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                writeGenerated(out, a, len, null);
                out.flush();
                return true;
            }
            case "/gated-download" -> {
                long n = qlong(query, "n");
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + n + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                started.countDown();
                writeCounted(out, n);
                return true;
            }
            case "/gated-upload" -> {
                started.countDown();
                gate.await(30, TimeUnit.SECONDS);
                MessageDigest md = sha256();
                long n = hashCounted(in, md, Long.parseLong(headers.get("content-length")));
                simple(out, 200, hex(md.digest()) + " " + n);
                return true;
            }
            case "/cancel" -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 1073741824\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                started.countDown();
                slowWrite(out);
                return false;
            }
            case "/cut-download" -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                writeGenerated(out, 0, 64 * 1024, null);
                out.flush();
                s.close();
                return false;
            }
            case "/cut-chunked" -> {
                out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                byte[] buf = new byte[16 * 1024];
                for (long pos = 0; pos < 64 * 1024; pos += buf.length) {
                    fill(buf, buf.length, pos);
                    out.write((Integer.toHexString(buf.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(buf);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
                s.close();
                return false;
            }
            case "/cut-upload" -> {
                in.readNBytes(8192);
                s.close();
                return false;
            }
            default -> {
                simple(out, 404, "no");
                return true;
            }
        }
    }

    private void writeCounted(OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long pos = 0;
        while (pos < n) {
            int len = (int) Math.min(buf.length, n - pos);
            fill(buf, len, pos);
            out.write(buf, 0, len);
            pos += len;
            produced.set(pos);
        }
        out.flush();
    }

    private long hashCounted(InputStream in, MessageDigest md, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        while (got < n) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n - got));
            if (r < 0) {
                break;
            }
            md.update(buf, 0, r);
            got += r;
            consumed.set(got);
        }
        return got;
    }

    /** 每 20ms 写 64KB，最多 30 秒；写失败时记录时间，用来判断取消是否传到了上游。 */
    private void slowWrite(OutputStream out) throws InterruptedException {
        byte[] buf = new byte[64 * 1024];
        long end = System.currentTimeMillis() + 30_000;
        long pos = 0;
        try {
            while (System.currentTimeMillis() < end) {
                fill(buf, buf.length, pos);
                out.write(buf);
                out.flush();
                pos += buf.length;
                produced.set(pos);
                Thread.sleep(20);
            }
        } catch (IOException e) {
            writeFailedAtMs.set(System.currentTimeMillis());
        }
    }

    private static long consumeBody(InputStream in, Map<String, String> headers, MessageDigest md) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            return hashChunked(in, md);
        }
        String cl = headers.get("content-length");
        return cl == null ? 0 : hashStream(in, md, Long.parseLong(cl.trim()));
    }

    private static String framing(Map<String, String> headers) {
        if (headers.containsKey("content-length")) {
            return "cl";
        }
        return headers.containsKey("transfer-encoding") ? "chunked" : "none";
    }

    private static long qlong(String query, String key) {
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key)) {
                return Long.parseLong(part.substring(i + 1));
            }
        }
        throw new IllegalArgumentException(key);
    }

    private static void simple(OutputStream out, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + b.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        pool.shutdownNow();
    }
}
