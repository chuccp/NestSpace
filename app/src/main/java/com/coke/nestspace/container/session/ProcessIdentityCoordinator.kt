package com.coke.nestspace.container.session

internal class ProcessIdentityCoordinator(
    private val restoreHost: () -> Unit,
    private val activateTarget: () -> Unit
) {
    private val lock = Any()
    private var hostDepth = 0
    private var hostSuspended = false

    fun <T> withHost(block: () -> T): T {
        synchronized(lock) {
            if (hostDepth++ == 0) suspendLocked()
        }
        return try {
            block()
        } finally {
            synchronized(lock) {
                hostDepth--
            }
        }
    }

    fun suspend() {
        synchronized(lock) { suspendLocked() }
    }

    fun activate() {
        synchronized(lock) {
            if (!hostSuspended) return
            activateTarget()
            hostSuspended = false
        }
    }

    private fun suspendLocked() {
        if (hostSuspended) return
        restoreHost()
        hostSuspended = true
    }
}
