package com.coke.nestspace.container

import android.content.Intent
import java.io.File
import com.coke.nestspace.container.api.RuntimeHostHooks
import com.coke.nestspace.container.api.webUrlFromViewIntent

    internal fun ContainerContext.dir(name: String): File {
        ensureBackingDirectory(File(activeDataRoot, name))
        return File(visibleDataRoot, name)
    }

    internal fun ContainerContext.ensureBackingDirectory(directory: File) {
        if (!directory.isDirectory) directory.mkdirs()
        val mode = when (directory.name) {
            "cache", "code_cache" -> 0x5f9
            "files", "no_backup", "databases", "shared_prefs", "obb" -> 0x1f9
            else -> null
        }
        if (mode != null) {
            runCatching {
                android.system.Os.chmod(directory.absolutePath, mode)
            }
        }
    }

    internal fun ContainerContext.openWebIntentInside(intent: Intent): Boolean {
        val url = webUrlFromViewIntent(intent) ?: return false
        return runCatching {
            RuntimeHostHooks.openBrowser?.invoke(this, url)
            true
        }.getOrDefault(false)
    }
