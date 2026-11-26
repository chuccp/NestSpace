package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.integrations.mcp.McpClientManager
import com.coke.nestspace.plugins.host.PluginCenterStore
import com.coke.nestspace.agent.tools.ToolRegistry
import com.coke.nestspace.agent.tools.ToolSpec

enum class AgentToolSource {
    BUILT_IN,
    PLUGIN,
    EXTERNAL_MCP
}

data class AgentToolInfo(
    val name: String,
    val title: String,
    val description: String,
    val source: AgentToolSource,
    val mutating: Boolean,
    val enabled: Boolean
)

/**
 * Global tool exposure for the local Agent. Remote MCP keeps its complete
 * registry because it is an explicit external control channel rather than a
 * model-specific tool set.
 */
object AgentToolSettings {

    private const val PREFS = "agent_tools"
    private const val KEY_DISABLED = "disabled"
    private val hiddenFromAgent = setOf(
        "session_acquire",
        "session_release",
        "session_status",
        "approval_request",
        "approval_wait",
        "assistant_type",
        "assistant_send",
        "assistant_status"
    )

    fun disabledTools(context: Context): Set<String> =
        preferences(context)
            .getStringSet(KEY_DISABLED, emptySet())
            .orEmpty()
            .toSet()

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "tool name is required" }
        val disabled = disabledTools(context)
        val next = if (enabled) disabled - normalized else disabled + normalized
        check(
            preferences(context).edit()
                .putStringSet(KEY_DISABLED, next)
                .commit()
        ) { "tool settings could not be saved" }
    }

    fun availableToolSpecs(registry: ToolRegistry): List<ToolSpec> {
        val external = if (
            PluginCenterStore.isEnabled(
                registry.context,
                PluginCenterStore.MCP_TOOL_BRIDGE
            )
        ) {
            McpClientManager.toolSpecs()
        } else {
            emptyList()
        }
        return (registry.list() + external)
            .filterNot { it.name in hiddenFromAgent }
            .distinctBy { it.name }
    }

    fun catalog(registry: ToolRegistry): List<AgentToolInfo> =
        catalogFromSpecs(
            specs = availableToolSpecs(registry),
            disabled = disabledTools(registry.context)
        )

    internal fun enabledToolSpecs(
        specs: List<ToolSpec>,
        disabled: Set<String>
    ): List<ToolSpec> = specs.filterNot {
        presentation(it).source == AgentToolSource.PLUGIN &&
            it.name in disabled
    }

    internal fun catalogFromSpecs(
        specs: List<ToolSpec>,
        disabled: Set<String>
    ): List<AgentToolInfo> = specs
        .distinctBy { it.name }
        .mapNotNull { spec ->
            val presentation = presentation(spec)
            if (presentation.source != AgentToolSource.PLUGIN) {
                return@mapNotNull null
            }
            AgentToolInfo(
                name = spec.name,
                title = presentation.title,
                description = presentation.description,
                source = presentation.source,
                mutating = spec.mutating,
                enabled = spec.name !in disabled
            )
        }
        .sortedWith(
            compareBy<AgentToolInfo> { it.title.lowercase() }
                .thenBy { it.name }
        )

    private fun presentation(spec: ToolSpec): ToolPresentation {
        val lines = spec.description
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val first = lines.firstOrNull().orEmpty()
        val source = when {
            first.startsWith("[Plugin:", ignoreCase = true) ->
                AgentToolSource.PLUGIN
            first.startsWith("[Plugin command:", ignoreCase = true) ->
                AgentToolSource.PLUGIN
            first.startsWith("[MCP:", ignoreCase = true) ->
                AgentToolSource.EXTERNAL_MCP
            else -> AgentToolSource.BUILT_IN
        }
        val title = when (source) {
            AgentToolSource.BUILT_IN -> spec.name
            else -> first
                .substringAfter("]", first.removePrefix("["))
                .trim()
                .ifBlank { spec.name }
        }
        val description = when (source) {
            AgentToolSource.BUILT_IN -> first
            else -> lines.drop(1).joinToString(" ").ifBlank { title }
        }.take(320)
        return ToolPresentation(source, title, description)
    }

    private data class ToolPresentation(
        val source: AgentToolSource,
        val title: String,
        val description: String
    )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
}
