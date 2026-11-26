package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject

data class SystemStatusSnapshot(
    val platform: String,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val availableMemoryBytes: Long? = null,
    val totalMemoryBytes: Long? = null,
    val attributes: Map<String, String> = emptyMap()
)

fun interface SystemStatusProvider {
    suspend fun snapshot(): SystemStatusSnapshot
}

class SystemStatusTool(
    private val provider: SystemStatusProvider
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "system_status",
            description = "Read authoritative host status such as battery, network and memory.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            val status = provider.snapshot()
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("platform", status.platform)
                    .put("battery_percent", status.batteryPercent ?: JSONObject.NULL)
                    .put("charging", status.charging ?: JSONObject.NULL)
                    .put("network", status.network ?: JSONObject.NULL)
                    .put(
                        "available_memory_bytes",
                        status.availableMemoryBytes ?: JSONObject.NULL
                    )
                    .put(
                        "total_memory_bytes",
                        status.totalMemoryBytes ?: JSONObject.NULL
                    )
                    .put("attributes", JSONObject(status.attributes))
                    .toString()
            )
        }
    )
}
