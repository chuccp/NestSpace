package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CurrentTimeTool(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault()
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "current_time",
            description = "Return the current local date, time and UTC offset.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            val now = clock.instant().atZone(zoneId)
            val formatter = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss XXX",
                locale
            )
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("local", now.format(formatter))
                    .put("timezone", now.zone.id)
                    .put("epoch_millis", now.toInstant().toEpochMilli())
                    .toString()
            )
        }
    )

}
