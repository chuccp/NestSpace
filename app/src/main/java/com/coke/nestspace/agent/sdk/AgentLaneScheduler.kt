package com.coke.nestspace.agent.sdk

/**
 * Thread-safe FIFO scheduler with one active task per Agent lane.
 *
 * It owns only in-memory ordering. Persistence, coroutine jobs and UI state
 * remain the consumer's responsibility.
 */
class AgentLaneScheduler {
    private val lock = Any()
    private val queues = mutableMapOf<AgentLane, ArrayDeque<String>>()
    private val active = mutableMapOf<AgentLane, String>()

    fun enqueue(lane: AgentLane, taskId: String): Boolean = synchronized(lock) {
        require(taskId.isNotBlank()) { "Task id must not be blank" }
        if (containsLocked(taskId)) return false
        queues.getOrPut(lane, ::ArrayDeque).addLast(taskId)
        true
    }

    fun active(lane: AgentLane): String? = synchronized(lock) {
        active[lane]
    }

    fun takeNext(lane: AgentLane): String? = synchronized(lock) {
        if (active[lane] != null) return null
        val queue = queues[lane] ?: return null
        val taskId = queue.removeFirstOrNull() ?: return null
        active[lane] = taskId
        taskId
    }

    fun markActive(lane: AgentLane, taskId: String): Boolean = synchronized(lock) {
        if (active[lane] != null && active[lane] != taskId) return false
        active[lane] = taskId
        true
    }

    fun clearActive(lane: AgentLane, taskId: String): Boolean = synchronized(lock) {
        if (active[lane] != taskId) return false
        active.remove(lane)
        true
    }

    fun removeQueued(taskId: String): Boolean = synchronized(lock) {
        queues.values.any { it.remove(taskId) }
    }

    fun remove(taskId: String): Boolean = synchronized(lock) {
        val removed = removeQueuedLocked(taskId)
        val lane = active.entries.firstOrNull { it.value == taskId }?.key
        if (lane != null) active.remove(lane)
        removed || lane != null
    }

    fun isActive(taskId: String): Boolean = synchronized(lock) {
        active.containsValue(taskId)
    }

    fun queuePosition(taskId: String): Int = synchronized(lock) {
        if (active.containsValue(taskId)) return 0
        queues.values.forEach { queue ->
            val index = queue.indexOf(taskId)
            if (index >= 0) return index + 1
        }
        -1
    }

    fun queuedIds(): List<String> = synchronized(lock) {
        queues.values.flatMap { it.toList() }
    }

    fun isEmpty(): Boolean = synchronized(lock) {
        active.isEmpty() && queues.values.all(ArrayDeque<String>::isEmpty)
    }

    private fun containsLocked(taskId: String): Boolean =
        active.containsValue(taskId) || queues.values.any { taskId in it }

    private fun removeQueuedLocked(taskId: String): Boolean =
        queues.values.any { it.remove(taskId) }
}
