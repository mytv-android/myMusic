# 性能：Baseline Profile 与帧时间

## Baseline Profile

- 模块：`baselineprofile/`（`com.android.test` + `androidx.baselineprofile` 1.5.0，自带被测应用的启动方式）。应用侧加了 `androidx.profileinstaller`，`baselineProfile(project(":baselineprofile"))`。
- 采集路径（`BaselineProfileGenerator`）：
  - `startup`：冷启动到榜单首屏数据出现，单独生成 Startup Profile，供 R8 优化 dex 启动布局；
  - `criticalJourneys`：冷启动 → 榜单上下甩动 → “播放全部”→ 收起到迷你播放器 → 从迷你播放器打开播放页（封面共享元素转场）→ 歌单 / 我的 / 搜索 / 榜单各滚一下。
- 产物：`app/src/release/generated/baselineProfiles/baseline-prof.txt`（约 4.4 万条规则，其中本应用约 3300 条）和 `startup-prof.txt`。随 release 构建打包，安装后由 profileinstaller 触发预编译。
- 重新生成（API 33+ 或已 root 的设备 / 模拟器）：

```bash
ANDROID_SERIAL=<设备> ./gradlew :app:generateReleaseBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.baselineprofile.BaselineProfileGenerator
```

界面改动较大后重新生成一次；规则对不上的方法只是不被预编译，不会出错。

## 帧时间测试

`FrameBenchmarks` 用 Macrobenchmark 测三条关键路径，每条都对比“不预编译（`CompilationMode.None`）”和“Baseline Profile（`Partial(Require)`）”，各 5 轮：

| 路径 | 手机 | 电视 |
|---|---|---|
| 冷启动 `coldStartup` | 启动到榜单数据出现 | 同左 |
| 榜单滚动 `chartsScroll` | 竖向列表上下各甩 3 次 | 方向键下移 4 行再上移 4 行 |
| 打开播放页 `openPlayer` | 从迷你播放器点开、停 1.2 秒、返回 | “播放全部”进入全屏播放页、返回 |

```bash
ANDROID_SERIAL=<设备> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mytv.mymusic.baselineprofile.FrameBenchmarks
```

结果在 `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/<设备>/*-benchmarkData.json`。

### 测试设备

都是模拟器，没有真机。模块里已设置 `androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY`，否则 Macrobenchmark 拒绝在模拟器上出数。模拟器的绝对数值不代表真机，只看同一台、同一时段内的相对变化。

- **低端手机**：自建 AVD `Phone_LowEnd`，Android 14，2 核，2GB 内存，720×1600。软件渲染（SwiftShader）在跑测试时崩溃，改用宿主 GPU。
- **电视盒子**：AVD `TV33_OOM`，Android TV 13，4 核，2GB 内存，720p。

### 电视盒子结果（TV33_OOM）

| 指标 | 不预编译 | Baseline Profile |
|---|---|---|
| 冷启动 timeToInitialDisplay 中位数 | 1562 ms | **800 ms** |
| 冷启动 frameOverrun P90 | 336 ms | **36 ms** |
| 榜单滚动 frameDurationCpu P50 / P90 | 19.0 / 32.8 ms | 18.8 / 34.2 ms |
| 打开播放页 frameDurationCpu P50 / P90 | 21.0 / 33.8 ms | 19.4 / 33.7 ms |

- Baseline Profile 让冷启动到首帧快了约一半，启动阶段的长帧基本消失。
- 滚动和打开播放页的稳态帧时间两种模式差别不大（JIT 预热后代码已编译），P50 在 19–21ms，略高于 60Hz 的 16.7ms 预算；P99 偶尔出现 100–300ms 的长帧。用 Perfetto trace（`trace_processor_shell` 查主线程最长的 `Choreographer#doFrame` 和同时段的 RenderThread）定位：最长的一帧 279ms 里，主线程 278ms 在 `postAndWait` 等 RenderThread，RenderThread 有 268ms 卡在 `eglSwapBuffersWithDamageKHR → rcCreateSyncKHR encode`，也就是模拟器把 GL 命令转发给宿主 GPU 时的同步等待，不是应用自身的工作。真机上不会出现这类长帧。
- 打开播放页期间最长的应用侧帧约 83ms，是播放页首次组合（`Compose:recompose` 73ms）；这一帧只发生一次，之后稳定在 19–21ms。

### 低端手机结果（Phone_LowEnd，宿主空闲时段）

| 指标 | 不预编译 | Baseline Profile |
|---|---|---|
| 冷启动 timeToInitialDisplay 中位数 | 1579 ms | **1089 ms** |
| 冷启动 frameOverrun P90 | 909 ms | **195 ms** |
| 打开播放页 frameDurationCpu P50 / P99 | 30.8 / 261.5 ms | 33.6 / **108.2** ms |
| 榜单滚动 frameDurationCpu P50 / P90 | 32.8 / 49.8 ms | 35.4 / 77.6 ms |

- 冷启动到首帧快约 31%，启动期的长帧明显减少；打开播放页（含封面共享元素转场和首次组合）的 P99 长帧从 262ms 降到 108ms。
- 稳态滚动两种模式在误差范围内（Baseline Profile 一轮的 P90 偏高，同组冷启动里也出现过一次 9.2 秒的离群值，属于模拟器抖动）。
- 2 核模拟器上帧时间 P50 约 30–35ms。trace 显示最长的几帧都是主线程 `postAndWait` 等 RenderThread，RenderThread 卡在 `eglSwapBuffersWithDamageKHR → rcCreateSyncKHR encode`（模拟器 GL 转发），应用主线程的组合、测量、布局不在长帧里。RenderThread 每帧本身的绘制约 25ms（滚动测试时没有在播放，屏上只有导航栏一个模糊层；模糊占多少没有单独测）。如果真机低端机上也偏高，可以把 BlurPolicy 的“自动”扩展到按 CPU 核数 / 性能等级关闭模糊，再对比一次。

### 测试过程中的干扰

- 同一台宿主上另一个会话在并行跑性能测试，期间测得的低端手机数据（Baseline Profile 反而更慢）不可比，已作废；上表是双方约定的安静时段重测的结果。
- SwiftShader 软件渲染的 AVD 在测试中崩溃（exit 139）；内存紧张时 Claude Code 会回收模拟器进程。
- 一次测试被设备上的 `adbd restarting as root` 打断，来源未查明，重跑后正常。
