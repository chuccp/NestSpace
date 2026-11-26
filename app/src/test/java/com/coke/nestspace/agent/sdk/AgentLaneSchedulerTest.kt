package com.coke.nestspace.agent.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLaneSchedulerTest {
    private val firstLane = AgentLane("first")
    private val secondLane = AgentLane("second")

    @Test
    fun eachLaneRunsOneTaskAndKeepsIndependentFifo() {
        val scheduler = AgentLaneScheduler()
        scheduler.enqueue(firstLane, "a")
        scheduler.enqueue(firstLane, "b")
        scheduler.enqueue(secondLane, "c")

        assertEquals("a", scheduler.takeNext(firstLane))
        assertEquals("c", scheduler.takeNext(secondLane))
        assertNull(scheduler.takeNext(firstLane))
        assertEquals(1, scheduler.queuePosition("b"))

        assertTrue(scheduler.clearActive(firstLane, "a"))
        assertEquals("b", scheduler.takeNext(firstLane))
    }

    @Test
    fun removingTasksUpdatesBothQueuedAndActiveState() {
        val scheduler = AgentLaneScheduler()
        scheduler.enqueue(firstLane, "a")
        scheduler.enqueue(firstLane, "b")
        assertEquals("a", scheduler.takeNext(firstLane))

        assertTrue(scheduler.remove("a"))
        assertTrue(scheduler.removeQueued("b"))
        assertTrue(scheduler.isEmpty())
        assertFalse(scheduler.isActive("a"))
    }
}
