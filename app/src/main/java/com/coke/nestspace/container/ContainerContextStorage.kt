package com.coke.nestspace.container

import android.content.Context
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.core.files.safePathSegment
import java.io.File

internal object ContainerContextStorage {
        /**
         * Uninstall removes the whole package, so clear preferences of every
         * instance (including stale ones from an earlier installation).
         */
        fun clearPackagePrefs(base: Context, packageName: String) {
            clearPrefs(base, "ns_${packageName}_")
        }

        /** Clear preferences owned by one instance only. */
        fun clearInstancePrefs(base: Context, packageName: String, instanceId: String) {
            clearPrefs(base, "ns_${packageName}_${instanceId}_")
        }

        private fun clearPrefs(base: Context, prefix: String) {
            val prefsDir = File(base.dataDir, "shared_prefs")
            prefsDir.listFiles()?.forEach { file ->
                val prefName = file.name.removeSuffix(".xml")
                if (prefName.startsWith(prefix)) {
                    runCatching {
                        base.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    }
                    file.delete()
                }
            }
        }

        internal fun prefKey(
            packageName: String,
            instanceId: String,
            name: String,
            prefix: String = "ns_"
        ): String = "$prefix${packageName}_${instanceId}_$name"

        fun externalRootFor(base: Context, packageName: String, instanceId: String): File? {
            val frameworkRoot = HostIdentity.externalFilesDir
                ?: runCatching { base.getExternalFilesDir(null) }.getOrNull()
            val explicitRoot = File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/${HostIdentity.packageName}/files"
            )
            val hostRoot = sequenceOf(frameworkRoot, explicitRoot)
                .filterNotNull()
                .distinctBy { it.absolutePath }
                .firstOrNull { it.isDirectory || it.mkdirs() }
                ?: return null
            val result = File(
                File(hostRoot, "vspace"),
                "${safePathSegment(packageName)}_${safePathSegment(instanceId)}"
            )
            if (!result.isDirectory && !result.mkdirs()) return null
            return result
        }

    }
