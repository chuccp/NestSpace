package com.coke.nestspace.agent.sdk.tools

import org.json.JSONArray
import org.json.JSONObject

object AgentToolSchema {
    fun objectSchema(
        vararg properties: Pair<String, String>,
        required: List<String> = emptyList()
    ): String {
        val fields = JSONObject()
        properties.forEach { (name, type) ->
            fields.put(name, JSONObject().put("type", type))
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", fields)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString()
    }
}
