# myMusic 本地音乐 · 云盘/WebDAV · 跨设备同步 技术说明

> 日期：2026-11-09
> 用途：给其他模型/开发者做方案设计参考。本文只描述事实、接口、数据模型与取舍，不代替实现代码。
> 工程：`C:\PythonProject\myMusic`，Android/Kotlin，minSdk 23，targetSdk 37，Media3 1.11.1，OkHttp 5.5.0，Room 2.8.5，DataStore 1.2.1。

---

## 0. 结论先行

| 方案 | 列表 | 上传 | 播放 | 封面/歌词 | 跨设备 | 推荐度 |
|---|---|---|---|---|---|---|
| 网易云音乐云盘 | 已验证 | 有完整开源闭环 | 已匹配走现有取流；未匹配需云盘专用地址（待真机验证） | 匹配后直接复用现有接口 | 天然成立 | 首选 |
| QQ 音乐云盘 | 已验证（源码） | 只有微云协议与未公开的 `UploadAudio`，无闭环证据 | 仅已匹配曲目可播；原文件（matched=2）不能按普通路由播 | 匹配后可用 | 读取成立，上传存疑 | 先做只读 |
| WebDAV | 标准协议 | 标准 PUT | Media3 可直接流式播放 | 与存储无关，单独做匹配 | 依赖同一 WebDAV | 兜底/自托管 |
| 纯本地 | 本地扫描 | 不需要 | 本地文件直放 | 指纹/元数据匹配 | 只同步元数据，文件不跟随 | 第一阶段 |

核心判断：

1. **匹配封面/歌词不依赖云盘。** 现有音频指纹识别链路已经可用，本地文件扫描后就能匹配。
2. **跨设备同步的关键不是把音频塞进 Gist，而是让同步文件里存一个“可解析的引用”。** 云盘歌曲 ID / WebDAV 路径 + 本地内容 hash，快照体积几乎不变。
3. **不要用云端 ID 替换本地曲目身份。** 本地 hash 是主身份，云端引用是附加信息；这样云端删除、换号、超容量时仍能退回“仅本机可用”，不会把歌单弄坏。
4. **QQ 云盘上传不建议作为第一阶段目标。** 它有明确的方法名，但参数没有公开闭环，且网页版已无云盘入口。

---

## 1. 现有架构（设计时必须对齐的部分）

### 1.1 歌曲模型

`Song`（`app/src/main/java/com/mytv/mymusic/data/Models.kt:35`）：

```kotlin
data class Song(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val duration: Int = 0,      // 秒
    val size: Long = 0,
    val bitrate: Int = 0,
    val source: String = "",    // provider id，如 netease / qq / kugou / local
    val url: String = "",
    val ext: String = "",
    val cover: String = "",
    val link: String = "",
    val extra: Map<String, String> = emptyMap(),
    val isInvalid: Boolean = false,
    val isVip: Boolean = false,
) {
    val key get() = "$source:$id"
}
```

`key` 是全局身份字符串，歌单、收藏、下载、同步、播放缓存都以它为主键。本地音乐最自然的身份是 `local:<contentHash>`。

### 1.2 Provider 抽象

`NativeProvider`（`data/native/NativeProvider.kt:37`）定义搜索、详情、取流、歌词、歌单、登录等能力；`playableStream` 返回 `PlayableStream(url, ext, bitrate, level, preview, extra)`。

调用入口：

```
MusicRepository.playableStream(song, cookie, workDir)
  -> NativeProviders.get(song.source).playableStream(cookie, song, workDir)
```

（`data/MusicRepository.kt:31`）

这意味着：只要本地音乐有一个能返回 `file:` / `content:` 地址的 provider，或绕过 provider 直接构造 `Song.url`，现有播放器就能放。

### 1.3 已有的本地文件播放路径

`MusicViewModel.localFileFor(track)`（`ui/MusicViewModel.kt:3162`）已经实现了“下载完成的文件优先于网络源”：

```kotlin
private fun localFileFor(track: CanonicalTrack): Pair<TrackSource, File>? {
    val records = downloads.value
    for (source in track.sources) {
        val record = records.firstOrNull { it.key == source.song.key && it.state == DOWNLOAD_DONE } ?: continue
        val file = File(record.path)
        if (file.exists() && file.length() > 0) return source to file
    }
    return null
}
```

调用点：
- `playTrack` / `resolveQueueItem` 先查本地文件，命中就直接 `song.copy(url = Uri.fromFile(file).toString())`（`MusicViewModel.kt:2519`、`3460`）
- `preferredSong` 也优先返回本地文件地址（`3148`）

**本地音乐扫描应复用这条路径的思路**：新增一个 `LocalFileStore`，按 `local:<hash>` 查到 `content://` 或文件路径，在 `localFileFor` 之前或之中返回。不要为本地文件伪装成某个平台 provider。

### 1.4 聚合与换源模型

`CanonicalTrack`（`aggregate/Models.kt:28`）：

```kotlin
data class CanonicalTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationSec: Int,
    val version: VersionKind,
    val cover: String,
    val sources: List<TrackSource>,
    val alternatives: List<TrackSource> = emptyList(),
    val confidence: Float = 0f,
)
```

`TrackSource(providerId, providerName, song, matchScore, matchMethod, availability, isPreview, ...)`。

本地曲目可以作为 `TrackSource(providerId = "local", song = Song(source="local", id=hash, url=contentUri))` 参与排序；但它不应该参与“平台换源”，因为换源会把它换成网络源，反而丢掉本地文件。建议在 `SourceResolver.orderCandidates` 之前单独处理本地源，优先级固定最高。

### 1.5 同步模型

`SyncSnapshot`（`data/sync/SyncModels.kt:18`），当前 `SCHEMA_VERSION = 2`：

