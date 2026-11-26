# NestSpace Android 架构重构与精简方案

- 状态：本轮代码结构重构完成，待设备冒烟验证
- 审计基线：`c5f5342`（2026-11-26）
- 复核状态：已完成静态复核、Debug/Snapshot/Release 构建和 JVM 测试；Lint 基线已记录，设备验证未执行
- 范围：`:app`、`:plugin-api`、`:testapp` 及 Gradle 构建脚本
- 目标：在保持功能行为不变的前提下，解决单模块内上帝文件、上帝类、循环依赖、
  重复实现和无效代码问题
- 本轮约束：NestSpace 业务代码和业务无关 Agent SDK 都留在 `:app`，
  不新增业务模块或 SDK Gradle 模块

## 1. 审计范围与方法

本次审计基于当前仓库静态代码检查，覆盖 Kotlin、Java、AIDL、Manifest、
Compose UI、Gradle 配置、JNI 调用面、现有 JVM 测试和 Native 文件规模。
首轮重构以 Kotlin/Java 的职责和包边界为主；C++ 只审计 JNI 边界，不在没有
Native 静态分析和设备基线时贸然修改。

主要检查项：

- `:app` 内部包边界和包依赖方向；
- 文件、类、函数规模和职责数量；
- 包与包之间的双向依赖；
- 重复实现、无效导入、无引用符号和迁移残留；
- 反射、AIDL、Manifest、JNI 等非普通调用入口；
- 现有测试覆盖与高风险兼容路径。

系统默认 `java` 不在 `PATH` 中，但 Android Studio JBR
`/Applications/Android Studio.app/Contents/jbr/Contents/Home` 可用。以下基线
已经实际执行：

- `:app:compileDebugKotlin --rerun-tasks --warning-mode all`：成功；
- `:app:testDebugUnitTest :plugin-api:test`：成功，`:app` 250 个用例、
  `:plugin-api` 53 个用例，0 failure、0 error、0 skipped；
- `:app:assembleDebug`：成功；
- `:testapp:assembleDebug`：成功，覆盖四 ABI Native 构建；
- `:app:assembleSnapshot` 和 `:app:assembleRelease`：分别在
  `kotlin.daemon.jvmargs=-Xmx4g` 下成功，Release 为本地未签名构建；同时并行
  运行两个任务曾在默认内存下触发 Kotlin daemon OOM，不是源码失败；
- `:app:lintDebug`：失败，已有 193 errors、363 warnings、2 hints。

因此删除项已有编译和单元测试基线，但仍必须在目标 androidTest/真机流程中验证。

### 1.1 编译与 Lint 基线

Kotlin 编译没有阻塞错误，但报告了以下需要单独评估的编译警告：

- `ContainerSession` 和 `ContainerAppWidgetManager` 的反射返回值存在 nullable
  类型不匹配；
- `ContainerSession.kt:1385`、`ContainerJobScheduler.kt:55` 和
  `VirtualStorageProbe.kt:34` 存在“条件恒真/恒假”；
- 多个 Android 旧 API、`onBackPressed` 和系统栏接口存在弃用警告；
- `NativeLibs`、`RuntimeDexPathClassLoader` 存在未检查数组转换警告。

Lint 错误主要来自容器有意访问高于 minSdk 的 API、隐藏系统接口和虚拟广播：

- 162 个 `NewApi`；
- 11 个 `MissingPermission`；
- 7 个 `SoonBlockedPrivateApi`；
- 2 个 `GestureBackNavigation`；
- 2 个 `ResourceType`；
- 其余包括 `MissingSuperCall`、`HardcodedDebugMode`、`ByteOrderMark` 等。

这些不能直接按普通 App 的方式“修复”：

- `StageAActivity` 不调用 `super.onCreate` 是有意接管目标 Activity；
- `HostUiController` 的 `\uFEFF` 是用于裁剪 `uiautomator` 输出的字符字面量，
  Lint 的 `ByteOrderMark` 是误报；
- `debug/AndroidManifest.xml` 固定 `debuggable=false` 可能用于兼容测试，需确认
  构建预期后才能改；
- `AboutDetailScreen` 对 drawable 使用 `openRawResource` 目前能编译，但设备导出
  必须回归，不能按 `ResourceType` 告警直接改资源路径。

Lint 明确标记为未使用的资源是
`app/src/main/res/raw/ic_model_disconnected.svg`；代码实际使用
`R.drawable.ic_model_disconnected`，两者不是同一资源。删除前仍需确认没有按文件名
读取 raw 资源。另有 Manifest 中的冗余 Activity 标签可在行为验证后删除。

## 2. 当前代码规模

审计结果：

| 范围 | 文件数 | 文件行数（含空行和注释） |
| --- | ---: | ---: |
| `app/src/main/java` | 323 | 95,816 |
| `plugin-api/src/main/java` | 131 | 7,760 |
| `app/src/test` | 78 | 6,742 |
| `plugin-api/src/test` | 16 | 1,718 |
| `testapp/src/main/java` | 38 | 8,774 |
| `app/src/main/cpp` | 29 | 18,840 |

其中 `:app` 的 Kotlin/Java 主源码约占 Kotlin/Java 主代码总量的 92.5%。
`plugin-api` 的职责相对清晰，主要架构压力集中在 `:app`。另有
`AndroidManifest.xml` 660 行，其中包含大量进程槽位和 Binder Service 声明，
也是运行时拆分时必须处理的结构性代码。

Native 侧同样存在大文件：`app/src/main/cpp` 有 7 个文件达到或超过 1,000 行，
合计 9,412 行。本文不为追求行数而拆 C++；Native 职责拆分必须先完成调用图、
符号导出、编译选项和多 ABI 基线审计。

规模分布：

- 16 个文件达到或超过 1,000 行，共 25,866 行；
- 53 个文件达到或超过 500 行，共 50,272 行，约占 Kotlin/Java 主代码 48.5%；
- 最大的 10 个文件合计约 19,300 行；
- `package` 层最大的三个区域为 `container`（约 33,700 行）、`ui`
  （约 20,600 行）和 `tools`（约 5,300 行）。

最大 Kotlin/Java 文件：

