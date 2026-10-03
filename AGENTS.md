# KeyVault 项目记忆

## 项目概况

密钥/API Key 管理应用，**Kotlin Multiplatform + Compose Multiplatform**（Android + 桌面 JVM），UI 库为 **miuix**（`top.yukonga.miuix.kmp`，HyperOS 风格，非 Material 3）。数据存 Preferences DataStore（KMP 版，JSON 序列化，以稳定 id 为 key，name 为字段，兼容历史上三层旧格式）。包名 `cn.lemwood.keyvault`。

- 仓库：https://github.com/leemwood/KeyVault（public，main 分支，2026-08-02 初始化推送）
- 截图位于 `docs/screenshots/`（home/detail/delete_dialog），README 引用

### 模块结构

| 模块 | 内容 |
| --- | --- |
| `:shared` | KMP 库（`androidTarget` + `jvm()`），数据层、ViewModel、全部 Compose UI |
| `:androidApp` | Android 壳：Activity / Application / 签名 / res |
| `:desktopApp` | 桌面壳：Compose Desktop 窗口（`MainKt`） |

`shared` 源码集：`commonMain` 全是共享代码；平台差异只留在 `platform/VaultPlatform.kt` 的两个 expect（`createVaultDataStore()`、`platformAppVersion()`）在 `androidMain` / `jvmMain` 的 actual。

## 关键坑

- **返回键的「孤立 dispatcher」陷阱（2026-10-03 真机定位）**：miuix `SuperDialog`（含 InputDialog）内部用 `NavigationBackHandler`，Composition 根部**必须**提供 `LocalNavigationEventDispatcherOwner`（删掉会 `IllegalStateException` 崩溃），但**不能无脑传 `parent = null`**：
  - `rememberNavigationEventDispatcherOwner(parent = null)` 造出的是一个**没有父级、也没有 `OnBackInvokedInput` 的孤立 dispatcher**，收不到任何系统返回事件 → 表现就是**在任意页面按返回键直接退出 App**（一二级页面全中招）。
  - 真正的事件源是 Activity：`ComponentActivity` 自 **androidx-activity 1.12.0** 起才实现 `NavigationEventDispatcherOwner`（1.9.3/1.11.0 都没有，已用 javap 验证）。因此 `MainActivity.setContent` 里必须把 Activity 自己传进去：
    ```kotlin
    KeyVaultApp(..., navigationEventDispatcherOwner = this@MainActivity)
    ```
    `KeyVaultApp` 里则是 `navigationEventDispatcherOwner ?: rememberNavigationEventDispatcherOwner(parent = null)` —— 桌面端没有系统返回手势，走孤立实例即可。
  - 所以 **`androidx-activity` 不能低于 1.12.0**（现锁 1.12.4），它和「返回键直接退出」是同一条因果链。
- **诊断手法**：`javap -classpath <解压后的 classes.jar> androidx.activity.ComponentActivity` 看 implements 列表；`unzip` AAR 取 `classes.jar`。Google Maven 元数据可直接 `curl https://dl.google.com/dl/android/maven2/androidx/activity/activity/maven-metadata.xml` 查可用版本。
- **返回键用 `NavigationBackHandler`，不要用 CMP 的 `BackHandler`**：`androidx.compose.ui.backhandler.BackHandler` 在 Compose Multiplatform 里已被标记 `@Deprecated("Use NavigationEventHandler instead")` 且需 opt-in `ExperimentalComposeUiApi`。正确写法：
  ```kotlin
  NavigationBackHandler(
      state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
      isBackEnabled = cond,
      onBackCompleted = { /* 返回 */ }
  )
  ```