```json
{
  "schema": "mymusic.library",
  "version": 2,
  "deviceId": "...",
  "updatedAt": "2026-...",
  "playlists": [
    {
      "id": "...",
      "name": "...",
      "createdAt": "...",
      "updatedAt": "...",
      "deletedAt": null,
      "tracks": [
        {
          "key": "netease:1901371647",
          "provider": "netease",
          "id": "1901371647",
          "title": "...",
          "artist": "...",
          "album": "...",
          "durationSec": 240,
          "cover": "https://..."
        }
      ]
    }
  ],
  "favorites": [
    { "key": "...", "addedAt": "...", "deletedAt": null, "track": { ... } }
  ],
  "cookies": [
    { "provider": "netease", "value": "...", "updatedAt": "...", "deletedAt": null }
  ]
}
```

关键实现：

- `PlaylistSyncProvider`（`data/sync/PlaylistSyncProvider.kt:35`）是远端存储接口：`validate/read/create/update`。现有实现 `GistSyncProvider`、`GiteeSyncProvider`，文件名固定 `mymusic-library.json`。
- `SyncCoordinator`（`data/sync/SyncCoordinator.kt`）负责合并、推送、拉取；`applySnapshot` 在 975 行附近。
- 合并粒度是**整个歌单**，不是单曲；冲突时按 `updatedAt` 和墓碑处理。
- 同步内容只包含自建歌单、收藏、平台 Cookie；最近播放、下载、播放进度、播放地址不上传（README 已写明）。
- Cookie 以明文写入私密 Gist / Gitee 代码片段。新增 WebDAV 密码**不应**进入同步文件。

### 1.6 设置与密钥

- `SettingsStore`（`data/AppStore.kt:156`）用 DataStore Preferences；`cookies` 是一个 JSON `Map<providerId, cookie>`（`cookiesKey`，167 行），`cookie_updated_at` 记录更新时间。
- `SecretStore`（`data/sync/SecretStore.kt`）用于加密保存同步令牌，令牌不进数据库/日志。
- WebDAV 账号密码应放 `SecretStore` 或独立的加密存储，按设备保存。

### 1.7 识别链路（可直接复用于本地匹配）

- `NeteaseFingerprinter`（`recognize/NeteaseFingerprinter.kt`）：ncm-afp WASM 跑在本地 WebView，输入 **3 秒、8kHz、单声道、Float32 PCM**，输出指纹字符串。
- `NetEaseProvider.matchAudio(fingerprint)`（`data/native/NetEaseProvider.kt:92`）调用 `https://interface.music.163.com/api/music/audio/match`，表单字段：
  `sessionId, algorithmCode=shazam_v2, duration=3, rawdata=<fingerprint>, times=2, decrypt=1`
- `AudioResampler`（`recognize/AudioResampler.kt`）负责把任意音频转成指纹输入格式。
- 现有 `MusicViewModel` 已把网易 + 酷狗两路识别结果合并（`RecognitionMerger`），本地扫描可以直接复用这条链路。

---

## 2. 目标与硬约束

目标：

1. 扫描本机音乐（MediaStore + SAF 目录）。
2. 为无标签/缺标签文件匹配封面、歌词、曲目元数据。
3. 把选中的本地曲目上传到用户自己的云盘（网易/QQ）或 WebDAV。
4. 自建歌单/收藏跨设备同步，另一台设备能直接播放。
5. 云端不可用时优雅降级为“仅本机可用”。

硬约束：

- 音频字节、歌词正文、WebDAV 密码**不得**进入 Gist/Gitee 快照。
- 本地曲目身份不能依赖云盘 ID。
- 上传必须串行、可恢复、有容量预检；不能并发轰炸平台接口。
- minSdk 23，必须同时处理旧存储权限、分区存储、SAF。
- 第三方协议/代码的细节写入 `THIRD_PARTY_NOTICES`，README 只保留一行入口说明。

---

## 3. 曲目身份模型（核心设计）

### 3.1 本地 ID

```
localId = "sha256:" + hex( SHA-256( 前 64KB || 末 64KB || uint64(文件大小) ) )
Song.key = "local:" + localId
```

- 只读首尾各 64KB，避免扫描上万首时把整盘读一遍。
- 文件大小参与，降低同名前奏碰撞。
- 上传前再计算整文件 MD5（网易要求）或 SHA-1（微云要求），结果单独存，不当作身份。
- `Song.source = "local"`，`Song.id = localId`，`Song.url = ""`；播放时由本地解析器补 `content://` 地址。

不建议用路径作 ID：SAF、重命名、跨设备路径都会变。也不建议用纯文件大小 + 文件名：重名率高。

### 3.2 云端引用

上传成功后记录：

```json
{
  "remote": {
    "provider": "netease_cloud",   // netease_cloud | qq_cloud | webdav
    "id": "云盘条目 ID（songId 或 simpleSong.id，以实测为准）/ 微云 file_id / WebDAV 相对路径",
    "playableId": "匹配到的曲库 songId / songmid；WebDAV 为空",
    "fileName": "song.flac",
    "fileSize": 32123456,
    "status": "ready",             // queued | uploading | transcoding | ready | failed
    "uploadedAt": "2026-..."
  }
}
```

- 网易：云盘条目同时有 `songId` 和 `simpleSong.id`。开源实现（ncmExtend）在播放、匹配、删除时都传 `simpleSong.id`，所以第一版以 `simpleSong.id` 作为云端条目的解析 ID；`songId` 保留在本地记录里用于 `byids` 查详情。两者是否恒等、匹配后 `simpleSong.id` 是否变成曲库 ID，必须真机确认。`playableId` 只在确认“曲库 ID 与云盘条目 ID 不同”时才有意义，确认前可以为空。
- QQ：`id` 用微云 `file_id`（APK 内部名 `weiYunFileId`）；`playableId` 用 `song_info.mid`，未匹配时为空。
- WebDAV：`id` 是相对路径，`playableId` 为空。

### 3.3 匹配结果

```json
{
  "match": {
    "provider": "netease",      // 提供封面/歌词的平台
    "id": "1901371647",
    "confidence": 0.96,
    "method": "audio",          // tag | audio | search
    "matchedAt": "2026-..."
  }
}
```

只同步匹配结果，不同步歌词正文。另一台设备按 `provider + id` 现取歌词，避免快照膨胀和版权文本扩散。

### 3.4 本机可用性判定

播放前按顺序解析：

