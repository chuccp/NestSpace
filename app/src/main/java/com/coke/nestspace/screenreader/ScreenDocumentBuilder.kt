package com.coke.nestspace.screenreader

import com.coke.nestspace.agent.UserGateManager
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Converts a raw container UI snapshot into a compact screen document. */
object ScreenDocumentBuilder {
    private const val MAX_BLOCKS = 60
    private const val MAX_ACTIONS = 40
    private const val MAX_BLOCK_CHARS = 800

    private data class Node(
        val id: String,
        val role: String,
        val label: String,
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean
    )

    fun build(snapshotJson: String): JSONObject {
        val root = JSONObject(snapshotJson)
        val sourceNodes = root.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (index in 0 until sourceNodes.length()) {
                val json = sourceNodes.optJSONObject(index) ?: continue
                val bounds = json.optJSONArray("bounds") ?: continue
                if (bounds.length() < 4) continue
                val text = json.optString("text").trim()
                val desc = json.optString("desc").trim()
                val label = text.ifBlank { desc }
                if (label.isBlank() &&
                    !json.optBoolean("clickable") &&
                    !json.optBoolean("editable") &&
                    !json.optBoolean("scrollable")
                ) {
                    continue
                }
                add(
                    Node(
                        id = json.optString("id"),
                        role = json.optString("role"),
                        label = label,
                        text = text,
                        left = bounds.optInt(0),
                        top = bounds.optInt(1),
                        right = bounds.optInt(2),
                        bottom = bounds.optInt(3),
                        clickable = json.optBoolean("clickable"),
                        editable = json.optBoolean("editable"),
                        scrollable = json.optBoolean("scrollable"),
                        enabled = json.optBoolean("enabled", true)
                    )
                )
            }
        }.sortedWith(compareBy<Node>({ it.top }, { it.left }))

