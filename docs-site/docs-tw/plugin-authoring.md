# NestSpace 外掛撰寫指南

本指南說明從原始碼開發到可散佈 `.nest` 套件的目前流程。正式 API 請見 [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api)。

## 1. 從範例開始

完整範例包含原始碼資源隔離、Agent 工具、設定、執行階段 Proxy 與封裝流程。

```bash
cp -R example-plugin/plugin dev-plugins/hello-plugin
```

加入功能前先修改：

- `plugin-assets/manifest.json` 的外掛 ID、名稱與版本；
- Gradle namespace；
- Java 套件；
- `entry_class` 與 Java 入口類別名稱。

外掛模組是 Android Library。原始碼模式應與宿主共用 SDK，而不是把 SDK 打包進外掛。

```kotlin
dependencies {
    compileOnly(project(":plugin-api"))
}
```

不要使用 `implementation(project(":plugin-api"))`，否則宿主與外掛可能出現兩份相同 API 型別。

## 2. 目錄結構

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

`plugin-assets/manifest.json` 才是 NestSpace 外掛註冊檔；`AndroidManifest.xml` 只用於 Android Library 建置。

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

- `id` 是設定、儲存、通知、快取與執行階段規則的永久命名空間。
- `entry_class` 必須是具無參數建構子的 Java 類別。
- `api_version` 目前必須等於宿主 API 主版本 `1`。
- `priority` 只影響 Hook 順序，不是權限等級。
- 未知權限與重複權限會導致安裝失敗。
- 圖示、頁面與貢獻檔路徑不能逃出外掛套件。

請使用最小權限。主要群組為 `agent`、`runtime`、`automation`、`storage`、`secrets`、`content`、`ui`、`notifications`、`network` 與 `device`。

## 4. 生命週期

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

`PluginContext` 不是 Android `Context`，只能透過它存取 Manifest 允許的 Host API。`onDetach` 應釋放網路、訂閱、任務、資料庫與 View。

## 5. 新增 Agent 工具

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

會修改外部狀態的工具應設為 `mutating=true`，以沿用宿主確認流程。工具 ID 只在外掛內唯一，宿主公開給 Agent/MCP 時會加入外掛命名空間。長工作應使用任務 API，不要無限期阻塞工具。

## 6. Agent Hook

主要擴充點：

- `AgentHookPlugin`：提示詞、模型前問答、模型請求；
- `AgentToolHookPlugin`：工具呼叫前後；
- `AgentToolPresentationPlugin`：結果卡片與自訂 View；
- `AgentToolProvider`：新工具；
- `PluginCommandProvider`：指令；
- `PluginLlmProvider`：模型 Provider；
- `PluginSpeechProvider`：語音 Provider。

問答與模型請求 Hook 能改變正常模型流程，因此需要 `agent` 權限，適用範圍應保持狹窄。

## 7. 宣告式設定

設定頁由宿主依 `PluginSettingView` 呈現，外掛不直接建立設定頁 View。於 `contributes.settings` 宣告 Schema，再透過 `host.settings()` 與 `host.secrets()` 存取相同命名空間。

API Key 等敏感資料應存入 `secrets()`，不要放進一般設定。

## 8. Host API

依權限可使用：

- 私有檔案、快取、設定、秘密與 SQLite；
- `host_permissions` 範圍內的 HTTP、SSE、WebSocket；
- 檔案選擇/匯出、分享與剪貼簿；
- 通知、指令、管理任務與事件；
- 受限制的 Android API；
- 宣告式頁面與結果 Renderer。

使用前請確認 [`plugin-api`](https://github.com/chuccp/NestSpace/tree/main/plugin-api) 中目前的 Capability 與方法。

## 9. LLM 與語音 Provider

`PluginLlmProvider` 接收正規化 messages、tools、串流設定與模型請求，回傳文字、工具呼叫、停止原因與使用量。使用者以 `plugin://<provider-id>` 選擇。

`PluginSpeechProvider` 回傳 PCM16LE 的 `PluginSpeechAudio`。播放、佇列、停止、AudioFocus 與喇叭輸出由宿主管理。

## 10. 執行階段 Proxy

`RuntimeProxyPlugin` 提供目標比對與啟動決策：

```text
CONTAINER | HOST_FALLBACK | BLOCKED
```

`versionCode` 支援精確值、前綴、通配符與區間。Application/Activity 的深入 Hook 使用 `RuntimeProfileProvider`。目標不符合時應安全失敗。

## 11. 偵錯原始碼外掛

Debug 建置會自動發現 `example-plugin/plugin` 與 `dev-plugins/*`。啟動 NestSpace 後開啟外掛中心，外掛會顯示「原始碼」標記。

若未出現，請檢查：

- 外掛 ID 是否重複；
- `entry_class`；
- Gradle namespace 與 Java 套件；
- `PluginSourceLoader` 記錄與 Manifest 驗證錯誤。

## 12. 封裝與分享

開發時可直接從 Android Studio 載入 Debug 原始碼外掛。要散佈時，在外掛中心使用「分享」或「匯出」產生 `.nest`。

```text
manifest.json
classes.dex
contributions/
pages/
assets/
```

目前安裝器會驗證路徑、大小、項目數、Manifest、API 版本與 DEX。發佈者簽章、信任儲存、原子更新/回滾與獨立外掛處理程序仍是後續工作。

## 13. 發佈前檢查

- Java 11 編譯與 R8/consumer rules；
- 最小權限與 Host 白名單；
- 問答/模型請求 Hook 的失敗回退；
- 變更工具的確認、逾時與取消；
- 設定/秘密持久化與 detach 清理；
- 網路取消與大小限制；
- 目標版本比對與 `BLOCKED` 策略；
- `.nest` 安裝、重啟恢復、停用、匯出與卸載清理。

## 相關文件

- [外掛平台](plugin-center.md)
- [AI 助手](assistant.md)
- [MCP 工具參考](mcp-tools.md)
- [範例外掛](https://github.com/chuccp/NestSpace/tree/main/example-plugin)
