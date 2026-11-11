# NestSpace 使用手冊

> 在 Android 上執行應用程式層級虛擬空間的實驗性開源專案。

[快速開始](getting-started.md) · [應用程式安裝與管理](app-manager.md) · [常見問題](troubleshooting.md) · [GitHub](https://github.com/chuccp/NestSpace)

![NestSpace 桌面](screenshots/desktop.jpg)

## 為什麼選擇 NestSpace

- **不用開啟無障礙服務**：介面觀察、點擊和輸入由應用程式內部的通道直接完成。
- **不用 adb**：安裝 APK、多開、整理桌面和使用 AI 助手都能直接在手機上完成。
- **不用 root**：像一般應用程式一樣安裝即可使用，不修改系統。
- **Android 版本要求寬鬆**：支援 Android 7.0（API 24）及以上，7.1、15、16、17 都已驗證。

## 先了解這些限制

- **NestSpace 主要用於驗證 AI 能力**：它不是通用相容容器，不保證所安裝 App 的相容性和安全性。加固、native SDK、熱更新等都可能造成問題；遇到問題歡迎提出 issue。
- **同時執行的虛擬應用程式有數量限制**：受手機記憶體和容器槽位限制，最多同時執行 16 個容器處理程序。預設自動管理，記憶體緊張時會關閉最久未使用的背景容器。

## 手冊導覽

### 使用

| 文件 | 內容 |
| --- | --- |
| [快速開始](getting-started.md) | 安裝、第一次開啟、匯入應用程式、啟動和多開 |
| [應用程式安裝與管理](app-manager.md) | APK / split 安裝、驗證規則、執行個體與資料隔離、卸載 |
| [桌面與外觀](desktop.md) | 桌面圖示、資料夾、排序、壁紙、執行管理 |
| [設定詳解](settings.md) | 空間設定裡的每個入口和選項 |
| [AI 助手](assistant.md) | 模型設定、串流對話、語音助手、自動記憶/技能草擬、定時任務 |
| [內建瀏覽器](browser.md) | 分頁、無頭模式、下載上傳、代理、轉人工 |
| [MCP 控制服務](mcp.md) | 電腦連線、Token、區域網路、CLI、外部 MCP |
| [常見問題](troubleshooting.md) | 安裝失敗、黑屏、背景被回收、連線失敗等 |

### 參考

| 文件 | 內容 |
| --- | --- |
| [架構與限制](architecture.md) | 容器實作、處理程序模型和已知邊界 |
| [外掛平台與 Java SDK](https://github.com/chuccp/NestSpace/blob/main/docs/plugin-center.md) | 外掛中心、`.nest` 套件、Agent 工具/Hook、Host API、Provider 和執行階段 Profile |
| [外掛撰寫指南](https://github.com/chuccp/NestSpace/blob/main/docs/plugin-authoring.md) | 原始碼外掛、Manifest、Java API、設定、測試和封裝 |
| [MCP 工具參考](mcp-tools.md) | 全部 MCP 工具和參數 |
| [App / 網站知識包](knowledge-packs.md) | 知識包的格式、匹配和新增方法 |
| [從原始碼建置](building.md) | 環境需求、建置命令和 CI |
| [nestspace-mcp CLI](cli.md) | 命令列用戶端的安裝和使用 |
| [技術設計](android-virtual-space-agent-mcp.md) | 完整技術方案（開發參考） |

## 約定

- 文件中的「空间设置」（空間設定）是指桌面上的空間設定圖示。
- 「应用管理」（應用程式管理）、「模型设置」（模型設定）等都是空間設定裡的第二層頁面。
- NestSpace 是主要用於驗證 AI 能力的實驗性專案，不保證所安裝 App 的相容性和安全性，同時執行數量也有邊界，詳見[架構與限制](architecture.md)。

## License

[Apache License 2.0](https://github.com/chuccp/NestSpace/blob/main/LICENSE)
