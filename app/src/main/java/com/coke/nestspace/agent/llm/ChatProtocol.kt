package com.coke.nestspace.agent.llm

import com.coke.nestspace.agent.sdk.AgentChatInput
import com.coke.nestspace.agent.sdk.AgentChatMessage
import com.coke.nestspace.agent.sdk.AgentChatResponse
import com.coke.nestspace.agent.sdk.AgentChatRole
import com.coke.nestspace.agent.sdk.AgentChatTool
import com.coke.nestspace.agent.sdk.AgentChatUsage
import com.coke.nestspace.agent.sdk.AgentReasoningEffort
import com.coke.nestspace.data.ReasoningEffort

typealias ChatRole = AgentChatRole
typealias ChatMessage = AgentChatMessage
typealias ChatTool = AgentChatTool
typealias ChatRequest = AgentChatInput
typealias ChatResponse = AgentChatResponse
typealias ChatUsage = AgentChatUsage

fun ReasoningEffort.toAgentReasoningEffort(): AgentReasoningEffort =
    AgentReasoningEffort.valueOf(name)
