# Navidrome / Airsonic（Subsonic 协议）源接入调研

> 日期：2026-11-23
> 用途：给实现做方案设计与事实依据。本文只描述已核实的协议契约、本仓库的对接点与取舍，不代替实现代码。
> 工程：`C:\PythonProject\myMusic`，Android/Kotlin，minSdk 23，targetSdk 37，Media3 1.11.1，OkHttp 5.5.0，Room 2.8.5。
> 核实来源：Subsonic 官方 API 页（subsonic.org/pages/api.jsp）、OpenSubsonic 规范站（opensubsonic.github.io）、
> Navidrome 官方文档 `docs/developers/subsonic-api/`、Navidrome 与 Airsonic-Advanced 的 master 源码（下文逐条给出文件路径）。

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 能不能接 | 能。Subsonic 是一个纯 HTTP + 查询参数认证的 REST 协议，不需要任何签名算法、不需要抓包逆向。 |
| 覆盖哪些服务器 | 一份客户端同时覆盖 Navidrome、Airsonic-Advanced、经典 Airsonic、Gonic、Supysonic 等所有 Subsonic 兼容服务器。差异只在版本号和 OpenSubsonic 扩展上。 |
| 工作量主要在哪 | 不在协议，在「把它塞进现有播放/歌单/封面/歌词链路」。协议本身约 700 行 Kotlin。 |
| 最大风险 | 不是协议失效，而是**转码模式下服务器不支持 Range**，以及**封面/歌词都要带认证**（现有 29 处 `AsyncImage` 都按明文 URL 加载）。 |
| 建议定位 | **`NativeProvider`，每个服务器一个实例**（`info.id = "subsonic:<accountId>"`），不是 WebDAV 那种单账号本地源。 |

三条核心判断：

1. **就是 `NativeProvider`，而且天然支持多服务器。** Subsonic 是协议，和网易云、QQ、酷狗是同一层的东西——
   一个 `SubsonicProvider` 实例对应一台服务器，`NativeProviders` 里注册 N 个实例，UI 的搜索/换源/歌单/榜单
   就自动多出 N 个源，各自带自己的名字。**账号就是 `settings.cookies` 里的一条 value**
   （key = `subsonic:<accountId>`，value 是 `{url, username, password, name}` 的 JSON），
   所以同步、退出登录墓碑、provider 列表刷新全部白拿，不需要新的账号存储，也不需要 Keystore（§5.1.3）。
2. **但一定要产出 `Song` / `Playlist`。** 本仓库的歌单、收藏、播放队列、海报墙、歌词、缓存全部以
   `Song.key = "$source:$id"` 为身份。Subsonic 层只做「协议 → `Song`」的翻译，下游 UI 一行不改。
3. **播放链路仍然要加一层路由 DataSource，但这和「是不是 NativeProvider」无关。** Subsonic 的认证只能靠
   查询参数表达，而 `PlayableStream` 只带一个 URL 字符串；要把凭据挡在 URL 之外，只能在
   `PlaybackService` 的工厂链上按 host+port 认账号、在 open() 时补参数。详见 §5.3。

### 为什么不是 WebDAV 那种做法

| | WebDAV（现有） | Subsonic（本方案） |
|---|---|---|
| 本质 | 本地歌的一个**云端副本位置** | 一个**独立的曲库源** |
| 曲目从哪来 | 本机扫描 + 匹配平台曲目 | 服务器自己的库（`getAlbum`/`search3`） |
| 账号数 | 一个（同步用） | 多个，每台服务器一个 |
| 抽象 | `data/webdav/*` + `CloudProvider.WebDav` | `data/native/SubsonicProvider`，`info.id = "subsonic:<accountId>"` |
| 出现在哪 | 「本地音乐 › 云端副本」、同步设置 | 搜索平台列表、换源面板、歌单来源、榜单来源 |
| 歌曲身份 | `local:<sha256>`，云端引用是附加信息 | `subsonic:<accountId>:<songId>` |
| 搜索/换源 | 不参与 | **完整参与**聚合搜索与换源 |
| 播放 | `WebDavDataSource`（请求头认证） | 路由 DataSource（查询参数认证） |
| 收藏/歌单/队列 | 客户端侧状态，靠同步 v3 跨设备 | **服务端状态，服务器自己同步**；只有「歌单绑定」和「收藏」跟同步文件走（§5.8） |

一句话：WebDAV 是「我的歌放在哪」，Subsonic 是「我还能从哪听歌」。

---

## 1. 目标服务器现状（已核实）

| 服务器 | Subsonic API 版本 | JSON (`f=json`) | token/salt | `enc:` 密码 | `apiKey` | `jwt` | OpenSubsonic 扩展 |
|---|---|---|---|---|---|---|---|
| **Navidrome**（master） | **1.16.1** | ✅ | ✅ | ✅ | ✅ `nds_` 前缀 | ✅ 查询参数 | 8 个（§3.6） |
| **Airsonic-Advanced**（master） | **1.15.0** | ✅ | ✅ | ✅ | ❌ | ✅ 查询参数 `jwt` | ❌ 源码中 0 处 opensubsonic |
| 经典 Airsonic | 1.15.0 | 有 JAXB JSON 序列化器 | ✅ | ✅ | ❌ | ❌ | ❌ |
| Gonic / Supysonic 等 | 各自 1.13–1.16 | 多数支持 | 多数支持 | 多数支持 | ❌ | ❌ | 部分 |

来源：
- Navidrome：`docs/developers/subsonic-api/` 明确 "compatible with Subsonic API v1.16.1, with some exceptions"。
- Airsonic-Advanced：`subsonic-rest-api/src/main/resources/subsonic-rest-api.xsd` 根元素 `version="1.15.0"`，
  `airsonic-main/.../controller/JAXBWriter.java:111 getRestProtocolVersion()` 读的就是这个 XSD 的 version。
- Airsonic-Advanced 的 JWT：`airsonic-main/.../security/JWTRequestParameterProcessingFilter.java` +
  `airsonic-main/.../service/JWTSecurityService.java:31 public static final String JWT_PARAM_NAME = "jwt"`。

**版本协商的真相**（重要，能省掉一堆兼容代码）：
Airsonic-Advanced 的 `RESTRequestParameterProcessingFilter.attemptAuthentication` 只比较 **major**：

```java
if (serverVersion.getMajor() > clientVersion.getMajor())      throw CLIENT_TOO_OLD;
else if (serverVersion.getMajor() < clientVersion.getMajor()) throw SERVER_TOO_OLD;
```

Navidrome 则不校验客户端版本，只在响应里回 `version: "1.16.1"`。
→ **客户端固定发 `v=1.16.1` 即可**，1.x 全部放行；真正要处理的是扩展缺失（§3.6）。

---

## 2. 协议契约

### 2.1 请求形状

所有端点都是 `GET /rest/<endpoint>.view?<通用参数>&<端点参数>`，可选 `f=json`。
Navidrome 的 `/rest` 挂在 `path.Join(conf.Server().BasePath, "/rest")`（`server/server.go:52 MountRouter`），
所以**反代在子路径下的服务器（`https://nas.example.com/music`）直接用带子路径的地址就能连**，客户端不需要猜。

通用参数（Subsonic 官方页 + Navidrome `server/subsonic/middlewares.go`）：

