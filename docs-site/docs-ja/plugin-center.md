# NestSpace プラグインプラットフォームと Java SDK

NestSpace プラグインは Java の Android モジュールで、AI アシスタント、プラグインセンター、Host API、モデル／音声 Provider、対象アプリ専用のランタイム互換処理を、メインアプリを毎回変更せずに拡張します。

実際の開発手順は[プラグイン作成ガイド](plugin-authoring.md)を参照してください。

## 現在の実装

| 領域 | 状態 |
| --- | --- |
| 公開 `plugin-api` | Java 11 Android Library として実装 |
| Manifest と権限 | 解析、検証、権限グループ化、実行時チェック |
| ソースプラグイン | Debug ビルドで自動検出 |
| 外部プラグイン | `.nest` パッケージからインストール |
| プラグインセンター | 検索、詳細、設定、有効化/無効化、名前変更、共有、エクスポート、削除 |
| Agent Hook/Tool | プロンプト、質問、モデル要求、ツール、表示 Hook |
| Host API | ストレージ、秘密情報、キャッシュ、SQLite、ファイル、共有、クリップボード、通知、HTTP、SSE、WebSocket、Android API ファサード |
| Provider | LLM Provider、Speech Provider |
| ランタイムプロキシ | 起動ポリシー、クラス索引、Application/Activity Hook、バージョン照合 |
| MCP 管理 | `plugin_list`、`plugin_manage` |

## モジュール構成

```text
:app -> :plugin-api
```

`example-plugin/plugin` と `dev-plugins/*` は Debug ビルドのソースプラグインとして読み込まれます。ソースプラグインはホストの ClassLoader を共有するため、通常の開発中に `.nest` を作る必要はありません。

外部パッケージは検証、展開、`DexClassLoader` 読み込み、再起動後の復元を行います。同じ ID のソースプラグインが外部パッケージより優先されます。

## Manifest と権限

プラグインの登録情報は Android の `AndroidManifest.xml` ではなく `manifest.json` です。

```json
{
  "manifest_version": 1,
  "id": "com.example.plugin",
  "name": "Example Plugin",
  "version": "1.0.0",
  "api_version": 1,
  "entry_class": "com.example.plugin.ExamplePlugin",
  "permissions": ["agent", "storage", "network"],
  "host_permissions": ["https://api.example.com/*"],
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "settings": "contributions/settings.schema.json"
  }
}
```

| 権限 | 展開される能力 |
| --- | --- |
| `agent` | Agent のプロンプト、質問、モデル要求、ツール、ツール Hook、表示、LLM／音声 Provider |
| `runtime` | 起動ポリシーと対象 Application/Activity Hook |
| `automation` | コマンド、管理タスク、イベント |
| `storage` | 専用ファイル、設定、キャッシュ、SQLite |
| `secrets` | Keystore で暗号化された秘密情報 |
| `content` | ファイル選択／書き出し、共有、クリップボード |
| `ui` | 宣言型ページと結果 Renderer |
| `notifications` | プラグイン通知 |
| `network` | HTTP、SSE、WebSocket、Host ホワイトリスト |
| `device` | 制限付き Android API ファサード |

旧細粒度権限は互換のため解析できますが、新しいプラグインは権限グループを使用してください。

## 拡張ポイント

### Agent

- プロンプト貢献は独立したプラグイン領域に追加されます。
- モデル前質問 Hook は直接回答するか、通常のモデル処理へ戻せます。
- モデル要求 Hook は要求の確認、補足、ブロックができます。
- ツール前 Hook は引数変更や呼び出しブロック、後 Hook は結果の観察のみ行えます。
- 表示 Hook はテキスト、JSON Renderer、プラグイン作成の Android View を使用できます。

プラグインツールは組み込みツールと同じ `ToolRegistry` に入り、ローカル Agent とリモート MCP から利用できます。権限確認とタスクのライフサイクルも共通です。

### Host API

宣言された権限の範囲で次を利用できます。

- 専用ファイル、キャッシュ、設定、SQLite、Keystore 秘密情報
- Manifest の Host 制限付き HTTP、SSE、WebSocket
- ファイル選択／書き出し、共有、クリップボード、通知
- 制限付き Android API、プラグイン専用タスクとイベント
- ホストが描画する宣言型 `PluginSettingView`

ホストの Application、任意の Activity、他プラグインのストレージ、API Key、無制限ネットワークにはアクセスできません。

### LLM と音声 Provider

LLM Provider はモデル設定で次の URL を使って選択します。

```text
plugin://<provider-id>
```

上流の認証情報はプラグインが保持します。ホストは正規化した messages、tools、流式設定を Provider に渡します。

Speech Provider は PCM16LE 音声のみを返します。AudioFocus、再生キュー、停止、キャッシュ、スピーカー出力はホストが管理し、`speech_speak`、`speech_stop`、`speech_status` を公開します。

## ランタイムプロキシ

`RuntimeProxyPlugin` はパッケージ名、`versionCode` の完全一致／接頭辞／ワイルドカード／範囲、任意のクラス名で対象を照合し、次の起動方法を選べます。

- `CONTAINER`：通常のコンテナ
- `HOST_FALLBACK`：ホストにインストール済みのアプリを起動
- `BLOCKED`：理由付きで起動を拒否

深い Hook は `RuntimeProfileProvider` で提供し、プラグインのライフサイクルに合わせて自動登録・解除されます。

## プラグインセンター

プラグインセンターは下部 Dock とは別のデスクトップ入口です。

- タップ：バージョン、提供元、Capability、詳細ページ、設定を表示
- 長押し：有効化/無効化、設定、名前変更、共有、エクスポート、削除
- 無効化：Hook、ツール、Provider、設定、ランタイムプロファイルを現在のプロセスから削除し、インストール内容は保持
- 外部パッケージのみ削除可能。ソースプラグインは Debug ビルドが管理

## MCP 管理

| ツール | 操作 |
| --- | --- |
| `plugin_list` | ソース、インストール済み `.nest`、内蔵プラグインを一覧表示 |
| `plugin_manage` | install、uninstall、rename、設定の取得／保存／削除、有効化/無効化、コマンド一覧／実行 |

インストール元は `https://`、`http://`、`content://`、`file://`、読み取り可能なローカルパスに対応します。パス、サイズ、Manifest、API バージョン、DEX の検証は常に実行されます。

## `.nest` パッケージ

```text
example.nest
├── manifest.json
├── classes.dex
├── contributions/
├── pages/
└── assets/
```

インストール時に ZIP のパス脱出、エントリ数とサイズ、Manifest、API 互換性、`classes.dex` を検証します。展開後は読み取り専用のコードアーカイブとして読み込みます。

## セキュリティと現在の制限

- `.nest` の配布と DEX 実行は、まだ信頼できるアプリストアのセキュリティモデルではありません。
- 発行者署名、信頼レベル、原子的アップグレード／ロールバック、オンラインマーケットは未完成です。
- 外部プラグインはまだ独立プロセスで実行されません。
- プラグインはホストや他プラグインの秘密情報、任意ファイル、完全な Agent 履歴を取得できません。
- 対象アプリの互換性はベストエフォートです。NestSpace は主に AI 能力の検証を目的とし、インストールしたアプリの互換性や安全性を保証しません。

## 関連ドキュメント

- [プラグイン作成ガイド](plugin-authoring.md)
- [AI アシスタント](assistant.md)
- [MCP ツールリファレンス](mcp-tools.md)
- [アーキテクチャと制限](architecture.md)
