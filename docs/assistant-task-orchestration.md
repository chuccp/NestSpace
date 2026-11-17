# AI 助手任务与子 Agent 编排设计

状态：设计草案（可靠性基线已补齐）  
适用范围：本地 AI 助手、语音助手、远程 MCP 发起的助手任务

## 1. 目标

把 AI 助手从“一个长期运行的会话”改造成“主代理 + 任务队列 + 单交互 Worker”的结构。

目标如下：

- 简单问答由主代理直接回答，不进入交互任务队列；
- 复杂任务创建为独立任务，由子 Agent Worker 执行；
- 同一时间只运行一个需要操作手机 UI 的交互任务；
- 新任务按顺序排队，补充消息归属于当前任务；
- 主代理和用户都可以查询进度、暂停、继续和取消任务；
- 尚未执行的任务不会提前创建 Worker；
- 用户可以取消排队任务或运行中的任务；
- 执行过程通过结构化事件展示，主代理和用户都能按需展开；
- 重新进入助手时，新主代理必须先接收后台任务交接摘要，再处理新的用户消息；
- 新对话只根据场景绑定相关任务，不自动继承上一对话的任务上下文；
- 进程重启后不自动重放手机操作。

非目标：

- MVP 不支持多个交互 Worker 并行操作同一个虚拟空间；
- MVP 不允许子 Agent 再创建子 Agent；
- 不展示模型隐藏思维链，只展示可审计的动作和结果事件；
- 不允许远程 MCP 客户端控制其他客户端或本地用户创建的任务。

## 2. 核心概念

### 2.1 主代理

主代理是用户消息的唯一入口，负责：

- 判断消息是简单回答、状态查询、任务补充还是新任务；
- 回答不需要操作手机的简单问题；
- 创建任务并决定是否交给 Worker；
- 查询任务、队列和 Worker 进度；
- 向运行中的 Worker 追加补充；
- 根据用户指令暂停、继续或取消任务；
- 在 Worker 完成、失败或等待用户时生成简短总结。

主代理不持有交互任务整个生命周期的 `busy` 状态。Worker 运行时，主代理仍可处理状态查询和简单问答。

主代理实例使用 `controller_id` 标识。页面重建、重新进入助手或主代理上下文被重新创建后，新的
`controller_id` 必须先绑定当前会话任务，不能假定自己知道后台任务做了什么。

### 2.2 任务

任务是排队、持久化、暂停和取消的基本单位。任务至少包含：

- `task_id`
- `conversation_id`
- `owner_id`
- 标题和目标
- 来源：本地、语音、定时任务或 MCP
- 模型配置快照
- 当前状态
- 优先级和入队序号
- 任务能力快照和预算快照
- 显示模式
- 当前 Worker ID
- 创建、开始、暂停、更新和完成时间
- 最终结果或错误

任务在 `QUEUED` 状态只保存意图和上下文，不创建真实 Worker、LLM 循环或容器操作 Job。
`queue_position` 是展示字段，由当前优先级、队列和运行状态实时计算；持久化只保存 `priority` 与
单调递增的 `queue_sequence`。

### 2.3 Worker 子 Agent

Worker 是任务的执行器，只在任务真正获得交互执行权时创建。

Worker 应：

- 使用宿主统一的 ToolRegistry 和权限系统；
- 能使用完成复杂任务所需的读写工具，而不局限于当前只读子 Agent 工具集；
- 独占当前交互资源；
- 在每次模型步骤和工具步骤前检查暂停、取消和补充队列；
- 持续产生结构化进度事件；
- 把最终结果作为不可信数据处理后返回主代理。

### 2.4 消息分类

用户消息分为：

- `ANSWER_ONLY`：简单问答，主代理直接回答；
- `QUICK_HEADLESS`：只使用独立无头网页标签的轻任务；
- `TASK_CREATE`：创建新复杂任务并排队；
- `TASK_FOLLOW_UP`：补充或修改当前任务；
- `TASK_QUERY`：查询任务状态、队列或执行过程；
- `TASK_CONTROL`：暂停、继续、取消；
- `TASK_CLARIFY`：无法可靠判断归属时要求用户选择。

活动任务运行期间，每条新的用户消息都必须先由主代理判断是当前任务的补充，还是独立的新任务。
界面和 MCP 可以提供显式提示或 `intent=follow_up|new_task`，但最终仍由主代理结合当前任务目标、
最近进度和对话上下文做语义判定。

存在活动任务但消息归属不明确时，不应静默合并或立即执行。主代理应快速询问“补充当前任务，还是
排队为新任务”，在用户选择前不改变任务状态。

### 2.5 主代理消息判定

补充、新任务、任务绑定和控制模式的语义判定由主代理综合上下文完成。系统不按关键词匹配、正则规则、
固定候选顺序或数值置信度阈值自动分类。

主代理可以参考：

1. 判断是否为状态查询或任务控制；状态查询和控制命令不进入任务内容；
2. 找到当前活动、暂停或最近完成的相关任务；
3. 判断消息是否明确引用、修正或扩展现有任务目标；
4. 结合任务目标、应用、最近进度、对话历史和用户在任务中心的选择；
5. 输出结构化路由决定，并向用户确认实际动作。

建议路由结果：

```json
{
  "intent": "follow_up",
  "task_id": "task-123",
  "needs_clarification": false,
  "reason": "消息要求在当前任务中增加导出格式"
}
```

以下是语义判断示例，不是硬编码规则：

- “再顺便导出 PDF”可能是在补充当前任务，也可能是新任务请求，由主代理结合上下文判断；
- “不要打开相册”可能是在修改当前计划，也可能是在指定另一个任务；
- 用户在任务中心明确选择某个任务、提供了 `task_id`，或说“补充当前任务”时，这是强上下文信号，但
  仍需主代理判断目标和控制模式；
- 多个任务都可能相关时，主代理根据目标和上下文决定；无法可靠判断时向用户澄清；
- 补充和新任务的判定结果必须显示在对话中，方便用户发现误判并纠正。

结构化路由决定应写入任务事件，用于审计和后续修正，但不能把判定过程当作模型隐藏推理展示。

误判修正规则：

- 补充尚未被 Worker 消费：从收件箱撤销，再按用户要求创建新任务；
- 补充已被消费但尚未产生不可逆操作：暂停 Worker，撤销后续计划并按修正后的目标重新规划；
- 补充已导致点击、提交、下载或消息发送：不能伪装成完全撤销；记录 `ROUTE_CORRECTED`，
  创建纠错任务或等待用户确认后续处理；
- 反过来，如果新任务被误判为当前任务的补充，也遵循同一原则；尚未执行的排队项可以直接删除。

### 2.6 简单任务与复杂任务边界

简单与复杂任务也由主代理综合判断。下面的清单是能力边界和风险提示，不是自动分类器：

主代理直接处理：

- 不调用工具即可完成的问答；
- 单次只读查询；
- 不修改共享状态的一步网页读取；
- 预计不会持续等待、不会触发确认或用户接管的任务。

创建交互任务：

- 需要点击、输入、启动或关闭应用；
- 需要多步工具链、轮询等待、下载、安装或重试；
- 需要修改可见浏览器、登录状态、文件或共享页面；
- 需要持续跟进、暂停恢复或等待用户确认；
- 预计会在一轮回复后继续执行。

无法确定时，主代理根据用户目标和预计执行路径决定直接处理还是创建任务。安全边界不由模型判断：
需要写手机 UI、等待用户接管、下载、安装或持续运行的操作不能留在直连轻任务中。分流结果写入任务
事件，后续可根据真实工具调用自动升级，但已经直接执行的单步查询不再补建重复任务。

主代理直连执行还必须有硬上限，避免一次“简单任务”长期占住控制面。初始建议：

- 最多 2 次工具调用，或从第一次工具调用开始计算 30 秒工具执行时间；纯问答不受此限制；
- 不得启动应用、修改可见浏览器、下载、安装或等待用户接管；
- 直连轻任务只在 AI 助手页面可见时执行；用户离开助手页面时立即停止，不转入后台；
- 页面仍可见但超过任一上限时，在安全边界停止直连执行，转为 `QUEUED` 任务或返回需要用户确认的后续任务；
- 已完成的只读结果可以作为新任务的上下文，不能重复执行有副作用的工具调用。

主代理的短回合也需要一个串行 `ControllerInbox`。状态查询、任务控制和补充路由按到达顺序处理，
避免两个控制回合同时修改队列；短回合并发不代表 Worker 可以并发操作 UI。

### 2.7 主代理重连与子代理交接

后台任务运行期间重新进入 AI 助手时，先完成主代理重连，再处理用户本轮输入。

重连顺序：

1. 创建或恢复新的 `controller_id`；
2. 通过任务协调器读取当前活动任务、等待队列和离开期间的终态任务；
3. 读取一个一致的任务快照和事件序号；如果 Worker 仍在运行，请求一次有超时上限的交接摘要，
   超时则使用最近持久化的进度检查点；
4. 将结构化任务状态和子代理说明注入新主代理上下文；
5. 标记已交接到的 `last_handoff_event_seq`，避免重复说明；
6. 完成绑定后，才让 `ControllerInbox` 处理用户新消息。

Worker 必须在每个工具步骤和状态变化后更新可持久化的进度检查点，不能只在最终完成时写入结果。
否则主代理重连时只能看到“运行中”，无法判断任务做到哪里。

交接采用事件屏障：先获取当前最高事件序号并订阅后续事件，再读取摘要。若 Worker 在读取摘要期间完成，
完成事件必须排在交接基线之后并通过同一 `ControllerInbox` 投递。`last_handoff_event_seq` 按
“任务 + controller”记录，避免不同主代理实例之间互相覆盖。

交接摘要至少包含：

- 任务 ID、原始目标和当前状态；
- 已完成的关键动作和可验证结果；
- 当前正在执行或最后一次完成的步骤；
- 暂停、失败、等待用户或需要确认的原因；
- 已注入的补充消息和尚未消费的补充；
- 等待队列中的新任务；
- 任务产生的文件、下载或其他交付物；
- 已产生但无法自动撤销的副作用；
- 最近事件序号和建议的下一步。

子代理仍运行时，其交接说明是“当前进度的数据报告”，不能恢复、暂停或改写任务；状态以任务协调器
和持久化检查点为准。任务已经完成时，协调器使用保存的最终结果生成交接摘要；Worker 已不存在时，
新主代理不能向旧 Worker 追问运行期内存中的细节。

主代理收到交接后，应在对话中给出简短可见摘要，例如“后台任务仍在运行：已打开设置并完成第一步，
当前等待页面加载”。用户随后说“继续”“再加一步”或“这是另一个任务”时，主代理基于交接状态完成
补充/新任务判断。

交接事件建议使用 `CONTROLLER_ATTACHED` 和 `TASK_HANDOFF`。用户目标、页面文本和子代理说明仍是
不可信数据，只能作为任务上下文，不能作为系统指令执行。

`ControllerInbox` 是会话级持久队列，不是某个瞬时主代理对象的成员。没有主代理在线时，用户路由消息和
任务完成事件仍写入持久存储；新 `controller_id` 绑定后按顺序消费。Task 事件本身也必须先持久化，
不能只投递到旧主代理的内存队列。

