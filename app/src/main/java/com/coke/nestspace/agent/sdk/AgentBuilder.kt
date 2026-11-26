package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentConfirmationHook
import com.coke.nestspace.agent.sdk.hook.AgentRunHook
import com.coke.nestspace.agent.sdk.hook.AgentToolExecutionHook
import java.util.UUID

/**
 * Minimal assembly facade for one runnable Agent.
 *
 * Providers, stores and host tools are still supplied by the consumer, but
 * catalogs and runtimes are assembled by the SDK.
 */
class AgentBuilder {
    private var agentKey: AgentKey? = null
    private var displayName = ""
    private var lane = AgentLane("default")
    private var instructions = AgentInstructionProvider.Empty
    private var toolPolicy = AgentToolPolicy()
    private var chatService: AgentChatService? = null
    private var chatOptions = AgentChatOptions()
    private var maxToolRounds = 8
    private var contextBudgetTokens: Int? = null
    private var maxParallelTools = 1
    private var runTimeoutMillis: Long? = null
    private var customToolExecutor: AgentToolExecutor? = null
    private var confirmationHook: AgentConfirmationHook? = null
    private val toolHooks = mutableListOf<AgentToolExecutionHook>()
    private val runHooks = mutableListOf<AgentRunHook>()
    private var capabilityPolicy = AgentCapabilityPolicy()
    private val tools = mutableListOf<AgentTool>()
    private val capabilities = mutableListOf<AgentCapability>()

    fun key(value: String) {
        agentKey = AgentKey(value)
    }

    fun displayName(value: String) {
        displayName = value
    }

    fun lane(value: String) {
        lane = AgentLane(value)
    }

    fun instructions(value: String) {
        instructions = AgentInstructionProvider { value }
    }

    fun instructions(provider: AgentInstructionProvider) {
        instructions = provider
    }

    fun toolPolicy(policy: AgentToolPolicy) {
        toolPolicy = policy
    }

    fun enabledTools(names: Set<String>) {
        toolPolicy = toolPolicy.copy(allowed = names)
    }

    fun disabledTools(names: Set<String>) {
        toolPolicy = toolPolicy.copy(denied = names)
    }

    fun features(config: AgentFeatureConfig) {
        toolPolicy = AgentToolPolicy(
            allowed = config.allowedTools,
            denied = config.deniedTools
        )
        capabilityPolicy = AgentCapabilityPolicy(
            allowed = config.allowedCapabilities,
            denied = config.deniedCapabilities
        )
    }

    fun tool(value: AgentTool) {
        tools += value
    }

    fun tools(values: Iterable<AgentTool>) {
        tools += values
    }

    fun capability(value: AgentCapability) {
        capabilities += value
    }

    fun capabilityPolicy(policy: AgentCapabilityPolicy) {
        capabilityPolicy = policy
    }

    fun enabledCapabilities(ids: Set<String>) {
        capabilityPolicy = capabilityPolicy.copy(allowed = ids)
    }

    fun disabledCapabilities(ids: Set<String>) {
        capabilityPolicy = capabilityPolicy.copy(denied = ids)
    }

    fun chatService(value: AgentChatService) {
        chatService = value
    }

    fun providerId(value: String) {
        chatOptions = chatOptions.copy(providerId = value)
    }

    fun model(value: String) {
        chatOptions = chatOptions.copy(model = value)
    }

    fun maxOutputTokens(value: Int) {
        chatOptions = chatOptions.copy(maxOutputTokens = value)
    }

    fun temperature(value: Double) {
        chatOptions = chatOptions.copy(temperature = value)
    }

    fun reasoningEffort(value: AgentReasoningEffort) {
        chatOptions = chatOptions.copy(reasoningEffort = value)
    }

    fun purpose(value: String) {
        chatOptions = chatOptions.copy(purpose = value)
    }

    fun attribute(key: String, value: String) {
        chatOptions = chatOptions.copy(
            attributes = chatOptions.attributes + (key to value)
        )
    }

    fun maxToolRounds(value: Int) {
        maxToolRounds = value
    }

    fun contextBudgetTokens(value: Int) {
        contextBudgetTokens = value
    }

    fun maxParallelTools(value: Int) {
        maxParallelTools = value
    }

