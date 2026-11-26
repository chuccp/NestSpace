package com.coke.nestspace.agent.memory

import android.content.Context
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.llm.ChatServices
import com.coke.nestspace.agent.llm.ChatUsageSink
import com.coke.nestspace.agent.llm.toAgentReasoningEffort
import com.coke.nestspace.agent.sdk.AgentChatOptions
import com.coke.nestspace.agent.sdk.memory.MemoryExtractionCapability
import com.coke.nestspace.agent.sdk.memory.MemoryItem
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort

/**
 * App adapter for the SDK's optional post-turn memory extraction.
 */
internal object MemoryExtractor {
    fun mightContainMemory(text: String): Boolean =
        MemoryExtractionCapability.mightContainMemory(text)

    suspend fun extract(
        context: Context,
        config: LlmConfig,
        utterances: List<String>,
        contextSummary: String? = null
    ): List<MemoryItem> {
        val app = context.applicationContext
        val capability = MemoryExtractionCapability(
            chatService = ChatServices.create(
                config,
                ChatUsageSink { usage, purpose ->
                    TokenUsageStore.get(app).record(config, usage, kind = purpose)
                }
            ),
            store = MemoryStore(app),
            chatOptions = AgentChatOptions(
                model = config.model,
                maxOutputTokens = 500,
                temperature = 0.1,
                reasoningEffort = ReasoningEffort.OFF.toAgentReasoningEffort(),
                purpose = "memory"
            ),
            requiresReview = {
                ToolPermissionSettings.requiresReviewForLearning(app)
            }
        )
        val added = capability.extract(
            utterances = utterances,
            contextSummary = contextSummary
        )
        if (added.isNotEmpty()) AppSettingsEvents.notifyChanged()
        return added
    }
}
