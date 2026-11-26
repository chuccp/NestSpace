package com.coke.nestspace.agent.llm

import android.content.Context
import com.coke.nestspace.agent.sdk.AgentChatBlock
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LlmProbe {

    suspend fun test(config: LlmConfig, context: Context? = null): String =
        withContext(Dispatchers.IO) {
            val config = config.withNormalizedEndpoint()
            if (!config.isConfigured) {
                throw IllegalArgumentException(
                    if (config.pluginProviderId() != null) {
                        AppLanguage.tr(
                            "插件模型不能为空",
                            "The plugin model must not be empty"
                        )
                    } else {
                        AppLanguage.tr(
                            "Base URL、模型和 API Key 不能为空",
                            "Base URL, model and API key must not be empty"
                        )
                    }
                )
            }
            val sink = context?.let { app ->
                ChatUsageSink { usage, purpose ->
                    TokenUsageStore.get(app).record(config, usage, kind = purpose)
                }
            }
            val response = ChatServices.create(config, sink).chat(
                ChatRequest(
                    model = config.model,
                    system = AppLanguage.tr(
                        "你是连通性测试助手。",
                        "You are a connectivity test assistant."
                    ),
                    messages = listOf(
                        ChatMessage(
                            ChatRole.USER,
                            listOf(
                                AgentChatBlock.Text(
                                    AppLanguage.tr("只回复 pong", "Reply with pong only")
                                )
                            )
                        )
                    ),
                    tools = emptyList(),
                    maxTokens = 64,
                    temperature = config.temperature,
                    reasoningEffort = ReasoningEffort.OFF.toAgentReasoningEffort(),
                    purpose = "probe"
                )
            )
            response.text.trim().ifBlank {
                throw IllegalStateException(
                    AppLanguage.tr(
                        "连接成功，但响应没有文本",
                        "Connected, but the response contained no text"
                    )
                )
            }
        }
}
