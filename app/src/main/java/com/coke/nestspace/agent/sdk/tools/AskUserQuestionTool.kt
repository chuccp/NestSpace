package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import org.json.JSONArray
import org.json.JSONObject

data class AgentQuestionOption(
    val label: String,
    val description: String? = null
)

data class AgentQuestion(
    val prompt: String,
    val header: String? = null,
    val options: List<AgentQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowFreeText: Boolean = true
)

data class AgentQuestionAnswer(
    val prompt: String,
    val selected: List<String> = emptyList(),
    val text: String? = null
)

enum class AgentQuestionStatus {
    ANSWERED,
    CANCELLED,
    TIMEOUT
}

data class AgentQuestionResult(
    val status: AgentQuestionStatus,
    val answers: List<AgentQuestionAnswer> = emptyList()
)

fun interface AgentQuestionHandler {
    suspend fun ask(questions: List<AgentQuestion>): AgentQuestionResult
}

/**
 * Lets the model pause and request structured information or a decision from
 * the user. This is distinct from confirmation before a side-effecting tool.
 */
class AskUserQuestionTool(
    private val handler: AgentQuestionHandler
) {
    val tool: AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "ask_user_question",
            description = "Ask the user one to four questions when required information is " +
                "missing or a decision cannot be inferred safely. Questions may contain " +
                "selectable options and allow free text.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "questions" to "string",
                required = listOf("questions")
            )
        ),
        handler = { _, call ->
            val raw = JSONObject(call.inputJson).optString("questions")
            val questions = runCatching { parseQuestions(raw) }.getOrNull()
            if (questions.isNullOrEmpty()) {
                AgentToolResult(
                    success = false,
                    output = "questions must be a non-empty JSON array",
                    errorCode = "INVALID_ARGUMENT"
                )
            } else {
                val result = handler.ask(questions)
                when (result.status) {
                    AgentQuestionStatus.ANSWERED -> AgentToolResult(
                        success = true,
                        output = JSONObject()
                            .put(
                                "answers",
                                JSONArray().apply {
                                    result.answers.forEach { answer ->
                                        put(
                                            JSONObject()
                                                .put("prompt", answer.prompt)
                                                .put(
                                                    "selected",
                                                    JSONArray(answer.selected)
                                                )
                                                .put(
                                                    "text",
                                                    answer.text ?: JSONObject.NULL
                                                )
                                        )
                                    }
                                }
                            )
                            .toString()
                    )
                    AgentQuestionStatus.CANCELLED -> AgentToolResult(
                        success = false,
                        output = "user cancelled the question",
                        errorCode = "USER_CANCELLED"
                    )
                    AgentQuestionStatus.TIMEOUT -> AgentToolResult(
                        success = false,
                        output = "user did not answer in time",
                        errorCode = "USER_TIMEOUT"
                    )
                }
            }
        }
    )

    private fun parseQuestions(raw: String): List<AgentQuestion> {
        val array = JSONArray(raw)
        require(array.length() in 1..4) { "expected one to four questions" }
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val prompt = json.optString("question").trim()
            require(prompt.isNotBlank()) { "question is required" }
            val options = json.optJSONArray("options")?.let { values ->
                (0 until values.length()).mapNotNull { optionIndex ->
                    when (val value = values.opt(optionIndex)) {
                        is JSONObject -> {
                            val label = value.optString("label").trim()
                            label.takeIf(String::isNotBlank)?.let {
                                AgentQuestionOption(
                                    label = it,
                                    description = value.optString("description")
                                        .trim()
                                        .takeIf(String::isNotBlank)
                                )
                            }
                        }
                        is String -> value.trim().takeIf(String::isNotBlank)
                            ?.let { AgentQuestionOption(it) }
                        else -> null
                    }
                }
            }.orEmpty()
            AgentQuestion(
                prompt = prompt,
                header = json.optString("header").trim().takeIf(String::isNotBlank),
                options = options,
                multiSelect = json.optBoolean("multi_select", false),
                allowFreeText = json.optBoolean("allow_free_text", true)
            )
        }
    }
}
