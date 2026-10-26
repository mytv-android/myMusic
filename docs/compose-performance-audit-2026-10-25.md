# Compose 性能审计

日期：2026-10-25。审计对象为当前工作区，包括未提交的歌词背景、节奏分析和歌词设置功能。

后续已按授权实施修复，详见 [修复清单](compose-performance-repair-checklist.md) 和 [修复验证报告](compose-performance-fix-verification.md)。以下保留修复前的审计结论、行号和当时验证结果，不代表当前代码仍有这些问题。

本次完成源码审计、发布版 Kotlin 编译、单元测试重跑和发布版 Android Lint。没有修改应用源码，也没有提交代码。未采集本次设备帧时间、分配数据或 Perfetto trace，因此以下结论不包含已实测的掉帧或性能提升比例。

审计遵循 jetpack-compose-performance 技能。每类问题在报告前均核对对应参考文档，并追踪必要的数据来源。子审计因连接错误中断的部分已补齐，原始建议经过复核后才纳入本报告。

## 确认问题与建议

**主线程文件统计与批量解析**

- [SettingsRoutes.kt:132](../app/src/main/java/com/mytv/mymusic/ui/settings/SettingsRoutes.kt:132) 和 [同文件:170](../app/src/main/java/com/mytv/mymusic/ui/settings/SettingsRoutes.kt:170) 在组合期遍历下载记录并读取文件长度。设置首页和双栏摘要也会执行，不限于存储子页。记忆缓存只避免相同记录的重复统计，首次进入和记录变化仍会同步访问磁盘。
- [MusicViewModel.kt:2292](../app/src/main/java/com/mytv/mymusic/ui/MusicViewModel.kt:2292) 的缓存大小查询会扫描汽水缓存目录。设置初始化、清理后的刷新，以及 [LibraryRoutes.kt:93](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryRoutes.kt:93) 的下载页组合均在主线程调用。清理操作本身已在 I/O 线程，但其后的大小刷新没有。
- [LibraryScreen.kt:248](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryScreen.kt:248) 和 [同文件:367](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryScreen.kt:367) 的“播放喜欢的歌”回调同步解析全部收藏。解析辅助函数默认每次新建 JSON 配置，见 [MusicViewModel.kt:2560](../app/src/main/java/com/mytv/mymusic/ui/MusicViewModel.kt:2560)。收藏数量增加时，按下播放到界面响应之间的主线程工作随之增加。

建议把文件统计移入后台 I/O 任务，将纯 JSON 解码移入后台 Default 任务，并复用解析器。保留清理、下载完成或删除后的刷新语义。音乐下载记录目前在开始、完成和删除时变化，不含逐字节进度，不能把上述统计描述成每个下载数据块都会触发。

**动态列表缺少逐行虚拟化**

| 位置 | 实际场景 | 建议 |
|---|---|---|
| [AppUi.kt:608](../app/src/main/java/com/mytv/mymusic/ui/AppUi.kt:608) | 合并候选来自聚合结果和播放队列，全部放进滚动 Column；选择变化还会重新遍历候选 | 使用有高度约束的 LazyColumn，逐项提供稳定 key 和 contentType |
| [AppUi.kt:536](../app/src/main/java/com/mytv/mymusic/ui/AppUi.kt:536) | 添加到自建歌单的选择列表随用户歌单数量增长，打开时全量组合 | 同上，保留选择和关闭行为 |
| [LocalPlaylistScreen.kt:671](../app/src/main/java/com/mytv/mymusic/ui/library/LocalPlaylistScreen.kt:671) | 添加歌曲的搜索结果全部组合，包含封面、按钮和语义节点 | 将结果区域改为惰性列表，输入框继续保留在外层 |

添加歌曲使用平台单页搜索，不应称为无限分页列表。QQ 和网易当前请求上限为 10，Apple 请求上限为 30，因此这处建议取决于平台返回规模，优先级低于可持续增长的合并候选和自建歌单。普通少量按钮、平台选项和预览卡片的 Row/Column 不需要因此改成惰性列表。

**歌词、搜索和电视焦点更新扩大重组范围**

