package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentConfirmationHook
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AgentRunContext(
    val sessionId: String,
    val userId: String? = null,
    val ownerId: String? = null,
    val parentTaskId: String? = null,
    val displayMode: String? = null,
    val invocationSource: String? = null,
    val userUtterances: List<String> = emptyList(),
    val capabilityObserver: ((String) -> Unit)? = null,
    val confirmationHook: AgentConfirmationHook? = null,
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
    }
}

data class AgentRunRequest(
    val agentKey: AgentKey,
    val input: String,
    val context: AgentRunContext,
    val history: List<AgentChatMessage> = emptyList(),
    val chatOptions: AgentChatOptions = AgentChatOptions(),
    val runId: String = UUID.randomUUID().toString()
) {
    init {
        require(input.isNotBlank()) { "Agent input must not be blank" }
        require(runId.isNotBlank()) { "Run id must not be blank" }
    }
}

data class AgentFailure(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
    val kind: AgentFailureKind = AgentFailureKind.UNKNOWN
) {
    init {
        require(code.isNotBlank()) { "Failure code must not be blank" }
        require(message.isNotBlank()) { "Failure message must not be blank" }
    }
}

sealed interface AgentEvent {
    val runId: String

    data class Started(override val runId: String) : AgentEvent

    data class TextDelta(
        override val runId: String,
        val text: String
    ) : AgentEvent

    data class ToolStarted(
        override val runId: String,
        val callId: String,
        val toolName: String,
        val inputJson: String
    ) : AgentEvent

    data class ToolCompleted(
        override val runId: String,
        val callId: String,
        val toolName: String,
        val success: Boolean,
        val output: String,
        val errorCode: String? = null
    ) : AgentEvent

    data class Completed(
        override val runId: String,
        val output: String
    ) : AgentEvent

    data class Failed(
        override val runId: String,
        val failure: AgentFailure
    ) : AgentEvent

    data class Cancelled(
        override val runId: String,
        val reason: String
    ) : AgentEvent
}

fun interface AgentEventSink {
    fun emit(event: AgentEvent)
}

/**
 * Supplies messages that arrived while a run is active. The runtime merges
 * consecutive user messages so provider role alternation stays valid.
 */
fun interface AgentMessageSource {
    suspend fun take(): List<AgentChatMessage>
}

data class AgentRunResult(
    val output: String,
    val messages: List<AgentChatMessage>,
    val events: List<AgentEventRecord>,
    val failure: AgentFailure? = null,
    val attachments: List<AgentToolAttachment> = emptyList()
) {
    val isSuccess: Boolean get() = failure == null
}

data class AgentRuntimeConfig(
    val maxToolRounds: Int = 8,
    val contextBudgetTokens: Int? = null,
    val maxParallelTools: Int = 1,
    val runTimeoutMillis: Long? = null
) {
    init {
        require(maxToolRounds > 0) { "maxToolRounds must be greater than zero" }
        require(contextBudgetTokens == null || contextBudgetTokens > 0) {
            "contextBudgetTokens must be greater than zero"
        }
        require(maxParallelTools > 0) {
            "maxParallelTools must be greater than zero"
        }
        require(runTimeoutMillis == null || runTimeoutMillis > 0) {
            "runTimeoutMillis must be greater than zero"
        }
    }
}

interface AgentRuntime {
    fun start(
        request: AgentRunRequest,
        events: AgentEventSink = AgentEventSink {}
    ): AgentRunHandle

    suspend fun run(
        request: AgentRunRequest,
        events: AgentEventSink = AgentEventSink {}
    ): AgentRunResult {
        val handle = start(request, events)
        return try {
            handle.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                handle.cancelAndJoin(cancelled.message ?: "cancelled")
            }
            throw cancelled
        }
    }
}
