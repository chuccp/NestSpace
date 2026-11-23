package com.coke.nestspace.container.loader

import android.content.Context

/** A target ClassLoader that can seed Context-aware library singletons. */
interface TargetContextInstaller {
    fun installTargetContext(context: Context)
}
