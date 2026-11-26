package com.coke.nestspace.agent.sdk.hook

import com.coke.nestspace.agent.sdk.AgentChatInput
import com.coke.nestspace.agent.sdk.AgentChatResponse
import com.coke.nestspace.agent.sdk.AgentFailure
import com.coke.nestspace.agent.sdk.AgentRunRequest
import com.coke.nestspace.agent.sdk.AgentRunResult

sealed interface AgentModelHookDecision {
    data class Proceed(
        val request: AgentChatInput
    ) : AgentModelHookDecision

    data class Respond(
        val response: AgentChatResponse
    ) : AgentModelHookDecision

    data class Stop(
        val failure: AgentFailure
    ) : AgentModelHookDecision
}

/**
 * Runtime lifecycle and model request hooks.
 *
 * beforeModel/afterModel may rewrite the request and response. completed,
 * failed and cancelled are observation-only callbacks.
 */
interface AgentRunHook {
    suspend fun beforeRun(request: AgentRunRequest) = Unit

    suspend fun beforeModel(request: AgentChatInput): AgentChatInput = request

    suspend fun beforeModelDecision(
        request: AgentChatInput
    ): AgentModelHookDecision = AgentModelHookDecision.Proceed(
        beforeModel(request)
    )

    suspend fun afterModel(
        request: AgentChatInput,
        response: AgentChatResponse
    ): AgentChatResponse = response

    suspend fun onCompleted(request: AgentRunRequest, result: AgentRunResult) = Unit

    suspend fun onFailed(request: AgentRunRequest, failure: AgentFailure) = Unit

    suspend fun onCancelled(request: AgentRunRequest, reason: String) = Unit
}
