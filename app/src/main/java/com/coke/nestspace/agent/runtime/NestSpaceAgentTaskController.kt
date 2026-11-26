package com.coke.nestspace.agent.runtime

import android.content.Context
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.definitions.NestSpaceAgents
import com.coke.nestspace.agent.sdk.task.AgentTaskController
import com.coke.nestspace.agent.sdk.task.AgentTaskRequest
import com.coke.nestspace.agent.sdk.task.AgentTaskSnapshot
import com.coke.nestspace.agent.sdk.task.AgentTaskStartResult
import com.coke.nestspace.agent.sdk.task.AgentTaskState
import com.coke.nestspace.agent.task.OperationTaskRegistry
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import com.coke.nestspace.agent.tools.ToolRegistry

internal fun NestSpaceAgentRunner.taskController(
    context: Context,
    registry: ToolRegistry
): AgentTaskController = object : AgentTaskController {
    private val app = context.applicationContext

    override suspend fun start(request: AgentTaskRequest): AgentTaskStartResult {
        val ownerId = request.context.ownerId
            ?: request.context.userId
            ?: LOCAL_OWNER
        val displayMode = ContainerDisplayMode.parse(
            request.context.displayMode,
            ContainerDisplayMode.AUTO
        )
        return when (
            val result = AssistantTaskCoordinator.enqueue(
                context = app,
                registry = registry,
                ownerId = ownerId,
                goal = request.goal,
                extraContext = request.extraContext,
                displayMode = displayMode,
                requestId = request.requestId,
                source = request.source ?: "assistant",
                displayTitle = request.title,
                agentKey = request.agentKey
            )
        ) {
            is AssistantTaskCoordinator.EnqueueResult.Error ->
                AgentTaskStartResult.Rejected(result.code, result.message)
            is AssistantTaskCoordinator.EnqueueResult.Queued ->
                AgentTaskStartResult.Started(result.task.toSdk())
        }
    }

    override suspend fun list(
        ownerId: String?,
        source: String?
    ): List<AgentTaskSnapshot> = AssistantTaskCoordinator
        .list(ownerId, source)
        .map { it.toSdk() }

    override suspend fun status(
        taskId: String,
        ownerId: String?
    ): AgentTaskSnapshot? = AssistantTaskCoordinator
        .get(taskId, ownerId)
        ?.toSdk()

    override suspend fun supplement(
        taskId: String,
        text: String,
        ownerId: String?
    ): Boolean = AssistantTaskCoordinator.supplement(taskId, text, ownerId)

    override suspend fun pause(
        taskId: String,
        ownerId: String?
    ): AgentTaskSnapshot? = AssistantTaskCoordinator
        .pause(taskId, ownerId)
        ?.toSdk()

    override suspend fun resume(
        taskId: String,
        ownerId: String?
    ): AgentTaskSnapshot? = AssistantTaskCoordinator
        .resume(app, registry, taskId, ownerId)
        ?.toSdk()

    override suspend fun cancel(
        taskId: String,
        ownerId: String?,
        reason: String
    ): AgentTaskSnapshot? = AssistantTaskCoordinator
        .cancel(taskId, ownerId, reason)
        ?.toSdk()

    override suspend fun cancelQueued(
        ownerId: String?,
        reason: String
    ): Int = AssistantTaskCoordinator.cancelQueued(ownerId, reason)
}

internal fun AssistantTaskCoordinator.TaskView.toSdk(): AgentTaskSnapshot = AgentTaskSnapshot(
    id = id,
    agentKey = if (source == "screen_reader") {
        NestSpaceAgents.screenReaderKey
    } else {
        NestSpaceAgents.workerKey
    },
    title = goal,
    goal = goal,
    state = state.toSdk(),
    queuePosition = queuePosition,
    progress = progress,
    stateRevision = stateRevision,
    progressRevision = progressRevision,
    idleMilliseconds = idleMilliseconds,
    result = result,
    error = error,
    createdAt = createdAt,
    updatedAt = updatedAt,
    uiState = uiState.api,
    primaryAction = primaryAction.api,
    canCancel = canCancel
)

private fun OperationTaskRegistry.State.toSdk(): AgentTaskState =
    AgentTaskState.valueOf(name)
