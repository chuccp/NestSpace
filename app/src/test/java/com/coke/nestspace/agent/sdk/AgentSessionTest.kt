package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionTest {
    private val context = AgentRunContext(sessionId = "session-1")

    @Test
    fun subsequentTurnReceivesCommittedHistory() = runBlocking {
        val chatService = SessionChatService { request ->
            if (request.messages.last().text == "first") {
                textResponse("first answer")
            } else {
                textResponse("second answer")
            }
        }
        val session = agent(chatService).session(context)

        assertTrue(session.run("first").isSuccess)
        assertTrue(session.run("second").isSuccess)

        assertEquals(2, chatService.requests.size)
        assertEquals(
            listOf("first", "first answer", "second"),
            chatService.requests[1].messages.map(AgentChatMessage::text)
        )
    }

    @Test
    fun failedTurnIsStoredAsRunButDoesNotPolluteHistory() = runBlocking {
        val chatService = SessionChatService { request ->
            if (request.messages.last().text == "fail") {
                error("HTTP 401")
            } else {
                textResponse("recovered")
            }
        }
        val historyStore = InMemoryAgentHistoryStore()
        val runStore = InMemoryAgentRunStore()
        val session = agent(chatService).session(
            context = context,
            historyStore = historyStore,
            runStore = runStore
        )

        val failed = session.run("fail")
        val succeeded = session.run("retry")

        assertFalse(failed.isSuccess)
        assertTrue(succeeded.isSuccess)
        assertEquals(listOf("retry"), chatService.requests[1].messages.map { it.text })
        val runs = runStore.load(context.sessionId)
        assertEquals(2, runs.size)
        assertFalse(runs.first().isSuccess)
        assertTrue(runs.last().isSuccess)
    }

    @Test
    fun concurrentTurnsAreSerialized() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val chatService = SessionChatService { request ->
            if (request.messages.last().text == "first") {
                firstStarted.complete(Unit)
                releaseFirst.await()
                textResponse("first answer")
            } else {
                textResponse("second answer")
            }
        }
        val session = agent(chatService).session(context)

        val first = async { session.run("first") }
        firstStarted.await()
        val second = async { session.run("second") }
        delay(40)
        assertEquals(1, chatService.requests.size)

        releaseFirst.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(
            listOf("first", "first answer", "second"),
            chatService.requests[1].messages.map(AgentChatMessage::text)
        )
    }

    @Test
    fun runStoreKeepsBoundedRecentRuns() = runBlocking {
        val store = InMemoryAgentRunStore()
        repeat(3) { index ->
            store.save(
                "session",
                AgentRunResult(
                    output = index.toString(),
                    messages = emptyList(),
                    events = emptyList()
                )
            )
        }

        assertEquals(
            listOf("1", "2"),
            store.load("session", limit = 2).map(AgentRunResult::output)
        )
    }

    private fun agent(chatService: AgentChatService): RunnableAgent = agentSdk {
        key("agent")
        displayName("Agent")
        chatService(chatService)
        model("test-model")
    }

    private fun textResponse(text: String): AgentChatResponse =
        AgentChatResponse(blocks = listOf(AgentChatBlock.Text(text)))
}

private class SessionChatService(
    private val respond: suspend (AgentChatInput) -> AgentChatResponse
) : AgentChatService {
    val requests = mutableListOf<AgentChatInput>()

    override suspend fun chat(request: AgentChatInput): AgentChatResponse {
        requests += request
        return respond(request)
    }
}