| 参数 | 必需 | 说明 |
|---|---|---|
| `u` | 是（除 apiKey/jwt/反代认证外） | 用户名 |
| `p` | 与 `t`+`s` 二选一 | 密码，明文或 `enc:` + UTF-8 十六进制 |
| `t` | 与 `p` 二选一 | `md5(密码 + salt)` 的小写十六进制 |
| `s` | 同 `t` | 随机盐，Subsonic 规格要求 ≥ 6 字符 |
| `v` | 是 | 客户端协议版本，固定 `1.16.1` |
| `c` | 是 | 客户端标识，如 `myMusic` |
| `f` | 否 | `xml`（默认）/ `json` / `jsonp` |

Navidrome 的 `checkRequiredParameters`：带 `apiKey` 或反代认证头时只要求 `v` + `c`，否则要求 `u` + `v` + `c`。

**长参数用 POST（OpenSubsonic `formPost`）**：Navidrome 全局挂了 `postFormToQueryParams`
（`server/subsonic/middlewares.go`），把 `application/x-www-form-urlencoded` 的 body 合并进查询参数，
body 上限 10MB。所以「往歌单里加 500 首」这种 GET URL 会爆长度的操作可以直接改 POST：
路径不变（`/rest/updatePlaylist.view`），参数从 query 搬到 form body。
Airsonic-Advanced 没有这个扩展，**超长列表要自己分批**（建议每批 ≤ 100 个 `songIdToAdd`）。

### 2.2 认证：四种方式与取舍

| 方式 | 请求 | 优点 | 缺点 |
|---|---|---|---|
| 明文密码 | `u=&p=secret` | 最简单 | 密码进 URL/日志，Subsonic 规格 1.13.0 起只建议测试用 |
| `enc:` 十六进制 | `u=&p=enc:<hex>` | 避免日志里直接看到明文 | 只是编码不是加密，且 URL 里仍然是可逆的 |
| **token + salt**（推荐） | `u=&t=md5(pw+s)&s=<salt>` | 密码本身不出现在请求里 | 需要客户端存 md5 能力；盐每次可不同 |
| `apiKey`（Navidrome ≥ 新版） | `apiKey=nds_…` | 可单独吊销，不暴露密码 | 仅 Navidrome 支持；且**不能和 `u`/`p`/`t`/`s`/`jwt` 同时出现** |

Navidrome `validateCredentials`（`server/subsonic/middlewares.go`）：

```go
case jwt != "":   auth.Validate(jwt) && claims.Subject == user.UserName && audience == subsonic
case pass != "":  decodePassword(pass) == user.Password      // decodePassword 会解 enc: 的 hex
case token != "": fmt.Sprintf("%x", md5.Sum([]byte(user.Password+salt))) == token
```

`apiKey` 走独立分支 `authenticateAPIKey`，冲突检测很严（`apiKeyConflicts = []string{"u","p","t","s","jwt"}`），
命中即回错误 43 `Multiple conflicting authentication mechanisms provided`。
另外 `playerFromPasswordKey`：如果 `p` 的值以 `nds_` 开头，会被当成 API key 去查 Player——**即使用户只填一个"密码"框，贴 API key 也能登**。

Airsonic-Advanced 只支持前三种，且**接受已通过其他方式（如 Basic Auth）认证的请求**
（`RESTRequestParameterProcessingFilter` 里 `previousAuth.isAuthenticated()` 直接放行）。

**对本应用的建议**：主用 token+salt，盐在账号保存时生成一次并固定（保证 URL 稳定，见 §5.2）；
把「粘贴 API key」作为高级选项，检测到 `nds_` 前缀就自动改用 `apiKey` 参数。

**踩雷预警——登录限流**：Navidrome 对失败的 Subsonic 登录按 `(客户端IP, 用户名)` 限流，
默认 `authrequestlimit=5` / `authwindowlength=20s`（`conf/configuration.go:1041`、`consts/consts.go:78`），
被拦的请求**返回和密码错误完全一样的错误 40**（源码注释："Blocked attempts get the same response as a wrong password, so they reveal nothing"）。
→ 「测试连接」按钮**绝不能自动重试**，失败一次就把服务端原文提示给用户，等 20 秒。

### 2.3 响应信封

Navidrome 实测快照（`server/subsonic/responses/.snapshots/…`，v0.55.0）：

```json
{
  "status": "ok",
  "version": "1.16.1",
  "type": "navidrome",
  "serverVersion": "v0.55.0",
  "openSubsonic": true,
  "<payload>": { … }
}
```

- `status`：`ok` / `failed`。失败时 HTTP 仍是 200，错误在 `error { code, message, helpUrl? }` 里。
  这和本仓库已知的 `NativeHttp` 行为（`throwOnError`）是同一类坑：**不能靠 HTTP 状态码判断成败**。
- `type` / `serverVersion`：`ping` 就能拿到，用来做「这是什么服务器」的能力探测。
- `openSubsonic: true`：只有 OpenSubsonic 服务器才有这个字段。

### 2.4 错误码（Subsonic 官方 + OpenSubsonic 增补）

| code | 含义 | 客户端该做什么 |
|---|---|---|
| 0 | 通用错误 | 显示 message |
| 10 | 缺参数 | 代码 bug |
| 20 | 客户端太老 | 提示升级 App |
| 30 | 服务端太老 | 提示升级服务器 |
| **40** | 用户名或密码错（**也包含被限流**） | 提示检查账号密码，并说明可能被临时限流 |
| 41 | token 认证不可用（LDAP 用户） | 退回 `p=enc:` 或 apiKey |
| 42 | 某认证方式被移除 | 读 `helpUrl` |
| **43** | 多种认证方式冲突 | 代码 bug（apiKey 与 u 同时发了） |
| 44 | API key 无效 | 提示重新生成 key |
| 50 | 无权限（如下载被禁） | 提示服务器关了该功能 |
| 70 | 数据不存在 | 当成「已删除」 |

---

## 3. 本应用需要的端点清单（已按服务器实现情况核对）

Navidrome 已实现的完整列表见官方文档页；下表挑出接入必需的，并标注坑。

### 3.1 连接与探测

| 端点 | 用途 | 坑 |
|---|---|---|
| `ping.view` | 测试连接、拿 `type`/`serverVersion`/`openSubsonic` | 顺带会在服务器注册一个 player（见 §6 第 7 条） |
| `getOpenSubsonicExtensions.view` | 拿到服务器支持的扩展列表 | **公开端点，不需要认证**（`server/subsonic/api.go` 里挂在 Protected group 之外）；只有 OpenSubsonic 服务器有 |
| `getMusicFolders.view` | 多库（Multi-Library） | `musicFolder.id` 在 JSON 里是 **int**（`111`），而歌曲/专辑 id 是 string |

### 3.2 浏览

| 端点 | 用途 | 坑 |
|---|---|---|
| `getArtists.view?musicFolderId=` | 歌手索引（返回 `index[] → artist[]`） | — |
| `getArtist.view?id=` | 歌手 + 专辑列表 | — |
| `getAlbum.view?id=` | 专辑 + 曲目（返回 `album.song[]`） | — |
| `getAlbumList2.view?type=&size=&offset=&musicFolderId=` | 最新/随机/最多播放/按名/按年/按风格，`size` 最大 500 | 分页靠 `offset`，不是游标 |
| `getGenres.view` | 风格列表（带 `songCount`/`albumCount`） | — |
| `getSongsByGenre.view?genre=&count=&offset=` | 风格下的歌 | — |
| `search3.view?query=&songCount=&albumCount=&artistCount=` | 全文搜索 | Navidrome **不支持 Lucene 语法，只做简单自动补全** |
| `getStarred2.view` | 收藏 | — |
| `getRandomSongs.view?size=&genre=&fromYear=&toYear=` | 随便听听 | — |

