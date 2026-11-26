package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject

interface ClipboardProvider {
    suspend fun read(): String?
    suspend fun write(text: String)
}

class ClipboardTools(
    private val provider: ClipboardProvider
) {
    val read: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "clipboard_read",
            description = "Read the current clipboard text.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("text", provider.read() ?: JSONObject.NULL)
                    .toString()
            )
        }
    )

    val write: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "clipboard_write",
            description = "Replace the current clipboard text.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "text" to "string",
                required = listOf("text")
            )
        ),
        handler = { _, call ->
            val text = JSONObject(call.inputJson).optString("text")
            provider.write(text)
            AgentToolResult(success = true, output = "clipboard updated")
        }
    )

    fun tools(): List<AgentTool> = listOf(read, write)
}
