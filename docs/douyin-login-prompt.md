# 抖音扫码登录接入提示词（基于 myMusic 汽水登录的实战实现）

> 用途：把这份文档整体作为提示词交给 AI，让它在一个 Android 工程里实现「应用内扫码登录抖音」。
> 文档中的「已验证」指 2026-10-30 在 myMusic 里真机（模拟器 + 手机官方 App 扫码）跑通过；
> 标「待验证」的是从汽水类推到抖音、尚未实测的部分，实现前必须先验证。

---

## 一、任务

在 Android 工程中实现抖音（Douyin）**应用内扫码登录**：

1. 用户点「登录」→ 应用生成二维码（手机 & 电视都能用）；
2. 用户用手机上的**抖音官方 App** 扫码并确认；
3. 应用拿到完整登录态 Cookie（`sessionid` 等），持久化，用于后续接口；
4. 登录全程不离开应用，不要求用户抓包/Cookie。

约束：
- 系统 WebView 可用（minSdk ≥ 23 即可，`android.webkit.CookieManager`）；
- 抖音没有可抓 Cookie 的网页版登录，**必须**走扫码；抖音和汽水同属字节 passport 体系，原理一致；
- 不允许引入外部浏览器依赖，不能要求用户装插件。

---

## 二、背景：为什么不能用普通 HTTP 直接调接口（已验证的事实）

字节系 Web passport 有强风控，以下四件事缺一不可，全部来自汽水登录的实测失败/成功记录：

| 尝试 | 结果 |
|---|---|
| OkHttp 裸发 `get_qrcode` / `check_qrconnect` | 扫码后一路 `error_code=7 访问太频繁`，永远拿不到 `confirmed` |
| 抖音系开源的 `a_bogus` 纯算（web V1.0.1.19 那套 vm 实现） | 不被认，同样 `error_code=7` |
| 带应用级签名 `x-helios` / `x-medusa`（另一套设备指纹） | 与二维码会话自己的 `device_id` 冲突，被判异常设备 |
| 真浏览器上下文里发请求，且用**官方安全 SDK bdms** 补签名 | ✅ 稳定成功 |

关键结论：
1. **签名必须由官方 Web 安全 SDK（bdms.js + sdk-glue.js）在真实浏览器上下文里生成**，a_bogus 与页面设备身份、cookie、localStorage 绑定，离开页面算不出来；
2. passport 请求**不能**带 `x-helios`/`x-medusa`（那是 App 签名，另一套设备指纹）；
3. 请求必须和「登录态所在的那个 cookie jar」是同一个浏览器会话——手机确认后，`sessionid` 通常**不在响应体里**，而是 Set-Cookie 进浏览器会话，需要跟一跳 `redirect_url` 收割；
4. `error_code=7` 是**临时限流**，冷却 5 秒后继续，绝不判失败；写成 60 秒会把「扫码→确认」窗口整段跳过；
5. 二维码内容必须是服务端下发的 `qrcode_index_url` **原样**（字节对该地址做过改写，改写后只能停在「已扫码」）。

桌面端（SodaM / libresoda）的做法是拉起本机 Chromium 当「签名页」；Android 上没有 CDP，但有**等价物**：

> **隐藏 WebView + `loadDataWithBaseURL("https://<业务域名>/", 安全页 HTML)`**
> 页面拿到官方站点的「同源身份」后，passport 请求就是同源 XHR，直接走 WebView 自己的 Chromium 网络栈（原生 TLS、CookieManager），**CORS 问题根本不存在**，也不需要 `--disable-web-security`。

---

## 三、已验证的架构（直接照抄）

