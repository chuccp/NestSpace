# NestSpace プラグイン作成ガイド

このガイドでは、ソース開発から配布可能な `.nest` パッケージまでの現在の手順を説明します。正式な API は [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api) を参照してください。

## 1. サンプルから開始

完全なサンプルには、ソースリソース分離、Agent ツール、設定、ランタイムプロキシ、パッケージ作成が含まれます。

```bash
cp -R example-plugin/plugin dev-plugins/hello-plugin
```

機能追加前に次を変更します。

- `plugin-assets/manifest.json` のプラグイン ID／名前／バージョン
- Gradle namespace
- Java パッケージ
- `entry_class` と Java エントリクラス名

プラグインモジュールは Android Library です。ソースモードでは SDK をパッケージせず、ホストと共有します。

```kotlin
dependencies {
    compileOnly(project(":plugin-api"))
}
```

`implementation(project(":plugin-api"))` は使用しないでください。ホストとプラグインで同じ API 型を共有する必要があります。

## 2. ディレクトリ

```text
dev-plugins/hello-plugin/
├── build.gradle.kts
├── consumer-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/hello/HelloPlugin.java
    └── plugin-assets/
        ├── manifest.json
        ├── icons/icon.png
        ├── pages/detail.html
        └── contributions/
            ├── tools.json
            ├── commands.json
            └── settings.schema.json
```

`plugin-assets/manifest.json` が NestSpace のプラグイン登録情報です。`AndroidManifest.xml` は Android Library のビルド用です。

## 3. Manifest

```json
{
  "manifest_version": 1,
  "id": "com.example.hello",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "publisher": "Example Studio",
  "description": "Example NestSpace plugin",
  "api_version": 1,
  "min_host_version": "1.0.7",
  "entry_class": "com.example.hello.HelloPlugin",
  "priority": 100,
  "permissions": ["agent", "storage"],
  "host_permissions": ["https://api.example.com/*"],
  "icons": {"128": "icons/icon.png"},
  "ui": {"html_page": "pages/detail.html"},
  "contributes": {
    "agent_tools": "contributions/tools.json",
    "settings": "contributions/settings.schema.json"
  }
}
```

- `id` は設定、ストレージ、通知、キャッシュ、ランタイム規則の永続的な名前空間です。
- `entry_class` は引数なしコンストラクタを持つ Java クラスを指定します。
- `api_version` は現在ホスト API メジャーバージョン `1` と一致する必要があります。
- `priority` は Hook の順序だけを変え、権限レベルではありません。
- 不明な権限や重複権限はインストール時に拒否されます。
- アイコン、ページ、貢献ファイルのパスはパッケージ外へ脱出できません。

必要最小限の権限を宣言してください。主なグループは `agent`、`runtime`、`automation`、`storage`、`secrets`、`content`、`ui`、`notifications`、`network`、`device` です。

## 4. ライフサイクル

```java
public final class HelloPlugin implements PluginLifecycle {
    private PluginContext context;

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }
}
```

`PluginContext` は Android の `Context` ではありません。Manifest で許可された Host API のみ利用できます。`onDetach` ではネットワーク、購読、タスク、データベース、View などを解放してください。

## 5. Agent ツール

```java
public final class HelloPlugin implements
    PluginLifecycle,
    AgentToolProvider {

    private PluginContext context;

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        context = null;
    }

    @Override
    public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
        String schema =
            "{\"type\":\"object\",\"properties\":{" +
            "\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        return CompletableFuture.completedFuture(List.of(
            new PluginToolSpec(
                "hello",
                "Say hello",
                "Return a greeting for the supplied name",
                schema,
                false
            )
        ));
    }

    @Override
    public CompletableFuture<PluginToolResult> invokeAsync(
        PluginToolCall call
    ) {
        String name = new JSONObject(call.getArgumentsJson())
            .optString("name", "world");
        return CompletableFuture.completedFuture(
            PluginToolResult.success("Hello, " + name)
        );
    }
}
```

