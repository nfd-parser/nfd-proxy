/**
 * 腾讯云 EdgeOne 边缘函数 - 123网盘(123pan)分享解析 · 302 版本
 *
 * 解析逻辑与 pan123-edge-function.js 相同, 成功时一律 302 跳转到直链,
 * 对齐 nfd 的 /parser(非 /json/parser)。适合作为下载入口单独部署:
 * 用户就近命中 EO 节点取链, Location 绑定该节点出口地市, 避免跨地市 403。
 *
 * 逻辑对齐 cn.qaiu.parser.impl.Ye2Tool:
 *   1. /b/api/share/get                      获取分享文件列表
 *   2. /b/api/v2/share/download/info         新版直链接口(优先)
 *   3. /b/api/file/download_info             旧版直链接口(回退)
 *   4. /b/api/file/batch_download_share_info 文件夹打包下载
 *
 * EO 拦截路径:
 *   GET /api/user/parse/parser?url=<分享链接>&pwd=<提取码>        302 跳转到直链
 *   GET /api/user/parse/list?url=<分享链接>&pwd=&dirId=0         返回 JSON 文件列表
 *
 * url 参数为 URL 编码后的分享链接, pwd 为提取码(可为空)。
 * 短路径 /、/d、/parser 同样 302; /list、/getFileList 返回列表。
 *
 * 单文件解析可以带上 /list 已经返回过的元数据直接跳过 share/get:
 *   GET .../parser?url=...&fileId=1&etag=x&size=1024&s3keyFlag=y
 *
 * 平台限制(见腾讯云文档 1552/127416、1552/81897):
 *   CPU 时间 200ms(不含 I/O 等待) / 内存 128MB / 单次运行 fetch 上限 64 次(重定向计入)
 *   / fetch 并发 8 / fetch 默认超时 15s / console 调用 20 次 / 单个循环 10 万次。
 * 本函数最长调用链为 4 跳, 远低于 fetch 次数上限; 真正的风险是默认 15s 超时下的尾延迟,
 * 因此所有子请求都显式配置了 eo.timeoutSetting。
 */

// ============================ 配置 ============================

/**
 * token 通过 EO「环境变量与密钥」注入(建议类型选 Secret, 控制台不可见)。
 * 变量名 PAN123_TOKEN。未配置时回退到下面的常量, 仅供本地调试。
 */
const ACCESS_TOKEN_FALLBACK = 'PUT_YOUR_123PAN_ACCESS_TOKEN_HERE';

const API_BASE = 'https://api.123278.com';
const SHARE_GET_PATH = '/b/api/share/get';
const DOWNLOAD_V2_PATH = '/b/api/v2/share/download/info';
const DOWNLOAD_LEGACY_PATH = '/b/api/file/download_info';
const BATCH_DOWNLOAD_PATH = '/b/api/file/batch_download_share_info';

const UA_WEB = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36';
const UA_ANDROID = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36';

const DEFAULT_ORIGIN = 'https://www.123pan.com';

// v2 给短超时: 超时即回退旧接口, 把最坏路径收敛到可控范围
const TIMEOUT_V2_MS = 1500;
const TIMEOUT_DEFAULT_MS = 5000;

// share/get 结果在分享有效期内基本不变, 缓存后单文件解析 2 跳降 1 跳、/list 降 0 跳
const SHARE_INFO_TTL_SEC = 300;

function readEnv(name, fallback) {
  try {
    if (typeof env !== 'undefined' && env && env[name]) {
      return env[name];
    }
  } catch (e) {
    // 本地调试环境没有 env 全局变量
  }
  return fallback;
}

// ============================ 子请求超时 ============================

/** 腾讯云文档 1552/81897: fetch 通过 requestInit.eo.timeoutSetting 控制超时 */
function withTimeout(options, timeoutMs) {
  return {
    ...options,
    eo: {
      ...(options.eo || {}),
      timeoutSetting: {
        connectTimeout: timeoutMs,
        readTimeout: timeoutMs,
        writeTimeout: timeoutMs
      }
    }
  };
}

