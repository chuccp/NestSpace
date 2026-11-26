package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentRunHook
import com.coke.nestspace.agent.sdk.hook.AgentModelHookDecision
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentRuntimeFeaturesTest {
    private val context = AgentRunContext(sessionId = "features-session")

    @Test
    fun runHandleExposesMonotonicReplayableEvents() = runBlocking {
        val handle = runtime(QueueChatService(textResponse("hello")))
            .start(AgentRunRequest(AgentKey("agent"), "question", context))

        val result = handle.await()

        assertTrue(result.isSuccess)
        assertEquals(
            result.events.indices.map { (it + 1).toLong() },
            result.events.map(AgentEventRecord::sequence)
        )
        assertEquals(result.events, handle.snapshotEvents())
        assertEquals(
            result.events,
            withTimeout(1_000) {
                handle.events().take(result.events.size).toList()
            }
        )
    }

    @Test
    fun runHandleCancellationStopsTheIndependentRuntime() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val handle = runtime(chatService).start(
            AgentRunRequest(AgentKey("agent"), "wait", context)
        )

        started.await()
        assertTrue(handle.cancel("manual stop"))
        val cancellation = awaitCancellation(handle)

        assertTrue(cancellation.message.orEmpty().contains("manual stop"))
        val cancelled = handle.snapshotEvents().last().event
        assertTrue(cancelled is AgentEvent.Cancelled)
        assertEquals(
            "manual stop",
            (cancelled as AgentEvent.Cancelled).reason
        )
        assertFalse(handle.cancel())
    }

    @Test
    fun configuredRunTimeoutCancelsTheRuntime() = runBlocking {
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                awaitCancellation()
            }
        }
        val handle = runtime(
            chatService = chatService,
            config = AgentRuntimeConfig(runTimeoutMillis = 20)
        ).start(AgentRunRequest(AgentKey("agent"), "wait", context))

        val cancellation = awaitCancellation(handle)

        assertEquals("run timeout", cancellation.message)
        val cancelled = handle.snapshotEvents().last().event
        assertTrue(cancelled is AgentEvent.Cancelled)
    }

    @Test
    fun toolsRunConcurrentlyButResultsKeepModelCallOrder() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val chatService = QueueChatService(
            AgentChatResponse(
                blocks = listOf(
                    toolUse("first", "first"),
                    toolUse("second", "second")
                )
            ),
            textResponse("done")
        )
        val delayedTool = AgentToolHandler { _, call ->
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current) { previous, value ->
                maxOf(previous, value)
            }
            try {
                delay(80)
                AgentToolResult(success = true, output = call.name)
            } finally {
                active.decrementAndGet()
            }
        }
        val runtime = runtime(
            chatService = chatService,
            tools = listOf(
                tool("first", delayedTool),
                tool("second", delayedTool)
            ),
            config = AgentRuntimeConfig(maxParallelTools = 2)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "work", context)
        )

        assertTrue(result.isSuccess)
        assertEquals(2, peak.get())
        val toolResults = chatService.requests[1].messages.last()
            .blocks
            .filterIsInstance<AgentChatBlock.ToolResult>()
        assertEquals(listOf("first", "second"), toolResults.map { it.toolUseId })
        assertEquals(
            result.events.indices.map { (it + 1).toLong() },
            result.events.map(AgentEventRecord::sequence)
        )
    }

    @Test
    fun modelVisibleAttachmentsRespectAudienceAndVisionSupport() = runBlocking {
        val chatService = QueueChatService(
            AgentChatResponse(
                blocks = listOf(
                    toolUse("capture", "capture"),
                    toolUse("export", "export")
                )
            ),
            textResponse("done"),
            visionSupported = true
        )
        val capture = AgentTool(
            descriptor = AgentToolDescriptor("capture", "Capture", "{}"),
            handler = AgentToolHandler { _, _ ->
                AgentToolResult(
                    success = true,
                    attachments = listOf(
                        AgentToolAttachment.Image(
                            dataBase64 = "image-data",
                            mimeType = "image/png",
                            metadata = mapOf("source" to "screen")
                        )
                    )
                )
            }
        )
        val export = AgentTool(
            descriptor = AgentToolDescriptor("export", "Export", "{}"),
            handler = AgentToolHandler { _, _ ->
                AgentToolResult(
                    success = true,
                    attachments = listOf(
                        AgentToolAttachment.File(
                            name = "private.txt",
                            mimeType = "text/plain",
                            uri = "file:///private.txt"
                        )
                    )
                )
            }
        )

        val result = runtime(chatService, listOf(capture, export)).run(
            AgentRunRequest(AgentKey("agent"), "inspect", context)
        )

        assertTrue(result.isSuccess)
        val modelAttachment = chatService.requests[1].messages.last().blocks.single()
        assertEquals(
            AgentChatBlock.Image("image-data", "image/png"),
            modelAttachment
        )
        assertEquals(2, result.attachments.size)
        assertEquals(
            "screen",
            (result.attachments.first() as AgentToolAttachment.Image)
                .metadata["source"]
        )
        assertFalse(modelAttachment.toString().contains("private.txt"))
    }

    @Test
    fun nonVisionModelReceivesTextPlaceholderInsteadOfImage() = runBlocking {
        val chatService = QueueChatService(
            AgentChatResponse(blocks = listOf(toolUse("capture", "capture"))),
            textResponse("done")
        )
        val capture = AgentTool(
            descriptor = AgentToolDescriptor("capture", "Capture", "{}"),
            handler = AgentToolHandler { _, _ ->
                AgentToolResult(
                    success = true,
                    attachments = listOf(
                        AgentToolAttachment.Image(
                            dataBase64 = "image-data",
                            mimeType = "image/jpeg"
                        )
                    )
                )
            }
        )

        runtime(chatService, listOf(capture)).run(
            AgentRunRequest(AgentKey("agent"), "inspect", context)
        )

        val attachment = chatService.requests[1].messages.last().text
        assertTrue(attachment.contains("image/jpeg"))
        assertTrue(
            chatService.requests[1].messages
                .flatMap(AgentChatMessage::blocks)
                .none { it is AgentChatBlock.Image }
        )
    }

    @Test
    fun runHooksCanRewriteModelTurnsAndObserveCompletion() = runBlocking {
        val calls = mutableListOf<String>()
        val hook = object : AgentRunHook {
            override suspend fun beforeRun(request: AgentRunRequest) {
                calls += "beforeRun"
            }

            override suspend fun beforeModel(
                request: AgentChatInput
            ): AgentChatInput {
                calls += "beforeModel"
                return request.copy(system = request.system + "\n[hook]")
            }

            override suspend fun afterModel(
                request: AgentChatInput,
                response: AgentChatResponse
            ): AgentChatResponse {
                calls += "afterModel"
                return response.copy(
                    blocks = listOf(AgentChatBlock.Text("rewritten"))
                )
            }

            override suspend fun onCompleted(
                request: AgentRunRequest,
                result: AgentRunResult
            ) {
                calls += "completed"
            }
        }
        val chatService = QueueChatService(textResponse("original"))
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = chatService,
            runHooks = listOf(hook)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertEquals("rewritten", result.output)
        assertTrue(chatService.requests.single().system.endsWith("[hook]"))
        assertEquals(
            listOf("beforeRun", "beforeModel", "afterModel", "completed"),
            calls
        )
    }

    @Test
    fun modelHookFailureBecomesStructuredConfigurationFailure() = runBlocking {
        val chatService = QueueChatService(textResponse("unused"))
        val hook = object : AgentRunHook {
            override suspend fun beforeModel(
                request: AgentChatInput
            ): AgentChatInput = error("broken hook")
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = chatService,
            runHooks = listOf(hook)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertFalse(result.isSuccess)
        assertEquals("RUN_HOOK_ERROR", result.failure?.code)
        assertEquals(AgentFailureKind.CONFIGURATION, result.failure?.kind)
        assertTrue(result.failure?.message.orEmpty().contains("beforeModel"))
        assertTrue(chatService.requests.isEmpty())
    }

    @Test
    fun modelHookCanAnswerWithoutCallingProvider() = runBlocking {
        val chatService = QueueChatService()
        val hook = object : AgentRunHook {
            override suspend fun beforeModelDecision(
                request: AgentChatInput
            ): AgentModelHookDecision = AgentModelHookDecision.Respond(
                AgentChatResponse(
                    blocks = listOf(AgentChatBlock.Text("local answer"))
                )
            )
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = chatService,
            runHooks = listOf(hook)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertTrue(result.isSuccess)
        assertEquals("local answer", result.output)
        assertTrue(chatService.requests.isEmpty())
    }

    @Test
    fun modelHookCanStopRunWithStructuredFailure() = runBlocking {
        val failure = AgentFailure(
            code = "POLICY_BLOCKED",
            message = "blocked locally",
            kind = AgentFailureKind.CONFIGURATION
        )
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = QueueChatService(),
            runHooks = listOf(object : AgentRunHook {
                override suspend fun beforeModelDecision(
                    request: AgentChatInput
                ): AgentModelHookDecision =
                    AgentModelHookDecision.Stop(failure)
            })
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertEquals(failure, result.failure)
    }

    @Test
    fun incomingMessagesMergeIntoTheNextModelTurn() = runBlocking {
        val chatService = QueueChatService(textResponse("done"))
        var delivered = false
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = chatService,
            incomingMessages = AgentMessageSource {
                if (delivered) {
                    emptyList()
                } else {
                    delivered = true
                    listOf(
                        AgentChatMessage(
                            AgentChatRole.USER,
                            listOf(AgentChatBlock.Text("follow-up"))
                        )
                    )
                }
            }
        )

        runtime.run(
            AgentRunRequest(AgentKey("agent"), "initial", context)
        )

        val messages = chatService.requests.single().messages
        assertEquals(1, messages.size)
        assertEquals(
            listOf("initial", "follow-up"),
            messages.single().blocks
                .filterIsInstance<AgentChatBlock.Text>()
                .map(AgentChatBlock.Text::text)
        )
    }

    @Test
    fun beforeRunHookFailureIsReportedThroughLifecycle() = runBlocking {
        var failed = false
        val hook = object : AgentRunHook {
            override suspend fun beforeRun(request: AgentRunRequest) {
                error("initialization failed")
            }

            override suspend fun onFailed(
                request: AgentRunRequest,
                failure: AgentFailure
            ) {
                failed = true
            }
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = QueueChatService(textResponse("unused")),
            runHooks = listOf(hook)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertEquals("RUN_HOOK_ERROR", result.failure?.code)
        assertEquals(AgentFailureKind.CONFIGURATION, result.failure?.kind)
        assertTrue(failed)
    }

    @Test
    fun completionObserverFailureDoesNotChangeRunResult() = runBlocking {
        val hook = object : AgentRunHook {
            override suspend fun onCompleted(
                request: AgentRunRequest,
                result: AgentRunResult
            ) {
                error("observer failure")
            }
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = QueueChatService(textResponse("ok")),
            runHooks = listOf(hook)
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertTrue(result.isSuccess)
        assertEquals("ok", result.output)
    }

    @Test
    fun configuredContextCompressionPreservesInputsAndInstructions() = runBlocking {
        val chatService = QueueChatService(textResponse("ok"))
        val history = buildList {
            repeat(8) { index ->
                add(
                    AgentChatMessage(
                        AgentChatRole.USER,
                        listOf(AgentChatBlock.Text("question-$index"))
                    )
                )
                add(
                    AgentChatMessage(
                        AgentChatRole.ASSISTANT,
                        listOf(AgentChatBlock.Text("answer".repeat(2_000)))
                    )
                )
            }
        }
        val originalHistory = history.toList()
        val read = tool("read", AgentToolHandler { _, _ ->
            AgentToolResult(success = true)
        }).copy(
            descriptor = AgentToolDescriptor(
                name = "read",
                description = "Read data",
                inputSchemaJson = "{}",
                usagePrompt = "Read before writing."
            )
        )
        val runtime = runtime(
            chatService = chatService,
            tools = listOf(read),
            config = AgentRuntimeConfig(
                contextBudgetTokens = 1_500,
                maxParallelTools = 1
            )
        )

        val result = runtime.run(
            AgentRunRequest(
                agentKey = AgentKey("agent"),
                input = "latest",
                context = context,
                history = history,
                chatOptions = AgentChatOptions(maxOutputTokens = 100)
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(originalHistory, history)
        val request = chatService.requests.single()
        assertEquals(
            "Base instructions.\n\nRead before writing.",
            request.system
        )
        assertEquals(listOf("read"), request.tools.map(AgentChatTool::name))
        originalHistory.filter { it.role == AgentChatRole.USER }.forEach {
            assertTrue(request.messages.any { message -> message.text == it.text })
        }
        assertTrue(
            request.messages
                .filter { it.role == AgentChatRole.ASSISTANT }
                .any { it.text.length < "answer".repeat(2_000).length }
        )
    }

    @Test
    fun failuresExposeStableKinds() = runBlocking {
        assertEquals(
            AgentFailureKind.AUTHENTICATION,
            classifyAgentFailure(IllegalStateException("HTTP 401"))
        )
        assertEquals(
            AgentFailureKind.RATE_LIMIT,
            classifyAgentFailure(IllegalStateException("HTTP 429"))
        )
        assertEquals(
            AgentFailureKind.TIMEOUT,
            classifyAgentFailure(IllegalStateException("request timeout"))
        )
        assertEquals(
            AgentFailureKind.CONTEXT_LENGTH,
            classifyAgentFailure(IllegalStateException("context length exceeded"))
        )
        assertEquals(
            AgentFailureKind.INVALID_REQUEST,
            classifyAgentFailure(IllegalStateException("HTTP 400"))
        )
        assertEquals(
            AgentFailureKind.PROVIDER,
            classifyAgentFailure(IllegalStateException("HTTP 503"))
        )

        val unknownAgent = runtime(QueueChatService()).run(
            AgentRunRequest(AgentKey("missing"), "question", context)
        )
        assertEquals("UNKNOWN_AGENT", unknownAgent.failure?.code)
        assertEquals(
            AgentFailureKind.CONFIGURATION,
            unknownAgent.failure?.kind
        )
    }

    @Test
    fun invalidCapabilityCompositionReturnsStructuredFailure() = runBlocking {
        val duplicate = tool("read") { _, _ -> AgentToolResult(success = true) }
        val capability = object : AgentCapability {
            override val id: String = "duplicate"
            override val description: String = "Duplicate tool"
            override fun tools(): List<AgentTool> = listOf(duplicate)
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(listOf(definition())),
            toolCatalog = AgentToolCatalog(listOf(duplicate)),
            chatService = QueueChatService(textResponse("unused")),
            capabilities = AgentCapabilityCatalog(listOf(capability))
        )

        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "question", context)
        )

        assertEquals("AGENT_START_ERROR", result.failure?.code)
        assertEquals(AgentFailureKind.CONFIGURATION, result.failure?.kind)
    }

    private suspend fun awaitCancellation(handle: AgentRunHandle): CancellationException =
        try {
            withTimeout(2_000) { handle.await() }
            fail("Expected run cancellation")
            error("unreachable")
        } catch (cancelled: CancellationException) {
            cancelled
        }

    private fun runtime(
        chatService: AgentChatService,
        tools: List<AgentTool> = emptyList(),
        config: AgentRuntimeConfig = AgentRuntimeConfig()
    ): DefaultAgentRuntime = DefaultAgentRuntime(
        catalog = AgentCatalog(listOf(definition())),
        toolCatalog = AgentToolCatalog(tools),
        chatService = chatService,
        config = config
    )

    private fun definition(): AgentDefinition = AgentDefinition(
        key = AgentKey("agent"),
        lane = AgentLane("default"),
        displayName = "Agent",
        instructions = AgentInstructionProvider {
            "Base instructions."
        }
    )

    private fun tool(name: String, handler: AgentToolHandler): AgentTool =
        AgentTool(
            descriptor = AgentToolDescriptor(name, "$name tool", "{}"),
            handler = handler
        )

    private fun toolUse(id: String, name: String): AgentChatBlock.ToolUse =
        AgentChatBlock.ToolUse(id, name, JSONObject())

    private fun textResponse(text: String): AgentChatResponse =
        AgentChatResponse(blocks = listOf(AgentChatBlock.Text(text)))
}

private class QueueChatService(
    vararg responses: AgentChatResponse,
    visionSupported: Boolean = false
) : AgentChatService {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<AgentChatInput>()

    override val supportsVision: Boolean = visionSupported

    override suspend fun chat(request: AgentChatInput): AgentChatResponse =
        next(request)

    override suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse {
        val response = next(request)
        if (response.text.isNotEmpty()) onTextDelta(response.text)
        return response
    }

    private fun next(request: AgentChatInput): AgentChatResponse {
        requests += request
        return checkNotNull(responses.removeFirstOrNull()) {
            "No scripted response left"
        }
    }
}
