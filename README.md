# myMusic

myMusic 是面向 Android 6.0 及以上设备的音乐客户端，支持手机、平板和电视。界面使用 Jetpack Compose 与 Material 3；触摸、鼠标、键盘和遥控器使用同一套焦点与播放操作。

音乐平台能力来自 [music-lib](https://github.com/guohuiyuan/music-lib)，当前纳入版本为 `7cc4467b4e300547edd5d3d5bf0eff1c21ffc2ec`。平台层已移植为 Kotlin 原生实现（`app/src/main/java/com/mytv/mymusic/data/native`），直接使用 OkHttp 与原生加密工具，不需要 GoMobile、AAR 或额外的后端服务；只有汽水音乐的会员整曲需要用户自备可选的签名器（见下文）。Go 源码保留在 `third_party/music-lib`，作为原生移植的参考与 AGPL-3.0 来源说明。

## 项目与社区

- 项目主页：https://github.com/mytv-android/myMusic
- Telegram 群组：https://t.me/mytv_android_channel

两个入口在应用的 `设置 › 关于与更新` 里同样提供：手机上点按打开，电视上用手机扫页面上的二维码（二维码由本机编码器生成，不经过第三方图片服务）。

## 界面结构

界面使用 Material 3 Expressive（`material3 1.5.0-alpha25`，最后一个支持 minSdk 23 的版本），配色由 [MaterialKolor](https://github.com/jordond/MaterialKolor) 从当前歌曲封面实时生成，模糊效果来自 [Haze](https://github.com/chrisbanes/haze)。

主界面有四个入口：**榜单**、**歌单**、**搜索**、**我的**。设置是低频入口：手机在“我的”右上角，平板和电视在导航栏底部。

- **榜单**：平台排行榜轮播、飙升榜第一名和完整榜单；电视上方的大图跟随焦点切换。
- **歌单**：推荐与分类共用一个浏览上下文；手机和平板滚到底自动加载下一页，电视保留翻页按钮。
- **搜索**：提交才请求；类型只有单曲 / 歌单 / 专辑，粘贴链接会自动识别并显示“解析链接”；结果按同一录音合并，逐条标出各平台是完整、试听、VIP 还是没有音源（不只靠颜色区分）。
- **我的**：喜欢、最近播放、下载和自建歌单。移出歌单、取消收藏、清除记录都可以撤销；删除下载文件和歌单仍需确认。自建歌单可同步到 GitHub Gist 或 Gitee。
- **设置**：外观、播放与换源、均衡器、搜索与浏览、平台账号、音乐库同步、存储、关于与更新。平板和电视是“列表 + 详情”双栏。

**播放**

- 手机底部是悬浮迷你播放器：左右滑切歌，上滑展开，长按打开队列；读屏用户有对应的按钮和自定义操作。
- 播放页是沉浸式的：封面铺满并延伸到状态栏下，背景是缓慢流动的封面光，固定深色；控件 4 秒无操作后隐藏；在封面上左右滑切歌、双击收藏；歌名下常驻两行歌词，点开是全屏歌词，点某一行跳到该处播放。
- 定时关闭：15 / 30 / 45 / 60 分钟或播完当前歌曲；结束前 30 秒淡出。计时在播放服务里，后台和锁屏都有效。
- 平板 840–1199dp 使用底部播放条，≥1200dp 右侧常驻 380dp 播放窗格；电视使用全屏播放页，空闲后进入只显示封面和歌词的环境模式。

**外观**

配色来源可选跟随封面（默认）、系统壁纸、品牌松绿或自选颜色；浏览页和播放页的配色风格、对比度、纯黑背景、模糊（自动 / 开 / 关）和减少动画都可以调整。电视、低内存设备和 Android 12 以下默认关闭实时模糊。

拉丁字母和数字使用内置的 Roboto Flex（只含字重轴的子集，约 94KB），中文由系统字体显示。手机上从迷你播放器打开播放页时，封面以共享元素飞入。

**存储、无障碍与性能**

- 播放缓存上限可在“设置 › 存储”里调整（128 MB–1 GB），电视默认 256 MB，其他设备默认 512 MB；调低后立即按最近使用淘汰。
- TalkBack 走查结果见 [docs/accessibility-audit.md](docs/accessibility-audit.md)。
- 发布包带 Baseline Profile（`baselineprofile/` 模块生成）；帧时间测试方法和结果见 [docs/performance.md](docs/performance.md)。

## 平台能力

支持网易云音乐、QQ 音乐、酷狗音乐、酷我音乐、咪咕音乐、千千音乐、汽水音乐、5sing、Jamendo、JOOX、Bilibili 和 Apple Music。每个平台在榜单、歌单和设置中按上游实际能力显示搜索、歌单、专辑、排行榜、分类、歌词、下载和登录。

汽水音乐的会员整曲依赖官方客户端逐请求生成的应用级签名（`x-helios` / `x-medusa`），本项目按 [SodaM](https://github.com/sodahub-org/sodam) / [libresoda](https://github.com/sodahub-org/libresoda) 的签名服务协议接入外部签名器：在设置里填写签名器地址和 Token 后，会员账号可以播放整曲与无损音质；未配置时只使用公开接口提供的试听片段。Apple Music 只提供上游返回的试听预览。上游未实现的歌词、排行榜或登录不会显示为可用操作。

### 汽水音乐播放（签名器）

汽水的播放流分三层：分享页 / SEO、Web / H5、以及 App 端 `POST /luna/pc/track_v2`。前两层对会员曲目只返回 30～60 秒试听；App 端要求每个请求都带 `x-helios` / `x-medusa`，这两个头只能由官方客户端的原生组件生成，纯软件算不出来。因此本项目把「签名」交给一个可选的外部服务，取流和加密流解密留在客户端：

- 汽水账号仍需登录：在 `设置 › 平台账号 › 汽水音乐` 粘贴官方客户端的 Cookie。电视端按既有约定不提供文本输入，汽水会员播放目前主要在手机或平板上使用。
- 在 `设置 › 平台账号 › 汽水播放签名器` 填写签名服务的 `POST /sign` 地址（例如 `http://192.168.1.10:8899/sign`）和可选的 Bearer Token。
- 签名服务可运行 [libmssdk](https://github.com/sodahub-org/libmssdk)（官方客户端 `bdms.node` 的 HTTP 包装）或 SodaM/libresoda 的签名宿主。它需要一台能加载官方原生组件的电脑（Windows / macOS / Linux + Wine），手机或电视通过局域网访问；签名器会看到即将发出的完整请求头（含 Cookie），只应指向自己信任的实例，跨公网使用 SSH 隧道或反向代理，并始终设置 Token。
- 协议与 sodam 一致：请求 `{url, method, body, headers, ts_ms}`，响应 `{ok, headers: {x-helios, x-medusa}, ms_token, a_bogus}`；客户端通过签名器的 `GET /config` 对齐 `device_id` / `iid` / `fp`，并用 body 的 MD5（大写）生成 `X-SS-STUB`。
- App 端点返回的加密流（`play_auth` / spade）会在本机用 AES-CTR 按 MP4 CENC 逐样本解密，解密后的明文缓存到应用缓存目录（最多 24 首，按最近使用淘汰），再交给 ExoPlayer 播放；下载按钮保存的同样是解密后的文件。
- 没有签名器时行为保持不变：免费曲目照常播放，会员曲目只返回公开试听流。

签名器与相关参考实现不随本应用分发，也不包含任何官方二进制。请只用于播放自己账号有权播放的内容，不要做公共代理或分发凭据。

**来源与许可**：签名服务协议、取流链路和加密流解密逻辑参考 [SodaM](https://github.com/sodahub-org/sodam)（AGPL-3.0）与 [libresoda](https://github.com/sodahub-org/libresoda)（AGPL-3.0），签名服务契约参考 [libmssdk](https://github.com/sodahub-org/libmssdk)（MIT，不包含官方 `bdms.node`）。本项目的 Kotlin 实现是对应的衍生作品，同样以 AGPL-3.0 分发；第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 跨平台聚合与同步歌词

- **聚合搜索**：单曲、专辑、歌单并行查询已启用的平台（默认网易云、QQ、酷狗、酷我、咪咕）。单曲按同一录音合并；专辑按名称、艺人和曲目数保守分组；歌单只做“同名歌单”推荐，不合并歌单内容。
- **分页**：默认 5 个平台的单曲聚合搜索支持“加载更多”；其余平台目前返回第一页。
- **换源播放**：榜单或歌单中的曲目可以在其他平台按需检索同一版本并播放；所选平台没有返回播放地址时，自动回退到其他来源并保留播放进度。
- **人工纠错**：来源面板支持“拆分”，搜索结果支持“合并到…”，也可以把“其他版本”合并进当前曲目；可在设置中恢复自动匹配。
- **状态透明**：完整、试听、VIP、无版权和未检测分别展示；试听片段默认不会自动播放。
- **同步歌词**：标准 LRC 支持逐行高亮和自动滚动；增强 LRC 支持逐词高亮；没有时间戳时回退为原文。
- **歌词背景律动**：在 `设置 › 歌词 › 显示歌词背景` 中选择“律动”，背景会随真实音频的低 / 中 / 高频能量变化，并通过频谱通量检测鼓点来驱动短促脉冲，不再只是旋转封面。分析直接读取 ExoPlayer 解码后的 PCM，在本机单线程分析（常规音源约 30 fps），不申请麦克风权限，不保存或上传音频；暂停、离开播放页或开启“减少动画”时停止。该功能参考 [YesPlayMusic](https://github.com/ijoji42/YesPlayMusic)（MIT），算法与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- **可配置**：设置中可以选择聚合平台、播放策略、优先平台、匹配严格度，并清除匹配缓存或恢复自动匹配。

聚合结果与匹配缓存保存在本机 Room 数据库，播放地址只存在内存中，不会上传到服务器。

部分平台的封面和音频 CDN 仍使用 `http://`，应用为此允许明文流量；请只在可信网络下使用，并注意公共 Wi-Fi 下的流量风险。

## 后台播放与离线缓存

- 后台播放由 Media3 `MediaSessionService` 负责，切到后台或锁屏后继续播放，并保留系统媒体通知与控制。
- ExoPlayer 使用 512 MB 的 LRU `SimpleCache`，播放过的音频会自动缓存；可在设置中点击“清除播放缓存”。
- 播放器与歌曲列表的下载按钮会把音频保存到应用目录。已完成的下载会在播放时优先作为本地音源，网络不可用时也能从本地文件播放。
- 下载记录在“我的 → 下载”中管理，可删除本地文件。

## 音乐库同步

- 同步自建歌单、收藏和平台账号 Cookie。Cookie 会以明文写入私密 Gist / 代码片段，请勿公开文件链接；最近播放、下载文件、播放地址和播放进度不上传。
- 后端支持 GitHub Gist 与 Gitee 代码片段；也可以导入 / 导出本机 JSON。
- TV 连接页可生成 10 分钟有效的二维码，手机在同一局域网打开网页面板，填写令牌和可选的文件 ID / 链接。网页沿用 TV 当前 Material 配色，服务基于 [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)（BSD-3-Clause），只在配置页前台运行；离开页面、进入后台或会话过期即关闭，已保存令牌不会传给网页。
- 本机修改先写入 Room，界面立即反馈；修改后约 1 秒防抖立即推送，离线时交给 WorkManager 重试。推送后会再读一次云端并重新合并，避免两台设备停在旧版本。
- 三个版本（上次基线、本机、云端）逐歌单合并。两边都修改同一歌单时不会静默覆盖，可选择“合并两版 / 保留本机 / 使用云端”；删除使用墓碑，不会把已删除歌单重新创建。
- 收藏和平台 Cookie 按修改时间合并，取消收藏 / 退出登录同样使用墓碑，不会被另一台设备的旧数据恢复。
- 令牌使用 Android Keystore 的 AES-GCM 密钥加密，只保存在本机，不进入数据库、日志或同步文件。

## 均衡器与应用更新

- 均衡器绑定当前播放会话，按设备实际频段绘制五段或更多滑杆，提供平直、流行、摇滚、爵士、人声和自定义预设；设置保存在本机，切换歌曲、换源或重建 audio session 后重新应用。设备不支持时明确显示“未生效”，不影响播放。
- 关于页检查 GitHub Release 的 `update.json`，只比较 versionCode；下载后校验 SHA-256、包名、版本号和签名，再通过 FileProvider 打开系统安装器。Android 不允许静默安装。
- CI 在发布 APK 的同时生成 `update.json` 与 SHA-256 文件。

## 构建

提交或拉取请求会运行 Android 单元测试。推送到 `main` 或 `dev` 时，GitHub Actions 会构建 Android 6.0+ 的通用 APK，并随 Release 发布 `update.json` 和 `update.apk.sha256`。

配置以下仓库或组织 Secrets 后，Release 会使用正式签名；未配置时使用 debug 签名：

- `SIGNING_PROPERTIES`：Base64 编码的 properties，包含 `storeFile=keystore.jks`、`storePassword`、`keyAlias`、`keyPassword`。
- `SIGN_KEY`：Base64 编码的 JKS 密钥库。

## 许可证

应用代码与纳入的 music-lib 均使用 GNU Affero General Public License v3.0。完整文本见 [LICENSE](LICENSE)。

这是一个学习项目。请遵守所在地法律和各音乐平台的服务条款，不要将程序或下载内容用于商业用途。
