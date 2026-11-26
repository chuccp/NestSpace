package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigDocumentTest {
    @Test
    fun declarativeConfigRoundTripsWithoutExecutableDependencies() {
        val document = AgentConfigDocument(
            key = "assistant",
            displayName = "Assistant",
            lane = "interactive",
            instructions = "Be concise.",
            features = AgentFeatureConfig(
                allowedTools = setOf("time", "read"),
                deniedTools = setOf("write"),
                allowedCapabilities = setOf("memory"),
                deniedCapabilities = setOf("skills")
            ),
            runtime = AgentConfigDocument.Runtime(
                maxToolRounds = 4,
                contextBudgetTokens = 16_000,
                maxParallelTools = 2,
                runTimeoutMillis = 30_000
            ),
            chat = AgentConfigDocument.Chat(
                providerId = "provider-a",
                model = "model-a",
                maxOutputTokens = 4_096,
                temperature = 0.2,
                reasoningEffort = AgentReasoningEffort.HIGH,
                purpose = "assistant",
                attributes = mapOf("trace" to "on")
            )
        )

        val restored = AgentConfigDocument.fromJson(document.toJson())

        assertEquals(document, restored)
        assertTrue(restored.toJson().contains("\"version\": 1"))
    }

    @Test
    fun unsupportedVersionFailsBeforeBuildingAnAgent() {
        val json = """{"version": 99, "key": "assistant"}"""

        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentConfigDocument.fromJson(json)
        }

        assertTrue(error.message.orEmpty().contains("99"))
    }

    @Test
    fun builderAppliesConfigurationAndFiltersExplicitTools() = runBlocking {
        val chatService = ConfigChatService()
        val allowed = testTool("time")
        val denied = testTool("write")
        val agent = agentSdk {
            config(
                AgentConfigDocument(
                    key = "configured",
                    instructions = "Configured instructions.",
                    features = AgentFeatureConfig(
                        allowedTools = setOf("time"),
                        deniedTools = setOf("write")
                    ),
                    runtime = AgentConfigDocument.Runtime(maxParallelTools = 2),
                    chat = AgentConfigDocument.Chat(
                        providerId = "provider-a",
                        model = "model-a"
                    )
                )
            )
            chatService(chatService)
            tool(allowed)
            tool(denied)
        }

        val result = agent.run("hello")

        assertTrue(result.isSuccess)
        val request = chatService.requests.single()
        assertEquals("configured", agent.definition.key.value)
        assertEquals("Configured instructions.", request.system)
        assertEquals("provider-a", request.providerId)
        assertEquals("model-a", request.model)
        assertEquals(listOf("time"), request.tools.map(AgentChatTool::name))
    }

    private fun testTool(name: String): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(name, "$name tool", "{}"),
        handler = AgentToolHandler { _, _ -> AgentToolResult(success = true) }
    )
}

private class ConfigChatService : AgentChatService {
    val requests = mutableListOf<AgentChatInput>()

    override suspend fun chat(request: AgentChatInput): AgentChatResponse {
        requests += request
        return AgentChatResponse(
            blocks = listOf(AgentChatBlock.Text("ok"))
        )
    }
}
