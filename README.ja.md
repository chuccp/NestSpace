# NestSpace

[简体中文](README.md) · [English](README.en.md) · [繁體中文](README.zh-TW.md) · [日本語](README.ja.md)

オンラインドキュメント：[简体中文](https://chuccp.github.io/NestSpace/zh/) · [English](https://chuccp.github.io/NestSpace/en/) · [繁體中文](https://chuccp.github.io/NestSpace/tw/) · [日本語](https://chuccp.github.io/NestSpace/ja/)

[![Build APK](https://github.com/chuccp/NestSpace/actions/workflows/build.yml/badge.svg)](https://github.com/chuccp/NestSpace/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-online-blue.svg)](https://chuccp.github.io/NestSpace/ja/)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)

**Android 上でアプリレベルの仮想空間を実行する実験的なオープンソースプロジェクトです。**

> ⚠️ NestSpace は主に AI 機能の検証を目的とする実験的プロジェクトです。インストールしたアプリの互換性や安全性は保証しません。

NestSpace はサードパーティ APK をアプリ内部にインストールし、相互に分離されたインスタンスを作成して、アプリ内デスクトップから起動・整理できます。ランタイム、デスクトップ、AI アシスタント、MCP 制御サービス、Java プラグインプラットフォームは同じホストアプリに含まれます。

## 主な機能

- 通常利用にアクセシビリティサービス、adb、root は不要です。
- アプリデータを分離した複数インスタンス。
- モデル計画、UI 制御、内蔵ブラウザ、メモリ、スキル、スケジュールタスクを備えたローカル AI アシスタント。
- `background`、`mirror`、`visible` のアプリ表示モード。
- Claude Code などのクライアントから操作できる MCP 制御。
- Agent Hook/ツール、LLM・音声 Provider、Host API、対象アプリ専用ランタイム Profile を追加する Java `.nest` プラグイン。
- ダウンロード、アップロード、ユーザー引き継ぎ、プロキシに対応したマルチタブ内蔵ブラウザ。

## インストール

[GitHub Releases](https://github.com/chuccp/NestSpace/releases/latest) から最新 APK をダウンロードします。

- `NestSpace-vX.Y.Z-arm64.apk`：ARM64 端末、MuMu などの ARM 変換エミュレーター。
- `NestSpace-vX.Y.Z-x86_64.apk`：ARM 変換のない x86_64 PC／エミュレーター。
- `NestSpace-vX.Y.Z-universal.apk`：アーキテクチャが不明な場合。

Android 7.0（API 24）以降が必要です。Android 7.1、15、16、17 は現在の検証対象に含まれます。

## 使い方

1. ホストにインストール済みのアプリ、または APK/split APK をインポートします。
2. 「スペース設定 → アプリ管理」からインスタンスを起動または複製します。
3. AI アシスタントを使う前に「スペース設定 → モデル設定」でモデルを設定します。
4. 「プラグインセンター」から `.nest` プラグインをインストール・管理します。
5. 「MCP 制御サービス」から PC で NestSpace を操作できます。

AI アシスタントにはオフラインのルール Agent がありません。モデル未設定時は設定入口だけを表示し、コマンドやツール呼び出しは実行しません。アシスタントが操作するアプリはデフォルトでミラーを使用し、ログイン、CAPTCHA、支払い、承認は `hand_to_user` でユーザーに引き継げます。

## ビルド

必要なもの:

- JDK 21
- Android SDK platform 37
- Android Build Tools 36.0.0
- NDK 27.2.12479018
- CMake 3.22.1

```bash
./gradlew :app:assembleDebug
```

Release ではホスト ABI を指定します。

```bash
./gradlew :app:assembleRelease -PnestspaceHostAbi=universal
./gradlew :app:assembleRelease -PnestspaceHostAbi=arm64
./gradlew :app:assembleRelease -PnestspaceHostAbi=x86_64
```

## ドキュメント

- [クイックスタート](https://chuccp.github.io/NestSpace/ja/getting-started/)
- [アプリ管理](https://chuccp.github.io/NestSpace/ja/app-manager/)
- [AI アシスタント](https://chuccp.github.io/NestSpace/ja/assistant/)
- [プラグインプラットフォーム](https://chuccp.github.io/NestSpace/ja/plugin-center/)
- [プラグイン作成](https://chuccp.github.io/NestSpace/ja/plugin-authoring/)
- [MCP ツール](https://chuccp.github.io/NestSpace/ja/mcp-tools/)
- [アーキテクチャと制限](https://chuccp.github.io/NestSpace/ja/architecture/)

## ライセンス

[Apache License 2.0](LICENSE)