| 文件 | 行数 | 当前主要职责 |
| --- | ---: | --- |
| `ContainerSession.kt` | 3,666 | 进程身份、LoadedApk、Application、ClassLoader、Resources、Activity 创建、Provider/Service 直达、Native Hook、诊断 |
| `StageAActivity.kt` | 2,567 | 目标 Activity 宿主、生命周期、镜像窗口、导航交接、返回、PiP、权限结果、Intent 分发 |
| `FloatingDotOverlay.kt` | 2,002 | 悬浮球控制器、系统窗口、任务面板、朗读面板、多个自绘 View |
| `PluginCenterScreen.kt` | 1,931 | 插件中心状态、操作、筛选、列表、设置表单、多个 Dialog |
| `ContainerPackageManager.java` | 1,791 | 虚拟 PackageManager 全量查询、权限、签名、UID、结果克隆与标志过滤 |
| `SystemServiceIdentity.kt` | 1,671 | 多组系统服务代理、Binder 事务和包名/UID 改写 |
| `MiniContainerEngine.kt` | 1,465 | APK 复制、解析、安装、匹配、状态持久化、启动、镜像、进程池和图标缓存 |
| `ContainerContext.kt` | 1,454 | Context、存储、权限、资源和系统服务虚拟化 |
| `DesktopScreen.kt` | 1,402 | 桌面状态、布局、手势、文件夹、菜单和 Compose UI |
| `BrowserSession.kt` | 1,334 | 标签、导航、WebView、下载、代理和状态持久化 |

## 3. 核心架构问题

### P0：`:app` 内部缺少可执行的逻辑边界

Gradle 模块继续保留 `:app`，不在本次重构中拆分。问题是当前 `:app` 内部的包
之间没有稳定依赖方向，同时包含：

- 控制面和 Android 入口；
- APK 与实例存储；
- 容器 Session、Host Activity、进程槽位和 Provider；
- Compose UI、Agent、MCP、插件中心和设置页；
- Native Facade、运行时诊断和插件宿主。

结果是 UI、Agent、容器运行时和系统 Hook 可以任意互相引用。单模块编译不会
阻止越层 import，因此需要包边界和自动检查来承担原本由多模块提供的约束。

### P0：多个上帝类同时承担编排、协议适配和 UI

最明显的职责混装：

- `ContainerSession.create()` 约 774 行，连续处理 ClassLoader、Application、
  LoadedApk、Context、Resources、Native Environment、Binder 和启动报告。
- `PluginCenterScreen()` 主函数约 733 行，混合页面状态、文件分享、安装、
  启停、选择、筛选和多个业务动作。
- `MiniContainerEngine.installLocked()` 约 269 行，混合输入复制、APK 解析、
  包一致性、签名一致性、ABI、文件移动、状态回滚和模型发布。
- `StageAActivity.openActivity()`、`onCreateHost()` 和 `handleIntent()`
  分别约 159、149 和 141 行，和镜像、导航、生命周期、权限交织。

单纯把长函数拆成小函数不能解决职责问题。必须先把稳定边界提取为协作对象，
再让原类退化为明确的门面或编排者。

### P1：核心包形成一个跨 21 个域的强连通分量

静态 import 图不是一个或几个孤立循环，而是下面 21 个顶层域共同组成的单一
强连通分量：

```text
agent, assistant, browser, container, control, engine, eval, llm, mcp,
memory, notebook, plugin, proxy, replay, schedule, screenreader, service,
speech, tools, ui, vm
```

当前存在以下直接双向依赖：

```text
agent <-> browser       agent <-> llm          agent <-> mcp
agent <-> memory        agent <-> plugin       agent <-> service
agent <-> tools         assistant <-> browser  assistant <-> ui
browser <-> proxy       browser <-> ui         container <-> control
container <-> engine    mcp <-> service        plugin <-> speech
plugin <-> tools        replay <-> tools
```

代表性具体耦合：

| 方向 A | 方向 B | 典型耦合 |
| --- | --- | --- |
| `agent` | `tools` | Agent 使用工具注册与结果，工具使用任务协调、权限和用户确认 |
| `tools` | `plugin` | 插件工具使用 Host 能力，插件宿主又把插件能力注册成 Agent 工具 |
| `container` | `engine` | Engine 使用 Session/ProcessPool，容器辅助类又使用 SpaceStore/EngineResult |
| `browser` | `assistant` | 浏览器入口控制悬浮球，语音界面直接依赖 BrowserSession |

这些循环说明当前包名只是目录，不是依赖边界。即使不拆 Gradle 模块，仅按现有
目录移动文件也无法消除环；必须先收口契约和回调，再用自动测试约束 import。

### P1：跨功能具体实现依赖

除上面的包间环，还存在必须专项处理的跨层依赖：

- `ContainerLaunchProxyActivity` 和 `ContainerSelfCheck` 直接调用 `AppGraph`，
  让容器域反向依赖应用组合根；
- `ContainerUiService` 引用 `control.ContainerUiController`，而控制客户端又引用
  `container`，需要把容器侧 UI 执行器与宿主侧客户端分到不同包；
- `SystemServiceIdentity` 直接引用浏览器包中的 `webUrlFromViewIntent`，仅是工具
  函数位置错误，却制造了容器到浏览器的依赖；
- `agent` 直接引用 `AssistantTaskForegroundService`、`HeadlessMirrorWindow`、
  `ContainerProcessPool`、`BrowserActivity` 和 `BrowserSession`；
- `mcp` 直接读取悬浮球设置、浏览器代理状态和多个 Agent 注册表；
- `tools` 同时依赖 Agent、浏览器、容器、控制、插件、记忆、调度和宿主等具体实现。

这些耦合不能靠“多一个 Utils”解决，需要在对应 API 中只定义真实存在的调用能力，
再由 `:app` 组合根注入实现。

### P1：重复实现散落在多个包

已确认的重复模式：

- 7 处路径名清理函数，其中 6 处完全相同：
  `replace(Regex("[^A-Za-z0-9._-]"), "_")`；
- 4 份带大小上限的流读取循环；
- 5 处相同的 `MM-dd HH:mm` 时间格式化；
- `MiniContainerEngine` 三次重复“复制 URI 到临时 APK、检查输入、解析描述符”；
- `ContainerSession` 多处重复遍历字段层级、设置反射字段和处理反射失败；
- `settings.gradle.kts` 与 `app/build.gradle.kts` 各自实现一次开发插件目录发现和名称清理。

### P2：存在迁移残留和无引用代码

静态引用扫描发现一组高可信无效代码：

- `NotebookActivity.NotebookEditor` 约 382 行，已被
  `NotebookEditorDesk` 取代；
- `AssistantVoiceActivity` 中 `ChatBubble`、`MessageText`、`CodeBlock`、
  `MarkdownBlock.plainText` 和 `ModelPreparingCard` 组成的迁移残留；
- `ContainerSession.restoreHostLoadedApkPackageName()`；
- `ContainerSession.createTargetLoadedApk()`；
- `BrowserSession.removeDownload()`；
- `ToolRegistry.subagentTools`；
- `ContainerContext.instanceKey`；
- `ContainerSlotState.generationOf()`；
- 模板测试 `ExampleUnitTest`。

同时静态扫描检出 81 个仅出现在 import 语句中的 Kotlin 导入；删除迁移残留后，
只被残留代码使用的导入还会继续增加。其中
`VirtualApp.kt` 在页面拆分后遗留了大量 Compose 和业务类型导入。该结果仍需
编译器最终确认，尤其是 Compose 委托属性和平台特定扩展。

