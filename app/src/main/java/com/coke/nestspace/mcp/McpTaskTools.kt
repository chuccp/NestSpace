package com.coke.nestspace.mcp

import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.task.OperationTaskRegistry
import com.coke.nestspace.tools.ContainerDisplayMode
import com.coke.nestspace.tools.ToolRegistry
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import com.coke.nestspace.tools.toolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP compatibility adapter over the shared assistant Worker coordinator.
 */
object McpTaskTools {

    fun specs(registry: ToolRegistry): List<ToolSpec> = listOf(
        ToolSpec(
            name = "nestspace_run_task",
            description = "Start a shared assistant Worker task asynchronously and return a " +
                "task_id immediately. Use nestspace_task_status or nestspace_wait_task to " +
                "follow it. Pass request_id to make retries idempotent. display_mode is " +
                "background, mirror or visible.",
            inputSchema = toolSchema(
                "goal" to "string",
                "request_id" to "string",
                "display_mode" to "string",
                required = listOf("goal")
            ).apply {
                getJSONObject("properties").getJSONObject("display_mode").put(
                    "enum",
                    JSONArray(listOf("background", "mirror", "visible"))
                )
            }
        ) { args, ctx ->
            val goal = args.optString("goal").trim()
            if (goal.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "goal is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            when (
                val result = AssistantTaskCoordinator.enqueue(
                    context = registry.context,
                    registry = registry,
                    ownerId = ctx.ownerId,
                    goal = goal,
                    extraContext = "",
                    displayMode = ContainerDisplayMode.parse(
                        args.optString("display_mode"),
                        ContainerDisplayMode.BACKGROUND
                    ),
                    requestId = args.optString("request_id")
                        .trim()
                        .takeIf(String::isNotBlank),
                    source = "mcp"
                )
            ) {
                is AssistantTaskCoordinator.EnqueueResult.Queued ->
                    ToolResult(true, taskJson(result.task).toString())
                is AssistantTaskCoordinator.EnqueueResult.Error ->
                    ToolResult(false, result.message, errorCode = result.code)
            }
        },
        ToolSpec(
            name = "nestspace_steer_task",
            description = "Append a follow-up instruction to a running or paused shared Worker task.",
            inputSchema = toolSchema(
                "task_id" to "string",
                "text" to "string",
                required = listOf("task_id", "text")
            )
        ) { args, ctx ->
            val id = args.optString("task_id").trim()
            val text = args.optString("text").trim()
            if (text.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "text is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            if (!AssistantTaskCoordinator.supplement(id, text, ctx.ownerId)) {
                return@ToolSpec ToolResult(
                    false,
                    "task is not available for follow-up: $id",
                    errorCode = "TASK_NOT_RUNNING"
                )
            }
            val task = AssistantTaskCoordinator.get(id, ctx.ownerId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).put("steered", true).toString())
        },
        ToolSpec(
            name = "nestspace_task_status",
            description = "Return one shared Worker task's state, progress and answer.",
            inputSchema = toolSchema("task_id" to "string", required = listOf("task_id"))
        ) { args, ctx ->
            val id = args.optString("task_id").trim()
            val task = AssistantTaskCoordinator.get(id, ctx.ownerId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "nestspace_wait_task",
            description = "Wait for a shared Worker task lifecycle change or timeout.",
            inputSchema = toolSchema(
                "task_id" to "string",
                "timeout_s" to "integer",
                required = listOf("task_id")
            )
        ) { args, ctx ->
            val id = args.optString("task_id").trim()
            val timeout = args.optInt("timeout_s", 30).coerceIn(1, 50)
            val wait = OperationTaskRegistry.wait(id, timeout * 1_000L, ctx.ownerId)
            val task = AssistantTaskCoordinator.get(id, ctx.ownerId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(
                true,
                taskJson(task)
                    .put("changed", wait.changed)
                    .put("timed_out", wait.timedOut)
                    .toString()
            )
        },
        ToolSpec(
            name = "nestspace_cancel_task",
            description = "Cancel one queued, paused or running shared Worker task.",
            inputSchema = toolSchema("task_id" to "string", required = listOf("task_id"))
        ) { args, ctx ->
            val id = args.optString("task_id").trim()
            val task = AssistantTaskCoordinator.cancel(id, ctx.ownerId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "nestspace_list_tasks",
            description = "List recent shared Worker tasks, newest first.",
            inputSchema = toolSchema("limit" to "integer")
        ) { args, ctx ->
            val limit = args.optInt("limit", 10).coerceIn(1, 50)
            val array = JSONArray()
            AssistantTaskCoordinator.list(ctx.ownerId)
                .sortedByDescending { it.createdAt }
                .take(limit)
                .forEach { array.put(taskJson(it)) }
            ToolResult(true, JSONObject().put("tasks", array).toString())
        }
    )

    private fun taskJson(task: AssistantTaskCoordinator.TaskView): JSONObject = JSONObject()
        .put("task_id", task.id)
        .put("status", mcpStatus(task.state))
        .put("ui_state", task.uiState.api)
        .put("primary_action", task.primaryAction.api)
        .put("can_cancel", task.canCancel)
        .put("goal", task.goal)
        .put("created_at", task.createdAt)
        .put("started_at", task.startedAt)
        .put("finished_at", task.finishedAt)
        .put("answer", task.result.orEmpty())
        .put("error", task.error ?: "")
        .put("progress", task.progress)
        .put("queue_position", task.queuePosition)
        .put("revision", task.updatedAt)
        .put("request_id", task.requestId ?: JSONObject.NULL)
        .put("display_mode", task.displayMode.api)

    private fun mcpStatus(state: OperationTaskRegistry.State): String = when (state) {
        OperationTaskRegistry.State.QUEUED,
        OperationTaskRegistry.State.PREPARING -> "queued"
        OperationTaskRegistry.State.RUNNING,
        OperationTaskRegistry.State.FINALIZING -> "running"
        OperationTaskRegistry.State.WAITING_USER -> "waiting_for_user"
        OperationTaskRegistry.State.PAUSED -> "paused"
        OperationTaskRegistry.State.CANCELLING -> "cancelling"
        OperationTaskRegistry.State.SUCCEEDED -> "done"
        OperationTaskRegistry.State.FAILED -> "failed"
        OperationTaskRegistry.State.CANCELLED -> "cancelled"
    }
}
