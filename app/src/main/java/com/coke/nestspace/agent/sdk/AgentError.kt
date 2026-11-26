package com.coke.nestspace.agent.sdk

enum class AgentFailureKind {
    AUTHENTICATION,
    RATE_LIMIT,
    TIMEOUT,
    CONTEXT_LENGTH,
    INVALID_REQUEST,
    PROVIDER,
    CONFIGURATION,
    TOOL,
    UNKNOWN
}

fun classifyAgentFailure(error: Throwable): AgentFailureKind {
    val message = buildString {
        append(error.message.orEmpty())
        append(' ')
        append(error.javaClass.simpleName)
    }.lowercase()
    return when {
        listOf("401", "403", "unauthorized", "forbidden", "authentication", "api key")
            .any(message::contains) -> AgentFailureKind.AUTHENTICATION
        listOf("429", "rate limit", "too many requests").any(message::contains) ->
            AgentFailureKind.RATE_LIMIT
        listOf("timeout", "timed out", "time out").any(message::contains) ->
            AgentFailureKind.TIMEOUT
        listOf("context length", "context window", "max tokens", "too many tokens")
            .any(message::contains) -> AgentFailureKind.CONTEXT_LENGTH
        listOf("400", "bad request", "invalid request").any(message::contains) ->
            AgentFailureKind.INVALID_REQUEST
        listOf("500", "502", "503", "504", "provider", "server error")
            .any(message::contains) -> AgentFailureKind.PROVIDER
        else -> AgentFailureKind.UNKNOWN
    }
}