// ============================ 签名 ============================

const CHAR_MAP = 'adefghlmyijnopkqrstubcvwsz';

const CRC32_TABLE = (() => {
  const table = new Int32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[i] = c;
  }
  return table;
})();

function crc32(str) {
  const bytes = new TextEncoder().encode(str);
  let crc = -1;
  for (let i = 0; i < bytes.length; i++) {
    crc = (crc >>> 8) ^ CRC32_TABLE[(crc ^ bytes[i]) & 0xFF];
  }
  return (crc ^ -1) >>> 0;
}

/** 签名基于东八区的 yyyyMMddHHmm, 与 123pan 网页端保持一致 */
function formatCst(epochSec) {
  const d = new Date((epochSec + 8 * 3600) * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
}

/**
 * 生成形如 ?<y>=<秒级时间戳>-<随机数>-<校验值> 的签名查询串
 * @param {string} path 不含域名的接口路径
 * @param {string} way  平台标识 web/android
 * @param {string} version App-Version
 */
function signQuery(path, way, version) {
  const timeSec = Math.floor(Date.now() / 1000);
  const randomInt = Math.floor(Math.random() * 10000000) + 1;
  const a = randomInt * 1000;

  let g = '';
  for (const ch of formatCst(timeSec)) {
    const digit = Number(ch);
    g += digit === 0 ? CHAR_MAP[0] : CHAR_MAP[digit - 1];
  }

  const y = String(crc32(g));
  const checksum = String(crc32(`${timeSec}|${a}|${path}|${way}|${version}|${y}`));
  return `?${y}=${timeSec}-${a}-${checksum}`;
}

function signedUrl(path, way, version) {
  return API_BASE + path + signQuery(path, way, version);
}

// ============================ 分享链接解析 ============================

/** 从分享链接中提取 shareKey 与所属子域 uid */
function parseShareLink(input) {
  const raw = (input || '').trim();
  if (!raw) {
    throw new Error('缺少分享链接');
  }

  if (!/^https?:\/\//i.test(raw)) {
    return { shareKey: normalizeShareKey(raw), uid: '' };
  }

  let parsed;
  try {
    parsed = new URL(raw);
  } catch (e) {
    throw new Error('分享链接格式错误: ' + raw);
  }

  const segments = parsed.pathname.split('/').filter(Boolean);
  const shareKey = normalizeShareKey(segments.length ? segments[segments.length - 1] : '');
  if (!shareKey) {
    throw new Error('无法从链接中解析 shareKey: ' + raw);
  }

  const subdomain = /^(\d+)\.(?:m)?share\.123pan\.cn$/i.exec(parsed.hostname);
  return { shareKey, uid: subdomain ? subdomain[1] : '' };
}

function normalizeShareKey(key) {
  return (key || '').trim().replace(/\.html?$/i, '').replace(/^\/+|\/+$/g, '');
}

function resolveShareOrigin(uid) {
  return uid ? `https://${uid}.share.123pan.cn` : DEFAULT_ORIGIN;
}

function buildReferer(origin, shareKey, pwd) {
  if (!shareKey) {
    return origin + '/';
  }
  const referer = `${origin}/123pan/${shareKey}`;
  return pwd ? `${referer}?pwd=${encodeURIComponent(pwd)}` : referer;
}

/** 用户从浏览器复制的 token 常带 "Bearer " 前缀, 不清理会拼成 "Bearer Bearer xxx" */
function bearer() {
  const token = String(readEnv('PAN123_TOKEN', ACCESS_TOKEN_FALLBACK) || '')
    .trim()
    .replace(/^bearer\s+/i, '');
  return token && token !== 'PUT_YOUR_123PAN_ACCESS_TOKEN_HERE' ? `Bearer ${token}` : '';
}

/** 未配置 token 时不能发出空的 Authorization 头, 否则接口直接判为鉴权失败 */
function withAuth(headers) {
  const auth = bearer();
  return auth ? { ...headers, Authorization: auth } : headers;
}

// ============================ 缓存 ============================

/**
 * 只缓存 share/get 的文件元数据。
 * 直链绝对不能缓存: 123 的 302 与取链出口的地市归属绑定且 TTL 很短, 跨用户复用会 403。
 */
function shareInfoCacheKey(ctx, parentFileId) {
  const q = new URLSearchParams({
    k: ctx.shareKey,
    d: String(parentFileId),
    p: ctx.pwd
  });
  return `https://pan123-edge.cache/share-info?${q}`;
}

function cacheStore() {
  try {
    return typeof caches !== 'undefined' && caches ? caches.default : null;
  } catch (e) {
    return null;
  }
}

async function cacheReadJson(cacheKey) {
  const cache = cacheStore();
  if (!cache) {
    return null;
  }
  try {
    const cached = await cache.match(cacheKey);
    return cached ? await cached.json() : null;
  } catch (e) {
    // 文档示例说明缓存过期等场景 cache.match 会抛错, 清掉后按未命中处理
    try {
      await cache.delete(cacheKey);
    } catch (ignored) {
      // 删除失败不影响主流程
    }
    return null;
  }
}

function cacheWriteJson(event, cacheKey, payload, ttlSec) {
  const cache = cacheStore();
  if (!cache) {
    return;
  }
  const response = new Response(JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      'Cache-Control': `s-maxage=${ttlSec}`
    }
  });
  const task = cache.put(cacheKey, response).catch(() => {});
  if (event && typeof event.waitUntil === 'function') {
    event.waitUntil(task);
  }
}

