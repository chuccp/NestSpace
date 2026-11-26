package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONArray
import org.json.JSONObject

data class AgentSchedule(
    val id: String,
    val goal: String,
    val trigger: String,
    val enabled: Boolean
)

interface ScheduleController {
    suspend fun list(): List<AgentSchedule>
    suspend fun create(goal: String, trigger: String): AgentSchedule
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun runNow(id: String): Boolean
}

class ScheduleTool(
    private val controller: ScheduleController
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "schedule_task",
            description = "Create or manage timed Agent tasks.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "action" to "string",
                "id" to "string",
                "goal" to "string",
                "trigger" to "string"
            )
        ),
        handler = { _, call ->
            val args = JSONObject(call.inputJson)
            when (args.optString("action", "list").lowercase()) {
                "list" -> AgentToolResult(
                    true,
                    JSONObject()
                        .put(
                            "tasks",
                            JSONArray().apply {
                                controller.list().forEach { put(it.toJson()) }
                            }
                        )
                        .toString()
                )
                "create" -> {
                    val goal = args.optString("goal").trim()
                    val trigger = args.optString("trigger").trim()
                    if (goal.isBlank() || trigger.isBlank()) {
                        AgentToolResult(
                            false,
                            "goal and trigger are required",
                            "INVALID_ARGUMENT"
                        )
                    } else {
                        AgentToolResult(
                            true,
                            controller.create(goal, trigger).toJson().toString()
                        )
                    }
                }
                "pause", "resume" -> {
                    val id = args.optString("id")
                    val ok = controller.setEnabled(
                        id,
                        args.optString("action").equals("resume", true)
                    )
                    AgentToolResult(
                        ok,
                        if (ok) "schedule updated" else "schedule not found",
                        if (ok) null else "SCHEDULE_NOT_FOUND"
                    )
                }
                "delete" -> {
                    val ok = controller.delete(args.optString("id"))
                    AgentToolResult(
                        ok,
                        if (ok) "schedule deleted" else "schedule not found",
                        if (ok) null else "SCHEDULE_NOT_FOUND"
                    )
                }
                "run_now" -> {
                    val ok = controller.runNow(args.optString("id"))
                    AgentToolResult(
                        ok,
                        if (ok) "schedule started" else "schedule not found",
                        if (ok) null else "SCHEDULE_NOT_FOUND"
                    )
                }
                else -> AgentToolResult(false, "unknown action", "INVALID_ARGUMENT")
            }
        }
    )
}

private fun AgentSchedule.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("goal", goal)
    .put("trigger", trigger)
    .put("enabled", enabled)
