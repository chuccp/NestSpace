package com.coke.nestspace.agent.memory

import android.content.Context
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.llm.ChatServices
import com.coke.nestspace.agent.llm.ChatUsageSink
import com.coke.nestspace.agent.llm.toAgentReasoningEffort
import com.coke.nestspace.agent.sdk.AgentChatOptions
import com.coke.nestspace.agent.sdk.skill.SkillDraftingCapability
import com.coke.nestspace.agent.sdk.skill.SkillItem
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort

/**
 * App adapter for the SDK's optional skill drafting pass.
 */
internal object SkillDrafter {
    const val MIN_TOOL_STEPS = 4

    private val IGNORED_TOOLS = setOf(
        "skill_list", "skill_read", "skill_save",
        "memory_search", "memory_save", "memory_forget",
        "system_status", "assistant_status",
        "ui_observe", "browser_status", "browser_tabs"
    )

    fun eligible(toolNames: List<String>): Boolean =
        toolNames.size >= MIN_TOOL_STEPS &&
            "skill_save" !in toolNames &&
            toolNames.any { it !in IGNORED_TOOLS }

    suspend fun draft(
        context: Context,
        config: LlmConfig,
        goal: String,
        answer: String,
        toolNames: List<String>,
        existing: List<SkillItem>
    ): SkillItem? {
        if (!config.isConfigured || !eligible(toolNames)) return null
        val app = context.applicationContext
        return SkillDraftingCapability(
            chatService = ChatServices.create(
                config,
                ChatUsageSink { usage, purpose ->
                    TokenUsageStore.get(app).record(config, usage, kind = purpose)
                }
            ),
            store = SkillStore(app),
            chatOptions = AgentChatOptions(
                model = config.model,
                maxOutputTokens = 1_600,
                temperature = 0.2,
                reasoningEffort = ReasoningEffort.OFF.toAgentReasoningEffort(),
                purpose = "skill"
            ),
            ignoredTools = IGNORED_TOOLS
        ).draft(
            goal = goal,
            answer = answer,
            toolNames = toolNames,
            existing = existing,
            minToolSteps = MIN_TOOL_STEPS
        )
    }
}
