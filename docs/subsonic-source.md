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
| 建议定位 | 和 WebDAV 同级：一个**用户自配置的本机/内网源**，不是 `music.op.wiki` 的又一个 provider。 |

三条核心判断：

1. **做成「自配置源」，不要做成 `NativeProvider`。** 现有十多个 provider（`data/native/*Provider.kt`，另有一个 LX 源）全部走 `music.op.wiki` 统一网关（`MusicRepository.providers()` → `NativeProviders.infos()`），cookie 由全局设置持有。Subsonic 服务器是用户自己填的地址 + 自己的账号密码，天然是多账号，和 WebDAV 账号同构（`data/webdav/WebDavAccount.kt`）。
2. **但一定要产出 `Song` / `Playlist`。** 本仓库的歌单、收藏、播放队列、海报墙、歌词、缓存全部以 `Song.key = "$source:$id"` 为身份。Subsonic 层只做「协议 → `Song`」的翻译，下游 UI 一行不改。
3. **认证走查询参数，所以播放链路要加一层路由 DataSource。** 详见 §5.2。这是唯一需要动 `PlaybackService` 的地方，而且现成的 `WebDavRoutingDataSourceFactory` 就是范本。

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

### 5.1 数据层：账号与歌曲缓存

WebDAV 账号用的是 SharedPreferences（`mymusic_webdav`）+ Keystore 存密码
（`data/webdav/WebDavAccount.kt:108 WebDavAccountStore`）。Subsonic 账号**同构且需要多账号**，
建议照抄这套：SharedPreferences 存账号列表（JSON 数组），密码/API key 进 `SecretStore`。

需要落库的只有「歌曲缓存」。两种选择：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 不落库 | 每次进页面现拉 `getAlbumList2`/`getPlaylists` | 实现最快；大库（上万首）会慢，且离线不可用 |
| **B. 落一张薄缓存表**（推荐） | `subsonic_tracks(subsonic_track_id, account_id, song_id, title, artist, album, duration, suffix, bitrate, cover_art, updated_at)`，Room 升到 **v13** | 搜索/排序/海报墙都走本地；配合 15 分钟节流刷新（现成范式见 `BoundPlaylists`） |

Room 当前 `version = 12`（`data/AppStore.kt:1231`）。加表就是 v13 + 一条 `Migration(12, 13)`，
迁移写法照 `AppStore.MIGRATION_1_2` 的样子。

### 5.2 播放链路：加一层路由 DataSource

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

`SubsonicRoutingDataSourceFactory` 的职责：URL 的 host+port 命中某个已配置的 Subsonic 账号时，
用一个带认证参数 + 自签证书信任的 OkHttpClient 去 `OkHttpDataSource`；否则原样交给 fallback。

**为什么必须在 open() 时注入认证参数，而不是把 `u&t&s` 拼进 `Song.url`：**

1. Media3 的缓存键就是 `DataSpec.uri`。盐每次随机 → 每次拖动都是一条新缓存键 → 缓存永远打不中，
   还会把同一首歌存好几份。
2. 密码派生 token 会出现在 Media3 的 `Log`、`MediaMetadata`、通知栏、崩溃栈里。
3. 固定盐 + 固定 token 虽然能缓效，但等于把一把长期有效的凭据写进每一个 `Song` 对象、
   每一个歌单快照、每一份同步文件。`Song.url` 是会进同步文件和分享卡片的。

→ **URL 保持干净**（`https://host/rest/stream.view?id=X&v=1.16.1&c=myMusic&format=raw`），
认证参数在 DataSource.open() 里补。这正是 `WebDavRoutingDataSourceFactory` 已经解决过的同一个问题
（见 `data/webdav/WebDavDataSource.kt:211` 的注释："播放器只有一个 DataSource.Factory"）。

### 5.3 封面：这是最容易被低估的一块

`getCoverArt.view` 和 `stream.view` 一样要认证，而本仓库有 **29 处 `AsyncImage`、散布在 11 个文件**里
（`AppUi` / `CoverStage` / `PosterTile` / `GrownPoster` / `ChartsScreen` / `ArtistScreen` /
`CommentsSheet` / `SettingsSubScreens` / `LyricsSettingsScreen` / `VinylDiscVisualizer` / `WidgetImages`），
全部按 `model = song.cover` 这种明文 URL 加载。改 29 个调用点不现实。

三个方案：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 本地磁盘缓存 | 后台把封面字节写进 `cacheDir/covers/<id>.jpg`，`Song.cover` 填 `file://…` | **推荐**。零改动下游；离线可用；RemoteViews/桌面小组件也能用；Coil 内存缓存天然生效 |
| B. 全局 Coil 拦截器 | 给 Application 实现 `ImageLoaderFactory`，在 OkHttp 层按 host 补认证参数 | 改动小（一个类），但只解决 Coil 路径，桌面小组件（`WidgetImages` 用 `SingletonImageLoader.execute`）和分享截图要另行确认；且自签证书也要在这套 client 里再配一遍 |
| C. 每处传 headers | — | 否决，侵入面太大 |

