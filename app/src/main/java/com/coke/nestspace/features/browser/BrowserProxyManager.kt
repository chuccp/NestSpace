package com.coke.nestspace.features.browser

import android.content.Context
import android.app.Application
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.io.File

/**
 * Applies the persisted proxy to every WebView in the main process through
 * androidx.webkit's ProxyController (Android 10+ / WebView 83+).
 *
 * The override is app-wide, so changing it affects all browser tabs. The
 * callback-based API is serialized on the main thread; stale callbacks are
 * ignored when the user changes the setting quickly.
 */
object BrowserProxyManager {

    private const val TAG = "BrowserProxy"

    data class Status(
        val supported: Boolean = true,
        val applied: Boolean = false,
        val message: String = ""
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> mainHandler.post(command) }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Signature currently requested; callbacks from older requests are ignored. */
    private var pendingSignature: String? = null

    /** Signature the WebView has confirmed applying (or clearing). */
    private var appliedSignature: String? = null

    fun isSupported(): Boolean =
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        }.getOrDefault(false)

    /**
     * Load and apply the saved config. Safe to call from any thread; applying
     * happens synchronously when already on the main thread so opening the
     * browser does not race the first page load.
     */
    fun apply(context: Context, onSettled: ((Status) -> Unit)? = null) {
        val app = context.applicationContext
        if (!shouldApplyInProcess(app)) {
            Log.d(TAG, "WebView proxy skipped in isolated process")
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyOnMain(app, onSettled)
        } else {
            mainHandler.post { applyOnMain(app, onSettled) }
        }
    }

    private fun shouldApplyInProcess(context: Context): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().trim('\u0000')
            }.getOrDefault("")
        }
        return processName == context.packageName ||
            processName == context.packageName + ":c1"
    }

    private fun applyOnMain(context: Context, onSettled: ((Status) -> Unit)?) {
        val supported = isSupported()
        if (!supported) {
            pendingSignature = null
            settle(
                Status(
                    supported = false,
                    applied = false,
                    message = "WebView proxy override is not supported on this device"
                ),
                onSettled
            )
            return
        }
        val config = BrowserProxySettings.effective(context)
        val validation = BrowserProxySettings.validate(config)
        if (validation != null) {
            clear(context, "invalid config: ${validation.name.lowercase()}", onSettled)
            return
        }
        val signature = BrowserProxySettings.signature(config)
        if (signature == appliedSignature && signature == pendingSignature) {
            settle(
                Status(supported = true, applied = signature != "off", message = ""),
                onSettled
            )
            return
        }
        if (!config.enabled) {
            clear(context, "", onSettled)
            return
        }

        val proxyConfig = runCatching {
            val builder = ProxyConfig.Builder()
                .addProxyRule(BrowserProxySettings.proxyRule(config))
            BrowserProxySettings.bypassRules(config).forEach(builder::addBypassRule)
            builder.build()
        }.getOrElse { error ->
            clear(
                context,
                error.message ?: error.javaClass.simpleName,
                onSettled
            )
            return
        }
        pendingSignature = signature
        runCatching {
            ProxyController.getInstance().setProxyOverride(proxyConfig, mainExecutor) {
                if (pendingSignature == signature) {
                    appliedSignature = signature
                    val status = Status(supported = true, applied = true)
                    _status.value = status
                    onSettled?.invoke(status)
                    Log.i(TAG, "proxy applied: ${BrowserProxySettings.proxyRule(config)}")
                }
            }
        }.onFailure { error ->
            if (pendingSignature == signature) pendingSignature = null
            settle(
                Status(
                    supported = true,
                    applied = false,
                    message = error.message ?: error.javaClass.simpleName
                ),
                onSettled
            )
            Log.w(TAG, "setProxyOverride failed", error)
        }
    }

    private fun clear(
        context: Context,
        message: String,
        onSettled: ((Status) -> Unit)? = null
    ) {
        val signature = "off"
        pendingSignature = signature
        runCatching {
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                if (pendingSignature == signature) {
                    appliedSignature = signature
                    val status = Status(
                        supported = true,
                        applied = false,
                        message = message
                    )
                    _status.value = status
                    onSettled?.invoke(status)
                    Log.i(TAG, "proxy cleared")
                }
            }
        }.onFailure { error ->
            if (pendingSignature == signature) pendingSignature = null
            settle(
                Status(
                    supported = true,
                    applied = false,
                    message = error.message ?: error.javaClass.simpleName
                ),
                onSettled
            )
            Log.w(TAG, "clearProxyOverride failed", error)
        }
    }

    private fun settle(status: Status, onSettled: ((Status) -> Unit)?) {
        _status.value = status
        onSettled?.invoke(status)
    }
}
