package com.coke.nestspace.agent

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.task.OperationTaskRegistry

enum class AssistantTaskUiState(val api: String) {
    WAITING("waiting"),
    RUNNING("running"),
    PAUSED("paused"),
    NEEDS_ATTENTION("needs_attention"),
    CANCELLING("cancelling"),
    FINISHED("finished")
}

enum class AssistantTaskPrimaryAction(val api: String) {
    NONE("none"),
    PAUSE("pause"),
    RESUME("resume"),
    HANDLE("handle"),
    RETRY_CLEANUP("retry_cleanup"),
    VIEW_RESULT("view_result")
}

data class AssistantTaskControls(
    val uiState: AssistantTaskUiState,
    val primaryAction: AssistantTaskPrimaryAction,
    val canCancel: Boolean
)

/**
 * Keeps detailed scheduler states out of the Compose layer. The task card
 * should have at most one primary action and one cancel action.
 */
fun assistantTaskControls(
    state: OperationTaskRegistry.State,
    interruptible: Boolean = true
): AssistantTaskControls = when (state) {
    OperationTaskRegistry.State.QUEUED -> AssistantTaskControls(
        AssistantTaskUiState.WAITING,
        AssistantTaskPrimaryAction.NONE,
        canCancel = true
    )
    OperationTaskRegistry.State.PREPARING,
    OperationTaskRegistry.State.RUNNING,
    OperationTaskRegistry.State.FINALIZING -> AssistantTaskControls(
        AssistantTaskUiState.RUNNING,
        if (interruptible) AssistantTaskPrimaryAction.PAUSE else AssistantTaskPrimaryAction.NONE,
        canCancel = interruptible
    )
    OperationTaskRegistry.State.PAUSED -> AssistantTaskControls(
        AssistantTaskUiState.PAUSED,
        AssistantTaskPrimaryAction.RESUME,
        canCancel = true
    )
    OperationTaskRegistry.State.WAITING_USER -> AssistantTaskControls(
        AssistantTaskUiState.NEEDS_ATTENTION,
        AssistantTaskPrimaryAction.HANDLE,
        canCancel = true
    )
    OperationTaskRegistry.State.CANCELLING -> AssistantTaskControls(
        AssistantTaskUiState.CANCELLING,
        AssistantTaskPrimaryAction.NONE,
        canCancel = false
    )
    OperationTaskRegistry.State.SUCCEEDED,
    OperationTaskRegistry.State.FAILED,
    OperationTaskRegistry.State.CANCELLED -> AssistantTaskControls(
        AssistantTaskUiState.FINISHED,
        AssistantTaskPrimaryAction.VIEW_RESULT,
        canCancel = false
    )
}

/**
 * Worker goals are model-generated and can contain execution notes. Keep the
 * compact task surfaces focused on the outcome the user asked for.
 */
internal fun assistantTaskGoalForDisplay(goal: String): String {
    val clean = goal.trim()
    if (clean.isBlank()) return clean
    val lower = clean.lowercase()
    val outcomeHint = clean.contains("提醒") ||
        clean.contains("告诉用户") ||
        lower.contains("remind") ||
        lower.contains("tell the user") ||
        lower.contains("report to the user")
    if (!outcomeHint) return clean
    val quoted = Regex("[“\"]([^”\"\\n]{2,120})[”\"]")
        .find(clean)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return clean
    return if (AppLanguage.isChinese()) {
        "提醒你：$quoted"
    } else {
        "Reminder: $quoted"
    }
}
