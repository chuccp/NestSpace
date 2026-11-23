package com.coke.nestspace.container.identity

import android.os.Process

/**
 * UID model for virtual apps.
 *
 * The kernel process still runs under the host UID. App-facing self-queries
 * use a stable synthetic UID from Android's ordinary application range so the
 * process, package metadata, SELinux categories and native identity APIs
 * remain semantically consistent. Packages installed in the same space get
 * distinct stable values, exactly like separately installed apps.
 *
 * This is app-facing identity only (Java layer). It does not spoof the UID the
 * kernel puts into Binder transactions; system services still see the host
 * UID. Package/attribution arguments are rewritten by SystemServiceIdentity
 * before they reach the system.
 */
object ContainerIdentity {

    /** Android's per-user application UID range. */
    private const val VIRTUAL_UID_BASE = 10_000
    private const val VIRTUAL_UID_RANGE = 10_000

    @Volatile
    private var capturedHostUid: Int = -1

    /**
     * Must run before the target UID facade is installed. Once
     * Process.myUid() is virtualized it no longer identifies the Binder
     * caller, and using it for system-service arguments creates impossible
     * host-package/virtual-UID attribution pairs.
     */
    fun captureHostUid() {
        if (capturedHostUid <= 0) {
            capturedHostUid = Process.myUid()
        }
    }

    fun hostUid(): Int {
        captureHostUid()
        return capturedHostUid
    }

    /** UserHandle.myUserId() is hidden; the container runs in the current user. */
    fun userId(): Int = runCatching {
        Class.forName("android.os.UserHandle")
            .getMethod("myUserId")
            .invoke(null) as Int
    }.getOrDefault(0)

    /** Stable synthetic UID for a package that is installed in the space. */
    fun virtualUidFor(packageName: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (ch in packageName) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        val value = hash and Int.MAX_VALUE
        return VIRTUAL_UID_BASE + (value % VIRTUAL_UID_RANGE)
    }

    fun isVirtualUid(uid: Int): Boolean =
        uid in VIRTUAL_UID_BASE until (VIRTUAL_UID_BASE + VIRTUAL_UID_RANGE)
}
