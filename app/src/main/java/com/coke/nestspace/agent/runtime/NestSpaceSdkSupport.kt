package com.coke.nestspace.agent.runtime

import com.coke.nestspace.agent.AgentToolSettings
import com.coke.nestspace.agent.sdk.AgentChatInput
import com.coke.nestspace.agent.sdk.AgentChatMessage
import com.coke.nestspace.agent.sdk.AgentChatResponse
import com.coke.nestspace.agent.sdk.AgentChatService
import com.coke.nestspace.agent.sdk.AgentDefinition
import com.coke.nestspace.agent.sdk.AgentHistoryStore
import com.coke.nestspace.agent.tools.ToolRegistry
import com.coke.nestspace.agent.tools.ToolSpec

internal class ResilientAgentChatService(
    private val delegate: AgentChatService
) : AgentChatService {
    override val supportsVision: Boolean
        get() = delegate.supportsVision

    override fun supportsVision(providerId: String?): Boolean =
        delegate.supportsVision(providerId)

    override suspend fun chat(request: AgentChatInput): AgentChatResponse {
        val response = delegate.chat(request)
        return if (needsRetry(response)) {
            delegate.chat(retryRequest(request))
        } else {
            response
        }
    }

    override suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse {
        val response = delegate.streamChat(request, onTextDelta)
        return if (needsRetry(response)) {
            delegate.chat(retryRequest(request))
        } else {
            response
        }
    }

    private fun needsRetry(response: AgentChatResponse): Boolean =
        response.text.isBlank() &&
            response.toolUses.isEmpty() &&
            response.stopReason?.lowercase() in setOf("length", "max_tokens")

    private fun retryRequest(request: AgentChatInput): AgentChatInput =
        request.copy(
            maxTokens = maxOf(request.maxTokens, 8_192).coerceAtMost(32_768),
            purpose = "retry"
        )
}

internal class MutableAgentHistoryStore(
    initial: List<AgentChatMessage>
) : AgentHistoryStore {
    private var messages = initial.toList()

    override suspend fun load(sessionId: String): List<AgentChatMessage> =
        messages

    override suspend fun save(
        sessionId: String,
        messages: List<AgentChatMessage>
    ) {
        this.messages = messages.toList()
    }

    fun replace(messages: List<AgentChatMessage>) {
        this.messages = messages.toList()
    }
}

internal object AgentToolSettingsAccess {
    fun enabledSpecs(
        registry: ToolRegistry,
        definition: AgentDefinition
    ): List<ToolSpec> {
        val available = AgentToolSettings.availableToolSpecs(registry)
        val disabled = AgentToolSettings.disabledTools(registry.context)
        return AgentToolSettings
            .enabledToolSpecs(available, disabled)
            .filter { definition.toolPolicy.permits(it.name) }
    }
}
