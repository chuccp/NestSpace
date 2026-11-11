package com.coke.nestspace.proxy

import android.content.Context
import android.util.Log
import com.coke.nestspace.i18n.AppLanguage
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Installs one stable ProxySelector for the process. OkHttp captures the
 * selector when a client is built, so replacing the default selector later
 * would not affect existing LLM, plug-in or update clients. The stable
 * selector reads the current rule for every route request instead.
 */
object AppProxyManager {
    data class Status(
        val applied: Boolean = false,
        val message: String = ""
    )

    private data class ActiveRule(
        val config: AppProxySettings.Config,
        val proxy: Proxy
    )

    private val mutableStatus = MutableStateFlow(Status())
    private val lock = Any()
    private var relay: AppProxyRelay? = null
    private var appliedSignature: String? = null

    @Volatile
    private var activeRule: ActiveRule? = null

    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    fun applySaved(context: Context): Status =
        apply(context, AppProxySettings.load(context), persist = false)

    fun apply(context: Context, config: AppProxySettings.Config): Status =
        apply(context, config, persist = true)

    private fun apply(
        context: Context,
        requested: AppProxySettings.Config,
        persist: Boolean
    ): Status = synchronized(lock) {
        installStableSelector()
        if (persist) AppProxySettings.save(context.applicationContext, requested)
        val config = requested.copy(host = requested.host.trim())
        val validation = AppProxySettings.validate(config)
        if (validation != null) {
            return disable(
                AppLanguage.tr(
                    "代理配置无效：${validation.name.lowercase()}",
                    "Invalid proxy configuration: ${validation.name.lowercase()}"
                )
            )
        }

        val signature = AppProxySettings.signature(config)
        if (signature == appliedSignature && (relay != null || !config.enabled)) {
            val status = Status(
                applied = config.enabled,
                message = if (config.enabled) {
                    AppLanguage.tr("应用代理已生效", "App proxy is active")
                } else {
                    AppLanguage.tr("应用代理已关闭", "App proxy disabled")
                }
            )
            mutableStatus.value = status
            return status
        }
        if (!config.enabled) {
            return disable(AppLanguage.tr("应用代理已关闭", "App proxy disabled"))
        }

        relay?.close()
        relay = null
        activeRule = null
        val started = runCatching {
            val nextRelay = AppProxyRelay(
                config,
                AppProxySettings.parseType(config.type) ?: AppProxySettings.TYPE_HTTP
            )
            nextRelay to nextRelay.start()
        }.getOrElse { error ->
            appliedSignature = null
            val status = Status(
                applied = false,
                message = AppLanguage.tr(
                    "应用代理启动失败：${error.message ?: error.javaClass.simpleName}",
                    "Could not start app proxy: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            )
            mutableStatus.value = status
            Log.w("AppProxyManager", "could not start proxy relay", error)
            return status
        }

        val nextRelay = started.first
        val relayPort = started.second
        relay = nextRelay
        activeRule = ActiveRule(
            config,
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(InetAddress.getLoopbackAddress(), relayPort)
            )
        )
        appliedSignature = signature
        val status = Status(
            applied = true,
            message = AppLanguage.tr("应用代理已生效", "App proxy is active")
        )
        mutableStatus.value = status
        status
    }

    private fun disable(message: String): Status {
        relay?.close()
        relay = null
        activeRule = null
        appliedSignature = AppProxySettings.signature(
            AppProxySettings.Config(enabled = false)
        )
        val status = Status(applied = false, message = message)
        mutableStatus.value = status
        return status
    }

    private fun installStableSelector() {
        val current = ProxySelector.getDefault()
        if (current !is StableProxySelector) {
            ProxySelector.setDefault(StableProxySelector(current))
        }
    }

    private fun select(uri: URI): List<Proxy> {
        val rule = activeRule ?: return listOf(Proxy.NO_PROXY)
        val host = uri.host.orEmpty()
        return if (host.isBlank() || AppProxySettings.shouldBypass(rule.config, host)) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(rule.proxy)
        }
    }

    private class StableProxySelector(
        private val fallback: ProxySelector?
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            if (activeRule == null) {
                return runCatching { fallback?.select(uri) }
                    .getOrNull()
                    .orEmpty()
                    .ifEmpty { listOf(Proxy.NO_PROXY) }
            }
            return AppProxyManager.select(uri)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            Log.w(
                "AppProxyManager",
                "proxy connection failed for ${uri.host}",
                ioe
            )
        }
    }
}
