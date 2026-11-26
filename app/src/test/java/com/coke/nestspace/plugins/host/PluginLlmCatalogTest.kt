package com.coke.nestspace.plugins.host

import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginLlmModel
import com.coke.nestspace.plugin.PluginLlmProvider
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginLlmRequest
import com.coke.nestspace.plugin.PluginLlmResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class PluginLlmCatalogTest {

    @After
    fun cleanup() {
        PluginLlmProviders.uninstallProvider(PROVIDER_ID)
    }

    @Test
    fun `registered plugin models become ready to use configs`() {
        PluginLlmProviders.install(provider())

        val option = PluginLlmCatalog.snapshot().single()

        assertEquals(PROVIDER_ID, option.providerId)
        assertEquals("Plugin provider", option.providerName)
        assertEquals("Model 1", option.modelName)
        assertEquals(32_000, option.contextTokens)
        assertTrue(option.supportsVision)

        val config = option.toLlmConfig()
        assertEquals("plugin://$PROVIDER_ID", config.baseUrl)
        assertEquals("model-1", config.model)
        assertEquals("Model 1", config.name)
        assertEquals(32_000, config.contextTokens)
        assertTrue(config.isConfigured)
        assertTrue(config.apiKey.isBlank())
    }

    @Test
    fun `duplicate model ids are listed once`() {
        PluginLlmProviders.install(provider(duplicateModels = true))

        assertEquals(1, PluginLlmCatalog.snapshot().size)
        assertFalse(PluginLlmCatalog.snapshot().single().modelId.isBlank())
    }

    private fun provider(duplicateModels: Boolean = false): PluginLlmProvider =
        object : PluginLlmProvider {
            override fun getDescriptor(): PluginDescriptor =
                PluginDescriptor(
                    "test.llm.catalog",
                    "Catalog test",
                    "1",
                    PluginDescriptor.API_VERSION,
                    0,
                    EnumSet.of(PluginCapability.LLM_PROVIDERS)
                )

            override fun getProviderId(): String = PROVIDER_ID

            override fun getDisplayName(): String = "Plugin provider"

            override fun getModels(): List<PluginLlmModel> {
                val model = PluginLlmModel("model-1", "Model 1", 32_000, true)
                return if (duplicateModels) listOf(model, model) else listOf(model)
            }

            override fun executeAsync(
                request: PluginLlmRequest,
                onTextDelta: Consumer<String>
            ): CompletableFuture<PluginLlmResponse> =
                CompletableFuture.completedFuture(
                    PluginLlmResponse("ok", "[]", "stop", 0, 0)
                )
        }

    private companion object {
        const val PROVIDER_ID = "test.catalog.provider"
    }
}
