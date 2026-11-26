package com.coke.nestspace.agent.sdk

import org.json.JSONObject

enum class AgentReasoningEffort(
    val reasoningReserveTokens: Int
) {
    OFF(0),
    LOW(4_096),
    MEDIUM(8_192),
    HIGH(16_384)
}

enum class AgentChatRole {
    USER,
    ASSISTANT
}

sealed class AgentChatBlock {
    data class Text(val text: String) : AgentChatBlock()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject
    ) : AgentChatBlock()

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean
    ) : AgentChatBlock()

    data class Image(
        val data: String,
        val mimeType: String = "image/png"
    ) : AgentChatBlock()

    data class Thinking(
        val thinking: String,
        val signature: String?
    ) : AgentChatBlock()

    data class RedactedThinking(val data: String) : AgentChatBlock()
}

data class AgentChatMessage(
    val role: AgentChatRole,
    val blocks: List<AgentChatBlock>
) {
    val text: String
        get() = blocks.filterIsInstance<AgentChatBlock.Text>()
            .joinToString(separator = "") { it.text }
}

data class AgentChatTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class AgentChatOptions(
    val providerId: String? = null,
    val model: String = "",
    val maxOutputTokens: Int = 2_048,
    val temperature: Double = 0.7,
    val reasoningEffort: AgentReasoningEffort = AgentReasoningEffort.MEDIUM,
    val purpose: String = "agent",
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(providerId == null || providerId.isNotBlank()) {
            "Provider id must not be blank"
        }
        require(maxOutputTokens > 0) {
            "maxOutputTokens must be greater than zero"
        }
        require(temperature.isFinite()) {
            "temperature must be finite"
        }
        require(purpose.isNotBlank()) { "Purpose must not be blank" }
    }

    fun toInput(
        system: String,
        messages: List<AgentChatMessage>,
        tools: List<AgentChatTool>
    ): AgentChatInput = AgentChatInput(
        model = model,
        system = system,
        messages = messages,
        tools = tools,
        maxTokens = maxOutputTokens,
        temperature = temperature,
        reasoningEffort = reasoningEffort,
        purpose = purpose,
        providerId = providerId,
        attributes = attributes
    )
}

/**
 * Unified, provider-neutral input for one model turn.
 */
data class AgentChatInput(
    val model: String,
    val system: String,
    val messages: List<AgentChatMessage>,
    val tools: List<AgentChatTool>,
    val maxTokens: Int,
    val temperature: Double,
    val reasoningEffort: AgentReasoningEffort = AgentReasoningEffort.MEDIUM,
    val purpose: String = "chat",
    val providerId: String? = null,
    val attributes: Map<String, String> = emptyMap()
)

data class AgentChatUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val estimated: Boolean = false
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

data class AgentChatResponse(
    val blocks: List<AgentChatBlock>,
    val stopReason: String? = null,
    val usage: AgentChatUsage? = null
) {
    init {
        val ids = blocks.filterIsInstance<AgentChatBlock.ToolUse>().map { it.id }
        require(ids.toSet().size == ids.size) {
            "Tool call ids must be unique within one model response"
        }
    }

    val text: String
        get() = blocks.filterIsInstance<AgentChatBlock.Text>()
            .joinToString(separator = "") { it.text }

    val toolUses: List<AgentChatBlock.ToolUse>
        get() = blocks.filterIsInstance<AgentChatBlock.ToolUse>()
}

fun interface AgentChatService {
    val supportsVision: Boolean get() = false

    fun supportsVision(providerId: String?): Boolean = supportsVision

    suspend fun chat(request: AgentChatInput): AgentChatResponse

    suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse {
        val response = chat(request)
        if (response.text.isNotEmpty()) onTextDelta(response.text)
        return response
    }
}
