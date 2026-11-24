package com.coke.nestspace.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerUiSlotResolverTest {

    @Test
    fun focusedContainerWinsOverStaleLastAllocation() {
        assertEquals(
            1,
            chooseContainerUiSlot(
                focusedSlot = 1,
                mirroredSlot = null,
                foregroundSlot = 2,
                lastSlot = 4,
                liveSlots = setOf(0, 1, 2, 3, 4)
            )
        )
    }

    @Test
    fun mirroredContainerWinsWhenHostTaskIsBehindAnotherActivity() {
        assertEquals(
            5,
            chooseContainerUiSlot(
                focusedSlot = null,
                mirroredSlot = 5,
                foregroundSlot = 3,
                lastSlot = 4,
                liveSlots = setOf(3, 4, 5)
            )
        )
    }

    @Test
    fun deadCandidatesAreIgnored() {
        assertEquals(
            2,
            chooseContainerUiSlot(
                focusedSlot = 1,
                mirroredSlot = 5,
                foregroundSlot = 2,
                lastSlot = 4,
                liveSlots = setOf(2, 4)
            )
        )
    }

    @Test
    fun noLiveContainerReturnsNull() {
        assertNull(
            chooseContainerUiSlot(
                focusedSlot = 1,
                mirroredSlot = null,
                foregroundSlot = 2,
                lastSlot = 4,
                liveSlots = emptySet()
            )
        )
    }
}