外部状態を変更するツールは `mutating=true` にし、通常の確認フローを有効にします。ツール ID はプラグイン内で一意です。長い処理はタスク API を使用し、ツール呼び出しを無期限にブロックしないでください。

## 6. Agent Hook

主な拡張点:

- `AgentHookPlugin`：プロンプト、モデル前質問、モデル要求
- `AgentToolHookPlugin`：ツール呼び出しの前後
- `AgentToolPresentationPlugin`：結果カードとカスタム View
- `AgentToolProvider`：新しいツール
- `PluginCommandProvider`：コマンド
- `PluginLlmProvider`：LLM Provider
- `PluginSpeechProvider`：音声 Provider

質問 Hook やモデル要求 Hook は通常のモデル処理を変更できるため、`agent` 権限が必要で、適用範囲を狭くしてください。

## 7. 宣言型設定

設定画面の View はプラグインが作成せず、ホストが `PluginSettingView` を描画します。`contributes.settings` で設定 Schema を宣言し、`host.settings()` と `host.secrets()` で同じ名前空間を読み書きします。

API Key などは通常設定ではなく `secrets()` に保存してください。

## 8. Host API

権限に応じて次を利用できます。

- 専用ファイル、キャッシュ、設定、秘密情報、SQLite
- `host_permissions` の範囲内の HTTP、SSE、WebSocket
- ファイル選択／書き出し、共有、クリップボード
- 通知、コマンド、管理タスク、イベント
- 制限付き Android API
- 宣言型ページと結果 Renderer

利用前に [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api) の現在の Capability とメソッドを確認してください。

## 9. LLM と音声 Provider

`PluginLlmProvider` は正規化された messages、tools、ストリーミング設定、モデル要求を受け取り、テキスト、ツール呼び出し、停止理由、使用量を返します。ユーザーは `plugin://<provider-id>` で選択します。

`PluginSpeechProvider` は PCM16LE の `PluginSpeechAudio` を返します。音声再生、キュー、停止、AudioFocus、スピーカー出力はホストが管理します。

## 10. ランタイムプロキシ

`RuntimeProxyPlugin` は対象照合と起動方法を提供します。

```text
CONTAINER | HOST_FALLBACK | BLOCKED
```

`versionCode` は完全一致、接頭辞、ワイルドカード、範囲に対応します。Application/Activity の詳細 Hook には `RuntimeProfileProvider` を使用します。対象が一致しない場合は安全側で失敗させてください。

## 11. ソースプラグインのデバッグ

Debug ビルドでは `example-plugin/plugin` と `dev-plugins/*` が自動検出されます。NestSpace を起動し、プラグインセンターを開くと「ソース」バッジ付きで表示されます。

表示されない場合:

- プラグイン ID の重複
- `entry_class`
- Gradle namespace と Java パッケージ
- `PluginSourceLoader` のログと Manifest 検証エラー

## 12. パッケージと共有

開発中は Android Studio から Debug ソースプラグインを直接読み込めます。配布時はプラグインセンターの「共有」または「エクスポート」から `.nest` を生成します。

```text
manifest.json
classes.dex
contributions/
pages/
assets/
```

現在のインストーラはパス、サイズ、エントリ数、Manifest、API バージョン、DEX を検証します。発行者署名、信頼ストア、原子的更新／ロールバック、独立プラグインプロセスは今後の課題です。

## 13. 公開前チェック

- Java 11 と R8／consumer rules
- 最小権限と Host ホワイトリスト
- 質問／モデル要求 Hook の失敗時 fallback
- 変更ツールの確認、タイムアウト、キャンセル
- 設定と秘密情報の永続化、detach 時の解放
- ネットワークのキャンセルとサイズ制限
- 対象バージョン照合と `BLOCKED` ポリシー
- `.nest` のインストール、再起動復元、無効化、エクスポート、削除

## 関連ドキュメント

- [プラグインプラットフォーム](plugin-center.md)
- [AI アシスタント](assistant.md)
- [MCP ツールリファレンス](mcp-tools.md)
- [サンプルプラグイン](https://github.com/chuccp/NestSpace/tree/main/example-plugin)
