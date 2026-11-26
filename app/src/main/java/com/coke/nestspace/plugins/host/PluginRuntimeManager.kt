package com.coke.nestspace.plugins.host

import com.coke.nestspace.plugin.AgentHookPlugin
import com.coke.nestspace.plugin.AgentModelHookPlugin
import com.coke.nestspace.plugin.AgentModelPlugins
import com.coke.nestspace.plugin.AgentPlugins
import com.coke.nestspace.plugin.AgentToolHookPlugin
import com.coke.nestspace.plugin.AgentToolHookPlugins
import com.coke.nestspace.plugin.AgentToolPlugins
import com.coke.nestspace.plugin.AgentToolPresentationPlugin
import com.coke.nestspace.plugin.AgentToolPresentationPlugins
import com.coke.nestspace.plugin.AgentToolProvider
import com.coke.nestspace.plugin.PluginCommandPlugins
import com.coke.nestspace.plugin.PluginCommandProvider
import com.coke.nestspace.plugin.PluginContext
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginLifecycle
import com.coke.nestspace.plugin.PluginLlmProvider
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginSpeechProvider
import com.coke.nestspace.plugin.PluginSpeechProviders
import com.coke.nestspace.plugin.PluginUiPlugins
import com.coke.nestspace.plugin.PluginUiProvider
import com.coke.nestspace.plugin.RuntimeProxyPlugin
import com.coke.nestspace.plugin.RuntimeProxyPlugins
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.features.speech.SpeechGateway
import com.coke.nestspace.plugin.PluginMetadataRegistry
import com.coke.nestspace.runtime.plugin.RuntimeProfileProvider
import com.coke.nestspace.runtime.plugin.RuntimeProfiles

/**
 * Attaches one instantiated plugin to every Hook registry it implements.
 *
 * This is the only host-side wiring point. Adding a new Provider interface does
 * not require changes to the Agent, UI or container internals.
 */
internal object PluginRuntimeManager {

    fun attach(plugin: Any, context: PluginContext) {
        val descriptor = context.descriptor
        PluginMetadataRegistry.bind(plugin, descriptor)
        try {
            if (plugin is PluginLifecycle) plugin.onAttach(context)
            if (plugin is RuntimeProxyPlugin) {
                RuntimeProxyPlugins.install(plugin)
            }
            if (plugin is RuntimeProfileProvider) {
                RuntimeProfiles.install(plugin)
            }
            if (plugin is AgentHookPlugin) {
                AgentPlugins.install(plugin)
            }
            if (plugin is AgentModelHookPlugin) {
                AgentModelPlugins.install(plugin)
            }
            if (plugin is AgentToolProvider) {
                AgentToolPlugins.install(plugin)
            }
            if (plugin is AgentToolHookPlugin) {
                AgentToolHookPlugins.install(plugin)
            }
            if (plugin is AgentToolPresentationPlugin) {
                AgentToolPresentationPlugins.install(plugin)
            }
            if (plugin is PluginCommandProvider) {
                PluginCommandPlugins.install(plugin)
            }
            if (plugin is PluginUiProvider) {
                PluginUiPlugins.install(plugin)
            }
            if (plugin is PluginLlmProvider) {
                PluginLlmProviders.install(plugin)
                AppSettingsEvents.notifyChanged()
            }
            if (plugin is PluginSpeechProvider) {
                PluginSpeechProviders.install(plugin)
                AppSettingsEvents.notifyChanged()
            }
        } catch (error: Throwable) {
            detach(plugin, descriptor, context)
            throw error
        }
    }

    fun detach(plugin: Any, context: PluginContext) {
        detach(plugin, context.descriptor, context)
    }

    private fun detach(
        plugin: Any,
        descriptor: PluginDescriptor,
        context: PluginContext
    ) {
        val id = descriptor.id
        RuntimeProxyPlugins.uninstall(id)
        RuntimeProfiles.unregister(id)
        AgentPlugins.uninstall(id)
        AgentModelPlugins.uninstall(id)
        AgentToolPlugins.uninstall(id)
        AgentToolHookPlugins.uninstall(id)
        AgentToolPresentationPlugins.uninstall(id)
        PluginCommandPlugins.uninstall(id)
        PluginUiPlugins.uninstall(id)
        if (plugin is PluginLlmProvider) {
            PluginLlmProviders.uninstallProvider(plugin.providerId)
            AppSettingsEvents.notifyChanged()
        }
        if (plugin is PluginSpeechProvider) {
            PluginSpeechProviders.uninstall(plugin.providerId)
            SpeechGateway.stopProvider(plugin.providerId)
            AppSettingsEvents.notifyChanged()
        }
        if (plugin is PluginLifecycle) {
            runCatching(plugin::onDetach)
        }
        if (context is AutoCloseable) {
            runCatching(context::close)
        }
        PluginMetadataRegistry.unbind(plugin)
    }
}