**没有文件夹浏览。** Navidrome 的 `getIndexes` / `getMusicDirectory` 返回的是**模拟树**，
形如 `/Artist/Album/01 - Song.mp3`（官方文档原文："return a simulated tree"），且不支持 `shortcuts` 和直接子节点。
Airsonic-Advanced 有真实目录。→ **UI 上不要承诺「按目录浏览」**，按 歌手/专辑/风格/歌单 四层组织。

### 3.3 歌单

| 端点 | 用途 |
|---|---|
| `getPlaylists.view` | 列表（`username` 参数 Navidrome 未实现，忽略即可） |
| `getPlaylist.view?id=` | 详情 + `entry[]` |
| `createPlaylist.view?name=&songId=`（`songId` 可重复） | 新建；带 `playlistId` 时是更新 |
| `updatePlaylist.view?playlistId=&name=&comment=&public=&songIdToAdd=&songIndexToRemove=` | 改名/增删曲 |
| `deletePlaylist.view?id=` | 删除 |

响应快照字段（golden vector）：

```json
{"id":"111","name":"aaa","comment":"comment","songCount":2,"duration":120,
 "public":true,"owner":"admin","created":"2023-02-20T14:45:00Z","changed":"2023-02-20T14:45:00Z",
 "coverArt":"pl-123123123123","readonly":true,"validUntil":"2023-02-20T14:45:00Z"}
```

注意 `readonly`：别人共享给你的歌单是只读的，编辑前要先判断（对应现有 `PlaylistWrite` / `PlaylistReorder` 能力位思路）。

### 3.4 播放与媒体

| 端点 | 用途 | 坑 |
|---|---|---|
| **`stream.view?id=&format=raw`** | 播放/试听 | 见 §4，这是全篇最重要的一节 |
| `download.view?id=` | 下载；id 可以是歌曲/专辑/歌手/歌单 | 专辑以上返回 **zip**；`EnableDownloads=false` 时回 50 |
| `getCoverArt.view?id=&size=` | 封面 | **要认证**；Navidrome 另有 `square` 参数 |
| `getAvatar.view?username=` | 头像 | 开了 Gravatar 会 302 |
| `getLyricsBySongId.view?id=` | 结构化歌词（OpenSubsonic `songLyrics`） | Airsonic 没有这个端点 |
| `getLyrics.view?artist=&title=` | 纯文本歌词 | 兼容老服务器 |
| `getInternetRadioStations.view` | 电台 | 和现有云听电台能力叠加 |

`getScanStatus.view` / `startScan.view`：Navidrome 多返回 `lastScan`、`folderCount`，`startScan` 多一个 `fullScan`，
且 `startScan` 是 `adminOnly`。可以做「设置里触发服务器扫描」，但属于锦上添花。

### 3.5 明确没有 / 会报错的端点（Navidrome `api.go` 路由表实测）

| 端点 | 行为 |
|---|---|
| `search.view`（v1 老搜索） | **410 Gone**（已废弃，用 `search2`/`search3`） |
| `getChatMessages` / `addChatMessage` | 410 Gone |
| `getVideos` / `getVideoInfo` / `getCaptions` / `hls` | 410 Gone（Navidrome 只做音乐） |
| `getPodcasts` / `createPodcastChannel` / `downloadPodcastEpisode` 等播客端点 | **501 Not Implemented** |
| `createUser` / `updateUser` / `deleteUser` / `changePassword` | 501 |
| `jukeboxControl` | 501，除非服务器开了 `Jukebox.Enabled` |
| `getShares` / `createShare` / `updateShare` / `deleteShare` | 501，除非开了 `EnableSharing` |
| `startScan` | 需要管理员角色，否则错误 50 |

Airsonic-Advanced 则反过来：`getChatMessages`/`addChatMessage` 410，`getVideoInfo`/`getCaptions` 501，
播客端点齐全。→ 客户端**不要假定任何非核心端点存在**，一律按错误码降级。

### 3.6 OpenSubsonic 扩展：Navidrome 实际广告的 8 个

`server/subsonic/opensubsonic.go` 里 `GetOpenSubsonicExtensions` 的硬编码返回：

| 扩展 ID | 版本 | 对客户端意味着什么 |
|---|---|---|
| `transcodeOffset` | 1 | `stream.view` 认 `timeOffset`（**秒**），转码时也能定位 |
| `formPost` | 1 | 参数可以走 POST form body，绕开 URL 长度上限 |
| `songLyrics` | **1, 2** | 有 `getLyricsBySongId`；v2 还有逐字 `cueLine`/`cue` |
| `indexBasedQueue` | 1 | 有 `getPlayQueueByIndex` / `savePlayQueueByIndex`，队列里允许重复曲目 |
| `transcoding` | 1 | 有 `getTranscodeDecision` / `getTranscodeStream`，让客户端声明解码能力 |
| `playbackReport` | 1 | 有 `reportPlayback`（带 `positionMs`/`state`/`playbackRate`） |
| `topSongsByArtistId` | 1 | `getTopSongs` 支持按歌手 ID 取 |
| `apiKeyAuthentication` | 1 | 认 `apiKey` 查询参数（且不能和 `u` 同时出现） |
| `sonicSimilarity` | 1 | 仅装了 AudioMuse-AI 插件时追加（`api.sonic.HasProvider()`） |

**扩展是逐项可选的**，规范原话："Each part of the OpenSubsonic API is designed to be optional"。
所以客户端的正确姿势是：**`ping` 之后调一次 `getOpenSubsonicExtensions`，把返回的 name 列表存进账号，
每个能力点用前先查表**，不要假定「Navidrome 就有」。

对 Airsonic-Advanced（以及所有非 OpenSubsonic 服务器）这张表是空的，
`getOpenSubsonicExtensions` 直接不存在（按错误 70/0 处理即可，不影响其它调用）。

---

## 4. 播放链路：Range、转码与拖动（关键结论）

Navidrome `server/subsonic/stream.go` → `core/stream/media_streamer.go:158`：

```go
// Serve writes the stream to the HTTP response. For seekable streams it uses http.ServeContent
// With estimateContentLength=true, a non-seekable stream declares an estimated Content-Length.
        http.ServeContent(w, r, s.Name(), s.ModTime(), s)
…
    w.Header().Set("Accept-Ranges", "none")
```

结论：

1. **`format=raw`（原文件直出）= 可 seek**，走 `http.ServeContent`，标准 206 + `Content-Range`。
   Media3 拖动、缓存、`LiveBypassDataSourceFactory` 全都正常工作。
2. **转码流（ffmpeg 管道）= 不可 seek**，服务器主动声明 `Accept-Ranges: none`，只有 `timeOffset`
   （OpenSubsonic `transcodeOffset`，单位**秒**，`core/stream.Request.Offset int // seconds`）能定位。
3. **`format=raw` 是权威的**：`core/stream/legacy_client.go ResolveRequest` 第一行就是
   `if reqFormat == "raw" { return Request{Format: "raw"} }`，**跳过服务器端按 player 配置的强制转码**。
   不带 `format` 也不带 `maxBitRate` 时，`buildLegacyClientInfo` 会给一个只声明 `ProtocolHTTP` 的
   DirectPlayProfile → 默认也是直出原文件。