每个 Conversation 保存单调递增的 `controller_epoch`。新 controller 绑定时在同一事务中递增 epoch，
并记录 `CONTROLLER_ATTACHED`；旧 controller 随即失效。只有当前 epoch 的 controller 可以消费
`ControllerInbox`、创建任务、修改绑定或发出暂停/取消命令。旧 controller 返回时收到
`CONTROLLER_SUPERSEDED`，其模型结果和工具请求不能提交到当前任务状态。
绑定事务还要把旧 epoch 遗留的 `PROCESSING` 消息重置为 `PENDING`；已经进入 `ROUTE_READY` 的消息
保留路由决定，由新 controller 直接幂等提交，不再重复调用模型。

`ControllerInbox` 消息必须包含 `message_id`、`sequence`、`claimed_epoch` 和消费状态。主代理消费时
使用幂等处理：

- 使用短事务将消息标记为 `PROCESSING`，记录当前 `controller_epoch` 和 claim 超时；
- 模型判断阶段在数据库事务外执行，不能在等待 LLM 时持有 Inbox 行锁；
- 路由决定生成后进入 `ROUTE_READY`；
- 用一次数据库事务提交路由副作用、幂等结果、回复事件并标记 `CONSUMED`；
- 新建任务时使用由 `message_id` 派生的 `request_id`；
- 追加补充时使用稳定的 `instruction_id`；
- 主代理在 `PROCESSING` 阶段崩溃时，claim 超时后可以重新消费；
- 新 controller 绑定时可以接管旧 epoch 的 `PROCESSING` 消息；旧模型结果因 epoch 不匹配被丢弃；
- `ROUTE_READY` 的路由决定已经持久化，恢复时直接提交，不重复进行语义判断；
- 已经提交路由结果但尚未写入 `CONSUMED` 的情况，通过幂等键返回原任务或原指令；
- 旧 controller epoch 不能 claim 或提交消费结果。

### 2.8 新对话与任务上下文绑定

每个新对话创建独立 `conversation_id`，默认不继承旧对话的聊天上下文和任务上下文。主代理判断当前
场景需要绑定哪些任务；绑定确定后，Coordinator 生成有界 `TaskContextBundle` 并注入新主代理。

建议增加 `ConversationTaskBinding`：

```text
conversation_id
task_id
mode = OBSERVE | CONTROL
attached_at
last_delivered_event_seq
```

绑定规则：

- 本对话创建的新任务自动绑定为 `CONTROL`；
- 主代理判断为独立新目标时，不绑定旧任务，只使用全局任务数量和等待队列摘要；
- 用户提到“刚才那个任务”“昨天的进度”或从任务中心选择任务时，这些是上下文信号；是否绑定、绑定
  哪些任务由主代理判断；
- 主代理可把已完成结果绑定为 `OBSERVE` 用于总结和追问；需要继续操作时创建关联新任务，旧任务不会
  重新执行；
- 全局状态查询可以读取所有活动任务摘要，但不把全部事件注入主代理；
- 定时任务和 MCP 任务不会自动绑定到普通新对话，除非用户明确选择。

新对话绑定哪个任务由主代理判断。Coordinator 只提供当前任务目录、状态摘要、最近事件和已有绑定，
不执行关键词匹配、固定优先级或置信度阈值分类。

主代理可以参考用户文本、对话历史、任务目标、应用、时间、任务状态、用户是否从任务中心选择以及
旧对话中的引用，决定是否绑定、绑定哪些任务，以及采用 `OBSERVE` 还是 `CONTROL`。多个任务都可能
相关时，由主代理根据上下文决定；无法可靠判断时展示候选并询问用户。

用户要求继续一个已完成任务时，不重新启动旧任务；旧任务保持 `OBSERVE`，并以其结果创建新的关联任务。

一个任务可以绑定多个 `OBSERVE` 对话，但同一时间只能有一个 `CONTROL` 对话。新对话请求接管时记录
`CONTROL_TRANSFERRED` 事件；如果任务原来没有 `CONTROL` 对话，则记录 `CONTROL_ACQUIRED`。旧对话
降级为 `OBSERVE`，后续补充和控制请求会被拒绝，并提示控制权已经转移。绑定只改变对话控制权，不改变
任务原有 Worker 和队列状态。

绑定表的 `CONTROL` 唯一性必须在数据库事务中保证。两个新对话同时请求接管时只能一个成功，另一个
重新读取绑定状态或请求用户选择。

`OBSERVE` 对话默认接收有界进度摘要和最终结果，不接收 Worker 的全部内部事件。控制对话负责处理
补充和异常；观察对话可在用户主动展开时按事件游标分页读取详情。

`TaskContextBundle` 应有字符和事件条数上限：默认包含任务目标、当前状态、最近检查点、未消费补充、
最近若干事件和结果摘要。完整历史按需从 `agent_task_events` 分页读取，不能一次性灌入新主代理。

## 3. 状态机

内部任务状态（用于调度、清理和恢复，不直接等同于 UI 展示）：

```text
QUEUED
  ├─ dispatch ───────────────→ PREPARING
  └─ cancel ─────────────────→ CANCELLED

PREPARING
  ├─ worker ready ───────────→ RUNNING
  ├─ needs user ─────────────→ WAITING_USER
  └─ cancel ─────────────────→ CANCELLING

RUNNING
  ├─ needs user ─────────────→ WAITING_USER
  ├─ pause request ──────────→ PAUSING
  ├─ finish ─────────────────→ FINALIZING
  └─ cancel ─────────────────→ CANCELLING

WAITING_USER
  ├─ resolved ───────────────→ PREPARING
  ├─ pause request ──────────→ PAUSING
  └─ cancel ─────────────────→ CANCELLING

PAUSING
  ├─ safe boundary reached ──→ PAUSED
  └─ cancel ─────────────────→ CANCELLING

PAUSED
  ├─ resume ─────────────────→ PREPARING
  └─ cancel ─────────────────→ CANCELLING

CANCELLING
  └─ cleanup finished ───────→ CANCELLED

FINALIZING
  └─ result handled ─────────→ SUCCEEDED / FAILED

RECOVERY_REQUIRED
  ├─ cleanup finished ───────→ CANCELLED
  ├─ result reconciled ──────→ SUCCEEDED / FAILED / INTERRUPTED
  ├─ user revalidates ───────→ PREPARING / PAUSED
  └─ cancel ─────────────────→ CANCELLING
```

`INTERRUPTED` 是进程恢复时使用的终态，但不能把所有非终态任务未经区分地映射到它。`QUEUED` 可以
保留为暂缓执行，`PAUSED` 可以保留暂停状态并要求重新验证；已经创建执行实体、正在清理或正在收尾的
任务才进入 `INTERRUPTED`。完整映射见第 13 节。

### 3.1 状态约束

- `QUEUED` 任务不能有真实 Worker 或运行中的 Tool Job；
- `PAUSED` 任务继续占据队列头，但释放 Agent 对交互资源的所有权；
- `CANCELLING` 任务尚未完成资源清理前，不能启动下一项；
- `CANCELLING` 是不可操作锁定期：暂停、继续、补充、再次取消和计划修改全部拒绝，只允许查询状态；
- `SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED` 为终态，`RECOVERY_REQUIRED` 不是终态；
- `RECOVERY_REQUIRED` 会阻塞交互队列，完成资源清理或结果对账后才能离开；
- 所有控制操作必须幂等，重复取消或暂停不能产生重复副作用。

### 3.2 状态分层

一个公开任务状态不应同时表达 Worker 是否存在、租约是否持有以及等待原因。任务快照需要拆成以下维度，
但由同一个 `AssistantTaskCoordinator` 原子更新：

| 维度 | 作用 | 示例 |
| --- | --- | --- |
| `TaskState` | 生命周期权威状态，供调度和恢复使用 | `QUEUED`、`RUNNING`、`PAUSED`、`RECOVERY_REQUIRED`、`SUCCEEDED` |
| `ExecutionState` | Worker 执行实体 | `NOT_CREATED`、`PREPARING`、`ACTIVE`、`WAITING_IO`、`EXITED` |
| `ResourceState` | 当前持有的资源 | `NONE`、`UI_OWNED`、`HEADLESS_TAB`、`CLEANUP_PENDING` |
| `wait_reason` | 当前等待原因 | `USER_CONFIRMATION`、`USER_TAKEOVER`、`NETWORK`、`MODEL` |
| `interaction_blocking` | 是否阻止用户手动操作共享 UI | `true`、`false` |
| `UiState` | 派生出的 6 个用户界面状态 | `等待中`、`执行中`、`已暂停`、`需要处理`、`取消中`、`已结束` |

`WAITING_USER` 必须同时记录具体 `wait_reason`。等待用户确认或接管通常为阻塞；已经释放 UI
租约、只等待网络或内部异步任务时，可以保持 `TaskState=RUNNING`、`ExecutionState=WAITING_IO`，
不新增公开状态。等待中的任务是否保留队首位置必须在任务快照中明确，不能由界面推断。

`TaskState`、`ExecutionState` 和 `ResourceState` 是运行时状态，不直接作为 UI 状态机的全部选项。
用户界面只展示第 3.4 节定义的 `UiState`、必要的可读 `wait_reason` 和结果标签。

本产品明确规定正式任务一次只执行一个：当前正式任务即使进入 `WAITING_IO` 并释放 UI 租约，仍然占据
交互队列，直到成功、失败、取消或进入暂停状态。`interaction_blocking=false` 只表示用户可以使用
共享 UI，不表示下一个正式任务可以启动。该取舍优先保证任务串行和操作语义可预测，后续若改变并发模型，
再通过新的调度策略扩展。

### 3.3 核心不变量

以下规则是实现的硬约束：

1. 同一个交互资源最多存在一个有效写入者；
2. 一个任务最多存在一个有效 Worker；
3. 助手任务的状态只能由 `AssistantTaskCoordinator` 迁移；
4. 任务状态、事件和通知意图必须一致持久化；
5. 任务进入终态后不能重新执行；
6. 旧 Worker 使用过期 fencing token 必须被宿主拒绝；
7. 未确认的不可逆操作不得自动重试；
8. 重启后绝不自动重放结果不确定的 UI 操作；
9. 没有合法授权和资源租约的任务不得执行写操作；
10. 主代理离线时，任务完成事件和结果不能丢失；
11. `QUEUED` 任务不得提前创建 Worker、模型请求或工具执行 Job；
12. `queue_position` 只能由当前调度顺序计算，不能作为持久化权威数据；
13. 每个 Conversation 同时只能有一个当前 `controller_epoch` 消费者；
14. 每个任务同时只能有一个 `CONTROL` 对话，其他绑定只能观察。

### 3.4 简化 UI 状态与操作

详细状态用于调度、恢复和可靠性，不需要全部暴露给用户。任务中心统一收敛为 6 个 UI 状态：

