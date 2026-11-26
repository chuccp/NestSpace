package com.coke.nestspace.container.session

import com.coke.nestspace.container.*
import android.annotation.SuppressLint
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.container.application.ExternalApplicationBootstrap
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.session.TargetClassPathBinder
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper

private const val TAG = "ContainerSession"

    internal fun ContainerSession.createTargetActivity(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle?,
        fallbackActivityClass: String? = null
    ): Activity {
        val info = resolveActivity(className)
            ?: throw IllegalArgumentException(
                AppLanguage.tr(
                    "找不到 Activity：${className ?: spec.launcherActivity}",
                    "Activity not found: ${className ?: spec.launcherActivity}"
                )
            )
        fun normalizeApplicationInfo(targetInfo: android.content.pm.ApplicationInfo?) {
            targetInfo ?: return
            targetInfo.sourceDir = spec.baseApkPath
            targetInfo.publicSourceDir = spec.baseApkPath
            targetInfo.nativeLibraryDir =
                virtualEnvironment.virtualNativeLibraryDir
                    ?: targetInfo.nativeLibraryDir
            targetInfo.splitSourceDirs = virtualEnvironment.virtualSplitApkPaths
                .takeIf { it.isNotEmpty() }
                ?.toTypedArray()
                ?: targetInfo.splitSourceDirs
        }
        normalizeApplicationInfo(info.applicationInfo)
        normalizeApplicationInfo(context.applicationInfo)
        normalizeApplicationInfo(application.applicationInfo)
        normalizeApplicationInfo(spec.applicationInfo)
        normalizeApplicationInfo(spec.packageInfo.applicationInfo)
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val bound = threadClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(thread)
                ?: return@runCatching
            bound.javaClass.getDeclaredField("appInfo")
                .apply { isAccessible = true }
                .set(bound, spec.applicationInfo)
            val loadedApk = bound.javaClass.getDeclaredField("info")
                .apply { isAccessible = true }
                .get(bound)
                ?: return@runCatching
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            loadedApkClass.getDeclaredField("mApplicationInfo")
                .apply { isAccessible = true }
                .set(loadedApk, spec.applicationInfo)
            loadedApkClass.getDeclaredField("mAppDir")
                .apply { isAccessible = true }
                .set(loadedApk, spec.baseApkPath)
            loadedApkClass.getDeclaredField("mResDir")
                .apply { isAccessible = true }
                .set(loadedApk, spec.baseApkPath)
        }.onFailure {
            Log.w(TAG, "normalize published activity application info failed", it)
        }
        Log.d(
            TAG,
            "resolve activity requested=$className info=${info.name} " +
                "target=${info.targetActivity}"
        )
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.crash_trace"
            ) == "1"
        ) {
            NativeEnvironmentBridge.installCrashTrace(spec.dataDir)
        }
        val preparePauseMs = NativeEnvironmentBridge
            .readPropertyForDiagnostics(
                "debug.nestspace.activity_prepare_pause_ms"
            )
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        if (preparePauseMs > 0L) {
            Log.w(
                TAG,
                "pausing before target Activity class load for ${preparePauseMs}ms"
            )
            logClassLoaderShape("session", loader)
            application.classLoader?.let {
                logClassLoaderShape("application", it)
            }
            Thread.sleep(preparePauseMs)
        }
        val activityClass = fallbackActivityClass
            ?: (info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name)
        val targetClass = activeClassLoader.loadClass(activityClass)
            .asSubclass(Activity::class.java)
        runtimeProfile?.beforeTargetActivity(
            applicationSpec,
            loader,
            context,
            targetClass
        )
        Log.d(TAG, "activity class=${targetClass.name}")

        Log.d(
            TAG,
            "target activity phase=theme class=${targetClass.name} " +
                "loader=${targetClass.classLoader?.javaClass?.name}"
        )
        val theme = resources.newTheme().apply {
            applyStyle(resolveTheme(info), true)
        }
        val intent = Intent(sourceIntent ?: Intent())
            .setClassName(spec.packageName, info.name)
        launchActivityThroughFramework(
            host = host,
            info = info,
            intent = intent,
            savedState = savedState
        )?.let { target ->
            targetHosts[target] = host
            logJniSurface(target)
            Log.d(
                TAG,
                "target created through ActivityThread ${target.javaClass.name}"
            )
            return target
        }
        Log.d(
            TAG,
            "target activity phase=construct-start class=${targetClass.name}"
        )
        val target = targetClass.getDeclaredConstructor().newInstance()
        Log.d(
            TAG,
            "target activity phase=construct-ready class=${targetClass.name}"
        )

        val attach = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "attach" }
            ?: error(
                "Activity.attach not found: " +
                    "classLoader=${Activity::class.java.classLoader} " +
                    "methods=${Activity::class.java.declaredMethods.size}"
            )
        val activityBase = ContainerUiContext(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                host.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            } else {
                android.view.Display.DEFAULT_DISPLAY
            }
        )
        val args = attach.parameterTypes.mapIndexed { index, _ ->
            when (index) {
                0 -> activityBase
                1 -> activityThread
                2 -> instrumentation
                3 -> hostToken(host)
                4 -> 0
                5 -> application
                6 -> intent
                7 -> info
                8 -> spec.label
                9 -> null
                10 -> info.name
                11 -> null
                12 -> resources.configuration
                13 -> null
                14 -> null
                15 -> null
                16 -> null
                else -> null
            }
        }

        attach.isAccessible = true
        val attachApplicationInfos = listOf(
            info.applicationInfo,
            context.applicationInfo,
            application.applicationInfo,
            spec.applicationInfo,
            spec.packageInfo.applicationInfo
        ).filterNotNull().distinctBy { System.identityHashCode(it) }
        attachApplicationInfos.forEach { targetInfo ->
            targetInfo.sourceDir = spec.baseApkPath
            targetInfo.publicSourceDir = spec.baseApkPath
        }
        Log.d(
            TAG,
            "target activity phase=attach-start class=${targetClass.name}"
        )
        try {
            attach.invoke(target, *args.toTypedArray())
        } catch (t: Throwable) {
            val parameterTypes = attach.parameterTypes.joinToString { it.name }
            val argumentTypes = args.joinToString { it?.javaClass?.name ?: "null" }
            Log.e(
                TAG,
                "Activity.attach failed target=${targetClass.name} " +
                    "manifest=${info.name} parameters=$parameterTypes arguments=$argumentTypes",
                t
            )
            throw t
        }
        Log.d(
            TAG,
            "target activity phase=attach-ready class=${targetClass.name}"
        )
        applyTargetResources(target, resources, theme)
        installVirtualTaskRoot(target)
        bindTargetWindowTokenResources(target)
        targetHosts[target] = host
        logJniSurface(target)
        Log.d(
            TAG,
            "target activity phase=oncreate-start class=${targetClass.name}"
        )
        try {
            instrumentation.callActivityOnCreate(target, savedState)
        } catch (t: Throwable) {
            inspectMissingField(t, loader)
            val continueAfterFieldOperation =
                NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.continue_after_oncreate_field_error"
                ) == "1" &&
                    generateSequence(t) { it.cause }
                        .any { cause ->
                            cause.message?.contains(
                                "AGET_OBJECT",
                                ignoreCase = true
                            ) == true ||
                                cause.message?.contains(
                                    "DoFieldOperate",
                                    ignoreCase = true
                                ) == true
                        }
            if (continueAfterFieldOperation) {
                Log.w(
                    TAG,
                    "continuing target Activity after field operation failure",
                    t
                )
                return target
            }
            val retryAfterFieldFailure =
                onCreateFieldRetry.get() != true &&
                    generateSequence(t) { it.cause }
                        .any { cause ->
                            cause.message?.contains(
                                "DoFieldOperate",
                                ignoreCase = true
                            ) == true ||
                                cause.message?.contains(
                                    "AGET_OBJECT",
                                    ignoreCase = true
                            ) == true
                        }
            // A throwing target onCreate leaves the Activity attached to the
            // host token; without cleanup targetHosts would retain it for the
            // life of the container process.
            targetHosts.remove(target)
            runCatching { instrumentation.callActivityOnDestroy(target) }
                .onFailure { Log.w(TAG, "cleanup failed target destroy", it) }
            if (retryAfterFieldFailure) {
                Log.w(TAG, "retrying target Activity after field failure", t)
                onCreateFieldRetry.set(true)
                try {
                    Log.w(TAG, "retrying target Activity after field operation")
                    Thread.sleep(100L)
                    return createTargetActivity(
                        host,
                        className,
                        sourceIntent,
                        savedState,
                        fallbackActivityClass
                    )
                } finally {
                    onCreateFieldRetry.remove()
                }
            }
            if (fallbackActivityClass == null && info.targetActivity != null) {
                val concreteAliasClass = runCatching {
                    loader.loadClass(info.name)
                        .takeIf { Activity::class.java.isAssignableFrom(it) }
                }.getOrNull()
                if (concreteAliasClass != null &&
                    concreteAliasClass.name != info.targetActivity
                ) {
                    Log.w(
                        TAG,
                        "alias target ${info.targetActivity} failed; " +
                            "retrying concrete alias class ${concreteAliasClass.name}",
                        t
                    )
                    return createTargetActivity(
                        host,
                        info.name,
                        sourceIntent,
                        savedState,
                        concreteAliasClass.name
                    )
                }
            }
            throw t
        }
        Log.d(
            TAG,
            "target activity phase=oncreate-ready class=${targetClass.name}"
        )
        val activityCalledField = runCatching {
            Activity::class.java
                .getDeclaredField("mCalled")
                .apply { isAccessible = true }
        }.getOrNull()
        val activityCalled = activityCalledField
            ?.let { runCatching { it.getBoolean(target) }.getOrNull() }
            ?: true
        if (!activityCalled) {
            Log.w(
                TAG,
                "target Activity returned without calling super.onCreate: " +
                    target.javaClass.name
            )
            val postCreatePauseMs = NativeEnvironmentBridge
                .readPropertyForDiagnostics(
                    "debug.nestspace.activity_post_create_pause_ms"
                )
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            if (postCreatePauseMs > 0L) {
                Log.w(
                    TAG,
                    "pausing after target Activity onCreate for ${postCreatePauseMs}ms"
                )
                Thread.sleep(postCreatePauseMs)
            }
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.activity_oncreate_recovery"
                ) == "1"
            ) {
                runCatching {
                    Activity::class.java
                        .getDeclaredMethod("onCreate", Bundle::class.java)
                        .apply { isAccessible = true }
                        .invoke(target, savedState)
                }.onFailure {
                    Log.w(TAG, "framework Activity onCreate fallback failed", it)
                }
                runCatching {
                    instrumentation.callActivityOnCreate(target, savedState)
                }.onFailure {
                    Log.w(TAG, "target Activity onCreate retry failed", it)
                }
                if (activityCalledField != null &&
                    !runCatching { activityCalledField.getBoolean(target) }
                        .getOrDefault(true)
                ) {
                    runCatching {
                        activityCalledField.setBoolean(target, true)
                    }
                }
            }
        }
        Log.d(
            TAG,
            "target created ${target.javaClass.name} " +
                "taskRoot=${runCatching { target.isTaskRoot() }.getOrDefault(false)} " +
                "finishing=${target.isFinishing}"
        )
        return target
    }

    private fun ContainerSession.bindTargetWindowTokenResources(target: Activity) {
        runCatching {
            val clientField = generateSequence(target.javaClass as Class<*>?) {
                it.superclass
            }.flatMap { it.declaredFields.asSequence() }
                .firstOrNull {
                    it.name == "mWindowTokenClient" ||
                        it.name == "mWindowClient"
                }
                ?: return@runCatching
            clientField.isAccessible = true
            val client = clientField.get(target) ?: return@runCatching
            val resourcesField = generateSequence(client.javaClass as Class<*>?) {
                it.superclass
            }.flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == "mResources" }
                ?: return@runCatching
            resourcesField.isAccessible = true
            resourcesField.set(client, target.resources)
            Log.d(TAG, "bound target window token resources")
        }.onFailure {
            Log.w(TAG, "bind target window token resources failed", it)
        }
    }

    private fun ContainerSession.inspectMissingField(error: Throwable, loader: ClassLoader) {
        if (error !is NoSuchFieldError ||
            NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.inspect_missing_fields"
            ) != "1"
        ) {
            return
        }
        val message = error.message.orEmpty()
        val owner = Regex("""class "L([^;]+);"""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace('/', '.')
            ?: return
        val fieldName = Regex("""field "([^"]+)"""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: return
        val type = Regex("""no "([^"]+)" field""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: "?"
        val resolved = runCatching { loader.loadClass(owner) }.getOrNull()
        val fields = resolved
            ?.let { runCatching { it.declaredFields.toList() }.getOrNull() }
            .orEmpty()
        Log.e(
            TAG,
            "missing field inspection owner=$owner type=$type name=$fieldName " +
                "classLoader=${resolved?.classLoader?.javaClass?.name}@" +
                "${System.identityHashCode(resolved?.classLoader)} " +
                "fields=${fields.size} present=${fields.any { it.name == fieldName }} " +
                "matching=${fields.filter { it.name == fieldName }.joinToString { it.type.name }}",
            error
        )
    }

    private fun ContainerSession.launchActivityThroughFramework(
        host: StageAActivity,
        info: ActivityInfo,
        intent: Intent,
        savedState: Bundle?
    ): Activity? {
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.framework_activity_launch"
            ) != "1"
        ) {
            return null
        }
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)
            val method = activityThreadClass.declaredMethods.firstOrNull {
                it.name == "startActivityNow" && it.parameterCount == 9
            } ?: error("ActivityThread.startActivityNow not found")
            method.isAccessible = true
            val launchInfo = ActivityInfo(info).apply {
                applicationInfo = info.applicationInfo
            }
            val compatibilityClass = Class.forName(
                "android.content.res.CompatibilityInfo"
            )
            val compatibility = compatibilityClass
                .getField("DEFAULT_COMPATIBILITY_INFO")
                .get(null)
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val loadedApkConstructor = loadedApkClass
                .getDeclaredConstructor(
                    activityThreadClass,
                    ApplicationInfo::class.java,
                    compatibilityClass,
                    ClassLoader::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .apply { isAccessible = true }
            val targetLoadedApk = publishedLoadedApk
                ?: loadedApkConstructor.newInstance(
                    thread,
                    info.applicationInfo,
                    compatibility,
                    loader,
                    false,
                    true,
                    false
                ).also {
                    applyTargetLoadedApkIdentity(it, loader)
                    publishedLoadedApk = it
                }
            mapOf(
                "mApplication" to application,
                "mApplicationInfo" to info.applicationInfo,
                "mClassLoader" to context.currentClassLoader(),
                "mPackageName" to spec.packageName,
                "mAppDir" to spec.baseApkPath,
                "mResDir" to spec.baseApkPath,
                "mDataDir" to info.applicationInfo.dataDir,
                "mResources" to resources
            ).forEach { (name, value) ->
                TargetClassPathBinder.findField(
                    targetLoadedApk.javaClass,
                    name
                )?.let { field ->
                    runCatching { field.set(targetLoadedApk, value) }
                        .onFailure {
                            Log.w(TAG, "initialize target LoadedApk field failed: $name", it)
                        }
                }
            }
            val packagesField = activityThreadClass
                .getDeclaredField("mPackages")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val packages = packagesField.get(thread)
                as MutableMap<String, Any>
            packages[spec.packageName] = java.lang.ref.WeakReference(
                targetLoadedApk
            )
            val assistToken = activityTokenField(host, "mAssistToken")
            val shareableToken = activityTokenField(
                host,
                "mShareableActivityToken"
            )
            Log.d(
                TAG,
                "framework activity launch target=${info.name} " +
                    "uid=${launchInfo.applicationInfo.uid} " +
                    "loadedApk=${targetLoadedApk.javaClass.name} " +
                    "parameters=${method.parameterTypes.joinToString { it.name }}"
            )
            method.invoke(
                thread,
                null,
                null,
                intent,
                launchInfo,
                hostToken(host),
                savedState,
                null,
                assistToken,
                shareableToken
            ) as? Activity
        }.onFailure {
            Log.w(TAG, "framework activity launch failed: ${info.name}", it)
        }.getOrNull()
    }

    private fun ContainerSession.activityTokenField(
        source: Activity,
        name: String
    ): IBinder? = runCatching {
        Activity::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(source) as? IBinder
    }.getOrNull()

    private fun ContainerSession.logJniSurface(activity: Activity) {
        if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                "debug.nestspace.jni_surface_log"
            ) != "1"
        ) {
            return
        }
        var type: Class<*>? = activity.javaClass
        var depth = 0
        while (type != null && depth < 8 && type != Activity::class.java) {
            val nativeMethods = runCatching {
                type?.declaredMethods
                    ?.filter { java.lang.reflect.Modifier.isNative(it.modifiers) }
                    ?.map {
                        it.name + "(" +
                            it.parameterTypes.joinToString(",") { parameter ->
                                parameter.name
                            } +
                            ")"
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
            Log.d(
                TAG,
                "jni surface class=${type?.name} loader=${type?.classLoader} " +
                    "nativeMethods=$nativeMethods"
            )
            type = type?.superclass
            depth += 1
        }
        Log.d(
            TAG,
            "jni surface targetLoader=${activity.javaClass.classLoader} " +
                "sessionLoader=$loader applicationLoader=${application.javaClass.classLoader}"
        )
    }

    private fun ContainerSession.logClassLoaderShape(label: String, candidate: ClassLoader) {
        runCatching {
            val base = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathList = base.getDeclaredField("pathList").apply {
                isAccessible = true
            }.get(candidate)
            val elementsField = Class.forName("dalvik.system.DexPathList")
                .getDeclaredField("dexElements")
                .apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*>
            Log.d(
                TAG,
                "runtime dex shape $label loaderId=${System.identityHashCode(candidate)} " +
                    "loader=${candidate.javaClass.name} " +
                    "parent=${candidate.parent?.javaClass?.name} " +
                    "pathListId=${System.identityHashCode(pathList)} " +
                    "elements=${elements?.size ?: -1}"
            )
            elements?.forEachIndexed { index, value ->
                if (value == null) {
                    Log.d(TAG, "runtime dex element $label[$index]=null")
                    return@forEachIndexed
                }
                val elementClass = Class.forName(
                    "dalvik.system.DexPathList\$Element"
                )
                val dexFile = runCatching {
                    elementClass.getDeclaredField("dexFile").apply {
                        isAccessible = true
                    }.get(value)
                }.getOrNull()
                val path = runCatching {
                    elementClass.getDeclaredField("path").apply {
                        isAccessible = true
                    }.get(value)
                }.getOrNull()
                val dexFileClass = dexFile?.javaClass
                val fileName = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mFileName" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                val cookie = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mCookie" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                val internalCookie = dexFileClass?.declaredFields
                    ?.firstOrNull { it.name == "mInternalCookie" }
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(dexFile) }.getOrNull() }
                Log.d(
                    TAG,
                    "runtime dex element $label[$index] " +
                        "elementId=${System.identityHashCode(value)} " +
                        "path=$path " +
                        "dexId=${dexFile?.let(System::identityHashCode)} " +
                        "fileName=$fileName " +
                        "cookieId=${cookie?.let(System::identityHashCode)} " +
                        "cookieSize=${(cookie as? LongArray)?.size ?: -1} " +
                        "cookie=${(cookie as? LongArray)?.joinToString(",")} " +
                        "internalCookieId=${internalCookie?.let(System::identityHashCode)} " +
                        "internalCookieSize=${(internalCookie as? LongArray)?.size ?: -1} " +
                        "internalCookie=${(internalCookie as? LongArray)?.joinToString(",")}"
                )
            }
        }.onFailure {
            Log.w(TAG, "runtime dex shape $label unavailable", it)
        }
    }

    private fun ContainerSession.hostToken(host: Activity): IBinder =
        Activity::class.java
            .getDeclaredField("mToken")
            .apply { isAccessible = true }
            .get(host) as IBinder

    @SuppressLint("BlockedPrivateApi")
    private fun ContainerSession.installVirtualTaskRoot(target: Activity) {
        runCatching {
            val field = Activity::class.java
                .getDeclaredField("mWindowControllerCallback")
                .apply { isAccessible = true }
            val original = field.get(target)
            val callbackClass = Class.forName(
                "android.view.Window\$WindowControllerCallback"
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "isTaskRoot") {
                    true
                } else if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(original)
                        "equals" -> proxyOrSelf(original, args)
                        else -> "VirtualWindowController"
                    }
                } else {
                    runCatching {
                        method.invoke(original, *(args ?: emptyArray()))
                    }.getOrNull()
                }
            }
            field.set(target, proxy)
        }.onFailure { Log.w(TAG, "virtual task-root compat failed", it) }
    }

    private fun ContainerSession.proxyOrSelf(original: Any?, args: Array<out Any?>?): Boolean =
        args?.firstOrNull() === original

    private fun ContainerSession.resolveTheme(info: ActivityInfo): Int = when {
        info.theme != 0 -> info.theme
        spec.applicationTheme != 0 -> spec.applicationTheme
        else -> android.R.style.Theme_Material_Light
    }

    private fun ContainerSession.applyTargetResources(
        activity: Activity,
        targetResources: Resources,
        theme: Resources.Theme
    ) {
        val resourcesField = ContextThemeWrapper::class.java
            .getDeclaredField("mResources")
            .apply { isAccessible = true }
        resourcesField.set(activity, targetResources)

        val themeField = ContextThemeWrapper::class.java
            .getDeclaredField("mTheme")
            .apply { isAccessible = true }
        themeField.set(activity, theme)
    }

    internal fun ContainerSession.applyTargetLoadedApkIdentity(
        loadedApk: Any,
        targetLoader: ClassLoader
    ) {
        mapOf(
            "mApplication" to (
                ExternalApplicationBootstrap.lastRealApplication ?: application
            ),
            "mApplicationInfo" to context.applicationInfo,
            "mClassLoader" to targetLoader,
            "mDefaultClassLoader" to targetLoader,
            "mPackageName" to spec.packageName,
            "mAppDir" to spec.baseApkPath,
            "mResDir" to spec.baseApkPath,
            "mDataDir" to context.dataDir.absolutePath,
            "mResources" to resources
        ).forEach { (name, value) ->
            runCatching {
                TargetClassPathBinder.findField(loadedApk.javaClass, name)
                    ?.set(loadedApk, value)
            }.onFailure {
                Log.w(TAG, "initialize target LoadedApk field failed: $name", it)
            }
        }
    }

    internal fun ContainerSession.isSelfLauncher(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true &&
            intent.`package` == spec.packageName