```
┌────────────────────────────── Kotlin（业务层） ──────────────────────────────┐
│ LoginStateMachine（移植版）：公共参数、状态机、error_code 分支、cookie 收割      │
│        │ suspend request(method,url,headers,body)                          │
│        ▼                                                                   │
│ WebViewSigner：隐藏 0x0 WebView（挂在当前 Activity 上）                        │
│   loadDataWithBaseURL("https://api.qishui.com/", security_host.html)        │
│   shouldInterceptRequest → 本地 assets 供给 react/sdk-glue/bdms.js          │
│   evaluateJavascript("window.__qishuiRequest(spec)") → 页面内发 XHR          │
│   addJavascriptInterface 回传结果/二次验证完成                                 │
│   CookieManager.getCookie(url) → 收割登录态                                   │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 隐藏签名页（核心技巧）

```kotlin
// 1) 0x0 WebView 挂到窗口上（未挂载的 WebView 定时器/渲染会被系统节流）
(activity.window.decorView as? ViewGroup)?.addView(webView, ViewGroup.LayoutParams(0, 0))

// 2) 关键：baseURL 用官方域名，让页面拿到同源身份
webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = UA }
CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

// 3) 本地资产供给：页面相对引用 react/sdk-glue，sdk-glue 再从 CDN 拉 bdms.js
webView.webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
        intercept(req.url.toString())   // 命中 bdms.js / react.js / react-dom.js / sdk-glue.js → assets
}
webView.addJavascriptInterface(Bridge(), "__androidLoginBridge")
webView.loadDataWithBaseURL("https://api.qishui.com/", html, "text/html", "utf-8", null)
```

安全页 HTML（`security_host.html`）里必须有：
```html
<script src="react.js"></script><script src="react-dom.js"></script>
<script>window.ucSecondVerifyReact = React; window.ucSecondVerifyReactDom = ReactDOM;</script>
<script src="sdk-glue.js"></script>
<script>
  window._SdkGlueInit({ self: { aid: <AID>, pageId: <PAGE_ID> }, bdms: { aid: <AID>, paths: ['/passport'] } });
  window.__qishuiRequest = function (spec) {        // spec: {method,url,headers,body,timeout}
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(spec.method || 'GET', spec.url, true);
      xhr.withCredentials = true;                     // 浏览器自己带该 jar 的 cookie
      for (const [k, v] of Object.entries(spec.headers || {})) xhr.setRequestHeader(k, String(v));
      xhr.timeout = spec.timeout || 20000;
      xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText,
                                   responseURL: xhr.responseURL, headers: xhr.getAllResponseHeaders() });
      xhr.onerror = () => reject(new Error('network error'));
      xhr.ontimeout = () => reject(new Error('timeout'));
      xhr.send(spec.body == null ? null : spec.body);
    });
  };
