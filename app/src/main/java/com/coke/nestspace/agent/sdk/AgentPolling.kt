package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.delay

data class AgentPollingPolicy(
    val maxChecks: Int = 10,
    val intervalMillis: Long = 2_000L
) {
    init {
        require(maxChecks > 0) { "maxChecks must be greater than zero" }
        require(intervalMillis >= 0) { "intervalMillis must not be negative" }
    }
}

data class AgentPollResult<T : Any>(
    val value: T?,
    val checks: Int,
    val timedOut: Boolean
)

suspend fun <T : Any> pollAgentState(
    policy: AgentPollingPolicy,
    probe: suspend () -> T?,
    isComplete: (T) -> Boolean
): AgentPollResult<T> {
    var last: T? = null
    repeat(policy.maxChecks) { index ->
        val current = probe()
        if (current != null) {
            last = current
            if (isComplete(current)) {
                return AgentPollResult(
                    value = current,
                    checks = index + 1,
                    timedOut = false
                )
            }
        }
        if (index < policy.maxChecks - 1) {
            delay(policy.intervalMillis)
        }
    }
    return AgentPollResult(
        value = last,
        checks = policy.maxChecks,
        timedOut = true
    )
}
