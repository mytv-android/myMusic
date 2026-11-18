// 落雪桌面版（Electron）插件的宿主环境。移动端那套 globalThis.lx 事件接口在桌面版是
// CommonJS 模块：module.exports 导出 getMediaSource 等函数。这里提供 require/module/exports，
// 并把 axios 的 adapter 换成本机 OkHttp（QuickJS 里没有 XMLHttpRequest），其余一律走白名单。
;(() => {
  const nativeHttp = globalThis.__lx_native_http__
  const nativeDone = globalThis.__lx_native_done__
  const nativeSetTimeout = globalThis.__lx_native_set_timeout__
  delete globalThis.__lx_native_http__
  delete globalThis.__lx_native_done__
  delete globalThis.__lx_native_set_timeout__

  const MAX_SOURCE = 2 * 1024 * 1024
  const timers = new Map()
  let timerId = 0
  globalThis.setTimeout = (fn, delay = 0, ...args) => {
    const id = ++timerId
    timers.set(id, { fn, args })
    nativeSetTimeout(id, Math.min(Math.max(delay | 0, 0), 86_400_000))
    return id
  }
  globalThis.clearTimeout = (id) => { timers.delete(id) }
  globalThis.__lx_desktop_timeout__ = (id) => {
    const entry = timers.get(Number(id))
    if (!entry) return
    timers.delete(Number(id))
    try { entry.fn(...entry.args) } catch (e) { console.error('[lx] timeout callback', e && e.message) }
  }

  // ---------------------------------------------------------------- 网络（OkHttp）
  const httpPending = new Map()
  let httpId = 0
  function request(options) {
    return new Promise((resolve, reject) => {
      const key = 'h' + (++httpId)
      httpPending.set(key, { resolve, reject })
      try {
        nativeHttp(key, JSON.stringify(options))
      } catch (e) {
        httpPending.delete(key)
        reject(new Error(String(e && e.message || e)))
      }
    })
  }
  globalThis.__lx_desktop_http_done__ = (key, error, responseJson) => {
    const pending = httpPending.get(key)
    if (!pending) return
    httpPending.delete(key)
    if (error) return pending.reject(new Error(String(error)))
    let response
    try { response = JSON.parse(responseJson) } catch (e) { return pending.reject(new Error('响应无法解析')) }
    if (response.status === 0) return pending.reject(new Error(response.message || '网络请求失败'))
    pending.resolve(response)
  }

  // ---------------------------------------------------------------- base64（纯 JS，不依赖宿主）
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
  function b64encode(bytes) {
    let out = ''
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i]
      const b = i + 1 < bytes.length ? bytes[i + 1] : 0
      const c = i + 2 < bytes.length ? bytes[i + 2] : 0
      out += B64[a >> 2]
      out += B64[((a & 3) << 4) | (b >> 4)]
      out += i + 1 < bytes.length ? B64[((b & 15) << 2) | (c >> 6)] : '='
      out += i + 2 < bytes.length ? B64[c & 63] : '='
    }
    return out
  }
  function b64decode(text) {
    const clean = String(text).replace(/[^A-Za-z0-9+/]/g, '')
    const out = []
    let buffer = 0
    let bits = 0
    for (const ch of clean) {
      buffer = (buffer << 6) | B64.indexOf(ch)
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out.push((buffer >> bits) & 0xff)
      }
    }
    return out
  }

  // ---------------------------------------------------------------- UTF-8（QuickJS 没有 TextEncoder/TextDecoder）
  function utf8Encode(text) {
    const out = []
    for (let i = 0; i < text.length; i++) {
      let code = text.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
        const next = text.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00)
          i++
        }
      }
      if (code < 0x80) out.push(code)
      else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f))
      else if (code < 0x10000) out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
      else out.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
    }
    return out
  }
  function utf8Decode(bytes) {
    let out = ''
    const at = (i) => (i < bytes.length ? bytes[i] : 0)
    for (let i = 0; i < bytes.length;) {
      const b = bytes[i]
      let code

      if (b < 0x80) { code = b; i += 1 }
      else if ((b & 0xe0) === 0xc0) { code = ((b & 0x1f) << 6) | (at(i + 1) & 0x3f); i += 2 }
      else if ((b & 0xf0) === 0xe0) { code = ((b & 0x0f) << 12) | ((at(i + 1) & 0x3f) << 6) | (at(i + 2) & 0x3f); i += 3 }
      else if ((b & 0xf8) === 0xf0) {
        code = ((b & 0x07) << 18) | ((at(i + 1) & 0x3f) << 12) | ((at(i + 2) & 0x3f) << 6) | (at(i + 3) & 0x3f)
        i += 4
      } else { out += '�'; i += 1; continue }
      if (code > 0xffff) {
        code -= 0x10000
        out += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff))
      } else {
        out += String.fromCharCode(code)
      }
    }
    return out
  }

  // ---------------------------------------------------------------- Buffer 子集
  const encoder = { encode: utf8Encode }
  const decoder = { decode: utf8Decode }
  class LxBuffer extends Uint8Array {
    static from(input, encoding) {
      if (typeof input === 'string') {
        if (encoding === 'base64') return LxBuffer.fromBase64(input)
        if (encoding === 'hex') return new LxBuffer(input.match(/.{1,2}/g).map((b) => parseInt(b, 16)))
        return new LxBuffer(encoder.encode(input))
      }
      if (input instanceof Uint8Array) return new LxBuffer(input)
      if (Array.isArray(input)) return new LxBuffer(input)
      throw new TypeError('Unsupported input for Buffer.from')
    }
    static fromBase64(text) { return new LxBuffer(b64decode(text)) }
    static isBuffer(value) { return value instanceof Uint8Array }
    toString(encoding = 'utf-8', start = 0, end = this.length) {
      const slice = this.subarray(start, end)
      if (encoding === 'base64' || encoding === 'base64url') {
        const text = b64encode(slice)
        return encoding === 'base64url' ? text.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') : text
      }
      if (encoding === 'hex') return Array.from(slice).map((b) => b.toString(16).padStart(2, '0')).join('')
      return decoder.decode(slice)
    }
    toJSON() { return { type: 'Buffer', data: Array.from(this) } }
  }
  globalThis.Buffer = LxBuffer

  // ---------------------------------------------------------------- 官方 axios + adapter
  // axios 在浏览器/Node 下靠 XMLHttpRequest / http 模块选 adapter，QuickJS 里两者都没有。
  // 我们用官方 dist 包，只把 defaults.adapter 换成本机 OkHttp；请求/响应形状保持一致，
  // 脚本读到的 response.data / status / headers 和原来一样。
  function buildUrl(config) {
    let url = String((config.baseURL || '') + (config.url || ''))
    const params = config.params
    if (params && typeof params === 'object') {
      const search = Object.keys(params)
        .filter((k) => params[k] !== undefined && params[k] !== null)
        .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(
          typeof params[k] === 'object' ? JSON.stringify(params[k]) : params[k],
        )}`)
        .join('&')
      if (search) url += (url.includes('?') ? '&' : '?') + search
    }
    return url
  }

  function serializeBody(config) {
    const data = config.data
    if (data === undefined || data === null) return null
    if (typeof data === 'string') return data
    if (data instanceof Uint8Array) return data
    if (typeof FormData !== 'undefined' && data instanceof FormData) return data
    return JSON.stringify(data)
  }

  async function okHttpAdapter(config) {
    const headers = {}
    const rawHeaders = config.headers || {}
    const read = (name) => {
      const value = rawHeaders[name] ?? rawHeaders[name.toLowerCase()]
      return typeof value === 'object' && value !== null ? value.value : value
    }
    Object.keys(rawHeaders).forEach((name) => {
      const value = read(name)
      if (value !== undefined && value !== null) headers[name] = String(value)
    })
    if (config.auth && !headers.Authorization) {
      headers.Authorization = 'Basic ' + b64encode(encoder.encode(`${config.auth.username}:${config.auth.password}`))
    }
    const body = serializeBody(config)
    if (body !== null && typeof body === 'string' && !headers['Content-Type']) headers['Content-Type'] = 'application/json'

    const response = await request({
      url: buildUrl(config),
      method: (config.method || 'get').toUpperCase(),
      headers,
      body: typeof body === 'string' ? body : body === null ? null : Array.from(body),
      timeout: config.timeout || 20_000,
      binary: config.responseType === 'arraybuffer' || config.responseType === 'blob',
    })
    const type = config.responseType || 'json'
    let data = response.body
    if (type === 'arraybuffer' || type === 'blob') {
      data = new Uint8Array(response.bytes || []).buffer
    } else if (type === 'json') {
      if (typeof data === 'string') {
        try { data = data.length ? JSON.parse(data) : '' } catch (e) { /* 保持原文 */ }
      }
    }
    return {
      data,
      status: response.status,
      statusText: response.statusText || '',
      headers: response.headers || {},
      config,
      request: {},
    }
  }

  // ---------------------------------------------------------------- require 白名单
  const registry = new Map()
  let axiosLibrary = null
  function registerAxios(source) {
    const module = { exports: {} }
    runCommonJs(source, module, module.exports, 'axios')
    const axios = module.exports.default || module.exports
    if (!axios) throw new Error('axios 包没有导出默认函数')
    if (!axios.default) axios.default = axios
    if (!axios.defaults) axios.defaults = {}
    axios.defaults.adapter = okHttpAdapter
    if (axios.default.defaults) axios.default.defaults.adapter = okHttpAdapter
    axiosLibrary = axios
  }
  let heLibrary = null
  function registerHe(source) {
    const module = { exports: {} }
    runCommonJs(source, module, module.exports, 'he')
    heLibrary = module.exports
  }

  const builtins = {
    buffer: () => { const m = { exports: {} }; m.exports.Buffer = LxBuffer; m.exports.default = m.exports; return m.exports },
    crypto: () => ({ randomUUID: () => globalThis.__lx_uuid__(), randomBytes: (n) => { const a = new Uint8Array(n); for (let i = 0; i < n; i++) a[i] = Math.floor(Math.random() * 256); return a } }),
  }
  function require(name) {
    const id = String(name).replace(/^node:/, '').split('/')[0]
    if (id === 'axios') return axiosLibrary || fail('axios')
    if (id === 'he') return heLibrary || fail('he')
    if (builtins[id]) return builtins[id]()
    throw new Error(`桌面版脚本依赖了不受支持的模块「${name}」：手机端只提供 axios、he、buffer、crypto`)
  }
  function fail(name) { throw new Error(`依赖 ${name} 尚未就绪，脚本没有被正确安装`) }

  function runCommonJs(source, module, exports, name) {
    if (source.length > MAX_SOURCE) throw new Error(`${name} 超过 2 MB`)
    const factory = new Function('module', 'exports', 'require', 'global', 'console', source)
    factory(module, exports, require, globalThis, console)
  }

  // ---------------------------------------------------------------- 脚本宿主
  globalThis.module = { exports: {} }
  globalThis.exports = globalThis.module.exports
  let loadedExports = null
  const pendingCalls = new Map()

  globalThis.__lx_desktop_install__ = (metaJson) => {
    const meta = JSON.parse(metaJson)
    const module = { exports: {} }
    const source = meta.source
    try {
      runCommonJs(source, module, module.exports, meta.name || 'script')
    } catch (e) {
      throw new Error(String(e && e.message || e))
    }
    const exports = module.exports.default && Object.keys(module.exports).length === 1
      ? module.exports.default
      : module.exports
    if (!exports || typeof exports !== 'object') throw new Error('脚本没有导出任何函数')
    loadedExports = exports
    const functions = Object.keys(exports).filter((k) => typeof exports[k] === 'function')
    if (!functions.includes('getMediaSource')) throw new Error('脚本没有导出 getMediaSource，手机端目前只支持取流')
    return JSON.stringify({ functions })
  }

  const QUALITY = { '128k': 'low', '320k': 'standard', flac: 'high', flac24bit: 'super' }
  globalThis.__lx_desktop_call__ = (requestKey, action, payloadJson) => {
    if (!loadedExports) return
    const payload = JSON.parse(payloadJson)
    if (action !== 'mediaUrl') {
      nativeDone(requestKey, `不支持的操作 ${action}`, null)
      return
    }
    pendingCalls.set(requestKey, true)
    Promise.resolve()
      .then(() => loadedExports.getMediaSource(payload.musicItem, QUALITY[payload.quality] || 'standard'))
      .then((result) => {
        if (requestKey.startsWith('probe:')) return
        const url = typeof result === 'string' ? result : result && result.url
        if (typeof url !== 'string' || !/^https?:/i.test(url)) throw new Error('脚本没有返回播放地址')
        nativeDone(requestKey, null, JSON.stringify({ url }))
      })
      .catch((e) => nativeDone(requestKey, String(e && e.message || e), null))
      .finally(() => pendingCalls.delete(requestKey))
  }

  // 依赖在导入时由 Kotlin 侧下载并解包进来，脚本不会自己去网上取库
  globalThis.__lx_desktop_provide_library__ = (name, source) => {
    if (name === 'axios') registerAxios(source)
    else if (name === 'he') registerHe(source)
    else throw new Error(`未知依赖 ${name}`)
  }
  globalThis.__lx_desktop_ready__ = true
  console.log('[lx] desktop host ready')
})()