| UI 状态 | 对应内部状态 | 默认操作 |
| --- | --- | --- |
| 等待中 | `QUEUED` | 取消 |
| 执行中 | `PREPARING`、`RUNNING`、`FINALIZING`、自动恢复中 | 暂停；部分阶段不可暂停时只显示进度 |
| 已暂停 | `PAUSING`、`PAUSED` | 确认暂停前禁用操作；暂停后主操作为继续 |
| 需要处理 | `WAITING_USER`、需要人工的 `RECOVERY_REQUIRED` | 一个“处理”入口 |
| 取消中 | `CANCELLING` | 全部操作禁用，只显示状态 |
| 已结束 | `SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED` | 查看结果；继续会创建新任务 |

Coordinator 在任务快照中直接提供：

```text
primary_action = NONE | PAUSE | RESUME | HANDLE | RETRY_CLEANUP | VIEW_RESULT
can_cancel = true | false
```

UI 只根据这两个字段和 `CANCELLING` 锁定状态渲染按钮，不再为每个内部状态分别写一套操作逻辑。
`CANCELLING` 时 `primary_action=NONE`、`can_cancel=false`。补充消息继续通过聊天输入处理，不在任务卡
增加单独按钮。

任务卡只保留一个主操作和一个取消操作；“展开执行过程”、诊断信息和结果属于只读区域。失败重试和
已结束任务继续都创建带父引用的新任务，不增加“恢复原终态”的复杂按钮。

## 4. 调度与并发

### 4.1 单一交互执行器

引入全局 `InteractiveTaskScheduler`：

- 同一时间最多一个 `RUNNING` 交互任务；
- 同一优先级内严格 FIFO；
- 队首任务暂停时，整个交互队列冻结；
- 队首任务取消并完成清理后，下一项自动进入 `PREPARING`；
- 新任务追加到队尾；
- 补充消息不改变队列顺序。

暂停时不能让后续任务自动启动。否则用户暂停当前 Agent 后，手机仍可能被另一个 Agent 操作。

交互队列是应用进程级队列，不是每个会话各自运行一个 Worker。不同会话可以分别展示自己的任务列表，
但都必须竞争同一个全局交互执行权。为防止单个 MCP owner 无限占用队列，应为每个 owner 设置最大
待执行数量；超过限制时拒绝创建并返回 `TASK_QUEUE_FULL`。

建议优先级从高到低为：

1. 用户在本机助手或语音中明确创建的任务；
2. 定时任务；
3. 远程 MCP 创建的任务。

同一优先级内 FIFO。调度器只会在当前交互任务完成或取消后选择下一项，不抢占正在运行或暂停的队首。
任务面板显示的 `queue_position` 必须是实际调度顺序，而不是创建时间顺序。
持久化层不得保存 `queue_position`；取消、暂停或恢复后由调度器重新计算。

### 4.2 轻任务例外

以下操作可以在交互 Worker 运行时由主代理直接执行：

- 不访问手机 UI 的简单问答；
- 只读系统状态；
- 任务状态和队列查询；
- 使用独立 headless tab 的简单网页读取；
- 不改变当前可见标签页、不共享登录页面的轻量网络请求。

可见浏览器、页面登录、文件上传、用户接管和其他会改变共享页面的操作属于交互资源，必须进入交互队列。
首版建议最多允许 2 个 `HEADLESS_WEB` 轻任务同时执行，每个任务只能操作自己创建的 tab；达到上限后
新网页任务进入队列，不能无限并发。

直连轻任务不进入正式任务队列，也不由后台运行时继续托管。AI 助手页面离开前台后，应取消尚未完成的
直连任务和 headless tab；用户重新进入时不会继续该任务，也不会收到完成通知。只有明确创建的正式任务
才享受进程级保活。

### 4.3 资源类别

建议为任务标记资源类别：

| 类别 | 示例 | 并发规则 |
| --- | --- | --- |
| `NONE` | 简单问答 | 可与任何不冲突操作并行 |
| `HEADLESS_WEB` | 独立标签页搜索、读取 | 允许有限并行，必须独占自己的 tab |
| `INTERACTIVE` | 点击、输入、启动应用、可见浏览器 | 全局最多一个 |
| `WAITING` | 等待下载、网络或用户 | 可等待，但不能占用 UI 租约 |

资源租约应以 `task_id` 为 owner，而不是仅以会话 ID 为 owner。暂停、取消或任务完成时必须通过
`ResourceScope` 有界释放所有子资源。
租约的具体 fencing、heartbeat 和失效规则见第 15.2 节。

### 4.4 进程生命周期

正式任务的执行不能绑定到 AI 助手页面、Activity 或 Compose 生命周期。只要 NestSpace 应用进程仍在
运行，正式任务队列就应继续推进。直连轻任务不受此保活规则保护。

建议增加进程级 `AssistantTaskRuntime`：

- 持有 `SupervisorJob` 和任务调度器，不随页面销毁而取消；
- 只要存在任何未终态的正式任务或等待队列项，就启动并保持任务前台服务；
- 直连轻任务不计入正式任务保活，离开助手页面即取消；
- 前台服务通知显示当前任务、等待数量和暂停/取消操作；
- 所有任务进入终态且等待队列为空后停止任务前台服务；
- AI 助手页面只观察状态，不拥有任务 Job；
- 页面进入后台、用户返回桌面或关闭助手页都不能取消正式任务；
- 只有用户显式取消、正式任务完成或应用进程被系统终止时才停止执行。

任务前台服务不能依靠界面可见性保活。若现有 `McpForegroundService` 可能被用户关闭，则应提供独立的
助手任务前台服务，或将其泛化为统一的 Agent 运行时服务，避免任务执行依赖 MCP 开关。

应用进程被系统终止后仍不能保证继续运行。此时使用任务日志和恢复矩阵处理任务状态；需要清理或结果
对账的任务进入非终态 `RECOVERY_REQUIRED`，无法安全继续的运行任务进入 `INTERRUPTED`，不能静默
重放 UI 操作。

前台服务不能绕过 Android 的后台 Activity 启动限制：

- 正式交互任务应在应用仍可见、用户操作通知或 MCP 已处于前台服务时启动；
- 没有悬浮窗后台启动豁免时，`space_launch`、可见 Activity 和用户接管可能返回 `LAUNCH_BLOCKED`；
- 设备锁屏、屏幕关闭或系统裁剪后台进程时，交互任务应进入 `WAITING_USER` 或明确失败，不能无限重试；
- Android 13+ 需要申请通知权限。通知权限被拒绝时任务仍可运行，但必须让用户能通过打开助手查看状态；
- WakeLock 只在实际 CPU、网络或超时等待期间持有，任务暂停或等待用户时释放。

## 5. 补充消息

主代理判断消息属于补充后，每个运行中任务拥有独立指令收件箱：

```text
用户补充
  ↓
TaskInbox.append(TaskInstruction)
  ↓
Worker 在下一模型步骤前读取
  ↓
合并到当前任务上下文
```

补充消息使用结构化 `TaskInstruction`：

| 字段 | 说明 |
| --- | --- |
| `instruction_id` | 全局唯一 ID |
| `task_id` | 目标任务 |
| `sequence` | 任务内单调递增序号 |
| `type` | `ADD_REQUIREMENT`、`MODIFY_REQUIREMENT`、`EXCLUDE_ACTION`、`SET_CONSTRAINT`、`CANCEL_CURRENT_PLAN` |
| `content` | 用户原始文本 |
| `created_at` | 创建时间 |
| `consumed_at` | Worker 读取时间，未读取为空 |
| `status` | `PENDING`、`CONSUMED`、`APPLIED`、`REJECTED` |

`EXCLUDE_ACTION` 和 `MODIFY_REQUIREMENT` 不能只当作普通上下文文本追加，Worker 必须把它们转成当前
执行约束。任务详情应显示补充是否已被消费，避免用户误以为修改已经生效。

规则：

- 主代理必须先确定目标任务的 `task_id`，不能只向全局会话追加文本；
- 补充被接受后，对话应显示“已补充到当前任务”，并标明目标任务；
- 判定为新任务时，对话应显示“已排队为新任务”、队列位置和新任务 ID；
- 低置信度或目标不唯一时，不写入收件箱、不创建任务，先要求用户澄清；
- Worker 正在执行一个不可分割工具调用时，不立即中断，先完成当前原子动作；
- 下一模型步骤前统一注入所有待处理补充；
- 补充按输入顺序处理；
- `PAUSED` 状态可以继续接收补充，恢复后一起注入；
- 补充消息不能隐式扩大 `TaskCapabilitySnapshot`；需要额外工具或更高风险权限时，必须让用户确认能力
  升级，无法安全升级则创建新任务；
- “别做了，改成另一个目标”不属于补充，应取消当前任务并创建新任务；
- 任务进入 `FINALIZING` 后到达的补充应成为同一会话的新任务，不能丢失。

主代理可以调用 `agent_task_supplement(task_id, text)`，但该操作只允许作用于当前会话中可控制的
`RUNNING`、`WAITING_USER` 或 `PAUSED` 任务。

只有 `ConversationTaskBinding.mode=CONTROL` 的对话可以向 Worker 添加补充或修改计划；`OBSERVE`
对话只能查询状态和读取结果。

## 6. 暂停与恢复

### 6.1 暂停

用户可以直接点击暂停；主代理在用户明确要求时也可以调用暂停工具。

暂停采用协作式执行：

1. 任务进入 `PAUSING`；
2. 调度器不再批准新的模型步骤或工具调用；
3. 当前工具若可取消则取消，若是原子 UI 动作则允许在短时限内完成；
4. 释放交互租约、镜像小窗和临时控制资源；
5. 进入 `PAUSED`。

暂停不应强行终止一次已经提交的点击或输入。如果当前工具无法及时停止，界面应显示“正在暂停”，
不能提前显示“已暂停”。

### 6.2 恢复

恢复只允许作用于当前队首的 `PAUSED` 任务。

恢复流程：

1. 任务进入 `PREPARING`；
2. 重新检查权限、模型配置和工具可用性；
3. 重新获取交互租约；
4. 执行一次 `ui_observe`，核对当前应用、页面和屏幕状态；
5. 追加 `STATE_REVALIDATED` 事件；
6. 进入 `RUNNING` 并重新规划下一步。

恢复不能假定暂停前的页面和 Activity 仍然存在。原应用或实例已关闭时，应先恢复容器；无法安全恢复时
进入 `WAITING_USER` 并说明原因。

主代理不得自动恢复用户暂停的任务。继续操作必须来自用户的明确指令或用户点击“继续”；恢复来源和
时间写入任务事件。

## 7. 取消

### 7.1 取消排队任务

`QUEUED → CANCELLED` 应立即完成：

- 从等待队列移除；
- 不创建 Worker；
- 不创建模型请求；
- 不获取容器或浏览器资源；
- 保持其他任务状态不变；
- 更新后续任务的 `queue_position`；
- 保留审计记录，界面显示“已取消（未执行）”。

同时提供“取消全部排队任务”，默认不取消正在执行或暂停的队首任务。

### 7.2 取消运行任务

`RUNNING → CANCELLING → CANCELLED`，清理失败时为 `CANCELLING → RECOVERY_REQUIRED`：

