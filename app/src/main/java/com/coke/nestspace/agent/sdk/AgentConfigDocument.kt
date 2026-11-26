package com.coke.nestspace.agent.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, declarative Agent configuration.
 *
 * Executable dependencies such as Chat services, tools, capabilities and hooks
 * are intentionally not serialized. The host resolves their IDs at runtime.
 */
data class AgentConfigDocument(
    val version: Int = CURRENT_VERSION,
    val key: String,
    val displayName: String? = null,
    val lane: String = "default",
    val instructions: String? = null,
    val features: AgentFeatureConfig = AgentFeatureConfig(),
    val runtime: Runtime = Runtime(),
    val chat: Chat = Chat()
) {
    init {
        require(version == CURRENT_VERSION) {
            "Unsupported Agent config version: $version"
        }
        require(key.isNotBlank()) { "Agent key must not be blank" }
        require(displayName == null || displayName.isNotBlank()) {
            "Agent display name must not be blank"
        }
        require(lane.isNotBlank()) { "Agent lane must not be blank" }
    }

    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("key", key)
        .apply {
            displayName?.let { put("displayName", it) }
            instructions?.let { put("instructions", it) }
            put("lane", lane)
            put("features", features.toJson())
            put("runtime", runtime.toJson())
            put("chat", chat.toJson())
        }
        .toString(2)

    data class Runtime(
        val maxToolRounds: Int = 8,
        val contextBudgetTokens: Int? = null,
        val maxParallelTools: Int = 1,
        val runTimeoutMillis: Long? = null
    ) {
        init {
            require(maxToolRounds > 0) {
                "maxToolRounds must be greater than zero"
            }
            require(contextBudgetTokens == null || contextBudgetTokens > 0) {
                "contextBudgetTokens must be greater than zero"
            }
            require(maxParallelTools > 0) {
                "maxParallelTools must be greater than zero"
            }
            require(runTimeoutMillis == null || runTimeoutMillis > 0) {
                "runTimeoutMillis must be greater than zero"
            }
        }

        internal fun toJson(): JSONObject = JSONObject()
            .put("maxToolRounds", maxToolRounds)
            .put("maxParallelTools", maxParallelTools)
            .apply {
                contextBudgetTokens?.let { put("contextBudgetTokens", it) }
                runTimeoutMillis?.let { put("runTimeoutMillis", it) }
            }
    }

    data class Chat(
        val providerId: String? = null,
        val model: String = "",
        val maxOutputTokens: Int = 2_048,
        val temperature: Double = 0.7,
        val reasoningEffort: AgentReasoningEffort = AgentReasoningEffort.MEDIUM,
        val purpose: String = "agent",
        val attributes: Map<String, String> = emptyMap()
    ) {
        init {
            AgentChatOptions(
                providerId = providerId,
                model = model,
                maxOutputTokens = maxOutputTokens,
                temperature = temperature,
                reasoningEffort = reasoningEffort,
                purpose = purpose,
                attributes = attributes
            )
        }

        internal fun toJson(): JSONObject = JSONObject()
            .put("model", model)
            .put("maxOutputTokens", maxOutputTokens)
            .put("temperature", temperature)
            .put("reasoningEffort", reasoningEffort.name)
            .put("purpose", purpose)
            .put("attributes", attributes.toJson())
            .apply {
                providerId?.let { put("providerId", it) }
            }
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJson(json: String): AgentConfigDocument {
            val root = JSONObject(json)
            val version = root.optInt("version", 0)
            require(version == CURRENT_VERSION) {
                "Unsupported Agent config version: $version"
            }
            val featuresJson = root.optJSONObject("features") ?: JSONObject()
            val runtimeJson = root.optJSONObject("runtime") ?: JSONObject()
            val chatJson = root.optJSONObject("chat") ?: JSONObject()
            val reasoningEffort = chatJson.optString(
                "reasoningEffort",
                AgentReasoningEffort.MEDIUM.name
            ).let { value ->
                AgentReasoningEffort.entries.firstOrNull { it.name == value }
                    ?: throw IllegalArgumentException(
                        "Unknown reasoningEffort: $value"
                    )
            }
            return AgentConfigDocument(
                version = version,
                key = root.optString("key"),
                displayName = root.optString("displayName").ifBlank { null },
                lane = root.optString("lane", "default"),
                instructions = root.optString("instructions").ifBlank { null },
                features = AgentFeatureConfig(
                    allowedTools = featuresJson.stringSet("allowedTools"),
                    deniedTools = featuresJson.stringSet("deniedTools").orEmpty(),
                    allowedCapabilities =
                        featuresJson.stringSet("allowedCapabilities"),
                    deniedCapabilities =
                        featuresJson.stringSet("deniedCapabilities").orEmpty()
                ),
                runtime = Runtime(
                    maxToolRounds = runtimeJson.optInt("maxToolRounds", 8),
                    contextBudgetTokens =
                        runtimeJson.positiveIntOrNull("contextBudgetTokens"),
                    maxParallelTools =
                        runtimeJson.optInt("maxParallelTools", 1),
                    runTimeoutMillis =
                        runtimeJson.positiveLongOrNull("runTimeoutMillis")
                ),
                chat = Chat(
                    providerId = chatJson.optString("providerId")
                        .ifBlank { null },
                    model = chatJson.optString("model"),
                    maxOutputTokens = chatJson.optInt(
                        "maxOutputTokens",
                        2_048
                    ),
                    temperature = chatJson.optDouble("temperature", 0.7),
                    reasoningEffort = reasoningEffort,
                    purpose = chatJson.optString("purpose", "agent"),
                    attributes = chatJson.optJSONObject("attributes")
                        ?.toStringMap()
                        .orEmpty()
                )
            )
        }
    }
}

private fun AgentFeatureConfig.toJson(): JSONObject = JSONObject().apply {
    allowedTools?.toJsonArray()?.let { put("allowedTools", it) }
    put("deniedTools", deniedTools.toJsonArray())
    allowedCapabilities?.toJsonArray()?.let {
        put("allowedCapabilities", it)
    }
    put("deniedCapabilities", deniedCapabilities.toJsonArray())
}

private fun Set<String>.toJsonArray(): JSONArray = JSONArray().apply {
    sorted().forEach(::put)
}

private fun Map<String, String>.toJson(): JSONObject = JSONObject().apply {
    toSortedMap().forEach { (key, value) -> put(key, value) }
}

private fun JSONObject.stringSet(name: String): Set<String>? {
    val values = optJSONArray(name) ?: return null
    return buildSet {
        repeat(values.length()) { index ->
            val value = values.optString(index)
            require(value.isNotBlank()) {
                "$name must not contain blank values"
            }
            add(value)
        }
    }
}

private fun JSONObject.toStringMap(): Map<String, String> {
    val keys = keys()
    return buildMap {
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, optString(key))
        }
    }
}

private fun JSONObject.positiveIntOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name).also {
        require(it > 0) { "$name must be greater than zero" }
    }

private fun JSONObject.positiveLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).also {
        require(it > 0) { "$name must be greater than zero" }
    }