</script>
```

Kotlin 侧发请求（`evaluateJavascript` 回调 + `@JavascriptInterface` 回传，用 id 关联 `CompletableDeferred`）：
```kotlin
val js = """(function(){var id=$id, spec=$specJson;
  var done=r=>{try{__androidLoginBridge.onResult(id,JSON.stringify(r));}catch(e){}};
  Promise.resolve(window.__qishuiRequest(spec)).then(r=>done({ok:true,result:r}),
                                               e=>done({ok:false,error:String(e&&e.message||e)}));
})()"""
webView.evaluateJavascript(js) { /* 立即返回 null；结果走 bridge */ }
```

**签名自检（必做）**：检查 `responseURL` 查询串里是否出现 `a_bogus=`。
- 长度随 WebView/bdms 版本变化（实测 44 或 46），**不能按固定 44 卡死**；
- 长度 < 40 说明 bdms 没签上（页面没就绪 / 被限流），此时请求必然一路 `error_code=7`，应直接报「签名生成失败」。

### 3.2 会话隔离（每张二维码一个新身份）

桌面端每个二维码开独立 `BrowserContext`；Android 上等价做法：

```kotlin
fun beginSession(msToken: String) {
    await pageLoaded
    removeAllCookies { removeSessionCookies { done } }    // 清 CookieManager
    manager.flush()
    evaluateJavascript("""(function(){try{localStorage.clear();
        localStorage.setItem('xmst', "<msToken>"); localStorage.setItem('xmsty', "<msToken>");
        return 'ok';}catch(e){return 'err:'+e;}})()""")
    // 等 bdms 就绪：while (!evaluate("(window.bdms ? '1':'0')").contains("1")) delay(200)
}
```
不隔离会被护照服务按「设备身份」连环限流（`error_code=7`）。

### 3.3 会话参数（以汽水为模板；抖音的 aid/版本号需按参考实现替换）

每会话随机生成：
- `device_id`：16 位数字；`install_id`：15 位数字；`did`/`iid` 分别等于上面两个；
- `msToken`：88 字节 base64url（无填充）+ `"=="` 后缀；
- `verify_portrait_id`：`<UUID>.login`；
- 每请求随机 `biz_trace_id`：8 位 hex。

请求头：
- `Accept: application/json, text/javascript`
- `x-tt-passport-verify-portrait: <verify_portrait_id>`
- `x-tt-passport-trace-id: <biz_trace_id>`
- POST 另有 `Content-Type: application/x-www-form-urlencoded`、`x-ss-stub = MD5(body).toUpperCase()`
- **不要**传 Cookie / User-Agent（XHR forbidden header，浏览器自己带；UA 用 `webView.settings.userAgentString` 统一设置）

查询公共参数（汽水实测值，抖音请对照参考实现替换 aid、版本、request_host 等）：
`passport_jssdk_version=2.4.13, passport_jssdk_type=normal, is_from_ttaccountsdk=1, aid=<AID>, language=zh, account_sdk_source=web, p_js_v=2.4.13, p_js_t=pro, p_zt=3.3.5, p_ver=1.0.29, request_host=app%3A%2F%2Fresources, p_bd=1.0.0.41, is_new_login=1, is_from_iesaccountsaas=1, device_platform=PC, version_code=3.5.2, account_sdk_source_info=00, biz_trace_id, device_id, install_id, did, iid, msToken`

> 注意：参数值要**先预转义**再用 URL 编码拼上去（例如 `request_host` 最终呈现为 `app%253A%252F%252Fresources`），与参考实现 `Params.encode`（按 key 排序）保持一致。

### 3.4 状态机（对齐 libresoda `qr_login.rs`）

**创建**：`GET /passport/web/get_qrcode/?<公共参数>&next=https://api.qishui.com&need_logo=false&need_short_url=false&is_new_login=1`
- 响应 `data.token`、`data.qrcode_index_url`；
- 二维码内容 = `qrcode_index_url` **原样**（校验其查询串里有非空 `token=`）；
- 客户端会话 TTL 3～4 分钟；过期后重新 create。

**轮询**：`POST /passport/web/check_qrconnect/`
- body（按 key 排序）：`is_frontier=true, is_new_login=1, need_logo=false, need_short_url=false, next=https://api.qishui.com, token=<token>`，加 `x-ss-stub`；
- 最小轮询间隔 2.5 秒；`error_code=7` 冷却 **5 秒**后继续，回上一次状态，不判失败；
- `status` 归一化：`1/new` → waiting，`2/scanned` → scanned（已扫码，请在手机上确认），`3/confirmed/success` 或 `logged_in=true` 或存在 `session_cookie` → confirmed；
- `error_code=2` 或 `status=expired|expire|timeout` → 过期；
- `error_code=2046` → 二次验证（见 3.5）；
- **只认服务端状态判断成功**，不能用「本地 jar 里已有 sessionid」判（jar 可能残留上次登录）。

**确认后收割**：
1. 先把响应体里可能的 `session_cookie` / `cookie` / `sessionid` / `auth.sessionid` 合并进会话 Cookie；
2. 若还没有 `sessionid|sessionid_ss|sid_guard|sid_tt` 任一，跟一跳 `data.redirect_url`（经签名页 GET），然后 `CookieManager.getCookie(url)` 收割（同时查 `https://api.qishui.com/` 与业务站 https://www.qishui.com/ 等域）；
3. Cookie 合并规则：按 `name=value` 去重、新值覆盖旧值，丢弃 `Path/Domain/Expires/Max-Age/SameSite/Secure/HttpOnly` 属性段；
4. 拿到四件套即成功，写入设置存储，随后用 `/luna/pc/me`（抖音对应账号信息接口）验证 Cookie 有效并显示账号卡片。

