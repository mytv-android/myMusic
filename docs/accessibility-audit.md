# myMusic 无障碍（TalkBack）走查报告

日期：2026-10-22　范围：M3 Expressive 重设计后的全部主要页面（手机 / 电视；平板只做了代码检查）

## 1. 方法

### 设备与读屏

| 设备 | 系统 | 读屏 | 用途 |
|---|---|---|---|
| `emulator-5554` Google TV 1080p（320dpi，`isTv`） | Android 12（API 31），简体中文 | TalkBack 14.2.0.740629310 leanback | 电视逐页 D-pad 走查，记录 TalkBack 实际说出的话 |
| `emulator-5556` Pixel 7（420dpi） | Android 14（API 34），en-US | 无 | 手机逐页 `uiautomator dump` + 截图 |

- 手机 TalkBack 没有跑起来：SDK 里的 `android-36.1/google_apis_playstore` 目录是空的，建不了带 TalkBack 的 AVD；把电视上的 TalkBack 装到手机，先是只有 x86 原生库（`INSTALL_FAILED_NO_MATCHING_ABIS`），去掉原生库重签后能装但启动即崩（`CustomLabelManager: run not on UI thread`）。随后按协调要求不再启动额外模拟器，5556 也交给了别的会话。所以手机端靠 `uiautomator dump`（它读取的就是 TalkBack 使用的 AccessibilityNodeInfo 树）+ 下面实测得出的 TalkBack 读法规则 + 代码阅读。
- 平板布局（`LargeScreen.kt`、双栏）没有在设备上跑（`wm size` 需要占用手机模拟器），只做了代码检查。
- TalkBack 实际语音：在 TalkBack「高级设置 → 开发者设置」里打开「诊断模式」后，logcat 的 `TalkBackFeedbackProvider … ttsOutput=` 就是它说的整句。`/c/tmp/mmx-scripts/a11y_tv_helpers.sh` 里的 `say KEY…` 发按键后打印这些句子（解析脚本 `a11y_talkback_log.py`）。

### 自动检查脚本

`C:\tmp\mmx-scripts\a11y_audit.py`（Python，读 `uiautomator dump` XML）：

```
python a11y_audit.py --adb emulator-5554 --name 页面名 --save C:/tmp/a11y/pages --tree
python a11y_audit.py dump.xml --density 420 [--tree] [--raw]
```

检查：可点 / 可聚焦 / 可勾选节点没有可读标签；触控目标 < 48dp（贴着滚动容器或屏幕边缘的标为“被裁切”，多数是滚出一半的行，属误报）；同一屏多个可操作节点读法完全相同；标签像 URL / id / 色值（`#006B5F`）或只有“按钮”；可聚焦但没有内容的空节点；不可操作却单独带描述的节点。`--tree` 按位置列出每个可操作节点会被读成的整句和选中状态。另有 `a11y_contrast.py` 从截图取样估算文字对比度。

### 实测得到的 TalkBack 读法（决定了怎么修）

在电视 TalkBack 上对比几种写法后确认：**焦点节点读“自己的 contentDescription（没有时读 text）”，再接上所有不可聚焦子节点；子节点有 contentDescription 时只读描述、不再往下读。** 因此：

- 在可点容器（`Surface(onClick)`、`clickable` 的 Row / Column、卡片）上加 `semantics { contentDescription }` **不会替换**里面的文字，而是读两遍（例：`喜欢的歌，0 首. 0. 首喜欢的歌`）。
- 要替换读法，描述要放在文字那一层（`Text(…, Modifier.semantics { contentDescription = … })`），或把内层用 `clearAndSetSemantics {}` 清掉；`clearAndSetSemantics` 写在 `clickable` 之后时会保留点击。

## 2. 结果统计