    fun runTimeoutMillis(value: Long) {
        runTimeoutMillis = value
    }

    fun toolExecutor(value: AgentToolExecutor) {
        customToolExecutor = value
    }

    fun confirmationHook(value: AgentConfirmationHook) {
        confirmationHook = value
    }

    fun toolHook(value: AgentToolExecutionHook) {
        toolHooks += value
    }

    fun runHook(value: AgentRunHook) {
        runHooks += value
    }

    fun config(document: AgentConfigDocument) {
        key(document.key)
        displayName = document.displayName.orEmpty()
        lane(document.lane)
        instructions = document.instructions?.let { instruction ->
            AgentInstructionProvider { instruction }
        } ?: AgentInstructionProvider.Empty
        features(document.features)
        maxToolRounds(document.runtime.maxToolRounds)
        contextBudgetTokens = document.runtime.contextBudgetTokens
        maxParallelTools(document.runtime.maxParallelTools)
        runTimeoutMillis = document.runtime.runTimeoutMillis
        chatOptions = AgentChatOptions(
            providerId = document.chat.providerId,
            model = document.chat.model,
            maxOutputTokens = document.chat.maxOutputTokens,
            temperature = document.chat.temperature,
            reasoningEffort = document.chat.reasoningEffort,
            purpose = document.chat.purpose,
            attributes = document.chat.attributes
        )
    }

    fun build(): RunnableAgent {
        val key = requireNotNull(agentKey) { "Agent key is required" }
        val service = requireNotNull(chatService) {
            "AgentChatService is required"
        }
        val definition = AgentDefinition(
            key = key,
            lane = lane,
            displayName = displayName.ifBlank { key.value },
            toolPolicy = toolPolicy,
            instructions = instructions
        )
        val capabilityCatalog = AgentCapabilityCatalog(
            capabilities,
            capabilityPolicy
        )
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition)),
            toolCatalog = AgentToolCatalog(tools),
            chatService = service,
            config = AgentRuntimeConfig(
                maxToolRounds = maxToolRounds,
                contextBudgetTokens = contextBudgetTokens,
                maxParallelTools = maxParallelTools,
                runTimeoutMillis = runTimeoutMillis
            ),
            capabilities = capabilityCatalog,
            toolExecutor = customToolExecutor ?: AgentToolExecutor(toolHooks),
            runHooks = runHooks
        )
        return RunnableAgent(
            definition = definition,
            runtime = runtime,
            chatOptions = chatOptions,
            confirmationHook = confirmationHook
        )
    }
}

class RunnableAgent internal constructor(
    val definition: AgentDefinition,
    private val runtime: AgentRuntime,
    private val chatOptions: AgentChatOptions,
    private val confirmationHook: AgentConfirmationHook?
) {
    fun start(
        input: String,
        context: AgentRunContext = AgentRunContext(
            sessionId = UUID.randomUUID().toString()
        ),
        history: List<AgentChatMessage> = emptyList(),
        events: AgentEventSink = AgentEventSink {}
    ): AgentRunHandle {
        val runContext = context.copy(
            confirmationHook = context.confirmationHook
                ?: confirmationHook
        )
        return runtime.start(
            request = AgentRunRequest(
                agentKey = definition.key,
                input = input,
                context = runContext,
                history = history,
                chatOptions = chatOptions
            ),
            events = events
        )
    }

    suspend fun run(
        input: String,
        context: AgentRunContext = AgentRunContext(
            sessionId = UUID.randomUUID().toString()
        ),
        history: List<AgentChatMessage> = emptyList(),
        events: AgentEventSink = AgentEventSink {}
    ): AgentRunResult = start(input, context, history, events).await()

    fun session(
        context: AgentRunContext = AgentRunContext(
            sessionId = UUID.randomUUID().toString()
        ),
        historyStore: AgentHistoryStore = InMemoryAgentHistoryStore(),
        runStore: AgentRunStore? = null
    ): AgentSession = AgentSession(
        agent = this,
        context = context,
        historyStore = historyStore,
        runStore = runStore
    )
}

fun agentSdk(configure: AgentBuilder.() -> Unit): RunnableAgent =
    AgentBuilder().apply(configure).build()