```
1. 本机存在同一 localHash 的文件        -> LOCAL
2. remote.provider 可访问且已就绪       -> CLOUD / WEBDAV
3. match.provider 能取到流               -> PLATFORM（可选）
4. 都没有                                -> NONE，UI 标记“仅本机可用”
```

`NONE` 不能把歌单条目删掉，也不能阻塞同步。这一点必须写进合并逻辑的测试。

---

## 4. 本地扫描

### 4.1 权限矩阵

| Android | 权限 | 说明 |
|---|---|---|
| 23–28 | `READ_EXTERNAL_STORAGE` | 运行时权限 |
| 29–32 | `READ_EXTERNAL_STORAGE` | 分区存储下仍可读 MediaStore |
| 33+ | `READ_MEDIA_AUDIO` | 音频专用权限；不要申请 `READ_MEDIA_VISUAL_USER_SELECTED` |
| 任意 | SAF `ACTION_OPEN_DOCUMENT_TREE` | 读取 MediaStore 之外的目录；`takePersistableUriPermission` 持久化 |

注意：按当前工程规则，权限相关改动要走 `android-intent-security` / `android-permissions-security` 的审查；明文流量已经迁到 network security config，新增域名不要回退到 cleartext。

### 4.2 MediaStore 查询

`MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`，建议列：

```
_ID, DISPLAY_NAME, TITLE, ARTIST, ALBUM, ALBUM_ID, DURATION,
SIZE, DATE_ADDED, DATE_MODIFIED, MIME_TYPE, RELATIVE_PATH (29+), IS_MUSIC
```

过滤：`IS_MUSIC != 0` 且 `DURATION > 30000`（可选），排除 `Ringtones/Notifications/Alarms` 目录。

内容 URI：`ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)`。

### 4.3 SAF 目录

- `ACTION_OPEN_DOCUMENT_TREE` 选择目录，`takePersistableUriPermission(..., READ)`。
- 用 `DocumentFile` 递归，或 `DocumentsContract.buildChildDocumentsUriUsingTree` 批量查。
- 记录 `treeUri + documentId`，下次增量扫描按 `lastModified`。

### 4.4 标签与内嵌封面

- `MediaMetadataRetriever.setDataSource(context, uri)`：
  - `METADATA_KEY_TITLE / ARTIST / ALBUM / DURATION / BITRATE / MIMETYPE`
  - `embeddedPicture` 拿内嵌封面字节，写缓存文件，`Song.cover` 用 `file:` 或自定义 image loader。
- MediaStore 的 `ALBUM_ID` 拼 `content://media/external/audio/albumart/<id>` 在新系统上不可靠，优先内嵌图，其次平台匹配结果。
- 不要在扫描阶段解析整首歌；标签读取按需、可取消。

### 4.5 增量扫描

本地表建议（Room 新表，避免动现有 `saved_songs`）：

```kotlin
@Entity(tableName = "local_tracks")
data class LocalTrackEntity(
    @PrimaryKey val localId: String,     // sha256:...
    val uri: String,                     // content:// 或 file://
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val size: Long,
    val mimeType: String,
    val modifiedAt: Long,
    val addedAt: Long,
    val coverPath: String,               // 内嵌封面缓存
    val matchProvider: String,
    val matchId: String,
    val matchConfidence: Float,
    val matchMethod: String,
    val remoteProvider: String,
    val remoteId: String,
    val remotePlayableId: String,
    val remoteStatus: String,
    val remoteUpdatedAt: Long,
)
```

扫描流程：

```
查询 MediaStore / SAF
  -> 对每个条目算 localId（首尾 hash）
  -> 已存在且 size/modifiedAt 未变：跳过
  -> 新增/变化：读标签、写 local_tracks
  -> 标记消失的条目（不要立即删，先标记 missing，用户确认后清理）
```

### 4.6 本地播放

- `MediaItem.Builder().setUri(Uri.parse(localTrack.uri))`；ExoPlayer 默认 DataSource 支持 `content://` 和 `file://`。
- 播放时需要 `READ_MEDIA_AUDIO`（33+）；SAF 的 `content://com.android.externalstorage.documents/...` 不需要额外权限。
- 本地文件也要参与 MediaSession 元数据、封面、歌词显示；歌词按 `match.provider + id` 取。

---

## 5. 封面/歌词匹配

### 5.1 决策顺序

```
1. 标签完整（title + artist 非空）
   -> 平台搜索（现有 NetEase/QQ 搜索接口）
   -> TrackMatcher 打分（标题、歌手、时长、版本关键词）
   -> 分数 >= 0.85 直接采纳
2. 标签缺失或分数低
   -> AudioResampler 取 3 秒 8kHz 单声道 PCM
   -> NeteaseFingerprinter 出指纹
   -> NetEaseProvider.matchAudio
   -> 结果与酷狗识别结果合并（可选）
3. 仍失败
   -> 保持“未匹配”，UI 提供手动搜索/重新匹配
```

### 5.2 复用点

- `NeteaseFingerprinter`：本地 WASM，不联网。
- `NetEaseProvider.matchAudio`：匿名可用，不需要登录 Cookie。
- `TrackMatcher` / `TrackNormalizer`：现成的标题清洗、版本识别、打分逻辑，直接复用，避免再造一套。
- 歌词：匹配到后调用现有 `lyrics(cookie, song)`（`weapi/song/lyric`），带翻译/罗马音；云盘自定义歌词走 `/api/cloud/lyric/get`。
- 封面：`simpleSong.al.picUrl`；QQ 用 `album.mid` 拼 `https://y.gtimg.cn/music/photo_new/T002R300x300M000{mid}.jpg`。

### 5.3 缓存与写回

- 匹配结果写 `local_tracks` + 同步快照（只写 `provider/id/confidence/method`）。
- 歌词正文缓存到本地文件或 Room，**不进同步**。
- 可选：把标题/歌手/专辑/封面写回文件标签。Android 没有系统级标签写入 API，需要引入 tag 库；建议第一版只做应用内覆盖（`track_overrides` 表已有类似机制），不改原文件。

---

## 6. 网易云音乐云盘

### 6.1 鉴权

