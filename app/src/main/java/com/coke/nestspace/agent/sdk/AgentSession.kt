package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AgentHistoryStore {
    suspend fun load(sessionId: String): List<AgentChatMessage>
    suspend fun save(sessionId: String, messages: List<AgentChatMessage>)
}

class InMemoryAgentHistoryStore : AgentHistoryStore {
    private val lock = Any()
    private val sessions = mutableMapOf<String, List<AgentChatMessage>>()

    override suspend fun load(sessionId: String): List<AgentChatMessage> =
        synchronized(lock) { sessions[sessionId].orEmpty() }

    override suspend fun save(
        sessionId: String,
        messages: List<AgentChatMessage>
    ) {
        synchronized(lock) {
            sessions[sessionId] = messages.toList()
        }
    }
}

interface AgentRunStore {
    suspend fun save(sessionId: String, result: AgentRunResult)
    suspend fun load(sessionId: String, limit: Int = 20): List<AgentRunResult>
}

class InMemoryAgentRunStore : AgentRunStore {
    private val lock = Any()
    private val runs = mutableMapOf<String, List<AgentRunResult>>()

    override suspend fun save(sessionId: String, result: AgentRunResult) {
        synchronized(lock) {
            runs[sessionId] = runs[sessionId].orEmpty() + result
        }
    }

    override suspend fun load(
        sessionId: String,
        limit: Int
    ): List<AgentRunResult> = synchronized(lock) {
        runs[sessionId].orEmpty().takeLast(limit.coerceAtLeast(1))
    }
}

class AgentSession(
    val agent: RunnableAgent,
    val context: AgentRunContext,
    private val historyStore: AgentHistoryStore = InMemoryAgentHistoryStore(),
    private val runStore: AgentRunStore? = null
) {
    private val turnLock = Mutex()

    suspend fun run(
        input: String,
        events: AgentEventSink = AgentEventSink {}
    ): AgentRunResult = turnLock.withLock {
        val history = historyStore.load(context.sessionId)
        val result = agent.run(
            input = input,
            context = context,
            history = history,
            events = events
        )
        runStore?.save(context.sessionId, result)
        if (result.isSuccess) {
            historyStore.save(context.sessionId, result.messages)
        }
        result
    }
}
