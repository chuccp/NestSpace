# NestSpace

[简体中文](README.md) · [English](README.en.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md)

線上文件：[简体中文](https://chuccp.github.io/NestSpace/zh/) · [English](https://chuccp.github.io/NestSpace/en/) · [繁體中文](https://chuccp.github.io/NestSpace/tw/) · [日本語](https://chuccp.github.io/NestSpace/ja/)

[![Build APK](https://github.com/chuccp/NestSpace/actions/workflows/build.yml/badge.svg)](https://github.com/chuccp/NestSpace/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-online-blue.svg)](https://chuccp.github.io/NestSpace/tw/)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)

**在 Android 上執行應用程式層級虛擬空間的實驗性開源專案。**

> ⚠️ NestSpace 是實驗性專案，主要用於驗證 AI 能力，不保證所安裝 App 的相容性和安全性。

NestSpace 會把第三方 APK 安裝到應用程式內部，建立多個相互隔離的執行個體，並透過內建桌面啟動與整理。執行階段、桌面、AI 助手、MCP 控制服務和 Java 外掛平台都位於同一個宿主應用程式。

## 主要功能

- 一般使用不需要無障礙服務、adb 或 root。
- 多個獨立執行個體與分離的應用程式資料。
- 本機 AI 助手支援模型規劃、介面控制、內建瀏覽器、記憶、技能和定時任務。
- 支援 `background`、`mirror`、`visible` 三種應用程式顯示模式。
- 可透過 MCP 讓 Claude Code 等用戶端控制 NestSpace。
- Java `.nest` 外掛可擴充 Agent Hook/工具、LLM 與語音 Provider、Host API 和目標執行階段 Profile。
- 多分頁內建瀏覽器支援下載、上傳、轉交使用者和代理。

## 安裝

從 [GitHub Releases](https://github.com/chuccp/NestSpace/releases/latest) 下載最新 APK：

- `NestSpace-vX.Y.Z-arm64.apk`：ARM64 裝置，以及 MuMu 等 ARM 轉譯模擬器。
- `NestSpace-vX.Y.Z-x86_64.apk`：沒有 ARM 轉譯的 x86_64 電腦和模擬器。
- `NestSpace-vX.Y.Z-universal.apk`：不確定架構時使用。

需要 Android 7.0（API 24）以上；Android 7.1、15、16、17 都在目前驗證範圍內。

## 使用

1. 匯入宿主已安裝的應用程式，或選擇 APK/split APK。
2. 在「空間設定 → 應用程式管理」啟動或多開執行個體。
3. 使用 AI 助手前，先在「空間設定 → 模型設定」配置模型。
4. 開啟「外掛中心」安裝或管理 `.nest` 外掛。
5. 使用「MCP 控制服務」從電腦操作 NestSpace。

AI 助手不再提供離線規則 Agent。未設定模型時只顯示配置入口，不會執行指令或工具呼叫。助手控制的應用程式預設使用鏡像；登入、驗證碼、付款或授權可透過 `hand_to_user` 交給使用者。

## 建置

需求：

- JDK 21
- Android SDK platform 37
- Android Build Tools 36.0.0
- NDK 27.2.12479018
- CMake 3.22.1

```bash
./gradlew :app:assembleDebug
```

Release 建置需指定打包的宿主 ABI：

```bash
./gradlew :app:assembleRelease -PnestspaceHostAbi=universal
./gradlew :app:assembleRelease -PnestspaceHostAbi=arm64
./gradlew :app:assembleRelease -PnestspaceHostAbi=x86_64
```

## 文件

- [快速開始](https://chuccp.github.io/NestSpace/tw/getting-started/)
- [應用程式管理](https://chuccp.github.io/NestSpace/tw/app-manager/)
- [AI 助手](https://chuccp.github.io/NestSpace/tw/assistant/)
- [外掛平台](https://chuccp.github.io/NestSpace/tw/plugin-center/)
- [外掛撰寫](https://chuccp.github.io/NestSpace/tw/plugin-authoring/)
- [MCP 工具](https://chuccp.github.io/NestSpace/tw/mcp-tools/)
- [架構與限制](https://chuccp.github.io/NestSpace/tw/architecture/)

## 授權

[Apache License 2.0](LICENSE)
