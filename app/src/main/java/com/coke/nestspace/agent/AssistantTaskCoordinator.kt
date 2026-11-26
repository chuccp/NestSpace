package com.coke.nestspace.agent

import android.annotation.SuppressLint
import com.coke.nestspace.container.HeadlessMirrorWindow
import android.content.Context
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.agent.memory.MemoryStore
import com.coke.nestspace.agent.memory.SkillStore
import com.coke.nestspace.integrations.service.AssistantTaskForegroundService
import com.coke.nestspace.features.speech.AssistantSpeech
import com.coke.nestspace.features.speech.TtsSettings
import com.coke.nestspace.agent.task.OperationTaskRegistry
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import com.coke.nestspace.agent.tools.ToolRegistry
import com.coke.nestspace.agent.definitions.NestSpaceAgents
import com.coke.nestspace.agent.runtime.NestSpaceAgentRunner
import com.coke.nestspace.agent.sdk.AgentKey
import com.coke.nestspace.agent.sdk.AgentLane
import com.coke.nestspace.agent.sdk.AgentLaneScheduler
import com.coke.nestspace.agent.sdk.AgentRunContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide scheduler for independent assistant-worker and screen-reader agents.
 *
 * The OperationTaskRegistry remains the durable lifecycle authority. This
 * coordinator owns the process-local jobs, FIFO order per agent lane, pause
 * requests and instruction inbox. A screen-reader task can run concurrently
 * with one assistant Worker because they are peer agents.
 */
object AssistantTaskCoordinator {

    internal const val TASK_KIND = "assistant_worker"
    data class TaskView(
        val id: String,
        val goal: String,
        val source: String,
        val requestId: String?,
        val displayMode: ContainerDisplayMode,
        val state: OperationTaskRegistry.State,
        val uiState: AssistantTaskUiState,
        val primaryAction: AssistantTaskPrimaryAction,
        val canCancel: Boolean,
        val queuePosition: Int,
        val progress: String,
        val stateRevision: Long,
        val progressRevision: Long,
        val idleMilliseconds: Long,
        val result: String?,
        val error: String?,
        val createdAt: Long,
        val startedAt: Long,
        val finishedAt: Long,
        val updatedAt: Long
    )

    sealed class EnqueueResult {
        data class Queued(val task: TaskView) : EnqueueResult()
        data class Error(val code: String, val message: String) : EnqueueResult()
    }

