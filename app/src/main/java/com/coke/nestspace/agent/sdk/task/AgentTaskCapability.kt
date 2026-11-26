package com.coke.nestspace.agent.sdk.task

import com.coke.nestspace.agent.sdk.AgentCapability
import com.coke.nestspace.agent.sdk.AgentKey
import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import com.coke.nestspace.agent.sdk.tools.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

class AgentTaskCapability(
    private val controller: AgentTaskController,
    private val targetAgent: AgentKey
) : AgentCapability {
    override val id: String = "agent-tasks"
    override val description: String =
        "Start and control asynchronous peer Agent tasks."

    override fun tools(): List<AgentTool> = listOf(
        spawnTool(),
        listTool(),
        statusTool(),
        supplementTool(),
        pauseTool(),
        resumeTool(),
        cancelTool(),
        cancelQueuedTool()
    )

    override fun instructions(): String =
        "Delegate complex, multi-step or long-running work with spawn_agent. " +
            "The call returns after the task is queued; status is asynchronous."

    private fun spawnTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "spawn_agent",
            description = "Create an asynchronous assistant worker task. Complex, " +
                "multi-step, long-running, or UI-mutating work should be delegated here. " +
                "The task is queued and runs sequentially under the assistant task " +
                "coordinator. This tool returns immediately with task_id. Write title as " +
                "a concise user-facing task outcome and goal as the complete worker " +
                "instruction. The title must not contain backend waits, prompts, queue " +
                "mechanics, or implementation notes. Query progress with " +
                "agent_task_status when the user next asks. End the current turn after " +
                "spawning; do not wait or poll for worker progress, and never assume " +
                "completion from acceptance alone.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "title" to "string",
                "goal" to "string",
                "context" to "string",
                required = listOf("goal")
            ),
            usagePrompt = "After spawning an Agent task, finish the current turn."
        ),
        handler = { context, call ->
            val args = JSONObject(call.inputJson)
            val result = controller.start(
                AgentTaskRequest(
                    agentKey = targetAgent,
                    goal = args.optString("goal"),
                    context = context,
                    extraContext = args.optString("context"),
                    title = args.optString("title").takeIf(String::isNotBlank),
                    source = context.attributes["source"] ?: context.invocationSource
                )
            )
            when (result) {
                is AgentTaskStartResult.Rejected -> AgentToolResult(
                    success = false,
                    output = result.message,
                    errorCode = result.code
                )
                is AgentTaskStartResult.Started -> AgentToolResult(
                    success = true,
                    output = JSONObject()
                        .put("task_id", result.task.id)
                        .put("state", result.task.state.api)
                        .put("ui_state", result.task.uiState ?: JSONObject.NULL)
                        .put("queue_position", result.task.queuePosition)
                        .put("async", true)
                        .put("started", result.task.state == AgentTaskState.RUNNING)
                        .put(
                            "next",
                            "End this turn. The host will provide task status when the user asks."
                        )
                        .toString()
                )
            }
        }
    )

    private fun listTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "agent_task_list",
            description = "List assistant worker tasks, including active, queued and recent tasks.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { context, _ ->
            val tasks = controller.list(context.ownerId)
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
                    .toString()
            )
        }
    )

    private fun statusTool(): AgentTool = taskTool(
        name = "agent_task_status",
        description = "Read one assistant worker task, its latest progress, revisions and " +
            "available action."
    ) { context, args ->
        controller.status(args.optString("task_id"), context.ownerId)
    }

    private fun supplementTool(): AgentTool = AgentTool(
        descriptor = taskDescriptor(
            name = "agent_task_supplement",
            description = "Append a user follow-up instruction to a running or paused assistant task.",
            text = true
        ),
        handler = { context, call ->
            val args = JSONObject(call.inputJson)
            val taskId = args.optString("task_id")
            val text = args.optString("text")
            if (text.isBlank()) {
                AgentToolResult(false, "text is required", "INVALID_ARGUMENT")
            } else if (!controller.supplement(taskId, text, context.ownerId)) {
                AgentToolResult(
                    false,
                    "task is not available for follow-up",
                    "TASK_NOT_RUNNING"
                )
            } else {
                AgentToolResult(
                    true,
                    JSONObject().put("task_id", taskId).put("status", "queued").toString()
                )
            }
        }
    )

    private fun pauseTool(): AgentTool = taskTool(
        name = "agent_task_pause",
        description = "Pause the active assistant worker at a safe step."
    ) { context, args ->
        controller.pause(args.optString("task_id"), context.ownerId)
    }

    private fun resumeTool(): AgentTool = taskTool(
        name = "agent_task_resume",
        description = "Resume a paused assistant worker after re-observing the current screen."
    ) { context, args ->
        controller.resume(args.optString("task_id"), context.ownerId)
    }

    private fun cancelTool(): AgentTool = taskTool(
        name = "agent_task_cancel",
        description = "Cancel one queued, paused or running assistant worker task."
    ) { context, args ->
        controller.cancel(args.optString("task_id"), context.ownerId)
    }

    private fun cancelQueuedTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "agent_task_cancel_queued",
            description = "Cancel all queued assistant worker tasks without touching the active task.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { context, _ ->
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("cancelled", controller.cancelQueued(context.ownerId))
                    .toString()
            )
        }
    )

    private fun taskTool(
        name: String,
        description: String,
        action: suspend (
            com.coke.nestspace.agent.sdk.AgentRunContext,
            JSONObject
        ) -> AgentTaskSnapshot?
    ): AgentTool = AgentTool(
        descriptor = taskDescriptor(name, description),
        handler = { context, call ->
            val task = action(context, JSONObject(call.inputJson))
            if (task == null) {
                AgentToolResult(
                    success = false,
                    output = "task not found",
                    errorCode = "TASK_NOT_FOUND"
                )
            } else {
                AgentToolResult(true, task.toJson().toString())
            }
        }
    )

    private fun taskDescriptor(
        name: String,
        description: String,
        text: Boolean = false
    ): AgentToolDescriptor {
        val properties = if (text) {
            arrayOf("task_id" to "string", "text" to "string")
        } else {
            arrayOf("task_id" to "string")
        }
        return AgentToolDescriptor(
            name = name,
            description = description,
            inputSchemaJson = AgentToolSchema.objectSchema(
                *properties,
                required = properties.map { it.first }
            )
        )
    }
}

private fun AgentTaskSnapshot.toJson(): JSONObject = JSONObject()
    .put("task_id", id)
    .put("goal", goal.take(2_000))
    .put("state", state.api)
    .put("queue_position", queuePosition)
    .put("progress", progress.take(800))
    .put("state_revision", stateRevision)
    .put("progress_revision", progressRevision)
    .put("idle_ms", idleMilliseconds)
    .put("result", result?.take(8_000) ?: JSONObject.NULL)
    .put("error", error?.take(2_000) ?: JSONObject.NULL)
    .put("ui_state", uiState ?: JSONObject.NULL)
    .put("primary_action", primaryAction ?: JSONObject.NULL)
    .put("can_cancel", canCancel)
    .put("created_at", createdAt)
    .put("updated_at", updatedAt)
