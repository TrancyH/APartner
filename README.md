# APartner — 手机协助助手

AmooLink(aLink 桌面端)的 Android 伴侣应用,独立演进仓库。
从 amoolink `apps/android-companion` 拆分而来(2026-11-01),协议契约与桌面端 `packages/companion` 保持对齐。

> 包名保持 `com.amoolink.companion` 不变(桌面端配置/通知监听器注册依赖此名,见 docs/契约.md)。

## 定位

手机侧的「协助助手」三大支柱:

| 支柱 | 说明 | 状态 |
|---|---|---|
| **桌面互联** | 与 aLink 桌面端 mTLS+OTP+SAS 配对、通知实时同步与快捷回复、短信/通讯录+vCard、来电提醒、剪贴板互发、免 USB 常驻 | ✅ 已实现(自 amoolink M5 迁入) |
| **文件管理** | 桌面端远程浏览/收发手机文件(fs_* 协议:512KiB 块 + sha256 双端校验);手机本地浏览 | 🚧 远程方向已实现(2026-11-01),本地浏览规划中 |
| **共享** | 系统分享目标(ACTION_SEND)、二维码/网页收发、LocalSend v2 协议兼容(跨平台免装互传) | 🚧 规划中 |

详细路线见 [docs/产品规划.md](./docs/产品规划.md)。

## 仓库结构

```
android/   # Kotlin 工程(零外部依赖,Gradle + aliyun 镜像,JDK 17)
docs/      # 产品规划与协议契约文档
```

## 开发

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# 桌面侧配置/授权探针仍在 amoolink 仓库:pnpm probe:m5
```

- 工具链:JDK 17(brew openjdk@17,gradle.properties 已 pin)、Android SDK 34、targetSdk 34 / minSdk 26
- 协议帧实现:`android/app/src/main/java/com/amoolink/companion/Proto.kt`(与桌面端 `packages/companion/src/protocol.ts` 逐字节对齐,改动两侧同步)
