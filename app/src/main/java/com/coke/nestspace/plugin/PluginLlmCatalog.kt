package com.coke.nestspace.plugin

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol

/** One model advertised by an enabled plugin LLM provider. */
data class PluginLlmModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val contextTokens: Int,
    val supportsVision: Boolean
) {
    val key: String get() = "$providerId\u0000$modelId"

    fun toLlmConfig(): LlmConfig = LlmConfig(
        name = modelName,
        protocol = LlmProtocol.ANTHROPIC,
        baseUrl = "plugin://$providerId",
        model = modelId,
        contextTokens = contextTokens.coerceAtLeast(
            LlmConfig.MIN_PLUGIN_CONTEXT_TOKENS
        )
    )
}

/**
 * Flattens the plugin-owned model contributions into the display model used
 * by settings. The plugin registry remains the source of truth, so disabled or
 * uninstalled providers disappear without persisting temporary configurations.
 */
object PluginLlmCatalog {

    fun snapshot(): List<PluginLlmModelOption> =
        PluginLlmProviders.snapshot()
            .flatMap { provider ->
                val providerId = runCatching { provider.providerId }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (providerId.isBlank()) return@flatMap emptyList()
                val providerName = runCatching { provider.displayName }
                    .getOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: runCatching { provider.descriptor.name }
                        .getOrNull()
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
                    ?: providerId
                val models = runCatching { provider.models }
                    .getOrNull()
                    .orEmpty()
                models.map { model ->
                    PluginLlmModelOption(
                        providerId = providerId,
                        providerName = providerName,
                        modelId = model.id,
                        modelName = model.displayName,
                        contextTokens = model.contextTokens,
                        supportsVision = model.supportsVision()
                    )
                }
            }
            .distinctBy { it.key }
            .sortedWith(
                compareBy<PluginLlmModelOption> { it.providerName.lowercase() }
                    .thenBy { it.modelName.lowercase() }
                    .thenBy { it.modelId }
            )
}
