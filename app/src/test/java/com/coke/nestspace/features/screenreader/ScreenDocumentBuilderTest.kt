package com.coke.nestspace.features.screenreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDocumentBuilderTest {

    @Test
    fun buildsCompactNewsDocumentWithActionsAndSuggestions() {
        val document = ScreenDocumentBuilder.build(
            snapshot(
                textNode("n1", "今日热榜", 40, 30, 360, 90),
                textNode(
                    "n2",
                    "城市公共空间更新带来哪些变化？",
                    40,
                    120,
                    720,
                    180
                ),
                textNode(
                    "n3",
                    "从街区步道到社区公园，多个城市正在重新组织公共空间。",
                    40,
                    200,
                    720,
                    260
                ),
                textNode("n4", "查看详情", 40, 300, 220, 360, clickable = true)
            )
        )

        assertEquals("news", document.getString("page_type"))
        assertEquals("今日热榜", document.getString("title"))
        assertEquals(3, document.getJSONArray("blocks").length())
        assertEquals(1, document.getJSONArray("actions").length())
        assertFalse(document.getBoolean("screenshot_recommended"))
        assertTrue(
            document.getJSONArray("suggested_intents").toString()
                .contains("\"understand\"")
        )
        assertTrue(document.getString("snapshot_id").startsWith("screen-"))
    }

    @Test
    fun marksHighRiskActionsAndSensitivePages() {
        val document = ScreenDocumentBuilder.build(
            snapshot(
                textNode("n1", "登录验证", 40, 30, 360, 90),
                textNode("n2", "请输入验证码", 40, 120, 500, 180),
                textNode("n3", "立即购买", 40, 300, 220, 360, clickable = true)
            )
        )

        assertTrue(document.getBoolean("needs_user"))
        assertEquals("captcha", document.getString("user_reason"))
        val action = document.getJSONArray("actions").getJSONObject(0)
        assertEquals("blocked", action.getString("risk"))
        assertTrue(action.getBoolean("requires_confirmation"))
    }

    @Test
    fun recommendsScreenshotWhenNodeContentIsInsufficient() {
        val document = ScreenDocumentBuilder.build(
            snapshot(
                textNode("n1", "", 20, 20, 760, 500, clickable = true)
            )
        )

        assertTrue(document.getBoolean("screenshot_recommended"))
        assertEquals(0, document.getJSONArray("blocks").length())
        assertEquals(0, document.getJSONArray("actions").length())
    }

    private fun textNode(
        id: String,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("role", "TextView")
        .put("text", text)
        .put("desc", "")
        .put("bounds", JSONArray(listOf(left, top, right, bottom)))
        .put("clickable", clickable)
        .put("editable", false)
        .put("scrollable", false)
        .put("enabled", true)
        .put("focused", false)
        .put("selected", false)

    private fun snapshot(vararg nodes: JSONObject): String = JSONObject()
        .put("package", "com.example.target")
        .put("activity", "MainActivity")
        .put(
            "screen",
            JSONObject().put("width", 800).put("height", 1200)
        )
        .put("nodes", JSONArray(nodes.toList()))
        .put("timestamp_ms", 1L)
        .toString()
}
