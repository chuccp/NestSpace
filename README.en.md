# NestSpace

[简体中文](README.md) · [English](README.en.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md)

Online docs: [简体中文](https://chuccp.github.io/NestSpace/zh/) · [English](https://chuccp.github.io/NestSpace/en/) · [繁體中文](https://chuccp.github.io/NestSpace/tw/) · [日本語](https://chuccp.github.io/NestSpace/ja/)

[![Build APK](https://github.com/chuccp/NestSpace/actions/workflows/build.yml/badge.svg)](https://github.com/chuccp/NestSpace/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-online-blue.svg)](https://chuccp.github.io/NestSpace/en/)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)

**An experimental open-source project for running app-level virtual spaces on Android.**

> ⚠️ NestSpace is an experimental project focused on validating AI capabilities. It does not guarantee the compatibility or security of installed apps.

NestSpace installs third-party APKs inside its own application, creates isolated instances, and exposes them through an in-app desktop. Its runtime, desktop, AI assistant, MCP control service, and Java plugin platform work together in one host application.

## Highlights

- No accessibility service, adb, or root required for normal device use.
- Multiple isolated instances with separate app data.
- Local AI assistant with model planning, UI control, built-in browser, memory, skills, and scheduled tasks.
- `background`, `mirror`, and `visible` app display modes.
- MCP control for Claude Code and other clients.
- Java `.nest` plugins for Agent hooks/tools, LLM and speech providers, Host APIs, and target runtime profiles.
- Built-in multi-tab browser with downloads, uploads, handoff, and proxy support.

## Install

Download the latest APK from [GitHub Releases](https://github.com/chuccp/NestSpace/releases/latest):

- `NestSpace-vX.Y.Z-arm64.apk`: ARM64 devices and ARM-translation emulators such as MuMu.
- `NestSpace-vX.Y.Z-x86_64.apk`: x86_64 computers and emulators without ARM translation.
- `NestSpace-vX.Y.Z-universal.apk`: all ABIs when the target architecture is uncertain.

Android 7.0 (API 24) or later is required. Android 7.1, 15, 16, and 17 are part of the current validation matrix.

## Use

1. Import an app installed on the host or select an APK/split APK.
2. Launch or duplicate an instance from **Space settings → App management**.
3. Configure a model in **Space settings → Model settings** before using the AI assistant.
4. Open **Plugin Center** to install or manage `.nest` plugins.
5. Use **MCP control service** to drive NestSpace from a computer.

The AI assistant does not include a fallback rule Agent. When no model is configured, it shows a configuration entry and does not execute commands or tool calls. Assistant-controlled apps use the mirror by default; `hand_to_user` transfers an app to the user for login, CAPTCHA, payment, or approval.

## Build

Requirements:

- JDK 21
- Android SDK platform 37
- Android Build Tools 36.0.0
- NDK 27.2.12479018
- CMake 3.22.1

```bash
./gradlew :app:assembleDebug
```

Release variants select the packaged host ABI:

```bash
./gradlew :app:assembleRelease -PnestspaceHostAbi=universal
./gradlew :app:assembleRelease -PnestspaceHostAbi=arm64
./gradlew :app:assembleRelease -PnestspaceHostAbi=x86_64
```

## Documentation

- [Quick start](https://chuccp.github.io/NestSpace/en/getting-started/)
- [App management](https://chuccp.github.io/NestSpace/en/app-manager/)
- [AI assistant](https://chuccp.github.io/NestSpace/en/assistant/)
- [Plugin platform](https://chuccp.github.io/NestSpace/en/plugin-center/)
- [Plugin authoring](https://chuccp.github.io/NestSpace/en/plugin-authoring/)
- [MCP tools](https://chuccp.github.io/NestSpace/en/mcp-tools/)
- [Architecture and limitations](https://chuccp.github.io/NestSpace/en/architecture/)

## License

[Apache License 2.0](LICENSE)
