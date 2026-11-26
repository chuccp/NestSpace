package com.coke.nestspace.agent.sdk

/**
 * Records usage for every completed model call, including provider retries,
 * summaries and probes when those all pass through the same service.
 */
class RecordingAgentChatService(
    private val delegate: AgentChatService,
    private val onUsage: (AgentChatUsage, String) -> Unit
) : AgentChatService {
    override val supportsVision: Boolean get() = delegate.supportsVision

    override fun supportsVision(providerId: String?): Boolean =
        delegate.supportsVision(providerId)

    override suspend fun chat(request: AgentChatInput): AgentChatResponse =
        record(request, delegate.chat(request))

    override suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse = record(
        request,
        delegate.streamChat(request, onTextDelta)
    )

    private fun record(
        request: AgentChatInput,
        response: AgentChatResponse
    ): AgentChatResponse {
        onUsage(
            response.usage ?: AgentChatUsage(
                inputTokens = TokenEstimator.estimateRequest(request),
                outputTokens = TokenEstimator.estimateResponse(response),
                estimated = true
            ),
            request.purpose
        )
        return response
    }
}
