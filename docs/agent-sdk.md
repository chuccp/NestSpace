# NestSpace Agent SDK（App 内逻辑包）

- 状态：通用 Agent 内核、多轮会话、可选能力包和运行治理已实现
- 位置：`app/src/main/java/com/coke/nestspace/agent/sdk`
- 形态：`:app` 内的逻辑包，不单独创建 Gradle 模块
- 包名：`com.coke.nestspace.agent.sdk`
- 依赖：Kotlin Coroutines、`org.json`
- 约束：SDK 包不得导入 Android、Compose 或 App 业务包

## 1. 定位

Agent SDK 是一套业务无关的“空房子”：

- 定义 Agent、对话、工具、事件、任务和能力契约；
- 提供可运行的工具调用循环；
- 提供多轮 Session、自动上下文压缩、运行取消/超时和可重放事件流；
- 提供有上限的工具并发、结构化附件、错误分类和版本化配置；
- 提供 Token 估算、Usage 记录、运行指标和资源释放；
- 提供通用记忆、技能、知识和异步任务能力；
- 不包含 NestSpace、容器、读屏、插件、具体 UI 或持久化实现。

业务数据、Android 文件/通知、模型凭据、工具实现和任务执行仍由 App 提供。

## 2. 目录

```text
agent/sdk/
  AgentDefinition.kt
  AgentBuilder.kt
  AgentConfigDocument.kt
  AgentChat.kt
  AgentChatServiceRegistry.kt
  AgentRuntime.kt
  DefaultAgentRuntime.kt
  AgentRunHandle.kt
  AgentEventRecord.kt
  AgentSession.kt
  AgentError.kt
  AgentTool.kt
  AgentToolExecutor.kt
  AgentPolling.kt
  AgentCapability.kt
  AgentApproval.kt
  AgentLaneScheduler.kt
  ContextCompressor.kt
  TokenEstimator.kt
  RecordingAgentChatService.kt
  RunMetrics.kt
  ResourceScope.kt
  hook/
    AgentConfirmationHook.kt
    AgentRunHook.kt
    AgentToolExecutionHook.kt

  tools/
    AgentToolSchema.kt
    AskUserQuestionTool.kt
    ClipboardTools.kt
    CurrentTimeTool.kt
    HandoffTool.kt
    HttpRequestTool.kt
    ScheduleTool.kt
    SpeechTools.kt
    SystemStatusTool.kt

  memory/
    MemoryModels.kt
    MemoryPolicy.kt
    MemoryCapability.kt
    MemoryExtraction.kt

  skill/
    SkillModels.kt
    SkillCapability.kt
    SkillDraftingCapability.kt

  knowledge/
    KnowledgeEntry.kt
    KnowledgeCapability.kt

  task/
    AgentTask.kt
    AgentTaskCapability.kt
    AgentTaskUi.kt
```

## 3. Agent 与运行

核心类型：

| 类型 | 职责 |
| --- | --- |
| `AgentKey` / `AgentLane` | Agent 身份与调度车道 |
| `AgentDefinition` | 工具策略、指令和元数据 |
| `AgentCatalog` | 注册 Agent，校验 Key 唯一 |
| `AgentRunRequest` | 输入、历史、上下文和 Chat 参数 |
| `AgentRunContext` | 会话、用户、Owner、来源、用户原话和扩展属性 |
| `AgentRuntime` | 可取消的挂起式运行接口 |
| `DefaultAgentRuntime` | 模型 → 工具 → 模型的多轮循环 |
| `AgentRunHandle` | 运行 ID、等待、取消、超时和事件订阅 |
| `AgentEvent` | 文本、工具、完成、失败和取消事件 |
| `AgentEventRecord` | 带单调序号的稳定事件记录 |
| `AgentRunResult` | 输出、完整消息、事件和结构化失败 |
| `AgentSession` | 多轮历史加载、串行执行与结果持久化 |

运行语义：

1. 解析 Agent；
2. 按 `AgentToolPolicy` 过滤工具；
3. 生成系统指令；
4. 调用 `AgentChatService`；
5. 有工具调用时执行并回传工具结果；
6. 没有工具调用时完成；
7. 到达轮次上限返回 `MAX_TOOL_ROUNDS`；
8. 协程取消写入一次 `Cancelled` 事件后继续向上传播。

`start()` 立即返回 `AgentRunHandle`。事件序号从 1 开始单调递增，可通过
`events(afterSequence)` 多订阅，也可用 `snapshotEvents()` 获取当前快照。
`cancelAfter()` 或 `AgentRuntimeConfig.runTimeoutMillis` 提供运行级超时；
超时属于取消语义，不会伪装成普通模型失败。

