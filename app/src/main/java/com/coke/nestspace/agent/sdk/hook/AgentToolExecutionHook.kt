package com.coke.nestspace.agent.sdk.hook

import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentToolCall
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult

sealed interface AgentToolHookDecision {
    data class Proceed(val call: AgentToolCall) : AgentToolHookDecision

    data class Block(
        val code: String,
        val message: String
    ) : AgentToolHookDecision
}

/**
 * Optional tool lifecycle hook. Hooks are ordered and can rewrite the call,
 * block execution or transform the final result.
 */
interface AgentToolExecutionHook {
    suspend fun beforeExecute(
        tool: AgentToolDescriptor,
        context: AgentRunContext,
        call: AgentToolCall
    ): AgentToolHookDecision = AgentToolHookDecision.Proceed(call)

    suspend fun afterExecute(
        tool: AgentToolDescriptor,
        context: AgentRunContext,
        call: AgentToolCall,
        result: AgentToolResult
    ): AgentToolResult = result
}