// ============================ 123 API ============================

async function requestJson(url, options, label, timeoutMs = TIMEOUT_DEFAULT_MS) {
  let resp;
  try {
    resp = await fetch(url, withTimeout(options, timeoutMs));
  } catch (e) {
    throw new Error(`${label}请求失败(${timeoutMs}ms超时): ${e.message || e}`);
  }
  const text = await resp.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new Error(`${label}返回非JSON(${resp.status}): ${text.slice(0, 300)}`);
  }
  if (json.code !== 0) {
    throw new Error(`${label}返回异常: ${json.message || text.slice(0, 300)}`);
  }
  return json;
}

async function getShareInfo(event, ctx, parentFileId) {
  const cacheKey = shareInfoCacheKey(ctx, parentFileId);
  const cached = await cacheReadJson(cacheKey);
  if (cached) {
    return cached;
  }

  const query = new URLSearchParams({
    limit: '100',
    next: '1',
    orderBy: 'share_id',
    orderDirection: 'desc',
    shareKey: ctx.shareKey,
    SharePwd: ctx.pwd,
    ParentFileId: String(parentFileId),
    Page: '1'
  });

  const json = await requestJson(`${API_BASE}${SHARE_GET_PATH}?${query}`, {
    method: 'GET',
    headers: withAuth({
      'Accept': 'application/json, text/plain, */*',
      'User-Agent': UA_WEB,
      'Referer': ctx.referer,
      'Origin': ctx.origin,
      'platform': 'web',
      'App-Version': '3'
    })
  }, '获取分享信息');

  const infoList = json.data && json.data.InfoList;
  if (!Array.isArray(infoList)) {
    throw new Error('返回数据格式错误: 缺少 InfoList');
  }

  cacheWriteJson(event, cacheKey, infoList, SHARE_INFO_TTL_SEC);
  return infoList;
}