- 停止创建新的模型步骤和工具步骤；
- 取消当前 Worker Job 和相关子任务；
- 等待可取消的工具退出；
- 通过 `ResourceScope` 释放容器 UI、浏览器标签、小窗和文件句柄；
- 保留已完成动作和执行轨迹；
- 清理成功后标记 `CANCELLED`；
- 清理失败时进入 `RECOVERY_REQUIRED`，记录 `cleanup_error` 和 `cleanup_pending=true`；
- 只有清理完成后才启动队列下一项。

进入 `CANCELLING` 后，UI 应立即禁用该任务的暂停、继续、补充、修改计划和取消按钮，直到状态确认。
再次发送取消请求只返回当前 `CANCELLING` 快照，不重复触发清理回调。主 Agent 收到补充或新控制请求时
必须告知当前任务正在取消，不能向 Worker 注入新的补充。
独立的新的任务仍可进入等待队列，但必须等当前任务进入 `CANCELLED` 或完成 `RECOVERY_REQUIRED`
清理后才能启动。

状态确认出口只有：

- `CANCELLED`：清理完成，可以启动下一项；
- `RECOVERY_REQUIRED`：清理失败或结果不确定，显示诊断和“重试清理”入口，队列保持阻塞。

`RECOVERY_REQUIRED` 是状态已经确认后的恢复阶段，允许执行明确的清理重试；它不是 `CANCELLING`，
但仍禁止恢复执行原任务。

取消必须定向到 `task_id`，不能直接调用全局助手取消。全局取消可能误伤主代理的状态查询或其他会话。

### 7.3 暂停任务取消

`PAUSED → CANCELLING → CANCELLED`。取消暂停任务后队列继续运行下一项。

## 8. 任务等待列表

任务中心分为三组：

- 当前任务
- 等待队列
- 最近完成

等待队列至少显示：

- 队列位置
- 标题和目标摘要
- 来源
- 状态
- 创建时间
- 是否已创建 Worker
- 当前步骤摘要
- 取消按钮

当前任务显示：

- 等待中、执行中、已暂停、需要处理、取消中或已结束
- 执行中可附带“启动中、收尾中、恢复中”等只读阶段说明
- 最近进度
- 一个由 `primary_action` 决定的主动作：暂停、继续、处理、重试清理或查看结果
- 一个由 `can_cancel` 决定的取消操作
- 展开执行过程
- `RECOVERY_REQUIRED` 时显示恢复原因、诊断信息、重试清理或重新对账入口

`CANCELLING` 时任务卡显示“正在取消，等待资源清理”，所有变更按钮禁用；通知中的暂停/继续/取消操作
也同步禁用。页面轮询只读取状态，不重复发送取消命令。

队列调整操作：

- 单个排队任务取消；
- 全部排队任务取消；
- 当前任务暂停或取消；
- 可选：暂停队首时保持队列冻结提示。

MVP 不提供任意拖拽重排，先保证 FIFO 和取消语义稳定。

### 8.1 显示位置

任务列表的主入口应放在 AI 助手页面内，而不是「空间设置 → 高级助手工具」。

推荐布局：

1. **Header 任务按钮**：复用当前列表图标，改为助手任务入口，角标只显示待执行数量；
2. **活动任务摘要条**：Header 下方只显示当前正在处理的一条助手任务，同一行显示任务总数和“查看全部”；MCP 直接创建的任务只在完整任务中心显示；
3. **任务面板**：点击 Header 或摘要条后展开，不再把完整队列常驻在聊天消息之间；
4. **聊天内任务卡片**：只在任务创建、完成、失败或等待用户时显示摘要，点击后打开对应任务详情；
5. **运行记录**：继续作为历史会话入口，与实时任务列表分开。

任务详情提供“在这里继续”或“在新对话继续”操作；绑定到新对话后只注入相关任务摘要，不复制全部历史。

手机上使用接近全屏的 Bottom Sheet，平板上使用右侧任务面板。这样既不压缩聊天输入区，也能一键查看
当前进度和等待队列。

“助手界面可见”指主助手页面或语音助手界面前台可见；进入其他应用、返回桌面或锁屏都视为不可见。

摘要条示例：

```text
正在处理 · 共 3 个任务
整理今天的消息                                     查看全部
```

当前 [AgentHeader.kt](../app/src/main/java/com/coke/nestspace/ui/agent/AgentHeader.kt) 已有列表图标，
可以将其从“MCP 任务”扩展为统一“助手任务”，用户任务和 MCP 任务都进入同一 Worker 队列。设置页中的
子任务记录保留为历史审计入口，不作为主要操作入口。

语音助手界面空间较小，不显示完整队列；顶部显示“运行中/等待数量”，点击后打开同一个任务面板。
语音中查询状态时由主代理读取任务摘要，不在语音界面另外维护一份任务状态。

### 8.2 后台状态

用户离开 AI 助手页后：

- 正式任务继续执行；
- 等待队列继续按 FIFO 推进；
- 页面重新打开时从任务 Flow 恢复当前状态；
- 前台通知持续显示进度；
- 任务需要用户确认时使用现有 UserGate 通知；
- 正式任务完成时发送一条完成通知，不持续刷屏。

直连轻任务不适用于以上后台规则。离开 AI 助手页时，主代理的直连请求、临时 headless tab 和未完成工具
调用应立即进入取消清理，不显示完成通知，也不进入最近完成的任务列表。

通知操作至少包含“打开任务”“暂停/继续”“取消”。通知中的任务标题和控制操作必须来自持久化任务状态，
不能依赖 Compose 页面仍在内存中。

任务完成通知只在助手界面当前不可见时发送；助手可见时使用聊天内完成卡片，避免同一结果同时出现两次。
通知按 `task_id` 去重，打开结果页后清除对应完成通知。任务进度通知和完成通知使用不同渠道：
进度渠道保持低打扰，完成渠道使用默认提醒级别。

前台通知的进度更新应节流，例如最多每 1–2 秒更新一次，终态和等待用户事件立即更新。频繁重绘通知
不能阻塞 Worker。

任务标题、页面内容或用户目标可能包含隐私信息。锁屏通知默认使用 `VISIBILITY_PRIVATE`，锁屏显示
脱敏摘要；完整标题可以在解锁后的任务面板查看。

“新建对话”“清空消息”和切换历史记录只改变对话视图，不能取消正在运行或排队的任务。任务继续归属
原 `conversation_id`，全局任务入口和三组任务列表始终可见；只有终态任务可以从历史中删除。

重新进入助手时，在任务摘要条上方短暂显示“已同步后台任务”，并展示子代理交接摘要。交接完成前，
用户的新消息先进入 `ControllerInbox` 等待，不能先创建任务或向旧任务追加补充。

## 9. 主代理与 MCP 控制接口

建议新增任务级工具，统一供主代理和远程 MCP 使用：

| 工具 | 行为 |
| --- | --- |
| `agent_task_list` | 列出当前任务、等待队列和最近任务 |
| `agent_task_status` | 查询单个任务状态和进度 |
| `agent_task_events` | 分页读取结构化执行事件 |
| `agent_task_supplement` | 向当前任务追加补充 |
| `agent_task_pause` | 暂停当前任务 |
| `agent_task_resume` | 恢复队首暂停任务 |
| `agent_task_cancel` | 取消指定排队或运行任务 |
| `agent_task_cancel_queued` | 取消当前会话全部排队任务 |

授权规则：

- 本机用户是设备级最高控制者，可以查看、暂停、继续或取消设备上所有助手任务；
- 主代理只能控制当前对话中 `mode=CONTROL` 的任务，其他绑定任务只能观察；
- MCP 客户端只能控制其 `owner_id` 下的任务；
- 子 Agent 不获得任务管理工具；
- 未授权任务统一返回 `TASK_NOT_FOUND`，不泄露任务是否存在。

## 10. 进度与执行过程

### 10.1 结构化事件

Worker 产生按序持久化的事件，例如：

```text
TASK_STARTED
STEP_STARTED
TOOL_STARTED
TOOL_FINISHED
PROGRESS_UPDATED
CONTROLLER_ATTACHED
CONTROLLER_SUPERSEDED
TASK_HANDOFF
CONVERSATION_TASK_BOUND
CONTROL_ACQUIRED
CONTROL_TRANSFERRED
USER_INPUT_REQUIRED
MONITOR_RESUMED
STATE_REVALIDATED
TASK_RECOVERY_REQUIRED
TASK_RECOVERED
TASK_SUCCEEDED
TASK_FAILED
TASK_CANCELLED
TASK_INTERRUPTED
```

每条事件包含：

- `task_id`
- `worker_id`
- `seq`
- `type`
- `timestamp`
- 简短的 `summary`
- 可选 `tool_name`、`status` 和结构化元数据

工具参数和结果需要截断、脱敏并标记为不可信数据。不要持久化完整页面、密钥、登录信息和模型隐藏推理。

事件命名固定如下，不再使用含义重叠的 `TASK_COMPLETED`：

| 事件 | 含义 |
| --- | --- |
| `TASK_SUCCEEDED` | 任务成功并产生最终结果 |
| `TASK_FAILED` | 任务失败并产生错误终态 |
| `TASK_CANCELLED` | 用户或主代理取消并完成清理 |
| `TASK_INTERRUPTED` | 进程恢复时确认任务中断 |
| `TASK_RECOVERY_REQUIRED` | 任务进入资源清理或结果对账状态 |
| `TASK_RECOVERED` | 恢复对账完成并产生可继续处理的结果 |
| `TASK_HANDOFF` | 向新主代理提供任务基线 |
| `CONTROLLER_ATTACHED` | 新主代理完成绑定 |
| `CONTROLLER_SUPERSEDED` | 旧主代理实例被新 controller epoch 取代 |
| `CONVERSATION_TASK_BOUND` | 对话绑定或解除绑定任务 |
| `CONTROL_ACQUIRED` | 没有旧控制者时，当前对话取得控制权 |
| `CONTROL_TRANSFERRED` | 任务控制权转移到另一个对话 |

底层事件同时携带 `visibility=user|internal|developer`。用户界面只展示 `user` 和经过脱敏的必要
`internal` 摘要；Worker ID、租约 token、diagnostic code、revision、耗时等 `developer` 字段进入
诊断日志，不在聊天执行过程里直接展示。

### 10.2 主代理查询

主代理查询时默认返回：

- 状态
- 当前步骤摘要
- 最近一次工具动作
- 队列位置
- 是否需要用户操作

用户明确要求“展开过程”时再返回最近 N 条事件。不能用模型轮询等待任务完成；Worker 结束时应主动
发送完成或失败事件。

Worker 完成事件通过 `ControllerInbox` 排队。主代理正在回答另一个问题时，不能直接被完成事件打断；
任务终态、通知和任务列表先立即更新，主代理本轮结束后再读取事件并生成总结。

新的主代理实例没有旧实例的上下文时，必须把最近一次 `TASK_HANDOFF` 视为当前任务的基线；后续进度
只发送增量。不能要求用户重复说明后台任务，也不能用模型猜测任务完成情况。

