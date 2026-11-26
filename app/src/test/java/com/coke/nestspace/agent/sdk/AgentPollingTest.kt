package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPollingTest {

    @Test
    fun stopsAfterConditionBecomesTrue() = runBlocking {
        var probeCount = 0
        val result = pollAgentState(
            policy = AgentPollingPolicy(maxChecks = 10, intervalMillis = 0L),
            probe = {
                probeCount += 1
                probeCount
            },
            isComplete = { it >= 3 }
        )

        assertEquals(3, result.value)
        assertEquals(3, result.checks)
        assertFalse(result.timedOut)
    }

    @Test
    fun exhaustingChecksIsTimeout() = runBlocking {
        val result = pollAgentState(
            policy = AgentPollingPolicy(maxChecks = 4, intervalMillis = 0L),
            probe = { "downloading" },
            isComplete = { it == "done" }
        )

        assertEquals("downloading", result.value)
        assertEquals(4, result.checks)
        assertTrue(result.timedOut)
    }

    @Test
    fun missingProbeValueDoesNotBecomeSuccess() = runBlocking {
        val result = pollAgentState<String>(
            policy = AgentPollingPolicy(maxChecks = 2, intervalMillis = 0L),
            probe = { null },
            isComplete = { true }
        )

        assertEquals(null, result.value)
        assertEquals(2, result.checks)
        assertTrue(result.timedOut)
    }
}