/** 新版分享直链接口, 未返回 downloadPath 时返回空串以便回退旧接口 */
async function getDownloadUrlV2(ctx, item) {
  const body = {
    ShareKey: ctx.shareKey,
    FileID: item.FileId,
    S3keyFlag: item.S3KeyFlag,
    Size: item.Size,
    Etag: item.Etag
  };

  const json = await requestJson(signedUrl(DOWNLOAD_V2_PATH, 'web', '3'), {
    method: 'POST',
    headers: withAuth({
      'Accept': '*/*',
      'App-Version': '3',
      'platform': 'web',
      'Origin': ctx.origin,
      'Referer': ctx.referer,
      'User-Agent': UA_WEB,
      'Content-Type': 'application/json;charset=UTF-8'
    }),
    body: JSON.stringify(body)
  }, 'v2直链接口', TIMEOUT_V2_MS);

  const data = json.data || {};
  const downloadPath = data.downloadPath;
  if (!downloadPath) {
    return '';
  }

  const dispatchList = Array.isArray(data.dispatchList) ? data.dispatchList : [];
  const prefix = dispatchList.length && dispatchList[0] ? (dispatchList[0].prefix || '') : '';
  if (!prefix) {
    return downloadPath;
  }
  return prefix.replace(/\/+$/, '') + (downloadPath.startsWith('/') ? downloadPath : '/' + downloadPath);
}

async function getDownloadUrlLegacy(item) {
  const body = {
    driveId: 0,
    etag: item.Etag,
    fileId: item.FileId,
    fileName: item.FileName,
    s3keyFlag: item.S3KeyFlag,
    size: item.Size,
    type: 0
  };
  const json = await requestJson(signedUrl(DOWNLOAD_LEGACY_PATH, 'android', '55'), {
    method: 'POST',
    headers: androidHeaders(),
    body: JSON.stringify(body)
  }, '旧版直链接口');
  return resolveFinalUrl(json);
}

async function getFolderDownloadUrl(ctx, item) {
  const body = {
    shareKey: ctx.shareKey,
    fileIdList: [{ fileId: item.FileId }]
  };
  const json = await requestJson(signedUrl(BATCH_DOWNLOAD_PATH, 'android', '55'), {
    method: 'POST',
    headers: androidHeaders(),
    body: JSON.stringify(body)
  }, '文件夹打包下载接口');
  return resolveFinalUrl(json);
}

function androidHeaders() {
  return withAuth({
    'Accept': 'application/json, text/plain, */*',
    'App-Version': '55',
    'platform': 'android',
    'User-Agent': UA_ANDROID,
    'Content-Type': 'application/json'
  });
}

/**
 * 旧接口返回的 DownloadUrl 里带 base64 的 params, 需要解码后再请求一次拿真正的 302 地址。
 * 这一跳打的是 CDN 调度地址, 不带 Authorization: 既没必要, 也少一个风控指纹。
 */
async function resolveFinalUrl(json) {
  const data = json.data || {};
  const downloadUrl = data.DownloadUrl || data.DownloadURL;
  if (!downloadUrl) {
    throw new Error('未获取到下载链接');
  }

  let params;
  try {
    params = new URL(downloadUrl).searchParams.get('params');
  } catch (e) {
    return downloadUrl;
  }
  if (!params) {
    return downloadUrl;
  }

  const decoded = base64Decode(params);
  const resp = await fetch(decoded, withTimeout({
    method: 'GET',
    headers: { 'Accept': '*/*', 'User-Agent': UA_ANDROID },
    redirect: 'manual'
  }, TIMEOUT_DEFAULT_MS));

  if (resp.status === 301 || resp.status === 302) {
    const location = resp.headers.get('Location');
    if (!location) {
      throw new Error('重定向链接为空');
    }
    return location;
  }

  const text = await resp.text();
  let redirectJson;
  try {
    redirectJson = JSON.parse(text);
  } catch (e) {
    return decoded;
  }
  if (redirectJson.code !== 0) {
    throw new Error('重定向返回值异常: ' + text.slice(0, 300));
  }
  return (redirectJson.data && redirectJson.data.redirect_url) || decoded;
}

function base64Decode(value) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}

// ============================ 业务编排 ============================

function buildContext(searchParams) {
  const { shareKey, uid } = parseShareLink(searchParams.get('url') || searchParams.get('shareUrl') || searchParams.get('shareKey'));
  const pwd = searchParams.get('pwd') || '';
  const origin = resolveShareOrigin(uid);
  return { shareKey, pwd, origin, referer: buildReferer(origin, shareKey, pwd) };
}

