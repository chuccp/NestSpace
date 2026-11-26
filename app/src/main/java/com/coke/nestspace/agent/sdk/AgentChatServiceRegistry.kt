package com.coke.nestspace.agent.sdk

/**
 * Routes Chat requests by AgentChatOptions.providerId. The first registered
 * service becomes the default.
 */
class AgentChatServiceRegistry(
    services: Collection<Pair<String, AgentChatService>> = emptyList(),
    defaultServiceId: String? = null
) : AgentChatService {
    private val lock = Any()
    private val services = linkedMapOf<String, AgentChatService>()
    private var defaultId: String? = null

    init {
        services.forEach { (id, service) -> register(id, service) }
        defaultServiceId?.let { setDefault(it) }
        require(defaultId == null || defaultId in this.services) {
            "Unknown default Chat service: $defaultId"
        }
    }

    fun register(id: String, service: AgentChatService) {
        require(id.isNotBlank()) { "Chat service id must not be blank" }
        synchronized(lock) {
            services[id] = service
            if (defaultId == null) defaultId = id
        }
    }

    fun setDefault(id: String) {
        synchronized(lock) {
            require(id in services) { "Unknown Chat service: $id" }
            defaultId = id
        }
    }

    fun get(id: String): AgentChatService? = synchronized(lock) {
        services[id]
    }

    override val supportsVision: Boolean
        get() = synchronized(lock) {
            val id = defaultId ?: return false
            services[id]?.supportsVision == true
        }

    override fun supportsVision(providerId: String?): Boolean =
        synchronized(lock) {
            val id = providerId ?: defaultId ?: return false
            services[id]?.supportsVision == true
        }

    override suspend fun chat(request: AgentChatInput): AgentChatResponse =
        resolve(request).chat(request)

    override suspend fun streamChat(
        request: AgentChatInput,
        onTextDelta: (String) -> Unit
    ): AgentChatResponse = resolve(request).streamChat(request, onTextDelta)

    private fun resolve(request: AgentChatInput): AgentChatService {
        val id = request.providerId
            ?: synchronized(lock) { defaultId }
            ?: error("No Chat service is registered")
        return synchronized(lock) { services[id] }
            ?: error("Unknown Chat service: $id")
    }
}
