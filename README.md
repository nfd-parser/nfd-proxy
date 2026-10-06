# nfd-proxy

基于 Vert.x 的 HTTP 代理服务，给 [nfd](https://github.com/qaiu/netdisk-fast-download) 解析提供出口 IP。  
同一进程还可作为 **DNode 控制面** 或 **出口节点**：下游节点通过 WebSocket 接入后，代理 CONNECT / HTTP 会按地域就近分发。

## 特性

- Vert.x 异步网络，支持 HTTP 代理（含 HTTPS `CONNECT`）
- 可选用户名密码（`Proxy-Authorization`），可启动时随机生成
- 可选前置代理（`proxy-pre`，http / socks4 / socks5）
- DNode 分布式出口：节点 WebSocket 接入、隧道复用、同城 → 同省 → 默认节点 → 最低负载
- 无可用节点时默认回退本机直连，不影响原有代理行为
- 协议与 Python / C / PHP / 油猴节点一致

## 环境

独立 IP 的服务器 + **JDK 17**。

## 打包与运行

```bash
mvn clean package
java -jar target/nfd-proxy.jar
```

部署：把 `src/main/resources/app.yml` 和 `target/nfd-proxy.jar` 放到同一目录后启动。

```bash
nohup java -jar nfd-proxy.jar > out-nfd-proxy.log 2>&1 &
```

指定配置文件前缀（读取 `app-dev.yml`）：

```bash
java -jar nfd-proxy.jar app-dev
```

## HTTP 代理配置

`app.yml` 中 `proxy-server`：

```yml
proxy-server:
  randUserPwd: false   # true 时启动生成随机用户名密码，日志会打印
  type: http           # 目前对外代理为 HTTP 隧道
  port: 8899
  username: 您的用户名   # 线上建议配置
  password: 您的密码
```

启动后日志：

```
=============server info=================
port: 8899
username: xxx
password: xxx
dnode-server: off
dnode-client: off
```

可选前置代理（本机再走一层上游）：

```yml
proxy-pre:
  host: 127.0.0.1
  port: 7890
  type: http           # http / socks4 / socks5
  username:
  password:
```

### 在 netdisk-fast-download 中使用

在 nfd 所在机器的配置里添加：

```yml
proxy:
  - panTypes: pod,pgd
    type: http
    host: 您的IP
    port: 8899
    username: 您的用户名
    password: 您的密码
  - panTypes: fj,ye,iz
    type: http
    host: 您的IP
    port: 8899
    username: 您的用户名
    password: 您的密码
```

## DNode 分布式节点

```
nfd / 下载客户端
        │  HTTP 代理 (8899)
        ▼
   nfd-proxy（控制面 dnode-server）
        │  WebSocket /ws/node
        ▼
   若干出口节点（dnode-client，可为另一台 nfd-proxy）
        │
        ▼
      目标站点
```

调度顺序：**同城 → 同省 → `default-node` → 当前负载最低**。  
`fallback-direct: true` 时，没有节点或开隧道失败会走本机直连。

### 控制面（`dnode-server`）

默认关闭。开启后监听节点接入与管理接口。

```yml
dnode-server:
  enabled: true
  port: 9000
  path: /ws/node
  secret: "节点接入口令"       # 空则不校验
  admin-token: "管理口令"      # 空则管理接口全部 401
  fallback-direct: true
```

节点连接：`ws://控制面:9000/ws/node`（前面有 TLS 反代时用 `wss://`）。  
握手可用 `Authorization: Bearer <secret>` 或查询参数 `token`。  
节点可带请求头：`X-Node-ID`、`X-Platform`、`X-Version`、`X-Default-Node`。

| 接口 | 说明 |
|------|------|
| `GET /api/stats` | 节点数、隧道数、流量（无需 token） |
| `GET /api/admin/nodes` | 节点详情（需 `admin-token`） |
| `POST /api/admin/node/{id}/kick` | 踢掉单个节点 |
| `POST /api/admin/nodes/kick-all` | 踢掉全部节点 |

管理鉴权：`Authorization: Bearer <admin-token>` 或 `?token=`。`{id}` 可以是完整 `conn_id` / `device_id`，或唯一前缀。

```bash
curl -s http://127.0.0.1:9000/api/stats
curl -s -H "Authorization: Bearer 管理口令" http://127.0.0.1:9000/api/admin/nodes
```

### 出口节点（`dnode-client`）

把本机当作下游出口，接入远端控制面。`node-id` 为空时会生成并写入工作目录 `node_config.json`（已加入 `.gitignore`）。

```yml
dnode-client:
  enabled: true
  server: wss://dnode.example.com/ws/node
  secret: "与控制面一致"
  node-id: ""
  default-node: false    # 无同城/同省匹配时优先选中
  ssl-insecure: false    # 仅调试自签证书时设 true
```

同一进程可以同时开 `dnode-server` 和 `dnode-client`（本机既调度也当出口）。断线会指数退避重连（2s ~ 60s）。

### 隧道协议（二进制帧）

与其它语言节点一致：

```
[4B tunnel_id BE][1B type][payload...]
```

| type | 含义 | payload |
|------|------|---------|
| `0x01` CONNECT_REQ | 建立到目标的 TCP | `[1B host_len][host][2B port BE]` |
| `0x02` CONNECT_OK | 连接成功 | 空 |
| `0x03` CONNECT_FAIL | 连接失败 | 原因字符串 |
| `0x04` DATA | 双向数据 | 原始字节 |
| `0x05` CLOSE | 关闭隧道 | 空 |

心跳为 WebSocket **文本** JSON：`{"type":"ping"}` / `{"type":"pong"}`。

## 其它资源

- `src/main/resources/tampermonkey/`：油猴脚本（如 vConsole）
- `src/main/resources/edge/`：123 网盘 EdgeOne 边缘函数示例（JSON 解析 / 纯 302）
