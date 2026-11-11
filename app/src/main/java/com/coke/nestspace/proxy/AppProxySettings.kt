package com.coke.nestspace.proxy

import android.content.Context

object AppProxySettings {
    const val TYPE_HTTP = "http"
    const val TYPE_HTTPS = "https"
    const val NO_PORT = 0

    data class Config(
        val enabled: Boolean = false,
        val type: String = TYPE_HTTP,
        val host: String = "",
        val port: Int = NO_PORT,
        val bypass: String = DEFAULT_BYPASS
    )

    enum class ValidationError {
        INVALID_TYPE,
        HOST_REQUIRED,
        INVALID_HOST,
        INVALID_PORT
    }

    private const val PREFERENCES_NAME = "app_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TYPE = "type"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_BYPASS = "bypass"
    private const val DEFAULT_BYPASS = "<local>, localhost, 127.0.0.1, ::1"

    fun load(context: Context): Config {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        return Config(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            type = parseType(preferences.getString(KEY_TYPE, TYPE_HTTP)) ?: TYPE_HTTP,
            host = preferences.getString(KEY_HOST, "").orEmpty(),
            port = preferences.getInt(KEY_PORT, NO_PORT).coerceIn(NO_PORT, 65535),
            bypass = preferences.getString(KEY_BYPASS, DEFAULT_BYPASS).orEmpty()
        )
    }

    fun save(context: Context, config: Config) {
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_TYPE, parseType(config.type) ?: TYPE_HTTP)
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port.coerceIn(NO_PORT, 65535))
            .putString(KEY_BYPASS, config.bypass.trim())
            .apply()
    }

    fun parseType(value: String?): String? = when (value?.trim()?.lowercase()) {
        TYPE_HTTP -> TYPE_HTTP
        TYPE_HTTPS -> TYPE_HTTPS
        else -> null
    }

    fun validate(config: Config): ValidationError? {
        if (!config.enabled) return null
        if (parseType(config.type) == null) return ValidationError.INVALID_TYPE
        val host = config.host.trim()
        if (host.isBlank()) return ValidationError.HOST_REQUIRED
        if (host.any { it.isWhitespace() } || host.contains('/') || host.contains(':')) {
            return ValidationError.INVALID_HOST
        }
        if (config.port !in 1..65535) return ValidationError.INVALID_PORT
        return null
    }

    fun proxyRule(config: Config): String =
        "${parseType(config.type) ?: TYPE_HTTP}://${config.host.trim()}:${config.port}"

    fun bypassRules(config: Config): List<String> =
        config.bypass
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun signature(config: Config): String = if (!config.enabled) {
        "off"
    } else {
        listOf(
            "on",
            parseType(config.type) ?: TYPE_HTTP,
            config.host.trim().lowercase(),
            config.port.toString(),
            bypassRules(config).joinToString(",")
        ).joinToString("|")
    }

    fun shouldBypass(config: Config, host: String): Boolean {
        val target = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .lowercase()
            .removeSuffix(".")
        if (target.isBlank()) return true
        return bypassRules(config).any { raw ->
            val rule = raw.trim().lowercase().removeSuffix(".")
            when {
                rule.isEmpty() -> false
                rule == "<local>" ->
                    !target.contains('.') || target == "localhost" ||
                        target == "127.0.0.1" || target == "::1"
                rule.contains('*') -> wildcardMatch(rule, target)
                else -> target == rule || target.endsWith(".$rule")
            }
        }
    }

    private fun wildcardMatch(pattern: String, value: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.forEach { char ->
                when (char) {
                    '*' -> append(".*")
                    '.' -> append("\\.")
                    else -> append(Regex.escape(char.toString()))
                }
            }
            append('$')
        }
        return runCatching { Regex(regex).matches(value) }.getOrDefault(false)
    }
}
