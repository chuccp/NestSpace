package com.coke.nestspace.tools

import com.coke.nestspace.agent.AssistantTaskCoordinator
import org.json.JSONObject

internal fun ToolRegistry.agentToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "spawn_agent",
        description = "Create an asynchronous assistant worker task. Complex, multi-step, " +
            "long-running, or UI-mutating work should be delegated here. The task is queued " +
            "and runs sequentially under the assistant task coordinator. This tool returns " +
            "immediately with task_id. Write title as a concise user-facing task outcome and " +
            "goal as the complete worker instruction. The title must not contain " +
            "backend waits, prompts, queue mechanics, or implementation notes. Query progress with " +
            "agent_task_status, wait with " +
            "task_wait, and never assume completion from acceptance alone.",
        inputSchema = schema(
            "title" to "string",
            "goal" to "string",
            "context" to "string",
            required = listOf("goal")
        ),
        maxWaitMs = 1_000L,
        nativeAsync = true
    ) { args, ctx ->
        val goal = args.optString("goal").trim()
        if (goal.isBlank()) {
            return@ToolSpec ToolResult(
                false,
                "goal is required",
                errorCode = "INVALID_ARGUMENT"
            )
        }
        val result = AssistantTaskCoordinator.enqueue(
            context = context,
            registry = this@agentToolSpecs,
            ownerId = ctx.ownerId,
            goal = goal,
            extraContext = args.optString("context"),
            displayMode = ctx.displayMode,
            source = if (ctx.caller == Caller.REMOTE_MCP) "mcp" else "assistant",
            displayTitle = args.optString("title").trim().ifBlank { goal.take(160) }
        )
        when (result) {
            is AssistantTaskCoordinator.EnqueueResult.Error -> ToolResult(
                false,
                result.message,
                errorCode = result.code
            )
            is AssistantTaskCoordinator.EnqueueResult.Queued -> {
                val task = result.task
                ToolResult(
                    true,
                    JSONObject()
                        .put("task_id", task.id)
                        .put("state", task.state.api)
                        .put("ui_state", task.uiState.api)
                        .put("queue_position", task.queuePosition)
                        .put("async", true)
                        .put("started", task.state == com.coke.nestspace.task.OperationTaskRegistry.State.RUNNING)
                        .put(
                            "next",
                            "Use agent_task_status for progress or task_wait for lifecycle changes."
                        )
                        .toString()
                )
            }
        }
    }
)