事件路由按 `ConversationTaskBinding` 执行：`CONTROL` 对话接收执行中需要决策的进度和终态；
`OBSERVE` 对话只接收节流后的状态摘要和终态结果；没有对话绑定的任务只进入全局任务中心和通知。
终态结果可以对多个 `OBSERVE` 绑定各投递一次，但每个绑定使用自己的消费游标去重。

### 10.3 界面

任务卡片默认显示摘要，点击后展开：

- 步骤开始和结束
- 工具名称和简短结果
- 暂停、恢复、等待用户
- 取消和清理结果

不得把以下内容显示为“执行过程”：

- 模型隐藏思维链；
- 未脱敏的 API Key、Token 或密码；
- 未处理的网页提示词；
- 包含用户隐私的完整页面文本。

### 10.4 后台完成与通知打开

任务完成时，结果采用“直接展示 + 主代理接管上下文”的组合方式：

1. 先把子代理的最终结果、状态、交付物和事件序号持久化；
2. 通知使用确定性模板显示完成或失败摘要，不等待主代理生成文案；
3. 用户点击通知后，通过 `task_id` 直接打开任务结果卡片；
4. 结果卡片立即显示子代理的原始结果、完成步骤、失败原因和文件/下载交付物；
5. 同时生成 `TASK_SUCCEEDED` 或对应终态事件，并生成 `TASK_HANDOFF` 写入新主代理的
   `ControllerInbox`；
6. 新主代理把结果作为上下文保存，但不强制发起一次模型改写；
7. 用户继续追问、要求总结或决定下一步时，主代理再基于结果和结构化事件回答。

子代理结果是任务的权威交付物，不能为了等待主代理总结而延迟显示。主代理的总结只做面向对话的
压缩、解释和下一步建议，不能覆盖或替换原始结果。

通知打开后的主代理不应重复执行任务，也不应假定结果成功。必须先读取持久化终态：

- `SUCCEEDED`：展示结果和交付物；
- `FAILED`：展示错误、已完成步骤和可重试建议；
- `CANCELLED`：展示取消来源、时间和清理错误；
- `INTERRUPTED`：展示中断原因和恢复建议。

如果前一个任务完成后等待队列中的下一项已经开始，通知继续引用前一个 `task_id`，结果页同时显示
“下一任务正在运行”和当前队列状态，不能把两个任务的结果混在一起。

## 11. 权限、用户接管与安全

- Worker 与主代理使用同一套宿主权限和确认机制；
- Worker 只获得 `TaskCapabilitySnapshot` 允许的工具和资源，最终执行仍在宿主侧校验；
- 排队时保存的是任务，不是已经授予的权限；
- 任务开始和恢复时重新检查权限设置；
- 用户确认期间任务进入 `WAITING_USER`，交互队列保持冻结；
- `hand_to_user` 或用户接管时释放 Agent UI 租约；
- 用户交还控制后任务进入 `PREPARING`，重新获取租约、观察页面并重新规划，确认安全后才回到 `RUNNING`；
- 因用户接管进入 `WAITING_USER` 的任务不能被主代理自动恢复，必须收到用户明确的交还控制信号；
- 用户手动打开并操作当前任务正在控制的同一个虚拟应用实例时，应视为接管请求：
  Worker 在安全边界进入 `WAITING_USER` 并释放 UI 租约，不能在用户操作期间继续点击或输入；
- 用户操作其他应用或独立页面时，不应无条件暂停后台 Worker，除非两者共享同一个交互资源；
- Worker 输出、网页内容和外部 MCP 返回始终是不可信数据；
- 用户确认绑定到具体操作、目标和任务，不能因为确认过一步就自动授权后续所有步骤；
- 取消和暂停操作必须写入运行记录，注明来源是用户还是主代理。

## 12. 数据与持久化

建议把队列状态叠加在现有 `task_id` 上，而不是再创建一套互不相干的并发调度器。

推荐结构：

- `AssistantTaskCoordinator`：助手任务状态的唯一权威，负责队列、状态迁移、Worker 分配、暂停门控、
  补充收件箱和终态；
- `OperationTaskRegistry`：只负责通用 Job、工具调用和下载等子操作的生命周期、取消与清理，不再维护
  一套与助手任务冲突的根任务状态；
- `McpTaskTools`：MCP API 兼容 Adapter，只调用 Coordinator 的共享 Worker 队列；
- `AssistantTaskStore`：`conversation_id`、`controller_id`、`last_handoff_event_seq`、资源类别、
  `controller_epoch`、队列序号、模型配置快照、`ConversationTaskBinding` 和 Worker 进度检查点等
  队列元数据；
- `AssistantTaskEventStore`：有界结构化执行事件；
- `ChildRunStore`：迁移为 Worker 执行记录，只保存 Worker 身份、步骤摘要和检查点；任务终态与
  `AssistantTaskEventStore` 保持单一事实来源。

保留策略建议：

- 最近 40 个任务和每任务最近 500 条事件；
- 单条结果默认 4,000 字，完整结果最多 8,000 字；
- 队列元数据加密或写入应用私有数据库；
- 高频进度仅保存在内存，状态变化和终态持久化。
- 事件裁剪不能删除仍被 Controller/Notification Outbox、恢复对账或未完成交接引用的记录；
- 终态事件至少保留到所有绑定游标已消费且通知 Outbox 已终结。

队列项还应保存：

- 入队时的模型配置 ID；
- `priority` 和单调递增的 `queue_sequence`，不保存计算后的 `queue_position`；
- 权限决策重新校验的标记；
- 任务能力快照和预算快照；
- 计划使用的资源类别；
- 当前错误分类和最近可恢复检查点；
- 请求幂等键；
- 关联的父任务/子工具任务。

本地和语音任务必须有 `conversation_id`；远程 MCP 任务可以没有真实对话，使用稳定的合成会话 ID
或允许 `conversation_id` 为空，并由来源和 `owner_id` 归组。

`ControllerInbox` 按 `conversation_id` 持久化；`controller_id` 表示当前消费者。主代理重连时读取
尚未消费的路由消息和事件，不能依赖旧主代理对象仍然存在。
每条消息保存 `message_id`、`sequence`、`claimed_epoch`、状态、消费结果和幂等键。claim 使用短事务，
模型判断在事务外执行，最终路由副作用、回复事件和 `CONSUMED` 状态在同一事务中提交。

`ConversationTaskBinding` 同样与任务和事件一起持久化。新对话创建、绑定、解绑或转移控制权时必须
产生事件；对话删除不影响任务本身，活动任务必须先取消或改为归档。

模型配置在任务执行前被删除时，任务进入 `WAITING_USER`，不要静默改用其他模型。

持久化结构需要显式 `schema_version` 和迁移逻辑。升级应用时保留队列顺序、暂停状态和终态审计，
迁移失败时将相关任务标记为 `INTERRUPTED`，不能丢弃任务或自启动 Worker。

任务中心默认只显示根助手任务。下载、工具调用和子运行作为子节点折叠在所属任务下，避免一次复杂任务
产生多个顶层等待项。已取消或已完成的子任务保留在详情中用于审计。

任务生命周期独立于 Conversation 生命周期。清空消息、归档对话或新建对话都不删除任务；归档允许
任务继续运行。删除仍有活动任务的对话时必须阻止，并要求用户先取消任务或改为归档。终态任务结果仍可
从任务中心按 `task_id` 查看，新对话只能通过明确的任务引用继续追问旧结果，不会自动继承旧主代理上下文。

## 13. 进程恢复

应用重启后：

1. 加载任务和队列；
2. 按恢复矩阵映射状态，不把不同故障场景统一塞进 `INTERRUPTED`；
3. 保留原队列顺序和事件；
4. 审计遗留 Worker、租约、工具执行账本和清理资源；
5. 不自动启动任何 Worker；
6. 在任务中心显示恢复原因；
7. 用户确认后，根据恢复后的公开状态决定恢复原任务还是创建续跑任务，并从安全步骤重新观察页面。

| 重启前状态 | 重启后 `TaskState` | 恢复字段 | 自动执行 |
| --- | --- | --- | --- |
| `QUEUED` | `QUEUED` | `recovered_hold=true` | 否 |
| `PREPARING` | `INTERRUPTED` | `recovery_kind=PRESTART_ABORTED` | 否 |
| `RUNNING` | `INTERRUPTED` | `recovery_kind=NEEDS_STATE_REVALIDATION` | 否 |
| `WAITING_USER` | `INTERRUPTED` | `recovery_kind=USER_REQUEST_LOST` | 否 |
| `PAUSING` | `INTERRUPTED` | `recovery_kind=PAUSE_INCOMPLETE` | 否 |
| `PAUSED` | `PAUSED` | `recovery_required=true` | 否 |
| `CANCELLING` | `RECOVERY_REQUIRED` | `recovery_kind=CLEANUP_REQUIRED`、`cleanup_pending=true` | 否 |
| `FINALIZING` | `RECOVERY_REQUIRED` | `recovery_kind=RESULT_RECONCILIATION_REQUIRED` | 否 |
| 任意终态 | 原状态不变 | 无 | 不适用 |

恢复规则：

- `QUEUED` 恢复后继续显示在原优先级队列中，但必须等待用户点击“继续队列”；
- `PAUSED` 恢复后仍是暂停任务，重新校验模型、权限、页面和资源后才能继续；
- `CANCELLING` 必须先释放旧租约、关闭遗留 Provider、下载和浏览器 tab，再允许队列前进；
- `FINALIZING` 必须根据 `ToolExecutionLedger` 和实际页面状态判断操作是否已经发生，禁止盲目重放；
- `CANCELLING` 清理完成后进入 `CANCELLED`；`FINALIZING` 对账后进入 `SUCCEEDED`、`FAILED` 或
  `INTERRUPTED`；
- 清理失败保持 `RECOVERY_REQUIRED`，提供“重试清理”和诊断信息，不自动跳过；
- 只要存在 `RECOVERY_REQUIRED` 或 `cleanup_pending`，调度器不得启动下一项；
- 恢复完成后更新 `recovery_kind`、清理标记和事件，但仍由 Coordinator 决定是否允许用户继续。
- `INTERRUPTED` 是终态，不能回到 `RUNNING`；用户选择继续时创建新的 `assistant_task_resume`，
  以原任务和最近检查点为父引用，旧任务结果保持不变。

## 14. 错误与竞态处理

- 暂停、取消、补充和任务完成都通过 Coordinator 串行入口、数据库 revision 和事务处理，不能只依赖
  进程内锁；
- 状态变更返回 revision，客户端按 revision 合并；
- 只有 `AssistantTaskCoordinator` 可以提交助手任务公开状态；
- 任务状态、状态事件、Controller Outbox 和通知 Outbox 必须在同一数据库事务提交；
- Worker 的迁移请求必须校验任务 revision、Worker ID 和 fencing token，拒绝 stale Worker；
- 用户消息与主代理重连同时发生时，先完成 task handoff，再处理消息；
- 重复进入助手只能产生一个有效交接，使用 `last_handoff_event_seq` 去重；
- 交接基线和后续事件必须使用同一个有序事件流，Worker 在读取摘要期间完成时不能丢失完成事件；
- 没有主代理在线时，后台完成事件仍持久化；新 controller 绑定后再投递，不能丢给旧内存对象；
- Worker 在交接请求期间继续运行，交接读取不能改变任务状态或阻塞工具调用超过短超时；
- 取消排队任务和调度器取队首必须原子化；
- 补充与 `FINALIZING` 同时发生时，补充转成同会话新任务；
- `ROUTE_READY` 最终提交前必须重新读取目标任务状态、绑定和控制权；目标已终态或控制权已变化时
  不能机械应用旧路由决定，应转为新任务、重新路由或返回 `CONTROL_LOST`；
