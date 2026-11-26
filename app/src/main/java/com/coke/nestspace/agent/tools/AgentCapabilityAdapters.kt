package com.coke.nestspace.agent.tools

import com.coke.nestspace.agent.sdk.AgentCapability
import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentToolCall
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONObject
import java.util.UUID

internal fun ToolRegistry.capabilityToolSpecs(
    capability: AgentCapability,
    nativeAsync: Boolean = false,
    maxWaitMs: (String) -> Long = { ToolSpec.DEFAULT_MAX_WAIT_MS }
): List<ToolSpec> = capability.tools().map { tool ->
    ToolSpec(
        name = tool.descriptor.name,
        description = tool.descriptor.description,
        inputSchema = JSONObject(tool.descriptor.inputSchemaJson),
        nativeAsync = nativeAsync,
        maxWaitMs = maxWaitMs(tool.descriptor.name)
    ) { args, context ->
        val result = tool.handler.execute(
            context.toAgentRunContext(),
            AgentToolCall(
                id = UUID.randomUUID().toString(),
                name = tool.descriptor.name,
                inputJson = args.toString()
            )
        )
        result.toToolResult()
    }
}

private fun ToolContext.toAgentRunContext(): AgentRunContext = AgentRunContext(
    sessionId = sessionId,
    userId = ownerId,
    ownerId = ownerId,
    parentTaskId = parentTaskId,
    displayMode = displayMode.api,
    invocationSource = caller.name,
    userUtterances = userUtterances,
    capabilityObserver = onSkillUsed,
    attributes = mapOf(
        "source" to if (caller == Caller.REMOTE_MCP) "mcp" else "assistant"
    )
)

private fun AgentToolResult.toToolResult(): ToolResult = ToolResult(
    ok = success,
    text = output,
    errorCode = errorCode,
    pending = pending
)