- 发现问题 **51** 个：修复 **48** 个——走查时直接修 39 个（第 3 节带编号的 G/C/D/S/L/T/M/X 条目），R1–R9 这 9 个在当时由主代理负责的文件里，之后按第 5 节的改法补上；其余 **3** 个低优先级未修（O1–O3，第 6 节）。
- 编译：`compile.sh :app:assembleDebug` → `BUILD_EXIT=0`（最后一次 15:46）。
- 复测：前两批修复（标注“已实测”的条目）装到电视模拟器后用 TalkBack 复听过。最后一小批（T6 色值、T7 账号按钮、T8 风格 chip 组名、G14 抽屉收起时的名字）只做了编译和代码检查：15:47 安装最终 APK 时 `emulator-5554` 停止响应（`adb shell` 和模拟器控制台都超时，到 16:15 仍未恢复），没有强行重启它。
- （已处理）当时模拟器卡住，电视上的 TalkBack 和“诊断模式”还开着；之后已在复测结束时关闭。关闭命令：`adb -s emulator-5554 shell settings delete secure enabled_accessibility_services`、`adb -s emulator-5554 shell settings put secure accessibility_enabled 0`（诊断模式是 TalkBack 自己的设置，TalkBack 关掉后不再生效）。手机 `emulator-5556` 上临时装的 TalkBack 已卸载，无障碍设置已恢复。

## 3. 分页面结果

“原来”是 TalkBack 实际说出的话（电视）或 dump 推算出的读法（手机）。

### 全局 / 公共组件

| # | 问题 | 原来 | 现在 |
|---|---|---|---|
| G1 | 页面标题、小节标题、面板标题都不是 heading（M3 顶栏本身不加），读屏按标题跳转找不到 | — | 页面标题、`SectionLabel`、面板标题、详情标题、电视行标题都加了 `heading()` |
| G2 | 切换页面时读屏不知道到了哪一页 | 只读第一个按钮 | `PageScaffold` 设 `paneTitle`；TalkBack 日志确认窗口标题变为“我的” |
| G3 | 歌曲行名次只读数字 | `2，睡前别想太多…` | `第 2 名，睡前别想太多…` |
| G4 | 正在播放的行只靠底色 / 字重区分 | 无状态 | 状态“正在播放” |
| G5 | 歌曲行 / 最佳匹配长按没有动作名 | “双击并按住即可长按” | 长按动作名“更多操作” |
| G6 | 每行“更多”按钮同名（一屏 7 个“更多操作”） | `更多操作` ×7 | `更多操作：黑蝴蝶` |
| G7 | 列表行的选中（设置左栏、电视分类列表、双栏左栏）不读状态 | `全部` / `外观…` | `已选择. 外观…` |
| G8 | 单选的筛选 chip 读成复选框 | `未选择. QQ 音乐. 复选框` | `未选中. QQ 音乐. 单选按钮` |
| G9 | Split button 的展开键读“未选中” | `更多播放方式，未选中` | `更多播放方式，已收起 / 已展开` |
| G10 | 进度条读百分比，焦点停在上面时播放中每 1% 播报一次 | `百分之 1。`（约 3 秒一次） | `已播放 2 分 10 秒，共 5 分 20 秒`，按 10 秒取整，最多 10 秒播一次（电视上因第 5 节 R2 的自动隐藏没能实听到，读法以代码为准） |
| G11 | 音源 chip 描述 + 内部文字读两遍 | `音源：酷我音乐 128 kbps，自动。点按切换音源. 酷我音乐 · 128 kbps. 自动` | 只读描述 |
| G12 | 平台简称头像和全称重复 | `网易，网易云音乐…` | `网易云音乐…` |
| G13 | 电视左侧抽屉当前页没有状态 | `榜单` | `已选择. 榜单`（已实测） |
| G14 | 电视抽屉收起时“正在播放”只有封面，没有名字 | `<无标签>` | `正在播放，歌名` |

### 榜单

| # | 问题 | 原来 | 现在 |
|---|---|---|---|
| C1 | 手机轮播卡片名称读两遍（描述落在子节点上，再加标题文字） | `飙升榜，刚刚更新，网易云音乐，飙升榜，刚刚更新` | `飙升榜，刚刚更新` |
| C2 | 电视卡片只读标题，听不出是谁的歌 / 哪个平台 | `宝船` | `宝船，最近播放 · DOUDOU、燕云十六声 · 宝船`（已实测） |
| C3 | 电视默认焦点“播放全部”不知道播什么 | `播放全部. 按钮` | `播放全部：飙升榜. 按钮`（已实测） |
| — | 刷新、平台 chip、第 1 名大卡片（`第 1 名，宝船…`、`播放宝船`）读法正常 | | |

### 歌单（推荐 / 分类）

- 平台 chip、分类 chip → G8；电视分类列表选中项 → G7。
- 推荐卡片读法正常：`9,889 万 次播放. 一周日语上新… . 网易云推荐 · 50 首`；“推荐 / 分类”切换是 `已选中. 推荐. 单选按钮`。分类网格标题本来就是 heading。

