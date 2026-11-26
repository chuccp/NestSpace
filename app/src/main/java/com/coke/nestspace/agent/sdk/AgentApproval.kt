package com.coke.nestspace.agent.sdk

enum class AgentApprovalLevel(val storageValue: String) {
    STRICT("strict"),
    STANDARD("standard"),
    LOOSE("loose"),
    CUSTOM("custom"),
    DEVELOPER("developer");

    companion object {
        fun from(value: String?): AgentApprovalLevel =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

enum class AgentApprovalCategory(val api: String) {
    INSTALL("install"),
    PERMISSION("permission"),
    DOWNLOAD("download"),
    RISKY("risky"),
    MONEY("money"),
    EXTERNAL("external"),
    PLUGIN("plugin");

    companion object {
        fun from(value: String?): AgentApprovalCategory? =
            entries.firstOrNull { it.api == value?.trim()?.lowercase() }
    }
}

data class AgentApprovalSettings(
    val level: AgentApprovalLevel,
    val customCategories: Set<AgentApprovalCategory> = emptySet()
) {
    fun enabledCategories(): Set<AgentApprovalCategory> = when (level) {
        AgentApprovalLevel.STRICT -> AgentApprovalCategory.entries.toSet()
        AgentApprovalLevel.STANDARD -> setOf(
            AgentApprovalCategory.INSTALL,
            AgentApprovalCategory.MONEY,
            AgentApprovalCategory.PLUGIN
        )
        AgentApprovalLevel.LOOSE -> setOf(AgentApprovalCategory.MONEY)
        AgentApprovalLevel.CUSTOM -> customCategories
        AgentApprovalLevel.DEVELOPER -> emptySet()
    }

    fun requiresConfirmation(category: AgentApprovalCategory): Boolean =
        when (level) {
            AgentApprovalLevel.STRICT -> true
            AgentApprovalLevel.STANDARD ->
                category == AgentApprovalCategory.INSTALL ||
                    category == AgentApprovalCategory.MONEY
            AgentApprovalLevel.LOOSE -> category == AgentApprovalCategory.MONEY
            AgentApprovalLevel.CUSTOM -> category in customCategories
            AgentApprovalLevel.DEVELOPER -> false
        }

    fun requiresLearningReview(): Boolean =
        requiresConfirmation(AgentApprovalCategory.RISKY)
}
