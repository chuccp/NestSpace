package com.coke.nestspace.agent.sdk.hook

data class AgentConfirmationRequest(
    val title: String,
    val detail: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val timeoutMillis: Long
)

/**
 * Pre-execution hook used by AgentToolExecutor when a read or action policy
 * requires an explicit user decision.
 */
fun interface AgentConfirmationHook {
    suspend fun confirm(request: AgentConfirmationRequest): Boolean
}
