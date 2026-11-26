package com.coke.nestspace.agent

import com.coke.nestspace.agent.sdk.hook.AgentConfirmationHook
import com.coke.nestspace.agent.sdk.hook.AgentConfirmationRequest

/**
 * Android implementation of the SDK's host-independent confirmation contract.
 */
object AppAgentConfirmationHook : AgentConfirmationHook {
    override suspend fun confirm(request: AgentConfirmationRequest): Boolean =
        UserGateManager.askConfirmation(
            title = request.title,
            detail = request.detail,
            timeoutMs = request.timeoutMillis,
            confirmLabel = request.confirmLabel,
            dismissLabel = request.dismissLabel
        )
}