### 多轮 Session

```kotlin
val session = agent.session(
    context = AgentRunContext(sessionId = "chat-1"),
    historyStore = InMemoryAgentHistoryStore(),
    runStore = InMemoryAgentRunStore()
)

val first = session.run("第一步")
val second = session.run("继续")
```

同一 `AgentSession` 的轮次由 Mutex 串行化，第二轮会收到第一轮已提交的
User/Assistant 历史。成功运行会更新历史；失败运行只进入 RunStore，不把
未完成的用户请求污染下一轮历史。宿主的持久化实现只需实现
`AgentHistoryStore` 和可选的 `AgentRunStore`。

## 4. 统一对话输入

所有 Provider 接收同一个 `AgentChatInput`：

```kotlin
val input = AgentChatOptions(
    providerId = "provider-a",
    model = "model-a",
    maxOutputTokens = 4_096,
    temperature = 0.2,
    reasoningEffort = AgentReasoningEffort.MEDIUM,
    purpose = "agent"
).toInput(
    system = "You are a helpful agent.",
    messages = messages,
    tools = tools
)
```

`AgentChatInput` 包含：

- `model`；
- `system`；
- `messages`；
- `tools`；
- 输出预算、温度、推理强度、用途和 Provider 扩展属性。

消息和内容是 `AgentChatMessage` / `AgentChatBlock`：

- 文本；
- 工具调用；
- 工具结果；
- 图片；
- thinking；
- redacted thinking。

Provider 只需实现：

```kotlin
interface AgentChatService {
    val supportsVision: Boolean

    fun supportsVision(providerId: String?): Boolean =
        supportsVision

    suspend fun chat(request: AgentChatInput): AgentChatResponse

    suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse
}
```

`AgentChatServiceRegistry` 按 `providerId` 选择 Provider；未指定时使用首个注册项
或显式设置的默认项。视觉能力也按实际选中的 Provider 判断，避免默认文本模型
错误阻止另一个视觉模型接收图片。

`RecordingAgentChatService` 统一记录 Provider 返回的 Usage；没有 Usage 时由
`TokenEstimator` 保守估算。

## 5. 简单装配

使用 `agentSdk` DSL 只需配置核心参数即可得到可运行 Agent：

```kotlin
val assistant = agentSdk {
    key("assistant")
    displayName("Assistant")
    instructions("You are a concise assistant.")
    chatService(provider)
    model("model-a")
    maxOutputTokens(4_096)
    reasoningEffort(AgentReasoningEffort.MEDIUM)
    maxToolRounds(8)

    tool(myTool)
    capability(MemoryCapability(memoryStore))

    features(
        AgentFeatureConfig(
            allowedTools = setOf("current_time", "memory_search"),
            deniedTools = setOf("dangerous_tool"),
            deniedCapabilities = setOf("skills")
        )
    )
}

val result = assistant.run("hello")
```

需要从文件或服务端下发配置时，可使用版本化文档：

```kotlin
val document = AgentConfigDocument.fromJson(configJson)
val assistant = agentSdk {
    config(document)
    chatService(provider)
    tools(hostTools) // 宿主按文档中的工具 ID 解析实现
}
```

`AgentConfigDocument` 只序列化 Agent 身份、指令、工具/能力开关、运行参数和
Chat 参数。Provider、工具处理器、存储和 Hook 是可执行依赖，不进入配置；
宿主用 ID 解析并注册它们。当前版本为 `1`，未知版本会在构建 Agent 前明确失败，
不会静默套用错误配置。

Builder 会自动组装：

- `AgentCatalog`；
- `AgentToolCatalog`；
- `AgentCapabilityCatalog`；
- `DefaultAgentRuntime`；
- `AgentRuntimeConfig`；
- 默认 Chat 参数。

工具和能力都可以通过配置关闭。被关闭的能力不会暴露工具，也不会注入能力指令。

## 6. 能力包

Agent 能力被建模为 `AgentCapability`：

```kotlin
interface AgentCapability {
    val id: String
    val description: String
    fun tools(): List<AgentTool>
    fun instructions(): String
}
```

`AgentCapabilityCatalog` 可以合并能力工具和指令，并校验能力 ID 唯一。
`AgentCapabilityPolicy` 提供 `allowed` / `denied`，`denied` 优先。

能力与工具的关系：