- **FLAG_SECURE 由平台注入**：首页可截图，进入二级/三级页面（含 key 明文）禁截。逻辑在 `KeyVaultApp` 的 `DisposableEffect(deepPage)` 回调 `onSecureScreenChange`，Android 侧实现在 `androidApp` 的 `MainActivity.setSecureScreen()`，桌面侧空实现。
- **桌面端没有 ViewModelStoreOwner**：`rememberVaultViewModel()` 判断 `LocalViewModelStoreOwner.current`，为空时退化为 `remember { VaultViewModel() }`。想在桌面用 `viewModel()` 会直接崩。
- **DataStore 路径不能改**：Android 端必须写 `filesDir/datastore/vault.preferences_pb`（旧版就在那里），否则老用户升级即丢数据。桌面端写 `~/.keyvault/vault.preferences_pb`。换成 multiplatform-settings 同样会丢数据，别改。
- **CMP 版本要跟着 miuix 走**：miuix 0.8.8 用 Kotlin 2.3.20 + foundation 1.10.3 编译，因此 CMP Gradle plugin 锁 1.10.3。升 CMP 前先确认 miuix 新版跟随升级，否则可能 compose runtime 版本不匹配。
- **旧 JSON 格式有三层**：`VaultCodec.decode()` 里现格式（id 为 key）、中格式（名字为 key）、最旧格式（字段名→字段值，api/url 识别为 URL）都要保留，删任何一层都会让部分老用户数据错乱。

## 多配置文件（1.1.0 起）

- 存储：`profiles` 键存整个 `List<VaultProfile>` 的 JSON，`active_profile_id` 存当前配置 id。**旧版只有 `services` 键**，首次启动时 `VaultRepository.snapshotFlow` 会把它无损迁移成名为「默认配置」、id 固定为 `LEGACY_PROFILE_ID = "default"` 的配置。改这个 id 会让老用户丢数据上下文。
- 兼容写法：`save()` 仍然顺带把**当前配置**的 services 写回旧的 `services` 键，这样即使回滚到 1.0.x 也能看到当前配置的数据。
- 导入**永远新增配置，绝不覆盖**：`importBackup()` 只 append，名字用 `suggestedProfileName()` 做「配置 2 / 配置 3…」去重。用户明确要求过不要合并、不要覆盖。
- 备份格式（`ProfileCodec`）：`{"format":1,"name":"...","services":{...}}`；若没有 `services` 键则把整个文档当 services 解析（兼容裸 JSON）。文件名 `keyvault-<配置名>.json`，名字会做非法字符清洗（实测「配置 2」→ `keyvault-配置2.json`）。
- 平台文件通道：`VaultFileIo`（`saveFile`/`openFile`），Android 用 SAF（`CreateDocument`/`OpenDocument`，`CompletableDeferred` 桥成 suspend，注册必须在 STARTED 之前即 `onCreate` 里），桌面用 AWT `FileDialog`（必须留在 AWT 事件线程，macOS 要求）。

## 主题（1.1.0 起）

- 只跟随系统，无手动开关（用户明确要求）。`ThemeController(colorSchemeMode = ColorSchemeMode.System)`，内部会读 `isSystemInDarkTheme()`，系统切亮暗时 `uiMode` 变化触发重组。
- Android 侧要把结果回传给平台同步状态栏/导航栏图标颜色（`MainActivity.setSystemBarsAppearance`），并配 `res/values-night/themes.xml` 把 `windowBackground` 设黑，避免深色下冷启动白闪。
- 桌面端截图验证法：统计像素平均亮度，亮色 ~244、深色 ~5.7，且深色图里有占比 ~0.46% 的高亮像素（说明是浅色文字而非纯黑）。

## 构建/安装与发布

