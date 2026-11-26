package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject

enum class HandoffResult {
    COMPLETED,
    SKIPPED,
    CANCELLED,
    TIMEOUT
}

fun interface HandoffHandler {
    suspend fun handToUser(reason: String): HandoffResult
}

class HandoffTool(
    private val handler: HandoffHandler
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "hand_to_user",
            description = "Hand the current task to the user for a step only the user can " +
                "complete, such as login, verification or payment.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "reason" to "string",
                required = listOf("reason")
            )
        ),
        handler = { _, call ->
            val reason = JSONObject(call.inputJson).optString("reason").trim()
            if (reason.isBlank()) {
                AgentToolResult(false, "reason is required", "INVALID_ARGUMENT")
            } else {
                val result = handler.handToUser(reason)
                AgentToolResult(
                    success = result == HandoffResult.COMPLETED,
                    output = JSONObject()
                        .put("result", result.name.lowercase())
                        .toString(),
                    errorCode = when (result) {
                        HandoffResult.COMPLETED -> null
                        HandoffResult.SKIPPED -> "HANDOFF_SKIPPED"
                        HandoffResult.CANCELLED -> "HANDOFF_CANCELLED"
                        HandoffResult.TIMEOUT -> "HANDOFF_TIMEOUT"
                    }
                )
            }
        }
    )
}
