# myMusic

myMusic 是面向 Android 6.0 及以上设备的音乐客户端，支持手机、平板和电视。界面使用 Jetpack Compose 与 Material 3；触摸、鼠标、键盘和遥控器使用同一套焦点与播放操作。

音乐平台能力来自 [music-lib](https://github.com/guohuiyuan/music-lib)，当前纳入版本为 `7cc4467b4e300547edd5d3d5bf0eff1c21ffc2ec`。平台层已移植为 Kotlin 原生实现（`app/src/main/java/com/mytv/mymusic/data/native`），直接使用 OkHttp 与原生加密工具，不需要 GoMobile、AAR 或额外的后端服务。Go 源码保留在 `third_party/music-lib`，作为原生移植的参考与 AGPL-3.0 来源说明。

## 平台能力

支持网易云音乐、QQ 音乐、酷狗音乐、酷我音乐、咪咕音乐、千千音乐、汽水音乐、5sing、Jamendo、JOOX、Bilibili 和 Apple Music。每个平台在首页和设置中按上游实际能力显示搜索、歌单、专辑、排行榜、分类、歌词、下载和登录。

汽水音乐的会员整曲依赖官方客户端签名器，Android 上不可用。Apple Music 只提供上游返回的试听预览。上游未实现的歌词、排行榜或登录不会显示为可用操作。

## 跨平台聚合与同步歌词

- **聚合搜索**：单曲、专辑、歌单并行查询已启用的平台（默认网易云、QQ、酷狗、酷我、咪咕）。单曲按同一录音合并；专辑按名称、艺人和曲目数保守分组；歌单只做“同名歌单”推荐，不合并歌单内容。
- **分页**：默认 5 个平台的单曲聚合搜索支持“加载更多”；其余平台目前返回第一页。
- **换源播放**：榜单或歌单中的曲目可以在其他平台按需检索同一版本并播放；所选平台没有返回播放地址时，自动回退到其他来源并保留播放进度。
- **人工纠错**：来源面板支持“拆分”，搜索结果支持“合并到…”，也可以把“其他版本”合并进当前曲目；可在设置中恢复自动匹配。
- **状态透明**：完整、试听、VIP、无版权和未检测分别展示；试听片段默认不会自动播放。
- **同步歌词**：标准 LRC 支持逐行高亮和自动滚动；增强 LRC 支持逐词高亮；没有时间戳时回退为原文。
- **可配置**：设置中可以选择聚合平台、播放策略、优先平台、匹配严格度，并清除匹配缓存或恢复自动匹配。

聚合结果与匹配缓存保存在本机 Room 数据库，播放地址只存在内存中，不会上传到服务器。

部分平台的封面和音频 CDN 仍使用 `http://`，应用为此允许明文流量；请只在可信网络下使用，并注意公共 Wi-Fi 下的流量风险。

## 后台播放与离线缓存

- 后台播放由 Media3 `MediaSessionService` 负责，切到后台或锁屏后继续播放，并保留系统媒体通知与控制。
- ExoPlayer 使用 512 MB 的 LRU `SimpleCache`，播放过的音频会自动缓存；可在设置中点击“清除播放缓存”。
- 播放器与歌曲列表的下载按钮会把音频保存到应用目录。已完成的下载会在播放时优先作为本地音源，网络不可用时也能从本地文件播放。
- 下载记录在“音乐库 → 下载”中管理，可删除本地文件。

## 构建

提交或拉取请求会运行 Go 与 Android 单元测试。推送到 `main` 或 `dev` 时，GitHub Actions 会构建 Android 6.0+ 的通用 APK，并发布到 GitHub Release。

配置以下仓库 Secrets 后，Release 会使用正式签名；未配置时生成未签名的 Release 包：

- `SIGNING_PROPERTIES`：Base64 编码的 properties，包含 `storeFile=keystore.jks`、`storePassword`、`keyAlias`、`keyPassword`。
- `SIGN_KEY`：Base64 编码的 JKS 密钥库。

## 许可证

应用代码与纳入的 music-lib 均使用 GNU Affero General Public License v3.0。完整文本见 [LICENSE](LICENSE)。

这是一个学习项目。请遵守所在地法律和各音乐平台的服务条款，不要将程序或下载内容用于商业用途。
