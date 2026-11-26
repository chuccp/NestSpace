package com.coke.nestspace.agent

import com.coke.nestspace.agent.task.OperationTaskRegistry
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWorkerContextTest {

    @Test
    fun noWorkerTasksProducesNoPromptBlock() {
        assertNull(AssistantWorkerContext.build(emptyList()))
    }

    @Test
    fun activeWorkerProgressIsInjectedWithRevision() {
        val context = AssistantWorkerContext.build(
            listOf(
                task(
                    id = "task-active",
                    state = OperationTaskRegistry.State.RUNNING,
                    progress = "Running ui_capture\nchecking screen",
                    progressRevision = 7,
                    idleMilliseconds = 1_250
                )
            )
        )

        requireNotNull(context)
        assertTrue(context.contains("task-active"))
        assertTrue(context.contains("state: running"))
        assertTrue(context.contains("progress_revision: 7"))
        assertTrue(context.contains("idle_ms: 1250"))
        assertTrue(context.contains("Running ui_capture checking screen"))
        assertTrue(
            context.contains("untrusted worker data") ||
                context.contains("不可信的 Worker 数据")
        )
    }

    @Test
    fun recentTerminalResultIsAvailableOnTheNextUserTurn() {
        val context = AssistantWorkerContext.build(
            listOf(
                task(
                    id = "task-finished",
                    state = OperationTaskRegistry.State.SUCCEEDED,
                    progress = "completed",
                    result = "The requested page was opened"
                )
            )
        )

        requireNotNull(context)
        assertTrue(context.contains("task-finished"))
        assertTrue(context.contains("state: succeeded"))
        assertTrue(context.contains("result: The requested page was opened"))
        assertFalse(context.contains("error:"))
    }

    private fun task(
        id: String,
        state: OperationTaskRegistry.State,
        progress: String,
        progressRevision: Long = 1,
        idleMilliseconds: Long = 0,
        result: String? = null
    ) = AssistantTaskCoordinator.TaskView(
        id = id,
        goal = "Open the requested page",
        source = "assistant",
        requestId = null,
        displayMode = ContainerDisplayMode.BACKGROUND,
        state = state,
        uiState = assistantTaskControls(state).uiState,
        primaryAction = assistantTaskControls(state).primaryAction,
        canCancel = !state.terminal,
        queuePosition = if (state == OperationTaskRegistry.State.RUNNING) 0 else -1,
        progress = progress,
        stateRevision = 3,
        progressRevision = progressRevision,
        idleMilliseconds = idleMilliseconds,
        result = result,
        error = null,
        createdAt = 1,
        startedAt = 2,
        finishedAt = if (state.terminal) 3 else 0,
        updatedAt = 3
    )
}
