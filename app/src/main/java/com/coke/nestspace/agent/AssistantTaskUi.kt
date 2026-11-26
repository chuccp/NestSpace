package com.coke.nestspace.agent

import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.agent.sdk.task.AgentTaskControls
import com.coke.nestspace.agent.sdk.task.AgentTaskPrimaryAction
import com.coke.nestspace.agent.sdk.task.AgentTaskState
import com.coke.nestspace.agent.sdk.task.AgentTaskUiState
import com.coke.nestspace.agent.sdk.task.agentTaskControls
import com.coke.nestspace.agent.task.OperationTaskRegistry

typealias AssistantTaskUiState = AgentTaskUiState
typealias AssistantTaskPrimaryAction = AgentTaskPrimaryAction
typealias AssistantTaskControls = AgentTaskControls

/**
 * Keeps detailed scheduler states out of the Compose layer. The task card
 * should have at most one primary action and one cancel action.
 */
fun assistantTaskControls(
    state: OperationTaskRegistry.State,
    interruptible: Boolean = true
): AssistantTaskControls = agentTaskControls(
    state = AgentTaskState.valueOf(state.name),
    interruptible = interruptible
)

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