| 位置 | 触发与证据 | 建议 |
|---|---|---|
| [PlayerScreen.kt:131](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerScreen.kt:131) | 当前歌词行变化后重新构造 PlayerUi，并传给标题、收藏、控制和音源组件。它是普通 class，没有内容相等实现 | 把当前行状态留在歌词区域，其他组件接收实际使用的字段和回调 |
| [SearchScreen.kt:227](../app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:227) | 搜索页重组会新建 SearchCtx，并下传整包应用状态、查询和回调。输入变化或无关状态变化会使接收对象的组件失去跳过机会 | 拆开输入、搜索结果与播放标记的依赖，避免让整包上下文成为每行参数 |
| [ChartsScreen.kt:549](../app/src/main/java/com/mytv/mymusic/ui/charts/ChartsScreen.kt:549) | 电视卡片获取焦点后更新 focused；页面随后重新 map 榜单、推荐、热歌和历史，重建展示对象、文字及回调 | 按真实数据与动作依赖缓存列表，焦点只保存 key，头图区按 key 查找 |

已检查本次发布版编译产物：PlayerUi 每次执行父函数都会重新创建，接收它的组合函数存在参数比较。这说明这些函数会重新执行，不代表所有更深层节点都会重组。

播放进度已单独成流，并以 State 传递。当前歌词行也已通过 derivedStateOf 降频，不能误报为播放进度每次更新都重组应用根节点。不建议给包含歌词或队列的模型增加 Immutable、Stable 注解或改用不可变集合库。

**被完全遮挡或隐藏后仍保留动画与进度工作**

- [AppNavigation.kt:273](../app/src/main/java/com/mytv/mymusic/ui/AppNavigation.kt:273) 的平板底部播放条和常驻播放窗格没有按全屏播放器的显示状态停用。全屏播放器覆盖后，底层进度组件仍读取播放进度，波浪动画仍留在组合中。手机底栏已有隐藏门控，可参考其可见性处理，同时保留平板布局占位。
- [PlayerScreen.kt:302](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerScreen.kt:302) 的横屏歌词模式只把封面透明度降到零。封面仍接收播放中状态，内部 [呼吸动画](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:231) 没有停用。应在淡出完成后停止动画或移除不可见内容。
- [PlayerParts.kt:152](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:152) 在没有封面地址时仍可创建旋转动画，但没有图片读取角度。动画启用条件还应包含封面存在。

以上是可确认的无可见收益工作。队列和定时面板只是局部遮挡，背后的播放页仍可见，不能据此要求停掉全部歌词背景。律动分析已有暂停、生命周期和退出组合的关闭逻辑。

**骨架屏每次绘制重新分配渐变**

位置：[Progress.kt:183](../app/src/main/java/com/mytv/mymusic/ui/components/Progress.kt:183)。

Skeleton 已把动画值读取推迟到绘制阶段，这是正确的。但每次绘制都会创建渐变 Brush 和颜色停靠点，加载页会同时显示多个骨架块。每个块还独立持有无限动画，而这些块可以共享同一个流光进度。

建议在骨架组提升动画状态，通过 provider 在绘制阶段读取。缓存基础渐变，通过绘制变换或可复用 shader 实现位移。只把原代码外包一层 drawWithCache、仍在缓存块里读取逐帧变化的停靠点，不能消除逐帧重建。

**进度组件仍在组合阶段读取数值**

位置：[Progress.kt:80](../app/src/main/java/com/mytv/mymusic/ui/components/Progress.kt:80) 和 [PhoneBottomBar.kt:209](../app/src/main/java/com/mytv/mymusic/ui/adaptive/PhoneBottomBar.kt:209)。

波浪进度条先在组合期计算比例，再把比例捕获进 progress lambda。拖动数值变化和播放进度更新都会重新执行进度组件。迷你进度环也先读取 State，再传原始比例。

建议让 progress lambda 直接读取状态，将时间文字拆成独立小组件，并按显示秒数降频。无障碍语义继续保留当前的粗粒度播报规则。此问题的作用域是进度组件，不是迷你播放器或整个应用。

**列表构建重复做全量转换**

