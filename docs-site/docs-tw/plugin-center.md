# NestSpace 外掛平台與 Java SDK

NestSpace 外掛是 Java Android 模組，可擴充 AI 助手、外掛中心、Host API、模型與語音 Provider，以及目標應用程式專用的執行階段相容處理，而不必為每個擴充修改主程式。

實際開發流程請見[外掛撰寫指南](plugin-authoring.md)。

## 目前實作

| 區域 | 狀態 |
| --- | --- |
| 公開 `plugin-api` | Java 11 Android Library |
| Manifest 與權限 | 解析、驗證、權限分組與執行階段檢查 |
| 原始碼外掛 | Debug 建置自動載入 |
| 外部外掛 | 從 `.nest` 套件安裝 |
| 外掛中心 | 搜尋、詳細資料、設定、啟用/停用、重新命名、分享、匯出、刪除 |
| Agent Hook/Tool | 提示詞、問答、模型請求、工具與呈現 Hook |
| Host API | 儲存、秘密、快取、SQLite、檔案、分享、剪貼簿、通知、HTTP、SSE、WebSocket、Android API 門面 |
| Provider | LLM Provider、Speech Provider |
| 執行階段 Proxy | 啟動策略、類別索引、Application/Activity Hook、版本比對 |
| MCP 管理 | `plugin_list`、`plugin_manage` |

## 模組關係

```text
:app -> :plugin-api
```

`example-plugin/plugin` 與 `dev-plugins/*` 在 Debug 建置中作為原始碼外掛載入，共用宿主 ClassLoader，因此日常開發不需要先產生 `.nest`。

外部套件會經過驗證、解壓、`DexClassLoader` 載入，並在重新啟動後恢復。同 ID 的原始碼外掛優先於外部套件。

## Manifest 與權限

外掛註冊檔是 `manifest.json`，不是 Android 的 `AndroidManifest.xml`。

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

| 權限 | 展開能力 |
| --- | --- |
| `agent` | Agent 提示詞、問答、模型請求、工具、工具 Hook、呈現、LLM/語音 Provider |
| `runtime` | 啟動策略與目標 Application/Activity Hook |
| `automation` | 指令、管理任務與事件 |
| `storage` | 私有檔案、設定、快取與 SQLite |
| `secrets` | Keystore 加密秘密 |
| `content` | 檔案選擇/匯出、分享與剪貼簿 |
| `ui` | 宣告式頁面與結果 Renderer |
| `notifications` | 外掛通知 |
| `network` | HTTP、SSE、WebSocket 與 Host 白名單 |
| `device` | 受限制的 Android API 門面 |

舊細粒度權限仍可解析，但新外掛應使用權限群組。

## 擴充點

### Agent

- 提示詞貢獻會加入獨立的 Agent 區塊。
- 模型前問答 Hook 可直接回答，或交回正常模型流程。
- 模型請求 Hook 可檢查、補足或阻擋請求。
- 工具前 Hook 可修改參數或阻擋呼叫；後 Hook 只能觀察結果。
- 結果呈現可使用文字、JSON Renderer 或外掛建立的 Android View。

外掛工具會進入相同的 `ToolRegistry`，本機 Agent 與遠端 MCP 都可使用，權限確認與任務生命週期也共用。

### Host API

在宣告權限內可使用：

- 私有檔案、快取、設定、SQLite 與 Keystore 秘密；
- 受 Manifest Host 限制的 HTTP、SSE、WebSocket；
- 檔案選擇/匯出、分享、剪貼簿與通知；
- 受限制的 Android API、外掛任務與事件；
- 由宿主呈現的宣告式 `PluginSettingView`。

外掛不能取得宿主 Application、任意 Activity、其他外掛的儲存空間、API Key 或無限制網路。

### LLM 與語音 Provider

LLM Provider 在模型設定中使用：

```text
plugin://<provider-id>
```

上游憑證由外掛保存。宿主會傳入正規化 messages、tools 與串流設定。

Speech Provider 只回傳 PCM16LE 音訊。AudioFocus、播放佇列、停止、快取與喇叭輸出由宿主管理，並提供 `speech_speak`、`speech_stop`、`speech_status`。

## 執行階段 Proxy

`RuntimeProxyPlugin` 可依套件名稱、`versionCode` 精確值/前綴/通配符/區間與類別名稱比對目標，並選擇：

- `CONTAINER`：一般容器；
- `HOST_FALLBACK`：啟動宿主已安裝的應用程式；
- `BLOCKED`：附原因拒絕啟動。

較深的 Hook 使用 `RuntimeProfileProvider`，由宿主隨外掛生命週期自動註冊與解除。

## 外掛中心

外掛中心是底部 Dock 之外的獨立桌面入口。

- 點擊：查看版本、發佈者、Capability、詳細頁與設定。
- 長按：啟用/停用、設定、重新命名、分享、匯出或刪除。
- 停用：從目前處理程序移除 Hook、工具、Provider、設定與執行階段 Profile，保留安裝內容。
- 只有外部套件可刪除；原始碼外掛由 Debug 建置管理。

## MCP 管理

| 工具 | 操作 |
| --- | --- |
| `plugin_list` | 列出原始碼、已安裝 `.nest` 與內建外掛 |
| `plugin_manage` | install、uninstall、rename、讀取/寫入/移除設定、啟用/停用、列出/執行指令 |

安裝來源支援 `https://`、`http://`、`content://`、`file://` 與可讀取的本機路徑。安裝仍會驗證路徑、大小、Manifest、API 版本與 DEX。

## `.nest` 套件

```text
example.nest
├── manifest.json
├── classes.dex
├── contributions/
├── pages/
└── assets/
```

安裝時會檢查 ZIP 路徑逃逸、項目數與大小、Manifest、API 相容性與 `classes.dex`，解壓後以唯讀程式碼封裝載入。

## 安全與目前限制

- `.nest` 下載與 DEX 執行尚未形成可信的應用商店安全模型。
- 發佈者簽章、信任等級、原子更新/回滾與線上商店尚未完成。
- 外部外掛尚未在獨立處理程序執行。
- 外掛不能讀取宿主或其他外掛的秘密、任意檔案、完整 Agent 歷史或執行任意 shell。
- 目標應用程式相容性為盡力支援。NestSpace 主要用於驗證 AI 能力，不保證所安裝 App 的相容性和安全性。

## 相關文件

- [外掛撰寫指南](plugin-authoring.md)
- [AI 助手](assistant.md)
- [MCP 工具參考](mcp-tools.md)
- [架構與限制](architecture.md)
