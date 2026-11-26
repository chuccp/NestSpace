package com.coke.nestspace.container.engine

import android.content.Intent
import android.net.Uri
import com.coke.nestspace.data.AppShortcut

internal fun AppShortcut.toContainerIntent(packageName: String): Intent {
    val parsed = intentUri?.let {
        runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()
    }
    val intent = parsed ?: Intent().apply {
        this@toContainerIntent.action?.let { action = it }
        val dataUri = this@toContainerIntent.data
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val mimeType = this@toContainerIntent.mimeType
        if (dataUri != null && !mimeType.isNullOrBlank()) {
            setDataAndType(dataUri, mimeType)
        } else if (dataUri != null) {
            setData(dataUri)
        } else if (!mimeType.isNullOrBlank()) {
            type = mimeType
        }
        this@toContainerIntent.categories.forEach(::addCategory)
        this@toContainerIntent.extras?.let(::putExtras)
    }
    intent.setClassName(packageName, this.targetActivity)
    return intent
}