### 3.5 二次验证（`error_code=2046`）

确认请求返回 2046 时：
1. 会话 TTL 放宽（≥10 分钟）；
2. 弹一个**可见** Dialog，里面是第二个 WebView，同样 `loadDataWithBaseURL(官方域名)` 加载安全页变体（`soda_verify.html`）：
   - `_SdkGlueInit` 同上（bdms 在同一页面工作，签名自动补）；
   - 拦截页面内 `XMLHttpRequest.prototype.send` / `window.fetch`：对白名单域（`api.qishui.com`、`auth.zijieapi.com`、`bff-pc.qishui.com`）的请求改走 `__qishuiRequest`（保签），其余放行；`__qishuiRequest` 自己创建的 XHR 打 `__qDirect` 标记，防止重入递归；
   - 调 `window.__qishuiSecondVerify(decision, {generalParams})`（决策 JSON 来自 2046 的 `data`），官方组件在页面内渲染；
   - 组件完成 → `@JavascriptInterface` 回传 status → 关闭 Dialog；
3. 完成后带决策里的 `biz_params` + 查询参数 `isResend=true` **重发** `check_qrconnect`：
   - body 额外并入 `normalizeBizParams(decision.biz_params)`（JSON 字符串 / `k=v&k2=v2` / 对象三种形态归一）；
   - 返回 confirmed → 走确认收割；仍 2046 → 下一次轮询重新进入本分支（再次弹窗）；其余 → waiting。

### 3.6 前台 Activity 引用

数据层要挂 WebView，必须拿到当前 Activity：
```kotlin
object ForegroundActivity {   // Application.registerActivityLifecycleCallbacks 维护 WeakReference
    fun get(): Activity?       // onActivityResumed 设置，onActivityPaused 清空
}
```
取不到 → 提示「请回到应用界面内再发起登录」。

---

## 四、移植到抖音：需要先验证的差异点（不要照抄汽水的值）

抖音与汽水同属字节 passport（同样 bdms / a_bogus / msToken），但以下必须按抖音的实际值替换，并通过一次**真实手机扫码**验证：

1. **业务域名**：大概率是 `https://www.douyin.com`（`loadDataWithBaseURL` 的 base 和安全页的 origin 都要换成它）；
2. **aid / pageId / version 参数**：汽水是 `aid=386088`；抖音 web 的 aid、`version_code`、`p_ver` 等以参考实现为准（可从开源实现如 douyin 相关 web 登录项目、或浏览器抓 `www.douyin.com` 的 `/passport/web/get_qrcode/` 请求得到）；
3. **端点**：大概率同为 `/passport/web/get_qrcode/`、`/passport/web/check_qrconnect/`；确认 host（抖音可能走 `https://www.douyin.com/passport/...` 或 `https://sso.douyin.com/...`，以实测为准）；
4. **`qrcode_index_url`**：必须原样用于二维码，不得改写；
5. **登录态 cookie 名**：抖音同样是 `sessionid / sessionid_ss / sid_tt / sid_guard`（外加 `passport_csrf_token`），但以实测为准；
6. **二次验证**：抖音 web 的 2046 决策结构与验证组件 URL 需实测；页面桥接白名单要按抖音域名调整；
7. **设备身份冲突**：如果抖音登录后又调用 App 端接口（`x-helios`/`x-medusa`），注意这两套签名设备指纹不同，passport 请求**绝不能**带 App 签名。

**验证方法**：先在同一台设备上用 Chrome/桌面抓抖音 web 登录流程的完整请求（`get_qrcode`、`check_qrconnect`、`redirect_url`、`/passport/safe/pack_verify_ways_data/`），把参数逐项对齐，再接入。

---

## 五、实现步骤（建议顺序）