### 歌单 / 榜单 / 专辑详情

| # | 问题 | 处理 |
|---|---|---|
| D1 | 详情标题不是 heading | 加 `heading()` |
| D2 | 电视“播放全部 / 播放专辑”不带标题 | 读作 `播放全部：歌单名` |
| — | 收藏按钮 `收藏到本地歌单，未收藏`、返回、歌曲行（G3–G6）正常 | |

### 搜索

| # | 问题 | 原来 | 现在 |
|---|---|---|---|
| S1 | 文字按钮语义不完整 | `清除`、`调整` | `清除搜索记录`、`调整聚合平台` |
| S2 | 结果行“更多”同名、最佳匹配长按无名 | 见 G5/G6 | 已修 |
| S3 | 电视搜索词条、平台按钮、电视歌单 / 专辑卡片：描述 + 内部文字读两遍 | `修改搜索词：jay. jay` 等 | 只读描述（内层 `clearAndSetSemantics`） |
| — | 搜索框读作“编辑框，歌曲、歌单、专辑，或粘贴链接”；电视屏幕键盘的字母、空格、退格、清空都有名字 | 正常 | |

### 我的（+ 喜欢 / 最近 / 下载 / 自建歌单 / 编辑）、同步

| # | 问题 | 原来 | 现在 |
|---|---|---|---|
| L1 | 喜欢磁贴（手机 + 电视）数字与说明分开读 | `0. 首喜欢的歌` | `喜欢的歌，0 首`（已实测） |
| L2 | 电视“最近播放”卡片只读歌名，正在播放无状态 | `我不难过` | `我不难过，孙燕姿` + “正在播放”（已实测） |
| L3 | 手机“最近播放 / 下载”磁贴说明文字 0.8 透明度，对比度约 3.9–4.0:1 | — | 改为不透明的 on*Container 色（约 6:1） |
| L4 | 喜欢列表“取消收藏”、下载列表“删除下载文件”每行同名 | `取消收藏` ×N | `取消收藏“歌名”`、`删除“歌名”的下载文件` |
| L5 | 自建歌单页头部“更多”与行内“更多”同名 | `更多操作` | `歌单更多操作` |
| L6 | 平板“新建歌单”展开键无展开状态 | — | `已收起 / 已展开` |
| — | 同步卡片、云朵按钮（`音乐库同步：本机音乐库 · 未连接同步`）、编辑行的 `移除 / 上移 / 下移“歌名”`、同步策略单选、令牌“显示 / 隐藏令牌”、“新建”面板（电视焦点进入、返回键关闭后回到“新建”）正常 | | |

### 设置

| # | 问题 | 原来 | 现在 |
|---|---|---|---|
| T1 | 左栏选中分区不读状态 | 见 G7 | 已修（实测 `已选择. 均衡器…`） |
| T2 | 均衡器开关没有名字，“已关闭”读两遍 | `已关闭. 已关闭. 当前输出：扬声器. 开关` | `已关闭. 均衡器. 当前输出：扬声器. 开关`（已实测） |
| T3 | 电视均衡器：滑杆占用上下键，下方“恢复平直 / 保存为自定义”用遥控器到不了 | 焦点困在滑杆组 | 最右侧频段再按右键移到“恢复平直”，页内提示同步更新（已实测） |
| T4 | “默认浏览来源”面板：未选项不读单选状态，选中项“当前来源”重复 | `网易云音乐，当前来源` | `已选中 / 未选中 … 单选按钮` |
| T5 | 电视平台优先级行名次读两遍 | `第 1 位, 1, 网易云音乐` | `第 1 位, 网易云音乐` |
| T6 | 配色来源卡片读出色值 | `品牌松绿，#006B5F` | `品牌松绿` |
| T7 | 平台账号三个“登录”按钮同名 | `登录` ×3 | `扫码登录QQ 音乐`、`退出…`、`用 Cookie 登录…` |
| T8 | 外观页两组风格 chip 同名（浏览页 / 播放页） | `柔和` ×2 | `浏览页风格：柔和`、`播放页风格：柔和` |
| — | 主题 / 对比度 / 模糊等按钮组读作 `已选中. 柔和. 单选按钮`；配色来源卡片是单选；均衡器频段 `0 分贝. 910 Hz. 滑块`；存储、关于、许可、隐私（电视上 TextCard 可聚焦）正常 | | |

### 播放（迷你播放器、播放页、歌词、队列、定时、换源）

