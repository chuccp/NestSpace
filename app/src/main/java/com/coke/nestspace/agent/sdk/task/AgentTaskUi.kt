package com.coke.nestspace.agent.sdk.task

enum class AgentTaskUiState(val api: String) {
    WAITING("waiting"),
    RUNNING("running"),
    PAUSED("paused"),
    NEEDS_ATTENTION("needs_attention"),
    CANCELLING("cancelling"),
    FINISHED("finished")
}

enum class AgentTaskPrimaryAction(val api: String) {
    NONE("none"),
    PAUSE("pause"),
    RESUME("resume"),
    HANDLE("handle"),
    RETRY_CLEANUP("retry_cleanup"),
    VIEW_RESULT("view_result")
}

data class AgentTaskControls(
    val uiState: AgentTaskUiState,
    val primaryAction: AgentTaskPrimaryAction,
    val canCancel: Boolean
)

/**
 * Keeps lifecycle states out of presentation code. Each task exposes at most
 * one primary action and one cancellation action.
 */
fun agentTaskControls(
    state: AgentTaskState,
    interruptible: Boolean = true
): AgentTaskControls = when (state) {
    AgentTaskState.QUEUED -> AgentTaskControls(
        AgentTaskUiState.WAITING,
        AgentTaskPrimaryAction.NONE,
        canCancel = true
    )
    AgentTaskState.PREPARING,
    AgentTaskState.RUNNING,
    AgentTaskState.FINALIZING -> AgentTaskControls(
        AgentTaskUiState.RUNNING,
        if (interruptible) AgentTaskPrimaryAction.PAUSE else AgentTaskPrimaryAction.NONE,
        canCancel = interruptible
    )
    AgentTaskState.PAUSED -> AgentTaskControls(
        AgentTaskUiState.PAUSED,
        AgentTaskPrimaryAction.RESUME,
        canCancel = true
    )
    AgentTaskState.WAITING_USER -> AgentTaskControls(
        AgentTaskUiState.NEEDS_ATTENTION,
        AgentTaskPrimaryAction.HANDLE,
        canCancel = true
    )
    AgentTaskState.CANCELLING -> AgentTaskControls(
        AgentTaskUiState.CANCELLING,
        AgentTaskPrimaryAction.NONE,
        canCancel = false
    )
    AgentTaskState.SUCCEEDED,
    AgentTaskState.FAILED,
    AgentTaskState.CANCELLED -> AgentTaskControls(
        AgentTaskUiState.FINISHED,
        AgentTaskPrimaryAction.VIEW_RESULT,
        canCancel = false
    )
}
