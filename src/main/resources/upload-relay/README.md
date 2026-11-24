# 网盘上传中转（upload-relay）

开源的独立网盘上传中转服务。当前实现与 nfd-vip 后台**模式 2**（解析控制 → 蓝奏云 → 上传中转方式 = 中转服务器）的协议一致：把浏览器上传流式转发到蓝奏云，并提供浏览器直连本进程的测速接口。

nfd-vip 闭源，所以中转程序放在本仓库维护。它不进入 `nfd-proxy` 的 Vert.x 进程，用 Node.js 单独启动。后续新增网盘时在本目录扩展，不改现有蓝奏路由。

## 环境

- Node.js 18 或更高。只用内置模块 `node:http`、`node:https`、`node:crypto`、`node:url`。
- 无第三方依赖，不需要 `package.json`，也不需要 `npm install`。
- 运行机器要能访问 `https://pc.woozooo.com/`（上传目标与测速探测的上游）。

## 快速启动

在本目录执行：

```bash
RELAY_SECRET='与 nfd-vip 后台一致的密钥' PORT=6450 node server.mjs
```

- `RELAY_SECRET`：必填。未设置时进程打印错误并退出。密钥只用于解密票据，不会写入日志。
- `PORT`：可选，默认 `6450`。

看到 `蓝奏云上传中转已启动: http://0.0.0.0:6450/` 表示已在所有网卡监听。公网只开放 HTTPS 反代，用防火墙拦住对 `6450` 的直接访问。

自测：

```bash
node --test server.test.js
```

用例会在本机拉起服务并转发一次模拟上传。`/speedtest/ping` 会真实探测蓝奏云；探测失败只影响返回里的 `lanzouReachable`，不会单独导致用例失败。

## 在 nfd-vip 中配置并验证

1. 先按上面的方式启动中转，`RELAY_SECRET` 与后台使用同一串密钥。
2. 打开 nfd-vip 后台：**解析控制 → 蓝奏云 → 上传中转方式 = 中转服务器**（模式 2）。
3. 中转地址填浏览器能直接打开的根地址，例如 `https://relay.example.com/`。测速由浏览器直连该地址，不经过 nfd-vip 进程。
4. 中转密钥填 `RELAY_SECRET` 的原文。
5. 点击「速度测试」。通过时应能看到中转可达，以及本机到蓝奏云的连通性和耗时。浏览器实际请求的是：
   - `GET /speedtest/ping?ticket=...`
   - `POST /speedtest/upload?ticket=...`（正文在中转进程内丢弃，不会发给蓝奏云）
6. 测速通过后再上传一个真实文件，确认返回 `code: 200`，并且 `data.fileId` 有值。

密钥不一致、票据过期或用错票据类型时，接口返回 HTTP 403，正文 `code` 为 403。

## 生产部署

把 `server.mjs` 放到服务器即可，例如 `/opt/nfd-upload-relay/server.mjs`。生产机不需要 `server.test.js`。

### systemd

`/etc/nfd-upload-relay.env`，权限 `600`，属主为运行用户。不要写 `export`，`#` 开头会被当成注释：

```
RELAY_SECRET=替换为与后台一致的密钥
PORT=6450
```

`/etc/systemd/system/nfd-upload-relay.service`：