1. 建 `ForegroundActivity`（Application 生命周期回调）；
2. 安全页 assets：`security_host.html` + `sdk-glue.js` + `bdms.js` + `react.js` + `react-dom.js`（从字节系开源参考实现原样取用，如 libresoda `tools/qishui-signer/security/`；注意 AGPL 署名）；
3. 写 `WebViewSigner`：隐藏 WebView、资产拦截、`beginSession`（清 Cookie/localStorage、种 msToken、等 bdms）、`request()`（JS 桥 + 超时 + a_bogus 自检）、`harvestCookies()`、销毁；
4. 写登录状态机（不依赖 WebView，网关接口注入，方便 JVM 单测）：create / check / 冷却 / 过期 / confirmed 收割 / 2046 + 重发；
5. Provider/仓库接入：`createLogin()` 返回 `{sessionId, qrUrl}`、`checkLogin(sessionId)` 返回 `{status: waiting|scanned|success|expired|failed, message, cookie}`；
   - **二维码必须用服务端下发的原样 URL 在本机画**（服务端图片可能编码了改写过的地址）；
   - UI 状态文案：`waiting → 等待扫码`、`scanned → 已扫码，请在手机上确认`；`waiting` 时要透传平台文案（限流冷却时它比默认文案更准确）；
6. UI：现有扫码面板即可（二维码组件 + 轮询）；电视/手机同一套，无需摄像头；
7. 二次验证：可见 Dialog + 安全页变体 + 完成后重发；
8. 单测覆盖：等待/已扫码/限流冷却不失败/过期/2046 取消/2046 重发带 biz_params/确认收割（响应体 sessionid、redirect_url 追踪）/Cookie 合并/参数排序编码；
9. 真机验证：模拟器可见窗口 + 手机官方 App 扫码，确认 `waiting → scanned → success`，账号信息接口能取到昵称。

---

## 六、验收标准

- [ ] 设置页点登录 → 生成二维码，无 `error_code=7`（签名自检通过）；
- [ ] 手机官方 App 扫码 → 界面变「已扫码，请在手机上确认」；
- [ ] 手机确认 → 应用内弹出二次验证（若触发）并能完成 → 登录成功；
- [ ] `sessionid / sessionid_ss / sid_tt / sid_guard` 已持久化；
- [ ] 账号信息接口返回昵称/头像，账号卡片显示；
- [ ] 退出登录清除 Cookie 与会话；二维码过期后可刷新重来；
- [ ] 连续两次登录（会话隔离）都能成功，没有第二次就被限流。

---

## 七、常见坑速查

| 症状 | 原因 | 处理 |
|---|---|---|
| 一直 `error_code=7` | 请求没签名 / bdms 未就绪 / 共用设备身份 | 检查 responseURL 里 `a_bogus`；确认 `window.bdms` 就绪后才发请求；每次登录清 Cookie+localStorage |
| 卡在 `scanned` 永不 `confirmed` | 二维码用了改写地址，或把 `error_code=7` 冷却写成 60s | 用 `qrcode_index_url` 原样；`error_code=7` 冷却 5s |
| `a_bogus` 缺失/长度异常 | 安全页未加载完/sdk-glue 初始化失败 | 等 `window.bdms`；检查 `_SdkGlueInit` 参数、本地资产拦截 |
| 确认后没有 sessionid | 登录态只在浏览器 cookie jar | 跟一跳 `redirect_url`，再 `CookieManager.getCookie()` 收割 |
| 二次验证窗口里请求全失败 | 组件请求走了未签名路径 | XHR/fetch 拦截转 `__qishuiRequest`（防重入） |
| 第二次扫码就限流 | 浏览器上下文没隔离 | 每会话清 Cookie/localStorage；必要时重建 WebView |
| 未挂载的 WebView 行为异常 | 0×0 WebView 不在视图树里 | 挂到 Activity decorView 上 |

---

## 八、参考实现（一手来源）

- libresoda（AGPL-3.0）：`src/soda/qr_login.rs`（状态机、参数、错误码）、`tools/qishui-signer/security/`（官方安全组件与宿主页）、`docs/QR-LOGIN.md`（实测记录）；
- 对应 Kotlin 实现见 myMusic 仓库 `app/src/main/java/com/mytv/mymusic/data/native/SodaQrSigner.kt`、`SodaQrLogin.kt`、`app/src/main/assets/soda/security/`。
