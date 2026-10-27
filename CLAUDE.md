# CLAUDE.md

## 提交规范

- 直接提交到 `main`，不要新建分支。
- 改动完成后自动 commit，用简洁的中文提交信息。
- 只提交本次任务相关的文件；工作区里其他未提交改动不要顺手带入。

## 安装包命名

- APK 输出命名为 `myMusic-<versionName>.apk`（如 `myMusic-1.0.apk`），格式与 myDV
  （`myDV-$appVersionName.apk`）一致，在 `app/build.gradle.kts` 的
  `androidComponents.onVariants` 里统一设置。
- 版本号策略：`versionName` 固定，`versionCode` 取 `VERSION_CODE` 环境变量；
  CI 用 `v<versionName>.<versionCode>` 标签区分每次发布。
- `.github/workflows/build.yml` 以通配取 APK、下载 URL 由实际文件名推导，
  改名不影响发布流程。
- 应用内更新的下载落地文件名为 `myMusic-<versionName>.apk`
  （见 `data/update/UpdateRepository.kt`），与发布资产名保持一致。

## 构建

- 仓库没有 `local.properties`，Gradle 需要环境变量：
  `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`、
  `ANDROID_HOME="%LOCALAPPDATA%\Android\Sdk"`。
- 常用命令：`./gradlew :app:assembleDebug`、`./gradlew test`。