```
[Unit]
Description=nfd upload relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nfdrelay
Group=nfdrelay
WorkingDirectory=/opt/nfd-upload-relay
EnvironmentFile=/etc/nfd-upload-relay.env
ExecStart=/usr/bin/node server.mjs
Restart=on-failure
RestartSec=3
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

先写好上面的 env 文件，再执行：

```bash
sudo useradd --system --home /opt/nfd-upload-relay --shell /usr/sbin/nologin nfdrelay
sudo install -d -o nfdrelay -g nfdrelay /opt/nfd-upload-relay
sudo install -o nfdrelay -g nfdrelay -m 644 server.mjs /opt/nfd-upload-relay/server.mjs
sudo chown root:nfdrelay /etc/nfd-upload-relay.env
sudo chmod 640 /etc/nfd-upload-relay.env
sudo systemctl daemon-reload
sudo systemctl enable --now nfd-upload-relay
```

### Nginx

上传是流式转发。反代必须放行足够大的请求体，并且不能先把请求体收完再交给 Node。否则大文件会先落盘，客户端中途断开时上游仍可能继续收到已经缓冲的数据。

```nginx
server {
    listen 443 ssl;
    server_name relay.example.com;

    ssl_certificate     /etc/ssl/certs/relay.example.com.crt;
    ssl_certificate_key /etc/ssl/private/relay.example.com.key;

    client_max_body_size 100m;
    client_body_timeout 3600s;

    location / {
        proxy_pass http://127.0.0.1:6450;
        proxy_http_version 1.1;
        proxy_request_buffering off;
        proxy_buffering off;

        proxy_connect_timeout 60s;
        proxy_send_timeout 3600s;
        proxy_read_timeout 3600s;

        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

这三项决定大文件能不能传完：

- `client_max_body_size 100m;`：Nginx 默认 1m，超限直接 413，请求到不了中转。100m 是上传体积上限，不是测速上限。测速在程序里另有 10MiB 硬限制。按实际文件大小调整，不要改成 10m。
- `proxy_request_buffering off;`：边收边转。保持默认开启时，Nginx 会先把整个文件写入临时文件。
- `proxy_send_timeout` 与 `proxy_read_timeout`：上传大文件、等待蓝奏响应时，默认 60 秒容易被中途切断。`proxy_buffering off` 只影响响应，不能代替 `proxy_request_buffering off`。

### Caddy

Caddy 默认流式转发请求体，没有 Nginx 那种 1m 默认上限。不要打开 `buffer_requests`，也不要加会先把正文读进内存的 `request_body` 限制器。

```caddy
relay.example.com {
	reverse_proxy 127.0.0.1:6450 {
		flush_interval -1
		transport http {
			dial_timeout 60s
			read_timeout 3600s
			write_timeout 3600s
		}
	}
}
```

证书由 Caddy 自动申请。`write_timeout` 是向上游写出请求体的间隔上限，传大文件时要留够。`flush_interval -1` 让响应一写出就发给浏览器。

## 接口与票据

除 `OPTIONS` 预检返回 204 外，响应都是 JSON（`Content-Type: application/json;charset=utf-8`）。跨域头固定为：

- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: GET, POST, OPTIONS`
- `Access-Control-Allow-Headers: Content-Type`

票据在查询参数 `ticket` 中。格式：

```text
base64url( iv[12] || AES-256-GCM 密文 || tag[16] )
```

密钥是 `SHA-256(RELAY_SECRET)` 的原始 32 字节。明文是 UTF-8 JSON。解密失败时返回 403，响应里不会出现密钥或蓝奏 Cookie。

| 请求 | 行为 |
| --- | --- |
| `POST /?ticket=...` | 蓝奏上传。请求体原样流转发到 `https://pc.woozooo.com/html5up.php`，Cookie 取自票据字段 `c`，并附带固定浏览器 User-Agent。去掉末尾斜杠后，只要不是测速路径，`POST` 都按上传处理。 |
| `GET /speedtest/ping?ticket=...` | 测速探测。返回中转正常、到蓝奏云的连通性、HTTP 状态和耗时，以及本票据允许的最大字节数。探测超时 8 秒。 |
| `POST /speedtest/upload?ticket=...` | 上行测速。读完请求体后丢弃，返回字节数和耗时，不访问蓝奏云。 |
| `OPTIONS`（任意路径） | CORS 预检，204。 |

上传票据和测速票据不能混用：

- 用途看 JSON 的 `a`，没有则看 `action`。值为 `speedtest` 的是测速票据，其它情况视为上传票据。
- 上传票据只能用于上传。用在测速接口上返回 403，说明不能用于测速。
- 测速票据只能用于 `/speedtest/ping` 和 `/speedtest/upload`。用在上传接口上返回 403，说明不能用于上传。
- 测速票据不允许携带登录字段 `c`。带了直接 403，不会把这段内容发出去。
- 两类票据都检查 `e`（过期时间，Unix 毫秒）。过期返回 403。
- 测速票据的 `m` 是本次允许的最大字节数，必须是大于等于 1 的有限数字。服务端再与 **10MiB**（`10 * 1024 * 1024`）取较小值。`Content-Length` 或实际正文超过该值返回 413。这个上限只约束测速，不约束蓝奏上传。上传大小由反代的 `client_max_body_size` 和蓝奏云决定。

上传被蓝奏接受时，HTTP 200，正文为 `{ "code": 200, "data": { "fileId": "...", "fileName": "..." } }`。蓝奏业务失败时 HTTP 仍可能是 200，但 `code` 为 500。连不上蓝奏，或蓝奏返回的不是 JSON 时，HTTP 502。客户端中途断开上传时，到蓝奏的上游请求会被立刻销毁。

## 与发行包的关系

`server.mjs` 随资源打进 `nfd-proxy.jar`，Java 进程不会执行它。`pom.xml` 对 `upload-relay/` 关闭了 Maven 资源过滤，避免 `${...}` 被当成 POM 属性替换。部署用仓库里的 `server.mjs`，或从 jar 的 `BOOT-INF/classes/upload-relay/server.mjs` 取出后再用 Node 启动。

## 后续扩展

新增网盘时保持现在的蓝奏行为不变，按下面的边界加能力：

- 新网盘使用自己的路径前缀转发上传。`POST /` 继续只表示蓝奏 `html5up.php`。
- 继续使用同一套票据外壳（`base64url`、12 字节 IV、AES-256-GCM、SHA-256 密钥）。网盘差异放在 JSON 里，例如用途字段和上游需要的凭据字段。
- `/speedtest/ping` 与 `/speedtest/upload` 继续拒绝上传票据，也拒绝任何带 `c` 的票据。测速正文继续在进程内丢弃，并保留 10MiB 硬上限。
- 转发保持流式：把客户端请求体 `pipe` 到上游，客户端断开就销毁上游请求。这样现有的 `proxy_request_buffering off` 对新网盘仍然适用。