4. 但**服务器可以给这个客户端配「强制转码格式」**（Navidrome 的 player 级 Transcoding 设置）。
   一旦命中，即使你请求 raw 之外的组合也可能拿到不可 seek 的流。

**对本应用的做法**：
- 播放一律 `stream.view?id=…&format=raw`，先把「原文件直出」坐实。
- 只有在 `getTranscodeDecision` 可用、且用户显式选了「省流量转码」时才走转码，并**明确告知拖动会重新起一条流**。
- 客户端可以声明自己的解码能力走 `getTranscodeDecision`（OpenSubsonic `transcoding` 扩展），
  但这需要探测 Media3 的解码器能力，工作量不小，**第一期不做**。
- 服务器返回 `Accept-Ranges: none` 时，参考 WebDAV 那条路径的既有处理（`WebDavRangeReader` 的
  `rangeIgnored` 回调 + `WebDavDataSource.Factory(onRangeIgnored)`）给用户一句提示，而不是静默劣化。

---

## 5. 和现有架构的对接点

### 5.1 作为 `NativeProvider` 接入（核心）

这是 Subsonic 和 WebDAV 的本质区别：**WebDAV 是「本地歌的云端副本」的一个存储位置，Subsonic 是一个独立的曲库源**，
所以它应该走 provider 那条路，而不是账号那条路。

#### 5.1.1 注册机制

`NativeProviders`（`data/native/NativeProviders.kt`）现在是一个进程启动时 `buildList` 出来的**静态表**：

```kotlin
private val providers: List<NativeProvider> = buildList { addProvider { NetEaseProvider() } … }
fun get(id: String): NativeProvider? = providers.firstOrNull { it.info.id == id }
fun infos(): List<ProviderInfo> = providers.map { it.info }
```

要支持多账号，把它改成「静态表 + 动态表」：

```kotlin
private val static = listOf(/* 现有 16 个 */)
private val dynamic = CopyOnWriteArrayList<NativeProvider>()   // Subsonic 实例

fun register(provider: NativeProvider) { dynamic.removeAll { it.info.id == provider.info.id }; dynamic += provider }
fun unregister(id: String) { dynamic.removeAll { it.info.id == id } }
fun all(): List<NativeProvider> = static + dynamic
```

`MusicRepository.providers()` / `requireProvider()` 改读 `all()`。这是唯一的接口层改动，`MusicRepository`
的几十个 `suspend fun` 一个都不用动。

#### 5.1.2 账号 ID 与 `Song.source`

每台服务器一个实例，`info.id = "subsonic:<accountId>"`（`accountId` 用 UUID 或 `md5(serverUrl|username)`）：

| 好处 | 说明 |
|---|---|
| `Song.key` 天然不撞 | 两台服务器如果歌单 ID 相同（都从同一批文件扫出来时完全可能），key 不会重 |
| `settings.cookies` 按 id 分格 | 现有 `AggregationSettings.enabledProviders` / `providerPriority` 都是 `List<String>`，按 id 粒度生效 |
| `ProviderHealth` 熔断按 id | 一台服务器抽风不会把其它服务器的源也熔断（`aggregate/ProviderHealth.kt`） |
| UI 各自显示服务器名 | `ProviderInfo.name` 填「家里的 Navidrome」这种用户自定义名，搜索/换源列表一眼分清 |

`Song` 的映射（`data/Models.kt`）：

| Subsonic 字段 | `Song` 字段 |
|---|---|
| `id` | `id` |
| `title` | `name` |
| `artist` / `displayArtist` | `artist` |
| `album` / `displayAlbumArtist` | `album` |
| `duration`（秒） | `duration`（秒，直接可用） |
| `size` | `size` |
| `bitRate`（kbps） | `bitrate` |
| `suffix` | `ext` |
| `coverArt` | `cover`（**不要直接填 URL**，见 §5.4） |
| `musicBrainzId` / `isrc[]` | `extra["mbid"]` / `extra["isrc"]`，匹配层能用 |
| — | `source = "subsonic:<accountId>"`，`url` 留空 |

`url` 留空是对的：`playableStream` 时才拼地址，`availabilityOf`（`aggregate/Availability.kt`）对
`isVip = false`、`url` 空的源返回 `Unknown`，而 `orderCandidates` 只过滤 `Unavailable`/`VipOnly`/`PreviewOnly`，
**`Unknown` 照样会被尝试**——不需要额外改聚合层。

#### 5.1.3 `cookie` 参数就是账号本身（不需要新存储，不需要 Keystore）

`NativeProvider` 的每个方法都收 `cookie: String`，来源是 `settingsState.value.cookies[provider.id]`
（见 `aggregate/AggregateSearchEngine.kt:87`、`SourceResolver`）。对 Subsonic **这个参数不再是"忽略"，
它就是账号**：

```
settings.cookies["subsonic:<accountId>"] = """{"url":"https://…","username":"…","password":"…","name":"家里的 Navidrome"}"""
```

之所以选这条路，是因为代码里现成的三件事正好都能白拿：

| 现成能力 | 位置 | 对 Subsonic 意味着什么 |
|---|---|---|
| `settings.cookies` 是 `Map<String, String>`，按 provider id 分格 | `data/AppStore.kt:257` `cookiesKey` | 多账号天然成立，key 就是账号 id |
| 同步快照带 `cookies`，含墓碑和 `updatedAt` | `data/sync/SyncModels.kt:36`、`SyncCoordinator.localSnapshot()` | 在一台设备上加服务器，其它设备自动出现；删账号以墓碑同步出去 |
| `settings.state` 是 Flow，VM 已经 `stateIn` 在观察 | `ui/MusicViewModel.kt:400` | provider 列表随账号变化自动刷新（见 5.1.5） |

`SubsonicProvider(account)` 构造时解析这条 JSON、闭包住自己的 `SubsonicAccount`，`cookie` 参数到了就是它自己。

**关于 Keystore：没有理由用。** `SecretStore`（`data/sync/SecretStore.kt`，Keystore + AES-GCM）
目前只服务两处——同步令牌和 WebDAV 密码，是**按需选用**的，不是应用级规定。
平台 Cookie 全都明文放在 `preferencesDataStore("mymusic")` 里并随同步文件同步。
Subsonic 账号和平台账号是同一类东西（"我在这个服务上的凭据"），走同一条路才一致；
单独给它上 Keystore 反而会让它成为唯一不能跨设备同步的账号。

**安全姿态要说清楚，不能假装被加密了：** 密码会明文进 DataStore，并明文进同步文件
（同步文件本身就是 WebDAV 上的一个 JSON）。这和现有平台 Cookie 完全同级，
设置 UI 里应该明说"该凭据会随同步文件同步到你的其他设备"，让用户自己决定同步位置放哪。

**真正不该同步的是自签证书指纹**，但理由不是安全而是语义：TOFU 指纹是"本机第一次连上时用户当面确认过"
的记录，同步出去等于让另一台设备无条件信任一张它从未验证过的证书。指纹按设备存在本机
（照 `WebDavCertificate` 的做法，但不进 `SyncSnapshot`）。

#### 5.1.4 `ProviderInfo` 能力位怎么填

