package com.coke.nestspace.agent.sdk.task

import com.coke.nestspace.agent.sdk.AgentKey
import com.coke.nestspace.agent.sdk.AgentRunContext

enum class AgentTaskState(val api: String) {
    QUEUED("queued"),
    PREPARING("preparing"),
    RUNNING("running"),
    WAITING_USER("waiting_user"),
    PAUSED("paused"),
    CANCELLING("cancelling"),
    FINALIZING("finalizing"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

data class AgentTaskRequest(
    val agentKey: AgentKey,
    val goal: String,
    val context: AgentRunContext,
    val extraContext: String = "",
    val title: String? = null,
    val requestId: String? = null,
    val source: String? = null
) {
    init {
        require(goal.isNotBlank()) { "Task goal must not be blank" }
    }
}

data class AgentTaskSnapshot(
    val id: String,
    val agentKey: AgentKey? = null,
    val title: String,
    val goal: String = title,
    val state: AgentTaskState,
    val queuePosition: Int = -1,
    val progress: String = "",
    val stateRevision: Long = 0L,
    val progressRevision: Long = 0L,
    val idleMilliseconds: Long = 0L,
    val result: String? = null,
    val error: String? = null,
    val uiState: String? = null,
    val primaryAction: String? = null,
    val canCancel: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

sealed interface AgentTaskStartResult {
    data class Started(val task: AgentTaskSnapshot) : AgentTaskStartResult

    data class Rejected(
        val code: String,
        val message: String
    ) : AgentTaskStartResult
}

interface AgentTaskController {
    suspend fun start(request: AgentTaskRequest): AgentTaskStartResult

    suspend fun list(
        ownerId: String? = null,
        source: String? = null
    ): List<AgentTaskSnapshot>

    suspend fun status(
        taskId: String,
        ownerId: String? = null
    ): AgentTaskSnapshot?

    suspend fun supplement(
        taskId: String,
        text: String,
        ownerId: String? = null
    ): Boolean

    suspend fun pause(
        taskId: String,
        ownerId: String? = null
    ): AgentTaskSnapshot?

    suspend fun resume(
        taskId: String,
        ownerId: String? = null
    ): AgentTaskSnapshot?

    suspend fun cancel(
        taskId: String,
        ownerId: String? = null,
        reason: String = "cancelled"
    ): AgentTaskSnapshot?

    suspend fun cancelQueued(
        ownerId: String? = null,
        reason: String = "queued tasks cancelled"
    ): Int
}