- Android：`./gradlew :androidApp:assembleDebug` / `assembleRelease`；安装 `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- 桌面：`./gradlew :desktopApp:run` 运行；`./gradlew :desktopApp:packageDistributionForCurrentOS` 打本机安装包。
- **本机（Windows）构建环境**（2026-10-03 实测可用）：
  - JDK 21 在 `E:/jdk21`（系统 PATH 上的 JDK 25 与 Gradle 8.11.1 不兼容，不能用）。构建命令：`/e/jdk21/bin/java.exe -jar gradle/wrapper/gradle-wrapper.jar <任务>`。
  - Android SDK 在 `E:/android-sdk-win`（platforms android-36、build-tools 35/36）。已在 `local.properties` 写入 `sdk.dir=E:/android-sdk-win`（该文件已 gitignore）。
  - Android Studio 在 `D:/app/as`（自带 jbr），adb 在 `D:/app/platform-tools/adb.exe`。
  - 已验证通过：`:androidApp:assembleDebug`（2m9s）、`:shared:jvmTest`、`:desktopApp:compileKotlinJvm`。
- **`androidx.lifecycle` 锁 2.10.0，不要升到 2.11.0**：2.11.0 的 AAR metadata 要求 AGP ≥ 9.1.0 且 compileSdk ≥ 37，当前是 AGP 8.9.3 + compileSdk 36，会在 `checkDebugAarMetadata` 直接报 `INSTALL`/依赖校验失败。要升 lifecycle 就得先升 AGP 9.x 并装 android-37 平台。
- **`androidx-activity` 锁 1.12.4，不能降到 1.12.0 以下**：1.12.0 以下 `ComponentActivity` 不实现 `NavigationEventDispatcherOwner`，返回键会直接退出 App（见「关键坑」第一条）。
- **依赖/分发包加速**：Gradle 8.11.1 分发包从 services.gradle.org 下载会卡死，用腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip` 手动放到 `~/.gradle/wrapper/dists/gradle-8.11.1-bin/<hash>/` 后解压并 `touch gradle-8.11.1-bin.zip.ok`。Maven 镜像写在 `~/.gradle/init.d/mirrors.gradle`（不进仓库，符合「仓库文件保持官方源」原则）。
- 查看数据：`adb shell run-as cn.lemwood.keyvault.debug cat files/datastore/vault.preferences_pb | strings`（debug 包可 run-as；debug 包名为 `cn.lemwood.keyvault.debug`）。
- **Release 签名**：`keyvault-release.jks`（alias `keyvault`，RSA 2048，有效期 30 年）+ `keystore.properties` 存于项目根，均已 gitignore（`*.jks`、`keystore.properties`），**严禁入库**；`androidApp/build.gradle.kts` 从 keystore.properties 读取，文件缺失时 release 不签名（CI 可另行注入）。
- **务必离线备份 keystore 与 keystore.properties**，丢失则无法以同一签名发布更新。
- debug 与 release 签名不同，设备上互装需先卸载（数据会丢）。
- **debug 包名带 `.debug` 后缀**：`cn.lemwood.keyvault.debug`，可与 release 共存。
- 历史 tag：`v1.0.0`、`v1.1.0`、`v1.2.0`（预发布）。GitHub Release 附件命名 `KeyVault-<version>.apk`。
- Termux 构建时需手动打开 `gradle.properties` 里注释掉的 `android.aapt2FromMavenOverride`。

### 发布流程（2026-10-03 走通）

