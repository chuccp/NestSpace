package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatServiceRegistryTest {
    @Test
    fun providerIdRoutesToSelectedServiceAndFallsBackToDefault() = runBlocking {
        val first = RegistryChatService("first")
        val second = RegistryChatService("second")
        val registry = AgentChatServiceRegistry(
            listOf("first" to first, "second" to second)
        )

        val defaultResponse = registry.chat(input())
        val selectedResponse = registry.chat(input(providerId = "second"))

        assertEquals("first", defaultResponse.text)
        assertEquals("second", selectedResponse.text)
        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
    }

    @Test
    fun defaultCanBeChangedAtRuntime() = runBlocking {
        val first = RegistryChatService("first")
        val second = RegistryChatService("second")
        val registry = AgentChatServiceRegistry(
            listOf("first" to first, "second" to second)
        )

        registry.setDefault("second")

        assertEquals("second", registry.chat(input()).text)
    }

    @Test
    fun providerSpecificVisionCapabilityIsUsed() {
        val registry = AgentChatServiceRegistry(
            listOf(
                "text" to RegistryChatService("text"),
                "vision" to RegistryChatService("vision", supportsVision = true)
            )
        )

        assertFalse(registry.supportsVision)
        assertFalse(registry.supportsVision("text"))
        assertTrue(registry.supportsVision("vision"))
        assertFalse(registry.supportsVision("missing"))
    }

    @Test
    fun unknownProviderFailsClearly() = runBlocking {
        val registry = AgentChatServiceRegistry(
            listOf("known" to RegistryChatService("known"))
        )

        val error = runCatching {
            registry.chat(input(providerId = "missing"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Unknown Chat service: missing", error?.message)
    }

    private fun input(providerId: String? = null): AgentChatInput =
        AgentChatInput(
            model = "model",
            system = "",
            messages = emptyList(),
            tools = emptyList(),
            maxTokens = 100,
            temperature = 0.0,
            providerId = providerId
        )
}

private class RegistryChatService(
    private val label: String,
    override val supportsVision: Boolean = false
) : AgentChatService {
    val requests = mutableListOf<AgentChatInput>()

    override suspend fun chat(request: AgentChatInput): AgentChatResponse {
        requests += request
        return AgentChatResponse(
            blocks = listOf(AgentChatBlock.Text(label))
        )
    }
}
