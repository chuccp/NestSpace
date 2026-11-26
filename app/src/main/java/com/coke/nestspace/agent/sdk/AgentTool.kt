package com.coke.nestspace.agent.sdk

data class AgentToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val usagePrompt: String = ""
) {
    init {
        require(name.isNotBlank()) { "Tool name must not be blank" }
    }
}

data class AgentToolCall(
    val id: String,
    val name: String,
    val inputJson: String
) {
    init {
        require(id.isNotBlank()) { "Tool call id must not be blank" }
        require(name.isNotBlank()) { "Tool call name must not be blank" }
    }
}

data class AgentToolResult(
    val success: Boolean,
    val output: String = "",
    val errorCode: String? = null,
    val pending: Boolean = false,
    val attachments: List<AgentToolAttachment> = emptyList()
) {
    init {
        require(errorCode == null || errorCode.isNotBlank()) {
            "Tool result error code must not be blank"
        }
    }
}

enum class AgentAttachmentAudience {
    MODEL,
    USER,
    BOTH
}

sealed interface AgentToolAttachment {
    val audience: AgentAttachmentAudience

    data class Image(
        val dataBase64: String,
        val mimeType: String = "image/png",
        val metadata: Map<String, String> = emptyMap(),
        override val audience: AgentAttachmentAudience =
            AgentAttachmentAudience.MODEL
    ) : AgentToolAttachment

    data class File(
        val name: String,
        val mimeType: String? = null,
        val dataBase64: String? = null,
        val uri: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        override val audience: AgentAttachmentAudience =
            AgentAttachmentAudience.USER
    ) : AgentToolAttachment {
        init {
            require(name.isNotBlank()) { "Attachment name must not be blank" }
            require(dataBase64 != null || uri != null) {
                "File attachment requires data or uri"
            }
        }
    }

    data class Reference(
        val name: String,
        val uri: String,
        val metadata: Map<String, String> = emptyMap(),
        override val audience: AgentAttachmentAudience =
            AgentAttachmentAudience.MODEL
    ) : AgentToolAttachment {
        init {
            require(name.isNotBlank()) { "Reference name must not be blank" }
            require(uri.isNotBlank()) { "Reference uri must not be blank" }
        }
    }
}

data class AgentToolExecutionConfig(
    val timeoutMillis: Long? = null,
    val maxAttempts: Int = 1,
    val requiresConfirmation: Boolean = false,
    val confirmationTitle: String? = null,
    val confirmationDetail: String? = null,
    val polling: AgentPollingPolicy? = null
) {
    init {
        require(timeoutMillis == null || timeoutMillis > 0) {
            "Tool timeout must be greater than zero"
        }
        require(maxAttempts > 0) {
            "Tool maxAttempts must be greater than zero"
        }
        require(confirmationTitle == null || confirmationTitle.isNotBlank()) {
            "Confirmation title must not be blank"
        }
        require(confirmationDetail == null || confirmationDetail.isNotBlank()) {
            "Confirmation detail must not be blank"
        }
    }
}

fun interface AgentToolHandler {
    suspend fun execute(
        context: AgentRunContext,
        call: AgentToolCall
    ): AgentToolResult
}

data class AgentTool(
    val descriptor: AgentToolDescriptor,
    val handler: AgentToolHandler,
    val execution: AgentToolExecutionConfig = AgentToolExecutionConfig()
)

class AgentToolCatalog(tools: Collection<AgentTool>) {
    private val tools = tools.toList()
    private val toolsByName = this.tools.associateBy { it.descriptor.name }

    init {
        require(toolsByName.size == this.tools.size) {
            "Tool names must be unique"
        }
    }

    fun get(name: String): AgentTool? = toolsByName[name]

    fun all(): List<AgentTool> = tools.toList()

    fun forAgent(policy: AgentToolPolicy): List<AgentTool> =
        tools.filter { policy.permits(it.descriptor.name) }
}