- 能力是产品级功能包；
- 能力通过一个或多个 `AgentTool` 暴露给模型；
- 能力可以被宿主翻译成自己的工具格式；
- 能力不直接依赖 Android、数据库或 UI。

## 7. 记忆能力

`memory/MemoryCapability.kt` 提供：

- `memory_search`；
- `memory_save`；
- `memory_forget`。

通用组成：

- `MemoryKind`：事实、偏好、做法；
- `MemoryItem`：内容、关键词、来源、审批和置顶；
- `MemoryPolicy`：长度、敏感信息、用户原话校验；
- `MemoryRanking`：中文双字索引与相关性排序；
- `MemoryContextFormatter`：上下文注入格式；
- `AgentMemoryStore`：宿主存储接口。

宿主只负责持久化。当前 App 使用加密文件和 Android Context，存储实现仍位于
`agent/memory/MemoryStore.kt`。

`MemoryExtractionCapability` 提供模型驱动的记忆抽取：

- 只在用户原话命中了记忆线索时运行；
- 校验 quote 必须来自用户原话；
- 去重、相似流程过滤；
- 敏感信息过滤；
- 审批策略由消费方注入。

## 8. 技能能力

`skill/SkillCapability.kt` 提供：

- `skill_list`；
- `skill_read`；
- `skill_save`。

通用组成：

- `SkillItem` / `SkillRevision`；
- `SkillPolicy`：名称和正文规范；
- `SkillOutcomePolicy`：连续失败自动停用；
- `AgentSkillStore`：列表、保存、启停、结果记录和版本恢复接口。

内置技能资产加载仍属于 App；SDK 不知道资源目录或 Android AssetManager。

`SkillDraftingCapability` 提供模型驱动的技能草稿：

- 只处理达到最小步骤数的成功任务；
- 可配置忽略工具；
- 复用名称、敏感信息和正文长度策略；
- 新技能默认 disabled，由宿主展示启用入口。

## 9. 知识能力

`knowledge/` 提供：

- `KnowledgeEntry`：App/网站指南；
- `KnowledgeMarkdownParser`：解析 front matter；
- `KnowledgeIndex`：包名/域名确定性匹配；
- `KnowledgeCapability`：根据 `KnowledgeTarget` 解析并附加指南。

具体业务工具名到知识目标的映射留在 App，例如 `space_launch` 对应
`KnowledgeTarget.App`，`browser_open` 对应 `KnowledgeTarget.Website`。

## 10. Worker 与任务能力

`task/AgentTaskController` 定义通用异步任务协议：

- `start`；
- `list`；
- `status`；
- `supplement`；
- `pause`；
- `resume`；
- `cancel`；
- `cancelQueued`。

`AgentTaskCapability` 提供：

- `spawn_agent`；
- `agent_task_list`；
- `agent_task_status`；
- `agent_task_supplement`；
- `agent_task_pause`；
- `agent_task_resume`；
- `agent_task_cancel`；
- `agent_task_cancel_queued`。

SDK 只定义请求、任务快照和状态；App 的
`NestSpaceAgentTaskController` 把它适配到现有 `AssistantTaskCoordinator`。

`AgentLaneScheduler` 提供每车道 FIFO 和单活动任务约束：

- 不同 `AgentLane` 可并行；
- 同一车道严格 FIFO；
- 暂停任务继续占用车道；
- 状态机不负责 Android Job、通知或持久化。

`AgentTaskUiState` / `AgentTaskPrimaryAction` / `agentTaskControls` 提供通用任务动作映射，
Compose 层只需渲染。

## 11. 通用工具

工具不是“模型能力不足时的补丁”。只有满足以下至少一项时，才应该暴露为工具：

- 读取模型无法知道的权威外部状态，例如当前时间、设备状态、文件内容；
- 执行模型本身无法执行的动作，例如写入、发送、安装、打开页面；
- 访问受权限、网络或宿主生命周期控制的能力；
- 必须由宿主保证确定性、审计性或安全边界的高风险操作。

以下内容不单独做工具：

- 翻译、改写、总结、分类、代码解释；
- 模型直接知道的通用知识问答；
- 普通四则运算和简单文本处理；
- JSON 格式化、大小写转换等可由模型直接生成的内容。

`CurrentTimeTool` 只提供 `current_time`，因为真实时间属于外部权威状态：

```kotlin
val currentTime = CurrentTimeTool(
    clock = clock,
    zoneId = zoneId,
    locale = locale
).tool
```

Clock 和 Zone 可注入，测试不依赖系统时间。时区、地区和当前时刻不应由模型猜测。

