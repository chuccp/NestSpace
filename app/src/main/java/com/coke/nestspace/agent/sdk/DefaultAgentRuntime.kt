package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentRunHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Minimal business-independent agent loop.
 *
 * The runtime owns orchestration only: resolve an agent, apply its tool
 * policy, call the consumer-provided chat service, execute requested tools,
 * and feed their results back into the next model turn. Model transport,
 * persistence, presentation and scheduling belong to the consumer.
 */
class DefaultAgentRuntime(
    private val catalog: AgentCatalog,
    private val toolCatalog: AgentToolCatalog,
    private val chatService: AgentChatService,
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
    private val capabilities: AgentCapabilityCatalog =
        AgentCapabilityCatalog(emptyList()),
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor(),
    private val runHooks: List<AgentRunHook> = emptyList(),
    private val runtimeScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AgentRuntime {

    override fun start(
        request: AgentRunRequest,
        events: AgentEventSink
    ): AgentRunHandle {
        val eventLog = AgentEventLog()
        val deferred = CompletableDeferred<AgentRunResult>()
        val job = runtimeScope.launch {
            try {
                deferred.complete(execute(request, events, eventLog))
            } catch (cancelled: CancellationException) {
                deferred.cancel(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            }
        }
        val handle = AgentRunHandle(
            runId = request.runId,
            eventLog = eventLog,
            result = deferred,
            job = job,
            scope = runtimeScope
        )
        config.runTimeoutMillis?.let(handle::cancelAfter)
        return handle
    }

    private suspend fun execute(
        request: AgentRunRequest,
        events: AgentEventSink,
        eventLog: AgentEventLog
    ): AgentRunResult {
        val recorder = AgentEventRecorder(events, eventLog)
        recorder.emit(AgentEvent.Started(request.runId))
        try {
            val definition = catalog.get(request.agentKey)
                ?: return failBeforeSession(
                    request = request,
                    messages = request.history,
                    recorder = recorder,
                    code = "UNKNOWN_AGENT",
                    message = "Unknown agent: ${request.agentKey.value}",
                    kind = AgentFailureKind.CONFIGURATION
                )
            try {
                runHooks.forEach { it.beforeRun(request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return failBeforeSession(
                    request = request,
                    messages = request.history,
                    recorder = recorder,
                    code = "RUN_HOOK_ERROR",
                    message = error.message ?: error.javaClass.simpleName,
                    cause = error,
                    kind = AgentFailureKind.CONFIGURATION
                )
            }
            val allTools = AgentToolCatalog(
                toolCatalog.all() + capabilities.tools()
            )
            return AgentRunSession(
                request = request,
                definition = definition,
                tools = allTools.forAgent(definition.toolPolicy),
                capabilityInstructions = capabilities.instructions(),
                chatService = chatService,
                toolExecutor = toolExecutor,
                runHooks = runHooks,
                config = config,
                recorder = recorder
            ).execute()
        } catch (cancelled: CancellationException) {
            recordCancellation(
                request = request,
                recorder = recorder,
                reason = cancelled.message ?: "cancelled"
            )
            throw cancelled
        } catch (error: Throwable) {
            return failBeforeSession(
                request = request,
                messages = request.history,
                recorder = recorder,
                code = "AGENT_START_ERROR",
                message = error.message ?: error.javaClass.simpleName,
                cause = error,
                kind = when (error) {
                    is IllegalArgumentException,
                    is IllegalStateException -> AgentFailureKind.CONFIGURATION
                    else -> classifyAgentFailure(error)
                }
            )
        }
    }

    private suspend fun failBeforeSession(
        request: AgentRunRequest,
        messages: List<AgentChatMessage>,
        recorder: AgentEventRecorder,
        code: String,
        message: String,
        cause: Throwable? = null,
        kind: AgentFailureKind
    ): AgentRunResult {
        val result = failureResult(
            request = request,
            messages = messages,
            recorder = recorder,
            code = code,
            message = message,
            cause = cause,
            kind = kind
        )
        result.failure?.let { failure ->
            runHooks.forEach { hook ->
                runCatching { hook.onFailed(request, failure) }
            }
        }
        return result
    }

    private suspend fun recordCancellation(
        request: AgentRunRequest,
        recorder: AgentEventRecorder,
        reason: String
    ) {
        withContext(NonCancellable) {
            recorder.emit(AgentEvent.Cancelled(request.runId, reason))
            runHooks.forEach { hook ->
                runCatching { hook.onCancelled(request, reason) }
            }
        }
    }
}

private class AgentRunSession(
    private val request: AgentRunRequest,
    private val definition: AgentDefinition,
    private val tools: List<AgentTool>,
    private val capabilityInstructions: String,
    private val chatService: AgentChatService,
    private val toolExecutor: AgentToolExecutor,
    private val runHooks: List<AgentRunHook>,
    private val config: AgentRuntimeConfig,
    private val recorder: AgentEventRecorder
) {
    private val attachments = mutableListOf<AgentToolAttachment>()
    private val toolDescriptors = tools.map(AgentTool::descriptor)
    private val toolsByName = tools.associateBy { it.descriptor.name }
    private val chatTools = toolDescriptors.map {
        AgentChatTool(
            name = it.name,
            description = it.description,
            inputSchema = JSONObject(it.inputSchemaJson)
        )
    }
    private val messages = createMessages()
    private val instruction = systemInstruction()

    suspend fun execute(): AgentRunResult {
        repeat(config.maxToolRounds) { roundIndex ->
            runRound(roundIndex)?.let { return it }
        }

        return fail(
            code = "MAX_TOOL_ROUNDS",
            message = "Agent reached its ${config.maxToolRounds} tool-round limit",
            kind = AgentFailureKind.TOOL
        )
    }

    private suspend fun runRound(roundIndex: Int): AgentRunResult? {
        currentCoroutineContext().ensureActive()
        val response = try {
            completeModelTurn()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AgentRunHookException) {
            return fail(
                code = "RUN_HOOK_ERROR",
                message = error.message ?: "Agent run hook failed",
                cause = error,
                kind = AgentFailureKind.CONFIGURATION
            )
        } catch (error: Throwable) {
            return fail(
                code = "MODEL_ERROR",
                message = error.message ?: error.javaClass.simpleName,
                cause = error
            )
        }
        appendAssistantMessage(response)
        if (response.toolUses.isEmpty()) {
            return complete(response)
        }

        val toolBatch = executeTools(response.toolUses)
        attachments += toolBatch.attachments
        messages += AgentChatMessage(
            role = AgentChatRole.USER,
            blocks = toolBatch.results
        )
        val attachmentBlocks = toolBatch.attachments.mapNotNull(::attachmentBlock)
        if (attachmentBlocks.isNotEmpty()) {
            messages += AgentChatMessage(
                role = AgentChatRole.USER,
                blocks = attachmentBlocks
            )
        }
        return if (roundIndex == config.maxToolRounds - 1) {
            fail(
                code = "MAX_TOOL_ROUNDS",
                message = "Agent reached its ${config.maxToolRounds} tool-round limit",
                kind = AgentFailureKind.TOOL
            )
        } else {
            null
        }
    }

    private suspend fun completeModelTurn(): AgentChatResponse {
        val streamedText = StringBuilder()
        val modelMessages = messages.toMutableList()
        config.contextBudgetTokens?.let { budget ->
            ContextCompressor.compact(
                messages = modelMessages,
                systemPrompt = instruction,
                tools = chatTools,
                maxOutputTokens = request.chatOptions.maxOutputTokens,
                budgetTokens = budget
            )
        }
        var input = request.chatOptions.toInput(
            system = instruction,
            messages = modelMessages,
            tools = chatTools
        )
        input = runHooks.fold(input) { current, hook ->
            runHook("beforeModel") { hook.beforeModel(current) }
        }
        var output = chatService.streamChat(
            request = input,
            onTextDelta = { text ->
                if (text.isNotEmpty()) {
                    streamedText.append(text)
                    recorder.emit(AgentEvent.TextDelta(request.runId, text))
                }
            }
        )
        output = runHooks.fold(output) { current, hook ->
            runHook("afterModel") { hook.afterModel(input, current) }
        }
        if (streamedText.isEmpty() && output.text.isNotEmpty()) {
            recorder.emit(AgentEvent.TextDelta(request.runId, output.text))
        }
        return output
    }

    private suspend fun executeTools(
        calls: List<AgentChatBlock.ToolUse>
    ): ToolExecutionBatch = if (config.maxParallelTools == 1) {
        executeToolsSequentially(calls)
    } else {
        executeToolsConcurrently(calls)
    }

    private suspend fun executeToolsSequentially(
        calls: List<AgentChatBlock.ToolUse>
    ): ToolExecutionBatch {
        val outcomes = calls.map { call ->
            emitToolStarted(call)
            executeToolCall(call)
        }
        return outcomes.toBatch()
    }

    private suspend fun executeToolsConcurrently(
        calls: List<AgentChatBlock.ToolUse>
    ): ToolExecutionBatch {
        calls.forEach(::emitToolStarted)
        val semaphore = Semaphore(config.maxParallelTools)
        val outcomes = coroutineScope {
            calls.map { call ->
                async {
                    semaphore.withPermit {
                        executeToolCall(call)
                    }
                }
            }.awaitAll()
        }
        return outcomes.toBatch()
    }

    private fun emitToolStarted(call: AgentChatBlock.ToolUse) {
        recorder.emit(
            AgentEvent.ToolStarted(
                runId = request.runId,
                callId = call.id,
                toolName = call.name,
                inputJson = call.input.toString()
            )
        )
    }

    private suspend fun executeToolCall(
        call: AgentChatBlock.ToolUse
    ): ToolCallOutcome {
        currentCoroutineContext().ensureActive()
        val toolCall = AgentToolCall(
            id = call.id,
            name = call.name,
            inputJson = call.input.toString()
        )
        val result = executeTool(toolCall)
        recorder.emit(
            AgentEvent.ToolCompleted(
                runId = request.runId,
                callId = call.id,
                toolName = call.name,
                success = result.success,
                output = result.output,
                errorCode = result.errorCode
            )
        )
        return ToolCallOutcome(
            result = AgentChatBlock.ToolResult(
                toolUseId = call.id,
                content = result.output,
                isError = !result.success
            ),
            attachments = result.attachments
        )
    }

    private fun attachmentBlock(attachment: AgentToolAttachment): AgentChatBlock? {
        if (attachment.audience == AgentAttachmentAudience.USER) return null
        val supportsVision = chatService.supportsVision(request.chatOptions.providerId)
        return when (attachment) {
            is AgentToolAttachment.Image -> {
                if (supportsVision) {
                    AgentChatBlock.Image(
                        attachment.dataBase64,
                        attachment.mimeType
                    )
                } else {
                    AgentChatBlock.Text(
                        "[image attachment available: ${attachment.mimeType}]"
                    )
                }
            }
            is AgentToolAttachment.File -> {
                if (supportsVision &&
                    attachment.dataBase64 != null &&
                    attachment.mimeType?.startsWith("image/") == true
                ) {
                    AgentChatBlock.Image(
                        attachment.dataBase64,
                        requireNotNull(attachment.mimeType)
                    )
                } else {
                    AgentChatBlock.Text(
                        buildString {
                            append("[file attachment: ")
                            append(attachment.name)
                            attachment.mimeType?.let { append(", type=$it") }
                            attachment.uri?.let { append(", uri=$it") }
                            append(']')
                        }
                    )
                }
            }
            is AgentToolAttachment.Reference -> AgentChatBlock.Text(
                "[reference: ${attachment.name} (${attachment.uri})]"
            )
        }
    }

    private suspend fun executeTool(call: AgentToolCall): AgentToolResult {
        val tool = toolsByName[call.name]
            ?: return AgentToolResult(
                success = false,
                output = "Tool is not allowed for this agent: ${call.name}",
                errorCode = "TOOL_NOT_ALLOWED"
            )
        return toolExecutor.execute(tool, request.context, call)
    }

    private fun List<ToolCallOutcome>.toBatch(): ToolExecutionBatch =
        ToolExecutionBatch(
            results = map(ToolCallOutcome::result),
            attachments = flatMap(ToolCallOutcome::attachments)
        )

    private suspend fun <T> runHook(
        hookName: String,
        block: suspend () -> T
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw AgentRunHookException(
            "$hookName hook failed: ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }

    private fun createMessages(): MutableList<AgentChatMessage> = buildList {
        addAll(request.history)
        add(
            AgentChatMessage(
                role = AgentChatRole.USER,
                blocks = listOf(AgentChatBlock.Text(request.input))
            )
        )
    }.toMutableList()

    private fun appendAssistantMessage(response: AgentChatResponse) {
        if (response.blocks.isNotEmpty()) {
            messages += AgentChatMessage(
                role = AgentChatRole.ASSISTANT,
                blocks = response.blocks
            )
        }
    }

    private suspend fun complete(response: AgentChatResponse): AgentRunResult {
        recorder.emit(AgentEvent.Completed(request.runId, response.text))
        val result = AgentRunResult(
            output = response.text,
            messages = messages.toList(),
            events = recorder.events(),
            attachments = attachments.toList()
        )
        runHooks.forEach { hook ->
            runCatching { hook.onCompleted(request, result) }
        }
        return result
    }

    private suspend fun fail(
        code: String,
        message: String,
        cause: Throwable? = null,
        kind: AgentFailureKind? = null
    ): AgentRunResult {
        val result = failureResult(
            request = request,
            messages = messages,
            recorder = recorder,
            code = code,
            message = message,
            cause = cause,
            kind = kind
                ?: cause?.let(::classifyAgentFailure)
                ?: AgentFailureKind.UNKNOWN,
            attachments = attachments.toList()
        )
        result.failure?.let { failure ->
            runHooks.forEach { hook ->
                runCatching { hook.onFailed(request, failure) }
            }
        }
        return result
    }

    private fun systemInstruction(): String = buildList {
        definition.instructions.instructions(request.context)
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(::add)
        capabilityInstructions
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(::add)
        toolDescriptors.map(AgentToolDescriptor::usagePrompt)
            .filter(String::isNotBlank)
            .map(String::trim)
            .forEach(::add)
    }.joinToString(separator = "\n\n")

}

private class AgentEventRecorder(
    private val sink: AgentEventSink,
    private val log: AgentEventLog
) {
    private val lock = Any()

    fun emit(event: AgentEvent) {
        synchronized(lock) {
            log.append(event)
            runCatching { sink.emit(event) }
        }
    }

    fun events(): List<AgentEventRecord> = log.records()
}

private data class ToolExecutionBatch(
    val results: List<AgentChatBlock.ToolResult>,
    val attachments: List<AgentToolAttachment>
)

private data class ToolCallOutcome(
    val result: AgentChatBlock.ToolResult,
    val attachments: List<AgentToolAttachment>
)

private class AgentRunHookException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

private fun failureResult(
    request: AgentRunRequest,
    messages: List<AgentChatMessage>,
    recorder: AgentEventRecorder,
    code: String,
    message: String,
    cause: Throwable? = null,
    kind: AgentFailureKind = AgentFailureKind.UNKNOWN,
    attachments: List<AgentToolAttachment> = emptyList()
): AgentRunResult {
    val error = AgentFailure(code, message, cause, kind)
    recorder.emit(AgentEvent.Failed(request.runId, error))
    return AgentRunResult(
        output = messages.lastOrNull()?.text.orEmpty(),
        messages = messages.toList(),
        events = recorder.events(),
        failure = error,
        attachments = attachments
    )
}
