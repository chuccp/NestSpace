package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSdkTest {
    private val context = AgentRunContext(sessionId = "session-1")

    @Test
    fun toolPolicyUsesAllowListAndDenyPrecedence() {
        val policy = AgentToolPolicy(
            allowed = setOf("read", "write"),
            denied = setOf("write")
        )

        assertTrue(policy.permits("read"))
        assertFalse(policy.permits("write"))
        assertFalse(policy.permits("navigate"))
    }

    @Test
    fun catalogRequiresUniqueNonBlankKeys() {
        val first = agent("first")
        val second = agent("second")
        val catalog = AgentCatalog(listOf(first, second))

        assertEquals(first, catalog.get(first.key))
        assertNull(catalog.get(AgentKey("missing")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentCatalog(listOf(first, first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentKey(" ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.copy(displayName = "")
        }
    }

    @Test
    fun runtimeReturnsModelOutputAndEventStream() = runBlocking {
        val chatService = ScriptedChatService(textResponse("hello", "stop")) {
                response,
                deltas ->
            deltas(response.text.take(2))
            deltas(response.text.drop(2))
        }
        val result = runtime(
            definition = agent(
                instructions = AgentInstructionProvider { "session=${it.sessionId}" }
            ),
            chatService = chatService
        ).run(
            AgentRunRequest(
                agentKey = AgentKey("agent"),
                input = "question",
                context = context
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("hello", result.output)
        assertEquals(
            listOf(
                AgentEvent.Started::class,
                AgentEvent.TextDelta::class,
                AgentEvent.TextDelta::class,
                AgentEvent.Completed::class
            ),
            result.events.map { it.event::class }
        )
        assertEquals("session=session-1", chatService.requests.single().system)
        assertEquals("question", chatService.requests.single().messages.last().text)
        assertEquals("hello", result.messages.last().text)
    }

    @Test
    fun chatInputCarriesProviderNeutralConversationAndOptions() = runBlocking {
        val chatService = ScriptedChatService(textResponse("ok"))
        val options = AgentChatOptions(
            providerId = "provider-a",
            model = "model-a",
            maxOutputTokens = 1_024,
            temperature = 0.2,
            reasoningEffort = AgentReasoningEffort.HIGH,
            purpose = "test",
            attributes = mapOf("trace" to "enabled")
        )
        runtime(agent(), chatService).run(
            AgentRunRequest(
                agentKey = AgentKey("agent"),
                input = "hello",
                context = context,
                history = listOf(
                    AgentChatMessage(
                        role = AgentChatRole.USER,
                        blocks = listOf(AgentChatBlock.Text("earlier"))
                    )
                ),
                chatOptions = options
            )
        )

        val request = chatService.requests.single()
        assertEquals("model-a", request.model)
        assertEquals("provider-a", request.providerId)
        assertEquals(1_024, request.maxTokens)
        assertEquals(0.2, request.temperature, 0.0)
        assertEquals(AgentReasoningEffort.HIGH, request.reasoningEffort)
        assertEquals("test", request.purpose)
        assertEquals(mapOf("trace" to "enabled"), request.attributes)
        assertEquals(listOf("earlier", "hello"), request.messages.map { it.text })
    }

    @Test
    fun toolLoopFeedsResultBackToModel() = runBlocking {
        val chatService = ScriptedChatService(
            toolResponse("checking", "call-1", "read", """{"path":"a"}"""),
            textResponse("done")
        )
        var receivedContext: AgentRunContext? = null
        var receivedInput = ""
        val readTool = tool("read") { toolContext, call ->
            receivedContext = toolContext
            receivedInput = call.inputJson
            AgentToolResult(success = true, output = "contents")
        }
        val result = runtime(agent(), chatService, listOf(readTool)).run(
            AgentRunRequest(AgentKey("agent"), "read a", context)
        )

        assertTrue(result.isSuccess)
        assertEquals("done", result.output)
        assertEquals(context, receivedContext)
        assertEquals("""{"path":"a"}""", receivedInput)
        assertEquals(2, chatService.requests.size)
        val followUp = chatService.requests[1].messages
        val assistant = followUp[followUp.lastIndex - 1]
        val toolMessage = followUp.last()
        assertTrue(assistant.blocks.any { it is AgentChatBlock.ToolUse })
        val toolResult = toolMessage.blocks.single() as AgentChatBlock.ToolResult
        assertEquals("contents", toolResult.content)
        assertFalse(toolResult.isError)
        assertTrue(
            result.events.any {
                it.event is AgentEvent.ToolCompleted && it.event.success
            }
        )
    }

    @Test
    fun policyFiltersToolsAndRejectsCallsThatBypassModelContract() = runBlocking {
        val chatService = ScriptedChatService(
            toolResponse("", "call-1", "navigate", "{}"),
            textResponse("denied")
        )
        var navigateExecuted = false
        val readTool = tool("read") { _, _ -> AgentToolResult(success = true) }
        val navigateTool = tool("navigate") { _, _ ->
            navigateExecuted = true
            AgentToolResult(success = true)
        }
        val restricted = agent().copy(
            toolPolicy = AgentToolPolicy(allowed = setOf("read"))
        )
        val result = runtime(
            restricted,
            chatService,
            listOf(readTool, navigateTool)
        ).run(AgentRunRequest(AgentKey("agent"), "go", context))

        assertTrue(result.isSuccess)
        assertEquals(listOf("read"), chatService.requests.first().tools.map { it.name })
        assertFalse(navigateExecuted)
        val toolResult = chatService.requests[1].messages.last().blocks.single()
            as AgentChatBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals(
            "Tool is not allowed for this agent: navigate",
            toolResult.content
        )
    }

    @Test
    fun toolExceptionIsReturnedToModelWithoutCrashingRun() = runBlocking {
        val chatService = ScriptedChatService(
            toolResponse("", "call-1", "broken", "{}"),
            textResponse("recovered")
        )
        val broken = tool("broken") { _, _ -> error("tool failed") }
        val result = runtime(agent(), chatService, listOf(broken)).run(
            AgentRunRequest(AgentKey("agent"), "try", context)
        )

        assertTrue(result.isSuccess)
        val toolResult = chatService.requests[1].messages.last().blocks.single()
            as AgentChatBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals("tool failed", toolResult.content)
    }

    @Test
    fun maxToolRoundsReturnsFailureAfterExecutingLastRound() = runBlocking {
        val chatService = ScriptedChatService(
            toolResponse("", "call-1", "read", "{}")
        )
        val result = runtime(
            definition = agent(),
            chatService = chatService,
            tools = listOf(tool("read") { _, _ -> AgentToolResult(success = true) }),
            runtimeConfig = AgentRuntimeConfig(maxToolRounds = 1)
        ).run(AgentRunRequest(AgentKey("agent"), "loop", context))

        assertFalse(result.isSuccess)
        assertEquals("MAX_TOOL_ROUNDS", result.failure?.code)
        assertTrue(result.events.last().event is AgentEvent.Failed)
    }

    @Test
    fun modelFailureIsReturnedAsStructuredFailure() = runBlocking {
        val failure = IllegalStateException("model offline")
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                throw failure
            }
        }
        val result = runtime(agent(), chatService).run(
            AgentRunRequest(AgentKey("agent"), "hello", context)
        )

        assertFalse(result.isSuccess)
        assertEquals("MODEL_ERROR", result.failure?.code)
        assertEquals("model offline", result.failure?.message)
        assertTrue(result.events.last().event is AgentEvent.Failed)
    }

    @Test
    fun coroutineCancellationEmitsCancelledAndPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val events = mutableListOf<AgentEvent>()
        var cancellationObserved = false
        val job = launch {
            try {
                runtime(agent(), chatService).run(
                    AgentRunRequest(AgentKey("agent"), "wait", context),
                    AgentEventSink { events += it }
                )
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(cancellationObserved)
        assertTrue(events.last() is AgentEvent.Cancelled)
    }

    @Test
    fun toolCatalogRequiresUniqueNamesAndUsagePromptJoinsInstructions() = runBlocking {
        val duplicate = tool("read") { _, _ -> AgentToolResult(success = true) }
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolCatalog(listOf(duplicate, duplicate))
        }

        val chatService = ScriptedChatService(textResponse("ok"))
        val read = AgentTool(
            descriptor = AgentToolDescriptor(
                name = "read",
                description = "Read data",
                inputSchemaJson = "{}",
                usagePrompt = "Read before writing."
            ),
            handler = AgentToolHandler { _, _ -> AgentToolResult(success = true) }
        )
        runtime(
            definition = agent(
                instructions = AgentInstructionProvider { "Agent instructions." }
            ),
            chatService = chatService,
            tools = listOf(read)
        ).run(AgentRunRequest(AgentKey("agent"), "hello", context))

        assertEquals(
            "Agent instructions.\n\nRead before writing.",
            chatService.requests.single().system
        )
    }

    @Test
    fun chatResponseRejectsDuplicateToolCallIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentChatResponse(
                blocks = listOf(
                    toolUse("same", "first"),
                    toolUse("same", "second")
                )
            )
        }
    }

    private fun agent(
        key: String = "agent",
        instructions: AgentInstructionProvider = AgentInstructionProvider.Empty
    ): AgentDefinition = AgentDefinition(
        key = AgentKey(key),
        lane = AgentLane("default"),
        displayName = key,
        instructions = instructions
    )

    private fun tool(
        name: String,
        handler: AgentToolHandler
    ): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = name,
            description = "$name tool",
            inputSchemaJson = "{}"
        ),
        handler = handler
    )

    private fun runtime(
        definition: AgentDefinition,
        chatService: AgentChatService,
        tools: List<AgentTool> = emptyList(),
        runtimeConfig: AgentRuntimeConfig = AgentRuntimeConfig()
    ): DefaultAgentRuntime = DefaultAgentRuntime(
        catalog = AgentCatalog(listOf(definition)),
        toolCatalog = AgentToolCatalog(tools),
        chatService = chatService,
        config = runtimeConfig
    )

    private fun textResponse(
        text: String,
        stopReason: String? = null
    ): AgentChatResponse = AgentChatResponse(
        blocks = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(AgentChatBlock.Text(text))
        },
        stopReason = stopReason
    )

    private fun toolResponse(
        text: String,
        id: String,
        name: String,
        inputJson: String
    ): AgentChatResponse = AgentChatResponse(
        blocks = buildList {
            if (text.isNotEmpty()) add(AgentChatBlock.Text(text))
            add(toolUse(id, name, inputJson))
        }
    )

    private fun toolUse(
        id: String,
        name: String,
        inputJson: String = "{}"
    ): AgentChatBlock.ToolUse = AgentChatBlock.ToolUse(
        id = id,
        name = name,
        input = JSONObject(inputJson)
    )
}

private class ScriptedChatService(
    vararg responses: AgentChatResponse,
    private val stream: (AgentChatResponse, (String) -> Unit) -> Unit = { _, _ -> }
) : AgentChatService {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<AgentChatInput>()

    override suspend fun chat(request: AgentChatInput): AgentChatResponse =
        next(request)

    override suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse {
        val response = next(request)
        stream(response, onTextDelta)
        return response
    }

    private fun next(request: AgentChatInput): AgentChatResponse {
        requests += request
        return checkNotNull(responses.removeFirstOrNull()) {
            "No scripted response left"
        }
    }
}
