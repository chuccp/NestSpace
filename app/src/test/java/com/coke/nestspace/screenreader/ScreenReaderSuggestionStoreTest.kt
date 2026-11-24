package com.coke.nestspace.screenreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderSuggestionStoreTest {

    @After
    fun tearDown() {
        ScreenReaderSuggestionStore.clearAll()
    }

    @Test
    fun documentSuggestionsAreAvailableToTheReaderTask() {
        ScreenReaderSuggestionStore.saveFromDocument(
            taskId = "reader-task",
            document = JSONObject().put(
                "suggested_intents",
                JSONArray().put(
                    JSONObject()
                        .put("type", "assistive_read")
                        .put("label", "朗读当前页面")
                ).put(
                    JSONObject()
                        .put("type", "translate")
                        .put("label", "翻译当前页面")
                )
            )
        )

        val suggestions = ScreenReaderSuggestionStore.get("reader-task")
        assertEquals(listOf("朗读当前页面", "翻译当前页面"), suggestions.map { it.label })
        assertEquals(
            listOf(ScreenReaderActionType.EXPLAIN, ScreenReaderActionType.TRANSLATE),
            suggestions.map { it.type }
        )
        assertTrue(suggestions.first().goal.contains("朗读当前页面"))
    }

    @Test
    fun agentSuggestionsAreCleanedDeduplicatedAndCapped() {
        ScreenReaderSuggestionStore.saveFromLabels(
            taskId = "reader-task",
            suggestions = listOf(
                ScreenReaderActionType.EXPLAIN to "**整理前三条视频**",
                ScreenReaderActionType.NAVIGATE to "1. 切换到热门",
                ScreenReaderActionType.EXPLAIN to "整理前三条视频",
                ScreenReaderActionType.SEARCH to "查看评论",
                ScreenReaderActionType.OPERATE to "查找作者信息"
            ),
            recommendedLabel = "**整理前三条视频**"
        )

        val suggestions = ScreenReaderSuggestionStore.get("reader-task")
        assertEquals(4, suggestions.size)
        assertEquals("整理前三条视频", suggestions.first().label)
        assertTrue(suggestions.first().recommended)
        assertEquals(4, suggestions.map { it.label }.distinct().size)
    }

    @Test
    fun unknownCapabilityIsNotAccepted() {
        assertEquals(null, ScreenReaderActionType.fromApi("pay"))
        assertEquals(ScreenReaderActionType.SEARCH, ScreenReaderActionType.fromLegacy("find"))
        assertEquals(ScreenReaderActionType.OPEN_APP, ScreenReaderActionType.fromApi("open_app"))
        assertEquals(ScreenReaderActionType.EXTRACT, ScreenReaderActionType.fromApi("extract"))
        assertEquals(ScreenReaderActionType.COMPARE, ScreenReaderActionType.fromApi("compare"))
    }
}
