// ==UserScript==
// @name         NFD DNode 浏览器节点
// @namespace    https://qaiu.top/nfd
// @version      1.5.1
// @description  将浏览器标签页作为 NFD 分布式代理节点，通过 WSS 隧道转发 HTTP(S) 请求，支持 123 云盘快速下载
// @author       NFD Project
// @match        *://*/*
// @match        *://189.qaiu.top/*
// @match        *://dnode.qaiu.top/*
// @match        *://www.123pan.com/*
// @match        *://www.123link.com/*
// @match        *://www.123yunpan.com/*
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_notification
// @connect      *
// @run-at       document-idle
// @noframes
// ==/UserScript==

/*
 * 工作原理：
 *   1. 脚本通过 WSS 连接到 NFD 服务器，注册为一个代理节点
 *   2. 服务器收到用户解析请求时，通过 WebSocket 发送 CONNECT_REQ 帧
 *   3. 脚本用 GM_xmlhttpRequest（可绕过 CORS）转发 HTTP 请求
 *   4. 将响应打包成帧发回服务器
 *
 * 浏览器节点限制（与 Python/C 节点对比）：
 *   ✅ HTTP / HTTPS 请求（API 解析）
 *   ✅ 绕过 CORS（GM_xmlhttpRequest）
 *   ❌ 原始 TCP 连接（浏览器沙盒限制）
 *   ❌ 大文件传输（内存限制）
 *
 *  → 适合：云盘 API 解析、获取下载直链
 *  → 不适合：直接下载大文件
 *
 * 帧格式（与服务端一致）：
 *   [4B tunnel_id BE][1B type][payload]
 *   type: 0x01=CONNECT_REQ 0x02=CONNECT_OK 0x03=CONNECT_FAIL
 *         0x04=DATA        0x05=CLOSE
 */

