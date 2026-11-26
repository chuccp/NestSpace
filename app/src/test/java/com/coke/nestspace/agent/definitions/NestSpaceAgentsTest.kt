package com.coke.nestspace.agent.definitions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NestSpaceAgentsTest {
    @Test
    fun agentToolPoliciesKeepTopLevelAgentsIsolated() {
        assertFalse(NestSpaceAgents.main.toolPolicy.permits("screen_read"))
        assertFalse(NestSpaceAgents.main.toolPolicy.permits("screen_highlight"))
        assertTrue(NestSpaceAgents.main.toolPolicy.permits("spawn_agent"))

        assertTrue(NestSpaceAgents.screenReader.toolPolicy.permits("screen_read"))
        assertTrue(NestSpaceAgents.screenReader.toolPolicy.permits("screen_highlight"))
        assertTrue(NestSpaceAgents.screenReader.toolPolicy.permits("screen_suggest"))
        assertTrue(NestSpaceAgents.screenReader.toolPolicy.permits("spawn_agent"))
        assertFalse(NestSpaceAgents.screenReader.toolPolicy.permits("ui_click"))

        assertFalse(NestSpaceAgents.worker.toolPolicy.permits("spawn_agent"))
        assertFalse(NestSpaceAgents.worker.toolPolicy.permits("screen_read"))
        assertTrue(NestSpaceAgents.worker.toolPolicy.permits("ui_click"))
    }

    @Test
    fun topLevelAgentsUseSeparateLanes() {
        assertTrue(NestSpaceAgents.main.lane != NestSpaceAgents.worker.lane)
        assertTrue(NestSpaceAgents.main.lane != NestSpaceAgents.screenReader.lane)
        assertTrue(NestSpaceAgents.worker.lane != NestSpaceAgents.screenReader.lane)
    }
}