| 位置 | 可避免的工作 | 修正范围 |
|---|---|---|
| [SearchScreen.kt:800](../app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:800) | 每次构建结果列表重新 drop 和生成去重 key | 在调用惰性 DSL 的上层组合或 ViewModel 按结果列表缓存 |
| [SearchScreen.kt:819](../app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:819) | 折叠“其他版本”时仍创建全部配对列表并去重 | 按聚合结果缓存，必要时把计数与展开列表分开 |
| [SearchScreen.kt:1560](../app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:1560)、[同文件:1924](../app/src/main/java/com/mytv/mymusic/ui/search/SearchScreen.kt:1924) | 搜索集合列表重复生成 key，电视焦点变化还会重复查找和建表 | 缓存不随焦点变化的数据，头部单独处理焦点 |
| [CollectionScreen.kt:446](../app/src/main/java/com/mytv/mymusic/ui/CollectionScreen.kt:446) | 歌单详情每次构建列表重新生成全部歌曲 key | 以歌曲列表为输入缓存，保留重复歌曲消歧逻辑 |
| [AppNavigation.kt:330](../app/src/main/java/com/mytv/mymusic/ui/AppNavigation.kt:330) | 合并弹窗候选每次重组重新拼接、去重和过滤 | 按搜索结果、队列及当前曲目缓存 |
| [LargeScreen.kt:271](../app/src/main/java/com/mytv/mymusic/ui/adaptive/LargeScreen.kt:271) | 为显示少量后续歌曲，先复制队列剩余部分再截取 | 直接按索引取得所需范围，避免复制整段尾部 |

这些操作按数据规模增加成本，但并非每帧必然执行。普通 LazyListScope 辅助函数不是可组合函数，不能直接在其中添加 remember，缓存应放在调用方。

**歌词解析、历史分组和二维码首次计算仍占用组合线程**

- [PlayerScreen.kt:130](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerScreen.kt:130) 在歌词变化时同步执行正则匹配、逐字时间戳展开和排序。建议后台解析并按曲目或文本发布结果，防止快速切歌后旧结果覆盖新歌。[PlayerParts.kt:333](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:333) 的无时间戳歌词还可按原文缓存拆行结果。
- [LibraryCollections.kt:270](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryCollections.kt:270) 在历史列表变化时于主线程分组，每条记录调用 [dayLabel](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryScreen.kt:1179)，重复计算当天起点。可把日期边界算一次，并将大列表分组放进已有后台准备流程。不要把当前共享 SimpleDateFormat 直接拿到多个线程使用，应采用线程安全格式化器或任务私有实例。
- [QrCode.kt:22](../app/src/main/java/com/mytv/mymusic/ui/settings/QrCode.kt:22) 的 remember 缓存不能避开首次编码。关于页生成主页和群组二维码，登录面板在平台只提供链接时也执行编码。编码包含纠错、掩码评分和临时列表分配，建议在 Default 调度器生成矩阵。现有绘制已通过 drawWithCache 缓存 Path，保留该实现。

这组问题发生在数据到达或首次进入时，实际耗时随文本长度和设备变化，尚未做设备测量。

**更新进度带动不变信息重新格式化**

位置：[AboutScreen.kt:346](../app/src/main/java/com/mytv/mymusic/ui/settings/AboutScreen.kt:346)。

更新下载每次读取数据后发布进度，见 [UpdateRepository.kt:221](../app/src/main/java/com/mytv/mymusic/data/update/UpdateRepository.kt:221)。收到进度时，更新卡片会再次格式化不变的大小和发布日期，日期辅助函数又解析时间并创建格式化器。设置摘要也收集完整更新状态并重建摘要。

建议按更新元数据缓存文本，将进度读取限制在进度区域，并按必要频率发布更新。StateFlow 会合并部分更新，不能把数据块数量直接当作实际重组次数。

**次要清理项**

