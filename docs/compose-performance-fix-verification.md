# Compose 性能修复与验证

实施与验证：2026-10-25 至 2026-10-26。基于 [原审计](compose-performance-audit-2026-10-25.md) 修复全部确认项，保留既有歌词背景等未提交改动，没有提交代码或切换分支。HEAD 保持 `6efee267415cbc06514d7693224d776c000a0c96`。

## 修复与测试结果

逐项位置和验证状态见 [修复清单](compose-performance-repair-checklist.md)。修复覆盖后台文件统计与解析、动态选择列表惰性化、播放器和搜索状态依赖拆分、隐藏动画停用、绘制资源缓存、进度隔离、手势回弹及旅程成功断言。

集成复核发现的回归也已修正：链接解析忙碌字段、电视头图渐变合成、下载异步结果不一致、宽屏双击收藏、歌词解析占位和常驻窗格滚动恢复。没有引入集合稳定性注解、不可变集合库或新的依赖注入框架。

| 验证项目 | 结果 |
|---|---|
| Debug 应用 / AndroidTest 构建 | 通过 |
| Release Kotlin / R8 / APK 构建 | 通过 |
| 单元测试 | 90 用例，19 套件，全部通过，无跳过 |
| 手机 Compose 行为测试 | 15 用例，全部通过 |
| 手机 Release 旅程 | 2 用例，最终共享导航脚本重跑通过 |
| 独立电视 Release 旅程 | 2 用例，全部通过，包含全部标签入口断言 |
| 帧时间测量（模拟器） | 已执行完整场景与三轮交替复测；环境漂移不可归因，见[测量报告](compose-performance-measurement-2026-10-26.md) |
| Release Lint | 0 Error，52 Warning，1 Hint |
| git diff --check | 通过 |

单元测试最终执行时间为 `2026-10-25T16:05:03Z` 至 `16:05:05Z`。沿用项目现有 JUnit4、协程测试和 Compose 仪器测试依赖。组件测试使用 ComponentActivity 和合成数据，覆盖：

- 长列表合并候选、歌单选择、添加歌曲，以及屏外选择和保存恢复。
- 播放进度的粗粒度无障碍语义与 seek。
- 歌词跟随、点击跳转、快速换文后仅发布最新解析结果。
- 封面手势最新回调、宽屏双击收藏、拖动取消和回弹。
- 隐藏平板播放器移除进度节点，恢复后显示正常，常驻窗格保留滚动位置。
- 更新百分比刷新、链接解析与歌曲搜索忙碌状态隔离。
- 搜索其他版本展开状态在刷新及保存恢复后保留，内容更新。
- 电视布局卡片保持焦点，头图使用同一 key 的最新数据。
- 后台二维码编码与实际绘制，原有矩阵 SHA-256 回归。

首轮组件测试的电视焦点用例因手机宿主处于触摸模式失败。补齐键盘输入模式后，全套重跑通过，没有跳过失败用例。

## 实际设备行为与旅程

使用独立模拟器顺序验证，未操作其他会话的设备或重启共享 ADB。

| 设备 | 环境 | 验证 |
|---|---|---|
| 手机 emulator-5584 | Android 14，x86_64，720×1600 | 榜单、播放、迷你播放器、歌单、音乐库、搜索、设置和缓存清理 |
| 电视 emulator-5586 | Android 13，x86，1280×720，Leanback | 方向键焦点、头图、播放页、全部标签、设置及律动背景选择 |

两台设备均实际安装开启 R8 的非 debuggable Release 包，包标志没有 DEBUGGABLE。发布包包含 `baseline.prof` 和 `baseline.profm`。本次本地构建使用项目既有回退配置，以 debug 证书签名；没有修改签名配置。

**手机实际走查**

- 启动应用，打开榜单并开始播放，返回后迷你播放器正常显示。
- 进入音乐库和设置，后台缓存统计正常显示。
- 清除本次测试产生的播放缓存后，占用从 15.6 MB 变为 0 B，页头、总量与明细同时更新。
- 完整旅程验证榜单滚动、播放页进入/退出、迷你播放器恢复，以及歌单/我的/搜索/榜单入口。

