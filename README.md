# myMusic

myMusic 是面向 Android 6.0 及以上设备的音乐客户端，支持手机、平板和电视。界面使用 Jetpack Compose 与 Material 3；触摸、鼠标、键盘和遥控器使用同一套焦点与播放操作。

音乐平台能力来自 [music-lib](https://github.com/guohuiyuan/music-lib)，当前纳入版本为 `7cc4467b4e300547edd5d3d5bf0eff1c21ffc2ec`。Go 源码位于 `third_party/music-lib`，通过 GoMobile 编译进应用，不需要额外的后端服务。

## 平台能力

支持网易云音乐、QQ 音乐、酷狗音乐、酷我音乐、咪咕音乐、千千音乐、汽水音乐、5sing、Jamendo、JOOX、Bilibili 和 Apple Music。每个平台在首页和设置中按上游实际能力显示搜索、歌单、专辑、排行榜、分类、歌词、下载和登录。

汽水音乐的会员整曲依赖官方客户端签名器，Android 上不可用。Apple Music 只提供上游返回的试听预览。上游未实现的歌词、排行榜或登录不会显示为可用操作。

## 构建

提交或拉取请求会运行 Go 与 Android 单元测试。推送到 `main` 或 `dev` 时，GitHub Actions 会构建 Android 6.0+ 的通用 APK，并发布到 GitHub Release。

配置以下仓库 Secrets 后，Release 会使用正式签名；未配置时生成未签名的 Release 包：

- `SIGNING_PROPERTIES`：Base64 编码的 properties，包含 `storeFile=keystore.jks`、`storePassword`、`keyAlias`、`keyPassword`。
- `SIGN_KEY`：Base64 编码的 JKS 密钥库。

## 许可证

应用代码与纳入的 music-lib 均使用 GNU Affero General Public License v3.0。完整文本见 [LICENSE](LICENSE)。

这是一个学习项目。请遵守所在地法律和各音乐平台的服务条款，不要将程序或下载内容用于商业用途。
