package com.coke.nestspace.agent

import com.coke.nestspace.agent.task.OperationTaskRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTaskUiTest {

    @Test
    fun internalStatesCollapseToSixUiStates() {
        assertEquals(
            AssistantTaskUiState.WAITING,
            assistantTaskControls(OperationTaskRegistry.State.QUEUED).uiState
        )
        assertEquals(
            AssistantTaskUiState.RUNNING,
            assistantTaskControls(OperationTaskRegistry.State.PREPARING).uiState
        )
        assertEquals(
            AssistantTaskUiState.RUNNING,
            assistantTaskControls(OperationTaskRegistry.State.RUNNING).uiState
        )
        assertEquals(
            AssistantTaskUiState.RUNNING,
            assistantTaskControls(OperationTaskRegistry.State.FINALIZING).uiState
        )
        assertEquals(
            AssistantTaskUiState.PAUSED,
            assistantTaskControls(OperationTaskRegistry.State.PAUSED).uiState
        )
        assertEquals(
            AssistantTaskUiState.NEEDS_ATTENTION,
            assistantTaskControls(OperationTaskRegistry.State.WAITING_USER).uiState
        )
        assertEquals(
            AssistantTaskUiState.CANCELLING,
            assistantTaskControls(OperationTaskRegistry.State.CANCELLING).uiState
        )
        assertEquals(
            AssistantTaskUiState.FINISHED,
            assistantTaskControls(OperationTaskRegistry.State.SUCCEEDED).uiState
        )
    }

    @Test
    fun cancellingLocksAllActionsUntilStateIsConfirmed() {
        val controls = assistantTaskControls(OperationTaskRegistry.State.CANCELLING)

        assertEquals(AssistantTaskPrimaryAction.NONE, controls.primaryAction)
        assertFalse(controls.canCancel)
    }

    @Test
    fun runningTaskHasOnePrimaryActionAndCancel() {
        val controls = assistantTaskControls(OperationTaskRegistry.State.RUNNING)

        assertEquals(AssistantTaskPrimaryAction.PAUSE, controls.primaryAction)
        assertTrue(controls.canCancel)
    }
}
