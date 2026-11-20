# 备份与局域网同步验收

验收日期为 2026 年 11 月 21 日。以 GitHub 上 #1–#7 的验收条款为准。总跟踪 issue 引用的 `plan.md` 未在工作区或远端 `main` 中找到。[总跟踪 Issue #8](https://github.com/leemwood/KeyVault/issues/8)

| 里程碑 | 验证结果 |
| --- | --- |
| [#1 网络与密钥基础](https://github.com/leemwood/KeyVault/issues/1) | Android 与 Windows 完成双向 TLS。Android Keystore 证书私钥不可导出，桌面 PKCS#12 测试检查了加密私钥条目、错误口令及重启后复用。 |
| [#2 加密备份](https://github.com/leemwood/KeyVault/issues/2) | 往返、错误口令、篡改、截断、超限及旧格式兼容测试通过。曾用独立的 Python 加密库解开 Redmi 通过 SAF 导出的合成配置。 |
| [#3 导入策略](https://github.com/leemwood/KeyVault/issues/3) | 自动化测试覆盖空配置、重名、重复 Key、合并选择和写入失败回滚。Redmi 实测新增、合并、覆盖预览和二次确认。 |
| [#4 发现与配对](https://github.com/leemwood/KeyVault/issues/4) | Redmi 和 Windows 在同一 Wi-Fi 上双向发现和配对，重启后读回信任。自动化测试覆盖改动邀请、邀请过期、错误证书和不同子网。 |
| [#5 单次配置传送](https://github.com/leemwood/KeyVault/issues/5) | Windows 向 Redmi 传送 500 个合成 Key。限速测试在传输中断开连接，并在持久化后丢弃最终确认，验证不重传已确认分块且只保存一份。 |
| [#6 增量同步](https://github.com/leemwood/KeyVault/issues/6) | Windows 与 Redmi 双向交换独立修改。自动化测试覆盖同一 Key 的冲突选择、删除传播、断点续传和重放拒绝。 |
| [#7 多设备与后台同步](https://github.com/leemwood/KeyVault/issues/7) | 自动化测试覆盖三设备传播和撤销后断开连接、拒绝重连及拒绝提交。强制停止 Redmi 测试应用后，重新开启前台同步窗口并按 Home，收到 Windows 在离线期间的编辑。已用打包的 Windows 程序验证缩到托盘时保留监听、从托盘恢复窗口、停止同步释放端口以及退出进程。 |

## 复现构建与测试

使用 JDK 21 和 Android SDK 36。将 Redmi 或其他调试设备与 Windows 接入同一个 IPv4 子网。连接多台设备时设置 `ANDROID_SERIAL`。下列命令使用 Git Bash 语法。[密钥与传送说明](sync-transport.md)

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :desktopApp:compileKotlinJvm
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r -t androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
KEYVAULT_TEST_ADB=adb KEYVAULT_TEST_LAN=1 KEYVAULT_REQUIRE_MDNS=1 KEYVAULT_TEST_DESKTOP_HOST=1 ./gradlew :shared:jvmTest --rerun-tasks
```

40 项测试全部通过，未跳过。Windows 用 WiX 3.14.1 执行 `:desktopApp:createDistributable :desktopApp:packageMsi` 成功。托盘测试从 `createDistributable` 生成的应用目录运行，使用独立资料目录和合成设备记录。Windows 原生托盘使用 `Open KeyVault`、`Stop sync`、`Quit KeyVault`，避免打包运行时的中文缺字。

Android 的 `:androidApp:lintDebug` 通过。AGP 的 lint 仍输出已知的 Kotlin 元数据版本提示。Android 12 及以上还通过数据提取规则显式排除 DataStore 的系统备份和设备迁移。

## 发布前决策

- 现有 Android 和桌面 DataStore 仍以明文保存业务内容，包括待处理的冲突版本。备份文件和传输已加密，设备私钥按平台保护。公开发布前需确认是否接受本机存储边界。[备份文件格式](backup-format.md)
- 当前实现使用 JSSE 双向 TLS、Bouncy Castle 的 Argon2id 及 Ed25519/X25519 实现，以及 JCA 的 AES‑GCM。备份导入默认新增，合并和覆盖由用户明确选择，覆盖前有二次确认。[网络栈选型](sync-transport.md)
- 桌面平台实测覆盖 Windows。macOS 和 Linux 的本机打包、发现和托盘交互尚未验收。首发桌面范围和独立安全评审安排需要确认。
