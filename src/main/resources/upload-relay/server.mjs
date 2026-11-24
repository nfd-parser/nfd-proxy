// 蓝奏云上传中转服务器（后台「解析控制 → 蓝奏云 → 上传中转方式 = 中转服务器」），协议与 worker.js 相同。
// 运行：RELAY_SECRET=与后台一致的密钥 PORT=6450 node server.mjs   （Node 18+，无第三方依赖）
// 后台中转地址填 http(s)://<本机地址>:6450/ 。
// 公网建议放在 HTTPS 反代后面，并放开上传体积、关闭请求缓冲，例如 Nginx：
//   client_max_body_size 100m;
//   proxy_request_buffering off;
// 测速（浏览器直连本进程，不经 nfd-vip）：
//   GET  /speedtest/ping?ticket=...
//   POST /speedtest/upload?ticket=...
// 测速票据 action=speedtest，不含蓝奏 Cookie，不能用于上传；上传票据也不能用于测速。

import http from 'node:http'
import https from 'node:https'
import { createDecipheriv, createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'

export const UPLOAD_URL = 'https://pc.woozooo.com/html5up.php'
export const PROBE_URL = 'https://pc.woozooo.com/'
export const HARD_MAX_BYTES = 10 * 1024 * 1024
export const PROBE_TIMEOUT_MS = 8000
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36'
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type'
}

// ticket = base64url(iv[12] + AES-256-GCM 密文 + tag[16])，key = SHA-256(RELAY_SECRET)
export function openTicket(ticket, secret) {
  const raw = Buffer.from(ticket, 'base64url')
  const decipher = createDecipheriv('aes-256-gcm', createHash('sha256').update(secret).digest(), raw.subarray(0, 12))
  decipher.setAuthTag(raw.subarray(raw.length - 16))
  return JSON.parse(Buffer.concat([decipher.update(raw.subarray(12, raw.length - 16)), decipher.final()]).toString('utf8'))
}

export function ticketPurpose(ticket) {
  const action = ticket && (ticket.a || ticket.action)
  return action === 'speedtest' ? 'speedtest' : 'upload'
}

export function speedtestLimit(ticket) {
  const requested = Number(ticket && ticket.m)
  if (!Number.isFinite(requested) || requested < 1) return 0
  return Math.min(Math.floor(requested), HARD_MAX_BYTES)
}

function reply(res, status, body) {
  if (res.headersSent || res.destroyed) return
  res.writeHead(status, { ...CORS, 'Content-Type': 'application/json;charset=utf-8' })
  res.end(JSON.stringify(body))
}

function lanzouResult(status, text) {
  let json
  try { json = JSON.parse(text) } catch { return [502, { code: 502, msg: `蓝奏云返回异常: HTTP ${status}` }] }
  if (Number(json.zt) !== 1) return [200, { code: 500, msg: json.info || '上传失败' }]
  const file = (Array.isArray(json.text) && json.text[0]) || {}
  return [200, { code: 200, data: { fileId: String(file.id ?? ''), fileName: file.name_all || '' } }]
}

function routeOf(method, pathname) {
  const path = String(pathname || '/').replace(/\/+$/, '') || '/'
  if (method === 'OPTIONS') return 'options'
  if (path === '/speedtest/ping' && method === 'GET') return 'ping'
  if (path === '/speedtest/upload' && method === 'POST') return 'speed'
  if (path === '/speedtest/ping' || path === '/speedtest/upload') return 'method'
  if (method !== 'POST') return 'method'
  return 'upload'
}

function readTicket(req, secret) {
  try {
    return openTicket(new URL(req.url, 'http://relay').searchParams.get('ticket') || '', secret)
  } catch {
    return null
  }
}

function probeLanzou(probeUrl) {
  return new Promise(resolve => {
    const started = Date.now()
    let settled = false
    const done = (reachable, status) => {
      if (settled) return
      settled = true
      resolve({ lanzouReachable: reachable, lanzouStatus: status || 0, lanzouLatencyMs: Date.now() - started })
    }
    const lib = probeUrl.startsWith('http:') ? http : https
    const req = lib.request(probeUrl, { method: 'GET', timeout: PROBE_TIMEOUT_MS, headers: { 'User-Agent': UA } }, up => {
      up.resume()
      done(true, up.statusCode)
    })
    req.on('timeout', () => { req.destroy(); done(false, 0) })
    req.on('error', () => done(false, 0))
    req.end()
  })
}

