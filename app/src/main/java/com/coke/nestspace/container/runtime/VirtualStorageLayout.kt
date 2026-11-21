package com.coke.nestspace.container.runtime

import android.os.Environment
import com.coke.nestspace.container.identity.HostIdentity
import java.io.File

/**
 * Android exposes one credential-protected and one device-protected storage
 * area per application. Virtual instances keep them as sibling roots so data
 * never crosses the storage-area boundary or another instance.
 */
object VirtualStorageLayout {
    const val DEVICE_PROTECTED_DIRECTORY = "device_protected"

    fun deviceProtectedRoot(credentialRoot: File): File =
        File(credentialRoot, DEVICE_PROTECTED_DIRECTORY)

    fun externalInstanceRoot(packageName: String, instanceId: String): File {
        val safePackage = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeInstance = instanceId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val hostExternalData = HostIdentity.externalFilesDir?.parentFile
            ?: File(
                Environment.getExternalStorageDirectory(),
                "Android/data/${HostIdentity.packageName}"
            )
        return File(File(hostExternalData, "vspace"), "${safePackage}_${safeInstance}")
    }
}