| # | 问题 | 处理 |
|---|---|---|
| M1 | 队列中正在播放的行“正在播放”读两遍（状态 + 图标描述） | 图标改为装饰 |
| M2 | 队列“清空”含义不全 | `清空播放队列` |
| M3 | 平板播放窗格：可点封面没有名字；底部播放条歌曲信息区没有点击动作名 | `打开播放页` |
| M4 | 换源面板：`当前音源` 读两遍、`已固定` 读两遍、平台简称重复 | 已修 |
| R1–R9 | 播放页 / 迷你播放器 / 外壳本身的问题 | 文件不在本次可改范围，见第 5 节 |
| — | 定时面板：时长是单选按钮组，两个开关读开 / 关；播放键 `正在播放. 暂停. 按钮`；上一首 / 下一首有名字 | 正常 |

### 面板 / 对话框 / 空状态 / 错误

| # | 问题 | 处理 |
|---|---|---|
| X1 | “合并到…”对话框的单选行不读选中状态（行内 RadioButton 没有语义） | 行设为单选按钮 + 选中状态 |
| — | 手机底部面板是独立窗口，TalkBack 焦点会进入；电视 / 平板侧边面板默认焦点在第一项，返回键关闭后焦点回到触发按钮（电视“新建”实测）。空状态、错误块的按钮都有文字。Snackbar 是 polite live region。 | 正常 |
| — | 手机上的 `Close sheet` / `Drag handle` / Snackbar `Dismiss` 是英文：来自 M3 资源，跟随系统语言（模拟器是 en-US），中文系统会显示中文，不算应用问题 | — |

### 触控目标与对比度

- 触控目标：各页 dump 中没有发现完整显示却小于 48dp 的可操作节点；脚本报出的都是滚出一半的行或被浮层遮住的行。迷你播放器的 34dp 播放键靠 M3 `Surface(onClick)` 自带的 `minimumInteractiveComponentSize` 扩到 48dp（代码确认，手机未能实测迷你播放器）。
- 对比度（截图取样，抗锯齿会略微低估）：手机浅色副标题 / 摘要 5.7–6.1:1，电视深色说明文字 7.7–8.4:1，电视设置左栏选中行摘要约 4.5:1（临界，未改）。手机磁贴说明文字约 3.9–4.0:1（已修，L3）。电视停用的“搜索”按钮 2.2:1，属停用控件，WCAG 豁免。

## 4. 修改清单（file:line，路径相对 `app/src/main/java/com/mytv/mymusic/ui/`）

- `components/Scaffolds.kt:59, 84`（paneTitle）、`:92, 103, 152`（标题 heading）
- `components/Lists.kt:77`（SectionLabel heading）、`:118–120`（长按动作名、正在播放状态）、`:127`（第 N 名）、`:161`（更多操作：歌名）、`:196`（列表行选中状态）
- `components/Sheets.kt:122`（面板标题 heading）
- `components/Buttons.kt:222–226`（Split button 展开状态）
- `components/Progress.kt:79–90`（进度条读法与 10 秒取整）、`spokenTime()`
- `components/Badges.kt:127`（音源 chip 内层不再重复）
- `tv/TvNavRail.kt:150–154`（抽屉选中状态、收起时“正在播放”的名字）
- `AppUi.kt:302–313`（MusicChip 单选角色、`description` 参数）、`:614–620`（合并对话框单选）
- `charts/ChartsScreen.kt:336`（轮播去掉重复描述）、`:635, 718`（heading）、`:700`（播放全部：标题）、`:731, 841, 861`（电视卡片补歌手 / 来源）
- `CollectionScreen.kt:301`（电视播放按钮带标题）、`:372`（详情标题 heading）
- `search/SearchScreen.kt:628, 695`（清除 / 调整）、`:929`（更多操作：歌名）、`:1006`（长按动作名）、`:1632, 1784, 1936`（平台按钮、电视搜索词条、电视歌单卡片去重复）
- `library/LibraryScreen.kt:441, 709`（喜欢磁贴）、`:562`（电视行标题 heading）、`:607–610`（电视卡片补歌手 + 正在播放）、`:765`（磁贴说明对比度）、`:953`（新建 Split button 展开状态）
- `library/LibraryCollections.kt:163, 457`（取消收藏 / 删除下载带歌名）、`:667`（筛选 chip 单选角色）
- `library/LocalPlaylistScreen.kt:562`（歌单更多操作）
- `settings/EqualizerScreen.kt:96, 119, 124, 211, 247`（遥控器从最右频段到下方按钮）、`:158–183`（开关名称 / 状态去重）
- `settings/SettingsCommon.kt:151`（平台简称头像不读）
- `settings/SettingsSubScreens.kt:353–358`（色值不读）、`:425`（风格 chip 带组名）、`:609`（电视优先级名次去重）、`:722–731`（默认来源单选）、`:850–857`（账号按钮带平台名）
- `SourcePickerSheet.kt:274, 290, 325`（换源面板去重复）
- `player/PlayerSheets.kt:124`（清空播放队列）、`:147`（正在播放图标改装饰）
- `adaptive/LargeScreen.kt:129, 218, 272`（平板播放条 / 播放窗格）

