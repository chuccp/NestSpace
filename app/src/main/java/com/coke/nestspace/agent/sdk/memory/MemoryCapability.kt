package com.coke.nestspace.agent.sdk.memory

import com.coke.nestspace.agent.sdk.AgentCapability
import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import com.coke.nestspace.agent.sdk.tools.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

class MemoryCapability(
    private val store: AgentMemoryStore,
    private val requiresReview: (MemoryKind) -> Boolean = { false },
    private val onChanged: () -> Unit = {}
) : AgentCapability {
    override val id: String = "memory"
    override val description: String =
        "Search, save and forget approved durable user memories."

    override fun tools(): List<AgentTool> = listOf(
        searchTool(),
        saveTool(),
        forgetTool()
    )

    override fun instructions(): String =
        "Use memory_search before relying on user preferences. Save only durable, " +
            "user-supported facts or procedures with an exact source quote. Never store " +
            "passwords, verification codes or payment data."

    private fun searchTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "memory_search",
            description = "Search approved user memories (facts, preferences, procedures). " +
                "Only approved memories are returned.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "query" to "string",
                "limit" to "integer"
            )
        ),
        handler = { _, call ->
            val args = JSONObject(call.inputJson)
            val items = store.search(args.optString("query"), args.optInt("limit", 8))
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("memories", JSONArray().apply { items.forEach { put(it.toJson()) } })
                    .toString()
            )
        }
    )

    private fun saveTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "memory_save",
            description = "Save a durable fact, preference or procedure for the user " +
                "without asking first. The result reports active or pending; in " +
                "Standard/Loose mode procedures are active immediately, while " +
                "Strict/Custom-risk mode may show a confirmation card in the chat. Pass " +
                "the exact user quote that supports it in source_quote. Never save " +
                "passwords, codes or card data.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "kind" to "string",
                "content" to "string",
                "keywords" to "string",
                "source_quote" to "string",
                required = listOf("content", "source_quote")
            )
        ),
        handler = { context, call ->
            val args = JSONObject(call.inputJson)
            val sourceError = validateSource(args.optString("source_quote"), context)
            if (sourceError != null) {
                handlerFailure(sourceError.first, sourceError.second)
            } else {
                val kind = MemoryKind.from(args.optString("kind"))
                val keywords = args.optString("keywords")
                    .split(',', '，', ';', '；')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                store.add(
                    kind = kind,
                    content = args.optString("content"),
                    keywords = keywords,
                    source = args.optString("source_quote"),
                    approved = kind != MemoryKind.PROCEDURE || !requiresReview(kind)
                ).fold(
                    onSuccess = { item ->
                        onChanged()
                        AgentToolResult(
                            success = true,
                            output = JSONObject()
                                .put("saved", true)
                                .put("pending_review", !item.approved)
                                .put("status", if (item.approved) "active" else "pending")
                                .put("memory", item.toJson())
                                .toString()
                        )
                    },
                    onFailure = { error ->
                        handlerFailure(
                            error.message ?: "memory rejected",
                            (error as? AgentMemoryException)?.code?.uppercase()
                                ?: "MEMORY_ERROR"
                        )
                    }
                )
            }
        }
    )

    private fun forgetTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "memory_forget",
            description = "Delete one memory by id only when the user asks to forget it.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "id" to "string",
                required = listOf("id")
            )
        ),
        handler = { _, call ->
            val id = JSONObject(call.inputJson).optString("id")
            val removed = store.forget(id)
            if (removed) onChanged()
            AgentToolResult(
                success = removed,
                output = if (removed) "memory forgotten" else "memory not found",
                errorCode = if (removed) null else "MEMORY_NOT_FOUND"
            )
        }
    )

    private fun validateSource(
        source: String,
        context: AgentRunContext
    ): Pair<String, String>? {
        if (source.isBlank()) {
            return "source_quote is required" to "SOURCE_REQUIRED"
        }
        if (context.invocationSource.equals("LOCAL_AGENT", ignoreCase = true) &&
            context.userUtterances.isNotEmpty() &&
            !MemoryPolicy.quoteSupported(source, context.userUtterances)
        ) {
            return "source_quote must be an exact quote from the user's message" to
                "QUOTE_NOT_FROM_USER"
        }
        return null
    }

    private fun handlerFailure(message: String, code: String): AgentToolResult =
        AgentToolResult(false, message, code)
}

private fun MemoryItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("kind", kind.api)
    .put("content", content)
    .put("keywords", JSONArray(keywords))
    .put("source", source)
    .put("created_at", createdAt)
    .put("updated_at", updatedAt)
    .put("approved", approved)
    .put("pinned", pinned)