选 A。注意：
- 缓存键用 `coverArt` id（Navidrome 的 `pl-xxxx` / `al-xxxx` / `ar-xxxx` / `mf-xxxx`），
  **不要**用 `id=xxx&size=300` 这种带查询串的形式做文件名。
- 尺寸档位照现有 `ui/poster/CoverSizes.kt` 的思路（「换档后地址不同，Coil 的缓存键自然按档位分开」），
  按 `300` / `600` 两档各存一份。
- `Song.cover` 指到 `file://` 后，`CoverSizes` 那类「按 URL 改尺寸」的逻辑会失效——
  需要让它认识 `subsonic-cover:` 这种自定义 scheme，或者干脆在模型层存两套（原 id + 本地路径）。

### 5.4 歌词

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
  （见 `ui/LyricPipeline.kt` 与记忆里的 `lyric-sync-media3`），按 `lang` 取 `eng`/`und` 优先即可。
- **Airsonic-Advanced 没有 `getLyricsBySongId`**，只有 `getLyrics?artist=&title=` 的纯文本。
  能力探测（`getOpenSubsonicExtensions`）失败时走纯文本分支。
- v2 才有逐字（`cueLine`/`cue`/`byteStart`/`byteEnd`）。Navidrome 已经广告 `songLyrics: [1, 2]`，
  但**第一期只吃 line 级**；逐字等现有 Rush 歌词页有富余再上。

### 5.5 歌单与「绑定平台歌单」

现有绑定机制（`BoundPlaylists` + `data/AppStore.kt PlaylistEntity.linkProvider/linkId`）本来就是
provider 无关的。Subsonic 歌单可以直接当第 N 个 provider 绑：
- `linkProvider = "subsonic:<accountId>"`，`linkId = <playlist id>`。
- 写回用 `updatePlaylist`（Navidrome 支持增删；排序要靠「删到位置再插」模拟，代价高，
  建议和 QQ/酷狗一样只声明「增删」不支持「整单重排」——正好对应现有 `PlaylistWrite`/`PlaylistReorder` 两个能力位）。
- `readonly=true` 的共享歌单不能写，编辑时自动解绑（照现有「没有这个能力的平台编辑绑定歌单仍然解绑」的规则）。

### 5.6 下载

下载记录是 `DownloadRecord(key, payload, path, state)`（`data/AppStore.kt:909`），`payload` 是 `Song` JSON。
Subsonic 下载就是把 `download.view?id=…` 的字节写进文件再记一条。
- 单曲直接写文件；专辑/歌手/歌单返回 zip，要么解包要么**只对单曲开放下载**（推荐后者，第一期）。
- `EnableDownloads=false` 时回错误 50，要给出人话提示。

### 5.7 不在范围内的事

- **不同步。** Subsonic 源不进 `SyncCoordinator` 的同步文件（那是「跨设备同步自建歌单」的语义）。
  WebDAV 账号的密码「永远不进同步文件」这条规则同样适用，且更要紧。
- **不做上传。** `NativeProvider` 那套 `CloudUploadSource` 是给「本地歌的云端副本」用的；
  Subsonic 服务器没有公开的 Subsonic 上传端点（Navidrome 只有 Native API 能传，且要 JWT）。
- **不做 Jukebox。** Navidrome 未实现 `jukeboxControl`，Airsonic 有但没人用。

---

## 6. 坑与风险清单（按严重度排序）

| # | 坑 | 影响 | 规避 |
|---|---|---|---|
| 1 | **转码流不支持 Range** | 拖动失败/从头读，用户体感「卡」 | 默认 `format=raw`；探测到 `Accept-Ranges: none` 就给提示 |
| 2 | **封面/歌词/取流全要认证** | 封面白图、播放 401 | 封面走本地磁盘缓存（§5.3）；播放走路由 DataSource（§5.2） |
| 3 | **登录限流伪装成密码错误**（5 次/20 秒） | 用户反复被拒却以为是密码错 | 「测试连接」不自动重试；错误 40 的文案里带「可能被临时限流，请稍等 20 秒」 |
| 4 | **`musicFolder.id` 是 int，其它 id 是 string** | 解析崩溃 / 类型不匹配 | 解析层对 id 统一按「可 string 可 int」处理 |
| 5 | **Navidrome 没有文件夹浏览**（模拟树） | 做了「按目录」UI 就废了 | UI 只做 歌手/专辑/风格/歌单 |
| 6 | **Airsonic-Advanced 无 OpenSubsonic** | `getLyricsBySongId`、`formPost`、`apiKey`、`indexBasedQueue` 全没有 | 每个能力都做 `ping`/`getOpenSubsonicExtensions` 探测 + 降级分支 |
| 7 | **每次请求都会注册/触碰一个 player，并种 cookie** `nd-player-<hex(user)>` | 用户在自己服务器上看到一堆陌生设备条目 | 用一个固定的 `c=myMusic`；apiKey 认证时不种 cookie（源码 `boundByKey` 分支） |
| 8 | **内网地址 + 自签证书** | 连不上，且报错信息看不懂 | 复用 `WebDavCertificate` 的 TOFU 指纹钉住（`PinnedTrustManager`）；明文已全局放行（`app/src/main/res/xml/network_security_config.xml`），无需额外配置 |
| 9 | **Android 17 本地网络权限** | 局域网 IP 直接被内核丢包，服务端无日志 | 复用 `data/sync/LocalNetworkAccess.kt`，连内网地址前先查 `isGranted` |
| 10 | **`stream` 不计播放数**（Navidrome 官方明说只有 `scrobble` + `submission=true` 才算） | 服务器端「已播放」永远是 0 | 播够时长发一次 `scrobble`（现有 playreport 体系已有类似钩子） |
| 11 | **大库分页慢**（`getAlbumList2` 的 `size` 上限 500，靠 `offset`） | 首屏慢 | 本地缓存表 + 分页预取；首次同步显示进度 |
| 12 | **`getCoverArt` 有独立并发限流**：Navidrome 默认 `max(2, CPU/2)` 个并发，超出排队（积压上限 100、超时 1 分钟） | 海报墙/网格一次性拉几十张封面时明显变慢 | 封面按 §5.3 落本地磁盘，只拉一次；Coil 自带并发与内存缓存 |
| 13 | **`Song.url` 会泄密** | 凭据进同步文件/分享卡/日志 | 认证只在 DataSource 层注入（§5.2） |

