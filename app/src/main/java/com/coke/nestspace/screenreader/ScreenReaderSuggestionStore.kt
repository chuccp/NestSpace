package com.coke.nestspace.screenreader

import com.coke.nestspace.i18n.AppLanguage
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class ScreenReaderActionType(val api: String) {
    EXPLAIN("explain"),
    EXTRACT("extract"),
    COMPARE("compare"),
    NAVIGATE("navigate"),
    TRANSLATE("translate"),
    SEARCH("search"),
    OPERATE("operate"),
    OPEN_APP("open_app");

    companion object {
        fun fromApi(value: String?): ScreenReaderActionType? =
            entries.firstOrNull { it.api == value?.trim()?.lowercase() }

        fun fromLegacy(value: String?): ScreenReaderActionType? = when (
            value?.trim()?.lowercase()
        ) {
            "understand", "assistive_read" -> EXPLAIN
            "find" -> SEARCH
            "translate" -> TRANSLATE
            "operate" -> OPERATE
            "navigate" -> NAVIGATE
            "open_app" -> OPEN_APP
            "extract" -> EXTRACT
            "compare" -> COMPARE
            else -> fromApi(value)
        }
    }
}

data class ScreenReaderSuggestion(
    val type: ScreenReaderActionType,
    val label: String,
    val goal: String,
    val recommended: Boolean = false
)

/**
 * Keeps the short-lived suggestion list attached to one screen-reader task.
 * The data is intentionally in-memory only: suggestions describe the current
 * screen and must not leak into a later reading session.
 */
object ScreenReaderSuggestionStore {
    private const val MAX_SUGGESTIONS = 4
    private val entries = ConcurrentHashMap<String, List<ScreenReaderSuggestion>>()

    fun saveFromDocument(taskId: String?, document: JSONObject) {
        val task = taskId?.takeIf(String::isNotBlank) ?: return
        val intents = document.optJSONArray("suggested_intents") ?: return
        val suggestions = buildList {
            for (index in 0 until intents.length()) {
                val intent = intents.optJSONObject(index) ?: continue
                val type = ScreenReaderActionType.fromLegacy(
                    intent.optString("type")
                ) ?: continue
                val label = cleanLabel(intent.optString("label"))
                if (label.isBlank()) continue
                add(
                    ScreenReaderSuggestion(
                        type = type,
                        label = label,
                        goal = goalFor(type, label)
                    )
                )
            }
        }
        save(task, suggestions)
    }

    fun saveFromLabels(
        taskId: String?,
        suggestions: List<Pair<ScreenReaderActionType, String>>,
        recommendedLabel: String? = null
    ) {
        val task = taskId?.takeIf(String::isNotBlank) ?: return
        val recommended = cleanLabel(recommendedLabel.orEmpty())
        val cleaned = suggestions.mapNotNull { (type, raw) ->
            val label = cleanLabel(raw)
            label.takeIf(String::isNotBlank)?.let {
                ScreenReaderSuggestion(
                    type = type,
                    label = it,
                    goal = goalFor(type, it),
                    recommended = it == recommended
                )
            }
        }
        save(task, cleaned)
    }

    fun get(taskId: String?): List<ScreenReaderSuggestion> =
        taskId?.let { entries[it] }.orEmpty()

    fun clear(taskId: String?) {
        taskId?.let { entries.remove(it) }
    }

    internal fun clearAll() {
        entries.clear()
    }

    private fun save(taskId: String, suggestions: List<ScreenReaderSuggestion>) {
        val cleaned = suggestions
            .filter { it.label.isNotBlank() }
            .distinctBy { it.label }
            .take(MAX_SUGGESTIONS)
        if (cleaned.isEmpty()) {
            entries.remove(taskId)
        } else {
            entries[taskId] = cleaned
        }
    }

    private fun cleanLabel(value: String): String = value
        .trim()
        .removePrefix("-")
        .removePrefix("*")
        .removePrefix("•")
        .trim()
        .replace(Regex("^\\d+[.)、]\\s*"), "")
        .replace(Regex("[*_`#]"), "")
        .trim()
        .take(24)

    private fun goalFor(type: ScreenReaderActionType, label: String): String =
        when (type) {
            ScreenReaderActionType.EXPLAIN -> AppLanguage.tr(
                "解读当前容器页面：$label。只读取和整理内容，不执行页面动作。",
                "Explain the current container screen: $label. Read and organize it only; " +
                    "do not perform page actions."
            )
            ScreenReaderActionType.EXTRACT -> AppLanguage.tr(
                "从当前容器页面提取并整理：$label。保留来源和原文顺序，不执行页面动作。",
                "Extract and organize this from the current container screen: $label. Preserve " +
                    "source order and do not perform page actions."
            )
            ScreenReaderActionType.COMPARE -> AppLanguage.tr(
                "比较当前容器页面中的：$label。列出差异、适用条件和不确定项，不替我做决定。",
                "Compare this on the current container screen: $label. List differences, " +
                    "tradeoffs and uncertainty without deciding for me."
            )
            ScreenReaderActionType.NAVIGATE -> AppLanguage.tr(
                "在当前容器页面定位：$label。只高亮或查找位置，不点击；进入新页面或改变" +
                    "页面状态前先向我确认。",
                "Locate this on the current container screen: $label. Only highlight or find " +
                    "it; ask me before opening or changing page state."
            )
            ScreenReaderActionType.TRANSLATE -> AppLanguage.tr(
                "翻译当前容器页面的主要内容：$label。保留原文结构，不改变原页面。",
                "Translate the main content of the current container screen: $label. Preserve " +
                    "the original structure without changing the page."
            )
            ScreenReaderActionType.SEARCH -> AppLanguage.tr(
                "在当前容器页面查找并回答：$label。只读取页面，不执行点击或输入。",
                "Search the current container screen and answer: $label. Read the page only; " +
                    "do not tap or type."
            )
            ScreenReaderActionType.OPERATE -> AppLanguage.tr(
                "分析当前容器页面的可操作项：$label。列出作用和风险，不直接点击；需要执行时" +
                    "先向我确认。",
                "Analyze the current container actions: $label. List effects and risks without " +
                    "tapping; ask me before execution."
            )
            ScreenReaderActionType.OPEN_APP -> AppLanguage.tr(
                "打开应用：$label。使用现有的应用启动能力；目标不明确时先询问我。",
                "Open the app: $label. Use the existing app-launch capability and ask me first " +
                    "when the target is unclear."
            )
        }
}
