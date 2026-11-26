package com.coke.nestspace.agent.sdk.skill

data class SkillRevision(
    val at: Long,
    val description: String,
    val body: String
)

data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val source: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val builtIn: Boolean = false,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val recentOutcomes: List<Boolean> = emptyList(),
    val lastUsedAt: Long = 0L,
    val revisions: List<SkillRevision> = emptyList(),
    val autoSuspended: Boolean = false
)

open class AgentSkillException(
    val code: String
) : Exception(code)

class SkillException(code: String) : AgentSkillException(code)

interface AgentSkillStore {
    fun list(): List<SkillItem>

    fun enabled(): List<SkillItem>

    fun get(nameOrId: String): SkillItem?

    fun save(
        name: String,
        description: String,
        body: String,
        source: String,
        enabled: Boolean = false
    ): Result<SkillItem>

    fun setEnabled(id: String, enabled: Boolean): Boolean

    fun recordOutcome(id: String, success: Boolean): SkillItem?

    fun restoreRevision(id: String, at: Long): Result<SkillItem>?

    fun delete(id: String): Boolean

    fun enabledIndex(limit: Int = 20): String
}

object SkillPolicy {
    const val MAX_DESCRIPTION = 160
    const val MAX_BODY = 12_000
    private val NAME = Regex("[a-z0-9][a-z0-9-]{1,39}")
    private val WHITESPACE = Regex("\\s+")

    fun normalizeName(raw: String): String =
        WHITESPACE.replace(raw.trim().lowercase(), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .trim('-')
            .take(40)

    fun isValidName(value: String): Boolean = NAME.matches(value)
}

object SkillOutcomePolicy {
    /** Two failures in the last three reviewed uses disable a skill. */
    fun shouldSuspend(outcomes: List<Boolean>): Boolean =
        outcomes.takeLast(3).count { !it } >= 2
}