---

## 7. 分期建议

| 期 | 内容 | 验收标准 |
|---|---|---|
| **P0 打通** | `data/subsonic/` 协议层：账号存储（多账号 + Keystore）、`SubsonicClient`（OkHttp + JSON）、`ping` 连接测试 UI | 真机连自己的 Navidrome，填错密码能看到人话错误；填对能看到服务器名和版本 |
| **P1 能听** | `stream.view?format=raw` + `SubsonicRoutingDataSourceFactory` 接入 `PlaybackService`；封面本地磁盘缓存 | 从「专辑 → 歌曲」点进去能播，能拖动，海报墙和播放页封面正常 |
| **P2 能浏览** | 歌手/专辑/风格/歌单四层 UI；Room v13 缓存表 + 分页；`search3` | 上万首的库首屏 1 秒内；搜索可用 |
| **P3 能管** | 收藏（`star`/`unstar`）、评分（`setRating`）、歌单增删 + 绑定平台歌单、歌词（`getLyricsBySongId` + 纯文本降级） | 编辑后刷新服务器上能看到变化 |
| **P4 锦上添花** | 单曲下载、`scrobble` 上报、播放上报 `reportPlayback`、播放队列保存/恢复（`savePlayQueue` / `savePlayQueueByIndex`）、`getInternetRadioStations` | — |

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

1. **最小闭环**：用自己的 Navidrome 实例跑通
   `curl 'https://host/rest/ping.view?u=x&t=<md5(pw+salt)>&s=<salt>&v=1.16.1&c=myMusic&f=json'`，
   确认 `status=ok` 和 `type`。这一步不做，后面全是猜。
2. **Range 实测**：同一首歌分别 `format=raw` 和不带 format，用 `curl -r 1000-2000` 各打一次，
   看是不是 206。这决定 §4 的结论在你的服务器上成立。
3. **Airsonic-Advanced 兼容**：如果身边有实例，确认 `f=json` 与 `getAlbumList2` 的实际返回
   （Airsonic 的 JSON 是 JAXB 序列化出来的，字段名可能和 Navidrome 有细微差别）。
4. **自签证书**：确认 `PinnedTrustManager` 复用到 Subsonic 的 OkHttpClient 上无需改动
   （`data/webdav/WebDavCertificate.kt:122` 的 `manager` 已经是独立于 WebDAV 的）。
5. **golden vector 单测**：把 §2.3 / §3.3 / §5.4 里那份快照 JSON 抄成测试资源，
   解析层按它逐字段自测。这和本仓库处理签名类问题的思路一致：**缺的是算法契约，不是流量样本**。

---

## 附：本仓库相关文件索引

| 关注点 | 文件 |
|---|---|
| 歌曲/歌单模型 | `data/Models.kt` |
| Provider 抽象（对照，不复用） | `data/native/NativeProvider.kt` |
| 账号存储范本 | `data/webdav/WebDavAccount.kt`、`data/sync/SecretStore.kt` |
| 自签证书指纹钉住范本 | `data/webdav/WebDavCertificate.kt` |
| 路由 DataSource 范本 | `data/webdav/WebDavDataSource.kt`、`data/native/MHubTls.kt` |
| 播放器工厂链（插入点） | `playback/PlaybackService.kt:89` |
| Room 版本与迁移 | `data/AppStore.kt:1211` |
| 内网权限 | `data/sync/LocalNetworkAccess.kt` |
| 明文流量配置 | `app/src/main/res/xml/network_security_config.xml` |
| 绑定平台歌单 | `data/BoundPlaylists.kt` |
| 歌词管线 | `ui/LyricPipeline.kt` |