- 登录 Cookie：必须含 `MUSIC_U`；`__csrf` 填入 weapi body 的 `csrf_token`。
- 请求头：`Referer: https://music.163.com/`，桌面 UA。
- 加密：网页版 `weapi`（AES + RSA），工程里已有 `weapiPost`（`data/native/NetEaseProvider.kt:669`），直接用。
- 不需要 app 端 `a_bogus`、`anonymous_token`、`device_id`。
- 匿名或环境异常时，上传类接口返回 `code: -460`（“检测到您的网络环境存在风险”）。

### 6.2 接口清单

路径写法按 NCMApi 的 `/api/...`，实际请求替换为 `/weapi/...`（`url.replace('api','weapi')`）。

| 功能 | 方法与路径 | 关键请求 | 关键响应 | 证据 |
|---|---|---|---|---|
| 云盘列表 + 容量 | `POST /api/v1/cloud/get` | `limit, offset` | `count, size, maxSize, hasMore, data[]` | A |
| 单曲详情 | `POST /api/v1/cloud/get/byids` | `songIds[]` | `data[]` | A |
| 上传查重 | `POST /api/cloud/upload/check` | `songId:"0", md5, length, ext, version:1, bitrate:999000` | `songId, needUpload` | A |
| 取 NOS token | `POST /api/nos/token/alloc` | `filename, length, ext, type:"audio", bucket:"jd-musicrep-privatecloud-audio-public", local:false, nos_product:3, md5` | `result{resourceId, objectKey, token}` | A |
| NOS 直传 | `GET https://wanproxy.127.net/lbs?version=1.0&bucketname=jd-musicrep-privatecloud-audio-public` 后 `POST {uploadHost}/{bucket}/{urlencode(objectKey)}?offset=0&complete=true&version=1.0` | 头：`x-nos-token`, `Content-MD5`（十六进制 md5）, `Content-Type`, `Content-Length`；body 原始字节 | `offset, context`（分片续传） | A |
| 提交入库 | `POST /api/upload/cloud/info/v2` | `md5, songid(=check.songId), filename, song, album, artist, bitrate, resourceId` | `songId` | A |
| 转码状态 | `POST /api/v1/cloud/music/status` | `songIds[]` | `statuses[id].status`（`9`=就绪，`1`=处理中）、`waitTime` | A |
| 发布 | `POST /api/cloud/pub/v2` | `songid` | `code`（200/201） | A |
| 匹配曲库 | `POST /api/cloud/user/song/match` | `userId`；`songId` 传云盘条目 ID（开源实现传 simpleSong.id）；`adjustSongId` 传目标曲库 ID，0=取消 | `code, matchData` | A（调用方式来自 ncmExtend；字段语义待真机确认） |
| 删除 | `POST /api/cloud/del` | `songIds[]`，云盘条目 ID（开源实现传 simpleSong.id） | `code:200` | A |
| 导入已有云文件 | `POST /api/cloud/upload/check/v2` -> `POST /api/cloud/user/song/import` | `uploadType:0, songs`（md5/songId/bitrate/fileSize；song/artist/album/fileName） | `data[0].upload`（0 可导入 / 1 已存在 / 2 不能导入） | A |
| 已匹配歌词 | `POST /api/song/lyric` | `id, lv:-1, kv:-1, tv:-1` | `lrc/tlyric/yrc` | A |
| 云盘自定义歌词 | `POST /api/cloud/lyric/get`（eapi） | `userId, songId, lv:-1, kv:-1` | 云盘歌词 | B |
| 已匹配取流 | 现有 `/weapi/song/enhance/player/url` | `ids` | `url, br, type` | A（现有代码） |
| 未匹配取流 | eapi `/api/cloud/dowonload`（上游拼写如此）或 `/api/cloud/mobile/drive/file/playUrl` | `songId` | 云盘文件地址 | C，需真机验证 |

云盘条目字段：

```
songId        云盘条目 ID（byids 查详情用；与 simpleSong.id 的关系待真机确认）
simpleSong.id 开源实现用来播放/匹配/删除的条目 ID
fileName      文件名
fileSize      字节
bitrate       码率
addTime       添加时间
matchType     matched / unmatched
songName / album / artist   未匹配时的文件名元数据
simpleSong {
  id, name, ar[], al{id, name, picUrl}, dt, privilege
}
```

判据：ncmExtend 用 `simpleSong.al.id > 0` 判断“已匹配曲库”，匹配状态另有 `matchType` 字段。`simpleSong.al.picUrl` 直接作为封面。

### 6.3 上传全链路

```
1. 读文件，算整文件 MD5（十六进制）
2. POST /api/cloud/upload/check
   - needUpload=false：跳过第 4–5 步（秒传），但第 3 步仍要做
   - 返回 songId 记下来
3. POST /api/nos/token/alloc
   - 无论是否秒传都要取 result.resourceId / objectKey / token
   - info/v2 需要 resourceId
4. GET https://wanproxy.127.net/lbs?...
   - 取 lbs.upload[0] 作为上传节点
5. POST {node}/{bucket}/{urlencode(objectKey)}?offset=0&complete=true&version=1.0
   - 头：x-nos-token、Content-MD5（十六进制）、Content-Type、Content-Length
   - 大文件分片：offset 递增，complete 只在最后一片为 true；服务端回 offset/context
6. POST /api/upload/cloud/info/v2
   - md5、songid、filename、song、album、artist、bitrate、resourceId
   - 返回 songId
7. 轮询 /api/v1/cloud/music/status
   - status=9 就绪；status=1 继续等；超时按失败处理
8. POST /api/cloud/pub/v2 {songid}
9. 可选：POST /api/cloud/user/song/match
   - userId 从 /weapi/nuser/account/get 的 profile.userId 取
   - songId=云盘条目 ID（开源实现传 simpleSong.id），adjustSongId=目标曲库 ID
```

注意：

- `objectKey` 里的 `/` 必须替换成 `%2F` 再拼 URL。
- `Content-MD5` 在 NCMApi 里传的是十六进制 md5；HTTP 规范通常要求 base64，若服务端 400 再改 base64。
- `info/v2` 可能先返回 `rep create failed`，不是最终失败，继续轮询状态。
- 入库是异步转码，不能提交完就当成功。
- 写接口超时不要盲目重试；先查 `byids` 或列表确认是否已经入库。

