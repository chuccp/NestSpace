package com.coke.nestspace.screenreader

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

data class CachedScreenSnapshot(
    val id: String,
    val slot: Int?,
    val document: JSONObject,
    val createdAt: Long = System.currentTimeMillis()
)

object ScreenSnapshotStore {
    private const val MAX_ENTRIES = 12
    private const val MAX_AGE_MS = 10 * 60 * 1000L
    private val entries = ConcurrentHashMap<String, CachedScreenSnapshot>()

    fun save(slot: Int?, document: JSONObject): CachedScreenSnapshot {
        val snapshot = CachedScreenSnapshot(
            id = document.optString("snapshot_id"),
            slot = slot,
            document = JSONObject(document.toString())
        )
        entries[snapshot.id] = snapshot
        prune()
        return snapshot
    }

    fun get(id: String): CachedScreenSnapshot? {
        val value = entries[id] ?: return null
        if (System.currentTimeMillis() - value.createdAt > MAX_AGE_MS) {
            entries.remove(id)
            return null
        }
        return value
    }

    fun invalidateSlot(slot: Int) {
        entries.entries.removeAll { it.value.slot == slot }
    }

    fun invalidateAll() {
        entries.clear()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        entries.entries.removeAll {
            now - it.value.createdAt > MAX_AGE_MS
        }
        entries.entries
            .sortedByDescending { it.value.createdAt }
            .drop(MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }
}
