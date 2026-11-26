package com.coke.nestspace.container

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object SlotProcessProbe {
    private data class PssSample(val sampledAt: Long, val memoryKb: Long)

    private const val PSS_CACHE_MS = 1_500L
    private const val KILL_TIMEOUT_MS = 1_500L

    private val pssCache = ConcurrentHashMap<Int, PssSample>()

    fun residentKb(pid: Int): Long {
        val now = SystemClock.elapsedRealtime()
        pssCache[pid]?.takeIf { now - it.sampledAt <= PSS_CACHE_MS }?.let {
            return it.memoryKb
        }
        val pss = runCatching {
            File("/proc/$pid/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("Pss:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
        }.getOrNull()
        val memoryKb = if (pss != null && pss > 0) {
            pss
        } else {
            runCatching {
                File("/proc/$pid/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                } ?: 0L
            }.getOrDefault(0L)
        }
        pssCache[pid] = PssSample(now, memoryKb)
        return memoryKb
    }

    fun forgetPid(pid: Int) {
        pssCache.remove(pid)
    }

    fun readSlotMarker(file: File): Int? {
        val lines = runCatching { file.readLines() }.getOrNull().orEmpty()
        return lines.asReversed()
            .firstNotNullOfOrNull { it.trim().toIntOrNull() }
            ?.takeIf { it > 0 && it != Process.myPid() }
    }

    fun markerPid(markerFile: File): Int? {
        val pid = readSlotMarker(markerFile) ?: return null
        return pid.takeIf { sameUidPidExists(it) }
    }

    fun waitForProcessExit(pid: Int) {
        val deadline = SystemClock.uptimeMillis() + KILL_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (!sameUidPidExists(pid)) return
            runCatching { Thread.sleep(50L) }
                .onFailure {
                    Thread.currentThread().interrupt()
                    return
                }
        }
    }

    fun pidOfProcess(processName: String): Int? =
        processPidsByName()[processName]

    fun processPidsByName(): Map<String, Int> {
        val dirs = File("/proc").listFiles() ?: return emptyMap()
        return buildMap {
            dirs.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (!dir.isDirectory) return@forEach
                val processName = runCatching {
                    File(dir, "cmdline").readText().trim('\u0000')
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@forEach
                putIfAbsent(processName, pid)
            }
        }
    }

    fun sameUidPidExists(pid: Int): Boolean {
        if (pid <= 0) return false
        val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            ?: return false
        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toIntOrNull()
        return uid == Process.myUid()
    }
}