- 用户纠正路由误判时，按“未消费、已消费无副作用、已产生副作用”三种情况分别处理；
- 工具账本为 `UNKNOWN` 或可能存在副作用时，禁止自动重试并要求对账或用户确认；
- 任务完成后先提交终态，再调度下一项；
- 调度下一项必须校验队首仍是原任务，防止取消或调整造成重复启动；
- `ControllerInbox` 必须串行处理用户消息，保证同一时刻只有一个路由决定修改任务或队列；
- 只有 Conversation 当前 `controller_epoch` 可以消费 Inbox 或提交控制命令，旧 controller 必须拒绝；
- `ConversationTaskBinding` 的 `CONTROL` 唯一性和转移必须在同一数据库事务中完成；
- 补充、暂停、恢复和取消命令在最终提交时必须重新校验调用对话仍是任务 `CONTROL`，否则返回
  `CONTROL_LOST`；
- Worker 完成事件、补充消息和取消命令同时到达时，由状态机串行决定谁先提交；
- Worker 的 `finally` 必须释放租约，即使模型请求或工具抛出异常；
- 取消清理设置超时，避免一个卡住的 Binder 调用永久阻塞队列；
- 清理失败写入 `cleanup_error` 并保持 `RECOVERY_REQUIRED`，不能伪装成已取消或隐藏清理资源；
- `CANCELLING` 期间拒绝暂停、继续、补充、修改计划和重复取消，只有状态查询可用；
- 网络、模型和工具错误应有最大重试次数和退避；重试耗尽后进入 `FAILED` 或 `WAITING_USER`，
  不能让队首永久卡住；
- 队列达到上限时返回明确错误，不丢弃已有任务；
- 所有控制 API 都必须幂等。

## 15. Agent Runtime 可靠性与故障恢复

任务队列决定“谁先运行”，可靠性层决定“错误之后如何停止、恢复和证明没有重复操作”。正式 Worker 允许
执行写操作前，本节机制必须已经具备，不能推迟到高级版本。

### 15.1 单一状态权威

| 组件 | 权威职责 | 禁止事项 |
| --- | --- | --- |
| `AssistantTaskCoordinator` | 助手任务状态迁移、队列、暂停、取消、补充 | 不直接执行工具 |
| `InteractiveTaskScheduler` | 选择下一项任务、交互资源准入 | 不修改任务业务结果 |
| `OperationTaskRegistry` | 工具调用、下载和子 Job 的通用生命周期 | 不维护第二份助手根任务状态 |
| `AssistantTaskStore` | 任务快照、检查点和队列元数据持久化 | 不做调度决策 |
| `AssistantTaskEventStore` | 有序事件、交接和审计 | 不直接驱动任务状态 |
| `Worker Runtime` | 模型循环、工具调用、步骤检查点 | 不绕过 Coordinator 写任务终态 |
| `McpTaskTools` | MCP 协议兼容 Adapter | 不直接修改内部状态机 |

只有 `AssistantTaskCoordinator` 可以提交助手任务状态迁移。Worker 提交“期望迁移”和证据，由协调器
校验当前 revision、Worker ID 和租约后原子提交。旧 Worker 或重复消息不能覆盖新状态。

MVP 中本地助手、MCP 服务和调度器运行在同一个 NestSpace 主进程，Coordinator 使用进程级单例。
即便如此，执行权仍通过数据库中的 scheduler owner 和 fencing epoch 表达，而不是只依赖 Kotlin
`Mutex`。如果以后 MCP 或 Worker 迁移到独立进程，需要由数据库事务竞争 scheduler lease；只有当前
owner 可以取队首和创建 Worker，旧 owner 的 fencing token 立即失效。

### 15.2 资源租约与 Fencing

仅靠协程取消或进程内 `Mutex` 不能防止“旧 Worker 恢复后继续操作”。资源租约必须包含 fencing token：

```text
ResourceLease(
  resource_id,
  task_id,
  worker_id,
  fencing_token,
  acquired_at,
  heartbeat_at,
  expires_at
)
```

规则：

- 每次成功获取资源时，宿主单调递增 `fencing_token`；
- `fencing_token` 计数器和当前资源 owner 必须与租约一起持久化；
- 应用进程或执行 epoch 变化后，所有旧 token 自动失效，新租约从新的 epoch 开始；
- Worker 定期 heartbeat，租约过期后宿主可以回收；
- UI 租约按“动作批次”获取：连续相邻 UI 步骤可以保持，长时间模型、网络或用户等待前必须释放；
- 每个写工具在最终宿主执行入口校验 `resource_id + task_id + worker_id + fencing_token`；
- 只读工具可以豁免写前检查，但仍记录租约；
- 释放租约时校验当前 owner 和 token，旧 Worker 不能释放新租约；
- 暂停、用户接管、超时和取消都会使当前 token 失效；
- 恢复时重新获取新 token，并先重新观察页面状态；
- Worker 返回的旧结果不能修改新 Worker 的任务状态，Coordinator 必须拒绝 stale lease。

租约检查必须在最终 UI Binder/控制器入口完成。只在 Worker prompt 或工具包装层检查，无法阻止旧
协程绕过调度器继续点击。

### 15.3 工具执行账本

每次有副作用的工具调用必须持久化 `ToolExecutionRecord`：

```text
execution_id
task_id
step_id
tool_name
status
idempotency_key
started_at
completed_at
result_summary
error_class
```

状态：

```text
PENDING -> INTENT_RECORDED -> DISPATCHED -> EXECUTING -> SUCCEEDED / FAILED
          \-> INTENT_ABORTED                       \-> UNKNOWN
```

执行前先持久化“意图”和幂等键，真正调用宿主工具前再提交 `DISPATCHED`，完成后提交结果。若进程在
`INTENT_RECORDED` 中断且没有任何派发证据，可以将记录标记为 `INTENT_ABORTED`；若已经进入
`DISPATCHED`、`EXECUTING` 或 `UNKNOWN`，必须按可能存在副作用处理：

- 纯读取工具可以重新执行；
- 支持幂等键的工具可以使用同一 key 重试；
- UI 或外部写入必须先观察实际状态并对账；
- 无法证明操作未发生、也未证明已经完成时，任务进入 `WAITING_USER`，禁止盲目重试；
- 记录实际产生的副作用，主代理不能把它从交接摘要中省略。

手机 UI 操作通常无法实现严格 exactly-once。目标是“意图先落盘 + 尽量幂等 + 中断后对账 +
不确定时停止”。

### 15.4 事务状态、事件与 Outbox

助手任务状态、事件、Controller Outbox、Notification Outbox、队列元数据和工具执行账本必须放在同一个
应用私有 SQLite 数据库中。跨数据库或跨加密文件的写入无法参与同一事务，不能用来共同决定任务终态。

以下写入必须在同一个 SQLite 事务中完成：

1. 任务状态和 revision；
2. 对应的终态或状态迁移事件；
3. `ControllerInbox` 投递意图；
4. 通知意图；
5. 事件消费游标所需的基准信息。

禁止只更新任务状态后再单独插入事件，或先发通知再持久化结果。建议：

```text
BEGIN IMMEDIATE
  UPDATE assistant_tasks SET state=?, revision=revision+1 WHERE ...
  INSERT INTO assistant_task_events(...)
  INSERT INTO controller_outbox(event_id, conversation_id, ...)
  INSERT INTO notification_outbox(task_id, kind, ...)
COMMIT
```

`OutboxDispatcher` 负责异步投递：

- 每个消费者记录自己的 `last_event_seq`；
- 事件按 `event_id` 幂等；
- 投递失败可以重试，不阻塞任务终态提交；
- 通知只在 Outbox 中成功标记后发送；
- 应用重启后继续扫描未投递 Outbox；
- 过期或重复事件不会重复生成通知。
- 通知权限被拒绝时，通知 Outbox 标记为 `SKIPPED_PERMISSION` 并保留任务结果，不能无限重试；

### 15.5 能力快照与任务预算

Worker 不能因为共享 ToolRegistry 就获得全部工具权限。任务创建时生成 `TaskCapabilitySnapshot`：

```text
allowed_tools
allowed_resources
allow_external_write
confirmation_policy
```

能力快照只回答“允许做什么”，预算回答“最多做多少”，两者不能重复维护同一限制。

建议安全等级：

| 等级 | 操作 | 默认策略 |
| --- | --- | --- |
| L0 | 页面读取、状态查询 | 自动允许 |
| L1 | 可逆 UI 操作 | 自动执行 |
| L2 | 发送消息、删除文件、提交表单 | 按权限策略确认 |
| L3 | 支付、转账、不可逆外部操作 | 强制用户确认 |

能力快照只表示任务允许的范围，不能替代宿主权限。每次开始、恢复和每次工具执行仍由宿主校验当前
权限、确认记录、任务目标和租约。网页内容、外部 MCP 返回和 Worker 输出不能扩大能力范围。

正式任务使用统一预算：

| 预算 | 说明 |
| --- | --- |
| `max_runtime_ms` | 从开始执行到结束的总时限 |
| `max_model_calls` | 模型调用次数 |
| `max_tool_calls` | 工具调用次数 |
| `max_retries` | 单错误类型最大重试 |
| `max_wait_ms` | 网络、用户或内部任务的最大等待 |
| `max_result_bytes` | 结果和交付物的保留上限 |

预算耗尽不一定直接取消。等待用户和等待外部资源可以进入可恢复状态，但必须释放 UI 租约，并在
任务详情显示原因和过期时间。

### 15.6 错误分类与可观测性

错误至少分为：

```text
MODEL_ERROR
TOOL_ERROR
RESOURCE_BUSY
PERMISSION_DENIED
CONTROL_LOST
LAUNCH_BLOCKED
USER_TIMEOUT
NETWORK_TIMEOUT
WORKER_CRASHED
STATE_MISMATCH
UNKNOWN_SIDE_EFFECT
```

处理策略：

- `MODEL_ERROR`：有限重试，耗尽后失败或等待用户；
- `RESOURCE_BUSY`：等待资源，不能占用交互队首；
- `PERMISSION_DENIED`：等待用户授权；
- `CONTROL_LOST`：控制权已转移，拒绝旧对话提交并要求重新读取绑定；
- `LAUNCH_BLOCKED`：提示用户回到前台或授权；
- `STATE_MISMATCH`：重新观察页面并规划；
- `UNKNOWN_SIDE_EFFECT`：禁止自动重试，等待对账或用户确认；
- `WORKER_CRASHED`：根据工具账本和租约恢复，不按普通失败直接重跑。

