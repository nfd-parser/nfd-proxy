package cn.qaiu.vx.core.verticle;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 生成式数据、简易 HTTP 读写与等待工具。数据按位置生成，不落盘也不在内存中整体保存。
 */
final class TestSupport {

    private TestSupport() {
    }

    static byte at(long p) {
        return (byte) ((p * 131) ^ (p >>> 9));
    }

    static void fill(byte[] buf, int len, long pos) {
        for (int i = 0; i < len; i++) {
            buf[i] = at(pos + i);
        }
    }

    static void writeGenerated(OutputStream out, long from, long n, MessageDigest md) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long pos = from;
        long end = from + n;
        while (pos < end) {
            int len = (int) Math.min(buf.length, end - pos);
            fill(buf, len, pos);
            if (md != null) {
                md.update(buf, 0, len);
            }
            out.write(buf, 0, len);
            pos += len;
        }
    }

    static String sha256Generated(long n) {
        MessageDigest md = sha256();
        byte[] buf = new byte[64 * 1024];
        long pos = 0;
        while (pos < n) {
            int len = (int) Math.min(buf.length, n - pos);
            fill(buf, len, pos);
            md.update(buf, 0, len);
            pos += len;
        }
        return hex(md.digest());
    }

    static long hashStream(InputStream in, MessageDigest md, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        while (got < n) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n - got));
            if (r < 0) {
                break;
            }
            md.update(buf, 0, r);
            got += r;
        }
        return got;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static void writeLong(OutputStream out, long v) throws IOException {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        out.write(b);
    }

    static long readLong(InputStream in) throws IOException {
        byte[] b = in.readNBytes(8);
        if (b.length != 8) {
            throw new EOFException("long");
        }
        long v = 0;
        for (byte x : b) {
            v = (v << 8) | (x & 0xff);
        }
        return v;
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return bos.size() == 0 ? null : bos.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\n') {
                return bos.toString(StandardCharsets.ISO_8859_1);
            }
            if (b != '\r') {
                bos.write(b);
            }
        }
    }

    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("headers");
            }
            if (line.isEmpty()) {
                return h;
            }
            int c = line.indexOf(':');
            if (c > 0) {
                h.merge(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim(),
                        (a, b) -> a + ", " + b);
            }
        }
    }

    /** 读状态行与头部，返回状态码，头部写入 headers（含 ":status-line"）。 */
    static int readStatus(InputStream in, Map<String, String> headers) throws IOException {
        String line = readLine(in);
        if (line == null) {
            throw new EOFException("no status line");
        }
        headers.put(":status-line", line);
        String[] p = line.split(" ");
        headers.putAll(readHeaders(in));
        return Integer.parseInt(p[1]);
    }

    /** 读取 chunked 正文并计算摘要。 */
    static long hashChunked(InputStream in, MessageDigest md) throws IOException {
        long n = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("chunk size");
            }
            int size = Integer.parseInt(line.split(";", 2)[0].trim(), 16);
            if (size == 0) {
                while (true) {
                    String t = readLine(in);
                    if (t == null || t.isEmpty()) {
                        return n;
                    }
                }
            }
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) {
                throw new EOFException("chunk");
            }
            if (md != null) {
                md.update(chunk);
            }
            n += size;
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("bad chunk terminator");
            }
        }
    }

    static long hashBody(InputStream in, Map<String, String> headers, MessageDigest md) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            return hashChunked(in, md);
        }
        String cl = headers.get("content-length");
        if (cl != null) {
            return hashStream(in, md, Long.parseLong(cl.trim()));
        }
        return hashStream(in, md, Long.MAX_VALUE);
    }

    static String readSmallBody(InputStream in, Map<String, String> headers) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String line = readLine(in);
                int size = Integer.parseInt(line.split(";", 2)[0].trim(), 16);
                if (size == 0) {
                    readLine(in);
                    break;
                }
                bos.write(in.readNBytes(size));
                readLine(in);
            }
        } else if (headers.containsKey("content-length")) {
            bos.write(in.readNBytes(Integer.parseInt(headers.get("content-length").trim())));
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    static Socket connect(int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        s.setTcpNoDelay(true);
        return s;
    }

    static boolean waitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }
}