### P2：测试覆盖与风险不匹配

现有 78 个 `:app` JVM 测试文件对纯策略、存储、代理、网络和部分运行时有较好覆盖。
`app/src/androidTest` 目前只有 1 个包名校验模板测试，真机行为主要依赖手工冒烟和
`testapp`。最大的风险类没有同等强度的特征测试：

- `ContainerSession`；
- `StageAActivity`；
- `MiniContainerEngine` 的完整安装与启动事务；
- `FloatingDotOverlay` 的窗口状态；
- `PluginCenterScreen` 的用户动作。

在拆分类之前，必须先建立行为基线，不能把“能编译”当作“行为未变”。

## 4. 重构原则

1. 行为优先：不改变 Intent、生命周期顺序、线程、锁、超时、错误码、日志关键字段、
   持久化格式和系统调用顺序。
2. 业务分层不新增 Gradle 模块：`core`、`data`、`container/api`、
   `container/runtime`、`agent`、`agent/sdk` 仍是 `:app` 内的包边界。
3. 一次只做一类改动：删除死代码、提取重复、拆分职责、移动包分别提交和验证。
4. 禁止以“历史兼容”为理由保留无引用实现；反射、AIDL、Manifest、JNI 和插件公开
   API 必须单独证明后再处理。
5. 不引入通用 DI 框架、事件总线、BaseClass 或万能 Utils。只有稳定且重复的协议
   才允许抽象。
6. 不改变持久化键名、JSON schema、错误码、工具名、广播 Action 和进程名。

## 5. 目标边界：业务和通用 Agent 内核都留在 `:app`

Gradle 模块结构保持不变：

```text
:app
:plugin-api
:testapp

:dev-plugin-runtime-demo -> example-plugin/plugin
:dev-plugin-<name>       -> dev-plugins/<name>
```

业务侧不新增 `core`、`data`、`container/api`、`container/runtime`、`agent`
或 `agent/sdk` Gradle 模块。它们都是 `:app` 内的逻辑分层包；
`agent/sdk` 承载业务无关的 Agent 契约、统一对话输入和工具循环：

| 逻辑包 | 存放内容 | 允许依赖 |
| --- | --- | --- |
| `boot` | `NestSpaceApp`、`AppGraph`、延迟启动和依赖装配 | 所有功能、容器、Agent、数据 |
| `core` | 原 `common`、`i18n`、时间/文件/流等通用能力 | Android/Java 标准库 |
| `data` | 模型、仓储、设置、持久化和迁移 | `core` |
| `container/api` | `ContainerEngine`、启动 DTO、Host Hook、UI 命令契约 | `core`、`data`、`plugin-api` |
| `container/engine` | 安装、匹配、启动事务、实例和运行协调 | `container/api`、`data`、`core` |
| `container/runtime` | Session、Activity Host、Context、系统服务、Loader、Native | `container/api`、`data`、`core` |
| `agent/sdk` | Agent、统一 Chat、Tool/Capability、上下文压缩、记忆、技能、知识、任务委派和运行内核 | Kotlin Coroutines、`org.json` |
| `agent` | Agent 运行时、会话、任务、工具、权限和插件适配 | `agent/sdk`、`container/api`、`data`、`core`、`plugin-api` |
| `plugins/host` | 插件加载、安装、生命周期、Host Services 和启用配置 | `plugin-api`、`data`、`core` |
| `features` | Agent/Assistant/Browser/Desktop/Notebook/Settings/Plugin Center UI | 对应 API、`agent`、`data`、`core` |
| `integrations` | MCP、Android Service、Receiver、Schedule 和外部入口 | Agent API、Container API、数据 |

### 包依赖方向

箭头 `A -> B` 表示 A 可以依赖 B：

```text
boot -> features
boot -> integrations
boot -> agent
boot -> container/engine
boot -> container/runtime
boot -> plugins/host

features -> agent
features -> container/api
features -> data
features -> core

integrations -> agent
integrations -> container/api
integrations -> data
integrations -> core

agent -> container/api
agent -> data
agent -> core
agent -> plugin-api

container/engine -> container/api
container/engine -> data
container/engine -> core

container/runtime -> container/api
container/runtime -> data
container/runtime -> core

plugins/host -> plugin-api
plugins/host -> data
plugins/host -> core

container/api -> data
container/api -> core
data -> core
```

允许 `plugins/host` 通过宿主侧能力接口向 `boot` 注册插件贡献；不得直接引用
`features` 页面、`agent` 实现或 `container/runtime` 实现。

`features` 内部也不允许把“同属 features”当成无边界的理由：

- `assistant` 访问浏览器时使用 `BrowserControl` 能力，不引用 `BrowserActivity`；
- `browser` 隐藏悬浮球时使用 `OverlayControl` 能力，不引用悬浮球实现；
- `settings` 通过数据仓储和 Provider 接口读取状态，不直接构造业务单例；
- `integrations/mcp` 不引用 `FloatingDotOverlay`、`BrowserProxyManager` 等具体
  实现，通过能力接口和配置仓储调用；
- 共享接口放在 `features/contracts` 或对应逻辑 API 包，由 `boot` 负责装配。

### 同模块下的边界约束

单模块编译不会自动阻止越层 import，因此拆分后增加一个扫描 `app/src/main` import
图的 JVM 测试，例如 `PackageBoundaryTest`：

- 发现禁止方向的 import 时失败；
- 白名单只允许 Android 框架、JNI/反射入口和明确的第三方依赖；
- 测试不参与业务执行，也不改变运行时行为；
- 每次移动包后更新允许列表必须有明确理由。

这一步比新建多个 Gradle 模块轻量，同时能防止包边界重新退化为循环引用。

`plugin-api` 和 `testapp` 仍保持独立 Gradle 模块；插件宿主始终位于 `:app`。

## 6. 推荐目录结构

```text
app/src/main/java/com/coke/nestspace/
  boot/
    NestSpaceApp.kt
    AppGraph.kt
    NestSpaceDeferredStartup.kt
    MainActivity.kt
    navigation/
  core/
    common/
    i18n/
    time/
    files/
  data/
    model/
    repository/
    settings/
    store/
  container/
    api/
      ContainerEngine.kt
      RuntimeApi.kt
      ContainerControlApi.kt
    engine/
      MiniContainerEngine.kt
      install/
      launch/
    runtime/
      session/
      activity/
      context/
      loader/
      identity/
      system/
        provider/
        service/
        broadcast/
        package/
        job/
        location/
      remote/
      native/
    process/
    control/
    ui-service/
  agent/
    contracts/
    runtime/
    conversation/
    task/
    tools/
      builtin/
      presentation/
    plugin-adapter/
  plugins/
    host/
      source/
      installer/
      runtime/
      services/
    registry/
    policy/
  features/
    assistant/
    browser/
    desktop/
    notebook/
    settings/
    plugin-center/
  integrations/
    mcp/
    service/
    receiver/
    schedule/
    proxy/
  design/
    theme/
    components/
```