/**
 * /list 已经把 fileId/etag/size/s3keyFlag 返回给了前端, 点某个文件时原样带回来
 * 就能整跳跳过 share/get, 单文件解析压到 1 次请求。
 */
function fileMetaFromQuery(searchParams) {
  const fileId = searchParams.get('fileId');
  const etag = searchParams.get('etag');
  const size = searchParams.get('size');
  const s3keyFlag = searchParams.get('s3keyFlag');
  if (!fileId || !etag || !size || !s3keyFlag) {
    return null;
  }
  return {
    FileId: Number(fileId),
    FileName: searchParams.get('fileName') || '',
    Size: Number(size),
    Etag: etag,
    S3KeyFlag: s3keyFlag,
    Type: 0
  };
}

/** 取直链: 目录走打包下载, 文件优先 v2 接口并在失败/超时时回退旧接口 */
async function resolveDownloadUrl(event, ctx, searchParams) {
  let item = fileMetaFromQuery(searchParams);

  if (!item) {
    const infoList = await getShareInfo(event, ctx, 0);
    if (!infoList.length) {
      throw new Error('分享中没有文件');
    }
    const fileId = searchParams.get('fileId');
    item = fileId ? infoList.find((it) => String(it.FileId) === String(fileId)) : infoList[0];
    if (!item) {
      throw new Error('未找到指定文件: ' + fileId);
    }
  }

  if (item.Type === 1) {
    return { item, downloadUrl: await getFolderDownloadUrl(ctx, item) };
  }

  try {
    const v2Url = await getDownloadUrlV2(ctx, item);
    if (v2Url) {
      return { item, downloadUrl: v2Url };
    }
  } catch (e) {
    console.log('v2直链接口失败, 回退旧接口: ' + e.message);
  }
  return { item, downloadUrl: await getDownloadUrlLegacy(item) };
}

async function handleRedirect(event, searchParams) {
  const ctx = buildContext(searchParams);
  const { downloadUrl } = await resolveDownloadUrl(event, ctx, searchParams);
  return new Response(null, {
    status: 302,
    // 直链与取链出口绑定且 TTL 很短, 必须禁止任何层级的缓存
    headers: {
      'Location': downloadUrl,
      'Cache-Control': 'no-store',
      'Access-Control-Expose-Headers': 'Location',
      ...CORS_HEADERS
    }
  });
}

async function handleList(event, searchParams) {
  const ctx = buildContext(searchParams);
  const infoList = await getShareInfo(event, ctx, searchParams.get('dirId') || 0);
  return jsonResponse({
    code: 200,
    success: true,
    msg: 'success',
    data: infoList.map((item) => ({
      fileId: item.FileId,
      fileName: item.FileName,
      fileType: item.Type === 1 ? 'folder' : 'file',
      size: item.Size,
      etag: item.Etag,
      // 回传给前端, 下次解析可作为快查参数直接跳过 share/get
      s3keyFlag: item.S3KeyFlag,
      createTime: item.CreateAt,
      updateTime: item.UpdateAt
    }))
  });
}

// ============================ 入口 ============================

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,OPTIONS',
  'Access-Control-Allow-Headers': '*'
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json;charset=UTF-8', 'Cache-Control': 'no-store', ...CORS_HEADERS }
  });
}

function errorResponse(message, status = 500) {
  return jsonResponse({ code: status, success: false, msg: message, timestamp: Date.now() }, status);
}

/**
 * EO 拦截的真实路径是 /api/user/parse/parser。
 * /list 仍返回 JSON 列表; 其余路径(含 /、/d、/parser, 以及误打到 /json/parser)一律 302。
 */
async function handleRequest(event) {
  const request = event.request;
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';

  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  try {
    if (path.endsWith('/list') || path.endsWith('/getFileList')) {
      return await handleList(event, url.searchParams);
    }
    return await handleRedirect(event, url.searchParams);
  } catch (e) {
    return errorResponse(e.message || String(e));
  }
}

addEventListener('fetch', (event) => {
  event.respondWith(handleRequest(event));
});