function rejectSpeedtest(ticket) {
  if (ticketPurpose(ticket) !== 'speedtest') return '上传凭证不能用于测速'
  if (ticket.c) return '测速凭证不能携带登录信息'
  if (!(ticket.e > Date.now())) return '测速凭证已过期'
  if (speedtestLimit(ticket) < 1) return '测速凭证无效'
  return ''
}

function sinkUpload(req, res, limit) {
  const declared = Number(req.headers['content-length'])
  if (Number.isFinite(declared) && declared > limit) {
    reply(res, 413, { code: 413, msg: '测速数据超过上限' })
    req.destroy()
    return
  }
  let bytes = 0
  let failed = false
  const started = Date.now()
  req.on('data', chunk => {
    if (failed) return
    bytes += chunk.length
    if (bytes > limit) {
      failed = true
      reply(res, 413, { code: 413, msg: '测速数据超过上限' })
      req.destroy()
    }
  })
  req.on('end', () => {
    if (failed) return
    reply(res, 200, { code: 200, data: { bytes, serverMs: Date.now() - started } })
  })
  req.on('error', () => {
    if (!failed) reply(res, 400, { code: 400, msg: '测速连接中断' })
  })
}

function handle(req, res, options) {
  const route = routeOf(req.method, new URL(req.url, 'http://relay').pathname)
  if (route === 'options') {
    res.writeHead(204, CORS)
    res.end()
    return
  }
  if (route === 'method') {
    req.resume()
    reply(res, 405, { code: 405, msg: '请求方法不允许' })
    return
  }
  const ticket = readTicket(req, options.secret)
  if (!ticket) {
    req.resume()
    reply(res, 403, { code: 403, msg: '上传凭证无效，请检查中转密钥是否与后台一致' })
    return
  }
  if (route === 'ping' || route === 'speed') {
    const reason = rejectSpeedtest(ticket)
    if (reason) {
      req.resume()
      reply(res, 403, { code: 403, msg: reason })
      return
    }
    const limit = speedtestLimit(ticket)
    if (route === 'speed') {
      sinkUpload(req, res, limit)
      return
    }
    req.resume()
    probeLanzou(options.probeUrl).then(probe => reply(res, 200, {
      code: 200,
      data: { relayOk: true, ...probe, maxBytes: limit, serverTime: Date.now() }
    }))
    return
  }
  if (ticketPurpose(ticket) === 'speedtest') {
    req.resume()
    reply(res, 403, { code: 403, msg: '测速凭证不能用于上传' })
    return
  }
  if (!(ticket.e > Date.now())) {
    req.resume()
    reply(res, 403, { code: 403, msg: '上传凭证已过期，请重新上传' })
    return
  }
  const headers = { 'Content-Type': req.headers['content-type'] || '', Cookie: ticket.c, 'User-Agent': UA }
  if (req.headers['content-length']) headers['Content-Length'] = req.headers['content-length']
  const lib = options.uploadUrl.startsWith('http:') ? http : https
  const upstream = lib.request(options.uploadUrl, { method: 'POST', headers }, upRes => {
    const chunks = []
    upRes.on('data', chunk => chunks.push(chunk))
    upRes.on('end', () => reply(res, ...lanzouResult(upRes.statusCode, Buffer.concat(chunks).toString('utf8'))))
  })
  upstream.on('error', e => reply(res, 502, { code: 502, msg: '连接蓝奏云失败: ' + e.message }))
  req.on('aborted', () => upstream.destroy())
  req.pipe(upstream)
}

export function createRelayServer(options) {
  const secret = options && options.secret
  if (!secret) throw new Error('缺少 RELAY_SECRET')
  const uploadUrl = options.uploadUrl || UPLOAD_URL
  const probeUrl = options.probeUrl || PROBE_URL
  return http.createServer((req, res) => handle(req, res, { secret, uploadUrl, probeUrl }))
}

function start() {
  const secret = process.env.RELAY_SECRET
  if (!secret) {
    console.error('请设置环境变量 RELAY_SECRET（与后台中转密钥一致）')
    process.exit(1)
  }
  const port = Number(process.env.PORT || 6450)
  createRelayServer({ secret }).listen(port, () => console.log(`蓝奏云上传中转已启动: http://0.0.0.0:${port}/`))
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) start()