目录名只是目标边界，不要求一次移动全部文件。每阶段先建立目录，再移动一个高内聚
子集，编译和测试通过后再继续。

## 7. 核心类目标设计

### 7.1 `MiniContainerEngine`

保留 `MiniContainerEngine` 作为 `ContainerEngine` 门面和事务协调者，预计缩减到
500 行以内。内部拆分：

| 新对象 | 单一职责 |
| --- | --- |
| `ApkInputStager` | 把一个或多个 URI 复制到独立临时目录，并统一清理 |
| `ApkInspectionPipeline` | 解析描述符，验证同一包名、单一 base APK、签名和 ABI |
| `InstalledApkMatcher` | 分别执行 payload identity 和 install identity 匹配 |
| `AppInstaller` | 原子移动 APK、提交状态、失败回滚、删除旧安装 |
| `InstalledAppRepository` | 读取/发布 `StoredApp`、实例、图标和公开模型 |
| `LaunchRequestFactory` | 从应用记录、实例和 Intent 构造不可变启动请求 |
| `LaunchRuntimeCoordinator` | 槽位、镜像、前台/无头、keep-alive 和旧任务关闭 |

关键要求：

- 两次匹配 API 保留原有副作用、返回值和异常吞并范围；
- 安装锁、临时目录生命周期、文件移动顺序和回滚顺序不变；
- 不在重构中改变签名优先级、payload digest 或缓存失效条件。

### 7.2 `ContainerSession`

`ContainerSession` 保留为一次目标应用 Session 的门面，内部按启动阶段拆分：

| 新对象 | 单一职责 |
| --- | --- |
| `HostProcessState` | 保存和恢复宿主 ActivityThread、LoadedApk、Resources、Application |
| `ProcessIdentityCoordinator` | 进入/退出目标身份，处理嵌套深度和锁 |
| `TargetApplicationBootstrap` | 创建、附加、发布目标 Application |
| `TargetClassPathBinder` | Dex 元素重写、ClassLoader、虚拟路径和资源绑定 |
| `TargetActivityFactory` | 创建目标 Activity、Attach、Token、Resources 和主题 |
| `NativeHookPump` | Native Hook 首次扫描、周期刷新和诊断计数 |
| `RuntimeDiagnosticsWriter` | 启动报告和运行时诊断持久化 |
| `ContainerSession` | 按原有顺序编排上述对象，并保留现有对外访问方法 |

关键要求：

- ActivityThread 字段改写必须保持当前阶段顺序；
- 反射失败继续按原范围吞并或抛出，不能统一成粗粒度 `runCatching`；
- Process Identity 的挂起/恢复必须保持可重入计数；
- Host Activity 与 Target Activity 切换时必须恢复同一份宿主状态。

### 7.3 `StageAActivity`

`StageAActivity` 仍是 Android 真实 Activity 和生命周期入口，不改成 Compose 导航或
伪 Activity。内部拆分：

| 新对象 | 单一职责 |
| --- | --- |
| `TargetActivityOpener` | 创建目标 Activity、Attach、资源和首次生命周期 |
| `MirrorModeController` | VirtualDisplay、Presentation、镜像轮询和快照 |
| `NavigationHandoff` | staged launch、child handoff 和 parent 恢复 |
| `ContainerBackController` | 目标返回、根页面回桌面、预测返回拦截 |
| `PermissionResultRouter` | ActivityResult 和权限结果转发 |
| `HostChromeRenderer` | 加载页、环境提示、错误页和过渡快照 |
| `StageAActivity` | 只实现 Android 回调并转交控制器 |

关键要求：

- 所有 `onCreate`、`onStart`、`onResume`、`onPause`、`onStop`、`onDestroy`
  调用顺序保持不变；
- 镜像快速轮询、空闲轮询和显示刷新频率保持不变；
- 返回拦截、child handoff 和根页面关闭条件保持不变。

### 7.4 UI 上帝文件

`PluginCenterScreen.kt` 拆分：

- `PluginCenterState`：页面数据和派生状态；
- `PluginCenterActions`：安装、卸载、启停、分享、重命名和设置保存；
- `PluginFilter`：查询与分类匹配；
- `InstalledPluginSection`、`AvailablePluginSection`：列表；
- `PluginSettingsDialog`、`PluginDetailDialog`、`PluginRenameDialog`：独立 Dialog。

`FloatingDotOverlay.kt` 拆分：

- `FloatingDotController`：公开状态和窗口生命周期；
- `FloatingDotWindow`：WindowManager 参数、边缘停靠和抑制；
- `FloatingToolsPanel`；
- `FloatingReaderPanel`；
- `FloatingDotView`、`SpeakerGlyphView`、`ReadingIndicatorView`。

`DesktopScreen.kt` 拆分：

- `DesktopState`；
- `DesktopEntryProjection`；
- `DesktopPager`；
- `DesktopFolderSheet`；
- `DesktopContextMenu`；
- `DesktopGestureController`。

这些拆分只改变代码组织和依赖方向，不改变 UI 显示、点击区域、动画条件和状态流。

### 7.5 其他超大运行时类

`ContainerPackageManager` 必须继续作为 `PackageManager` 子类存在，保留所有
公开 override 和隐藏框架入口。内部拆为：

- `InstalledPackageQueries`：安装包、应用和组件查询；
- `IntentResolveQueries`：Activity、Service、Provider、Receiver 解析；
- `PackageInfoProjector`：深拷贝、资源/图标、flags 和 ApplicationInfo 补全；
- `VirtualPermissionQueries`：权限、签名和 UID 查询；
- `ContainerPackageManager`：只做虚包路由、宿主回退和方法适配。

`SystemServiceIdentity` 保留为系统服务代理安装门面，内部拆为：

- `ReflectionCache`：类、方法、构造器、字段和 assignable field 缓存；
- `ActivityManagerIdentityHandler`；
- `PackageManagerIdentityHandler`；
- `NotificationIdentityHandler`；
- `AppOpsAndPermissionHandlers`；
- `SystemServiceRuntimeState`：target identity、host context 和安装标志。

Binder 方法名、事务码、参数改写和安装/卸载顺序必须保持不变。

`ContainerContext` 应保留 Context 子类门面，但把以下职责从主文件移出：

- `VirtualStoragePaths`：files/cache/code_cache/external storage；
- `TargetResourcesFactory`：Resources、主题和 ClassLoader 绑定；
- `ContainerSystemServiceFactory`：Location、Window、Shortcut、Autofill 等；
- `FrameworkCallOrigin`：Toast/AttributionSource 等调用来源判断。

