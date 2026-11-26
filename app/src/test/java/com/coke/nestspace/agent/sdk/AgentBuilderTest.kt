package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBuilderTest {
    @Test
    fun simpleConfigurationBuildsRunnableAgent() = runBlocking {
        var capturedRequest: AgentChatInput? = null
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                capturedRequest = request
                return AgentChatResponse(
                    blocks = listOf(AgentChatBlock.Text("hello"))
                )
            }
        }
        val agent = agentSdk {
            key("assistant")
            displayName("Assistant")
            instructions("Be concise.")
            chatService(chatService)
            model("model-a")
            maxOutputTokens(512)
            capability(
                object : AgentCapability {
                    override val id = "sample"
                    override val description = "Sample"
                    override fun instructions(): String = "Use the sample capability."
                    override fun tools(): List<AgentTool> = listOf(
                        AgentTool(
                            AgentToolDescriptor("sample", "Sample", "{}"),
                            AgentToolHandler { _, _ -> AgentToolResult(true) }
                        )
                    )
                }
            )
        }
        val result = agent.run("hi")

        assertTrue(result.isSuccess)
        assertEquals("hello", result.output)
        assertEquals("model-a", capturedRequest?.model)
        assertEquals(512, capturedRequest?.maxTokens)
        assertEquals(listOf("sample"), capturedRequest?.tools?.map { it.name })
        assertTrue(capturedRequest?.system.orEmpty().contains("Use the sample capability."))
    }

    @Test
    fun buildRequiresChatService() {
        try {
            agentSdk {
                key("assistant")
            }
            error("Expected build to fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("AgentChatService"))
        }
    }

    @Test
    fun configurationCanDisableToolsAndCapabilities() = runBlocking {
        var captured: AgentChatInput? = null
        val service = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                captured = request
                return AgentChatResponse(listOf(AgentChatBlock.Text("ok")))
            }
        }
        fun capability(id: String) = object : AgentCapability {
            override val id: String = id
            override val description: String = id
            override fun instructions(): String = "instruction-$id"
            override fun tools(): List<AgentTool> = listOf(
                AgentTool(
                    AgentToolDescriptor("${id}_tool", id, "{}"),
                    AgentToolHandler { _, _ -> AgentToolResult(true) }
                )
            )
        }
        val agent = agentSdk {
            key("assistant")
            chatService(service)
            features(
                AgentFeatureConfig(
                    deniedTools = setOf("disabled_tool"),
                    deniedCapabilities = setOf("skills")
                )
            )
            tool(
                AgentTool(
                    AgentToolDescriptor("disabled_tool", "Disabled", "{}"),
                    AgentToolHandler { _, _ -> AgentToolResult(true) }
                )
            )
            capability(capability("memory"))
            capability(capability("skills"))
        }
        agent.run("hello")

        assertEquals(
            listOf("memory_tool"),
            captured?.tools?.map { it.name }
        )
        assertEquals("instruction-memory", captured?.system)
    }
}
