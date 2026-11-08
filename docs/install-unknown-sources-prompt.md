# 应用内更新装不上：`canRequestPackageInstalls()` 陷阱

给其他 Android 应用做体检时用的提示词。直接整段复制给模型即可。

---

## 提示词

> 帮我检查这个 Android 项目的应用内更新（自动升级）功能，重点排查下面这个在电视盒子 / 定制 ROM 上非常常见的坑。
>
> **现象**：应用下载完新版本 APK、SHA-256 校验也通过了，却弹出一句
> `无法打开安装权限设置：No Activity found to handle Intent { act=android.settings.MANAGE_UNKNOWN_APP_SOURCES dat=package:... }`，
> 用户点了「允许安装」什么都不会发生，更新从此卡死。有的设备上系统安装器明明能装第三方应用，应用自己却认定没有权限。
>
> **根因**：代码把 `PackageManager.canRequestPackageInstalls()` 当成了发起安装的**前置门槛**。这个接口在部分定制 ROM（尤其是电视盒子）上恒返回 `false`，但系统安装器其实完全可用；于是应用跳过安装，转头去打开「安装未知应用」设置页，而这些 ROM 根本没有这个页面 → `ActivityNotFoundException`。
>
> 请按下面的顺序排查并修复，**不要**只加个 try/catch 就交差。
>
> ### 1. 找出所有把 `canRequestPackageInstalls()` 当门槛的地方
>
> 全局搜索 `canRequestPackageInstalls`，逐个确认它是不是在**阻止**安装 / 阻止拉起安装器。典型坏味道有三种，都要改：
>
> - 安装入口 `if (!pm.canRequestPackageInstalls()) return NeedsPermission` —— 直接砍掉这个分支；
> - 下载完成后的自动安装回调里再判断一次 —— 同样砍掉；
> - Activity 回到前台（`ON_RESUME`）时用 `canRequestPackageInstalls()` 决定要不要续装 —— 改成无条件重试一次，否则用户在设置页和弹窗之间来回打转。
>
> 判断权限应该是**安装器自己**的事：让 `startActivity` 的结果说话，而不是替系统提前做决定。
>
> ### 2. 安装本身做成多级兜底，每一级都要真去尝试
>
> 顺序固定为：
>
> 1. `Intent.ACTION_VIEW` + `application/vnd.android.package-archive` + `FileProvider` 的 `content://` URI；
> 2. `Intent.ACTION_INSTALL_PACKAGE` + 同一个 URI（部分老 ROM 只认这个 action）；
> 3. `PackageInstaller` 会话安装（`createSession` → `openSession` → `write` → `commit`），不依赖任何安装器 Activity 的 IntentFilter；
> 4. 全都起不来才认为真的缺授权。
>
> Intent 上必须同时带：
>
> - `FLAG_GRANT_READ_URI_PERMISSION`（否则安装器读不到临时授权）；
> - `FLAG_ACTIVITY_NEW_TASK`（从非 Activity Context 拉起）；
> - **`clipData = ClipData.newRawUri("", apkUri)`** —— 有些 ROM 的安装器只看 `clipData` 不看 `setData`，漏了这一条会静默失败或报权限错误。
>
> `FileProvider` 的 `<cache-path>` 要覆盖 APK 实际所在目录（例如 `<cache-path name="updates" path="updates/" />`），并且 manifest 里声明了 `REQUEST_INSTALL_PACKAGES`。
>
> ### 3. 「安装未知应用」授权页要做多级降级
>
> 不要在 catch 里只写一句错误提示。按下面顺序逐个 `startActivity`，前一个抛异常就试下一个：
>
> 1. `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` + `package:<包名>`；
> 2. `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 不带 data（部分 ROM 只认全局页面）；
> 3. `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<包名>`（应用详情页里有开关）。
>
> 三级都打不开时，**把已经下载校验好的 APK 复制到外部可访问目录**（`getExternalFilesDir(null)`）并在界面上给出完整路径，提示用户用文件管理器或 U 盘安装。绝不能只留一条「无法打开设置」的死路。
>
> ### 4. 防死循环
>
> 如果授权页能打开、但用户授权后仍然装不上（固件根本不认这个开关），「回前台 → 重试安装 → 再次跳授权页」会无限循环。给授权页跳转加个**尝试次数上限**（2 次足够），超限直接转手动安装并说明原因。
>
> ### 5. 顺手检查这几项
>
> - 安装前是否校验了包名、`versionCode` 与清单一致（防止装错包 / 降级）；
> - `PackageInstaller` 会话的 `PendingIntent` 在 Android 12+ 是否需要 `FLAG_MUTABLE`（系统要往里面写结果）；
> - `Session.openWrite` 之后是否 `flush`（`fsync` 需要 API 24+，minSdk 更低时用 flush）；
> - 安装结果广播接收器是否用 `RECEIVER_NOT_EXPORTED` 注册（Android 14 开始必须显式声明）；
> - `STATUS_PENDING_USER_ACTION` 分支是否把 `EXTRA_INTENT` 里的确认界面拉起来了（部分 ROM 需要调用方自己拉）。
>
> ### 交付物
>
> 改完后给我：
>
> 1. 所有被改动的文件和关键 diff；
> 2. 一段能说明「为什么旧逻辑在盒子上必然失败」的结论（引用具体行号）；
> 3. 明确列出哪些设备 / 场景**仍然无法覆盖**（例如完全禁用了第三方安装的固件）。

---

## 一句话版本

> 安装未知来源的权限判断不要用 `canRequestPackageInstalls()` 做前置门槛——很多电视盒子恒返回 false 但能装；先无条件 `ACTION_VIEW` 拉起安装器（`content://` + `FLAG_GRANT_READ_URI_PERMISSION` + `clipData`），失败退到 `ACTION_INSTALL_PACKAGE`，再失败退到 `PackageInstaller` 会话，最后才逐级降级打开授权页（带包名 → 不带包名 → 应用详情页），全都打不开就把 APK 导到外部目录提示手动安装；授权页跳转要有次数上限，避免回前台重试时死循环。