### 6.4 容量与限制

- 容量：以 `/api/v1/cloud/get` 返回的 `maxSize` / `size` 为准，不要写死 5GB/20GB。
- 单文件：社区反馈 >500MB 会被拒，未实测；客户端无大小校验。
- 格式：mp3/flac 有可运行实现；网页控件 `accept="audio/*"`，其余格式未验证。
- 风控：匿名/异常环境直接 `-460`；必须真实登录 Cookie、正常 UA、串行 + 限速。
- 私有接口无稳定性承诺。

### 6.5 建议实现

```
data/cloud/netease/NeteaseCloudApi.kt      // weapi 调用封装
data/cloud/netease/NeteaseCloudUploader.kt // 状态机 + 串行队列 + 断点
data/cloud/netease/NeteaseCloudModels.kt   // 列表/状态/匹配 DTO
```

上传状态机：

```
QUEUED -> HASHING -> CHECKING -> UPLOADING -> SUBMITTING -> TRANSCODING -> PUBLISHING -> READY
                                                                        \-> FAILED(retryable?)
```

`READY` 后写 `local_tracks.remote*` 和同步快照；`FAILED` 保留错误原因，UI 可重试。

---

## 7. QQ 音乐云盘

### 7.1 事实

QQ 音乐「我的云盘」底层是**腾讯微云**。曲目清单在 musicu 里，文件字节走微云上传网关，不经过 `u.y.qq.com`。

- 网页版 `y.qq.com` 路由表已经没有云盘入口；歌曲页的“本地上传”跳的是 `rc.qzone.qq.com/305/music_coll?pa=upload`（QZone 音乐收藏，绿钻独享）。
- 因此在网页上抓不到云盘上传请求，需要登录官方客户端或使用已知的微云协议。

### 7.2 接口清单

| 功能 | 接口 | 关键参数 | 关键响应 | 证据 |
|---|---|---|---|---|
| 云盘列表 | `POST https://u.y.qq.com/cgi-bin/musicu.fcg`，module `music.WeiyunMusic.WeiyunMusicRead`，method `GetMatchAudio` | `comm{ct:24, cv:4747474, format:"json", platform:"yqq.json", needNewCode:1, uin}`；`param{uin, file_id:"", page_no(1 起), page_size:2000}` | `data.info_list[], total, has_more` | A（方法存在 + 两份源码） |
| 删除 | 同域名，module `music.WeiyunMusic.WeiyunMusicWrite`，method `DeleteAudio` | 推测 `file_id`（APK 内部名 `weiYunFileId`） | 未知 | B（方法存在，参数未实证） |
| 上传注册 | 同域名，module `...WeiyunMusicWrite`，method `UploadAudio` | 未公开 | 未知 | D（只有方法名） |
| 歌词 | 同域名，module `music.musichallSong.PlayLyricInfo`，method `GetPlayLyricInfo` | `songMID=song_info.mid`, `songID=song_info.id`, `songName/albumName/singerName`（base64）, `qrc/trans/roma=1, crypt=1` | `lyric/qrc/trans` | B（常规曲目可用；云盘歌未实测） |
| 封面 | 无匹配接口 | `song_info.album.mid` -> `https://y.gtimg.cn/music/photo_new/T002R300x300M000{mid}.jpg` | 图片 | A |
| 容量 | `https://www.weiyun.com/webapp/json/weiyunQdiskClient/DiskUserInfoGet?g_tk=<wyctoken>&cmd=2201` | `req_header{cmd, appid:30013, version:3}` + `req_body` | `used_space / total_space` | C（微云协议，音乐侧共用配额未证） |

列表条目字段：

```
filename     base64 编码的 UTF-8 文件名
timestamp    秒（<1e11 按秒处理）
matched      1 = 已匹配曲库；2 = 原文件
song_info {
  id, mid, name, singer[], album{mid, pmid, name}
}
```

### 7.3 播放

- 已匹配（`matched=1`）且有 `song_info.mid`：走现有 vkey 路径（`music.vkey.GetVkey / UrlGetVkey`），和普通曲目一样。
- 未匹配（`matched=2`）：没有普通曲目的 `mid`，源码明确标注“不能按普通路由播”。QQ 音乐客户端能播，但第三方没有公开的云盘原文件播放接口。
- 结论：QQ 云盘只适合“已匹配曲目”的跨设备播放；原文件需要另找播放地址接口，目前没有证据。

### 7.4 上传（微云协议）

微云侧需要 Cookie：`p_skey` 或 `skey`（`wy_uf=0` 为 QQ 登录），`g_tk` 用 `wyctoken` 计算。音乐侧需要 `uin` + `qm_keyst`（web cookie）或 Android comm 的 `authst`。

```
1. PreUpload  cmd=247120, appid=30013
   参数：
     common_upload_req {
       ppdir_key, pdir_key, file_size, filename,
       file_exist_option: 1, use_mutil_channel: true
     }
     upload_scr: 0
     channel_count: 4
     block_size: 1048576
     check_sha: 尾部检查点之前的累计 SHA1
     check_data: 最后 128 字节的 base64
     block_info_list[] {
       sha, offset, size
     }
   返回：upload_key, ex, channel_count, channel[]
2. UploadPiece cmd=247121
   参数：upload_key, ex, channel{id, offset, len} + 二进制分片
   返回：channel.len, upload_state
3. AddChannel cmd=247122
   参数：upload_key, ex, orig_channel_count, dest_channel_count, speed
   用于动态扩通道
4. 全部 upload_state=1 后，服务端合并，得到微云 file_id
5. musicu UploadAudio 把 file_id 注册成“云盘歌曲”
   —— 参数未知，这是当前最大缺口
6. 之后服务端自动匹配，matched 由服务端给，没有手动匹配/纠错接口
```

`block_info_list` 的细节（来自 OpenList 实现）：

- 每 1MB 一块；
- 每块的 `sha` 是**累计** SHA1 状态（不是单块 hash）；
- 最后一块的 `sha` 是全文件 SHA1；
- `check_sha` 是最后一块去掉末尾 128 字节后的累计 SHA1；
- `check_data` 是最后 128 字节的 base64。

