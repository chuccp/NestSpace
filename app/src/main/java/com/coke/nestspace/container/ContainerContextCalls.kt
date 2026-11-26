package com.coke.nestspace.container

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File

    internal fun ContainerContext.getContainerSystemService(name: String): Any? = when (name) {
        Context.LAYOUT_INFLATER_SERVICE -> targetLayoutInflater
        Context.WINDOW_SERVICE ->
            // Activity.attach requires the framework's concrete
            // WindowManagerImpl. Toast is the only caller that needs the
            // package-rewriting proxy. Selecting by caller instead of package
            // keeps this behavior consistent for every virtual application.
            if (calledFromToast()) {
                targetWindowManager() ?: superGetSystemService(name)
            } else {
                hostWindowManager ?: superGetSystemService(name)
            }
        Context.APPWIDGET_SERVICE -> targetAppWidgetManager ?: superGetSystemService(name)
        Context.JOB_SCHEDULER_SERVICE -> jobScheduler ?: superGetSystemService(name)
        // The real location service only knows the host package. Targets get a
        // local LocationManager that reports simulated coordinates (when the
        // setting is on) or delegates to the host-identity service.
        Context.LOCATION_SERVICE ->
            virtualLocationManager() ?: superGetSystemService(name)
        // Android only knows packages installed for the real user. A virtual
        // package therefore gets "Unknown package" from LocaleManager, which
        // some applications treat as a startup failure. Give targets a
        // package-local no-op LocaleManager instead.
        "locale" -> virtualLocaleManager() ?: superGetSystemService(name)
        Context.AUTOFILL_SERVICE ->
            virtualAutofillManager() ?: superGetSystemService(name)
        Context.SHORTCUT_SERVICE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                virtualShortcutManager() ?: superGetSystemService(name)
            } else {
                superGetSystemService(name)
            }
        else -> superGetSystemService(name)
    }

    internal fun ContainerContext.getContainerSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val prefName = name ?: "default"
        val directory = File(activeDataRoot, "shared_prefs")
        ensureBackingDirectory(directory)
        val key = "$deviceProtected:$prefName"
        synchronized(sharedPreferences) {
            sharedPreferences[key]?.let { return it }
            val file = File(directory, "$prefName.xml")
            val backup = File(directory, "$prefName.bak")
            val local = runCatching {
                val type = Class.forName("android.app.SharedPreferencesImpl")
                val constructor = type.constructors.firstOrNull { candidate ->
                    candidate.parameterTypes.contentEquals(
                        arrayOf(File::class.java, File::class.java, Int::class.javaPrimitiveType)
                    )
                }
                val instance = constructor?.newInstance(file, backup, mode)
                    ?: type.getDeclaredConstructor(
                        File::class.java,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }.newInstance(file, mode)
                instance as SharedPreferences
            }.getOrElse {
                android.util.Log.w(
                    "ContainerContext",
                    "instance SharedPreferences fallback name=$prefName",
                    it
                )
                val storagePrefix = if (deviceProtected) "ns_device_" else "ns_"
                return superGetSharedPreferences(
                    ContainerContextStorage.prefKey(targetPackage, instanceId, prefName, storagePrefix),
                    mode
                )
            }
            sharedPreferences[key] = local
            return local
        }
    }