| 项目 | 位置与建议 |
|---|---|
| 拖动事件创建协程 | [封面手势](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:253) 与 [迷你播放器手势](../app/src/main/java/com/mytv/mymusic/ui/adaptive/PhoneBottomBar.kt:151) 每个拖动事件 launch 后 snapTo。可直接累积拖动状态，结束时再启动回弹动画，确保手势中断会取消旧回弹 |
| 静态绘制资源重建 | [电视头部](../app/src/main/java/com/mytv/mymusic/ui/charts/ChartsScreen.kt:682) 与 [详情背景](../app/src/main/java/com/mytv/mymusic/ui/CollectionScreen.kt:257) 在绘制回调创建渐变。先缓存与尺寸、静态颜色有关的资源；动画颜色仍需更新，不能承诺换成 drawWithCache 就没有逐帧成本 |
| 焦点轮廓重建 | [Focus.kt:94](../app/src/main/java/com/mytv/mymusic/ui/components/Focus.kt:94) 在缩放绘制中重新创建不随缩放改变的轮廓与 Stroke。可按形状、尺寸、密度和布局方向缓存，复杂轮廓比简单圆角更值得优化 |
| 惰性项中的后台转换 | [LibraryScreen.kt:491](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryScreen.kt:491) 在“最近播放”惰性项里解码历史。该项被回收再进入时可能重做。可提升到页面层；它不是逐项共享 Flow 收集，也不是错误的曝光统计 |
| 小型重复转换 | [下载分组](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryCollections.kt:415)、[收藏摘要](../app/src/main/java/com/mytv/mymusic/ui/library/LibraryCollections.kt:133)、[分类筛选](../app/src/main/java/com/mytv/mymusic/ui/playlists/PlaylistsScreen.kt:399)、[收藏判断](../app/src/main/java/com/mytv/mymusic/ui/CollectionScreen.kt:125) 可按真实输入缓存。不为直接读取 size 创建派生状态 |
| 非挂起效果 | [律动重置](../app/src/main/java/com/mytv/mymusic/ui/player/LyricsBackdrop.kt:80)、[歌词拖动时间](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:338)、[扫码入口](../app/src/main/java/com/mytv/mymusic/ui/settings/SettingsSubScreens.kt:958) 可在保留执行顺序后改为带 key 的 SideEffect。负责取消轮询的 DisposableEffect 保留 |
| 记忆缓存与长寿命捕获 | [动态背景图片请求](../app/src/main/java/com/mytv/mymusic/ui/player/PlayerParts.kt:151) 和 [律动背景图片请求](../app/src/main/java/com/mytv/mymusic/ui/player/LyricsBackdrop.kt:84) 捕获 context，应一并作为 key。手势的密度阈值应通过最新状态读取，或在阈值变化时重启手势 |

多数列表的数据项已经有稳定 key 和 contentType，但单独声明的标题、错误、骨架、卡片等异构项常只声明 key。应按结构补充 contentType。涉及榜单、歌单、搜索、详情、音乐库和设置页。默认类型为 null，同为 null 的条目仍可复用，不能称为“完全无法复用”。

**性能测试可能没有执行到目标场景**

位置：[Journeys.kt:20](../baselineprofile/src/main/java/com/mytv/mymusic/baselineprofile/Journeys.kt:20)。

- 页面等待结果没有断言。已核对 UIAutomator 的本地二进制，超时返回条件最后结果，不自动报错。
- [滚动函数](../baselineprofile/src/main/java/com/mytv/mymusic/baselineprofile/Journeys.kt:44) 找不到列表就返回，并吞掉滚动异常。
- [开始播放](../baselineprofile/src/main/java/com/mytv/mymusic/baselineprofile/Journeys.kt:56) 使用可空点击，找不到按钮时继续执行。
- [打开播放页](../baselineprofile/src/main/java/com/mytv/mymusic/baselineprofile/Journeys.kt:83) 找不到迷你播放器就返回。
- [入口切换](../baselineprofile/src/main/java/com/mytv/mymusic/baselineprofile/Journeys.kt:93) 失败后继续其他入口。

应断言页面和关键节点就绪，并验证滚动、播放页进入与退出。失效节点可有限重试，耗尽后应失败并保留诊断。帧时间指标不能替代操作成功断言，不能仅因采集任务结束就宣称目标路径已覆盖。

## 覆盖、排除项与验证结果

**基础配置已满足**

| 项目 | 核验结果 |
|---|---|
| Kotlin | 2.4.20，未禁用 Strong Skipping |
| Compose Runtime / UI / Foundation | 1.12.1，离线 Gradle 实际解析核实 |
| Material 3 | 固定 1.5.0-alpha25 |
| 发布构建 | 已开启 R8 和资源压缩 |
| Baseline / Startup Profile | 已有生成模块、安装器和规则；发布任务链包含合并与编译 |
| API 兼容性 | 实际运行时支持带 key 的 SideEffect，不含 computedStateOf |

构建配置见 [app/build.gradle.kts](../app/build.gradle.kts:50) 和 [版本目录](../gradle/libs.versions.toml:1)。本次只核对任务链并编译 Kotlin，没有重新生成发布 APK。

**覆盖清单**

| 范围 | 已读文件 |
|---|---|
| 播放器 | PlayerScreen、PlayerParts、PlayerSheets、LyricsBackdrop |
| 设置 | SettingsCommon、SettingsRoutes、SettingsScreen、SettingsSubScreens、AboutScreen、EqualizerScreen、LyricsSettingsScreen、QrCode |
| 浏览与搜索 | ChartsScreen、DiscoverRoutes、PlaylistsScreen、SearchScreen、SearchRoutes、VoiceSearch、CollectionScreen |
| 音乐库与换源 | LibraryRoutes、LibraryScreen、LibraryCollections、LocalPlaylistScreen、SyncScreens、SourcePickerSheet |
| 应用外壳 | MainActivity、AppNavigation、AppUi、UiStates |
| 公共组件 | SharedCover、Scaffolds、Progress、Badges、Sheets、Buttons、Focus、Lists、Window |
| 自适应与主题 | PhoneBottomBar、LargeScreen、TvNavRail、CoverSeedRepository、Appearance、Theme、BlurPolicy |