### 7.5 结论

- 列表、删除、已匹配播放、歌词、封面：可以做。
- 上传：协议复杂 + 关键参数缺失 + 网页无入口，不建议第一阶段投入。
- 如果一定要做，先抓官方客户端 `UploadAudio` 的请求（需要真机 + 抓包），确认参数后再实现。
- 备选：酷狗也有云盘 `add_files` 接口（`musiccloud.kugou.com/api/cloud/add_files`），但同样需要协议验证，不在本次范围。

---

## 8. WebDAV

### 8.1 配置

```
baseUrl   https://dav.example.com/music
username
password
rootPath  myMusic/
```

- 只存本机（`SecretStore` 或加密偏好），**不进同步快照**。
- 必须 HTTPS；自签名证书需要显式开关，不能默认信任。
- 建议支持只读模式和读写模式；只读模式下不显示“上传”。

### 8.2 协议

| 操作 | 方法 | 说明 |
|---|---|---|
| 列目录 | `PROPFIND` + `Depth: 1` | body 请求 `getcontentlength/getetag/getcontenttype/resourcetype` |
| 建目录 | `MKCOL` | 逐级创建 |
| 上传 | `PUT` | 带 `Content-Length`；大文件单次 PUT，部分服务器限制 body 大小 |
| 下载/流播 | `GET` + `Range` | Media3 直接流式播放 |
| 存在性 | `HEAD` | 看 `Content-Length` / `ETag` |
| 重命名 | `MOVE` + `Destination` | 可选 |
| 删除 | `DELETE` | 可选 |

认证：优先 Basic over TLS；部分服务器只支持 Digest，需要 OkHttp `Authenticator` 或手动实现。401 时不要无限重试。

### 8.3 路径与身份

```
WebDAV 相对路径 = myMusic/<localId 前两位>/<localId>.<ext>
```

- 用 localId 分桶，避免单目录文件过多。
- 同步快照里存相对路径 + localId + 文件大小；不存账号密码。
- 另一台设备配置同一个 WebDAV 后，按路径直接取流；路径不存在则视为“未上传”。

### 8.4 播放

Media3 需要带认证头：

```kotlin
val factory = DefaultHttpDataSource.Factory()
    .setDefaultRequestProperties(mapOf("Authorization" to Credentials.basic(user, pass)))
    .setAllowCrossProtocolRedirects(true)
val mediaSourceFactory = DefaultMediaSourceFactory(factory)
```

- `Range` 支持由 WebDAV 服务器决定；不支持 Range 的服务器会退化为整文件下载后播放。
- 连接超时、读取超时单独配置；不要复用同步用的 20/40 秒超时。
- 播放地址是短时拼接的，不要写进同步快照。

### 8.5 上传

- 串行上传，单文件单 PUT；失败按 `Content-Length`/`ETag` 判断是否需要重传。
- 不支持断点续传是 WebDAV 的常态；大文件失败就整文件重传，UI 要能取消。
- `PUT` 前先 `HEAD`，`ETag` 相同的文件跳过。
- 上传完成后写 `local_tracks.remote*` 和快照。

---

## 9. 同步设计（v3）

### 9.1 Schema 变更

`SCHEMA_VERSION` 从 2 升到 3。`SyncTrack` 增加可选字段：

```kotlin
@Serializable
data class SyncTrack(
    val key: String,
    val provider: String = "",
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val cover: String = "",
    // v3 新增
    val localHash: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val remote: SyncRemote? = null,
    val match: SyncMatch? = null,
)

@Serializable
data class SyncRemote(
    val provider: String,   // netease_cloud | qq_cloud | webdav
    val id: String,
    val playableId: String = "",
    val status: String = "ready",
    val uploadedAt: String = "",
)

@Serializable
data class SyncMatch(
    val provider: String,
    val id: String,
    val confidence: Float = 0f,
    val method: String = "",
    val matchedAt: String = "",
)
```

兼容性：

- 新客户端读 v2：缺省字段为空，行为不变。
- 旧客户端读 v3：`SyncJson.decode` 会因 `version > SCHEMA_VERSION` 报“请先更新应用”。这是预期行为，避免旧客户端写入时丢掉云端引用。
- `ignoreUnknownKeys = true` 已经开启，新增字段对旧解析器安全；版本检查是额外保护。

### 9.2 快照示例

```json
{
  "schema": "mymusic.library",
  "version": 3,
  "deviceId": "phone-a",
  "updatedAt": "2026-11-09T12:00:00Z",
  "playlists": [
    {
      "id": "pl-1",
      "name": "本地收藏",
      "createdAt": "2026-11-01T00:00:00Z",
      "updatedAt": "2026-11-09T12:00:00Z",
      "deletedAt": null,
      "tracks": [
        {
          "key": "local:sha256:9f2c...",
          "provider": "local",
          "id": "sha256:9f2c...",
          "title": "Example",
          "artist": "Artist",
          "album": "Album",
          "durationSec": 240,
          "cover": "https://p1.music.126.net/...",
          "localHash": "sha256:9f2c...",
          "fileName": "example.flac",
          "fileSize": 32123456,
          "remote": {
            "provider": "netease_cloud",
            "id": "1234567890",
            "playableId": "1901371647",
            "status": "ready",
            "uploadedAt": "2026-11-09T11:59:00Z"
          },
          "match": {
            "provider": "netease",
            "id": "1901371647",
            "confidence": 0.96,
            "method": "audio",
            "matchedAt": "2026-11-09T11:58:00Z"
          }
        }
      ]
    }
  ],
  "favorites": [],
  "cookies": []
}
```

### 9.3 合并规则

- 现有合并粒度是歌单：按 `playlist.updatedAt` 和 `deletedAt` 做 last-write-wins。维持不变，避免重写整套同步。
- 单曲层面只做“引用合并”：
  - 同 `key` 的两条 track：字段取并集；`remote` 以 `uploadedAt` 较新者为准；`match` 以 `confidence` 高者为准。
  - 本地文件不存在不改变 track 本身，只改变本机可用性。
