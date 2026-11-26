package com.coke.nestspace.integrations.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProxySettingsTest {
    @Test
    fun acceptsHttpAndHttpsProxyProtocols() {
        assertEquals("http", AppProxySettings.parseType(" HTTP "))
        assertEquals("https", AppProxySettings.parseType("https"))
        assertEquals("socks", AppProxySettings.parseType("socks"))
        assertEquals("socks", AppProxySettings.parseType("SOCKS5"))
        assertNull(AppProxySettings.parseType("ftp"))
    }

    @Test
    fun validatesProxyEndpoint() {
        assertNull(
            AppProxySettings.validate(
                AppProxySettings.Config(
                    enabled = true,
                    type = AppProxySettings.TYPE_HTTPS,
                    host = "proxy.example.com",
                    port = 8443
                )
            )
        )
        assertEquals(
            AppProxySettings.ValidationError.INVALID_HOST,
            AppProxySettings.validate(
                AppProxySettings.Config(
                    enabled = true,
                    host = "https://proxy.example.com",
                    port = 443
                )
            )
        )
        assertEquals(
            AppProxySettings.ValidationError.INVALID_PORT,
            AppProxySettings.validate(
                AppProxySettings.Config(
                    enabled = true,
                    host = "10.0.2.2",
                    port = 0
                )
            )
        )
    }

    @Test
    fun appliesBypassRulesToProxySelectorDecision() {
        val config = AppProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 8080,
            bypass = "<local>, *.example.org"
        )

        assertTrue(AppProxySettings.shouldBypass(config, "localhost"))
        assertTrue(AppProxySettings.shouldBypass(config, "api.example.org"))
        assertFalse(AppProxySettings.shouldBypass(config, "api.example.com"))
        assertTrue(
            AppProxySettings.shouldBypass(
                config.copy(bypass = "192.168.*, api.*, *.corp.example"),
                "192.168.1.20"
            )
        )
        assertTrue(
            AppProxySettings.shouldBypass(
                config.copy(bypass = "192.168.*, api.*, *.corp.example"),
                "api.internal"
            )
        )
        assertTrue(
            AppProxySettings.shouldBypass(
                config.copy(bypass = "192.168.*, api.*, *.corp.example"),
                "git.corp.example"
            )
        )
    }

    @Test
    fun signatureIncludesProxyProtocol() {
        val http = AppProxySettings.Config(
            enabled = true,
            type = AppProxySettings.TYPE_HTTP,
            host = "proxy.example.com",
            port = 8080,
            bypass = "localhost"
        )
        val https = http.copy(type = AppProxySettings.TYPE_HTTPS)

        assertEquals(
            "on|http|proxy.example.com|8080|localhost|auth:off||",
            AppProxySettings.signature(http)
        )
        assertEquals(
            "on|https|proxy.example.com|8080|localhost|auth:off||",
            AppProxySettings.signature(https)
        )
    }

    @Test
    fun requiresUsernameWhenAuthenticationIsEnabled() {
        val config = AppProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 8080,
            authenticationEnabled = true
        )

        assertEquals(
            AppProxySettings.ValidationError.USERNAME_REQUIRED,
            AppProxySettings.validate(config)
        )
        assertNull(AppProxySettings.validate(config.copy(username = "nestspace")))
    }

    @Test
    fun signatureChangesWhenAuthenticationChanges() {
        val config = AppProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 8080,
            bypass = "localhost"
        )

        assertTrue(
            AppProxySettings.signature(config) !=
                AppProxySettings.signature(config.copy(authenticationEnabled = true))
        )
        assertTrue(
            AppProxySettings.signature(
                config.copy(authenticationEnabled = true, username = "user", password = "one")
            ) != AppProxySettings.signature(
                config.copy(authenticationEnabled = true, username = "user", password = "two")
            )
        )
    }

    @Test
    fun acceptsAuthenticationForSocks() {
        val config = AppProxySettings.Config(
            enabled = true,
            type = AppProxySettings.TYPE_SOCKS,
            host = "proxy.example.com",
            port = 1080
        )

        assertNull(AppProxySettings.validate(config))
        assertNull(
            AppProxySettings.validate(
                config.copy(
                    authenticationEnabled = true,
                    username = "nestspace",
                    password = "secret"
                )
            )
        )
    }
}
