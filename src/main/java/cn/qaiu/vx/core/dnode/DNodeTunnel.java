package cn.qaiu.vx.core.dnode;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条经由下游节点转发的 TCP 隧道。
 */
public final class DNodeTunnel {

    final int id;
    final String connId;
    final long createdAt = System.nanoTime();

    volatile String dstHost;
    volatile int dstPort;
    volatile String clientIp;
    volatile String nodeIp;

    volatile ServerWebSocket ws;
    volatile Context nodeContext;

    final Promise<DNodeTunnel> connectPromise = Promise.promise();
    final AtomicBoolean ready = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();

    private volatile Handler<Buffer> dataHandler;
    private volatile Handler<Void> closeHandler;
    volatile Runnable freedHook;

    long txBytes;
    long rxBytes;

    DNodeTunnel(int id, String connId) {
        this.id = id;
        this.connId = connId;
    }

    public int id() {
        return id;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
    }

    public void closeHandler(Handler<Void> handler) {
        this.closeHandler = handler;
    }

    /**
     * 向上游节点发送数据（TYPE_DATA）。可从任意线程调用。
     */
    public void write(Buffer data) {
        if (data == null || data.length() == 0 || closed.get() || ws == null) {
            return;
        }
        txBytes += data.length();
        Buffer frame = DNodeProtocol.pack(id, DNodeProtocol.TYPE_DATA, data);
        runOnNode(() -> {
            if (!closed.get() && !ws.isClosed()) {
                ws.writeBinaryMessage(frame);
            }
        });
    }

    public void close() {
        close(true);
    }

    void close(boolean notifyNode) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (notifyNode && ws != null) {
            Buffer frame = DNodeProtocol.pack(id, DNodeProtocol.TYPE_CLOSE);
            runOnNode(() -> {
                if (!ws.isClosed()) {
                    ws.writeBinaryMessage(frame);
                }
            });
        }
        connectPromise.tryFail("closed");
        Runnable hook = freedHook;
        if (hook != null) {
            hook.run();
        }
        Handler<Void> ch = closeHandler;
        if (ch != null) {
            ch.handle(null);
        }
    }

    void onConnected() {
        if (ready.compareAndSet(false, true)) {
            connectPromise.tryComplete(this);
        }
    }

    void onConnectFail(String reason) {
        connectPromise.tryFail(reason == null ? "connect fail" : reason);
        close(false);
    }

    void onData(Buffer payload) {
        if (closed.get() || payload == null) {
            return;
        }
        rxBytes += payload.length();
        Handler<Buffer> h = dataHandler;
        if (h != null) {
            h.handle(payload);
        }
    }

    private void runOnNode(Runnable task) {
        Context ctx = nodeContext;
        if (ctx == null) {
            task.run();
            return;
        }
        ctx.runOnContext(v -> task.run());
    }
}
