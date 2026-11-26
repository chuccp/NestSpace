package com.coke.nestspace.container

import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.location.ContainerLocationManager
import com.coke.nestspace.container.shortcut.ContainerShortcutManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

    internal fun ContainerContext.virtualLocationManager():
        android.location.LocationManager? = synchronized(this) {
        if (locationManagerReady) return locationManager
        locationManagerReady = true
        locationManager = ContainerLocationManager.create(this)
        return locationManager
    }

    internal fun ContainerContext.targetWindowManager(): android.view.WindowManager? {
        val delegate = hostWindowManager
            ?: (superGetSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?: return null
        return windowManagerProxy ?: Proxy.newProxyInstance(
            android.view.WindowManager::class.java.classLoader,
            arrayOf(android.view.WindowManager::class.java)
        ) { _, method, args ->
            val targetView = args?.firstOrNull() as? android.view.View
            val toastWindow = args?.filterIsInstance<
                android.view.WindowManager.LayoutParams
                >()?.firstOrNull()?.type ==
                android.view.WindowManager.LayoutParams.TYPE_TOAST
            if (targetView != null &&
                toastWindow &&
                method.name == "addView"
            ) {
                val text = targetViewText(targetView)
                if (isSecurityDecisionToast(text)) {
                    android.util.Log.w(
                        "ContainerSecurityDecision",
                        "target toast package=" + targetPackage +
                            " text=" + text +
                            "\n" +
                            android.util.Log.getStackTraceString(Throwable())
                    )
                }
            }
            args?.forEach { value ->
                if (value is android.view.WindowManager.LayoutParams) {
                    if (value.packageName == null || value.packageName == targetPackage) {
                        value.packageName = HostIdentity.packageName
                    }
                }
            }
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        } as android.view.WindowManager
    }

    internal fun ContainerContext.targetViewText(root: android.view.View): String {
        val values = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is android.widget.TextView) {
                view.text?.toString()?.takeIf(String::isNotBlank)?.let {
                    values += it
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    collect(view.getChildAt(index))
                }
            }
        }
        collect(root)
        return values.joinToString(" | ")
    }

    internal fun ContainerContext.isSecurityDecisionToast(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.lowercase(java.util.Locale.ROOT)
        return (normalized.contains("unofficial") ||
            normalized.contains("non-official")) &&
            normalized.contains("package") ||
            text.contains("非官方安装包")
    }

    internal fun ContainerContext.virtualShortcutManager(): Any? = synchronized(this) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        shortcutManager?.let { return it }
        return runCatching {
            HiddenApiCompat.ensure()
            val storeRoot = File(superGetFilesDir(), "vspace/shortcuts")
            val service = ContainerShortcutManager(
                context = this,
                packageName = targetPackage,
                instanceId = instanceId,
                storeRoot = storeRoot
            ).service()
            val iface = Class.forName("android.content.pm.IShortcutService")
            val managerClass = Class.forName("android.content.pm.ShortcutManager")
            val constructor = managerClass
                .getConstructor(Context::class.java, iface)
                .apply { isAccessible = true }
            constructor.newInstance(this, service).also { shortcutManager = it }
        }.onFailure {
            android.util.Log.w("ContainerContext", "ShortcutManager proxy failed", it)
        }.getOrNull()
    }

    internal fun ContainerContext.virtualAutofillManager(): Any? {
        autofillManager?.let { return it }
        return runCatching {
            val app = HostIdentity.application ?: return null
            // Do not call applicationContext here: this method is reached from
            // ContainerContext.getSystemService(), and a container-derived
            // application context can route back into this same method.
            val hostImpl = app.createDeviceProtectedStorageContext()
            val hostManager = hostImpl.getSystemService(Context.AUTOFILL_SERVICE)
                as? android.view.autofill.AutofillManager
                ?: error("host AutofillManager missing")
            val serviceField = android.view.autofill.AutofillManager::class.java
                .getDeclaredField("mService")
                .apply { isAccessible = true }
            val service = serviceField.get(hostManager)
                ?: error("host AutofillManager has no service")
            // The application's own ContextImpl has an AutofillClient and the
            // service call then carries the virtual package. A fresh derived
            // ContextImpl starts with no client; clear it explicitly to be
            // safe, so AutofillManager treats autofill as unavailable.
            runCatching {
                hostImpl.javaClass
                    .getDeclaredField("mAutofillClient")
                    .apply { isAccessible = true }
                    .set(hostImpl, null)
            }.onFailure {
                android.util.Log.w("ContainerContext", "clear autofill client failed", it)
            }
            val constructor = android.view.autofill.AutofillManager::class.java
                .declaredConstructors
                .firstOrNull {
                    it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Context::class.java
                }
                ?: error("AutofillManager(Context, service) constructor missing")
            constructor.isAccessible = true
            (constructor.newInstance(hostImpl, service) as? android.view.autofill.AutofillManager)
                ?.also { autofillManager = it }
        }.onFailure {
            android.util.Log.w("ContainerContext", "virtual autofill manager failed", it)
        }.getOrNull()
    }

    internal fun ContainerContext.virtualLocaleManager(): Any? {
        if (Build.VERSION.SDK_INT < 33) return null
        localeManager?.let { return it }
        return runCatching {
            val serviceClass = Class.forName("android.app.ILocaleManager")
            val service = Proxy.newProxyInstance(
                serviceClass.classLoader,
                arrayOf(serviceClass)
            ) { _, method, _ ->
                when (method.name) {
                    "getApplicationLocales" -> LocaleList.getEmptyLocaleList()
                    "getSystemLocales" -> LocaleList.getDefault()
                    "getOverrideLocaleConfig" -> null
                    "setApplicationLocales" -> null
                    else -> defaultServiceValue(method.returnType)
                }
            }
            val managerClass = Class.forName("android.app.LocaleManager")
            val constructor = managerClass.getDeclaredConstructor(
                Context::class.java,
                serviceClass
            ).apply { isAccessible = true }
            constructor.newInstance(this, service).also { localeManager = it }
        }.getOrNull()
    }
