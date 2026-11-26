package com.coke.nestspace.agent.llm

import com.coke.nestspace.agent.sdk.AgentChatService
import com.coke.nestspace.agent.sdk.RecordingAgentChatService
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol

/** Records one completed model request, including usage estimated locally. */
fun interface ChatUsageSink {
    fun record(usage: ChatUsage, purpose: String)
}

/** Maps the configured protocol to its provider-neutral chat adapter. */
object ChatServices {

    fun create(
        config: LlmConfig,
        usageSink: ChatUsageSink? = null
    ): AgentChatService {
        val service = config.pluginProviderId()?.let {
            PluginChatService(config, it)
        } ?: when (config.protocol) {
            LlmProtocol.ANTHROPIC -> AnthropicChatService(config)
            LlmProtocol.OPENAI -> OpenAiChatService(config)
        }
        return if (usageSink == null) {
            service
        } else {
            RecordingAgentChatService(service, usageSink::record)
        }
    }
}
