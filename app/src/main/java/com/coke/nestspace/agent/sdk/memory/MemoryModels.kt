package com.coke.nestspace.agent.sdk.memory

enum class MemoryKind(val api: String) {
    FACT("fact"),
    PREFERENCE("preference"),
    PROCEDURE("procedure");

    companion object {
        fun from(value: String?): MemoryKind =
            entries.firstOrNull { it.api == value?.lowercase() } ?: FACT
    }
}

data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val approved: Boolean,
    val pinned: Boolean
)

open class AgentMemoryException(
    val code: String
) : Exception(code)

class MemoryException(code: String) : AgentMemoryException(code)

interface AgentMemoryStore {
    fun list(): List<MemoryItem>

    fun approved(): List<MemoryItem>

    fun add(
        kind: MemoryKind,
        content: String,
        keywords: List<String>,
        source: String,
        approved: Boolean = false
    ): Result<MemoryItem>

    fun approve(id: String): Boolean

    fun setPinned(id: String, pinned: Boolean): Boolean

    fun forget(id: String): Boolean

    fun search(query: String, limit: Int = 8): List<MemoryItem>

    fun contextFor(query: String, limit: Int = 12): String
}
