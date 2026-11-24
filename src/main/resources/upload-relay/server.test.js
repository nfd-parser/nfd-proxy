import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { spawn } from 'node:child_process'
import { createCipheriv, createHash, randomBytes } from 'node:crypto'
import { once } from 'node:events'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRelayServer } from './server.mjs'

const SECRET = 'pwexample'
const DIR = path.dirname(fileURLToPath(import.meta.url))
const FIVE_MB = 5 * 1024 * 1024

function seal(payload, secret = SECRET) {
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', createHash('sha256').update(secret).digest(), iv)
  const body = Buffer.concat([cipher.update(JSON.stringify(payload)), cipher.final(), cipher.getAuthTag()])
  return Buffer.concat([iv, body]).toString('base64url')
}

function speedTicket(extra = {}, secret = SECRET) {
  return seal({ a: 'speedtest', action: 'speedtest', e: Date.now() + 60_000, m: FIVE_MB, ...extra }, secret)
}

function uploadTicket(secret = SECRET) {
  return seal({ c: 'phpdisk_info=cookie-value', e: Date.now() + 60_000 }, secret)
}

function listen(server) {
  server.listen(0, '127.0.0.1')
  return once(server, 'listening').then(() => server.address().port)
}

test('spawned server pings, sinks 5MB, and keeps tickets apart', async (t) => {
  const port = 18765
  const child = spawn(process.execPath, ['server.mjs'], {
    cwd: DIR,
    env: { ...process.env, RELAY_SECRET: SECRET, PORT: String(port) },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  let log = ''
  child.stdout.on('data', chunk => { log += chunk })
  child.stderr.on('data', chunk => { log += chunk })
  t.after(() => child.kill('SIGTERM'))
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(log || 'relay did not start')), 5000)
    const check = () => {
      if (!log.includes('已启动')) return
      clearTimeout(timer)
      resolve()
    }
    check()
    child.stdout.on('data', check)
    child.on('exit', code => {
      clearTimeout(timer)
      reject(new Error('relay exited ' + code + ' ' + log))
    })
  })
  await ready
  assert.equal(log.includes(SECRET), false)
  const base = `http://127.0.0.1:${port}`

  const options = await fetch(base + '/speedtest/ping', { method: 'OPTIONS' })
  assert.equal(options.status, 204)
  assert.match(options.headers.get('access-control-allow-methods'), /GET/)
  assert.match(options.headers.get('access-control-allow-methods'), /POST/)

  const ping = await fetch(base + '/speedtest/ping?ticket=' + speedTicket())
  const pingBody = await ping.json()
  assert.equal(ping.status, 200)
  assert.equal(pingBody.code, 200)
  assert.equal(pingBody.data.relayOk, true)
  assert.equal(typeof pingBody.data.lanzouReachable, 'boolean')
  assert.equal(typeof pingBody.data.lanzouLatencyMs, 'number')
  assert.equal(pingBody.data.maxBytes, FIVE_MB)
  assert.equal(JSON.stringify(pingBody).includes(SECRET), false)

  const wrong = await fetch(base + '/speedtest/ping?ticket=' + speedTicket({}, 'other-secret'))
  assert.equal(wrong.status, 403)
  const uploadOnSpeed = await fetch(base + '/speedtest/upload?ticket=' + uploadTicket(), { method: 'POST', body: 'nope' })
  assert.equal(uploadOnSpeed.status, 403)
  assert.match((await uploadOnSpeed.json()).msg, /不能用于测速/)
  const speedOnUpload = await fetch(base + '/?ticket=' + speedTicket(), { method: 'POST', body: 'nope' })
  assert.equal(speedOnUpload.status, 403)
  assert.match((await speedOnUpload.json()).msg, /不能用于上传/)
  const withCookie = await fetch(base + '/speedtest/ping?ticket=' + speedTicket({ c: 'phpdisk_info=cookie-value' }))
  assert.equal(withCookie.status, 403)
  assert.match((await withCookie.json()).msg, /登录信息/)

  const limited = speedTicket({ m: 1024 })
  const tooBig = await fetch(base + '/speedtest/upload?ticket=' + limited, {
    method: 'POST',
    body: Buffer.alloc(2048, 1),
    headers: { 'content-type': 'application/octet-stream' }
  })
  assert.equal(tooBig.status, 413)

  const uploaded = await fetch(base + '/speedtest/upload?ticket=' + speedTicket(), {
    method: 'POST',
    body: Buffer.alloc(FIVE_MB, 7),
    headers: { 'content-type': 'application/octet-stream' }
  })
  const uploadedBody = await uploaded.json()
  assert.equal(uploaded.status, 200)
  assert.equal(uploadedBody.data.bytes, FIVE_MB)
  assert.equal(typeof uploadedBody.data.serverMs, 'number')
  assert.equal(JSON.stringify(uploadedBody).includes(SECRET), false)
})

test('upload forwarding still reaches only the configured upstream', async () => {
  let hits = 0
  const stub = http.createServer((req, res) => {
    hits += 1
    req.resume()
    res.end(JSON.stringify({ zt: 1, text: [{ id: '9', name_all: 'a.zip' }] }))
  })
  const stubPort = await listen(stub)
  const relay = createRelayServer({
    secret: SECRET,
    uploadUrl: `http://127.0.0.1:${stubPort}/html5up.php`,
    probeUrl: 'http://127.0.0.1:9/'
  })
  const port = await listen(relay)
  try {
    const ok = await fetch(`http://127.0.0.1:${port}/?ticket=` + uploadTicket(), {
      method: 'POST',
      body: 'file-bytes',
      headers: { 'content-type': 'text/plain' }
    })
    const body = await ok.json()
    assert.equal(body.code, 200)
    assert.equal(body.data.fileId, '9')
    assert.equal(hits, 1)
    const blocked = await fetch(`http://127.0.0.1:${port}/?ticket=` + speedTicket(), {
      method: 'POST',
      body: 'file-bytes'
    })
    assert.equal(blocked.status, 403)
    assert.equal(hits, 1)
  } finally {
    relay.close()
    stub.close()
  }
})