- 删除本地文件：不写墓碑（本地库是设备私有的），只清 `local_tracks`；歌单里的 `local:` 条目保留，显示“仅本机可用/文件缺失”。
- 取消收藏/移出歌单：沿用现有墓碑机制。
- 云盘删除：把 `remote` 置空或 `status="deleted"`，不要删除 track。

### 9.4 体积预算

- 同步只包含“用户显式加入歌单/收藏”的曲目，不包含整个本地库。这样 1 万首以内快照通常在几百 KB 量级。
- 每条本地 track 新增约 150–250 字节；1 万条约 2MB。Gitee 代码片段/Gist 对大文件的限制需要实测（见第 14 节），建议：
  - 单文件超过 1MB 时提示用户“同步内容过大”；
  - 或把 `local` track 的 `title/artist/album/cover` 裁剪成短字段；
  - 不要把歌词、base64 封面、音频写进快照。
- 如果未来要同步完整本地库，应该单独用一个“本地库清单”文件或云盘列表接口，而不是塞进现有快照。

### 9.5 WebDAV 与同步的关系

两种用法都允许：

1. **WebDAV 只作为音频存储**：快照仍走 Gist/Gitee，`remote.provider="webdav"`，另一台设备配置同一个 WebDAV 即可播放。
2. **WebDAV 作为唯一同步介质**：把 `mymusic-library.json` 也放到 WebDAV，实现 `PlaylistSyncProvider` 的第三个实现。这个改动小，且不需要 Gist/Gitee 令牌，适合自托管用户。

建议先做 (1)，(2) 作为可选扩展。

---

## 10. 播放解析与降级

### 10.1 解析顺序

```
localFileFor(track)                      // 现有：下载完成 / 本地扫描命中
  -> remote 引用（netease_cloud / qq_cloud / webdav）
  -> 平台源（匹配到的 provider:id）
  -> 其他平台换源（现有 SourceResolver）
  -> NONE
```

### 10.2 云端引用解析

- 网易：
  - `remote.playableId` 非空时优先用它走现有 `resolveStream`（曲库 songId）。
  - 否则用 `remote.id`（云盘条目 ID）先试现有取流，再试 eapi 云盘地址接口；都失败则降级为 `NONE`。这条路径必须在真机上确认未匹配文件到底走哪个接口。
- QQ：
  - 已匹配：用 `remote.playableId`（songmid）走现有 vkey。
  - 未匹配：`NONE`，UI 标记“QQ 云盘原文件暂不支持播放”。
- WebDAV：拼 `baseUrl + remote.id`，带 Basic 头播放。

### 10.3 失败与降级

- 云端 401/403：提示重新登录对应平台，不清除本地引用。
- 云端 404：`remote.status="missing"`，UI 显示“云端文件已删除”，保留 track。
- 网络错误：短退避重试；不要把 `NONE` 写进同步快照。
- 试听片段：沿用现有 `PlayableStream.preview` 逻辑，云盘歌曲一般不是试听，但要防止误判。

### 10.4 缓存

- 播放地址缓存沿用 `SourceResolver.urlCache`，TTL 现成。
- 本地文件路径不要缓存进 `Song` 持久化数据（换设备会失效）。

---

## 11. UI/交互状态（给设计参考）

本地音乐页：

- 入口：我的 -> 本地音乐。
- 顶部：扫描进度 / 歌曲数 / 已匹配数 / 已上传数。
- 列表项：标题、歌手、时长、来源标签（本机 / 网易云盘 / QQ 云盘 / WebDAV）、匹配状态。
- 批量操作：匹配封面歌词、上传到云盘、删除本地记录。
- 单曲操作：重新匹配、手动搜索、上传、删除云端、查看云端。

上传队列：

- 串行，显示当前文件、进度、剩余数量。
- 失败项可重试/跳过；容量不足时顶部提示，不开始排队。
- `-460` 时暂停队列并提示检查登录环境，不要连续重试。

同步状态：

- 歌单里 `local:` 条目在无本地文件、无云端引用时显示“仅本机可用”；
- 有云端引用但当前网络不可达时显示“云端暂不可用”，不要标成删除。

设置：

- 本地音乐：扫描范围（MediaStore / 指定目录）、是否自动匹配、是否自动上传。
- 云盘：网易/QQ 登录状态、容量、上传并发固定 1、限速。
- WebDAV：地址、账号、密码、测试连接、只读开关。

---

## 12. 失败处理、限速与容量

| 场景 | 处理 |
|---|---|
| 网易 `-460` | 停止队列，提示检查 Cookie/网络环境；指数退避，不要连续重试 |
| 网易 `info/v2` 返回 `rep create failed` | 继续轮询 status，不要立即判失败 |
| 网易状态长时间 `1` | 超时（如 10 分钟）后标记失败，允许手动重试 |
| 网易容量不足 | 上传前用 `size/maxSize` + 待传总大小预检；不足则阻止排队 |
| 网易 >500MB | 上传前按文件大小拦截，提示“云盘不支持大文件” |
| QQ 未匹配 | 不提供上传后的播放承诺，UI 明确说明 |
| WebDAV 401 | 提示重新输入密码，不清除已上传引用 |
| WebDAV 不支持 Range | 退化为整文件下载，提示流量消耗 |
| 本地文件被删除 | 标记 missing，保留歌单条目 |
| 同步文件过大 | 提示用户，不要静默截断 |

并发与限速建议：

- 网易上传：并发 1，请求间隔 300–500ms；失败重试最多 2 次，指数退避。
- 网易状态轮询：单次批量查，间隔 3–5 秒，最长 10 分钟。
- 扫描/哈希：IO 线程，低优先级，可取消；扫描时不要阻塞播放。
- 匹配：并发 2–3，失败不阻塞扫描。

---

## 13. 模块拆分与落地顺序

### 13.1 建议新增模块

