package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject

data class AgentSpeechStatus(
    val speaking: Boolean,
    val provider: String? = null
)

interface SpeechProvider {
    suspend fun speak(text: String, interrupt: Boolean)
    suspend fun stop()
    suspend fun status(): AgentSpeechStatus
}

class SpeechTools(
    private val provider: SpeechProvider
) {
    val speak: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "speech_speak",
            description = "Speak text through the host speech provider.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "text" to "string",
                "interrupt" to "boolean",
                required = listOf("text")
            )
        ),
        handler = { _, call ->
            val args = JSONObject(call.inputJson)
            provider.speak(args.optString("text"), args.optBoolean("interrupt", false))
            AgentToolResult(true, "speech started")
        }
    )

    val stop: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "speech_stop",
            description = "Stop current speech playback.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            provider.stop()
            AgentToolResult(true, "speech stopped")
        }
    )

    val status: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "speech_status",
            description = "Read current speech playback status.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            val status = provider.status()
            AgentToolResult(
                true,
                JSONObject()
                    .put("speaking", status.speaking)
                    .put("provider", status.provider ?: JSONObject.NULL)
                    .toString()
            )
        }
    )

    fun tools(): List<AgentTool> = listOf(speak, stop, status)
}