**电视实际走查**

- 默认播放按钮获得焦点，方向键可进入导航、设置和歌词选项，右栏内容随焦点更新。
- 用遥控器选择律动背景，设置摘要同步更新，既有律动功能保留。
- 播放页歌词与控制画面已截图核对，媒体会话确认播放位置推进及队列存在。
- 点击暂停按钮后，按钮语义变为“播放”，媒体会话为暂停且速度为 0。
- 单独发送媒体暂停键没有改变播放状态，不把该按键尝试计作通过。
- 最终自动旅程已覆盖方向键移动、播放页进入/退出、歌单/我的/搜索/榜单全部入口和失败诊断。

**旅程断言修正**

此前会静默跳过的路径现在必须断言成功，失败保存 XML 和 PNG。实际测试过程中修正了滚动手势穿过顶栏、播放入口离屏、搜索键盘遮挡导航，以及电视焦点节点刷新失效的问题。

电视焦点改为读取系统一次性输入焦点快照，保留有限重试。头图的“播放全部”和“播放”使用对应语义定位。最终复核发现旧脚本直接跳过电视标签，已取消跳过并整套重跑，各入口均有成功断言日志。

手机和电视负向用例都验证了缺少应用窗口时必须失败，并确实保存页面层级与截图。两台设备的崩溃缓冲区均为空。实际应用走查独立于组件测试，组件测试不代替运行应用。

## 复现、证据与边界

环境使用 Android Studio 自带 JDK、现有 Android SDK 和离线 Gradle 缓存。构建及单元测试：

```bash
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :baselineprofile:assembleBenchmarkRelease :app:assembleRelease :app:lintRelease --offline --console=plain --max-workers=2
```

安装 Debug 应用与 AndroidTest APK 后执行组件测试：

```bash
adb -s emulator-5584 shell am instrument -w -r -e class com.mytv.mymusic.ComposePerformanceBehaviorTest com.mytv.mymusic.test/androidx.test.runner.AndroidJUnitRunner
```

安装 Release 应用与 baselineprofile 测试 APK 后执行关键旅程，手机使用其专用串号：

```bash
adb -s emulator-5586 shell am instrument -w -r -e class com.mytv.mymusic.baselineprofile.JourneyBehaviorTest com.mytv.mymusic.baselineprofile/androidx.test.runner.AndroidJUnitRunner
```

ADB instrumentation 即使用例失败也可能退出为 0，必须检查测试输出。本次辅助执行器要求出现 `OK (N tests)`，失败记录不会算作通过。

证据见 [验证索引](../build/compose-performance-verification-20261025/evidence-index.md)，包括构建日志、单测 XML、组件与旅程输出、校验值、设备标志、截图及失败诊断。[设备截图总览](../build/compose-performance-verification-20261025/device-verification-overview.png) 展示已核对的实际画面。产物和原始日志保存在忽略的 build 目录，清理构建目录会删除它们。

帧时间测量已补充执行：`FrameBenchmarks` 完整跑了修复前、修复后及重复修复后三轮，另有 `FocusFrameBenchmarks` 的生产编译模式交替复测。**同一修复后 APK 的榜单滚动中位帧时间从 86 ms 漂移到 214–237 ms，差异大于修复前后差异；共享主机上的其他模拟器使瞬时 CPU 负载在 2%–99% 间波动。**因此本轮时序数据不能归因于代码改动，报告不作性能提升或回退声明。详细数据、方法和限制见[帧时间测量报告](compose-performance-measurement-2026-10-26.md)。

未做分配计数或 Perfetto 组合/分配归因；未重新采集 Baseline Profile。发布包沿用并成功合并原有 profile，不能据此声称覆盖新增歌词背景的最新采集结果，也不从 Debug 行为测试推算性能提升。若需要可信性能结论，应在物理设备或空闲主机上用同一脚本复测。

本次未修改版本号或依赖。原有律动分析、播放服务和第三方说明文件与修复前快照一致，其余交叠文件在原改动上实施修复。全部改动保持未提交。
