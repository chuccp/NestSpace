package com.coke.nestspace.agent.memory

import android.content.Context
import com.coke.nestspace.agent.sdk.memory.AgentMemoryStore
import com.coke.nestspace.agent.sdk.memory.MemoryContextFormatter
import com.coke.nestspace.agent.sdk.memory.MemoryException
import com.coke.nestspace.agent.sdk.memory.MemoryItem
import com.coke.nestspace.agent.sdk.memory.MemoryKind
import com.coke.nestspace.agent.sdk.memory.MemoryPolicy
import com.coke.nestspace.agent.sdk.memory.MemoryRanking
import com.coke.nestspace.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * User-approved notes. Facts and preferences can be active immediately;
 * procedures are saved pending until the user approves them in Memory & skills.
 * Only approved notes are injected into the model context.
 */
class MemoryStore(context: Context) : AgentMemoryStore {

    private val appContext = context.applicationContext
    private val memoryFile = File(appContext.filesDir, FILE)
    // One process-wide lock: several MemoryStore instances (tools, agent,
    // settings UI) read-modify-write the same file and must not lose updates.
    private val lock: Any get() = LOCK

    override fun list(): List<MemoryItem> = synchronized(lock) { load() }

    override fun approved(): List<MemoryItem> =
        list().filter { it.approved }.sortedWith(compareByDescending<MemoryItem> { it.pinned }
            .thenByDescending { it.updatedAt })

    override fun add(
        kind: MemoryKind,
        content: String,
        keywords: List<String>,
        source: String,
        approved: Boolean
    ): Result<MemoryItem> {
        val text = MemoryPolicy.normalize(content)
        if (text.isBlank()) return Result.failure(MemoryException("empty"))
        if (text.length > MemoryPolicy.MAX_CONTENT) {
            return Result.failure(MemoryException("too_long"))
        }
        MemoryPolicy.sensitiveReason(text)?.let {
            return Result.failure(MemoryException("sensitive"))
        }
        val normalizedKeywords = MemoryPolicy.normalizeKeywords(keywords, text)
        val normalizedSource = source.trim().take(MemoryPolicy.MAX_SOURCE)
        // Do not persist a password/code/card number through the quote or a
        // keyword even when the one-line content itself looks harmless.
        val sensitive = MemoryPolicy.sensitiveReason(normalizedSource) != null ||
            normalizedKeywords.any { MemoryPolicy.sensitiveReason(it) != null }
        if (sensitive) return Result.failure(MemoryException("sensitive"))
        val now = System.currentTimeMillis()
        val item = MemoryItem(
            id = UUID.randomUUID().toString(),
            kind = kind,
            content = text,
            keywords = normalizedKeywords,
            source = normalizedSource,
            createdAt = now,
            updatedAt = now,
            approved = approved,
            pinned = false
        )
        synchronized(lock) {
            val items = loadForWrite()
                ?: return Result.failure(MemoryException("unavailable"))
            val next = items + item
            if (!save(next)) return Result.failure(MemoryException("write_failed"))
        }
        return Result.success(item)
    }

    override fun approve(id: String): Boolean = update(id) {
        it.copy(approved = true, updatedAt = System.currentTimeMillis())
    }

    override fun setPinned(id: String, pinned: Boolean): Boolean = update(id) {
        it.copy(pinned = pinned, updatedAt = System.currentTimeMillis())
    }

    override fun forget(id: String): Boolean = synchronized(lock) {
        val items = loadForWrite()?.toMutableList() ?: return false
        val removed = items.removeAll { it.id == id }
        if (!removed) return false
        save(items)
    }

    override fun search(query: String, limit: Int): List<MemoryItem> {
        val q = query.trim().lowercase()
        val all = approved()
        val cap = limit.coerceIn(1, 20)
        if (q.isBlank()) return all.take(cap)
        val ranked = MemoryRanking.search(all, q, cap)
        if (ranked.isNotEmpty()) return ranked
        // Keep exact short queries ("咖啡") working even when tokenization
        // produces no multi-character terms.
        return all.filter { item ->
            item.content.lowercase().contains(q) ||
                item.keywords.any { it.lowercase().contains(q) }
        }.take(cap)
    }

    /** Compact approved notes for injection into the system prompt. */
    override fun contextFor(query: String, limit: Int): String {
        val all = approved()
        if (all.isEmpty()) return ""
        // Small stores fit entirely; do not drop an old note just because a
        // dozen newer ones exist. Large stores are ranked by relevance.
        val full = MemoryContextFormatter.render(all)
        if (full.length <= MemoryPolicy.MAX_CONTEXT_CHARS) return full
        return MemoryContextFormatter.render(
            MemoryRanking.select(all, query, limit.coerceIn(1, 20))
        )
    }

    private fun update(id: String, transform: (MemoryItem) -> MemoryItem): Boolean =
        synchronized(lock) {
            val items = loadForWrite()?.toMutableList() ?: return false
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return false
            items[index] = transform(items[index])
            save(items)
        }

    private fun load(): List<MemoryItem> = loadForWrite() ?: emptyList()

    /**
     * Writes must not turn a temporarily unreadable file into an empty one.
     * A missing file is an empty memory; an existing file that cannot be read
     * or parsed makes the store unavailable until the next attempt.
     */
    private fun loadForWrite(): List<MemoryItem>? {
        val raw = SecureStore.read(appContext, FILE)
        if (raw == null) return if (memoryFile.isFile) null else emptyList()
        return runCatching { parse(raw) }.getOrNull()
    }

    private fun parse(raw: String): List<MemoryItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            MemoryItem(
                id = json.optString("id"),
                kind = MemoryKind.from(json.optString("kind")),
                content = json.optString("content"),
                keywords = json.optJSONArray("keywords")?.let { keys ->
                    (0 until keys.length()).map { keys.optString(it) }
                }.orEmpty(),
                source = json.optString("source"),
                createdAt = json.optLong("created_at"),
                updatedAt = json.optLong("updated_at"),
                approved = json.optBoolean("approved"),
                pinned = json.optBoolean("pinned")
            )
        }.filter { it.id.isNotBlank() && it.content.isNotBlank() }
    }

    private fun save(items: List<MemoryItem>): Boolean {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind.api)
                    .put("content", item.content)
                    .put("keywords", JSONArray(item.keywords))
                    .put("source", item.source)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt)
                    .put("approved", item.approved)
                    .put("pinned", item.pinned)
            )
        }
        return SecureStore.write(appContext, FILE, array.toString())
    }

    private companion object {
        val LOCK = Any()
        const val FILE = "memory.v1.enc"
    }
}
