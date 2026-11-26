package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject

data class AgentHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
) {
    init {
        require(method.isNotBlank()) { "HTTP method is required" }
        require(url.isNotBlank()) { "HTTP URL is required" }
    }
}

data class AgentHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

fun interface HttpToolClient {
    suspend fun execute(request: AgentHttpRequest): AgentHttpResponse
}

class HttpRequestTool(
    private val client: HttpToolClient
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "http_request",
            description = "Send an HTTP request to an external service. Network policy, " +
                "credentials and response limits are enforced by the host client.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "method" to "string",
                "url" to "string",
                "headers" to "string",
                "body" to "string",
                required = listOf("method", "url")
            )
        ),
        handler = { _, call ->
            val args = JSONObject(call.inputJson)
            val headers = parseHeaders(args.optString("headers", "{}"))
            if (headers == null) {
                AgentToolResult(
                    success = false,
                    output = "headers must be a JSON object",
                    errorCode = "INVALID_ARGUMENT"
                )
            } else {
                val response = client.execute(
                    AgentHttpRequest(
                        method = args.optString("method"),
                        url = args.optString("url"),
                        headers = headers,
                        body = args.optString("body").takeIf(String::isNotEmpty)
                    )
                )
                AgentToolResult(
                    success = response.statusCode in 200..399,
                    output = JSONObject()
                        .put("status_code", response.statusCode)
                        .put("headers", JSONObject(response.headers))
                        .put("body", response.body)
                        .toString(),
                    errorCode = if (response.statusCode in 200..399) {
                        null
                    } else {
                        "HTTP_${response.statusCode}"
                    }
                )
            }
        }
    )

    private fun parseHeaders(raw: String): Map<String, String>? = runCatching {
        JSONObject(raw.ifBlank { "{}" })
    }.getOrNull()?.let { values ->
        values.keys().asSequence().associateWith(values::optString)
    }
}