        val unique = buildList {
            val seen = mutableSetOf<String>()
            nodes.forEach { node ->
                val key = "${node.label}\u0000${node.left / 8}\u0000${node.top / 8}"
                if (node.label.isBlank() || seen.add(key)) add(node)
            }
        }
        val textNodes = unique.filter {
            it.enabled && it.label.isNotBlank() && it.label.length >= 2
        }
        val pageType = pageType(textNodes, unique)
        val title = title(textNodes, pageType)
        val blocks = buildBlocks(
            textNodes.filter { !it.clickable || it.text.length > 40 }
        )
        val actions = buildActions(unique)
        val sensitive = UserGateManager.annotateSensitive(snapshotJson)
        val sensitiveRoot = runCatching { JSONObject(sensitive) }.getOrNull()
        val fingerprint = fingerprint(
            root.optString("package"),
            root.optString("activity"),
            unique.map { "${it.id}|${it.label}|${it.left},${it.top},${it.right},${it.bottom}" }
        )
        val screenshotRecommended = textNodes.size < 2 &&
            actions.length() < 2
        return JSONObject()
            .put("snapshot_id", "screen-$fingerprint")
            .put("target", root.optString("target", "container"))
            .put("package", root.optString("package"))
            .put("activity", root.optString("activity"))
            .put(
                "screen",
                root.optJSONObject("screen") ?: JSONObject()
            )
            .put("captured_at", System.currentTimeMillis())
            .put("page_type", pageType)
            .put("title", title)
            .put(
                "summary",
                summary(pageType, title, blocks.length(), actions.length())
            )
            .put("blocks", blocks)
            .put("actions", actions)
            .put("source_node_count", sourceNodes.length())
            .put("kept_node_count", unique.size)
            .put("screenshot_recommended", screenshotRecommended)
            .put("needs_user", sensitiveRoot?.optBoolean("needs_user") == true)
            .put(
                "user_reason",
                sensitiveRoot?.optString("user_reason").orEmpty()
            )
            .put(
                "suggested_intents",
                suggestedIntents(
                    pageType,
                    blocks.length(),
                    actions,
                    textNodes.size
                )
            )
    }

    private fun buildBlocks(nodes: List<Node>): JSONArray {
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        nodes.asSequence()
            .filter { seen.add(it.text.trim()) }
            .take(MAX_BLOCKS)
            .forEachIndexed { index, node ->
                result.put(
                    JSONObject()
                        .put("block_id", "b${index + 1}")
                        .put("kind", blockKind(node))
                        .put("text", node.label.trim().take(MAX_BLOCK_CHARS))
                        .put("node_id", node.id)
                        .put(
                            "bounds",
                            JSONArray(
                                listOf(
                                    node.left,
                                    node.top,
                                    node.right,
                                    node.bottom
                                )
                            )
                        )
                )
            }
        return result
    }

    private fun buildActions(nodes: List<Node>): JSONArray {
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        nodes.asSequence()
            .filter {
                it.enabled &&
                    (it.clickable || it.editable) &&
                    it.label.isNotBlank()
            }
            .filter { seen.add("${it.label}\u0000${it.left / 8}\u0000${it.top / 8}") }
            .take(MAX_ACTIONS)
            .forEachIndexed { index, node ->
                val risk = when {
                    UserGateManager.isMoneyTap(node.label) -> "blocked"
                    UserGateManager.isInstallTap(node.label) -> "high"
                    UserGateManager.isRiskyTap(node.label) -> "high"
                    else -> "low"
                }
                result.put(
                    JSONObject()
                        .put("action_id", "a${index + 1}")
                        .put("node_id", node.id)
                        .put("label", node.label.take(200))
                        .put(
                            "kind",
                            if (node.editable) "input" else "button"
                        )
                        .put("risk", risk)
                        .put(
                            "requires_confirmation",
                            risk != "low"
                        )
                        .put(
                            "bounds",
                            JSONArray(
                                listOf(
                                    node.left,
                                    node.top,
                                    node.right,
                                    node.bottom
                                )
                            )
                        )
                )
            }
        return result
    }

    private fun suggestedIntents(
        pageType: String,
        blockCount: Int,
        actions: JSONArray,
        textCount: Int
    ): JSONArray {
        val result = JSONArray()
        fun add(type: String, confidence: Double, label: String) {
            result.put(
                JSONObject()
                    .put("type", type)
                    .put("label", label)
                    .put("confidence", confidence)
            )
        }
        add("understand", if (blockCount >= 2) 0.76 else 0.58, "解读当前页面")
        add("assistive_read", if (textCount >= 2) 0.72 else 0.54, "朗读当前页面")
        if (textCount >= 3) add("translate", 0.56, "翻译当前内容")
        if (actions.length() > 0) add("operate", 0.54, "查看可操作项")
        if (pageType in setOf("news", "generic", "commerce")) {
            add("find", 0.50, "查找页面信息")
        }
        return result
    }

    private fun pageType(nodes: List<Node>, all: List<Node>): String {
        val text = (nodes + all).joinToString(" ") { it.label }.lowercase()
        return when {
            listOf("验证码", "登录", "密码", "captcha", "sign in")
                .any(text::contains) -> "auth"
            listOf("价格", "¥", "购物车", "立即购买", "checkout")
                .any(text::contains) -> "commerce"
            listOf("热榜", "新闻", "推荐", "资讯", "news")
                .any(text::contains) -> "news"
            listOf("消息", "聊天", "发送", "message", "chat")
                .any(text::contains) -> "chat"
            listOf("播放", "视频", "弹幕", "video", "play")
                .any(text::contains) -> "video"
            all.count { it.editable } > 0 -> "form"
            else -> "generic"
        }
    }

    private fun title(nodes: List<Node>, pageType: String): String {
        val ignored = setOf(
            "首页", "推荐", "关注", "热门", "搜索", "返回", "更多", "菜单",
            "home", "search", "back", "more", "menu"
        )
        val candidates = nodes.filter {
            it.label.length in 2..80 &&
                it.label.lowercase() !in ignored
        }
        val headingLike = candidates.firstOrNull {
            it.top <= 500 && it.label.length <= 42
        }
        return headingLike?.label
            ?: candidates.firstOrNull()?.label
            ?: pageTypeLabel(pageType)
    }

    private fun summary(
        pageType: String,
        title: String,
        blockCount: Int,
        actionCount: Int
    ): String = "当前页面类型为${pageTypeLabel(pageType)}，标题为“$title”，" +
        "包含 $blockCount 个主要内容和 $actionCount 个可操作项。"

    private fun pageTypeLabel(type: String): String = when (type) {
        "auth" -> "登录或验证页面"
        "commerce" -> "商品或购物页面"
        "news" -> "新闻资讯页面"
        "chat" -> "消息或聊天页面"
        "video" -> "视频页面"
        "form" -> "表单页面"
        else -> "应用页面"
    }

    private fun blockKind(node: Node): String = when {
        node.label.length <= 40 && node.top <= 600 -> "heading"
        node.label.length <= 180 -> "text"
        else -> "paragraph"
    }

    private fun fingerprint(
        packageName: String,
        activity: String,
        values: List<String>
    ): String = MessageDigest.getInstance("SHA-256")
        .digest(
            "$packageName|$activity|${values.joinToString("\n")}"
                .toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(24)
}
