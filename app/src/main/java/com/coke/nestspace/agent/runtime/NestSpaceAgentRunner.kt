package com.coke.nestspace.agent.runtime

import android.content.Context
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.LlmAgentRuntime
import com.coke.nestspace.agent.definitions.NestSpaceAgents
import com.coke.nestspace.agent.sdk.AgentKey
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import com.coke.nestspace.agent.tools.ToolRegistry
import com.coke.nestspace.core.i18n.AppLanguage

internal object NestSpaceAgentRunner {
    const val LOCAL_OWNER = "local-llm"

    fun runtime(
        registry: ToolRegistry,
        agentKey: AgentKey
    ): LlmAgentRuntime = LlmAgentRuntime(
        registry = registry,
        definition = NestSpaceAgents.require(agentKey)
    )

    fun startWorker(
        context: Context,
        registry: ToolRegistry,
        ownerId: String,
        goal: String,
        extraContext: String,
        displayMode: ContainerDisplayMode,
        displayTitle: String,
        source: String = "assistant"
    ): AssistantTaskCoordinator.EnqueueResult =
        AssistantTaskCoordinator.enqueue(
            context = context,
            registry = registry,
            ownerId = ownerId,
            goal = goal,
            extraContext = extraContext,
            displayMode = displayMode,
            source = source,
            displayTitle = displayTitle,
            agentKey = NestSpaceAgents.workerKey
        )

    fun startScreenReader(
        context: Context,
        registry: ToolRegistry,
        goal: String,
        extraContext: String = "",
        displayMode: ContainerDisplayMode = ContainerDisplayMode.VISIBLE
    ): AssistantTaskCoordinator.EnqueueResult =
        AssistantTaskCoordinator.enqueue(
            context = context,
            registry = registry,
            ownerId = LOCAL_OWNER,
            goal = goal,
            extraContext = extraContext,
            displayMode = displayMode,
            source = "screen_reader",
            displayTitle = AppLanguage.tr("屏幕解读", "Screen reading"),
            agentKey = NestSpaceAgents.screenReaderKey
        )
}