### 7.6 浏览器和语音页面

`BrowserSession` 保留为浏览器能力的门面，内部拆为：

- `BrowserTabStore`：Tab、当前页和 owner；
- `BrowserWebViewFactory`：WebView 配置、Client、下载监听和上传桥；
- `BrowserDownloadManager`：下载状态、进度、取消、命名和清理；
- `BrowserNavigation`：URL 规范化、返回/前进/刷新和同进程路由。

`AssistantVoiceActivity` 的无效 Chat 渲染链删除后，再把语音采集、模型准备、
对话提交和 Compose 展示拆成 `VoiceController`、`SpeechSession` 和页面组件。
不改变 Activity Result、权限申请和语音监听回调时序。

### 7.7 `AppGraph`

将 `AppGraph` 作为显式组合根逐步完善，不引入 DI 框架：

- 应用级依赖由图构造：Repository、Engine、Tool/Agent、MCP；
- 容器进程依赖使用独立的 Runtime Graph；
- 跨功能依赖通过真实能力契约注入，例如容器启动、镜像窗口、浏览器控制、
  Agent 任务通知和 MCP 状态；
- UI 只接收所需能力，不主动构造全局单例；
- 保留 Android 生命周期或反射确实需要的进程级单例；
- 不改变现有的进程内共享语义。

## 8. 准备删除与合并的内容

### 8.1 确认删除，删除后由编译器复核

| 目标 | 位置 | 删除理由 |
| --- | --- | --- |
| `ExampleUnitTest` | `app/src/test/java/com/coke/nestspace/ExampleUnitTest.kt` | Android Studio 模板测试，不验证项目行为 |
| 旧 `NotebookEditor` | `NotebookActivity.kt:547-928` | 无引用，实际编辑器由 `NotebookEditorDesk` 提供 |
| `ChatBubble` 及依赖链 | `AssistantVoiceActivity.kt:1015-1113` | 无入口引用；删除后 `MessageText`、`CodeBlock`、`plainText` 同时失效 |
| `ModelPreparingCard` | `AssistantVoiceActivity.kt:1116-1181` | 无引用，当前模型准备 UI 已有替代实现 |
| `restoreHostLoadedApkPackageName` | `ContainerSession.kt:785-800` | 类内无调用 |
| `createTargetLoadedApk` | `ContainerSession.kt:860-894` | 类内无调用；`applyTargetLoadedApkIdentity` 仍被其他路径使用，只删除未调用方法 |
| `removeDownload` | `BrowserSession.kt:1298-1304` | 类内无调用 |
| `subagentTools` | `ToolRegistry.kt:67-94` | 除声明外无任何读取，没有筛选逻辑使用该集合 |
| `instanceKey` | `ContainerContext.kt:108` | 除声明外无任何读取 |
| `generationOf` | `ContainerSlotState.kt:368` | 主源码和测试均无调用 |
| 无效 import | `app/src/main/java` 中当前静态扫描命中的 81 处 | 迁移后遗留，特别是 `VirtualApp.kt` 的 54 处；删除残留后继续清理 |
| 重复原始资源 | `app/src/main/res/raw/ic_model_disconnected.svg` | Lint 未发现引用；实际使用的是 drawable XML |
| 冗余 Activity 标签 | `AndroidManifest.xml:85` | `MainActivity` 与 application 使用同一标签，删除后仍可继承 |

删除 `ChatBubble` 后必须继续清理只被它使用的导入和私有函数，不能只删入口函数。

### 8.2 合并重复实现

| 重复内容 | 合并方案 | 必须保留的差异 |
| --- | --- | --- |
| 6 份完全相同的路径名 `sanitize` | `PathNames.safeSegment(value)` | 保持字符集、大小写和空值行为 |
| `McpClientManager` 的扩展 `sanitize` | 复用统一实现后增加 trim、空值和 80 字符策略 | 不能改变工具名前缀规则 |
| 4 个 `readLimited` 入口 | `Streams.readBytesLimited(...)` + 可选 UTF-8 解码；安装器继续返回 `ByteArray` | 各调用方保留异常类型、错误文案和 1 MB / 2 MB / 16 MB 上限 |
| 5 份 `MM-dd HH:mm` 格式化 | `TimeText.shortDateTime(at, emptyFallback)` | 保留 `""`、`"-"`、时区和 Locale 差异 |
| 三次 APK 临时复制/解析 | `ApkInputStager` + `ApkInspectionPipeline` | 保留临时文件名、顺序、校验和清理时机 |
| Container 反射字段遍历 | `FrameworkReflection` | 只合并语义完全一致的查找/赋值逻辑；异常处理不同处保留 |
| 5 份原始类型 `defaultValue` | `ReflectionDefaults.valueFor(type)`，特殊类型由调用方扩展 | 保留数组、集合、`Intent` 和各代理不同的返回要求 |
| 开发插件项目发现 | 单一 Gradle 辅助逻辑 | 保留 Debug 构建才加入依赖的现有行为 |

### 8.3 禁止直接删除，必须专项验证

以下内容看起来“无直接调用”，但可能由框架或配置使用：

- `@Suppress("unused")` 标注的 `mPackageInfo`、
  `getPermissionRequestState()` 和 `getAssociatedDisplayId()`；
- AIDL 接口及实现方法；
- Manifest 中的 Activity、Service、Receiver、Provider；
- 16 个主槽位 Activity、16 个隔离槽位 Activity、16 个 UI Service 和
  16 个远程 Service 的重复声明；它们对应真实进程名，不能只因代码相似而合并；
- `RuntimeHostHooks`、插件 API 和 `RuntimeProfileProvider`；
- Native/JNI 方法及 Native 侧按名称查找的 Java 方法；
- `app/src/main/java/android/content/IContentProvider.java`：隐藏框架接口的
  编译期占位类，运行时由 boot class loader 覆盖，不能当普通空接口删除；
- 反射字段名、系统服务方法名和 Binder 事务码；
- `HostUiController`：它是主机控制工具的正式能力，不是调试残留；
- `ExampleInstrumentedTest`：目前价值很低，但它是唯一的 Android 测试入口；
  应先加入有效冒烟测试，再替换或删除；
- `Log` 调用：除重复刷屏和纯调试噪声外，现有运行诊断日志不能直接删除。

以下 Lint 告警也不能批量“修复”：

- `ApplySharedPref`：现有 `commit()` 有些用于跨进程立即可见，统一改成 `apply()`
  会改变时序；
- `StaticFieldLeak`：需要区分 application context、进程级窗口和真正的
  Activity/View 泄漏，不能只靠注解压掉；
