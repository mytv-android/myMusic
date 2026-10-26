# Compose 性能修复帧时间测量

测量日期：2026-10-26。目标是回答“修复是否带来可测量的性能改善”，而不只是验证功能。结论先行：**本轮测量执行成功，但共享主机/模拟器环境的漂移大于修复差异，因此不能据此声称性能提升或回退；以下原始数据如实保留，供安静环境或真机复测。**

## 方法

- 设备：独立模拟器 `MyMusic_Performance_Phone`（emulator-5584），Android 14 x86_64，720×1600，2 核，1536 MB，host GPU。
- 构建：`benchmarkRelease`，R8 压缩、非 Debug；与发布包同源。
- 测试代码：`FrameBenchmarks`（冷启动、榜单滚动、打开播放页；各 5 次迭代；`CompilationMode.None` 与 `Partial(BaselineProfile)`），以及为交替复测新增的 `FocusFrameBenchmarks`（只跑 `Partial(BaselineProfile)` 的榜单滚动）。
- 变体：
  - 修复后：当前工作区应用源码。
  - 修复前：`build/perf-before-workspace`，其 `app/src` 与修复前快照 `build/compose-performance-before-20261025-222420/app/src` 逐字节一致；两套变体共用同一份 `baselineprofile` 测试代码。
- 条件：`window_animation_scale=0`、`transition_animation_scale=0`、`animator_duration_scale=0`；每轮测试前停止应用并清空设备侧旧 trace；构建与测量分离，直接用 `am instrument` 启动测试，避免 Gradle 进程在测量期间占用主机。
- 指标：Macrobenchmark `FrameTimingMetric`（`frameDurationCpuMs`、`frameOverrunMs`、`frameCount`）和冷启动 `timeToInitialDisplayMs`。

测量过程中额外修正了测试脚本的页面/内容判定：导航回归只要求榜单页面打开；基准测量要求榜单内容出现；电视方向键前等待可聚焦卡片行。应用源码未因此改变。

## 完整场景结果

同一设备顺序运行：修复前（6/6 通过）→ 修复后第一轮（6/6 通过）→ 修复后第二轮（5/6 通过，`openPlayer[BaselineProfile]` 在榜单内容超时的 setup 阶段失败）。生产模式（Baseline Profile）关键值：

| 场景 | 指标 | 修复前 | 修复后第一轮 | 修复后第二轮 |
|---|---|---:|---:|---:|
| 冷启动 | 启动到首帧中位数 (ms) | 1567 | 1558 | 4293 |
| 冷启动 | 帧 CPU P50 (ms) | 115.9 | 98.7 | 149.1 |
| 榜单滚动 | 帧 CPU P50 (ms) | 136.8 | 86.4 | 213.7 |
| 榜单滚动 | 帧 CPU P90 (ms) | 817.5 | 214.5 | 375.5 |
| 打开播放页 | 帧 CPU P50 (ms) | 52.3 | 80.5 | 未产出 |

`None` 模式同样波动：榜单滚动 P50 修复前 106.5、修复后第一轮 67.2、第二轮 153.6；打开播放页 P50 修复前 62.2、修复后第一轮 23.1、第二轮 99.5。

**同一修复后 APK 的榜单滚动 P50 从第一轮 86.4 ms 升到第二轮 213.7 ms，而修复前为 136.8 ms。** 这种同版本漂移已经覆盖了任何修复差异，因此上表不能解释为“修复有效”或“修复有害”。

## 交替复测

为减弱顺序偏差，随后在同一设备按“修复后 → 修复前 → 修复后”连续运行 `FocusFrameBenchmarks`（仅生产编译模式，榜单滚动，各 5 次迭代）：

| 顺序 | 变体 | 帧 CPU P50 (ms) | P90 (ms) | 每轮迭代 P50 (ms) |
|---|---|---:|---:|---|
| 1 | 修复后 | 237.2 | 621.8 | 283.9 / 312.2 / 242.4 / 269.2 / 191.5 |
| 2 | 修复前 | 231.0 | 394.9 | 339.8 / 211.1 / 233.0 / 203.6 / 223.4 |
| 3 | 修复后 | 228.7 | 477.2 | 244.2 / 216.1 / 256.1 / 189.7 / 224.5 |

三轮中位帧时间差异在约 4% 以内，修复前版本落在两次修复后结果之间，方向不稳定。交替序列没有显示修复后的可重复优势。

## 为什么不能据此得出结论

- 主机同时存在其他来源的模拟器进程（测量期间观察到多个 `qemu-system-*`），系统瞬时 CPU 负载在 2%–99% 之间变化；本机不是受控测量环境。
- 同一版本、同一测试在约一小时内从 86 ms 漂移到 214–237 ms，说明漂移来自环境或模拟器状态，而不是代码差异。
- 第二轮完整测试还出现榜单数据未在超时内到齐的 setup 失败，进一步表明网络/数据状态不稳定。
- 模拟器绝对帧时间本身不代表真机；项目也明确标注 EMULATOR 结果只可做相对比较。

## 结论与后续

- 已建立可重复的帧时间测量工具与脚本（`FrameBenchmarks`、`FocusFrameBenchmarks`、`JourneyBehaviorTest`、直接 adb 运行脚本），并保留三轮完整场景与三轮交替滚动的原始 JSON、trace 和解析结果。
- 在本机共享模拟器上，修复前后的帧时间差异无法与噪声区分；**不作性能提升或回退声明**。此前报告中“未测量”的缺口现已补上，但测量结论是“环境不足，需受控复测”。
- 可信复测应在物理设备或空闲主机上进行；命令与参数已固定在验证报告和 `baselineprofile` 注释中。若需要更细的分配/组合证据，应在真机上增加 Perfetto/Compose 重组计数，而不是从本轮模拟器时序推断。

原始数据目录（build 下，忽略提交）：

- `build/compose-performance-verification-20261025/measure-before/`
- `build/compose-performance-verification-20261025/measure-after/`（第一轮）
- `build/compose-performance-verification-20261025/measure-after2/`（第二轮）
- `build/compose-performance-verification-20261025/measure-focus-after1/`、`measure-focus-before/`、`measure-focus-after2/`
- 对应 `*-summary.txt`、`compare-before-after-first.txt`、`measurement-settings.txt`。

可直接复用的运行/解析脚本已复制到 [measurement-scripts](measurement-scripts/)（`run_benchmark.py`、`parse_benchmarks.py`、`compare_benchmarks.py`、`run_instrumentation.py`）。脚本依赖本机 `LOCALAPPDATA` 下的 adb 路径；在真机或空闲主机上运行时只需替换串号和 APK 路径。