另追踪 LyricsParser、RhythmAnalyzer、RhythmAnalysis、PlaybackService、相关 ViewModel/数据库/更新路径和性能测试脚本。审计不等于全面审查所有平台协议、第三方库内部实现或网络行为。

| 技能类别 | 结论 |
|---|---|
| 状态、快照与稳定性 | 确认整包参数重建与进度读取阶段问题；未发现已验证的无穷反向写循环 |
| 布局与测量 | 核对约束布局及 intrinsic 用途；没有把合理的响应式布局当作缺陷 |
| 惰性布局 | 确认动态选择列表全量组合及异构 contentType 缺口；数据行 key 大多已提供 |
| 动画与共享转场 | 确认隐藏动画和被全屏遮挡的底层播放器仍活动；主要动画值已延迟读取 |
| 效果、生命周期与线程 | 确认主线程 I/O、解析及部分效果粒度问题；没有把所有内存 StateFlow 一概标成泄漏 |
| 图形与绘制 | 确认骨架渐变分配；新律动背景的画刷与频谱读取阶段正确 |
| Modifier.Node | TV 焦点已采用节点实现；仅提出轮廓缓存，不要求重复迁移 |

**复核后未纳入问题的建议**

- 榜首预览已在 [MusicViewModel.kt:385](../app/src/main/java/com/mytv/mymusic/ui/MusicViewModel.kt:385) 截为少量歌曲。平台热歌请求也有小型数量限制，不列为无界长列表问题。
- 均衡器的尺寸状态仅供手势读取，不构成布局到组合的反向写。
- 电视默认焦点等待下一帧，具有节点挂载时序要求，不能机械替换为同步效果。
- 音乐库磁贴的 IntrinsicSize 用于让左右区域等高并兼容大字号，没有深层嵌套链证据，不要求改固定高度。
- MaterialShapes.toShape 已有内部缓存，不因它出现在组合函数中就重复建议 remember。
- 现有设置摘要集合很小，其他稳定性注解用于平面配置或状态持有器，没有满足移除条件的大型惰性集合模型。
- 图片封面使用 AsyncImage，二维码 data 图片在后台解码。现有本地图像绘制不需要一律改为网络图片加载。
- 律动频谱只在 graphicsLayer 和绘制回调读取。画刷按尺寸缓存，分析在线程池执行且限制排队；没有源码证据表明它逐帧重组整个播放器。

**本次执行结果**

| 验证 | 结果 |
|---|---|
| 发布版 Kotlin 编译 | 通过 |
| 单元测试强制重跑 | 57 用例，12 套件，全部通过，无跳过 |
| 发布版 Android Lint | 通过；0 error，51 warning，1 hint |
| 代码空白检查 | 通过 |
| 本次设备帧时间 / 分配测试 | 未执行 |

验证命令：

```bash
./gradlew.bat :app:compileReleaseKotlin :app:testDebugUnitTest --offline --console=plain
```

```bash
./gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain
```

```bash
./gradlew.bat :app:lintRelease --offline --console=plain
```

最初尝试的发布版单元测试任务不存在，已核对任务清单并使用以上组合。发布版编译仍有已有弃用与空值警告。Lint 报告见 [文本结果](../app/build/reports/lint-results-release.txt)，其中资源、API 和样式警告没有全部转为 Compose 性能问题。

本地已有发布 APK 与基准 APK 早于本次歌词背景改动，已有基准结果也没有覆盖歌词背景模式切换。现存 profile 中没有新增背景与节奏分析类的规则，这不表示功能不能运行，只说明旧产物不能证明当前改动的性能。历史测量见 [performance.md](performance.md)，不作为本次新测量。

应用修复后，需要用新构建的非 debuggable、开启 R8 的包验证电视焦点移动、长列表首次打开、歌词换行、骨架加载、隐藏播放器和歌词背景运行/暂停。先修正场景成功断言，再比较同设备、同内容和同渲染设置的帧时间与分配。