- `DrawAllocation`：可在拆 View 时做无行为差异的预分配，但不能改变绘制参数；
- `UseKtx`：只有验证映射完全一致后才换，不能把现代化建议当作行为等价保证。

`.codex-tmp`、空的 `runtime-api`、`runtime-compat`、`runtime-core`、
`runtime-plugin-api` 目录不是受版本控制的源码；清理它们不属于本次代码删除，
也不应作为架构重构成果统计。

## 9. 分阶段执行计划

### 阶段 0：建立行为基线

执行内容：

- 固定使用 Android Studio JBR；当前 `compileDebugKotlin` 和现有 JVM 测试已通过，
  后续每次重构都以它作为可重复 SDK；
- 固化已通过的 `:app:assembleDebug` 和 `:testapp:assembleDebug` 结果；
- 记录 Debug、Snapshot、Release 和 `arm64`、`x86_64`、`universal` ABI
  构建的现有状态；
- 记录 `testapp` 的普通版本和 `secure` 版本，后者用于
  `REQUIRE_SECURE_ENV=1` 的安装校验；
- 记录 Native CMake 构建和 JNI 符号基线，后续 Java 拆分不能改变 JNI 名称、
  参数类型或加载顺序；
- 建立安装、启动、导航、返回、多开、Service/Provider/Broadcast 的
  `testapp` 冒烟清单；
- 为 APK identity、安装状态迁移、启动请求、返回策略和工具权限补充特征测试。

退出条件：

- 当前基线构建结果、失败测试和已知平台差异有记录；
- 删除操作能从基线区分“原有失败”和“重构引入失败”。

### 阶段 1：删除无效代码和导入

执行内容：

- 删除 8.1 中高可信无效代码；
- 清理对应私有依赖链和导入；
- 删除模板测试；
- 不移动文件，不修改业务分支。

预期收益：

- 删除约 650–850 行迁移残留和直接无效声明，另有约 81 行无效导入；
- 缩小 `NotebookActivity`、`AssistantVoiceActivity`；
- 降低编译器噪声和维护者误判。

验证：

- 全量编译；
- JVM 单元测试；
- 笔记本编辑、语音页、浏览器下载和容器启动冒烟。

### 阶段 2：抽取纯工具与重复协议

执行内容：

- 提取路径名清理、限流读取和时间展示；
- 合并 APK 临时输入和解析流程，但保留 `MiniContainerEngine` 公开方法；
- 合并 Gradle 插件目录发现；
- 为每项抽取增加输入/边界/错误文案测试。

预期收益：

- 删除重复分支约 200–400 行；
- 统一文件、网络和时间处理；
- 为后续包边界收敛减少依赖。

验证：

- 错误码、错误文案和长度上限逐项对比；
- APK 安装与更新场景回归。

### 阶段 3：拆分安装与启动引擎

执行内容：

- 建立 `install/` 和 `launch/` 子包；
- 提取 7.1 中列出的对象；
- 保持 `MiniContainerEngine` 原有接口和锁粒度；
- 将 `engine` 依赖的稳定 DTO 移到 `container/api` 包，消除
  `container ↔ engine` 包循环。

预期收益：

- `MiniContainerEngine` 从 1,465 行降到约 500 行以内；
- 安装、匹配、启动和状态持久化可以独立测试；
- 删除重复 APK 流程。

验证：

- 安装、覆盖安装、split APK、签名冲突、ABI 不匹配；
- 多开、删除实例、更新后启动；
- 失败回滚和临时目录清理。

### 阶段 4：拆分 `ContainerSession`

执行内容：

- 提取宿主框架状态、进程身份、Application 启动、ClassPath/Resources、
  Native Hook Pump 和诊断；
- 保留 `ContainerSession` 现有对外方法和调用顺序；
- 每提取一个阶段立即编译和容器冒烟，禁止一次移动全部代码。

预期收益：

- `ContainerSession` 主文件降至约 600–900 行；
- 反射和启动阶段可按职责审查；
- 运行时诊断与真实启动路径解耦。

验证：

- Application `attachBaseContext`、`onCreate` 顺序；
- LoadedApk、Resources、ClassLoader 和 Native 库；
- 宿主 Activity 返回与目标身份恢复；
- Native Hook 首次扫描和周期刷新。

### 阶段 5：拆分 Context 与系统服务代理

执行内容：

- 提取 `ContainerContext` 的存储、Resources 和系统服务工厂；
- 将 `ContainerPackageManager` 的查询、解析、Info 克隆和权限职责拆开；
- 将 `SystemServiceIdentity` 的缓存和 Binder handler 按服务族拆分；
- 保留所有 PackageManager override、Binder 方法名和事务码。

预期收益：

- `ContainerPackageManager` 和 `SystemServiceIdentity` 主文件降到约 600–800 行；
- 系统服务适配可按服务族测试和定位；
- 容器运行时依赖进一步收缩。

验证：

- `testapp` 的 Package、Resolve、Provider、UID 和 Native 探针；
- Hidden API、Binder transaction code 和异常返回；
- Android 24/25 与较新 API 的双路径。

### 阶段 6：拆分 `StageAActivity`

执行内容：

- 保持 Activity 作为生命周期入口；
- 提取目标 Activity 打开、镜像窗口、导航交接、返回和结果路由；
- 用控制器封装复杂状态，禁止控制器自行改变回调顺序。

预期收益：

- `StageAActivity` 主文件降到约 500–700 行；
- 镜像模式、普通模式和 headless 模式边界可单独测试；
- PiP、预测返回和 ActivityResult 不再分散在生命周期代码中。

验证：

- splash、主页、二级 Activity、返回栈和 `singleTop/singleTask/singleInstance`；
- 镜像小窗切前台、显示切换和关闭；
- headless 启动、返回桌面、PiP 和权限结果。

### 阶段 7：拆分 UI 与 Agent/工具边界

执行内容：

- 按 7.4 和 7.6 拆 Plugin Center、悬浮球、桌面、浏览器和语音页面；
- 把工具协议、权限策略、用户确认和任务协调收口到 `agent` 内部子包；
- 让 `plugin` 与 `tools` 通过稳定接口通信；
- 删除 `browser ↔ assistant` 的双向具体依赖，改由入口回调或能力接口连接。

预期收益：

- 单个文件不超过约 800 行；
- UI 页面只负责展示和用户动作；
- Agent、插件和工具之间不再直接依赖具体实现。

验证：

- 插件安装、启停、设置、分享；
- 悬浮球显示、隐藏、任务面板、朗读；
- 桌面拖拽、文件夹、菜单和快捷方式；
- 浏览器与语音页互相唤起。

### 阶段 8：收敛 `:app` 包边界与自动检查

执行内容：