    private data class WorkerTask(
        val id: String,
        val ownerId: String,
        val goal: String,
        val displayMode: ContainerDisplayMode,
        val config: LlmConfig,
        val source: String,
        val agentKey: AgentKey,
        val lane: AgentLane,
        var job: Job? = null,
        var pauseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
        var cancelReason: String = "",
        var lastProgress: String = "",
        var runCount: Int = 0,
        var runId: String = "",
        val instructions: ArrayDeque<String> = ArrayDeque()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val scheduler = AgentLaneScheduler()
    private val records = ConcurrentHashMap<String, WorkerTask>()
    @Volatile
    private var runtimeContext: Context? = null
    @Volatile
    private var runtimeRegistry: ToolRegistry? = null

    fun enqueue(
        context: Context,
        registry: ToolRegistry,
        ownerId: String,
        goal: String,
        extraContext: String,
        displayMode: ContainerDisplayMode,
        requestId: String? = null,
        source: String = "assistant",
        displayTitle: String? = null,
        agentKey: AgentKey = NestSpaceAgents.workerKey
    ): EnqueueResult {
        val cleanGoal = goal.trim()
        if (cleanGoal.isEmpty()) {
            return EnqueueResult.Error("INVALID_ARGUMENT", "goal is required")
        }
        val app = context.applicationContext
        runtimeContext = app
        runtimeRegistry = registry
        AssistantTaskForegroundService.ensureStarted(app)
        val state = LlmConfigStore(app).load()
        val config = state.configs.firstOrNull { it.id == state.activeId }
            ?: state.configs.firstOrNull()
            ?: return EnqueueResult.Error("LLM_NOT_CONFIGURED", "no model configured")
        if (!config.isConfigured) {
            return EnqueueResult.Error("LLM_NOT_CONFIGURED", "model is not configured")
        }
        val fullGoal = buildString {
            append(cleanGoal)
            if (extraContext.isNotBlank()) {
                append("\n\n补充上下文：\n").append(extraContext.trim())
            }
        }
        val operation = OperationTaskRegistry.start(
            ownerId = ownerId,
            kind = TASK_KIND,
            title = displayTitle?.trim()?.takeIf(String::isNotBlank)
                ?: cleanGoal.take(160),
            parentTaskId = null,
            requestId = requestId,
            requestHash = requestHash(fullGoal)
        )
        val snapshot = operation.task
            ?: return EnqueueResult.Error(
                operation.errorCode ?: "TASK_CREATE_FAILED",
                "Could not create assistant task"
            )
        if (operation.errorCode != null) {
            return EnqueueResult.Error(
                operation.errorCode,
                "request_id already belongs to a different task"
            )
        }
        if (!operation.created) {
            return view(snapshot.id)?.let { EnqueueResult.Queued(it) }
                ?: EnqueueResult.Error(
                    "TASK_CREATE_FAILED",
                    "Could not read the existing assistant task"
                )
        }
        // The worker is the primary execution loop. It inherits the user's
        // configured model budget instead of accepting a smaller per-task cap
        // from the coordinating agent.
        val workerSteps = config.maxToolRounds
            .coerceIn(LlmConfig.MIN_TOOL_ROUNDS, LlmConfig.MAX_TOOL_ROUNDS)
        val task = WorkerTask(
            id = snapshot.id,
            ownerId = ownerId,
            goal = fullGoal,
            displayMode = displayMode,
            config = config.copy(maxToolRounds = workerSteps),
            source = source,
            agentKey = agentKey,
            lane = NestSpaceAgents.require(agentKey).lane
        )
        records[task.id] = task
        OperationTaskRegistry.setCancelCallback(task.id) {
            requestCancellation(task.id)
        }
        synchronized(lock) {
            OperationTaskRegistry.transition(
                task.id,
                OperationTaskRegistry.State.QUEUED,
                if (task.agentKey == NestSpaceAgents.screenReaderKey) {
                    AppLanguage.tr("读屏 Agent 准备启动", "Preparing the screen-reader agent")
                } else {
                    AppLanguage.tr("等待前一个任务完成", "Waiting for the previous task")
                }
            )
            scheduler.enqueue(task.lane, task.id)
            startNextLocked(app, registry, task.lane)
        }
        return EnqueueResult.Queued(
            view(task.id) ?: return EnqueueResult.Error(
                "TASK_CREATE_FAILED",
                "Could not read the queued assistant task"
            )
        )
    }

    fun list(
        ownerId: String? = null,
        source: String? = null
    ): List<TaskView> {
        val owner = ownerId?.let(::ownerKey)
        return OperationTaskRegistry.tasksFlow.value
            .filter { it.kind == TASK_KIND && (owner == null || it.ownerId == owner) }
            .filter { snapshot ->
                source == null || inferredSource(snapshot) == source
            }
            .asSequence()
            .filter { it.parentTaskId == null }
            .sortedWith(
                compareByDescending<OperationTaskRegistry.Snapshot> {
                    it.state != OperationTaskRegistry.State.CANCELLING &&
                        !it.terminal
                }.thenBy { queueIndex(it.id) }
                    .thenByDescending { it.createdAt }
            )
            .mapNotNull { view(it.id) }
            .toList()
    }

    fun get(taskId: String, ownerId: String? = null): TaskView? {
        val task = OperationTaskRegistry.status(taskId) ?: return null
        if (task.kind != TASK_KIND) return null
        if (ownerId != null && task.ownerId != ownerKey(ownerId)) return null
        return view(taskId)
    }

    fun supplement(taskId: String, text: String, ownerId: String? = null): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        val task = records[taskId] ?: return false
        if (ownerId != null && task.ownerId != ownerKey(ownerId)) return false
        val snapshot = OperationTaskRegistry.status(taskId) ?: return false
        if (snapshot.terminal || snapshot.state == OperationTaskRegistry.State.CANCELLING) {
            return false
        }
        synchronized(lock) {
            task.instructions.addLast(value)
        }
        OperationTaskRegistry.progress(
            taskId,
            percent = null,
            message = AppLanguage.tr("收到补充，等待下一步处理", "Follow-up queued for the next step")
        )
        return true
    }

    fun pause(taskId: String, ownerId: String? = null): TaskView? {
        val task = records[taskId] ?: return null
        if (ownerId != null && task.ownerId != ownerKey(ownerId)) return null
        synchronized(lock) {
            val snapshot = OperationTaskRegistry.status(taskId) ?: return null
            if (snapshot.state != OperationTaskRegistry.State.RUNNING &&
                snapshot.state != OperationTaskRegistry.State.WAITING_USER
            ) {
                return view(taskId)
            }
            if (!isActiveLocked(task) || task.job == null) return view(taskId)
            task.pauseRequested = true
            OperationTaskRegistry.progress(
                taskId,
                percent = null,
                message = AppLanguage.tr("正在暂停，等待当前步骤结束", "Pausing at a safe step")
            )
            task.job?.cancel(CancellationException("assistant task paused"))
        }
        return view(taskId)
    }

