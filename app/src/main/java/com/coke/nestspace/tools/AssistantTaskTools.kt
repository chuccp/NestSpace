package com.coke.nestspace.tools

import com.coke.nestspace.agent.AssistantTaskCoordinator
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.assistantTaskToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "agent_task_list",
        description = "List assistant worker tasks, including active, queued and recent tasks.",
        inputSchema = schema(),
        nativeAsync = true
    ) { _, ctx ->
        val tasks = AssistantTaskCoordinator.list(ctx.ownerId)
        ToolResult(
            true,
            JSONObject()
                .put("tasks", JSONArray().apply {
                    tasks.forEach { put(taskJson(it)) }
                })
                .toString()
        )
    },
    ToolSpec(
        name = "agent_task_status",
        description = "Read one assistant worker task, its queue position and available action.",
        inputSchema = schema("task_id" to "string", required = listOf("task_id")),
        nativeAsync = true
    ) { args, ctx ->
        val taskId = args.optString("task_id").trim()
        val task = AssistantTaskCoordinator.get(taskId, ctx.ownerId)
            ?: return@ToolSpec ToolResult(
                false,
                "task not found",
                errorCode = "TASK_NOT_FOUND"
            )
        ToolResult(true, taskJson(task).toString())
    },
    ToolSpec(
        name = "agent_task_supplement",
        description = "Append a user follow-up instruction to a running or paused assistant task.",
        inputSchema = schema(
            "task_id" to "string",
            "text" to "string",
            required = listOf("task_id", "text")
        ),
        nativeAsync = true
    ) { args, ctx ->
        val taskId = args.optString("task_id").trim()
        val text = args.optString("text").trim()
        if (text.isBlank()) {
            return@ToolSpec ToolResult(
                false,
                "text is required",
                errorCode = "INVALID_ARGUMENT"
            )
        }
        if (!AssistantTaskCoordinator.supplement(taskId, text, ctx.ownerId)) {
            return@ToolSpec ToolResult(
                false,
                "task is not available for follow-up",
                errorCode = "TASK_NOT_RUNNING"
            )
        }
        ToolResult(
            true,
            JSONObject().put("task_id", taskId).put("status", "queued").toString()
        )
    },
    ToolSpec(
        name = "agent_task_pause",
        description = "Pause the active assistant worker at a safe step.",
        inputSchema = schema("task_id" to "string", required = listOf("task_id")),
        nativeAsync = true
    ) { args, ctx ->
        val taskId = args.optString("task_id").trim()
        val task = AssistantTaskCoordinator.pause(taskId, ctx.ownerId)
            ?: return@ToolSpec ToolResult(
                false,
                "task not found",
                errorCode = "TASK_NOT_FOUND"
            )
        ToolResult(true, taskJson(task).toString())
    },
    ToolSpec(
        name = "agent_task_resume",
        description = "Resume a paused assistant worker after re-observing the current screen.",
        inputSchema = schema("task_id" to "string", required = listOf("task_id")),
        nativeAsync = true
    ) { args, ctx ->
        val taskId = args.optString("task_id").trim()
        val task = AssistantTaskCoordinator.resume(context, this@assistantTaskToolSpecs, taskId, ctx.ownerId)
            ?: return@ToolSpec ToolResult(
                false,
                "task not found",
                errorCode = "TASK_NOT_FOUND"
            )
        ToolResult(true, taskJson(task).toString())
    },
    ToolSpec(
        name = "agent_task_cancel",
        description = "Cancel one queued, paused or running assistant worker task.",
        inputSchema = schema("task_id" to "string", required = listOf("task_id")),
        nativeAsync = true
    ) { args, ctx ->
        val taskId = args.optString("task_id").trim()
        val task = AssistantTaskCoordinator.cancel(taskId, ctx.ownerId)
            ?: return@ToolSpec ToolResult(
                false,
                "task not found",
                errorCode = "TASK_NOT_FOUND"
            )
        ToolResult(true, taskJson(task).toString())
    },
    ToolSpec(
        name = "agent_task_cancel_queued",
        description = "Cancel all queued assistant worker tasks without touching the active task.",
        inputSchema = schema(),
        nativeAsync = true
    ) { _, ctx ->
        val count = AssistantTaskCoordinator.cancelQueued(ctx.ownerId)
        ToolResult(
            true,
            JSONObject().put("cancelled", count).toString()
        )
    }
)

private fun taskJson(task: AssistantTaskCoordinator.TaskView): JSONObject = JSONObject()
    .put("task_id", task.id)
    .put("goal", task.goal.take(2_000))
    .put("state", task.state.api)
    .put("ui_state", task.uiState.api)
    .put("primary_action", task.primaryAction.api)
    .put("can_cancel", task.canCancel)
    .put("queue_position", task.queuePosition)
    .put("progress", task.progress.take(800))
    .put("result", task.result?.take(8_000) ?: JSONObject.NULL)
    .put("error", task.error?.take(2_000) ?: JSONObject.NULL)
    .put("created_at", task.createdAt)
    .put("updated_at", task.updatedAt)
