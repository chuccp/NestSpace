package com.coke.nestspace.container.runtime

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Process-local heartbeat used to diagnose native SIGKILL/self-kill failures.
 * A SIGKILL cannot be caught, so the previous heartbeat is examined on the
 * next launch.
 */
object RuntimeProcessSentinel {
    private const val TAG = "RuntimeSentinel"
    private const val STATE_FILE = "runtime-process-state.json"
    private const val LAST_EXIT_FILE = "runtime-last-exit.json"

    class Session internal constructor(
        private val dataRoot: String,
        private val packageName: String,
        private val processName: String
    ) {
        private val pid = Process.myPid()
        private val startedAt = System.currentTimeMillis()
        private val startedElapsed = SystemClock.elapsedRealtime()
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "nestspace-runtime-sentinel").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        private val lock = Any()

        @Volatile
        private var status = "running"

        @Volatile
        private var phase = "process-create"

        @Volatile
        private var failure: String? = null

        init {
            synchronized(lock) { writeLocked() }
            executor.scheduleAtFixedRate(
                { runCatching { write() }.onFailure {
                    Log.w(TAG, "heartbeat write failed", it)
                } },
                1L,
                1L,
                TimeUnit.SECONDS
            )
        }

        fun markPhase(value: String) {
            phase = value
            runCatching { write() }
        }

        fun markFailure(stage: String, error: Throwable) {
            status = "failed"
            phase = stage
            failure = "${error.javaClass.name}: ${error.message.orEmpty()}"
            runCatching { write() }
        }

        fun finish(reason: String) {
            status = "clean"
            phase = reason
            synchronized(lock) { writeLocked() }
            executor.shutdownNow()
        }

        private fun write() {
            synchronized(lock) { writeLocked() }
        }

        private fun writeLocked() {
            val hooks = NativeEnvironmentBridge.hookSnapshot()
            val state = JSONObject()
                .put("schema_version", 1)
                .put("package", packageName)
                .put("process", processName)
                .put("pid", pid)
                .put("status", status)
                .put("phase", phase)
                .put("failure", failure ?: JSONObject.NULL)
                .put("started_at", startedAt)
                .put("heartbeat_at", System.currentTimeMillis())
                .put(
                    "running_ms",
                    SystemClock.elapsedRealtime() - startedElapsed
                )
                .put("last_hook_event", hookEventName(hooks?.lastHookEvent ?: 0))
                .put("last_syscall_number", hooks?.lastSyscallNumber ?: -1)
            writeJson(File(directory(dataRoot), STATE_FILE), state)
        }
    }

    fun begin(
        dataRoot: String,
        packageName: String,
        processName: String
    ): Session {
        val directory = directory(dataRoot)
        val stateFile = File(directory, STATE_FILE)
        if (stateFile.isFile) {
            runCatching { JSONObject(stateFile.readText()) }.getOrNull()
                ?.takeIf { it.optString("status") == "running" }
                ?.let { previous ->
                    writeJson(
                        File(directory, LAST_EXIT_FILE),
                        JSONObject(previous.toString())
                            .put("detected_at", System.currentTimeMillis())
                            .put("detected_pid", Process.myPid())
                            .put(
                                "reason",
                                "process-ended-without-clean-shutdown"
                            )
                    )
                    Log.w(
                        TAG,
                        "previous process ended without shutdown " +
                            "phase=${previous.optString("phase")} " +
                            "pid=${previous.optInt("pid")} " +
                            "hook=${previous.optString("last_hook_event")}"
                    )
                }
        }
        return Session(dataRoot, packageName, processName)
    }

    private fun directory(dataRoot: String): File =
        File(dataRoot, ".nestspace").apply { mkdirs() }

    private fun writeJson(target: File, value: JSONObject) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(value.toString(2))
        if (!temporary.renameTo(target)) {
            target.writeText(value.toString(2))
            temporary.delete()
        }
    }

    private fun hookEventName(event: Int): String = when (event) {
        1 -> "uid"
        2 -> "file"
        3 -> "proc"
        4 -> "property"
        5 -> "direct_syscall"
        6 -> "loader"
        else -> "none"
    }
}
