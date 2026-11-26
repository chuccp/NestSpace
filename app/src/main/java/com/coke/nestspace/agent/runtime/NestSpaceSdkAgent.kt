package com.coke.nestspace.agent.runtime

import com.coke.nestspace.agent.AppAgentConfirmationHook
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.agent.llm.ChatServices
import com.coke.nestspace.agent.llm.ChatUsageSink
import com.coke.nestspace.agent.llm.toAgentReasoningEffort
import com.coke.nestspace.agent.sdk.AgentAttachmentAudience
import com.coke.nestspace.agent.sdk.AgentCatalog
import com.coke.nestspace.agent.sdk.AgentChatBlock
import com.coke.nestspace.agent.sdk.AgentChatMessage
import com.coke.nestspace.agent.sdk.AgentChatOptions
import com.coke.nestspace.agent.sdk.AgentChatResponse
import com.coke.nestspace.agent.sdk.AgentChatRole
import com.coke.nestspace.agent.sdk.AgentChatService
import com.coke.nestspace.agent.sdk.AgentEvent
import com.coke.nestspace.agent.sdk.AgentEventSink
import com.coke.nestspace.agent.sdk.AgentFailure
import com.coke.nestspace.agent.sdk.AgentFailureKind
import com.coke.nestspace.agent.sdk.AgentInstructionProvider
import com.coke.nestspace.agent.sdk.AgentMessageSource
import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentRuntimeConfig
import com.coke.nestspace.agent.sdk.AgentSession
import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolAttachment
import com.coke.nestspace.agent.sdk.AgentToolCall
import com.coke.nestspace.agent.sdk.AgentToolCatalog
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolExecutor
import com.coke.nestspace.agent.sdk.AgentToolResult
import com.coke.nestspace.agent.sdk.ContextCompressor
import com.coke.nestspace.agent.sdk.DefaultAgentRuntime
import com.coke.nestspace.agent.sdk.RunnableAgent
import com.coke.nestspace.agent.sdk.RunMetrics
import com.coke.nestspace.agent.sdk.hook.AgentModelHookDecision
import com.coke.nestspace.agent.sdk.hook.AgentRunHook
import com.coke.nestspace.agent.sdk.hook.AgentToolExecutionHook
import com.coke.nestspace.agent.sdk.hook.AgentToolHookDecision
import com.coke.nestspace.agent.tools.Caller
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import com.coke.nestspace.agent.tools.ToolContext
import com.coke.nestspace.agent.tools.ToolRegistry
import com.coke.nestspace.agent.tools.ToolResult
import com.coke.nestspace.agent.tools.ToolSpec
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.plugins.host.AgentModelHookOutcome
import com.coke.nestspace.plugins.host.AgentPluginSupport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Adapts NestSpace's Android tools and plugin hooks to the business-neutral
 * Agent SDK runtime. This class contains integration policy only; the model
 * loop, tool pipeline, context compaction, events and cancellation live in the
 * SDK.
 */