## 5. 当时由主代理负责的文件里的问题（已按下表改法修复）

R1–R9 已全部落地并编译通过：R1 播放页打开时底层页面整体移出无障碍树并禁止遥控器焦点进入；R2 新增 `rememberScreenReaderOn()`，读屏开着时播放页不进入空闲隐藏（随无障碍开关实时变化）；R3–R8 按表中改法；R9 采用“保留整行描述 + 文字列 `clearAndSetSemantics {}`”，并去掉了描述里的“点按打开播放页”。电视上用 TalkBack 实听复测过：播放页打开后无障碍树里只剩播放页节点，方向键不会跑到下面的榜单页（R1）；读屏开着时 8 秒后控件仍在、焦点正常移动（R2）；右侧按键读作“定时关闭，按钮”“未收藏，收藏，按钮”“播放队列，按钮”（R3）；封面读“宝船 的封面”（R5）；歌词行提示“按选择按钮即可从这一句播放”（R7）。R4、R6、R8、R9 在手机布局上，只做了编译和代码检查。复测后已关闭电视模拟器上的 TalkBack。


| # | 文件:行 | 问题（实测） | 建议改法 |
|---|---|---|---|
| R1 | `AppNavigation.kt` 约 210 行（装 NavHost 的 `Row`） | 播放页盖在页面上面时，底下的页面仍在无障碍树里且可聚焦：电视播放页里 TalkBack 焦点跑到了下面“我的”页的云朵按钮（`音乐库同步：本机音乐库 · 未连接同步. 按钮`），dump 里底层节点全部可见；手机上滑动浏览会从播放页穿到底下的页面 | 该 `Row` 的 modifier 追加 `.then(if (playerVisible) Modifier.clearAndSetSemantics {} else Modifier)`；电视再加 `.focusProperties { onEnter = { if (playerVisible) cancelFocusChange() } }.focusGroup()`，让遥控器焦点也进不去 |
| R2 | `player/PlayerParts.kt:90–100`（`rememberIdleState`）、`player/PlayerScreen.kt:336` | 播放 4 秒后控件淡出并拦截触摸；读屏的双击 / 滑动不产生指针事件，`wakeOnInput` 唤不醒；电视上焦点被移到一个无名字的 1dp 占位节点，第一下按键只用来唤醒。实测：TalkBack 焦点框停在看不见的“下一首”上，按键后不再有任何播报 | 读屏开启时不进入环境模式：`rememberIdleState` 里取 `LocalContext.current.getSystemService(AccessibilityManager::class.java)`，`val screenReader = am?.isEnabled == true && am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()`（可用 `AccessibilityStateChangeListener` 跟随变化），`LaunchedEffect(state.tick, playing, screenReader) { state.idle = false; if (playing && !screenReader) { delay(timeoutMs); state.idle = true } }` |
| R3 | `player/PlayerScreen.kt:386–395`（`TvAction`）、`:375` | 电视播放页右侧四个键没有按钮角色（读 `定时关闭` 而不是 `定时关闭. 按钮`）；收藏键读动作 `收藏 / 取消收藏`，不读当前状态 | `TvAction` 加参数 `state: String? = null`，`modifier = Modifier.size(56.dp).tvFocusRing(CircleShape).semantics { role = Role.Button; state?.let { stateDescription = it } }`；收藏调用改为固定标签 `"收藏"` + `state = if (ui.favorite) "已收藏" else "未收藏"` |
| R4 | `player/PlayerScreen.kt:490–500`（`FavoriteButton`） | `IconToggleButton` 的描述随状态在“收藏 / 取消收藏”之间变，再加上“已选中”，读成 `已选中, 取消收藏` | 描述固定为 `"收藏"`，在 `modifier` 上加 `.semantics { stateDescription = if (ui.favorite) "已收藏" else "未收藏" }` |
| R5 | `player/PlayerParts.kt:228`（`PlayerArt`） | 描述写“左右滑动切歌，双击收藏”，但读屏下滑动是移动焦点、双击是激活，这两个手势都不起作用，也没有对应的读屏操作 | 描述改为 `"$title 的封面"`，同一 `semantics` 里加 `customActions = listOf(CustomAccessibilityAction("下一首") { currentSkip(1); true }, CustomAccessibilityAction("上一首") { currentSkip(-1); true }, CustomAccessibilityAction("收藏") { currentDoubleTap(); true })` |
| R6 | `player/PlayerScreen.kt:255`（全屏歌词顶部小封面） | 可点但没有名字，读屏得到一个空的可点节点（旁边已有“回到封面”按钮） | 改为 `Modifier.clearAndSetSemantics {}.clickable { ui.onLyricsMode(false) }`（clear 写在 clickable 前面，读屏跳过它，触摸仍可点） |
| R7 | `player/PlayerParts.kt:322`（`LyricsList` 行） | 可点歌词行只读文字，提示“双击即可激活”，不知道会跳转进度 | `Modifier.clickable(onClickLabel = "从这一句播放") { onSeek(line.timeMs) }` |
| R8 | `player/PlayerScreen.kt:429–445`（`SleepChip`） | 只读 `12:34` / `本首结束`，不知道是定时关闭 | 内层 `Text` 加 `Modifier.semantics { contentDescription = if (ui.sleep.endOfTrack) "定时关闭：本首结束后停止" else "定时关闭：还剩 ${formatClock(remaining)}" }`（放在文字上，不要放在 Surface 上，否则会读两遍） |
| R9 | `adaptive/PhoneBottomBar.kt:134, 158–165, 171` | 迷你播放器 Row 自己有描述“正在播放 X，Y。点按打开播放页”，又会接着读子文字：`正在播放 X，Y。点按打开播放页. X. Y · 平台`；长按没有动作名 | 二选一：去掉 Row 上的 `contentDescription`（保留 `customActions`），让歌名、歌手文字自然读出；或保留描述并给 171 行的文字 `Column` 加 `.clearAndSetSemantics {}`。另外 `combinedClickable(onClick = actions::openPlayer, onClickLabel = "打开播放页", onLongClick = onOpenQueue, onLongClickLabel = "打开播放队列")`，描述里的“点按打开播放页”可以删掉 |