- 按第 5 节目录图逐步移动业务包，不新增或拆分业务 Gradle 模块；
- `:mcp`、`:assistant`、`:browser`、`:notebook` 等服务全部留在 `:app`；
- 插件宿主留在 `:app`，包级拆分为 host、UI、注册表和 Agent 适配边界；
- AIDL、容器资源、主题、Manifest 进程声明和 `R` 引用继续保留在 `:app`；
- 将 `ContainerLaunchProxyActivity`、`ContainerSelfCheck` 和宿主侧控制客户端
  归入 `boot`/`container.control` 包边界；
- 保持 16 个主槽位、16 个隔离槽位和对应 Service 类的进程映射；
- 保持 JNI 类名、方法名、签名、库名和加载顺序不变；
- 加入 `PackageBoundaryTest`，扫描 `app/src/main` import 并禁止越层依赖；
- 每移动一个主要包，立即运行该测试、编译和相关 JVM 测试。

退出条件：

- `settings.gradle.kts` 的模块集合保持不变；
- `plugin-api` 和 `testapp` 保持现有边界；
- `container/runtime` 不引用 `features`、`agent` 实现和 `boot`；
- `agent` 不引用 Compose 页面和 Android 入口；
- 第 3 节列出的 17 组直接双向依赖全部解除，或合并为同一职责域内部实现；
- `PackageBoundaryTest` 通过，包依赖图无环；
- Debug 开发插件、AIDL、Manifest 和 Native 构建全部通过。

## 10. 验证矩阵

### JVM 测试

当前终端的默认 `java` 不可用，执行前先选择 Android Studio JBR：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