(function () {
    'use strict';

    // ════════════════════════════════════════════════════════
    //  配置（存储在 GM 持久化存储）
    // ════════════════════════════════════════════════════════
    const CFG_KEYS = {
        SERVER:       'nfd_server',
        SECRET:       'nfd_secret',
        NODE_ID:      'nfd_node_id',
        ENABLED:      'nfd_enabled',
        DEFAULT:      'nfd_default_node',
        PARSER_TOKEN: 'nfd_parser_token',
    };

    const DEFAULT_SERVER = 'wss://dnode.qaiu.top/ws/node';
    const MAX_TUNNELS    = 20;
    const RECONNECT_DELAYS = [2000, 4000, 8000, 16000, 30000, 60000];

    // ── 读写配置 ─────────────────────────────────────────────
    function cfgGet(k, def) {
        const v = GM_getValue(k, null);
        return v !== null ? v : def;
    }
    function cfgSet(k, v) { GM_setValue(k, v); }

    // 自动生成 node_id（本机指纹 + localStorage 持久化）
    function getNodeId() {
        let id = cfgGet(CFG_KEYS.NODE_ID, '');
        if (!id) {
            // 用浏览器指纹生成（UA + 时间戳混合）
            const raw = navigator.userAgent + Date.now() + Math.random();
            let h = 0;
            for (const c of raw) { h = (Math.imul(31, h) + c.charCodeAt(0)) | 0; }
            id = Math.abs(h).toString(16).padStart(8, '0') +
                 Date.now().toString(16).slice(-8);
            cfgSet(CFG_KEYS.NODE_ID, id);
        }
        return id;
    }

    // ════════════════════════════════════════════════════════
    //  帧协议
    // ════════════════════════════════════════════════════════
    const T_CONNECT_REQ  = 0x01;
    const T_CONNECT_OK   = 0x02;
    const T_CONNECT_FAIL = 0x03;
    const T_DATA         = 0x04;
    const T_CLOSE        = 0x05;
    const T_HTTP_FETCH   = 0x06;  // server → browser: JSON {method,url,headers,body_b64}
    const T_HTTP_RESP    = 0x07;  // browser → server: JSON {status,statusText,headers,body_b64}

    function packFrame(tid, ftype, payload /* Uint8Array | null */) {
        const plen = payload ? payload.length : 0;
        const buf  = new ArrayBuffer(5 + plen);
        const dv   = new DataView(buf);
        dv.setUint32(0, tid, false);   // big-endian
        dv.setUint8(4, ftype);
        if (plen > 0) new Uint8Array(buf, 5).set(payload);
        return buf;
    }

    function unpackFrame(buf /* ArrayBuffer */) {
        if (buf.byteLength < 5) return null;
        const dv = new DataView(buf);
        return {
            tid:     dv.getUint32(0, false),
            ftype:   dv.getUint8(4),
            payload: new Uint8Array(buf, 5),
        };
    }

    // ════════════════════════════════════════════════════════
    //  隧道管理
    // ════════════════════════════════════════════════════════
    // tunnels: Map<tid, { host, port, bufChunks, state }>
    const tunnels = new Map();

    function tunnelCreate(tid, host, port) {
        tunnels.set(tid, {
            tid, host, port,
            bufChunks: [],   // 累积的请求数据
            state: 'open',   // open | closed
        });
    }

    function tunnelClose(tid) {
        tunnels.delete(tid);
    }

    function tunnelFeed(tid, chunk /* Uint8Array */) {
        const t = tunnels.get(tid);
        if (!t || t.state !== 'open') return;
        t.bufChunks.push(chunk);
    }

    // ════════════════════════════════════════════════════════
    //  HTTP 请求解析（从原始字节 → fetch 参数）
    // ════════════════════════════════════════════════════════
    const dec = new TextDecoder('utf-8', { fatal: false });
    const enc = new TextEncoder();

    function concatChunks(chunks) {
        const total = chunks.reduce((s, c) => s + c.length, 0);
        const out   = new Uint8Array(total);
        let off = 0;
        for (const c of chunks) { out.set(c, off); off += c.length; }
        return out;
    }

    /** 解析 HTTP/1.x 请求字节，返回 { method, url, headers, body } 或 null */
    function parseHttpRequest(bytes, host, port) {
        const raw  = dec.decode(bytes);
        const hEnd = raw.indexOf('\r\n\r\n');
        if (hEnd === -1) return null;   // 请求头不完整，继续等数据

        const headerPart = raw.slice(0, hEnd);
        const bodyStart  = hEnd + 4;
        const bodyBytes  = bytes.slice(bodyStart);

        const lines   = headerPart.split('\r\n');
        const reqLine = lines[0].split(' ');
        if (reqLine.length < 3) return null;

        const method = reqLine[0].toUpperCase();
        let   path   = reqLine[1];

        // 构造完整 URL
        const scheme = port === 443 ? 'https' : 'http';
        let url;
        if (path.startsWith('http://') || path.startsWith('https://')) {
            url = path;
        } else {
            url = `${scheme}://${host}${port !== 80 && port !== 443 ? ':' + port : ''}${path}`;
        }

        const headers = {};
        for (let i = 1; i < lines.length; i++) {
            const sep = lines[i].indexOf(':');
            if (sep === -1) continue;
            const k = lines[i].slice(0, sep).trim().toLowerCase();
            const v = lines[i].slice(sep + 1).trim();
            // 过滤掉 proxy 相关头
            if (k === 'proxy-authorization' || k === 'proxy-connection') continue;
            headers[k] = v;
        }

        return {
            method,
            url,
            headers,
            body: (method !== 'GET' && method !== 'HEAD' && bodyBytes.length > 0)
                  ? bodyBytes
                  : null,
        };
    }

    /** HTTP/1.1 响应 → Uint8Array */
    async function responseToBytes(resp) {
        const bodyBuf = await resp.arrayBuffer();
        const statusLine = `HTTP/1.1 ${resp.status} ${resp.statusText || 'OK'}\r\n`;
        let headerStr = '';
        resp.headers.forEach((v, k) => {
            // 跳过 transfer-encoding（我们已完全读取 body）
            if (k.toLowerCase() !== 'transfer-encoding') {
                headerStr += `${k}: ${v}\r\n`;
            }
        });
        headerStr += `content-length: ${bodyBuf.byteLength}\r\n`;
        headerStr += '\r\n';

        const headerBytes = enc.encode(statusLine + headerStr);
        const bodyBytes   = new Uint8Array(bodyBuf);
        const out = new Uint8Array(headerBytes.length + bodyBytes.length);
        out.set(headerBytes, 0);
        out.set(bodyBytes, headerBytes.length);
        return out;
    }

    // ════════════════════════════════════════════════════════
    //  请求执行（GM_xmlhttpRequest 绕过 CORS）
    // ════════════════════════════════════════════════════════
    function gmRequest(req) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method:       req.method,
                url:          req.url,
                headers:      req.headers,
                data:         req.body ? req.body.buffer : null,
                responseType: 'arraybuffer',
                anonymous:    false,
                onload(r) {
                    resolve({
                        status:     r.status,
                        statusText: r.statusText,
                        headers:    parseRespHeaders(r.responseHeaders),
                        body:       r.response,   // ArrayBuffer
                    });
                },
                onerror(e)   { reject(new Error(`网络错误: ${e.error || 'unknown'}`)); },
                ontimeout()  { reject(new Error('请求超时')); },
                timeout: 30000,
            });
        });
    }

    /** 将 GM_xmlhttpRequest 返回的 responseHeaders 字符串解析为对象 */
    function parseRespHeaders(str) {
        const h = {};
        if (!str) return h;
        for (const line of str.split('\r\n')) {
            const sep = line.indexOf(':');
            if (sep === -1) continue;
            h[line.slice(0, sep).trim().toLowerCase()] = line.slice(sep + 1).trim();
        }
        return h;
    }

    /** 把 GM 返回的简单响应对象转为字节 */
    async function gmRespToBytes(r) {
        const statusLine = `HTTP/1.1 ${r.status} ${r.statusText || 'OK'}\r\n`;
        let headerStr = '';
        for (const [k, v] of Object.entries(r.headers)) {
            if (k.toLowerCase() !== 'transfer-encoding') {
                headerStr += `${k}: ${v}\r\n`;
            }
        }
        const bodyBytes = r.body ? new Uint8Array(r.body) : new Uint8Array(0);
        headerStr += `content-length: ${bodyBytes.length}\r\n\r\n`;
        const headerBytes = enc.encode(statusLine + headerStr);
        const out = new Uint8Array(headerBytes.length + bodyBytes.length);
        out.set(headerBytes, 0);
        out.set(bodyBytes, headerBytes.length);
        return out;
    }

    // ════════════════════════════════════════════════════════
    //  隧道完成处理（尝试执行 HTTP 请求）
    // ════════════════════════════════════════════════════════
    async function handleTunnel(tid) {
        const node = ws;  // 使用当前 WebSocket 连接
        const t = tunnels.get(tid);
        if (!t || t.state !== 'open') return;

        const bytes = concatChunks(t.bufChunks);
        const req   = parseHttpRequest(bytes, t.host, t.port);
        if (!req) {
            // 请求数据不完整，继续等
            return;
        }

        t.state = 'executing';
        log(`[node] tid=${tid.toString(16).padStart(8,'0')} | ${req.method} ${req.url}`);

        try {
            const resp = await gmRequest(req);
            const respBytes = await gmRespToBytes(resp);

            // 分块发送（每帧最大 64KB）
            const CHUNK = 65536;
            for (let off = 0; off < respBytes.length; off += CHUNK) {
                const chunk = respBytes.slice(off, off + CHUNK);
                node.send(packFrame(tid, T_DATA, chunk));
            }
        } catch (e) {
            // 发送 HTTP 502 错误响应
            const errBody = enc.encode(`NFD 节点错误: ${e.message}`);
            const errHead = enc.encode(
                `HTTP/1.1 502 Bad Gateway\r\ncontent-length: ${errBody.length}\r\n\r\n`
            );
            const errBytes = new Uint8Array(errHead.length + errBody.length);
            errBytes.set(errHead, 0); errBytes.set(errBody, errHead.length);
            node.send(packFrame(tid, T_DATA, errBytes));
            log(`[node] tid=${tid.toString(16).padStart(8,'0')} | 错误: ${e.message}`);
        } finally {
            node.send(packFrame(tid, T_CLOSE, null));
            tunnelClose(tid);
        }
    }

    // ════════════════════════════════════════════════════════
    //  WebSocket 节点
    // ════════════════════════════════════════════════════════
    let ws         = null;
    let reconnectN = 0;
    let reconnectTimer = null;
    let pingTimer  = null;
    let enabled    = false;

    const state = {
        status:    'disconnected',   // disconnected | connecting | connected
        tunnelCnt: 0,
        totalReqs: 0,
    };

    function updateStatus(s) {
        state.status = s;
        uiUpdate();
    }

    function connect() {
        if (ws && ws.readyState <= 1) return;
        const server  = cfgGet(CFG_KEYS.SERVER, DEFAULT_SERVER);
        const secret  = cfgGet(CFG_KEYS.SECRET, '');
        const nodeId  = getNodeId();
        const isDef   = cfgGet(CFG_KEYS.DEFAULT, false);

        log(`连接服务端 url=${server} node_id=${nodeId.slice(0,8)}`);
        updateStatus('connecting');

        // WebSocket 握手头（通过 URL 参数传递，因为浏览器 WS 不支持自定义头）
        const browserTag = navigator.userAgent.match(/Chrome\/[\d.]+|Firefox\/[\d.]+|Safari\/[\d.]+/)?.[0] || 'Unknown';
        const params = new URLSearchParams({
            'x-node-id':      nodeId,
            'x-platform':     'Browser-' + browserTag,
            'x-version':      '3.0-js',
            'x-default-node': isDef ? 'true' : 'false',
        });
        if (secret) params.set('token', secret);

        // 部分服务端支持 URL 参数，保持兼容
        const url = server + (server.includes('?') ? '&' : '?') + params.toString();

        try {
            ws = new WebSocket(url);
            ws.binaryType = 'arraybuffer';
        } catch(e) {
            log('WebSocket 创建失败: ' + e.message);
            scheduleReconnect();
            return;
        }

        ws.onopen = () => {
            log('✅ 已连接  node_id=' + getNodeId().slice(0,8));
            updateStatus('connected');
            reconnectN = 0;
            startPing();
        };

        ws.onmessage = (ev) => {
            if (!(ev.data instanceof ArrayBuffer)) return;
            const frame = unpackFrame(ev.data);
            if (!frame) return;
            onFrame(frame);
        };

        ws.onclose = (ev) => {
            log(`连接断开  code=${ev.code}  reason=${ev.reason || '-'}`);
            updateStatus('disconnected');
            stopPing();
            tunnels.clear();
            state.tunnelCnt = 0;
            if (enabled) scheduleReconnect();
        };

        ws.onerror = () => {
            log('WS 错误');
        };
    }

    function disconnect() {
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
        stopPing();
        if (ws) { ws.onclose = null; ws.close(); ws = null; }
        updateStatus('disconnected');
    }

    function scheduleReconnect() {
        if (reconnectTimer) return;
        const delay = RECONNECT_DELAYS[Math.min(reconnectN, RECONNECT_DELAYS.length - 1)];
        reconnectN++;
        log(`${delay/1000}s 后重连... (第${reconnectN}次)`);
        reconnectTimer = setTimeout(() => {
            reconnectTimer = null;
            if (enabled) connect();
        }, delay);
    }

    function startPing() {
        stopPing();
        pingTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                // 发送 JSON 心跳（服务端返回 pong）
                ws.send(JSON.stringify({ type: 'ping' }));
            }
        }, 25000);
    }

    function stopPing() {
        if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
    }

    // ── 安全 base64 编码（避免大响应体展开运算符栈溢出）────────
    function safeBase64(bytes) {
        // btoa(String.fromCharCode(...bytes)) 在响应体 > ~50KB 时
        // 会触发 RangeError: Maximum call stack size exceeded
        // 原因：...bytes 把整个数组展开成函数参数，超出 JS 引擎参数上限
        let s = '';
        const len = bytes.length;
        for (let i = 0; i < len; i++) s += String.fromCharCode(bytes[i]);
        return btoa(s);
    }

    // ── T_HTTP_FETCH 处理（主路径）────────────────────────────
    // 服务端做 TLS 拦截后，以 JSON 形式把 HTTP 请求发给浏览器执行
    async function handleHttpFetch(tid, payload) {
        let req;
        try { req = JSON.parse(dec.decode(payload)); }
        catch(e) { sendHttpResp(tid, 502, 'Bad Request', {}, ''); return; }

        const tidHex = tid.toString(16).padStart(8, '0');
        log(`[fetch] tid=${tidHex} | ${req.method} ${req.url}`);
        state.totalReqs++;
        uiUpdate();

        try {
            const body = req.body_b64
                ? Uint8Array.from(atob(req.body_b64), c => c.charCodeAt(0))
                : null;
            const resp = await gmRequest({
                method:  req.method,
                url:     req.url,
                headers: req.headers || {},
                body,
            });
            const bodyBytes = resp.body ? new Uint8Array(resp.body) : new Uint8Array(0);
            log(`[fetch] tid=${tidHex} | ← ${resp.status} (${bodyBytes.length}B)`);
            sendHttpResp(tid, resp.status, resp.statusText || 'OK', resp.headers, safeBase64(bodyBytes));
        } catch(e) {
            log(`[fetch] tid=${tidHex} | 错误: ${e.message}`);
            sendHttpResp(tid, 502, 'Bad Gateway', {}, '');
        }
    }

    function sendHttpResp(tid, status, statusText, headers, body_b64) {
        // 过滤掉浏览器已自动解压的 content-encoding（GM_xmlhttpRequest 会自动解压 gzip）
        const filteredHeaders = {};
        for (const [k, v] of Object.entries(headers || {})) {
            const kl = k.toLowerCase();
            if (kl !== 'content-encoding' && kl !== 'transfer-encoding') {
                filteredHeaders[kl] = v;
            }
        }
        const payload = enc.encode(JSON.stringify({
            status,
            statusText: statusText || 'OK',
            headers:    filteredHeaders,
            body_b64:   typeof body_b64 === 'string' ? body_b64 : safeBase64(new Uint8Array(body_b64)),
        }));
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(packFrame(tid, T_HTTP_RESP, payload));
        }
    }

    // ── 帧分发 ───────────────────────────────────────────────
    function onFrame({ tid, ftype, payload }) {

        if (ftype === T_HTTP_FETCH) {
            // 新协议主路径：服务端拦截 TLS 后直接发 JSON 请求
            handleHttpFetch(tid, payload);
            return;
        }

        if (ftype === T_CONNECT_REQ) {
            // 兼容旧协议 / 非 HTTPS 直接 TCP 请求
            if (payload.length < 3) return;
            const hlen = payload[0];
            if (payload.length < 1 + hlen + 2) return;
            const host = dec.decode(payload.slice(1, 1 + hlen));
            const dv   = new DataView(payload.buffer, payload.byteOffset + 1 + hlen, 2);
            const port = dv.getUint16(0, false);

            if (tunnels.size >= MAX_TUNNELS) {
                ws.send(packFrame(tid, T_CONNECT_FAIL, enc.encode('too many tunnels')));
                return;
            }
            if (port !== 80 && port !== 443) {
                ws.send(packFrame(tid, T_CONNECT_FAIL, enc.encode('browser node: HTTP/HTTPS only')));
                return;
            }
            log(`[node] 新隧道 tid=${tid.toString(16).padStart(8,'0')} → ${host}:${port}`);
            tunnelCreate(tid, host, port);
            ws.send(packFrame(tid, T_CONNECT_OK, null));
            state.tunnelCnt++;
            uiUpdate();

        } else if (ftype === T_DATA) {
            tunnelFeed(tid, payload);
            const t = tunnels.get(tid);
            if (t && t.state === 'open') {
                const bytes = concatChunks(t.bufChunks);
                if (dec.decode(bytes).includes('\r\n\r\n')) {
                    handleTunnel(tid);
                }
            }

        } else if (ftype === T_CLOSE) {
            tunnelClose(tid);
            state.tunnelCnt = Math.max(0, state.tunnelCnt - 1);
            uiUpdate();
        }
    }

    // ════════════════════════════════════════════════════════
    //  保活策略
    // ════════════════════════════════════════════════════════
    let wakeLock = null;

    async function acquireWakeLock() {
        if (!('wakeLock' in navigator)) return;
        try {
            wakeLock = await navigator.wakeLock.request('screen');
            log('Screen Wake Lock 已获取（防止后台休眠）');
            wakeLock.addEventListener('release', () => {
                wakeLock = null;
                // 页面重新可见时重新获取
            });
        } catch (e) {
            log('Wake Lock 不可用: ' + e.message);
        }
    }

    // 页面可见时重新连接 + 重新获取 Wake Lock
    document.addEventListener('visibilitychange', async () => {
        if (document.visibilityState === 'visible') {
            if (enabled && (!ws || ws.readyState > 1)) connect();
            if (!wakeLock) await acquireWakeLock();
        }
    });

    // 阻止页面关闭（提示用户）
    window.addEventListener('beforeunload', (e) => {
        if (enabled && ws && ws.readyState === WebSocket.OPEN) {
            e.preventDefault();
            return e.returnValue = '关闭此页面将断开 NFD 代理节点，确定要关闭吗？';
        }
    });

    // SharedWorker 后台保活（如果支持）
    // 在当前标签页关闭后继续通过 SharedWorker 保持心跳连接
    function trySharedWorkerKeepalive() {
        if (typeof SharedWorker === 'undefined') return;
        try {
            const workerCode = `
                let ws = null, timer = null;
                onconnect = function(e) {
                    const port = e.ports[0];
                    port.onmessage = function(ev) {
                        if (ev.data.type === 'ping') {
                            if (ws && ws.readyState === 1) ws.send(JSON.stringify({type:'ping'}));
                        }
                    };
                };
            `;
            const blob   = new Blob([workerCode], { type: 'application/javascript' });
            const url    = URL.createObjectURL(blob);
            const worker = new SharedWorker(url);
            worker.port.start();
            setInterval(() => worker.port.postMessage({ type: 'ping' }), 30000);
            log('SharedWorker 后台保活已启动');
        } catch (e) {
            log('SharedWorker 不可用: ' + e.message);
        }
    }

    // ════════════════════════════════════════════════════════
    //  123 云盘快速下载（调用 189.qaiu.top 解析接口）
    // ════════════════════════════════════════════════════════
    const PARSER_API = 'https://189.qaiu.top/parser';

    /** 检测当前页面是否为 123 云盘分享链接 */
    function is123SharePage() {
        return /^https?:\/\/www\.123(pan|link|yunpan)\.com\/s\//.test(location.href);
    }

    /** 从页面提取分享密码（如果有密码输入框） */
    function extract123Pwd() {
        const el = document.querySelector('input[placeholder*="密码"]') ||
                   document.querySelector('input[placeholder*="提取码"]');
        return el ? el.value.trim() : '';
    }

    /** 调用 189.qaiu.top 解析接口 */
    function parse123Link(shareUrl, pwd) {
        const token = cfgGet(CFG_KEYS.PARSER_TOKEN, '');
        if (!token) {
            alert('请先在 NFD 设置中配置解析 Token（需在 189.qaiu.top 注册获取）');
            return;
        }
        const params = new URLSearchParams({ url: shareUrl, token });
        if (pwd) params.set('pwd', pwd);
        const apiUrl = PARSER_API + '?' + params.toString();

        // 显示加载提示
        showParseResult('解析中...', true);

        GM_xmlhttpRequest({
            method: 'GET',
            url: apiUrl,
            responseType: 'json',
            timeout: 30000,
            onload(r) {
                try {
                    const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
                    if (data && (data.url || data.data)) {
                        const dlUrl = data.url || data.data?.url || data.data?.downloadUrl || '';
                        const fileName = data.fileName || data.data?.fileName || '文件';
                        if (dlUrl) {
                            showParseResult(`✅ ${fileName}`, false, dlUrl);
                        } else {
                            showParseResult('❌ 解析成功但未获取到下载链接: ' + JSON.stringify(data));
                        }
                    } else {
                        showParseResult('❌ ' + (data?.msg || data?.message || JSON.stringify(data)));
                    }
                } catch (e) {
                    showParseResult('❌ 响应解析失败: ' + e.message);
                }
            },
            onerror(e) { showParseResult('❌ 网络错误: ' + (e.error || 'unknown')); },
            ontimeout() { showParseResult('❌ 请求超时'); },
        });
    }

    /** 快检下载结果浮层 */
    let parsePopup = null;
    function showParseResult(msg, loading, downloadUrl) {
        if (parsePopup) parsePopup.remove();
        parsePopup = document.createElement('div');
        Object.assign(parsePopup.style, {
            position: 'fixed', top: '50%', left: '50%', transform: 'translate(-50%,-50%)',
            zIndex: '2147483647', background: 'rgba(15,23,42,.96)', color: '#e2e8f0',
            padding: '24px 32px', borderRadius: '16px', fontSize: '14px', maxWidth: '480px',
            fontFamily: '-apple-system,PingFang SC,Microsoft YaHei,sans-serif',
            boxShadow: '0 12px 48px rgba(0,0,0,.45)', backdropFilter: 'blur(16px)',
            border: '1px solid rgba(255,255,255,.1)', textAlign: 'center', lineHeight: '1.8',
        });
        let html = `<div style="font-size:16px;font-weight:700;color:#38bdf8;margin-bottom:12px">🚀 123 云盘解析</div>`;
        html += `<div style="word-break:break-all">${msg}</div>`;
        if (downloadUrl) {
            html += `<div style="margin-top:14px;display:flex;gap:8px;justify-content:center">
                <a href="${downloadUrl}" target="_blank" style="display:inline-block;padding:8px 20px;background:rgba(52,211,153,.2);color:#34d399;
                   border-radius:8px;text-decoration:none;font-size:13px;border:1px solid rgba(52,211,153,.3)">📥 下载文件</a>
                <button id="nfd-parse-copy" style="padding:8px 20px;background:rgba(56,189,248,.15);color:#38bdf8;
                   border:1px solid rgba(56,189,248,.25);border-radius:8px;cursor:pointer;font-size:13px;font-family:inherit">📋 复制链接</button>
            </div>`;
        }
        html += `<button id="nfd-parse-close" style="position:absolute;top:8px;right:12px;background:none;border:none;
                  color:#64748b;font-size:18px;cursor:pointer;padding:4px">✕</button>`;
        parsePopup.innerHTML = html;
        document.body.appendChild(parsePopup);
        parsePopup.querySelector('#nfd-parse-close')?.addEventListener('click', () => parsePopup.remove());
        if (downloadUrl) {
            parsePopup.querySelector('#nfd-parse-copy')?.addEventListener('click', () => {
                navigator.clipboard?.writeText(downloadUrl).then(() => {
                    const btn = parsePopup.querySelector('#nfd-parse-copy');
                    if (btn) { btn.textContent = '✅ 已复制'; setTimeout(() => btn.textContent = '📋 复制链接', 1500); }
                });
            });
        }
    }

    // ════════════════════════════════════════════════════════
    //  日志
    // ════════════════════════════════════════════════════════
    const logs = [];
    function log(msg) {
        const t   = new Date().toLocaleTimeString('zh-CN', { hour12: false });
        const line = `${t} ${msg}`;
        logs.push(line);
        if (logs.length > 200) logs.shift();
        console.log('[NFD]', msg);
        uiUpdateLog();
    }

    // ════════════════════════════════════════════════════════
    //  UI — 悬浮状态徽章 + 配置面板
    // ════════════════════════════════════════════════════════
    const STATUS_COLOR = {
        disconnected: '#e5e7eb',
        connecting:   '#fbbf24',
        connected:    '#34d399',
    };
    const STATUS_LABEL = {
        disconnected: '未连接',
        connecting:   '连接中',
        connected:    '运行中',
    };

    let panelOpen = false;
    let settingsOpen = false;
    let badge, panel;

    function uiBuild() {
        // ── 徽章 ──
        badge = document.createElement('div');
        badge.id = 'nfd-badge';
        Object.assign(badge.style, {
            position:     'fixed',
            bottom:       '20px',
            right:        '16px',
            zIndex:       '2147483647',
            display:      'flex',
            alignItems:   'center',
            gap:          '7px',
            background:   'rgba(15,23,42,.92)',
            color:        '#f1f5f9',
            padding:      '8px 14px',
            borderRadius: '24px',
            fontSize:     '12px',
            fontFamily:   '-apple-system,PingFang SC,Microsoft YaHei,sans-serif',
            cursor:       'pointer',
            backdropFilter: 'blur(10px)',
            boxShadow:    '0 4px 20px rgba(0,0,0,.25)',
            userSelect:   'none',
            transition:   'transform .15s',
        });
        badge.innerHTML = `
            <span id="nfd-dot" style="width:8px;height:8px;border-radius:50%;background:${STATUS_COLOR.disconnected};flex-shrink:0;transition:background .3s"></span>
            <span id="nfd-label" style="white-space:nowrap">NFD 节点</span>
            <span id="nfd-cnt" style="font-size:10px;color:#94a3b8"></span>
        `;
        badge.addEventListener('click', () => {
            panelOpen = !panelOpen;
            panel.style.display = panelOpen ? 'flex' : 'none';
        });
        badge.addEventListener('mouseenter', () => badge.style.transform = 'scale(1.05)');
        badge.addEventListener('mouseleave', () => badge.style.transform = 'scale(1)');

        // ── 面板 ──
        panel = document.createElement('div');
        panel.id = 'nfd-panel';
        Object.assign(panel.style, {
            position:     'fixed',
            bottom:       '64px',
            right:        '16px',
            zIndex:       '2147483646',
            width:        '340px',
            maxHeight:    '520px',
            overflowY:    'auto',
            background:   'rgba(15,23,42,.97)',
            color:        '#e2e8f0',
            borderRadius: '16px',
            padding:      '18px',
            fontFamily:   '-apple-system,PingFang SC,Microsoft YaHei,sans-serif',
            fontSize:     '13px',
            boxShadow:    '0 8px 40px rgba(0,0,0,.4)',
            backdropFilter: 'blur(16px)',
            display:      'none',
            flexDirection:'column',
            gap:          '12px',
            border:       '1px solid rgba(255,255,255,.08)',
        });
        panel.innerHTML = `
            <div style="display:flex;align-items:center;justify-content:space-between">
                <span style="font-weight:700;font-size:14px;color:#38bdf8">📡 NFD 浏览器节点</span>
                <div style="display:flex;align-items:center;gap:8px">
                    <label style="display:flex;align-items:center;gap:6px;cursor:pointer">
                        <input type="checkbox" id="nfd-toggle" style="width:16px;height:16px;cursor:pointer;accent-color:#38bdf8"/>
                        <span id="nfd-toggle-label" style="font-size:11px;color:#94a3b8">启用</span>
                    </label>
                </div>
            </div>

            <div id="nfd-status-bar" style="padding:8px 12px;border-radius:8px;background:rgba(255,255,255,.05);font-size:11px;color:#94a3b8">
                状态：<span id="nfd-status-text">未连接</span>
                &nbsp;|&nbsp; 隧道：<span id="nfd-tunnel-cnt">0</span>
                &nbsp;|&nbsp; 累计：<span id="nfd-total-cnt">0</span>
            </div>

            <!-- 快捷操作区 -->
            <div style="display:flex;gap:8px">
                <button id="nfd-reconnect" style="flex:1;padding:8px;border:none;border-radius:8px;
                    background:rgba(52,211,153,.12);color:#34d399;cursor:pointer;font-size:12px;
                    font-family:inherit;border:1px solid rgba(52,211,153,.2)">
                    🔁 重连
                </button>
                <button id="nfd-btn-settings" style="flex:1;padding:8px;border:none;border-radius:8px;
                    background:rgba(148,163,184,.1);color:#94a3b8;cursor:pointer;font-size:12px;
                    font-family:inherit;border:1px solid rgba(148,163,184,.15)">
                    ⚙️ 设置
                </button>
                <button id="nfd-btn-parse" style="flex:1;padding:8px;border:none;border-radius:8px;
                    background:rgba(251,191,36,.12);color:#fbbf24;cursor:pointer;font-size:12px;
                    font-family:inherit;border:1px solid rgba(251,191,36,.2);display:none">
                    🚀 解析下载
                </button>
            </div>

            <!-- 设置区域（默认隐藏） -->
            <div id="nfd-settings" style="display:none;flex-direction:column;gap:10px;
                 padding:12px;background:rgba(0,0,0,.2);border-radius:10px;border:1px solid rgba(255,255,255,.05)">
                <div style="font-size:11px;color:#64748b;font-weight:700;letter-spacing:.08em;margin-bottom:2px">⚙️ 连接设置</div>
                <div style="display:flex;flex-direction:column;gap:4px">
                    <div style="font-size:10px;color:#475569">服务端地址</div>
                    <input id="nfd-server" type="text"
                        placeholder="${DEFAULT_SERVER}"
                        style="background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);
                               border-radius:7px;padding:7px 10px;color:#f1f5f9;font-size:12px;
                               font-family:monospace;outline:none;width:100%;box-sizing:border-box"/>
                </div>
                <div style="display:flex;flex-direction:column;gap:4px">
                    <div style="font-size:10px;color:#475569">接入密钥 (可空)</div>
                    <input id="nfd-secret" type="password"
                        placeholder="Bearer Token"
                        style="background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);
                               border-radius:7px;padding:7px 10px;color:#f1f5f9;font-size:12px;
                               outline:none;width:100%;box-sizing:border-box"/>
                </div>
                <div style="display:flex;align-items:center;gap:8px">
                    <input type="checkbox" id="nfd-default" style="width:15px;height:15px;cursor:pointer;accent-color:#38bdf8"/>
                    <label for="nfd-default" style="cursor:pointer;font-size:12px;color:#94a3b8">
                        设为默认节点（兜底分发）
                    </label>
                </div>

                <div style="font-size:11px;color:#64748b;font-weight:700;letter-spacing:.08em;margin-top:6px">🚀 123 云盘解析</div>
                <div style="display:flex;flex-direction:column;gap:4px">
                    <div style="font-size:10px;color:#475569">解析 Token（189.qaiu.top 注册获取）</div>
                    <input id="nfd-parser-token" type="password"
                        placeholder="189 用户 token"
                        style="background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);
                               border-radius:7px;padding:7px 10px;color:#f1f5f9;font-size:12px;
                               outline:none;width:100%;box-sizing:border-box"/>
                </div>

                <button id="nfd-save" style="padding:8px;border:none;border-radius:8px;
                    background:rgba(56,189,248,.15);color:#38bdf8;cursor:pointer;font-size:12px;
                    font-family:inherit;border:1px solid rgba(56,189,248,.25);margin-top:4px">
                    💾 保存设置
                </button>
            </div>

            <div style="background:rgba(0,0,0,.3);border-radius:8px;padding:8px;
                        font-size:11px;color:#64748b;font-family:monospace;max-height:100px;
                        overflow-y:auto;line-height:1.6" id="nfd-log">
                等待日志...
            </div>

            <div style="font-size:10px;color:#334155;text-align:center;line-height:1.6">
                节点 ID: <span id="nfd-nodeid" style="font-family:monospace"></span><br/>
                ⚠️ 浏览器节点仅支持 HTTP/HTTPS 请求转发
            </div>
        `;
        document.body.appendChild(badge);
        document.body.appendChild(panel);

        // ── 填充初始值 ──
        document.getElementById('nfd-server').value  = cfgGet(CFG_KEYS.SERVER, '');
        document.getElementById('nfd-secret').value  = cfgGet(CFG_KEYS.SECRET, '');
        document.getElementById('nfd-parser-token').value = cfgGet(CFG_KEYS.PARSER_TOKEN, '');
        document.getElementById('nfd-default').checked = cfgGet(CFG_KEYS.DEFAULT, false);
        document.getElementById('nfd-toggle').checked  = cfgGet(CFG_KEYS.ENABLED, false);
        document.getElementById('nfd-nodeid').textContent = getNodeId().slice(0, 16);

        // ── 123 云盘页面显示解析按钮 ──
        if (is123SharePage()) {
            document.getElementById('nfd-btn-parse').style.display = '';
        }

        // ── 事件 ──
        document.getElementById('nfd-toggle').addEventListener('change', (e) => {
            enabled = e.target.checked;
            cfgSet(CFG_KEYS.ENABLED, enabled);
            document.getElementById('nfd-toggle-label').textContent = enabled ? '已启用' : '已停止';
            if (enabled) {
                connect();
                acquireWakeLock();
            } else {
                disconnect();
            }
        });

        document.getElementById('nfd-btn-settings').addEventListener('click', () => {
            settingsOpen = !settingsOpen;
            document.getElementById('nfd-settings').style.display = settingsOpen ? 'flex' : 'none';
        });

        document.getElementById('nfd-save').addEventListener('click', () => {
            const srv = document.getElementById('nfd-server').value.trim();
            const sec = document.getElementById('nfd-secret').value.trim();
            const def = document.getElementById('nfd-default').checked;
            const ptk = document.getElementById('nfd-parser-token').value.trim();
            cfgSet(CFG_KEYS.SERVER, srv || DEFAULT_SERVER);
            cfgSet(CFG_KEYS.SECRET, sec);
            cfgSet(CFG_KEYS.DEFAULT, def);
            cfgSet(CFG_KEYS.PARSER_TOKEN, ptk);
            log('配置已保存');
            settingsOpen = false;
            document.getElementById('nfd-settings').style.display = 'none';
            if (enabled) { disconnect(); setTimeout(connect, 300); }
        });

        document.getElementById('nfd-reconnect').addEventListener('click', () => {
            if (!enabled) return;
            disconnect();
            setTimeout(connect, 300);
        });

        document.getElementById('nfd-btn-parse').addEventListener('click', () => {
            parse123Link(location.href, extract123Pwd());
        });
    }

    function uiUpdate() {
        if (!badge) return;
        const dot   = document.getElementById('nfd-dot');
        const label = document.getElementById('nfd-label');
        const cnt   = document.getElementById('nfd-cnt');
        if (dot)   dot.style.background = STATUS_COLOR[state.status];
        if (label) label.textContent = 'NFD ' + STATUS_LABEL[state.status];
        if (cnt)   cnt.textContent = state.tunnelCnt > 0 ? `${state.tunnelCnt}隧道` : '';

        const stEl = document.getElementById('nfd-status-text');
        const tcEl = document.getElementById('nfd-tunnel-cnt');
        const totEl = document.getElementById('nfd-total-cnt');
        if (stEl) stEl.textContent = STATUS_LABEL[state.status];
        if (tcEl) tcEl.textContent = state.tunnelCnt;
        if (totEl) totEl.textContent = state.totalReqs;
    }

    function uiUpdateLog() {
        const el = document.getElementById('nfd-log');
        if (!el) return;
        el.textContent = logs.slice(-20).join('\n');
        el.scrollTop   = el.scrollHeight;
    }

    // ════════════════════════════════════════════════════════
    //  油猴菜单
    // ════════════════════════════════════════════════════════
    GM_registerMenuCommand('⚡ 切换 NFD 节点', () => {
        enabled = !enabled;
        cfgSet(CFG_KEYS.ENABLED, enabled);
        if (enabled) { connect(); acquireWakeLock(); log('节点已启用'); }
        else         { disconnect(); log('节点已停止'); }
    });

    GM_registerMenuCommand('⚙️ 打开设置面板', () => {
        panelOpen = true;
        if (panel) panel.style.display = 'flex';
    });

    GM_registerMenuCommand('📋 复制节点 ID', () => {
        navigator.clipboard?.writeText(getNodeId())
            .then(() => log('节点 ID 已复制到剪贴板'));
    });

    // ════════════════════════════════════════════════════════
    //  启动
    // ════════════════════════════════════════════════════════
    function init() {
        // 等 body 就绪
        if (!document.body) {
            document.addEventListener('DOMContentLoaded', init);
            return;
        }
        uiBuild();
        uiUpdate();

        enabled = cfgGet(CFG_KEYS.ENABLED, false);
        if (enabled) {
            connect();
            acquireWakeLock();
            trySharedWorkerKeepalive();
        }

        log('NFD 浏览器节点 v1.5.0 已加载');
        log(`节点 ID: ${getNodeId().slice(0,16)}`);
    }

    init();

})();