| 字段 | 值 | 依据 |
|---|---|---|
| `Search` | ✅ | `search3.view` |
| `SearchPlaylist` / `PlaylistSongs` / `RecommendedPlaylists` / `UserPlaylists` | ✅ | `getPlaylists` / `getPlaylist` |
| `SearchAlbum` / `AlbumSongs` | ✅ | `search3` + `getAlbum` |
| `SearchArtist` | ✅ | `search3` |
| `HotSongs` | ✅ | `getAlbumList2?type=frequent` |
| `TopLists` / `TopListSongs` | 可选 | `getAlbumList2?type=newest\|recent\|highest` 包一层「服务器最新专辑」榜 |
| `Lyrics` | ✅ | `getLyricsBySongId`（降级 `getLyrics`） |
| `Download` | ✅ | `download.view` |
| `PlaylistWrite` | ✅ | `createPlaylist` / `updatePlaylist` / `deletePlaylist` |
| `PlaylistReorder` | ❌ | 协议只能按 index 删、尾部加，没有移动语义 |
| `Comments` | ❌ | Navidrome 410 Gone |
| `QRLogin` / `PasswordLogin` | ❌ | 账号在设置里配，不是平台登录态 |
| `LoginRequired` | ✅ 语义要改 | 「需要服务器凭据」，但由账号配置满足，不是扫码 |
| `ParseSong` / `ParsePlaylist` / `ParseAlbum` | ❌ | 没有分享链接可解析 |
| `ListeningModes` / `PlaylistCategories` | ❌ | 协议没有 |

`Unstable` / `Note` 可以填「自建服务器，行为随服务器实现而异」，和现有平台的用法一致。

#### 5.1.5 provider 列表刷新（唯一需要写代码的生命周期问题）

`MusicViewModel` 在 init 里**只读一次** `repository.providers()`（`ui/MusicViewModel.kt:817`），
之后不再刷新。账号改成存在 `settings.cookies` 里之后，`repository.providers()` 就能直接从 cookies 派生，
而 `settingsState` 是 VM 里已经 `stateIn` 起来的 Flow（`ui/MusicViewModel.kt:400`）——
加一个 collector 重读一次就行：

```kotlin
viewModelScope.launch {
    // 账号（settings.cookies 里的 subsonic:*）变了就重新注册/反注册 provider
    settingsState.map { it.cookies.keys }.distinctUntilChanged().collect {
        base.value = base.value.copy(providers = app.repository.providers())
    }
}
```

`MyMusicApp.onCreate()` 里按当前 cookies 做首次注册；之后增删账号都由上面这个 collector 兜住。

另外**新账号要自动启用**：`AggregationSettings.enabledOrDefault` 是
`enabledProviders.filter { it in available }`，而默认列表是 `listOf("netease","qq","kugou","kuwo","migu","lx")`——
新 id 不在里面就会被默认关掉，用户不手动去设置里开就搜不到任何东西。
照 LX 导入脚本时的写法（`ui/MusicViewModel.kt:749`）把新账号 id 追加进 `enabledProviders`。

### 5.2 数据层：歌曲缓存

账号不需要单独的存储（见 5.1.3）。要落库的只有歌曲缓存：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 不落库 | 每次进页面现拉 `getAlbumList2` / `getPlaylists` | 实现最快；大库（上万首）慢，离线不可用 |
| **B. 落一张薄缓存表**（推荐） | `subsonic_tracks(account_id, song_id, title, artist, album, duration, suffix, bitrate, cover_art, updated_at)`，主键 `(account_id, song_id)` | 搜索/排序/海报墙走本地；配合 15 分钟节流刷新（现成范式见 `BoundPlaylists`） |

Room 当前 `version = 12`（`data/AppStore.kt:1231`）。加表就是 v13 + 一条 `Migration(12, 13)`，
迁移写法照 `AppStore.MIGRATION_1_2` 的样子。`account_id` 从 cookie key 里取，
删账号时按它清行——墓碑同步过来时也要清（`applyCookies` 之后紧接着做）。

### 5.3 播放链路：为什么仍要一层路由 DataSource

`NativeProvider.playableStream` 返回的是 `PlayableStream(url, ext, bitrate, level, preview, extra)`
（`data/native/NativeProvider.kt:28`）——**只有一个 URL 字符串，没有 `dataSourceFactory` 这种口子**
（只有 `cloud/CloudModels.kt` 的 `CloudStream` 才有，而那条路只给本地歌的云端副本用）。
`SourceResolver` 拿到 URL 后原样塞进 `MediaItem`，最终由 `PlaybackService` 里那一条全局工厂链放。

现有取流工厂链（`playback/PlaybackService.kt:89`）：

```
DefaultMediaSourceFactory
 └─ LiveBypassDataSourceFactory（本地/直播绕过缓存）
     └─ CacheDataSource（SimpleCache）
         └─ BilibiliCdnFactory（防盗链 UA）
             └─ MHubTls.RoutingFactory（music.op.wiki 免验证证书）
                 └─ WebDavRoutingDataSourceFactory（cloud.routing）
                     └─ DefaultDataSource.Factory
```

Subsonic 就插在 `WebDavRoutingDataSourceFactory` 外面（或里面，等价）：

```kotlin
val upstream = BilibiliCdnFactory(
    fallback = MHubTls.RoutingFactory(
        fallback = SubsonicRoutingDataSourceFactory(
            fallback = app?.cloud?.routing(DefaultDataSource.Factory(this)) ?: DefaultDataSource.Factory(this),
        )
    )
)
```

`playableStream` 返回的 URL **不带凭据**：

```
https://nas.example.com/rest/stream.view?id=<songId>&format=raw
```

`SubsonicRoutingDataSourceFactory` 按 scheme+host+port 认出是哪台服务器（和
`WebDavRoutingDataSourceFactory.handles` 同一套判法），再从 `settings.cookies` 里对应的
`subsonic:<accountId>` 取出账号，用它的 OkHttpClient（带 `u`/`t`/`s`/`v`/`c` 参数注入 +
自签证书信任）起 `OkHttpDataSource`；认不出来就原样交给 fallback。
账号查找走内存里那份 cookie 快照，**不解密、不碰磁盘**，和 `CloudHub.routing` 现在读
`webDavAccounts.info.value?.serverUrl` 而不解密密码是同一个套路。

**为什么值得多这一层，而不是把凭据直接拼进 URL：**

1. **Media3 的缓存键就是 `DataSpec.uri`。** 盐每次随机 → 每次拖动都是新缓存键 → 缓存永远打不中，
   同一首歌还会存好几份。
2. **`SourceResolver.urlCache` 会把 URL 在内存里存 `URL_TTL_MS`**（`aggregate/SourceResolver.kt`），
   凭据版 URL 等于在进程里散落一把长期有效的钥匙。
3. `Song.url` / `PlayableStream.url` 会经过 `AppLog`、崩溃栈、分享卡片。
4. 固定盐 + 固定 token 虽然能让缓存生效，但那是把凭据写进每一个 `Song` 对象、每一份歌单快照。

反过来，**如果就是要把凭据拼进 URL**（少写一个类），最低限度也要：
- 盐在账号保存时生成一次并固定，保证 URL 稳定、缓存能命中；
- 用可吊销的 API key（`nds_…`）而不是密码派生的 token；
- 确认 `SourceResolver.urlCache`、`QueueStore`、`AppLog` 都没有把 URL 落盘。

> 这一层和「Subsonic 是不是 NativeProvider」是两个独立决定。做成 NativeProvider 解决的是
> 「怎么成为一个源」；这一层解决的是「Subsonic 的认证只能放在查询参数里」。
> WebDAV 需要它，是因为 WebDAV 用请求头认证；Subsonic 需要它，是因为 Subsonic 用查询参数认证、
> 而查询参数会污染缓存键和日志。

