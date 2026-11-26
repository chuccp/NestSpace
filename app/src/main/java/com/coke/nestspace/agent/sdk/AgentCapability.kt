package com.coke.nestspace.agent.sdk

/**
 * Optional Agent subsystem that exposes tools and prompt guidance.
 *
 * Storage, network and scheduling remain behind consumer-provided interfaces.
 */
interface AgentCapability {
    val id: String
    val description: String

    fun tools(): List<AgentTool>

    fun instructions(): String = ""

}

data class AgentCapabilityPolicy(
    val allowed: Set<String>? = null,
    val denied: Set<String> = emptySet()
) {
    init {
        require(allowed == null || allowed.all(String::isNotBlank)) {
            "Allowed capability ids must not be blank"
        }
        require(denied.all(String::isNotBlank)) {
            "Denied capability ids must not be blank"
        }
    }

    fun permits(id: String): Boolean =
        (allowed == null || id in allowed) && id !in denied
}

data class AgentFeatureConfig(
    val allowedTools: Set<String>? = null,
    val deniedTools: Set<String> = emptySet(),
    val allowedCapabilities: Set<String>? = null,
    val deniedCapabilities: Set<String> = emptySet()
)

class AgentCapabilityCatalog(
    capabilities: Collection<AgentCapability>,
    private val policy: AgentCapabilityPolicy = AgentCapabilityPolicy()
) {
    private val capabilities = capabilities.toList()
    private val byId = this.capabilities.associateBy(AgentCapability::id)

    init {
        require(byId.size == this.capabilities.size) {
            "Capability ids must be unique"
        }
    }

    fun enabled(): List<AgentCapability> =
        capabilities.filter { policy.permits(it.id) }

    fun tools(): List<AgentTool> = enabled().flatMap(AgentCapability::tools)

    fun instructions(): String = enabled()
        .map(AgentCapability::instructions)
        .filter(String::isNotBlank)
        .joinToString(separator = "\n\n")
}