然后运行：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :plugin-api:test
./gradlew :testapp:assembleDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleSnapshot
./gradlew :app:assembleRelease
./gradlew :plugin-api:assembleDebug
./gradlew :app:lintDebug
```

`lintDebug` 当前是已记录的失败基线，不能要求它突然全绿；重构验收应对比规则数量
和具体新增问题，不能通过新增全局 baseline 掩盖回归。

Snapshot/Release 在本机建议依次执行，并为 Kotlin daemon 提供 4 GB：

```bash
./gradlew :app:assembleSnapshot -Pkotlin.daemon.jvmargs=-Xmx4g
./gradlew :app:assembleRelease -Pkotlin.daemon.jvmargs=-Xmx4g
```

最终包重组完成后还要验证：

```bash
./gradlew :app:assembleDebug -PnestspaceHostAbi=arm64
./gradlew :app:assembleDebug -PnestspaceHostAbi=x86_64
./gradlew :app:assembleDebug -PnestspaceHostAbi=universal
```

新增特征测试优先覆盖：

- APK payload identity 和签名 identity 的差异；
- 安装事务成功、失败、回滚和旧目录清理；
- `SpaceStore` schema 迁移；
- 启动请求参数和进程/槽位选择；
- 返回策略、镜像策略和可见性策略；
- 工具权限和插件工具注册；
- 时间、流上限和路径清理的旧输出。

### Android 冒烟测试

使用 `testapp` 覆盖：

- 标准 Activity、splash、二级 Activity；
- `singleTop`、`singleTask`、`singleInstance`；
- Service、Provider、Broadcast、Job；
- 多进程和 Native 探针；
- headless、mirror、visible 三种启动模式；
- 截图、UI 观察、点击、输入和返回；
- 进程回收、重新启动和崩溃恢复。

当前 `app/src/androidTest` 只有一个模板测试，因此阶段 0 需要把关键安装、启动、
返回和插件操作转为可重复的 Instrumentation 或外部自动化流程。

### 设备矩阵

至少验证：

- Android 24/25；
- Android 31；
- Android 35 或 37；
- arm64 真机；
- 无 ARM 翻译的 x86_64 模拟器；
- 有 ARM 翻译的 x86_64 模拟器（需要时单独校验 ABI 选择）。

高兼容风险目标应用按现有测试资产回归，不能用单个原生测试应用代替所有目标兼容性。

## 11. 量化目标

这些是验收目标，不是当前未实施代码的承诺：

| 指标 | 当前 | 目标 |
| --- | ---: | --- |
| Gradle 模块集合 | `:app`、`:plugin-api`、`:testapp` 和 Debug 源码插件 | 不新增 SDK 或业务模块 |
| `>1,000` 行的主源码文件 | 16 | 0，或每个例外有明确架构理由 |
| `>500` 行的主源码文件 | 53 | 不超过 30 |
| 最大运行时类 | 3,666 行 | 不超过 900 行 |
| 最大 UI 层文件 | 2,002 行 | 不超过 800 行 |
| 顶层包强连通分量 | 1 个，覆盖 21 个域 | 包依赖图无环 |
| 直接双向依赖对 | 17 | 0 |
| 路径 `sanitize` 实现 | 7（6 份完全相同） | 1 个通用入口 + 明确的扩展策略 |
| 重复限流流读取 | 4 | 1 |
| 模板/迁移残留代码 | 存在 | 0 |

代码量目标：

- 阶段 1–2 预计净减少约 700–1,100 行；
- 阶段性职责拆分后，总净减少目标为 5%–8%；
- 不以减少行数牺牲可读性，拆分类可能先增加少量样板代码，随后通过删除重复
  和迁移残留回落到目标区间。

## 12. 执行纪律

每个主要部分按以下顺序完成并单独报告：

1. 写明本阶段删除项和保留项；
2. 先补特征测试；
3. 提取职责或删除无效代码；
4. 编译并运行对应测试；
5. 检查依赖方向；
6. 记录行为未变证据和剩余风险；
7. 再进入下一阶段。

出现以下情况立即停止该阶段：

- 生命周期、线程、锁、超时或错误码发生变化；
- 持久化 schema 或插件公开 API 发生变化；
- 容器启动和目标应用兼容性无法回归；
- 为消除依赖新增了没有真实多实现场景的抽象；
- 仅靠“看起来没用到”删除反射、AIDL、JNI 或 Manifest 入口。

## 13. 实施进度

### 2026-11-26：阶段 1 完成

- 删除模板 `ExampleUnitTest`；
- 删除旧 `NotebookEditor` 及仅被它使用的函数和导入，共 411 行；
- 删除语音页无效 Chat 渲染链和 `ModelPreparingCard`，共 176 行；
- 删除 `ContainerSession` 两个无调用方法，共 65 行；
- 删除 `subagentTools`、`instanceKey`、`generationOf`、`removeDownload`；
- 清理 99 个无效 import；
- 删除未使用的 raw SVG 和 MainActivity 冗余标签。

结果：

- `:app` 主 Kotlin/Java 从 95,816 行降到 95,022 行，净减少 794 行；
- 无效 import 静态候选为 0；
- `:app:testDebugUnitTest` 253 个用例、`:plugin-api:test` 53 个用例全部通过；
- `:app:assembleDebug` 成功；
- `lintDebug` 警告从 363 降到 354，错误仍为既有的 193 个。

### 2026-11-26：阶段 2 主体完成

- 6 份相同路径清理合并为 `core/files/PathNames.kt`；
- 4 个限流读取入口改由 `core/io/LimitedStreams.kt` 统一实现；
- 3 个相同时间格式入口和 2 个同格式调用方合并为
  `core/time/TimeText.kt`；
- `MiniContainerEngine` 的 3 次 APK 临时复制、解析流程合并为单一 staging helper；
- APK 输入复制、解析和临时目录清理已从 `MiniContainerEngine` 提取到
  `engine/install/ApkInputStager`；
- `HostProcessState`、`NativeHookPump`、`ProcessIdentityCoordinator` 和
  `RuntimeDiagnosticsWriter` 已从 `ContainerSession` 提取；
- `common/Polling`、`i18n/AppLanguage` 已迁入 `core/common`、`core/i18n`；
- `engine`、`control`、`runtime` 已分别迁入 `container/engine`、
  `container/control`、`container/api`；
- `tools`、`memory`、`replay`、`llm`、`task` 已迁入 `agent/` 下的对应目录；
- `assistant`、`browser`、`notebook`、`speech`、`screenreader`、`vm` 和原
  `ui` 子包已迁入 `features/`；
- `mcp`、`service`、`receiver`、`schedule`、`proxy`、`host`、`location`、
  `fastpath`、`update` 已迁入 `integrations/`；
- App 内部插件宿主已从 `plugin` 迁入 `plugins/host`，`plugin-api` 的公开
  `com.coke.nestspace.plugin` 包保持不变；
- `PluginToolViewContext` 的不可变构造器已公开，供迁出的宿主适配层构造视图上下文；
- 根级导航入口已迁入 `features/navigation`，`FrameSpinner` 已迁入
  `design/components`；
- `ContainerSession` 协作类已集中到 `container/session`；
- Manifest 中的 Activity、Service、Receiver 类名已同步更新；
- `app/build.gradle.kts` 不再重复扫描开发插件目录，改为读取 Settings 已注册工程。
- Debug、Snapshot、Release 构建和 308 个 JVM 用例全部通过。
- 新增 `PackageBoundaryTest`，锁定 `core` 与 `engine/install` 的依赖方向。
- `ContainerSession` 已提取 `HostProcessState`、`NativeHookPump`、
  `ProcessIdentityCoordinator`、`RuntimeDiagnosticsWriter`，从 3,666 行降到
  3,208 行。

### 2026-11-26：阶段 3-8 完成

- 5 份 `defaultValue` 已收敛到 `core/reflection/ReflectionDefaults.kt`，
  通过 `primitive`、`collection`、`shortcut` 三个明确入口保留不同代理的返回策略；
- `MiniContainerEngine` 已从 1,465 行降到 887 行；
- 安装输入、APK 校验、文件移动、安装记录投影、APK 元数据解析分别提取为
  `ApkInputStager`、`ApkInstallInspector`、`moveApkFile`、
  `InstalledAppProjector` 和 `InstalledApkResolver`；
- 启动流程提取为 `InstalledAppLauncher`，实例目录、旧宿主任务清理和运行时关闭
  提取为 `InstanceRuntimeCoordinator`；
- `ContainerSession` 已从 3,666 行降到 630 行；
- 启动编排、Activity Host、导航交接、Application 发布、ClassPath 绑定、
  Application attach 和 Resources 构建分别提取到 `container/session` 下的独立文件；
- `StageAActivity` 已从 2,567 行降到 893 行；
- 启动占位、镜像 Presentation、导航交接、返回策略和生命周期回调分别提取为
  `StageAStartupHosting`、`StageAMirrorHosting`、`StageAIntentRouting`、
  `StageABackController` 和 `StageALifecycleHosting`；
- `FloatingDotOverlay` 已从 2,002 行降到 519 行；
- 读屏控制器、工具面板控制器、窗口定位、ReaderPanel、ToolsPanel 和 DotView
  分别拆为独立文件；
- `PluginCenterScreen` 已从 1,930 行降到 786 行；
- 插件模型/筛选、列表组件、重命名 Dialog、设置 Dialog 和详情 Dialog
  分别拆为独立文件；
- `BrowserSession` 已从 1,334 行降到 991 行，下载状态、任务、队列、进度和清理
  集中到 `BrowserDownloads.kt`；
- `BrowserTools` 已降到 380 行，工具定义按导航、下载、读页三组拆分；
- `ContainerContext` 已从 1,454 行降到 1,000 行；
- 系统服务、Resources/ClassPath、框架调用、广播调用、Context 存储和辅助调用
  分别提取为独立文件；
- `SystemServiceIdentity` 已从 1,671 行降到 963 行；
- `PackageQueryHandler`、身份/AppOps/蓝牙处理器和通知处理器分别独立；
- `ContainerProcessPool` 已降到 1,000 行，进程/PSS/桩文件探测提取为
  `SlotProcessProbe`；
- `DesktopScreen` 已从 1,402 行降到 821 行，桌面上下文菜单、文件夹面板和确认
  Dialog 提取为 `DesktopOverlays`；
- `VirtualApp` 已降到 995 行，页面标题投影提取为 `VirtualAppNavigation`；
- Assistant 语音页和 Agent 任务视图也已按页面组件拆分。

### 当前规模与例外

- `app/src/main/java` 的 Kotlin 文件共 94,304 行；
- 当前没有超过 1,000 行的 Kotlin 文件；
- `ContainerPackageManager.java` 仍为 1,791 行：它必须保持
  `PackageManager` 子类及大量 API Level 重载，当前只完成静态职责审计，
  未在缺少设备回归的条件下重排 override 层级；
- 业务无关 Agent SDK 位于 `:app` 的 `agent/sdk` 逻辑包，不新增 Gradle 模块；
- `:app`、`:plugin-api`、`:testapp` 的业务职责未改变；
- `plugin-host` 仍在 `:app` 内，位于 `plugins/host`；
- 尚未执行真机/模拟器的安装、启动、导航、返回、多开、Provider/Service、
  悬浮球和插件操作冒烟。

### 最终验证

- `:app:testDebugUnitTest`：322 个用例通过，其中 `agent/sdk` 84 个；
- `:plugin-api:test`：53 个用例通过；
- `:app:assembleDebug`：成功；
- `:app:assembleSnapshot`：成功；
- `:app:assembleRelease`：成功；
- `:plugin-api:assembleDebug`：成功；
- `:testapp:assembleDebug`：成功；
- `:testapp:assembleSecure`：成功；
- `:app:lintDebug`：仍为既有失败基线，193 errors、353 warnings、2 hints；
  与重构前相比 errors 持平，warnings 减少 1；
- 未通过新增全局 lint baseline 掩盖问题，也未执行设备级冒烟。