内部指标应独立于用户可见事件，至少记录：

- 排队耗时、Worker 启动耗时；
- 模型和工具耗时；
- 暂停、取消响应耗时；
- 租约冲突、旧 token 拒绝次数；
- 进程恢复和重复事件次数；
- 交接失败、通知投递失败次数；
- 工具账本中 `UNKNOWN` 的数量。

## 16. 与当前实现的差距

### 16.1 已落地

- `spawn_agent` 已改为异步 Worker 任务并立即返回 `task_id`；
- 进程内单 Worker FIFO 队列、排队取消、暂停、继续和运行任务取消；
- `agent_task_list/status/supplement/pause/resume/cancel/cancel_queued` 工具；
- 任务卡 6 个 UI 状态映射、单一主操作和取消锁定；
- AI 助手 Header 统一任务入口；用户和 MCP 任务都创建为 Worker；
- 正式 Worker 前台保活服务；
- 后台完成通知、通知去重基础，以及点击通知定位任务；
- 每次暂停恢复使用新的 Worker 执行身份；
- `OperationTaskRegistry` 持久化任务状态和结果；
- 主 Agent 和 Worker 的工具边界：Worker 不能递归创建子 Agent 或控制任务队列。

### 16.2 仍待实现

- 完整的 SQLite 事务任务/事件/Outbox 存储；
- 持久化 fencing epoch、工具有副作用执行账本和崩溃对账；
- `ConversationTaskBinding`、`controller_epoch` 和新对话上下文绑定；
- `RECOVERY_REQUIRED` 的持久化恢复矩阵与清理重试；
- 完整任务事件展开、Outbox 消费游标和内部指标；
- 新主 Agent 的正式交接屏障；
- 能力快照和任务预算的完整执行器接入。

当前代码可复用：

- [OperationTaskRegistry.kt](../app/src/main/java/com/coke/nestspace/task/OperationTaskRegistry.kt)：
  已有 queued/running/paused/cancelling 等状态、父子任务取消和持久化基础；
- [TaskJournal.kt](../app/src/main/java/com/coke/nestspace/task/TaskJournal.kt)：
  已有终态持久化和重启中断处理；
- [ResourceScope.kt](../app/src/main/java/com/coke/nestspace/task/ResourceScope.kt)：
  可用于任务资源的有界释放。

需要改造：

- 当前 [LeaseManager](../app/src/main/java/com/coke/nestspace/tools/ToolTypes.kt) 只有会话级
  TTL，没有任务级 owner、heartbeat、持久化 epoch 或 fencing token；
- 当前 [TaskJournal](../app/src/main/java/com/coke/nestspace/task/TaskJournal.kt) 保存任务快照，
  但还没有状态、事件、Controller Outbox 和通知 Outbox 的统一事务；
- 当前 [ChildRunStore](../app/src/main/java/com/coke/nestspace/agent/ChildRunStore.kt) 只保存
  Worker 最终预览，不能承担步骤检查点、结构化指令和完整执行事件；
- [AssistantConversation.kt](../app/src/main/java/com/coke/nestspace/agent/AssistantConversation.kt)：
  去掉“主代理整个 Worker 生命周期都 busy”的模型，拆分消息控制面和执行面；
- [McpTaskTools.kt](../app/src/main/java/com/coke/nestspace/mcp/McpTaskTools.kt)：
  作为统一 Worker 队列的 MCP 兼容 Adapter，不再维护独立队列；
- [AgentToolSpecs.kt](../app/src/main/java/com/coke/nestspace/tools/AgentToolSpecs.kt)：
  `spawn_agent` 改为创建任务并异步返回，而不是在父运行内等待；
- [ChildRunStore.kt](../app/src/main/java/com/coke/nestspace/agent/ChildRunStore.kt)：
  增加 Worker 状态、事件、补充和暂停信息；
- [ToolTypes.kt](../app/src/main/java/com/coke/nestspace/tools/ToolTypes.kt)：
  LeaseManager 从会话级扩展为任务级、可暂停和可审计的资源租约；
- [ToolRegistry.kt](../app/src/main/java/com/coke/nestspace/tools/ToolRegistry.kt)：
  Worker 只在获得交互租约后执行写工具；
- [BrowserSession.kt](../app/src/main/java/com/coke/nestspace/browser/BrowserSession.kt)：
  为 headless tab 增加 task owner 和并发隔离；
- [AgentHeader.kt](../app/src/main/java/com/coke/nestspace/ui/agent/AgentHeader.kt)：
  把 MCP 任务按钮扩展为助手任务入口，并显示活动任务角标；
- [ChildRunsScreen.kt](../app/src/main/java/com/coke/nestspace/ui/settings/ChildRunsScreen.kt)：
  升级为任务中心，显示当前任务、等待队列和可展开事件。

需要新增：

- `AssistantTaskCoordinator`：唯一助手任务状态权威；
- `FencingLeaseManager`：带 resource owner、heartbeat 和 fencing token 的租约；
- `ToolExecutionLedger`：有副作用工具的执行意图、结果和 UNKNOWN 对账记录；
- `ControllerOutbox` 与 `NotificationOutbox`：事务事件投递和可靠通知；
- `TaskCapabilitySnapshot` 与 `TaskBudget`：任务能力和资源预算；
- `AssistantTaskStore` 与 `AssistantTaskEventStore`：任务快照、事件、检查点和恢复字段；
- `ObservabilityRecorder`：内部指标和诊断事件。

## 17. 实施阶段

### 阶段 1：可靠的单 Worker 基础

- 定义助手任务模型、状态维度、revision 和唯一状态权威；
- 由 Coordinator 统一本地、语音、定时和 MCP 的任务入队；
- 实现单一交互队列、同级 FIFO 和排队任务取消；
- 实现任务级资源租约、heartbeat 和 fencing token；
- 在最终 UI 入口校验 fencing token；
- 建立工具执行账本，先持久化意图再执行有副作用工具；
- 状态、事件、Controller Outbox 和通知 Outbox 使用同一事务；
- 实现基础暂停、恢复、取消和安全清理；
- 保留 MCP 工具兼容 Adapter。

阶段 1 不要求所有高级 UI 完成，但正式 Worker 一旦允许写手机 UI，fencing、执行账本和事务事件就是
不可省略的准入条件。

### 阶段 2：异步 Worker 与主代理交接

- 把 `spawn_agent` 改为异步创建任务；
- 队首任务才创建 Worker；
- 主代理在 Worker 运行期间继续处理查询；
- 实现结构化补充指令、暂停、恢复和取消；
- 实现 ControllerInbox、主代理重连和交接事件屏障；
- 升级为任务中心，显示当前任务、等待队列和可展开事件；
- 实现通知结果直出和主代理上下文接管。

### 阶段 3：多来源与体验完善

- 完善 MCP、语音和定时任务的统一体验；
- 增加来源公平性、owner 配额和必要的优先级老化；
- 完善浏览器 headless 任务隔离；
- 增加错误分类、预算、内部指标和诊断页面；
- 完善恢复对账、通知深链和 Conversation 生命周期；
- 根据真实失败数据调整超时、重试、保留和队列上限。

## 18. 验收场景

以下场景必须全部通过：

1. 任务 A 运行，B、C 排队；取消 B 后 B 立即取消，C 前移，A 不受影响；
2. 排队项只有任务记录，没有 Worker、模型请求或容器资源；
3. A 暂停后 B 不会自动启动；用户可手动操作手机；
4. A 恢复时重新观察页面，不沿用暂停前的错误 UI 状态；
5. 用户或主代理都可取消 A；取消只影响 A 及其子资源，B 正常启动；
6. Worker 运行时，主代理仍能回答“现在做到哪一步”；
7. 在上下文表明“再顺便导出 PDF”属于任务 A 时，主代理将其作为补充并在下一步注入；
8. 在上下文表明“另外帮我打开另一个应用”是独立目标时，主代理创建新任务并排到 B 后面；
9. 消息目标不明确时，主代理先询问且不执行任何任务变更；
10. 用户纠正误判后，原补充被撤销，新任务进入队列；
11. 等待列表的队列位置在取消和完成后正确更新；
12. 可见浏览器和交互 UI 不会出现两个 Agent 同时操作；
13. 简单问答和独立 headless 网页读取可与交互 Worker 并行；
14. 进程重启后不自动点击，所有运行任务显示为中断；
15. 用户拒绝或尚未处理权限确认时，队列保持冻结；
16. 执行过程可展开，但不包含密钥和模型隐藏推理；
17. 离开 AI 助手页面后正式任务和等待队列继续执行；
18. 任务运行期间存在前台服务和常驻状态通知；
19. 从助手 Header 一键打开任务面板，等待队列清晰可见；
20. 用户暂停任务后，应用保持运行但不会自动恢复或启动下一项；
21. 本机用户可从任务中心取消 MCP 创建的任务；
22. 远程 MCP 不能查询或取消其他 owner 的任务；
23. 多个 MCP owner 创建任务时不会突破队列上限或绕过全局交互锁；
24. 用户手动操作当前 Worker 正在控制的同一应用时，Worker 暂停并交还控制权；
25. 新建或清空对话不会取消后台任务，任务列表仍可查询和控制；
26. 后台任务运行期间退出并重新进入助手，新主代理先收到任务、进度、未消费补充和完成情况；
27. 任务在离开期间已完成时，新主代理能收到最终结果，而不是把任务当作仍在运行；
28. 重复重连不会重复注入旧的交接摘要；
29. 用户在交接完成前发送消息时，消息等待交接完成后再被路由为补充或新任务；
30. 用户不在助手页时任务完成，通知可立即出现，点击后直接看到子代理结果；
31. 打开完成通知后，新主代理已收到结果上下文，但不会为展示结果强制多调用一次模型；
32. 任务失败或取消时，通知和结果页显示对应终态，不只显示“已完成”；
33. 助手界面可见时使用聊天内完成卡片，不重复发送完成通知；
34. 同一次完成只产生一个完成通知，打开后对应通知被清除；
35. 用户离开页面时直连轻任务停止并清理，不会转入后台执行；
36. 直连轻任务离开后不产生完成通知，也不会出现在等待队列；
37. 本机用户任务不会长期排在一批旧 MCP 任务后面，队列优先级符合设计；
38. 主代理不在线时完成的后台任务，在新主代理绑定后仍能收到结果和交接事件；
39. 旧 Worker 使用过期 fencing token 执行 UI 操作时被宿主拒绝；
40. 进程在工具执行到一半时死亡，恢复后不会盲目重复不可逆操作；
41. 任务状态成功提交时，事件和通知 Outbox 在同一事务中可见；
42. 重复投递事件不会生成重复结果卡片、通知或交接；
43. 重启后 `QUEUED`、`PAUSED`、`CANCELLING` 和 `FINALIZING` 按恢复矩阵处理；
44. 调度队列位置变化不需要重写任务历史，位置由 `priority + queue_sequence` 实时计算；
45. Worker 请求能力范围外的工具时，宿主在执行前拒绝；
46. 新对话创建独立目标时不会继承旧对话的任务上下文；
47. 用户在新对话表达继续或查询既有任务意图时，主代理根据上下文选择任务和绑定方式；
48. 两个新对话可以分别绑定不同任务，并显示各自相关的任务摘要；
49. 同一任务只有一个 `CONTROL` 对话，转移控制权后旧对话降级为 `OBSERVE`；
50. `OBSERVE` 对话不能向运行中 Worker 添加补充或执行控制命令；
51. 原始任务没有控制对话时，新对话取得控制权记录 `CONTROL_ACQUIRED`；
52. 两个对话并发请求控制权时只有一个成功；
53. 用户要求继续已完成任务时保留旧结果并创建新的关联任务；
54. 多个新对话可绑定不同任务，彼此只收到自己的任务上下文和结果摘要；
55. 新主代理绑定后旧主代理的延迟消息和工具结果不能修改当前状态；
56. `CANCELLING` 和 `FINALIZING` 重启后进入 `RECOVERY_REQUIRED`，清理或对账前不启动下一任务；
57. `RECOVERY_REQUIRED` 根据账本和清理结果回到 `CANCELLED`、`SUCCEEDED`、`FAILED` 或 `INTERRUPTED`；
58. 主代理 claim 消息后崩溃，恢复重试不会重复创建任务或补充；
59. 控制权已经转移后，旧对话尚未提交的补充和控制命令被拒绝；
60. 清理失败时任务保持 `RECOVERY_REQUIRED`，用户可重试清理，后续任务不会提前启动；
61. 新 controller 接管旧 epoch 的 `PROCESSING` 消息，`ROUTE_READY` 消息不再重新调用模型；
62. `INTERRUPTED` 任务不能重新进入 `RUNNING`，用户继续时创建带父引用的续跑任务；
63. `QUEUED` 和可恢复的 `PAUSED` 任务可以恢复自身，不创建重复任务；
64. 长时间离线后事件裁剪不会删除未投递终态、恢复证据或未交接事件；
65. `ROUTE_READY` 提交前目标已终态或控制权已转移时，不会把旧路由机械应用到新状态；
66. 任务进入 `CANCELLING` 后所有变更按钮禁用，只有状态查询可用；
67. 重复点击取消不会重复触发清理，只返回当前取消中状态；
68. `CANCELLING` 只有在进入 `CANCELLED` 或 `RECOVERY_REQUIRED` 后才恢复可操作界面；
69. 任务中心只展示 6 个稳定 UI 状态，不直接暴露全部内部状态；
70. 任务卡最多显示一个主操作和一个取消操作，按钮由任务快照控制字段决定。

