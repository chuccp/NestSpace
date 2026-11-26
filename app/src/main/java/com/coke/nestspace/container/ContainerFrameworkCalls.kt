package com.coke.nestspace.container

import android.content.ContextWrapper

    internal fun ContainerContext.calledFromToast(): Boolean {
        val frames = Throwable().stackTrace
        val limit = minOf(frames.size, 12)
        for (i in 0 until limit) {
            val name = frames[i].className
            if (name == "android.widget.Toast" || name.startsWith("android.widget.Toast$")) {
                return true
            }
        }
        return false
    }

    internal fun ContainerContext.calledFromFrameworkAttribution(): Boolean {
        val frames = Throwable().stackTrace
        for (index in 1 until frames.size) {
            val name = frames[index].className
            if (name == ContextWrapper::class.java.name ||
                name == ContainerContext::class.java.name) {
                continue
            }
            if (name == "android.app.ActivityThread" &&
                frames[index].methodName == "currentOpPackageName") {
                return activityThreadOpPackageUsesHost(frames, index)
            }
            return name.startsWith("android.") ||
                name.startsWith("com.android.")
        }
        return true
    }

    internal fun ContainerContext.activityThreadOpPackageUsesHost(
        frames: Array<StackTraceElement>,
        activityThreadFrame: Int
    ): Boolean {
        for (index in activityThreadFrame + 1 until frames.size) {
            val name = frames[index].className
            if (name == ContextWrapper::class.java.name ||
                name == ContainerContext::class.java.name ||
                name == "android.app.ActivityThread") {
                continue
            }
            return name.startsWith("android.") ||
                name.startsWith("com.android.")
        }
        // A JNI caller has no Java frame beyond ActivityThread. Direct installs
        // normally hit hidden-API denial here; when the container exposes the
        // method, its value should still describe the target process.
        return false
    }