### 5.4 封面：这是最容易被低估的一块

`getCoverArt.view` 和 `stream.view` 一样要认证，而本仓库有 **29 处 `AsyncImage`、散布在 11 个文件**里
（`AppUi` / `CoverStage` / `PosterTile` / `GrownPoster` / `ChartsScreen` / `ArtistScreen` /
`CommentsSheet` / `SettingsSubScreens` / `LyricsSettingsScreen` / `VinylDiscVisualizer` / `WidgetImages`），
全部按 `model = song.cover` 这种明文 URL 加载。改 29 个调用点不现实。

三个方案：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 本地磁盘缓存 | 后台把封面字节写进 `cacheDir/covers/<accountId>/<coverArtId>@<size>.jpg`，`Song.cover` 填 `file://…` | **推荐**。零改动下游；离线可用；RemoteViews/桌面小组件也能用；Coil 内存缓存天然生效 |
| B. 全局 Coil 拦截器 | 给 Application 实现 `ImageLoaderFactory`，在 OkHttp 层按 host 补认证参数 | 改动小（一个类），但只解决 Coil 路径，桌面小组件（`WidgetImages` 用 `SingletonImageLoader.execute`）和分享截图要另行确认；且自签证书也要在这套 client 里再配一遍 |
| C. 每处传 headers | — | 否决，侵入面太大 |

选 A。注意：
- 缓存键用 `coverArt` id（Navidrome 的 `pl-xxxx` / `al-xxxx` / `ar-xxxx` / `mf-xxxx`）+ 账号 id，
  **不要**用 `id=xxx&size=300` 这种带查询串的形式做文件名。
- 尺寸档位照现有 `ui/poster/CoverSizes.kt` 的思路（「换档后地址不同，Coil 的缓存键自然按档位分开」），
  按 `300` / `600` 两档各存一份。
- `Song.cover` 指到 `file://` 后，`CoverSizes` 那类「按 URL 改尺寸」的逻辑会失效——
  需要让它认识 `subsonic-cover:` 这种自定义 scheme，或者干脆在模型层存两套（原 id + 本地路径）。

### 5.5 歌词

`getLyricsBySongId` 的 golden vector（`Responses LyricsList with data should match .JSON`）：

```json
{"lyricsList":{"structuredLyrics":[
  {"displayArtist":"Rick Astley","displayTitle":"Never Gonna Give You Up","lang":"eng",
   "line":[{"start":18800,"value":"We're no strangers to love"},
           {"start":22801,"value":"You know the rules and so do I"}],
   "offset":100,"synced":true},
  {"displayArtist":"…","lang":"xxx",
   "line":[{"value":"We're no strangers to love"},{"value":"You know the rules and so do I"}],
   "offset":100,"synced":false}
]}}
```

对接现有歌词管线的要点：
- `start` 是**毫秒**，`synced=false` 时整条没有 `start`。
- `offset` 是整条歌词的统一偏移（毫秒），**不要**把它加到每一行的 `start` 上——按 OpenSubsonic
  规范它「applies equally to both timestamps」，即整条平移，由渲染层处理。
- 一条歌可以有多组 `structuredLyrics`（多语言）。现有管线已经有翻译层合并顺序的处理
  （见 `ui/LyricPipeline.kt`），按 `lang` 取 `eng`/`und` 优先即可。
- **Airsonic-Advanced 没有 `getLyricsBySongId`**，只有 `getLyrics?artist=&title=` 的纯文本。
  能力探测（`getOpenSubsonicExtensions`）失败时走纯文本分支——正好对应 `ProviderInfo.lyrics` 能力位
  可以按服务器实现动态置 false。
- v2 才有逐字（`cueLine`/`cue`/`byteStart`/`byteEnd`）。Navidrome 已经广告 `songLyrics: [1, 2]`，
  但**第一期只吃 line 级**；逐字等现有 Rush 歌词页有富余再上。

### 5.6 歌单与「绑定平台歌单」

现有绑定机制（`data/BoundPlaylists.kt` + `data/AppStore.kt` 的 `PlaylistEntity.linkProvider/linkId`）
本来就是 provider 无关的，Subsonic 歌单可以直接当第 N 个 provider 绑：
- `linkProvider = "subsonic:<accountId>"`，`linkId = <playlist id>`。
- 写回用 `updatePlaylist`（Navidrome 支持增删；排序要靠「删到位置再插」模拟，代价高，
  所以 `ProviderInfo.playlistReorder = false`，正好和现有 `PlaylistReorder` 能力位对得上）。
- `readonly=true` 的共享歌单不能写，编辑时自动解绑（照现有「没有这个能力的平台编辑绑定歌单仍然解绑」的规则）。

### 5.7 下载

`NativeProvider.downloadUrl(cookie, song, quality)` 是现成的钩子，返回 `download.view?id=…` 的地址即可，
下载记录 `DownloadRecord(key, payload, path, state)`（`data/AppStore.kt:909`）那套完全不用改。
- 单曲直接写文件；专辑/歌手/歌单返回 zip，要么解包要么**只对单曲开放下载**（推荐后者，第一期）。
- `EnableDownloads=false` 时回错误 50，`downloadUrl` 要把 message 抛成人话。

### 5.8 同步：哪些跟同步走、哪些不跟（以及为什么）

先纠正一个容易搞错的事实：**现有同步文件里已经明文放着平台 Cookie**。
`SyncSnapshot.cookies: List<SyncCookie>`（`data/sync/SyncModels.kt:36`），
`SyncCoordinator.localSnapshot()` 建快照时 `settings.cookieSnapshot()` 全量带上，
没有任何开关。所以「密码不能进同步文件」**不构成不同步的理由**——应用的既有安全姿态已经接受了这件事。

真正的原因是语义上的。

#### 5.8.1 服务器自己就是同步后端

Navidrome / Airsonic 上这些状态全在服务端，同一账号的所有客户端天然共享：

| 状态 | 服务端端点 | 要客户端同步吗 |
|---|---|---|
| 收藏 | `getStarred2` / `star` / `unstar` | 不要 |
| 歌单及内容 | `getPlaylists` / `getPlaylist` / `updatePlaylist` | 不要 |
| 播放队列 | `getPlayQueue` / `savePlayQueue` | 不要 |
| 书签 / 播放位置 | `getBookmarks` / `createBookmark` | 不要 |
| 电台 | `getInternetRadioStations` | 不要 |
| 播放次数 | `scrobble` | 不要 |

这正是 Subsonic 和网易云 / QQ 的本质区别：那些平台的收藏和歌单客户端**没有写入权**，
所以要用「自建歌单 + 绑定平台歌单」去代理，才需要跨设备同步；Subsonic 的写入权是完整的，
服务器就是权威源。客户端再存一份只会带来「以谁为准」的合并冲突，而这种冲突没有有意义的答案。

现有同步 v3 要解决的命题——「自建歌单 + 本地音乐线索在多台设备间一致」——在 Subsonic 场景下不存在：
歌单就在服务器上，本机只是一个视图。下载 / 离线缓存是按设备的，和所有平台一样不跟同步走。

#### 5.8.2 但有三样东西确实会跟同步走，而且全都是白拿