    fun resume(
        context: Context,
        registry: ToolRegistry,
        taskId: String,
        ownerId: String? = null
    ): TaskView? {
        val task = records[taskId] ?: return null
        if (ownerId != null && task.ownerId != ownerKey(ownerId)) return null
        synchronized(lock) {
            val snapshot = OperationTaskRegistry.status(taskId) ?: return null
            if (snapshot.state != OperationTaskRegistry.State.PAUSED ||
                !isActiveLocked(task)
            ) {
                return view(taskId)
            }
            task.pauseRequested = false
            task.cancelRequested = false
            OperationTaskRegistry.transition(
                taskId,
                OperationTaskRegistry.State.RUNNING,
                AppLanguage.tr("继续执行任务", "Resuming task")
            )
            launchLocked(context.applicationContext, registry, task)
        }
        return view(taskId)
    }

    fun cancel(taskId: String, ownerId: String? = null, reason: String = "cancelled"): TaskView? {
        val snapshot = OperationTaskRegistry.status(taskId) ?: return null
        if (snapshot.kind != TASK_KIND) return null
        if (ownerId != null && snapshot.ownerId != ownerKey(ownerId)) return null
        val cancelled = OperationTaskRegistry.requestCancel(taskId, reason)
        return cancelled.task?.let { view(taskId) }
    }

    fun cancelQueued(ownerId: String? = null, reason: String = "queued tasks cancelled"): Int {
        val ids = synchronized(lock) {
            scheduler.queuedIds()
        }
        return ids.count { id ->
            val snapshot = OperationTaskRegistry.status(id) ?: return@count false
            val scoped = ownerId == null || snapshot.ownerId == ownerKey(ownerId)
            scoped && OperationTaskRegistry.requestCancel(id, reason).accepted
        }
    }

    private fun startNextLocked(
        context: Context,
        registry: ToolRegistry,
        lane: AgentLane
    ) {
        while (true) {
            val nextId = scheduler.takeNext(lane) ?: return
            val task = records[nextId] ?: continue
            val snapshot = OperationTaskRegistry.status(nextId)
            if (snapshot == null ||
                snapshot.terminal ||
                snapshot.state == OperationTaskRegistry.State.CANCELLING
            ) {
                records.remove(nextId)
                scheduler.clearActive(lane, nextId)
                continue
            }
            task.pauseRequested = false
            task.cancelRequested = false
            OperationTaskRegistry.transition(
                nextId,
                OperationTaskRegistry.State.RUNNING,
                if (task.agentKey == NestSpaceAgents.screenReaderKey) {
                    AppLanguage.tr("读屏 Agent 开始执行", "Screen-reader agent started")
                } else {
                    AppLanguage.tr("任务开始执行", "Task started")
                }
            )
            launchLocked(context, registry, task)
            return
        }
    }