## 19. 测试建议

单元测试：

- 状态机合法和非法迁移；
- 内部状态到 6 个 UI 状态的映射；
- `primary_action` 和 `can_cancel` 派生；
- `CANCELLING` 操作锁定和重复取消幂等；
- FIFO 调度；
- 队首暂停冻结；
- 排队任务取消优先级；
- 运行任务取消超时和清理失败；
- 主代理补充/新任务路由；
- 多活动任务的歧义澄清；
- 用户纠正误判后的回滚；
- 主代理直连执行超过步数或时限后的任务升级；
- 补充与完成竞态；
- 父子任务取消隔离；
- 幂等控制和 owner 鉴权；
- 状态维度组合和合法/非法迁移；
- fencing token 单调递增、过期和旧 token 拒绝；
- 工具账本意图、结果和 UNKNOWN 对账；
- 状态、事件、Outbox 事务回滚；
- 重启恢复矩阵和 `recovery_kind`；
- `RECOVERY_REQUIRED` 非终态、队列阻塞和后续状态收敛；
- 终态 `INTERRUPTED` 的续跑任务创建和父引用；
- `queue_position` 派生计算和优先级排序；
- 能力快照、预算和错误分类执行；
- 新对话任务匹配与绑定；
- `TaskContextBundle` 大小限制和事件分页；
- 单 `CONTROL`、多 `OBSERVE` 绑定规则；
- 控制权转移和旧对话降级；
- ControllerInbox claim、崩溃重试和幂等键。

集成测试：

- Worker 创建前无模型请求；
- 交互租约冲突时返回 `SPACE_BUSY`；
- 旧 Worker 使用旧 fencing token 调用 UI 写入被拒绝；
- 暂停释放租约、恢复重新获取；
- 进程重启按矩阵恢复，`QUEUED` 和 `PAUSED` 不被错误标记；
- 工具在 EXECUTING 时进程死亡，恢复后进入对账或等待用户；
- 事务提交失败时不会出现“任务成功但无事件”的孤立状态；
- Inbox 消息 claim 后进程死亡，恢复不会重复创建任务或补充；
- Outbox 重试和消费者游标不会重复通知或重复交接；
- Activity 暂停、页面销毁不会取消正式任务；
- 有活动任务时任务前台服务启动，队列清空后停止；
- 任务处于暂停、取消中或等待用户时前台服务不会提前停止；
- 后台任务完成和等待用户通知正确展示；
- Worker 完成事件不打断主代理当前的简单问答；
- 锁屏通知隐藏敏感任务内容；
- 没有后台启动豁免时返回明确错误，不进入无休止重试；
- 主代理控制回合串行执行，不会重复启动同一队首任务；
- 新 controller 绑定前不能处理用户任务消息；
- 重复重连按 `last_handoff_event_seq` 去重；
- 交接快照和后续事件之间没有丢事件窗口；
- Worker 运行中请求交接不会改变任务状态或阻塞超过超时上限；
- 离开期间完成的任务在重连时返回最终结果；
- 完成通知可以按 `task_id` 深链到结果页；
- 打开结果页不会重新执行任务或强制调用主代理模型；
- 下一任务已启动时，完成任务的结果和当前队列状态同时正确显示；
- 助手界面可见时不会重复发送完成通知；
- 完成通知按 `task_id` 去重并在打开后清理；
- 页面销毁时直连轻任务及其 headless tab 被取消清理；
- 直连轻任务取消后不产生完成通知，不进入等待队列；
- 用户、定时和 MCP 任务按优先级与同级 FIFO 调度；
- 没有 controller 时完成的事件在持久化后不会丢失；
- 事件裁剪保留未投递、未交接和恢复所需记录；
- `ROUTE_READY` 提交前重新校验目标状态和当前 CONTROL；
- 对话清空、归档和删除按任务生命周期规则处理；
- 新对话无引用时不绑定旧任务；
- 新对话明确引用任务时生成正确的 `TaskContextBundle`；
- 控制权转移不会改变任务 Worker 或队列状态；
- 控制权转移后旧对话的延迟补充或控制请求被拒绝；
- 清理失败不会把任务伪装成已取消，重试成功后队列才继续；
- 取消中的任务和通知按钮全部禁用，状态确认后恢复对应操作；
- 任务卡不因内部状态增加额外按钮，主动作和取消操作不重复；
- controller 接管时旧 claim 被回收，已持久化路由决定不会被重复推理；
- 对 `INTERRUPTED` 选择继续时创建新任务，不修改旧终态；
- `OBSERVE` 对话的补充和控制请求被拒绝；
- `CONTROL` 唯一约束在并发绑定下成立；
- 无旧控制者时记录 `CONTROL_ACQUIRED`；
- 已完成任务继续操作时创建新的关联任务，不重启终态；
- controller epoch 递增后旧 controller 不能消费消息或提交控制；
- 新建对话和清空消息不影响任务队列；
- 用户接管同一交互资源时 Worker 停止提交新的 UI 操作；
- 重试有上限，失败任务不会阻塞下一项；
- headless browser tab owner 隔离；
- 用户确认流程保持 `WAITING_USER`。

界面测试：

- 当前任务、等待队列、最近完成分组；
- Header 任务角标和活动任务摘要条；
- 手机 Bottom Sheet 与平板侧栏布局；
- 语音助手显示任务摘要并可打开任务面板；
- 重新进入助手时显示后台任务交接摘要；
- 从完成通知打开时直接定位对应任务结果；
- 成功、失败、取消和中断结果使用不同状态展示；
- 排队项没有 Worker 时仍可取消；
- 暂停/继续按钮状态；
- 展开执行过程；
- 中文和 English 文案。

## 20. 关键时序

### 20.1 创建与执行

```text
主代理
  → Coordinator.createTask(goal, capability, budget)
  → 持久化 QUEUED 任务和 ROUTE/TASK_CREATED 事件
  → InteractiveTaskScheduler.enqueue(task)

Scheduler
  → 选择队首
  → Coordinator.transition(PREPARING)
  → 获取 FencingLease
  → 创建 Worker

Worker
  → 校验 capability + lease token
  → ToolExecutionLedger.recordIntent()
  → 执行工具
  → ToolExecutionLedger.recordResult()
  → 提交步骤检查点和事件

Coordinator
  → 同一事务提交终态 + 事件 + Outbox
  → 释放资源
  → Scheduler 选择下一项
```

### 20.2 补充或新任务

```text
用户消息
  → ControllerInbox
  → 主代理读取 TaskHandoff / 当前任务快照
  → 判断 FOLLOW_UP 或 NEW_TASK

FOLLOW_UP
  → TaskInstruction(PENDING)
  → Worker 在下一模型步骤消费
  → 记录 CONSUMED / APPLIED

NEW_TASK
  → Coordinator.createTask()
  → 按 priority + queue_sequence 入队
```

### 20.3 主代理重连与交接

```text
新 controller 创建
  → 读取任务快照和 last_event_seq
  → 订阅后续事件（建立屏障）
  → 向运行中 Worker 请求有界交接摘要
  → 读取持久化检查点（超时回退）
  → 注入 TASK_HANDOFF
  → 标记 controller 消费游标
  → 开放 ControllerInbox 处理用户消息
```

### 20.4 进程死亡与恢复

```text
应用启动
  → 加载任务、事件、Outbox 和 ToolExecutionLedger
  → 按恢复矩阵映射 TaskState / recovery_kind
  → 审计 FencingLease 和 cleanup_pending
  → 对 EXECUTING/UNKNOWN 工具有限对账
  → 处理未投递 Outbox
  → 展示恢复确认，不自动执行 UI 操作

用户确认
  → QUEUED / PAUSED：恢复原任务
  → RECOVERY_REQUIRED：完成清理或结果对账后收敛原任务
  → INTERRUPTED：创建 assistant_task_resume 续跑任务，保留原终态
  → 重新验证权限、模型和页面
  → 获取新 fencing token
  → 重新观察页面
  → 从安全步骤继续或等待用户
```

### 20.5 新对话绑定任务

```text
用户新建对话并输入目标
  → 创建独立 conversation_id 和 controller_id
  → 主代理读取消息意图

独立新目标
  → 不绑定旧任务
  → 只提供全局运行中/等待数量

引用或选择已有任务
  → 创建 ConversationTaskBinding
  → 生成有界 TaskContextBundle
  → 读取对应 TASK_HANDOFF

请求接管运行中任务
  → 已有 CONTROL：旧对话降级为 OBSERVE，记录 CONTROL_TRANSFERRED
  → 没有 CONTROL：记录 CONTROL_ACQUIRED
  → 新对话成为唯一 CONTROL
  → 任务 Worker 和队列状态不改变
```
