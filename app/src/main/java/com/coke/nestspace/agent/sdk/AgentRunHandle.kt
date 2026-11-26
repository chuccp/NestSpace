package com.coke.nestspace.agent.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class AgentRunHandle internal constructor(
    val runId: String,
    private val eventLog: AgentEventLog,
    private val result: Deferred<AgentRunResult>,
    private val job: Job,
    private val scope: CoroutineScope
) {
    private val timeoutLock = Any()
    private var timeoutJob: Job? = null

    init {
        job.invokeOnCompletion {
            synchronized(timeoutLock) {
                timeoutJob?.cancel()
                timeoutJob = null
            }
        }
    }

    fun events(afterSequence: Long = 0L): Flow<AgentEventRecord> =
        eventLog.events(afterSequence)

    fun snapshotEvents(afterSequence: Long = 0L): List<AgentEventRecord> =
        eventLog.records().filter { it.sequence > afterSequence }

    suspend fun await(): AgentRunResult = result.await()

    fun cancel(reason: String = "cancelled"): Boolean {
        if (!job.isActive) return false
        job.cancel(CancellationException(reason))
        return true
    }

    suspend fun cancelAndJoin(reason: String = "cancelled"): Boolean {
        if (!cancel(reason)) return false
        job.join()
        return true
    }

    fun cancelAfter(timeoutMillis: Long): Job? {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        if (!job.isActive) return null
        val timeout = scope.launch {
            delay(timeoutMillis)
            cancel("run timeout")
        }
        synchronized(timeoutLock) {
            if (job.isActive) {
                timeoutJob?.cancel()
                timeoutJob = timeout
            } else {
                timeout.cancel()
            }
        }
        return timeout
    }
}

internal class AgentEventLog {
    private val state = MutableStateFlow<List<AgentEventRecord>>(emptyList())
    private var lastSequence = 0L
    private val lock = Any()

    fun append(event: AgentEvent): AgentEventRecord = synchronized(lock) {
        val record = AgentEventRecord(++lastSequence, event)
        state.value = state.value + record
        record
    }

    fun records(): List<AgentEventRecord> = state.value

    fun events(afterSequence: Long): Flow<AgentEventRecord> = flow {
        var cursor = afterSequence
        state.collect { records ->
            records.asSequence()
                .filter { it.sequence > cursor }
                .forEach {
                    emit(it)
                    cursor = it.sequence
                }
        }
    }
}
