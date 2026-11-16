package com.coke.nestspace.agent

import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSettingsTest {

    @Test
    fun `catalog only lists tools registered by plugins`() {
        val pluginName = "plugin_com_example_runtime_demo_runtime_demo"
        val tools = AgentToolSettings.catalogFromSpecs(
            specs = listOf(
                spec("ui_observe", "Inspect the current app UI"),
                spec("appfn_list", "List available AppFunctions"),
                spec(
                    "plugin_com_example_runtime_demo_runtime_demo",
                    "[Plugin: Runtime Demo Plugin] Runtime demo\nExercise plugin APIs"
                ),
                spec(
                    "mcp_search_search",
                    "[MCP: Search] Search the web"
                ),
                spec("memory_search", "Search memories"),
                spec("skill_list", "List skills"),
                spec("plugin_list", "List plugins"),
                spec("schedule_task", "Manage schedules"),
                spec("speech_speak", "Speak text")
            ),
            disabled = emptySet()
        )

        assertEquals(1, tools.size)
        assertEquals(pluginName, tools.single().name)
        assertEquals(AgentToolSource.PLUGIN, tools.single().source)
        assertTrue(tools.all { it.enabled })
    }

    @Test
    fun `disabled plugin tools remain listed but are switched off`() {
        val name = "plugin_com_example"
        val tool = AgentToolSettings.catalogFromSpecs(
            specs = listOf(
                spec(
                    name,
                    "[Plugin: Example] Example tool\nDescription"
                )
            ),
            disabled = setOf(name)
        ).single()

        assertFalse(tool.enabled)
        assertEquals(name, tool.name)

        val enabledSpecs = AgentToolSettings.enabledToolSpecs(
            specs = listOf(
                spec(
                    name,
                    "[Plugin: Example] Example tool\nDescription"
                )
            ),
            disabled = setOf(name)
        )
        assertTrue(enabledSpecs.isEmpty())
    }

    @Test
    fun `built in tools are never listed or disabled`() {
        val names = listOf(
            "ui_observe",
            "memory_search",
            "skill_list",
            "plugin_list",
            "appfn_list",
            "schedule_task"
        )
        val specs = names.map { spec(it, "Built-in tool") }
        assertTrue(
            AgentToolSettings.catalogFromSpecs(
                specs = specs,
                disabled = emptySet()
            ).isEmpty()
        )
        assertEquals(
            names,
            AgentToolSettings.enabledToolSpecs(
                specs = specs,
                disabled = names.toSet()
            ).map { it.name }
        )
    }

    private fun spec(name: String, description: String): ToolSpec =
        ToolSpec(
            name = name,
            description = description,
            inputSchema = JSONObject(),
            handler = { _, _ -> ToolResult(true, "ok") }
        )
}
