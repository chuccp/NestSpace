package com.coke.nestspace.features.notebook

import org.json.JSONArray
import org.json.JSONObject

data class NotebookReaderState(
    val activityLabel: String,
    val contentTitle: String,
    val contentBody: String,
    val images: List<String> = emptyList(),
    val actions: List<String> = emptyList()
)

/**
 * Exposes the built-in notebook's Compose content to the container screen
 * reader. Like the browser, the notebook is a container-owned host surface,
 * so it cannot live inside one of the virtual-app slot processes.
 */
object NotebookScreenReaderBridge {
    @Volatile
    private var active = false

    @Volatile
    private var snapshot: String? = null

    fun setActive(value: Boolean) {
        active = value
        if (!value) snapshot = null
    }

    fun publish(state: NotebookReaderState) {
        snapshot = buildSnapshot(state)
    }

    fun observe(): String? = snapshot.takeIf { active }

    private fun buildSnapshot(state: NotebookReaderState): String {
        val nodes = JSONArray()
        var top = 72
        var id = 0
        fun addNode(
            role: String,
            text: String = "",
            description: String = "",
            clickable: Boolean = false,
            editable: Boolean = false
        ) {
            val height = if (text.length > 120 || description.length > 120) 180 else 76
            nodes.put(
                JSONObject()
                    .put("id", "notebook-v${id++}")
                    .put("role", role)
                    .put("text", text)
                    .put("desc", description)
                    .put(
                        "bounds",
                        JSONArray(listOf(32, top, 2920, top + height))
                    )
                    .put("clickable", clickable)
                    .put("editable", editable)
                    .put("scrollable", false)
                    .put("enabled", true)
                    .put("focused", false)
                    .put("selected", false)
            )
            top += height + 18
        }

        addNode("TextView", text = state.activityLabel)
        addNode("TextView", text = state.contentTitle)
        if (state.contentBody.isNotBlank()) {
            addNode("TextView", text = state.contentBody)
        }
        state.images.forEachIndexed { index, label ->
            addNode(
                "ImageView",
                description = label.ifBlank {
                    "笔记图片 ${index + 1}"
                }
            )
        }
        state.actions.forEach { label ->
            addNode("Button", text = label, clickable = true)
        }
        return JSONObject()
            .put("target", "host")
            .put("package", "com.coke.nestspace")
            .put("activity", "NotebookActivity")
            .put(
                "screen",
                JSONObject().put("width", 2944).put("height", 1840)
            )
            .put("nodes", nodes)
            .put("timestamp_ms", System.currentTimeMillis())
            .toString()
    }
}
