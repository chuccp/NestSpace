package com.coke.nestspace.agent.sdk.skill

import com.coke.nestspace.agent.sdk.AgentChatBlock
import com.coke.nestspace.agent.sdk.AgentChatMessage
import com.coke.nestspace.agent.sdk.AgentChatOptions
import com.coke.nestspace.agent.sdk.AgentChatRole
import com.coke.nestspace.agent.sdk.AgentChatService
import com.coke.nestspace.agent.sdk.memory.MemoryPolicy
import org.json.JSONObject

/**
 * Optional post-run drafting of reusable skills.
 */
class SkillDraftingCapability(
    private val chatService: AgentChatService,
    private val store: AgentSkillStore,
    private val chatOptions: AgentChatOptions,
    private val ignoredTools: Set<String> = emptySet()
) {
    fun eligible(toolNames: List<String>, minToolSteps: Int = 4): Boolean {
        if (toolNames.size < minToolSteps) return false
        if ("skill_save" in toolNames) return false
        return toolNames.any { it !in ignoredTools }
    }

    suspend fun draft(
        goal: String,
        answer: String,
        toolNames: List<String>,
        existing: List<SkillItem>,
        minToolSteps: Int = 4
    ): SkillItem? {
        if (!eligible(toolNames, minToolSteps)) return null
        val existingNames = existing.map { it.name }.toSet()
        val response = runCatching {
            chatService.chat(
                chatOptions.toInput(
                    system = SYSTEM_PROMPT,
                    messages = listOf(
                        AgentChatMessage(
                            AgentChatRole.USER,
                            listOf(
                                AgentChatBlock.Text(
                                    userPrompt(goal, answer, toolNames, existing)
                                )
                            )
                        )
                    ),
                    tools = emptyList()
                )
            )
        }.getOrNull() ?: return null
        val json = parse(response.text) ?: return null
        if (json.optBoolean("skip", false)) return null
        val name = SkillPolicy.normalizeName(json.optString("name"))
        val description = json.optString("description").trim()
        val body = json.optString("body").trim()
        if (!SkillPolicy.isValidName(name) || name in existingNames) return null
        if (description.isBlank() || body.isBlank()) return null
        if (body.length > SkillPolicy.MAX_BODY) return null
        if (existing.any {
                it.name == name || it.description.equals(description, true)
            }
        ) {
            return null
        }
        if (listOf(body, description).any {
                MemoryPolicy.sensitiveReason(it) != null
            }
        ) {
            return null
        }
        return store.save(
            name = name,
            description = description,
            body = body,
            source = "auto: ${goal.take(200)}",
            enabled = false
        ).getOrNull()
    }

    private fun parse(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun userPrompt(
        goal: String,
        answer: String,
        toolNames: List<String>,
        existing: List<SkillItem>
    ): String = buildString {
        appendLine("任务目标：")
        appendLine(goal.take(1_500))
        appendLine()
        appendLine("最终结果：")
        appendLine(answer.take(2_500))
        appendLine()
        appendLine("实际调用过的工具（按顺序）：")
        toolNames.take(60).forEach { appendLine("- $it") }
        appendLine()
        if (existing.isEmpty()) {
            append("已有技能：无")
        } else {
            appendLine("已有技能（不要重复或换名重复）：")
            existing.take(30).forEach {
                appendLine("- ${it.name}: ${it.description}")
            }
        }
    }

    private companion object {
        val SYSTEM_PROMPT = """
            你负责把一次成功的多步骤任务整理成可复用的技能。只输出 JSON，不要 Markdown 代码块：
            {"name":"lowercase-hyphen-name","description":"一句话说明何时使用","body":"Markdown 正文"}
            如果这次任务只是一次性操作、没有可复用步骤，或已有技能已经覆盖，输出 {"skip":true}。

            body 要求：
            - 用 Markdown 写清适用场景、入口、步骤、容易出错的地方、怎样算完成、何时交给用户。
            - 用 <参数> 代替具体地址、账号、金额、日期、订单号等一次性值。
            - 不要写入密码、验证码、银行卡、身份证、API Key，也不要抄录网页原文。
            - 步骤要来自实际执行过程，不要编造未使用的工具。
            - 名称只使用小写字母、数字和连字符。
        """.trimIndent()
    }
}