1. **歌单绑定（零改动）。** `SyncPlaylist.link: SyncLink?` 带 `provider` + `id` + `cover`，
   建快照和合并都在处理它（`data/sync/SyncCoordinator.kt:1500`）。`provider` 就是个字符串，
   填 `"subsonic:<accountId>"` 直接就能用。
2. **收藏（零改动）。** `applyFavorites` 按 `key` 存 `SavedSong` + `Song` JSON，
   **不检查 provider 是否配过**。所以 A 设备收藏了一首 Subsonic 歌，B 设备同步过来照样显示；
   B 设备没配这台服务器时只是播不了，和「某个平台在这台设备上不可用」走同一套降级。
3. **账号本身（零改动）。** 见 5.8.3。

#### 5.8.3 账号：随同步走，而且是白拿的

既然账号就是 `settings.cookies` 里的一条 value（§5.1.3），那它在**设计上就已经跟着同步 v3 走了**——
不需要写任何同步代码：

| 行为 | 现成机制 |
|---|---|
| 在手机上添加服务器 → 电视上出现 | `SyncSnapshot.cookies` 建快照全量带上 |
| 删账号 → 所有设备上消失 | `SyncCookie.deletedAt` 墓碑 + `applyCookies` 的移除分支 |
| 改密码 → 以 `updatedAt` 新的为准 | `applyCookies` 按 provider 逐个比时间戳 |
| 「用云端覆盖本机」→ 本机多余的账号被清掉 | `applyCookies(removeAbsent = true)` |

之前一版我写「密码不进同步文件、所以账号不同步」，两个前提都是错的：
`settings.cookies` 本来就没加密（`preferencesDataStore("mymusic")` 明文字符串），
平台 Cookie 也本来就随同步文件走且没有开关。Subsonic 账号和平台账号是同一类东西，走同一条路才一致。

**要处理的只有两件事：**

1. **UI 上要说清楚。** 密码明文进同步文件，设置里保存账号时给一句提示
   （"凭据会随同步文件同步到你的其他设备"），同步位置是用户自己选的 WebDAV，风险由用户判断。
2. **连不上是常态，不是异常。** Subsonic 服务器可能是内网地址、Tailscale、Cloudflare Tunnel、
   公网反代域名——没有哪一种应该被假定。手机在移动网络下连不上家里的 NAS 是正常的，
   所以 UI 要能显示「连不上」并允许单设备删除，而不是把"同步过来一台连不上的服务器"当成设计缺陷。
   （真正按设备存的只有自签证书指纹，理由见 §5.1.3。）

### 5.9 不在范围内的事

- **不做上传。** Subsonic 协议没有上传端点（Navidrome 只有 Native API 能传，且要 JWT），
  所以 `data/cloud` 那套 `CloudUploadSource` 与 Subsonic 无关。
- **不做 Jukebox。** Navidrome 未实现 `jukeboxControl`，Airsonic 有但没人用。
- **不做播客 / 视频 / 聊天。** Navidrome 对这些端点一律 410 / 501。

---

## 6. 坑与风险清单（按严重度排序）

| # | 坑 | 影响 | 规避 |
|---|---|---|---|
| 1 | **转码流不支持 Range** | 拖动失败/从头读，用户体感「卡」 | 默认 `format=raw`；探测到 `Accept-Ranges: none` 就给提示 |
| 2 | **封面/歌词/取流全要认证** | 封面白图、播放 401 | 封面走本地磁盘缓存（§5.4）；播放走路由 DataSource（§5.3） |
| 3 | **登录限流伪装成密码错误**（5 次/20 秒） | 用户反复被拒却以为是密码错 | 「测试连接」不自动重试；错误 40 的文案里带「可能被临时限流，请稍等 20 秒」 |
| 4 | **`musicFolder.id` 是 int，其它 id 是 string** | 解析崩溃 / 类型不匹配 | 解析层对 id 统一按「可 string 可 int」处理 |
| 5 | **Navidrome 没有文件夹浏览**（模拟树） | 做了「按目录」UI 就废了 | UI 只做 歌手/专辑/风格/歌单 |
| 6 | **Airsonic-Advanced 无 OpenSubsonic** | `getLyricsBySongId`、`formPost`、`apiKey`、`indexBasedQueue` 全没有 | 每个能力都做 `ping`/`getOpenSubsonicExtensions` 探测 + 降级分支 |
| 7 | **每次请求都会注册/触碰一个 player，并种 cookie** `nd-player-<hex(user)>` | 用户在自己服务器上看到一堆陌生设备条目 | 用一个固定的 `c=myMusic`；apiKey 认证时不种 cookie（源码 `boundByKey` 分支） |
| 8 | **自签证书**（无论内网还是公网域名都有人用） | 连不上，且报错信息看不懂 | 复用 `WebDavCertificate` 的 TOFU 指纹钉住（`PinnedTrustManager`）；指纹按设备存、不进同步文件；明文已全局放行（`app/src/main/res/xml/network_security_config.xml`），无需额外配置 |
| 9 | **Android 17 本地网络权限**（仅当地址解析到私网/链路本地时） | 局域网 IP 直接被内核丢包，服务端无日志 | 复用 `data/sync/LocalNetworkAccess.kt`，连之前先查 `isGranted`；公网域名和 Tailscale 走不到这条 |
| 10 | **`stream` 不计播放数**（Navidrome 官方明说只有 `scrobble` + `submission=true` 才算） | 服务器端「已播放」永远是 0 | 播够时长发一次 `scrobble`（现有 playreport 体系已有类似钩子） |
| 11 | **大库分页慢**（`getAlbumList2` 的 `size` 上限 500，靠 `offset`） | 首屏慢 | 本地缓存表 + 分页预取；首次同步显示进度 |
| 12 | **`getCoverArt` 有独立并发限流**：Navidrome 默认 `max(2, CPU/2)` 个并发，超出排队（积压上限 100、超时 1 分钟） | 海报墙/网格一次性拉几十张封面时明显变慢 | 封面按 §5.3 落本地磁盘，只拉一次；Coil 自带并发与内存缓存 |
| 13 | **`Song.url` / `PlayableStream.url` 会泄密** | 凭据进 `SourceResolver.urlCache`、日志、分享卡 | 认证只在 DataSource 层注入（§5.3），URL 保持干净 |
| 14 | **新账号默认不在 `enabledProviders` 里**（默认列表只有 netease/qq/kugou/kuwo/migu/lx） | 加完服务器搜不到任何歌，用户以为坏了 | 保存账号时把 id 追加进 `AggregationSettings.enabledProviders`（照 `MusicViewModel.kt:749` LX 的写法） |
| 15 | **`MusicViewModel` 只在 init 读一次 provider 列表** | 运行时新增/删除服务器不出现在搜索和换源列表 | 账号放 `settings.cookies` 后，`repository.providers()` 从 cookies 派生；VM 收集 `settingsState.map { it.cookies.keys }` 重读（§5.1.5） |
| 16 | **密码明文进 DataStore，并明文随同步文件同步** | 用户以为凭据被加密了 | 和平台 Cookie 完全同级，不是新风险；但保存账号的 UI 要明说"会随同步文件同步到你的其他设备"，别装作加密过 |
| 17 | **服务器地址形态五花八门**（内网 IP / Tailscale / Cloudflare Tunnel / 公网反代域名） | 手机在移动网络下连不上家里的 NAS，容易被当成 bug | 不要假定某一种形态；UI 显示「连不上」并允许单设备删除，退出登录的墓碑会把它从所有设备清掉 |

---

## 7. 分期建议

