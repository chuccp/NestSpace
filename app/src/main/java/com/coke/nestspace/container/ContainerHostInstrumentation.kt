package com.coke.nestspace.container

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent

/**
 * Process-wide Instrumentation used by ActivityThread to instantiate host
 * Activities.
 *
 * A remote Provider/Observer can start the target process and activate its
 * virtual identity before a StageA Activity launch arrives. Activity.attach()
 * queries framework Settings before StageA.onCreate(), so restoring identity
 * in onCreate is too late. Suspend it before the host Activity constructor and
 * leave it suspended; target startup later calls ContainerSessions.retain(),
 * which activates the virtual identity again.
 */
class ContainerHostInstrumentation : Instrumentation() {

    override fun newActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Activity {
        val type = runCatching {
            Class.forName(className, false, cl)
        }.getOrElse {
            Class.forName(
                className,
                false,
                ContainerHostInstrumentation::class.java.classLoader
            )
        }
        val hostStage = StageAActivity::class.java.isAssignableFrom(type)
        if (hostStage) {
            ContainerSessions.prepareHostActivityLaunch()
        }
        return super.newActivity(
            if (hostStage) {
                ContainerHostInstrumentation::class.java.classLoader
            } else {
                cl
            },
            className,
            intent
        )
    }

}