`ui/theme/Theme.kt`、`ui/components/SharedCover.kt` 没有发现需要修改的问题（实测对比度见第 3 节）。

## 6. 其他未修项

| # | 问题 | 原因 |
|---|---|---|
| O1 | 歌曲时长、队列时长写成 `03:40`，读屏可能读成时刻 | 涉及列表、详情、队列多处展示格式，未在 TalkBack 中实听确认，暂不改；如需处理，可在时长 `Text` 上加 `semantics { contentDescription = "时长 3 分 40 秒" }` |
| O2 | 自选颜色色板读作 `色板 1…N`，没有颜色名 | 色板只有色值没有名字，需要先补一份中文色名 |
| O3 | 自建歌单编辑时上移 / 下移后不播报新位置 | 需要主动播报（`announceForAccessibility` 或 live region），不属于最小改动 |
| — | 手机 TalkBack 实机、平板布局实机未覆盖 | 见第 1 节；手机结论来自 dump + 电视实测的读法规则，平板来自代码 |
| — | 均衡器在电视上仍是“左右选频段、上下调增益”，滑杆组上下键不移动焦点 | 设计稿明确这样交互；已保证从最右频段能到下方按钮，并在页内提示 |

## 7. 复测

```
# 电视（TalkBack + 诊断模式）
source /c/tmp/mmx-scripts/a11y_tv_helpers.sh      # S=emulator-5554
say DPAD_RIGHT DPAD_DOWN                          # 打印 TalkBack 说的话
python C:/tmp/mmx-scripts/a11y_audit.py --adb emulator-5554 --name 页面 --tree
```

开启 / 关闭 TalkBack：`settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService` + `settings put secure accessibility_enabled 1`；关闭时 `settings delete secure enabled_accessibility_services` + `accessibility_enabled 0`。
