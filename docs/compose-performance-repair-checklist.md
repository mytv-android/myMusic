# 性能审计修复清单

对应 [原审计](compose-performance-audit-2026-10-25.md) 的全部确认项。原始工作区快照保存在 `build/compose-performance-before-20261025-222420`，没有提交代码。构建、测试与设备证据见 [验证报告](compose-performance-fix-verification.md)。

| 审计项 | 修复位置 | 实现与验证状态 |
|---|---|---|
| 设置/下载页面同步文件统计 | SettingsRoutes、LibraryRoutes、MusicViewModel | 已移到 IO；下载数据整体发布；单测通过，发布包实测清除缓存后占用从 15.6 MB 刷新到 0 B |
| 收藏批量 JSON 解码 | MusicViewModel、MusicActions、LibraryScreen | 后台解析完整队列，共用 JSON 实例；两个收藏播放入口均已迁移，编译通过，独立复核未发现残留同步入口 |
| 合并候选/歌单选择/添加歌曲全量组合 | AppUi、LocalPlaylistScreen | 有界 LazyColumn、key 与 contentType；长列表滚动选中、保存恢复和已添加状态测试通过 |
| PlayerUi 和 SearchCtx 整包传播 | PlayerScreen、SearchScreen | 拆分播放器参数、搜索字段依赖；搜索展开刷新、恢复与链接 busy 状态测试通过 |
| TV 榜单焦点重建全页数据 | ChartsScreen、ChartsHero | 列表按数据缓存，焦点仅保存 key；最新对象查找单测、TV 布局焦点保持测试及独立电视 Release 方向键旅程均通过 |
| 隐藏播放器/封面仍运行 | AppNavigation、LargeScreen、PlayerParts、PlayerScreen | 隐藏时移除动态子树并保留占位、窗格滚动；进度节点隐藏/恢复、滚动恢复测试通过 |
| 骨架渐变每帧分配与独立时钟 | Progress | 缓存基础渐变、绘制期平移，SkeletonRows 共享动画状态；编译通过 |
| 进度组合期读取与语义降频 | Progress、PhoneBottomBar | 数值在绘制 provider 读取，时间独立降频；粗粒度语义与 seek 测试通过 |
| 搜索/详情/合并候选/队列派生数据 | SearchResultCaches、CollectionScreen、AppNavigation、LargeScreen | 按输入缓存、直接索引队列；重复 key、其他版本去重与结果刷新测试通过 |
| 歌词/历史/二维码首次主线程计算 | PlayerScreen、LibraryDates、QrCode | 后台准备，旧歌词保留到新结果完成；快切歌词、日期分组、二维码矩阵与绘制测试通过 |
| 更新进度带动静态信息 | UpdateRepository、SettingsRoutes、AboutScreen | 进度限频、元数据独立流、静态格式缓存；限频与进度标签刷新测试通过 |
| 手势逐事件启动协程 | PlayerParts、PhoneBottomBar | 直接积累偏移，只在结束时回弹；取消/回弹单测、最新回调与宽屏双击收藏测试通过 |
| 静态绘制资源与焦点轮廓 | ChartsScreen、CollectionScreen、Focus | 静态画刷和轮廓缓存；TV 头图保留原单层颜色插值，颜色变化时重建该渐变；编译通过 |
| 惰性最近播放项后台转换 | LibraryScreen | 解码提升到页面层；音乐库实际页面显示正常 |
| 小型重复转换 | LibraryCollections、PlaylistsFilters、CollectionScreen | 收藏摘要、分类过滤、收藏判断与下载分组缓存；相关单测通过 |
| 同步效果与记忆缓存 keys | PlayerParts、LyricsBackdrop、SettingsSubScreens | 使用带 key 的 SideEffect，保留轮询取消和焦点挂载时序；图片请求包含 context；编译通过 |
| 异构惰性条目 contentType | 浏览、搜索、详情、库、设置 | 已补齐，源码检查未发现单行 singleton item 缺口 |
| 性能旅程静默失败 | baselineprofile/Journeys | 等待/滚动/播放/返回有断言，失败保存 XML/PNG；手机完整旅程与电视整套旅程均通过；电视已补齐歌单/我的/搜索/榜单入口断言，无静默跳过 |

本表记录行为和源码核验，不代表已测得帧时间或分配改善。

## 帧时间测量

已用同一专用手机、同一份基准脚本对比修复前后，并重复测量同一修复后 APK。完整场景和交替复测都显示：同版本随时间从约 86 ms 漂移到 214–237 ms，超过修复前后差异；主机负载受其他模拟器影响在 2%–99% 间波动。因此测量已执行，但当前共享模拟器环境不足以归因，报告不声称性能提升或回退。原始 JSON、trace、脚本和限制见[帧时间测量报告](compose-performance-measurement-2026-10-26.md)。

测量过程中新增仅测试用的 `FocusFrameBenchmarks`，并改进 `Journeys` 的页面/内容判定与电视卡片行等待；应用源码未因测量改动。
