package com.coke.nestspace.agent.sdk

@JvmInline
value class AgentKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent key must not be blank" }
    }
}

@JvmInline
value class AgentLane(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent lane must not be blank" }
    }
}

data class AgentToolPolicy(
    val allowed: Set<String>? = null,
    val denied: Set<String> = emptySet()
) {
    init {
        require(allowed == null || allowed.all(String::isNotBlank)) {
            "Allowed tool names must not be blank"
        }
        require(denied.all(String::isNotBlank)) {
            "Denied tool names must not be blank"
        }
    }

    fun permits(toolName: String): Boolean =
        (allowed == null || toolName in allowed) && toolName !in denied
}

fun interface AgentInstructionProvider {
    fun instructions(context: AgentRunContext): String

    companion object {
        val Empty = AgentInstructionProvider { "" }
    }
}

data class AgentDefinition(
    val key: AgentKey,
    val lane: AgentLane,
    val displayName: String,
    val toolPolicy: AgentToolPolicy = AgentToolPolicy(),
    val instructions: AgentInstructionProvider = AgentInstructionProvider.Empty,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(displayName.isNotBlank()) { "Agent display name must not be blank" }
    }
}

class AgentCatalog(definitions: Collection<AgentDefinition>) {
    private val definitions = definitions.associateBy(AgentDefinition::key)

    init {
        require(this.definitions.size == definitions.count()) {
            "Agent keys must be unique"
        }
    }

    fun get(key: AgentKey): AgentDefinition? = definitions[key]

    fun require(key: AgentKey): AgentDefinition =
        get(key) ?: error("Unknown agent: ${key.value}")

    fun all(): List<AgentDefinition> = definitions.values.toList()
}