```
data/local/
  LocalLibraryStore.kt       // Room 读写 local_tracks
  LocalScanner.kt            // MediaStore + SAF 扫描
  LocalHasher.kt             // 首尾 hash / 整文件 md5 / sha1
  LocalMetadata.kt           // MediaMetadataRetriever 标签与内嵌封面
  LocalPlaybackResolver.kt   // localId -> content:// / file://

data/match/
  LocalMatcher.kt            // 标签搜索 + 指纹识别 + 打分
  LocalMatchCache.kt         // 匹配结果与歌词缓存

data/cloud/netease/
  NeteaseCloudApi.kt
  NeteaseCloudUploader.kt
  NeteaseCloudModels.kt

data/cloud/qq/
  QqCloudApi.kt              // 只读列表/播放/歌词
  QqCloudUploader.kt         // 可选，待参数确认

data/webdav/
  WebDavClient.kt            // PROPFIND/MKCOL/PUT/HEAD/MOVE
  WebDavSource.kt            // Media3 DataSource + Basic Auth
  WebDavSettings.kt

ui/local/
  LocalMusicScreen.kt
  LocalUploadQueue.kt
  LocalMatchSheet.kt
```

### 13.2 顺序

1. **P1 本地库**：扫描、标签、hash、本地播放、列表 UI。不依赖任何云。
2. **P2 匹配**：指纹 + 搜索 + 封面/歌词；结果写 `local_tracks`；需要覆盖匹配关系时复用现有 `track_overrides` 机制。
3. **P3 同步 v3**：`SyncTrack` 扩展、版本升级、合并规则、UI 可用性标记。
4. **P4 网易云盘**：上传队列、列表、播放、删除、匹配。
5. **P5 WebDAV**：存储 + 播放 +（可选）快照存放。
6. **P6 QQ 云盘只读**：列表 + 已匹配播放 + 歌词；上传等真机抓包后再评估。

### 13.3 粗估

| 阶段 | 工作量 | 风险 |
|---|---|---|
| P1 本地库 | 3–5 人日 | 权限/SAF 兼容、扫描性能 |
| P2 匹配 | 2–4 人日 | 无标签文件识别率 |
| P3 同步 v3 | 2–3 人日 | 与现有合并逻辑的兼容 |
| P4 网易云盘 | 5–8 人日 | 风控、异步转码、断点续传 |
| P5 WebDAV | 3–4 人日 | 服务器差异、Range/认证 |
| P6 QQ 只读 | 2–3 人日 | 云盘条目字段、播放限制 |

---

## 14. 需要真机/登录态验证的清单

在写实现前，以下事实必须用真实账号验证，本文标了证据等级但未在本项目登录态闭环：

1. 网易云盘列表：`/api/v1/cloud/get` 在真实 Cookie 下的 `data[]` 字段是否与源码一致，`simpleSong.al.id` 是否可靠表示已匹配，以及 `songId` 与 `simpleSong.id` 在“未匹配/已匹配”两种状态下分别等于什么。
2. 网易未匹配云盘歌曲的播放地址：`/api/cloud/dowonload` 与 `/api/cloud/mobile/drive/file/playUrl` 哪个可用、参数是否只有 `songId`。
3. 网易上传：`/api/nos/token/alloc` 的 `bucket` 是否必须为 `jd-musicrep-privatecloud-audio-public`；`Content-MD5` 十六进制还是 base64。
4. 网易 `info/v2` 的返回时序：`rep create failed` 后 status 是否能到 9。
5. 网易 `cloud/user/song/match` 的 `userId` 来源、`songId` 到底传云盘 `songId` 还是 `simpleSong.id`、`adjustSongId` 的语义。
6. QQ 云盘列表：真实账号下 `info_list` 的字段、`matched` 取值、`song_info` 结构。
7. QQ 已匹配云盘歌曲的 vkey 取流是否正常。
8. QQ 未匹配原文件是否真的无法播放（客户端行为 vs 第三方接口）。
9. QQ 微云 `PreUpload/UploadPiece` 在当前 Cookie 下是否能拿到 `upload_key`。
10. QQ `UploadAudio` 的参数（必须抓官方客户端）。
11. WebDAV 服务器的 Range 支持、ETag 行为、401/403 差异。
12. Gitee/Gist 在 1MB 级快照下的写入限制与错误码。

---

## 15. 参考来源

- NetEase 云盘上传全链路（weapi）：<https://github.com/Cinvin/myuserscripts/blob/main/ncmExtend/src/home/cloudLocalUpload.js>
- NetEase 云盘列表/匹配/删除：<https://github.com/Cinvin/myuserscripts/blob/main/ncmExtend/src/home/myCloudDisk.js>
- NCMApi cloud 模块：<https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/cloud.js>
- NCMApi 云盘详情/歌词/下载：`module/user_cloud_detail.js`、`module/cloud_lyric_get.js`、`module/song_cloud_download.js`
- Alist 网易云驱动：<https://github.com/AlistGo/alist/blob/main/drivers/netease_music/upload.go>
- 云盘匹配接口记录：<https://github.com/LuckyJie3/NetEaseMusicCloudMatch/blob/main/docs/API.md>
- QQ 云盘列表实现：<https://github.com/yakult-green-tea/qq-music-api/blob/main/src/services/user/cloud.ts>
- QQ 云盘只读实现：<https://github.com/Sorihiko/DangoMusic/blob/main/common/core/src/main/kotlin/me/sorihiko/dango/music/QqCloud.kt>
- 微云上传协议：<https://github.com/OpenListTeam/OpenList-Worker/blob/main/src/backend/drivers/weiyun/util.ts>
- QQ/网易 APK 接口清单：<https://github.com/xiaomingky/MingYunTime/blob/master/docs/API_OPERATIONS_REVIEW.md>

---

## 16. 明确不要做的

1. 不要把音频字节、歌词正文、封面 base64 写进 Gist/Gitee 快照。
2. 不要用云盘 ID 替换 `local:<hash>` 作为歌单主身份。
3. 不要在扫描阶段并发上传或并发读全文计算 hash。
4. 不要把 WebDAV 账号密码放进同步文件。
5. 不要在 `-460`/401 时循环重试；先让用户处理登录态。
6. 不要把“云端删除/不可用”当成“用户删除了歌曲”。
7. 不要把 QQ 未匹配原文件的播放能力写进产品承诺，直到有真机闭环证据。
8. 照搬第三方协议/代码时，细节写进 `THIRD_PARTY_NOTICES`，README 只留一行链接。