    private fun launchLocked(context: Context, registry: ToolRegistry, task: WorkerTask) {
        task.runCount += 1
        task.runId = UUID.randomUUID().toString()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            var succeeded = false
            var terminalSnapshot: OperationTaskRegistry.Snapshot? = null
            try {
                val result = runWorker(context, registry, task)
                if (!task.pauseRequested && !task.cancelRequested) {
                    terminalSnapshot = OperationTaskRegistry.succeed(task.id, result)
                    succeeded = true
                }
            } catch (cancelled: CancellationException) {
                if (task.pauseRequested) {
                    withContext(NonCancellable) {
                        OperationTaskRegistry.transition(
                            task.id,
                            OperationTaskRegistry.State.PAUSED,
                            AppLanguage.tr(
                                "已暂停，等待用户继续",
                                "Paused; waiting for the user"
                            )
                        )
                    }
                } else {
                    withContext(NonCancellable) {
                        task.cancelRequested = true
                        terminalSnapshot = OperationTaskRegistry.markCancelled(
                            task.id,
                            task.cancelReason.ifBlank { "cancelled" }
                        )
                    }
                }
            } catch (error: Throwable) {
                if (!task.cancelRequested) {
                    terminalSnapshot = OperationTaskRegistry.fail(
                        task.id,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            } finally {
                task.job = null
                var closeMirror = false
                synchronized(lock) {
                    val current = terminalSnapshot ?: OperationTaskRegistry.status(task.id)
                    if (current?.state == OperationTaskRegistry.State.PAUSED) {
                        markActiveLocked(task)
                    } else {
                        if (current?.state == OperationTaskRegistry.State.CANCELLING) {
                            OperationTaskRegistry.markCancelled(
                                task.id,
                                task.cancelReason.ifBlank { "cancelled" }
                            )
                        }
                        clearActiveLocked(task)
                        startNextLocked(context, registry, task.lane)
                    }
                    closeMirror = scheduler.isEmpty()
                }
                (terminalSnapshot ?: OperationTaskRegistry.status(task.id))?.let {
                    publishWorkerResult(task, it)
                    AssistantTaskNotifications.notifyTerminal(context, it)
                    if (task.source == "assistant" &&
                        it.state == OperationTaskRegistry.State.SUCCEEDED
                    ) {
                        speakResult(context, it.result)
                    }
                }
                if (closeMirror) {
                    HeadlessMirrorWindow.scheduleAutoClose()
                }
            }
        }
        task.job = job
        job.start()
    }

    private fun isActiveLocked(task: WorkerTask): Boolean =
        scheduler.isActive(task.id)

    private fun markActiveLocked(task: WorkerTask) {
        scheduler.markActive(task.lane, task.id)
    }

    private fun clearActiveLocked(task: WorkerTask) {
        scheduler.clearActive(task.lane, task.id)
    }

    private fun speakResult(context: Context, result: String?) {
        val text = result?.trim().orEmpty()
        if (text.isEmpty()) return
        val config = TtsSettings.load(context)
        if (!config.enabled) return
        scope.launch {
            runCatching {
                AssistantSpeech.speak(
                    context = context,
                    text = text,
                    providerId = config.providerId,
                    interrupt = true
                )
            }
        }
    }

    private fun publishWorkerResult(
        task: WorkerTask,
        snapshot: OperationTaskRegistry.Snapshot
    ) {
        if (task.source != "assistant" || !snapshot.terminal) return
        val title = snapshot.title.take(160)
        val message = when (snapshot.state) {
            OperationTaskRegistry.State.SUCCEEDED -> buildString {
                append(AppLanguage.tr("后台任务完成：", "Background task finished: "))
                append(title)
                snapshot.result
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { append("\n\n").append(it) }
            }
            OperationTaskRegistry.State.CANCELLED ->
                AppLanguage.tr("后台任务已取消：$title", "Background task cancelled: $title")
            else -> AppLanguage.tr("后台任务没有完成：$title", "Background task failed: $title")
        }
        AssistantConversation.reportFromWorker(
            text = message,
            isError = snapshot.state == OperationTaskRegistry.State.FAILED
        )
    }

    @SuppressLint("StaticFieldLeak")
    private suspend fun runWorker(
        context: Context,
        registry: ToolRegistry,
        task: WorkerTask
    ): String {
        val app = context.applicationContext
        val definition = NestSpaceAgents.require(task.agentKey)
        val runtime = NestSpaceAgentRunner.runtime(registry, task.agentKey)
        val memoryContext = withContext(Dispatchers.IO) {
            runCatching { MemoryStore(app).contextFor(task.goal) }.getOrNull()
        }
        val skillIndex = withContext(Dispatchers.IO) {
            runCatching { SkillStore(app).enabledIndex() }.getOrNull()
        }
        val command = if (task.agentKey == NestSpaceAgents.screenReaderKey) {
            definition.instructions.instructions(
                AgentRunContext(
                    sessionId = "assistant-task-${task.id}",
                    userId = task.ownerId,
                    parentTaskId = task.id,
                    displayMode = task.displayMode.name
                )
            ) + task.goal
        } else if (task.runCount <= 1 && task.lastProgress.isBlank()) {
            task.goal
        } else {
            buildString {
                append(task.goal)
                append("\n\n")
                append(
                    AppLanguage.tr(
                        "这是暂停后的继续执行。先重新观察当前页面，不要假定旧页面仍存在。" +
                            "最近进度：",
                        "This continues a paused task. Re-observe the current screen and do not " +
                            "assume the old screen still exists. Latest progress:"
                    )
                )
                append("\n")
                append(task.lastProgress.ifBlank { "<none>" })
            }
        }
        val trace = runtime.run(
            config = task.config,
            command = command,
            sessionId = "assistant-task-${task.id}-${task.runId}",
            parentTaskId = task.id,
            displayMode = task.displayMode,
            memoryContext = memoryContext,
            skillIndex = skillIndex,
            onToolCall = { name, _ ->
                OperationTaskRegistry.progress(
                    task.id,
                    percent = null,
                    message = AppLanguage.tr("正在执行 $name", "Running $name")
                )
            },
            onMessage = { message ->
                if (message.role != AgentMessage.Role.USER) {
                    val summary = message.text.trim().take(800)
                    if (summary.isNotBlank()) {
                        task.lastProgress = summary
                        OperationTaskRegistry.progress(
                            task.id,
                            percent = null,
                            message = summary
                        )
                    }
                }
            },
            steeringProvider = {
                synchronized(lock) {
                    buildList {
                        while (task.instructions.isNotEmpty()) {
                            add(task.instructions.removeFirst())
                        }
                    }
                }
            }
        )
        trace.lastOrNull { it.role == AgentMessage.Role.ERROR }?.let {
            throw IllegalStateException(it.text)
        }
        return trace.lastOrNull { it.role == AgentMessage.Role.AGENT }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("assistant task returned no text")
    }

    private fun requestCancellation(taskId: String) {
        val task = records[taskId]
        if (task == null) {
            synchronized(lock) {
                scheduler.remove(taskId)
            }
            OperationTaskRegistry.markCancelled(taskId, "cancelled")
            runtimeContext?.let { app ->
                OperationTaskRegistry.status(taskId)?.let {
                    AssistantTaskNotifications.notifyTerminal(app, it)
                }
            }
            return
        }
        val snapshot = OperationTaskRegistry.status(taskId)
        task.cancelReason = snapshot?.error?.ifBlank { null } ?: "cancelled"
        task.pauseRequested = false
        task.cancelRequested = true
        synchronized(lock) {
            val removedQueued = scheduler.removeQueued(taskId)
            if (removedQueued) {
                OperationTaskRegistry.markCancelled(
                    taskId,
                    task.cancelReason.ifBlank { "cancelled before execution" }
                )
                runtimeContext?.let { app ->
                    OperationTaskRegistry.status(taskId)?.let {
                        AssistantTaskNotifications.notifyTerminal(app, it)
                    }
                }
                records.remove(taskId)
                val context = runtimeContext
                val registry = runtimeRegistry
                if (context != null && registry != null) {
                    startNextLocked(context, registry, task.lane)
                }
                return
            }
            val job = task.job
            if (job != null) {
                job.cancel(CancellationException("assistant task cancelled"))
            } else {
                OperationTaskRegistry.markCancelled(
                    taskId,
                    task.cancelReason.ifBlank { "cancelled" }
                )
                runtimeContext?.let { app ->
                    OperationTaskRegistry.status(taskId)?.let {
                        AssistantTaskNotifications.notifyTerminal(app, it)
                    }
                }
                clearActiveLocked(task)
                val context = runtimeContext
                val registry = runtimeRegistry
                if (context != null && registry != null) {
                    startNextLocked(context, registry, task.lane)
                }
            }
        }
    }

    private fun view(taskId: String): TaskView? {
        val snapshot = OperationTaskRegistry.status(taskId) ?: return null
        val controls = assistantTaskControls(
            snapshot.state,
            interruptible = records[taskId]?.job != null
        )
        return TaskView(
            id = snapshot.id,
            goal = snapshot.title,
            source = inferredSource(snapshot),
            requestId = snapshot.requestId,
            displayMode = records[taskId]?.displayMode ?: ContainerDisplayMode.BACKGROUND,
            state = snapshot.state,
            uiState = controls.uiState,
            primaryAction = controls.primaryAction,
            canCancel = controls.canCancel,
            queuePosition = queueIndex(snapshot.id),
            progress = snapshot.message,
            stateRevision = snapshot.stateRevision,
            progressRevision = snapshot.progressRevision,
            idleMilliseconds = snapshot.idleMilliseconds,
            result = snapshot.result,
            error = snapshot.error,
            createdAt = snapshot.createdAt,
            startedAt = snapshot.createdAt,
            finishedAt = snapshot.finishedAt,
            updatedAt = snapshot.updatedAt
        )
    }

    private fun queueIndex(taskId: String): Int = synchronized(lock) {
        scheduler.queuePosition(taskId)
    }

    private fun ownerKey(ownerId: String): String =
        ownerId

    private fun inferredSource(snapshot: OperationTaskRegistry.Snapshot): String =
        records[snapshot.id]?.source
            ?: if (snapshot.ownerId.startsWith("mcp:")) "mcp" else "assistant"

    private fun requestHash(goal: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(goal.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