| 期 | 内容 | 验收标准 |
|---|---|---|
| **P0 打通** | `data/subsonic/` 协议层 + `SubsonicProvider`（`NativeProvider` 实现）；`NativeProviders` 动态注册；账号就是 `settings.cookies` 里的一条 value；账号配置 UI | 加一台服务器后，搜索/换源列表里出现它自己的名字；填错密码能看到人话错误 |
| **P1 能听** | `stream.view?format=raw` + `SubsonicRoutingDataSourceFactory` 接入 `PlaybackService`；封面本地磁盘缓存 | 从「专辑 → 歌曲」点进去能播，能拖动，海报墙和播放页封面正常 |
| **P2 能浏览** | `search3` / `getAlbumList2` / `getArtists` / `getAlbum` / `getGenres`；Room v13 缓存表 + 分页 | 上万首的库首屏 1 秒内；搜索可用 |
| **P3 能管** | 收藏（`star`/`unstar`）、评分（`setRating`）、歌单增删 + 绑定平台歌单、歌词（`getLyricsBySongId` + 纯文本降级）、`downloadUrl` | 编辑后刷新服务器上能看到变化；下载能落盘 |
| **P4 锦上添花** | `scrobble` 上报、播放上报 `reportPlayback`、播放队列保存/恢复（`savePlayQueue` / `savePlayQueueByIndex`）、`getInternetRadioStations`、「服务器最新专辑」榜 | — |

P0 的落地顺序（都是小步，可分别提交）：

1. `NativeProviders` 拆静态/动态两张表，`all()` 合并——先不动任何现有行为。
2. `SubsonicAccount` + 存取：就是 `settings.cookies` 里 `subsonic:<accountId>` → JSON，
   **不新建存储、不用 Keystore**；删除账号时同步写墓碑（`SyncCookie(deletedAt)` 那套）。
3. `SubsonicClient`：OkHttp + JSON + 四种认证 + 错误码映射 + 限流不重试。
4. `SubsonicProvider(account)`：实现 `info`、`searchSongsPage`、`playableStream`、`playlistSongs`、`lyrics`。
5. `MusicRepository.providers()` 从 cookies 派生 Subsonic 实例；`MyMusicApp.onCreate` 首次注册；
   VM 收集 `settingsState.cookies` 刷新 + 新账号自动启用。
6. 账号配置 UI（照 `ui/local/LocalWebDavScreen.kt` / `ui/library/WebDavSyncForm.kt` 的表单范式），
   保存时提示"凭据会随同步文件同步"。

### 7.1 Navidrome 已注册但本文未展开的端点

全部来自 `server/subsonic/api.go` 路由表，接入时按需取用：

- `tokenInfo` — 转码 token 信息（配合 `getTranscodeStream`）。
- `getSonicSimilarTracks` / `findSonicPath` — 装了 AudioMuse-AI 插件才有（对应 `sonicSimilarity` 扩展）。
- `getBookmarks` / `createBookmark` / `deleteBookmark` — 书签（带播放位置），可做「继续播放」。
- `getNowPlaying` — 同账号其它设备正在放什么。
- `getUser` — 拿当前用户角色（`downloadRole`/`jukeboxRole` 等），可用来判断服务器开没开下载。
- `reportPlayback`（OpenSubsonic `playbackReport`）— `mediaId`/`mediaType`/`positionMs`/`state`/`playbackRate`/`ignoreScrobble`。
  比 `scrobble` 更细，`ignoreScrobble=true` 时只更新「正在播放」不计播放数。

---

## 8. 未验证项与下一步

本文的所有协议结论都来自官方文档与源码，**没有真机实测**。接入前建议按这个顺序补实测：

0. **先守住院子**：`NativeProviders` 拆静态/动态表后，加一条单测断言 `all()` 返回的 id 集合和现在完全一致
   （16 个），且 `get()` / `providerForSession()` 行为不变。这一步不过，后面全白做。
1. **最小闭环**：用自己的 Navidrome 实例跑通
   `curl 'https://host/rest/ping.view?u=x&t=<md5(pw+salt)>&s=<salt>&v=1.16.1&c=myMusic&f=json'`，
   确认 `status=ok` 和 `type`。这一步不做，后面全是猜。
2. **Range 实测**：同一首歌分别 `format=raw` 和不带 format，用 `curl -r 1000-2000` 各打一次，
   看是不是 206。这决定 §4 的结论在你的服务器上成立。
3. **Airsonic-Advanced 兼容**：如果身边有实例，确认 `f=json` 与 `getAlbumList2` 的实际返回
   （Airsonic 的 JSON 是 JAXB 序列化出来的，字段名可能和 Navidrome 有细微差别）。
4. **自签证书**：确认 `PinnedTrustManager` 复用到 Subsonic 的 OkHttpClient 上无需改动
   （`data/webdav/WebDavCertificate.kt:122` 的 `manager` 已经是独立于 WebDAV 的）。
5. **凭据不进 URL 的代价**：如果最终选择把 `u&t&s` 拼进 URL（§5.3 的退让方案），
   要实测 Media3 缓存命中率，并确认 `SourceResolver.urlCache` / `QueueStore` / `AppLog` 都没有落盘。
6. **golden vector 单测**：把 §2.3 / §3.3 / §5.5 里那份快照 JSON 抄成测试资源，
   解析层按它逐字段自测。这和本仓库处理签名类问题的思路一致：**缺的是算法契约，不是流量样本**。

---

## 附：本仓库相关文件索引

| 关注点 | 文件 |
|---|---|
| 歌曲/歌单模型 | `data/Models.kt` |
| **Provider 抽象（Subsonic 就实现在这）** | `data/native/NativeProvider.kt`、`data/native/NativeProviders.kt` |
| 账号存放处（`settings.cookies`，明文 DataStore） | `data/AppStore.kt:257`（`cookiesKey`）、`:359`（解析） |
| provider 列表刷新位置 | `ui/MusicViewModel.kt:817`、`:400`（`settingsState`）、`:749`（LX 自动启用的写法） |
| Keystore（按需选用，Subsonic 不用） | `data/sync/SecretStore.kt` |
| 聚合搜索/换源的 cookie 与可用性 | `aggregate/AggregateSearchEngine.kt`、`aggregate/SourceResolver.kt`、`aggregate/Availability.kt` |
| 启用开关与平台顺序 | `aggregate/Models.kt`（`AggregationSettings`） |
| 同步快照结构（账号/绑定/收藏都跟同步走） | `data/sync/SyncModels.kt`、`data/sync/SyncCoordinator.kt`、`data/AppStore.kt:703`（`applyCookies`） |
| 自签证书指纹钉住范本（按设备存，不同步） | `data/webdav/WebDavCertificate.kt` |
| 路由 DataSource 范本 | `data/webdav/WebDavDataSource.kt`、`data/native/MHubTls.kt` |
| 播放器工厂链（插入点） | `playback/PlaybackService.kt:89` |
| Room 版本与迁移 | `data/AppStore.kt:1211` |
| 内网权限 | `data/sync/LocalNetworkAccess.kt` |
| 明文流量配置 | `app/src/main/res/xml/network_security_config.xml` |
| 绑定平台歌单 | `data/BoundPlaylists.kt` |
| 歌词管线 | `ui/LyricPipeline.kt` |
| 账号配置 UI 范本 | `ui/local/LocalWebDavScreen.kt`、`ui/library/WebDavSyncForm.kt` |