internal class NestSpaceSdkAgent(
    private val registry: ToolRegistry,
    private val definition: com.coke.nestspace.agent.sdk.AgentDefinition,
    private val config: LlmConfig,
    private val systemPrompt: String,
    private val command: String,
    private val sessionId: String,
    private val parentTaskId: String?,
    private val displayMode: ContainerDisplayMode,
    private val history: List<AgentChatMessage>,
    private val allowContinuePrompt: Boolean,
    private val steeringProvider: (() -> List<String>)?,
    private val metrics: RunMetrics?,
    private val onToolCall: ((String, JSONObject) -> Unit)?,
    private val onMessage: ((AgentMessage) -> Unit)?,
    private val onTextDelta: ((String) -> Unit)?,
    private val onSkillUsed: ((String) -> Unit)?
) {
    private val trace = mutableListOf<AgentMessage>()
    private val toolOutcomes = ConcurrentHashMap<String, AgentToolOutcome>()
    private val userUtterances = mutableListOf(command)
    private val toolContext = ToolContext(
        sessionId = sessionId,
        caller = Caller.LOCAL_AGENT,
        spaceId = "virtual-1",
        parentTaskId = parentTaskId,
        displayMode = displayMode,
        userUtterances = userUtterances,
        onSkillUsed = onSkillUsed
    )
    private val toolSpecs = AgentToolSettingsAccess.enabledSpecs(
        registry,
        definition
    )
    private val chatTools = toolSpecs.map {
        com.coke.nestspace.agent.sdk.AgentChatTool(
            name = it.name,
            description = it.description,
            inputSchema = it.inputSchema
        )
    }
    private var lastResponseText = ""
    private var pluginAnswerChecked = false
    private var round = 0

    suspend fun run(): List<AgentMessage> {
        record(AgentMessage(AgentMessage.Role.USER, command))
        publishStarted()
        try {
            val service = ResilientAgentChatService(
                ChatServices.create(
                    config,
                    ChatUsageSink { usage, purpose ->
                        TokenUsageStore.get(registry.context)
                            .record(config, usage, kind = purpose)
                        metrics?.recordRequest(usage)
                    }
                )
            )
            val agentDefinition = sdkDefinition()
            val runtime = DefaultAgentRuntime(
                catalog = AgentCatalog(listOf(agentDefinition)),
                toolCatalog = AgentToolCatalog(agentTools(service)),
                chatService = service,
                config = AgentRuntimeConfig(
                    maxToolRounds = maxToolRounds(),
                    contextBudgetTokens = config.contextTokens,
                    maxParallelTools = 1
                ),
                toolExecutor = AgentToolExecutor(listOf(toolHook())),
                runHooks = listOf(runHook()),
                incomingMessages = steeringMessages()
            )
            executeTurns(runtime, service, agentDefinition)
            return trace
        } finally {
            AgentPluginSupport.publishEvent(
                "agent.run.completed",
                JSONObject()
                    .put("session_id", sessionId)
                    .put("messages", trace.size)
            )
            withContext(NonCancellable) {
                runCatching {
                    registry.execute("session_release", JSONObject(), toolContext)
                }
            }
        }
    }

    private suspend fun executeTurns(
        runtime: DefaultAgentRuntime,
        service: AgentChatService,
        agentDefinition: com.coke.nestspace.agent.sdk.AgentDefinition
    ) {
        val historyStore = MutableAgentHistoryStore(history)
        val chatOptions = AgentChatOptions(
            model = config.model,
            maxOutputTokens = config.maxTokens,
            temperature = config.temperature,
            reasoningEffort =
                config.reasoningEffort.toAgentReasoningEffort(),
            purpose = "agent"
        )
        val context = AgentRunContext(
            sessionId = sessionId,
            ownerId = sessionId,
            parentTaskId = parentTaskId,
            displayMode = displayMode.api,
            invocationSource = "assistant",
            userUtterances = userUtterances.toList(),
            confirmationHook = AppAgentConfirmationHook
        )
        val session = AgentSession(
            agent = RunnableAgent(
                definition = agentDefinition,
                runtime = runtime,
                chatOptions = chatOptions,
                confirmationHook = AppAgentConfirmationHook
            ),
            context = context,
            historyStore = historyStore
        )
        var nextInput = command
        while (true) {
            val result = session.run(nextInput, eventSink())

            if (result.isSuccess) {
                val resultHistory = if (result.output.isBlank()) {
                    val summary = summarizeEmptyResponse(
                        service,
                        result.messages
                    ) ?: lastResponseText.takeIf(String::isNotBlank)
                    val text = if (summary != null) {
                        emptySummaryPrefix() + summary
                    } else {
                        localEmptySummary()
                    }
                    record(AgentMessage(AgentMessage.Role.AGENT, text))
                    result.messages + assistantMessage(text)
                } else {
                    result.messages
                }
                val steering = steeringProvider?.invoke().orEmpty()
                if (steering.isEmpty()) return
                steering.forEach { text ->
                    record(AgentMessage(AgentMessage.Role.USER, text))
                    userUtterances += text
                }
                nextInput = steering.first()
                historyStore.replace(
                    resultHistory + steering.drop(1).map(::userMessage)
                )
                continue
            }

            if (result.failure?.code == "MAX_TOOL_ROUNDS") {
                val summary = summarizeAfterLimit(
                    service,
                    result.messages
                ) ?: lastResponseText.takeIf(String::isNotBlank)
                val text = if (summary != null) {
                    limitSummaryPrefix(maxToolRounds()) + summary
                } else {
                    localLimitSummary(maxToolRounds())
                }
                record(AgentMessage(AgentMessage.Role.AGENT, text))
                if (!allowContinuePrompt || !askContinue()) return
                historyStore.replace(result.messages + assistantMessage(text))
                nextInput = AppLanguage.tr(
                    "用户同意继续操作。请从刚才停下的地方继续，不要重复已经完成的步骤。",
                    "The user approved continuing. Resume from where you stopped " +
                        "and do not repeat completed steps."
                )
                lastResponseText = ""
                continue
            }

            val failure = result.failure
            val message = if (failure?.code == "CONFIGURATION" ||
                failure?.code == "PLUGIN_BLOCKED"
            ) {
                failure.message
            } else {
                AppLanguage.tr(
                    "大模型调用失败：${failure?.message ?: "unknown error"}",
                    "Model call failed: ${failure?.message ?: "unknown error"}"
                )
            }
            record(AgentMessage(AgentMessage.Role.ERROR, message))
            return
        }
    }

    private fun sdkDefinition() = definition.copy(
        instructions = AgentInstructionProvider {
            listOf(
                definition.instructions.instructions(it),
                systemPrompt,
                AgentPluginSupport.promptBlock(
                    userMessage = command,
                    recentUserMessages = userUtterances.takeLast(6),
                    toolNames = chatTools.map { it.name }
                )
            )
                .filter(String::isNotBlank)
                .joinToString("\n\n")
        }
    )

    private fun runHook(): AgentRunHook = object : AgentRunHook {
        override suspend fun beforeModelDecision(
            request: com.coke.nestspace.agent.sdk.AgentChatInput
        ): AgentModelHookDecision {
            if (!pluginAnswerChecked) {
                pluginAnswerChecked = true
                AgentPluginSupport.answer(
                    userMessage = command,
                    recentUserMessages = listOf(command),
                    toolNames = chatTools.map { it.name }
                )?.let { answer ->
                    return AgentModelHookDecision.Respond(
                        AgentChatResponse(
                            blocks = listOf(AgentChatBlock.Text(answer.text))
                        )
                    )
                }
            }
            if (!config.isConfigured) {
                return AgentModelHookDecision.Stop(configurationFailure())
            }
            round++
            return when (
                val hooked = AgentPluginSupport.beforeModelRequest(
                    requestId = "$sessionId-${System.currentTimeMillis()}-$round",
                    protocol = config.protocol.name,
                    model = config.model,
                    userMessage = command,
                    systemPrompt = request.system,
                    toolNames = chatTools.map { it.name },
                    round = round
                )
            ) {
                is AgentModelHookOutcome.Proceed ->
                    AgentModelHookDecision.Proceed(
                        request.copy(
                            system = hooked.systemPrompt
                        )
                    )
                is AgentModelHookOutcome.Answer ->
                    AgentModelHookDecision.Respond(
                        AgentChatResponse(
                            blocks = listOf(
                                AgentChatBlock.Text(hooked.text)
                            )
                        )
                    )
                is AgentModelHookOutcome.Blocked ->
                    AgentModelHookDecision.Stop(
                        AgentFailure(
                            code = "PLUGIN_BLOCKED",
                            message = hooked.message,
                            kind = AgentFailureKind.CONFIGURATION
                        )
                    )
            }
        }

        override suspend fun afterModel(
            request: com.coke.nestspace.agent.sdk.AgentChatInput,
            response: AgentChatResponse
        ): AgentChatResponse {
            val text = response.text.trim()
            if (text.isNotBlank()) {
                lastResponseText = text
                record(AgentMessage(AgentMessage.Role.AGENT, text))
            }
            return response
        }
    }

    private fun steeringMessages() = AgentMessageSource {
        steeringProvider?.invoke().orEmpty().map { text ->
            record(AgentMessage(AgentMessage.Role.USER, text))
            userUtterances += text
            userMessage(
                AppLanguage.tr("用户补充：$text", "User follow-up: $text")
            )
        }
    }

    private fun toolHook(): AgentToolExecutionHook =
        object : AgentToolExecutionHook {
            override suspend fun beforeExecute(
                tool: AgentToolDescriptor,
                context: AgentRunContext,
                call: AgentToolCall
            ): AgentToolHookDecision {
                val args = runCatching { JSONObject(call.inputJson) }
                    .getOrElse {
                        return AgentToolHookDecision.Block(
                            code = "INVALID_ARGUMENT",
                            message = "Invalid tool arguments: ${it.message.orEmpty()}"
                        )
                    }
                val guard = AgentPluginSupport.beforeToolCall(
                    call.name,
                    args,
                    toolContext
                )
                guard.blockedMessage?.let {
                    return AgentToolHookDecision.Block(
                        code = "PLUGIN_BLOCKED",
                        message = it
                    )
                }
                return AgentToolHookDecision.Proceed(
                    call.copy(inputJson = guard.arguments.toString())
                )
            }

            override suspend fun afterExecute(
                tool: AgentToolDescriptor,
                context: AgentRunContext,
                call: AgentToolCall,
                result: AgentToolResult
            ): AgentToolResult {
                val outcome = toolOutcomes.remove(call.id)
                val toolResult = outcome?.result ?: result.toToolResult()
                val arguments = outcome?.arguments
                    ?: runCatching { JSONObject(call.inputJson) }
                        .getOrDefault(JSONObject())
                AgentPluginSupport.afterToolCall(
                    call.name,
                    arguments,
                    toolResult,
                    toolContext
                )
                recordToolCompletion(
                    toolName = call.name,
                    result = toolResult,
                    arguments = arguments
                )
                return result
            }
        }

    private fun agentTools(service: AgentChatService): List<AgentTool> =
        toolSpecs.map { spec ->
            AgentTool(
                descriptor = AgentToolDescriptor(
                    name = spec.name,
                    description = spec.description,
                    inputSchemaJson = spec.inputSchema.toString()
                ),
                handler = { _, call ->
                    val args = JSONObject(call.inputJson)
                    val result = registry.execute(spec.name, args, toolContext)
                    toolOutcomes[call.id] = AgentToolOutcome(result, args)
                    result.toAgentToolResult(
                        supportsVision = service.supportsVision,
                        toolName = spec.name
                    )
                }
            )
        }

    private fun eventSink() = AgentEventSink { event ->
        when (event) {
            is AgentEvent.TextDelta -> onTextDelta?.invoke(event.text)
            is AgentEvent.ToolStarted -> {
                onToolCall?.invoke(
                    event.toolName,
                    runCatching { JSONObject(event.inputJson) }
                        .getOrDefault(JSONObject())
                )
                metrics?.recordToolCall()
            }
            else -> Unit
        }
    }

    private suspend fun recordToolCompletion(
        toolName: String,
        result: ToolResult,
        arguments: JSONObject
    ) {
        AgentPluginSupport.publishEvent(
            "agent.tool.completed",
            JSONObject()
                .put("session_id", sessionId)
                .put("tool", toolName)
                .put("successful", result.ok)
                .put(
                    "error_code",
                    result.errorCode ?: JSONObject.NULL
                )
        )
        val header = if (result.ok) "ok" else result.errorCode ?: "error"
        val rawDisplayText = toolDisplayText(toolName, result)
        val presentation = AgentPluginSupport.presentTool(
            toolName = toolName,
            arguments = arguments,
            resultText = rawDisplayText,
            successful = result.ok,
            errorCode = result.errorCode
        )
        record(
            AgentMessage(
                AgentMessage.Role.TOOL,
                AgentPluginSupport.formatToolMessage(
                    toolName,
                    header,
                    rawDisplayText,
                    presentation,
                    arguments,
                    result.ok,
                    result.errorCode
                )
            )
        )
    }

    private fun record(message: AgentMessage) {
        trace += message
        onMessage?.invoke(message)
    }

    private fun publishStarted() {
        AgentPluginSupport.publishEvent(
            "agent.run.started",
            JSONObject()
                .put("session_id", sessionId)
                .put("model", config.model)
        )
    }

    private suspend fun askContinue(): Boolean = UserGateManager.askConfirmation(
        title = AppLanguage.tr(
            "已达到工具轮次上限",
            "Tool round limit reached"
        ),
        detail = AppLanguage.tr(
            "本次已执行 ${maxToolRounds()} 轮工具调用。是否继续执行剩余操作？",
            "Used ${maxToolRounds()} tool rounds. Continue with the remaining steps?"
        ),
        confirmLabel = AppLanguage.tr("继续", "Continue"),
        dismissLabel = AppLanguage.tr("停止", "Stop")
    )

    private suspend fun summarizeAfterLimit(
        service: AgentChatService,
        messages: List<AgentChatMessage>
    ): String? {
        val rounds = maxToolRounds()
        val instruction = AppLanguage.tr(
            "你已达到 $rounds 轮工具调用上限，不能再调用任何工具。请根据上面的对话和" +
                "工具结果，用中文给用户一个阶段性总结：1) 已经完成了什么；2) 得到了哪些 " +
                "确定的信息；3) 哪些步骤失败或未完成；4) 建议用户接下来怎么做。" +
                "不要编造未验证的数据。",
            "You have reached the $rounds-round tool-call limit and cannot call any " +
                "more tools. Based on the conversation and tool results above, give " +
                "the user a progress summary: 1) what was completed; 2) what was " +
                "verified; 3) what failed or remains; 4) what the user should do next. " +
                "Do not invent unverified data."
        )
        return requestSummary(service, messages, instruction)
    }

    private suspend fun summarizeEmptyResponse(
        service: AgentChatService,
        messages: List<AgentChatMessage>
    ): String? {
        val instruction = AppLanguage.tr(
            "你上一条回复没有产生任何文字。不要再调用工具，请根据上面的对话和工具" +
                "结果，用中文简短总结：已经完成了什么、得到了哪些确定的信息、哪些步骤" +
                "失败或未完成。不要编造未验证的数据。",
            "Your previous reply contained no text. Do not call any more tools; " +
                "summarize the conversation and tool results: what was completed, " +
                "what was verified, and what failed or remains. Do not invent data."
        )
        return requestSummary(service, messages, instruction)
    }

    private suspend fun requestSummary(
        service: AgentChatService,
        messages: List<AgentChatMessage>,
        instruction: String
    ): String? {
        val summaryMessages = messages.toMutableList().apply {
            add(userMessage(instruction))
        }
        val summaryTokens = maxOf(config.maxTokens, 2_048).coerceAtMost(8_192)
        ContextCompressor.compact(
            messages = summaryMessages,
            systemPrompt = systemPrompt,
            tools = emptyList(),
            maxOutputTokens = summaryTokens,
            budgetTokens = config.contextTokens
        )
        return try {
            service.chat(
                AgentChatOptions(
                    model = config.model,
                    maxOutputTokens = summaryTokens,
                    temperature = config.temperature,
                    reasoningEffort =
                        config.reasoningEffort.toAgentReasoningEffort(),
                    purpose = "summary"
                ).toInput(
                    system = systemPrompt,
                    messages = summaryMessages,
                    tools = emptyList()
                )
            ).text.trim().ifBlank { null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private fun configurationFailure() = AgentFailure(
        code = "CONFIGURATION",
        message = AppLanguage.tr(
            "请先在 空间设置 → 模型设置 配置接口（Base URL / 模型 / API Key）。",
            "Configure an endpoint (Base URL / model / API key) in " +
                "Space settings → Model settings first."
        ),
        kind = AgentFailureKind.CONFIGURATION
    )

    private fun userMessage(text: String) = AgentChatMessage(
        AgentChatRole.USER,
        listOf(AgentChatBlock.Text(text))
    )

    private fun assistantMessage(text: String) = AgentChatMessage(
        AgentChatRole.ASSISTANT,
        listOf(AgentChatBlock.Text(text))
    )

    private fun maxToolRounds(): Int = config.maxToolRounds
        .coerceIn(LlmConfig.MIN_TOOL_ROUNDS, LlmConfig.MAX_TOOL_ROUNDS)

    private fun emptySummaryPrefix(): String = AppLanguage.tr(
        "模型这次没有返回文本，以下是自动补的总结：\n\n",
        "The model returned no text; here is an automatic summary:\n\n"
    )

    private fun limitSummaryPrefix(rounds: Int): String = AppLanguage.tr(
        "已达到 $rounds 轮工具调用上限，先给你一个阶段性总结：\n\n",
        "Reached the $rounds-round tool limit. Here is a progress summary:\n\n"
    )

    private fun localEmptySummary(): String {
        val recent = trace.asReversed()
            .firstOrNull { it.role == AgentMessage.Role.TOOL }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(240)
        val last = if (recent.isNullOrBlank()) {
            ""
        } else {
            AppLanguage.tr(" 最近一步：$recent。", " Last step: $recent.")
        }
        return AppLanguage.tr(
            "模型返回了空文本，自动总结也没能生成。${last}请重试或缩小任务范围。",
            "The model returned no text and the automatic summary failed. $last" +
                "Try again or use a smaller task."
        )
    }

    private fun localLimitSummary(rounds: Int): String {
        val recent = trace.asReversed()
            .firstOrNull { it.role == AgentMessage.Role.TOOL }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(240)
        val last = if (recent.isNullOrBlank()) {
            ""
        } else {
            AppLanguage.tr(" 最近一步：$recent。", " Last step: $recent.")
        }
        return AppLanguage.tr(
            "已达到 $rounds 轮工具调用上限，模型总结生成失败。$last" +
                "请缩小任务范围后重试，或在模型设置里提高「最大工具轮次」。",
            "Reached the $rounds-round tool limit and summary generation failed. $last" +
                "Try a smaller task or raise Max tool rounds in the model settings."
        )
    }

    private fun toolDisplayText(name: String, result: ToolResult): String {
        if (name == "ui_observe" && result.ok) {
            runCatching {
                val json = JSONObject(result.text)
                JSONObject()
                    .put("package", json.optString("package"))
                    .put("activity", json.optString("activity"))
                    .put("nodes_count", json.optJSONArray("nodes")?.length() ?: 0)
                    .put("needs_user", json.optBoolean("needs_user"))
                    .put("user_reason", json.optString("user_reason"))
                    .toString()
            }.getOrNull()?.let { return it }
        }
        return result.text.take(4_000)
    }

    private fun ToolResult.toAgentToolResult(
        supportsVision: Boolean,
        toolName: String
    ): AgentToolResult {
        val attachments = imageBase64
            ?.takeIf(String::isNotBlank)
            ?.let {
                listOf(
                    AgentToolAttachment.Image(
                        dataBase64 = it,
                        mimeType = imageMimeType,
                        metadata = mapOf("tool" to toolName),
                        audience = if (supportsVision) {
                            AgentAttachmentAudience.MODEL
                        } else {
                            AgentAttachmentAudience.USER
                        }
                    )
                )
            }
            .orEmpty()
        val output = if (text.isBlank()) {
            if (ok) "(ok)" else "ERROR ${errorCode.orEmpty()}"
        } else if (ok) {
            text
        } else {
            "ERROR ${errorCode.orEmpty()}: $text"
        }
        return AgentToolResult(
            success = ok,
            output = output.take(MAX_TOOL_TEXT),
            errorCode = errorCode,
            pending = pending,
            attachments = attachments
        )
    }

    private fun AgentToolResult.toToolResult() = ToolResult(
        ok = success,
        text = output,
        errorCode = errorCode,
        pending = pending
    )

    private data class AgentToolOutcome(
        val result: ToolResult,
        val arguments: JSONObject
    )

    private companion object {
        const val MAX_TOOL_TEXT = 12_000
    }
}
