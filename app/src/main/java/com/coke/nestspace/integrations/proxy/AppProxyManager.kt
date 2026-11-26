package com.coke.nestspace.integrations.proxy

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.coke.nestspace.features.browser.BrowserProxyManager
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.File
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

    internal data class ActiveEndpoint(
        val type: String,
        val host: String,
        val port: Int,
        val bypass: String
    )

    private data class ActiveRule(
        val config: AppProxySettings.Config,
        val proxy: Proxy
    )

    private data class StartedProxy(
        val httpRelay: AppProxyRelay?,
        val socksRelay: AppSocksRelay?,
        val proxy: Proxy
    )

    private val mutableStatus = MutableStateFlow(Status())
    private val lock = Any()
    private var relay: AppProxyRelay? = null
    private var socksRelay: AppSocksRelay? = null
    private var appliedSignature: String? = null

    @Volatile
    private var activeRule: ActiveRule? = null

    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    internal fun activeEndpoint(): ActiveEndpoint? {
        val rule = activeRule ?: return null
        val address = rule.proxy.address() as? InetSocketAddress ?: return null
        return ActiveEndpoint(
            type = if (rule.proxy.type() == Proxy.Type.SOCKS) {
                AppProxySettings.TYPE_SOCKS
            } else {
                AppProxySettings.TYPE_HTTP
            },
            host = address.address?.hostAddress ?: address.hostString,
            port = address.port,
            bypass = rule.config.bypass
        )
    }

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
                context,
                AppLanguage.tr(
                    "代理配置无效：${validation.name.lowercase()}",
                    "Invalid proxy configuration: ${validation.name.lowercase()}"
                )
            )
        }

        val signature = AppProxySettings.signature(config)
        if (signature == appliedSignature && (activeRule != null || !config.enabled)) {
            val status = Status(
                applied = config.enabled,
                message = if (config.enabled) {
                    AppLanguage.tr("应用代理已生效", "App proxy is active")
                } else {
                    AppLanguage.tr("应用代理已关闭", "App proxy disabled")
                }
            )
            mutableStatus.value = status
            syncBrowserProxy(context)
            return status
        }
        if (!config.enabled) {
            return disable(
                context,
                AppLanguage.tr("应用代理已关闭", "App proxy disabled")
            )
        }

        relay?.close()
        relay = null
        socksRelay?.close()
        socksRelay = null
        activeRule = null
        val started: StartedProxy = runCatching {
            when (AppProxySettings.parseType(config.type)) {
                AppProxySettings.TYPE_SOCKS -> {
                    val nextRelay = AppSocksRelay(config)
                    StartedProxy(
                        httpRelay = null,
                        socksRelay = nextRelay,
                        proxy = Proxy(
                            Proxy.Type.SOCKS,
                            InetSocketAddress(
                                AppProxyRelay.LOOPBACK_ADDRESS,
                                nextRelay.start()
                            )
                        )
                    )
                }

                else -> {
                    val nextRelay = AppProxyRelay(
                        config,
                        AppProxySettings.parseType(config.type)
                            ?: AppProxySettings.TYPE_HTTP
                    )
                    StartedProxy(
                        httpRelay = nextRelay,
                        socksRelay = null,
                        proxy = Proxy(
                            Proxy.Type.HTTP,
                            InetSocketAddress(
                                AppProxyRelay.LOOPBACK_ADDRESS,
                                nextRelay.start()
                            )
                        )
                    )
                }
            }
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

        relay = started.httpRelay
        socksRelay = started.socksRelay
        activeRule = ActiveRule(config, started.proxy)
        appliedSignature = signature
        val status = Status(
            applied = true,
            message = AppLanguage.tr("应用代理已生效", "App proxy is active")
        )
        mutableStatus.value = status
        syncBrowserProxy(context)
        status
    }

    private fun disable(context: Context, message: String): Status {
        relay?.close()
        relay = null
        socksRelay?.close()
        socksRelay = null
        activeRule = null
        appliedSignature = AppProxySettings.signature(
            AppProxySettings.Config(enabled = false)
        )
        val status = Status(applied = false, message = message)
        mutableStatus.value = status
        syncBrowserProxy(context)
        return status
    }

    private fun syncBrowserProxy(context: Context) {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }.getOrDefault(context.packageName)
        }
        if (processName != context.packageName) return
        runCatching {
            BrowserProxyManager.apply(context.applicationContext)
        }.onFailure {
            Log.w("AppProxyManager", "could not sync browser proxy", it)
        }
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