其他放在 `tools/` 的通用工具均通过宿主接口注入实现：

| 工具 | 宿主接口 | 作用 |
| --- | --- | --- |
| `system_status` | `SystemStatusProvider` | 电量、网络、内存等权威宿主状态 |
| `clipboard_read/write` | `ClipboardProvider` | 读取或替换剪贴板 |
| `http_request` | `HttpToolClient` | 外部 HTTP 请求，网络策略由宿主控制 |
| `speech_speak/stop/status` | `SpeechProvider` | 语音播放真实副作用 |
| `schedule_task` | `ScheduleController` | 创建和控制定时任务 |
| `hand_to_user` | `HandoffHandler` | 把登录、验证码、支付等步骤交给用户 |
| `ask_user_question` | `AgentQuestionHandler` | 缺少信息或需要用户选择时暂停并收集结构化答案 |

### 结构化工具附件

`AgentToolResult.attachments` 用于工具产生的真实文件、图片或引用，不是把普通
文本回答包装成附件的捷径。附件可携带业务无关的 `metadata`，供宿主展示来源、
尺寸、下载状态等信息。典型场景是截图、下载文件、生成的图片、本地文档和可追踪
资源 URI。

`AgentAttachmentAudience` 决定附件去向：

- `MODEL`：只进入下一轮模型上下文；
- `USER`：只进入 `AgentRunResult`，供宿主 UI 展示或下载；
- `BOTH`：两边都可使用。

`USER` 附件不会进入模型 Prompt。视觉模型收到图片 Block；不支持视觉的模型只收到
文本占位说明。文件默认以名称、MIME 和 URI 描述进入模型上下文，只有明确是图片、
有 Base64 且目标 Provider 支持视觉时才转为图片 Block。

## 12. 确认与审批

`AgentApprovalLevel`、`AgentApprovalCategory` 和 `AgentApprovalSettings` 提供纯策略：

- `STRICT`；
- `STANDARD`；
- `LOOSE`；
- `CUSTOM`；
- `DEVELOPER`。

`AgentConfirmationHook` 是 ToolExecutor 的前置确认 Hook，不是模型工具。
`AgentQuestionHandler` 才负责补充信息和方案选择。Android Dialog、通知和前台状态由
App 实现。当前确认适配器是 `AppAgentConfirmationHook`。

`AgentToolExecutionHook` 提供通用 `beforeExecute` / `afterExecute` 链，可用于审计、
参数改写、阻断和结果转换；确认 Hook 专门负责需要用户决定的前置检查。
Hook 属于 Agent 运行时功能，不是 Capability；Capability 只提供工具和指令。

`AgentToolExecutor` 统一处理：

- 工具确认；
- before/after Tool Hook；
- 单次工具超时；
- 显式配置的有限重试；
- 工具异常归一化。
- `pending` 结果的自动轮询；
- `POLL_TIMEOUT` 统一失败语义。
- Confirmation/Before/After Hook 异常归一化为工具错误；

重试默认是 1 次；有外部副作用的工具必须显式开启有限重试。
轮询只允许只读工具配置，不允许通过重试重复执行有副作用的操作。轮询不是模型工具，
模型不会看到 `poll_tool`。

## 13. 上下文与资源

`ContextCompressor` 提供确定性上下文压缩：

- `DefaultAgentRuntime` 在每次模型调用前按 `contextBudgetTokens` 自动执行；
- 不拆散工具调用/结果；
- 不截断用户消息；
- 旧工具结果和旧助手文本按级别缩短；
- 最近步骤保持完整；
- 支持预算判断和 Token 估算。

工具并发由 `AgentRuntimeConfig.maxParallelTools` 控制。并发调用仍按模型原始调用
顺序回填 ToolResult，事件序号保持单调，完成事件按真实完成顺序发出。

其他通用能力：

- `TokenEstimator`：统一 Token 估算；
- `RunMetrics`：模型请求、工具调用和 Usage 累计；
- `ResourceScope`：逆序释放、超时和异常聚合；
- `AgentLaneScheduler`：通用队列/active 状态。

## 14. NestSpace 接入

App 通过适配器连接 SDK：

- `agent/definitions/NestSpaceAgents.kt`：业务 Agent 定义；
- `agent/llm/*`：Provider 实现和配置映射；
- `agent/runtime/NestSpaceAgentTaskController.kt`：Worker 启动与控制；
- `agent/tools/AgentCapabilityAdapters.kt`：`AgentTool` → App `ToolSpec`；
- `agent/memory/MemoryStore.kt`、`SkillStore.kt`：Android 持久化；
- `agent/knowledge/KnowledgeStore.kt`：Android Asset 加载和业务工具映射。

