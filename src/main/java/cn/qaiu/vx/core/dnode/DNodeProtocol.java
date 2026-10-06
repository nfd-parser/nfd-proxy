package cn.qaiu.vx.core.dnode;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;

/**
 * DNode 二进制帧协议（与 Python/C/PHP/油猴节点一致）:
 * <pre>
 *   [4B tunnel_id BE][1B type][payload...]
 *   0x01 CONNECT_REQ  payload: [1B host_len][host][2B port BE]
 *   0x02 CONNECT_OK
 *   0x03 CONNECT_FAIL payload: reason
 *   0x04 DATA
 *   0x05 CLOSE
 * </pre>
 */
public final class DNodeProtocol {

    public static final byte TYPE_CONNECT_REQ = 0x01;
    public static final byte TYPE_CONNECT_OK = 0x02;
    public static final byte TYPE_CONNECT_FAIL = 0x03;
    public static final byte TYPE_DATA = 0x04;
    public static final byte TYPE_CLOSE = 0x05;

    public static final int HEADER_LEN = 5;

    private DNodeProtocol() {
    }

    public static Buffer pack(int tid, byte type) {
        return pack(tid, type, (Buffer) null);
    }

    public static Buffer pack(int tid, byte type, Buffer payload) {
        int plen = payload == null ? 0 : payload.length();
        Buffer buf = Buffer.buffer(HEADER_LEN + plen);
        buf.appendInt(tid);
        buf.appendByte(type);
        if (plen > 0) {
            buf.appendBuffer(payload);
        }
        return buf;
    }

    public static Buffer pack(int tid, byte type, byte[] payload) {
        return pack(tid, type, payload == null ? null : Buffer.buffer(payload));
    }

    public static Buffer packConnectReq(int tid, String host, int port) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            throw new IllegalArgumentException("host too long: " + host);
        }
        Buffer payload = Buffer.buffer(1 + hostBytes.length + 2);
        payload.appendUnsignedByte((short) hostBytes.length);
        payload.appendBytes(hostBytes);
        payload.appendUnsignedShort(port);
        return pack(tid, TYPE_CONNECT_REQ, payload);
    }

    public static Frame unpack(Buffer data) {
        if (data == null || data.length() < HEADER_LEN) {
            return null;
        }
        int tid = data.getInt(0);
        int type = data.getByte(4) & 0xFF;
        Buffer payload = data.length() > HEADER_LEN
                ? data.getBuffer(HEADER_LEN, data.length())
                : Buffer.buffer();
        return new Frame(tid, type, payload);
    }

    public static ConnectTarget parseConnectReq(Buffer payload) {
        if (payload == null || payload.length() < 3) {
            return null;
        }
        int hostLen = payload.getUnsignedByte(0);
        if (payload.length() < 1 + hostLen + 2) {
            return null;
        }
        String host = payload.getString(1, 1 + hostLen, "UTF-8");
        int port = payload.getUnsignedShort(1 + hostLen);
        return new ConnectTarget(host, port);
    }

    public static final class Frame {
        public final int tid;
        public final int type;
        public final Buffer payload;

        public Frame(int tid, int type, Buffer payload) {
            this.tid = tid;
            this.type = type;
            this.payload = payload == null ? Buffer.buffer() : payload;
        }
    }

    public static final class ConnectTarget {
        public final String host;
        public final int port;

        public ConnectTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
