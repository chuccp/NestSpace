package com.coke.nestspace.integrations.mcp

import android.content.Context
import com.coke.nestspace.features.browser.BrowserProxySettings
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.integrations.proxy.AppProxyManager
import com.coke.nestspace.integrations.proxy.AppProxySettings
import com.coke.nestspace.agent.tools.ToolResult
import com.coke.nestspace.agent.tools.ToolSpec

internal fun mcpCoreToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_settings_get",
                description = "Read all NestSpace settings: language, container slots, " +
                    "location simulation, floating dot, browser proxy, external MCP clients, " +
                    "desktop apps, wallpaper state, LLM configs (without API keys), " +
                    "memory/skill counts and MCP status.",
                inputSchema = objectSchema()
            ) { _, _ -> ToolResult(true, settingsJson(app).toString()) },

            ToolSpec(
                name = "nestspace_browser_proxy",
                description = "Read or set the built-in browser proxy. WebView proxy " +
                    "overrides require Android 10+ and affect all browser tabs. Actions: " +
                    "get, set. set accepts enabled, type (http, https or socks), host, port and " +
                    "bypass (comma-separated rules). An enabled app proxy overrides this " +
                    "browser-only fallback. Set enabled=false for a direct connection.",
                inputSchema = objectSchema(
                    "action" to stringProp("get or set"),
                    "enabled" to boolProp("enable or disable the proxy"),
                    "type" to stringProp("http, https or socks"),
                    "host" to stringProp("proxy hostname or IP, without scheme or port"),
                    "port" to intProp("proxy port, 1-65535"),
                    "bypass" to stringProp("comma-separated hosts that skip the proxy")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                when (action) {
                    "get" -> ToolResult(true, browserProxyJson(app).toString())

                    "set" -> {
                        val current = BrowserProxySettings.load(app)
                        val requestedType = if (args.has("type")) {
                            BrowserProxySettings.parseType(args.optString("type"))
                                ?: return@ToolSpec invalid(
                                    "type must be http, https or socks"
                                )
                        } else {
                            current.type
                        }
                        val config = current.copy(
                            enabled = if (args.has("enabled")) {
                                args.optBoolean("enabled")
                            } else {
                                current.enabled
                            },
                            type = requestedType,
                            host = if (args.has("host")) {
                                args.optString("host").trim()
                            } else {
                                current.host
                            },
                            port = if (args.has("port")) {
                                args.optInt("port", current.port)
                            } else {
                                current.port
                            },
                            bypass = if (args.has("bypass")) {
                                args.optString("bypass").trim()
                            } else {
                                current.bypass
                            }
                        )
                        BrowserProxySettings.validate(config)?.let { error ->
                            return@ToolSpec invalid(browserProxyError(error))
                        }
                        BrowserProxySettings.save(app, config)
                        val status = applyBrowserProxy(app)
                        val result = browserProxyJson(app)
                        if (config.enabled && !status.applied) {
                            ToolResult(
                                false,
                                result.toString(),
                                errorCode = "PROXY_NOT_APPLIED"
                            )
                        } else {
                            ToolResult(true, result.toString())
                        }
                    }

                    else -> invalid("action must be get or set")
                }
            },

            ToolSpec(
                name = "nestspace_app_proxy",
                description = "Read or set the process-wide application network proxy used by " +
                    "LLM, plugin, MCP and update requests. Actions: get, set. set accepts " +
                    "enabled, type (http, https or socks), host, port, bypass (comma-separated " +
                    "rules), authentication_enabled, username and password. Set " +
                    "enabled=false for direct app traffic. SOCKS5 supports unauthenticated " +
                    "proxies.",
                inputSchema = objectSchema(
                    "action" to stringProp("get or set"),
                    "enabled" to boolProp("enable or disable the proxy"),
                    "type" to stringProp("http, https or socks"),
                    "host" to stringProp("proxy hostname or IP, without scheme or port"),
                    "port" to intProp("proxy port, 1-65535"),
                    "bypass" to stringProp("comma-separated hosts that skip the proxy"),
                    "authentication_enabled" to boolProp(
                        "send Basic proxy authentication credentials"
                    ),
                    "username" to stringProp("proxy authentication username"),
                    "password" to stringProp("proxy authentication password")
                )
            ) { args, _ ->
                when (args.optString("action", "get").trim().lowercase()) {
                    "get" -> ToolResult(true, appProxyJson(app).toString())
                    "set" -> {
                        val current = AppProxySettings.load(app)
                        val config = current.copy(
                            enabled = if (args.has("enabled")) {
                                args.optBoolean("enabled")
                            } else {
                                current.enabled
                            },
                            type = if (args.has("type")) {
                                AppProxySettings.parseType(args.optString("type"))
                                    ?: return@ToolSpec invalid(
                                        "type must be http, https or socks"
                                    )
                            } else {
                                current.type
                            },
                            host = if (args.has("host")) {
                                args.optString("host").trim()
                            } else {
                                current.host
                            },
                            port = if (args.has("port")) {
                                args.optInt("port", current.port)
                            } else {
                                current.port
                            },
                            bypass = if (args.has("bypass")) {
                                args.optString("bypass").trim()
                            } else {
                                current.bypass
                            },
                            authenticationEnabled = if (args.has("authentication_enabled")) {
                                args.optBoolean("authentication_enabled")
                            } else {
                                current.authenticationEnabled
                            },
                            username = if (args.has("username")) {
                                args.optString("username").trim()
                            } else {
                                current.username
                            },
                            password = if (args.has("password")) {
                                args.optString("password")
                            } else {
                                current.password
                            }
                        )
                        AppProxySettings.validate(config)?.let { error ->
                            return@ToolSpec invalid(appProxyError(error))
                        }
                        val status = AppProxyManager.apply(app, config)
                        AppSettingsEvents.notifyChanged()
                        val result = appProxyJson(app)
                        if (config.enabled && !status.applied) {
                            ToolResult(
                                false,
                                result.toString(),
                                errorCode = "APP_PROXY_NOT_APPLIED"
                            )
                        } else {
                            ToolResult(true, result.toString())
                        }
                    }
                    else -> invalid("action must be get or set")
                }
            },
    )
}