1. 版本号在 `gradle/libs.versions.toml` 的 `app-version-name` / `app-version-code`，改这里即可。
2. 签名：`keystore.properties` + `keyvault-release.jks` 放**项目根**（均已 gitignore）。zip 由柠枺提供，解压即用，四个 key（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`）与 `androidApp/build.gradle.kts` 对应。
3. 构建：`JAVA_HOME=E:/jdk21 ./gradlew :androidApp:assembleRelease`。过程中 `lintVital` 会刷一堆 `Module was compiled with an incompatible version of Kotlin ... 2.3.0, expected 2.1.0` 的 `e:` 行，**那是 lint 的噪声，不影响产物**，别当成编译失败。
4. 验签（必做）：
   ```
   E:/android-sdk-win/build-tools/36.0.0/apksigner.bat verify --verbose --print-certs \
     androidApp/build/outputs/apk/release/androidApp-release.apk
   ```
   应见 `Verifies` + `v2 scheme: true` + `CN=lemwood, OU=KeyVault` + SHA-256 `e84ed384...`（与 `keytool -list -keystore keyvault-release.jks` 输出一致）。
5. 发版：**`gh` 不在 PATH，本机在 `D:/app/gh/gh.exe`**（已登录 leemwood，含 repo 权限）：
   ```
   cp androidApp-release.apk KeyVault-<ver>.apk
   gh release create <tag> --prerelease --title "..." --notes-file - KeyVault-<ver>.apk
   ```
   预发布加 `--prerelease`，正式版去掉。
6. 提交前务必 `git diff --cached --name-only | grep -iE "\.jks|keystore|local.properties"` 确认没把密钥带进去。

## 调试设备

Redmi K40（alioth，M2012K11AC），Android 13 MIUI，USB 连接时序列号 `7d2759bc`（也曾走 adb 网络连接，`adb devices` 中 172.25.x.x）。用户常用该设备跑 ZalithLauncher 游戏 VM（`:game` 进程会抢前台，干扰 UI 自动化，必要时 `adb shell am force-stop com.movtery.zalithlauncher.v2`）。

### 这台机器上跑 adb 的两个坑

- **Git Bash 会把 adb 的远端路径当成 Windows 路径转换**：`adb shell` / `adb pull` / `adb push` 里出现 `/data/...`、`/sdcard/...` 时，必须加前缀 `MSYS_NO_PATHCONV=1`，否则报 `remote secure_mkdirs failed` 或 `failed to stat remote object 'C:/Users/.../sdcard/...'`。
- **MIUI 会拦 adb 安装**：首次 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`。处理顺序：① `adb shell appops set com.android.shell REQUEST_INSTALL_PACKAGES allow`；② 重跑安装（实测第二次即 `Success`）。仍不行再让用户在开发者选项里开「USB 安装」。注意 `adb root` 在生产版不可用，`pm install` 与弹出安装界面的方式都会被 MIUI 拦。
- 启动 Activity 要用完整类名：`am start -n cn.lemwood.keyvault.debug/cn.lemwood.keyvault.MainActivity`（简写 `.MainActivity` 会被补全成 `.debug.MainActivity` 而找不到）。
- 二/三级页面禁截屏，UI 自动化只能靠 `uiautomator dump` + 解析 `bounds` + `input tap`；**软键盘弹出会把按钮顶上去**，点确定前必须重新 dump 取坐标，否则会点到键盘按键上（实测往输入框里打进了 `vv`）。
- **判断当前在哪一层页面**：靠 `content-desc` 区分——首页有 `切换配置`/`关于`/`添加服务`，二级（服务详情）有 `返回`/`添加配置项`，三级（配置项详情）有 `返回`/`添加 Key`，配置管理页有 `新建配置`/`导入备份文件`。**不要靠 `text` 猜**，首页卡片也有「编辑/删除」和「N 个配置项」，很容易误判成二级页。
- `uiautomator dump` 偶尔会吐 0 字节或报 `theme_compatibility.xml: ENOENT`（DocumentsUI 弹窗刚起来时常见），重试一次即可，别当成功能坏了。
- SAF 选择器实测可直接 `input tap` 命中：保存页用 `EditText[android:id/title]` 拿预填文件名、`Button[android:id/button1]` 是「保存」；选择页直接点文件名文本。

## 已知遗留

- UI 字符串仍硬编码中文，未迁移多语言资源（KMP 下应改用 `composeResources` 的 `values/strings.xml`）。
- 搜索会匹配 Key 明文（`HomeScreen.searchItems` 含 `it.value`），属有意保留。
- 桌面端未做 FLAG_SECURE 等价的防护，窗口可被系统截图。
- 桌面端数据未加密存储，与移动端一致，后续可考虑加 keystore/DPAPI 派生密钥。
