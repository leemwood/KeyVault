# KeyVault 项目记忆

## 项目概况

Android 密钥/API Key 管理应用，Kotlin + Jetpack Compose，UI 库为 **miuix**（`top.yukonga.miuix`，HyperOS 风格，非 Material 3）。数据存 Preferences DataStore（JSON 序列化，以稳定 id 为 key，name 为字段，兼容旧 name-key 格式）。包名 `cn.lemwood.keyvault`。

- 仓库：https://github.com/leemwood/KeyVault（public，main 分支，2026-08-02 初始化推送）
- 截图位于 `docs/screenshots/`（home/detail/delete_dialog），README 引用

## 关键坑（2026-08-02 实机踩过）

- **miuix SuperDialog 依赖 NavigationEventDispatcher**：`SuperDialog`（含 InputDialog）内部用 `NavigationBackHandler`，必须在 Composition 根部提供 `CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(parent = null))`（见 `MainActivity.kt`）。删掉会直接 `IllegalStateException` 崩溃；根部调用必须显式传 `parent = null`。这不是死代码，勿删。
- **FLAG_SECURE 按导航层级动态切换（2026-08-11 改）**：首页（服务列表）`clearFlags` 可截图；进入二级（配置项）/三级（Key 管理）页面（含 key 明文）`setFlags` 禁截。逻辑在 `MainActivity.KeyVaultApp` 的 `LaunchedEffect(selectedServiceId != null)`，`onCreate` 不再固定设置。首页截图用 `adb exec-out screencap -p > home.png`；二/三级页面截图仍为 0 字节，UI 自动化改用 `adb shell uiautomator dump` + 解析 bounds + `input tap`。
## 构建/安装与发布

- 构建：`./gradlew assembleDebug` / `assembleRelease`；安装 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- **Termux 构建加速（镜像，不进仓库）**：Termux 网络下 gradle distribution（services.gradle.org）与 Maven 依赖下载极慢（首版构建约 18 分钟，多半是下载）。临时把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成腾讯云 `https://mirrors.cloud.tencent.com/gradle/gradle-<ver>-bin.zip`；`settings.gradle.kts` 的 repositories 头部加阿里云镜像（`https://maven.aliyun.com/repository/google`、`/central`、`/gradle-plugin`）。distribution 与依赖缓存到 `~/.gradle` 后可 `--offline` 构建避免联网检查。仓库文件保持官方源原版，构建前手动套用镜像即可。
- 查看数据：`adb shell run-as cn.lemwood.keyvault.debug cat files/datastore/vault.preferences_pb | strings`（debug 包可 run-as；debug 包名为 `cn.lemwood.keyvault.debug`，见下）。
- **Release 签名**：`keyvault-release.jks`（alias `keyvault`，RSA 2048，有效期 30 年）+ `keystore.properties` 存于项目根，均已 gitignore（`*.jks`、`keystore.properties`），**严禁入库**；`app/build.gradle.kts` 从 keystore.properties 读取，文件缺失时 release 不签名（CI 可另行注入）。
- **务必离线备份 keystore 与 keystore.properties**，丢失则无法以同一签名发布更新。
- debug 与 release 签名不同，设备上互装需先卸载（数据会丢）。
- **debug 包名带 `.debug` 后缀**（`applicationIdSuffix`）：`cn.lemwood.keyvault.debug`，可与 release 共存；`run-as`、`adb shell pm` 等均用此包名。
- 首版：tag `v1.0.0`，GitHub Release 附件命名 `KeyVault-v1.0.0.apk`。

## 调试设备

Redmi K40（alioth，M2012K11AC），Android 13 MIUI，adb 网络连接（`adb devices` 中 172.25.x.x）。用户常用该设备跑 ZalithLauncher 游戏 VM（`:game` 进程会抢前台，干扰 UI 自动化，必要时 `adb shell am force-stop com.movtery.zalithlauncher.v2`）。

## 已知遗留（2026-08-02 审查后未做）

- UI 字符串仍硬编码中文，未迁移 `strings.xml`（`values-en` 也无）。
- `app/build.gradle.kts` 中 material3 依赖未被源码使用；navigationevent 依赖声明保留（miuix 需要）。
- 搜索会匹配 Key 明文（`HomeScreen.searchItems` 含 `it.value`），属有意保留。