现有的 `LlmAgentRuntime` 继续承载插件 Hook、截图、人工确认和宿主摘要等业务逻辑，
但 Chat 协议、上下文压缩、Token 统计、Agent 定义和工具策略已经使用 SDK。

## 15. 与 `go-agent-sdk` 的关系

采用：

- Agent 定义/运行上下文/Provider 分离；
- 统一 `Messages` 输入；
- 文本、工具调用和工具结果 Block；
- 流式事件与完整响应并存；
- 工具循环、异常回传和取消传播；
- 任务与工具能力可插拔。

没有照搬：

- REST / WebSocket Server；
- 多客户端游标和断线续传；
- Go 的 Session/Store/Transfer 实现；
- 数据库、超时守护和具体传输；
- 命令、HTTP、搜索等宿主工具。

## 16. 完成度与明确边界

核心能力已经闭合：

| 能力 | 实现 |
| --- | --- |
| Session / 历史 | `AgentSession`、`AgentHistoryStore`、失败轮不污染历史 |
| 运行生命周期 | `AgentRunHandle`、取消、超时、Run ID、最终事件 |
| 事件流 | 单调序号、历史回放、多订阅、序列快照 |
| Runtime Hook | beforeRun、beforeModel、afterModel、completed/failed/cancelled |
| 自动上下文压缩 | 每次模型调用前按预算执行，不修改调用方输入 |
| 结构化附件 | 图片、文件、引用和 MODEL/USER/BOTH 路由 |
| 多 Provider | Registry 路由、默认 Provider、按 Provider 判断视觉能力 |
| 工具管线 | 确认、Hook、超时、重试、轮询、有限并发、异常归一化 |
| 运行持久化抽象 | `AgentRunStore` 和公开的内存实现 |
| 配置 | 版本化 JSON、工具/能力开关、运行与 Chat 参数 |
| 错误 | 认证、限流、超时、上下文、请求、Provider、配置、启动和工具分类 |

以下内容是有意留给宿主或未来独立产品的能力，不当作 SDK 缺失：

- 不内置 OpenAI/Anthropic HTTP 客户端和凭据管理；
- 不内置 Provider 级限流、计费、熔断和统一网络重试策略；
- 不实现 REST/WebSocket Server、多客户端断线续传；
- 不提供数据库、文件或 Android 持久化实现；
- 不通过序列化执行工具处理器、Store 或 Hook；
- 不加入 NestSpace 容器、读屏、插件或 MCP 业务实现；
- 当前只有配置版本 `1`，因此不提前制造没有真实旧版本的迁移代码。

## 17. 验证

SDK 包当前包含 84 个单元测试，覆盖：

- Agent/工具/能力目录；
- 多轮工具调用；
- 参数透传和策略过滤；
- 文本流、工具异常、轮次上限、模型失败和取消；
- 上下文压缩和 Token 估算；
- 运行指标和资源释放；
- 分车道调度；
- 记忆存储、敏感信息、原话校验和排序；
- 技能读取与失败停用策略；
- 知识和 Markdown 解析；
- Worker 启停与任务快照；
- 可注入时钟；
- 简单 DSL 装配；
- 工具/能力配置开关；
- 审批策略；
- 记忆抽取与技能草稿；
- 工具确认、超时和重试；
- 工具结果 `pending` 与自动轮询；
- 通用外部状态和动作工具；
- Tool before/after Hook 及 Hook 异常隔离；
- Session 串行化、历史提交和失败隔离；
- 运行取消、超时、事件序号和事件回放；
- Runtime Hook 请求/响应改写和生命周期失败；
- 自动上下文压缩与输入不可变性；
- 有限工具并发、结果顺序和附件 audience；
- Provider 路由与 Provider 级视觉能力；
- 配置 JSON 往返、版本校验和 Builder 应用。

当前 App 测试总数：322。

验证命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleSnapshot
./gradlew :app:assembleRelease
./gradlew :plugin-api:test :plugin-api:assembleDebug
./gradlew :testapp:assembleDebug :testapp:assembleSecure
```

`./gradlew :app:lintDebug` 仍停在项目既有失败基线：193 errors、353 warnings、
2 hints；本轮 SDK 文件没有被 lint 报告。没有通过新增全局 ignore 或 baseline
掩盖问题。
