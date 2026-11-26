package com.coke.nestspace.agent.sdk.skill

import com.coke.nestspace.agent.sdk.AgentCapability
import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolDescriptor
import com.coke.nestspace.agent.sdk.AgentToolResult
import com.coke.nestspace.agent.sdk.tools.AgentToolSchema
import com.coke.nestspace.agent.sdk.memory.MemoryPolicy
import org.json.JSONArray
import org.json.JSONObject

class SkillCapability(
    private val store: AgentSkillStore,
    private val requiresReview: (SkillItem) -> Boolean = { false },
    private val onChanged: () -> Unit = {}
) : AgentCapability {
    override val id: String = "skills"
    override val description: String =
        "List, read and save reusable Agent skills."

    override fun tools(): List<AgentTool> = listOf(
        listTool(),
        readTool(),
        saveTool()
    )

    override fun instructions(): String =
        "Use skill_read before applying a reusable procedure. Save a skill only when " +
            "the request contains a reusable procedure and provide its exact user quote."

    private fun listTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "skill_list",
            description = "List reusable skills. Enabled skills are also included in the " +
                "system prompt index; disabled skills can still be read explicitly.",
            inputSchemaJson = AgentToolSchema.objectSchema()
        ),
        handler = { _, _ ->
            AgentToolResult(
                success = true,
                output = JSONObject()
                    .put("skills", JSONArray().apply {
                        store.list().forEach { item ->
                            put(
                                JSONObject()
                                    .put("id", item.id)
                                    .put("name", item.name)
                                    .put("description", item.description)
                                    .put("enabled", item.enabled)
                                    .put("built_in", item.builtIn)
                            )
                        }
                    })
                    .toString()
            )
        }
    )

    private fun readTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "skill_read",
            description = "Read a reusable skill by name or id before following it.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "name" to "string",
                required = listOf("name")
            )
        ),
        handler = { context, call ->
            val item = store.get(JSONObject(call.inputJson).optString("name"))
            if (item == null) {
                AgentToolResult(
                    success = false,
                    output = "skill not found",
                    errorCode = "SKILL_NOT_FOUND"
                )
            } else {
                if (!item.builtIn) {
                    context.capabilityObserver?.invoke(item.id)
                }
                AgentToolResult(
                    success = true,
                    output = JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("description", item.description)
                        .put("enabled", item.enabled)
                        .put("built_in", item.builtIn)
                        .put("body", item.body)
                        .toString()
                )
            }
        }
    )

    private fun saveTool(): AgentTool = AgentTool(
        descriptor = AgentToolDescriptor(
            name = "skill_save",
            description = "Save a reusable procedure as a skill without asking first. " +
                "The result reports whether it is enabled; in Standard/Loose mode it is " +
                "enabled immediately, while Strict/Custom-risk mode may show an enable " +
                "card in the chat. Pass the user's exact request in source_quote. Use a " +
                "short lowercase name such as 'bilibili-search'.",
            inputSchemaJson = AgentToolSchema.objectSchema(
                "name" to "string",
                "description" to "string",
                "body" to "string",
                "source_quote" to "string",
                required = listOf("name", "description", "body", "source_quote")
            )
        ),
        handler = { context, call ->
            val args = JSONObject(call.inputJson)
            val source = args.optString("source_quote")
            val sourceError = validateSource(source, context)
            if (sourceError != null) {
                AgentToolResult(false, sourceError.first, sourceError.second)
            } else {
                val draft = SkillItem(
                    id = "",
                    name = args.optString("name"),
                    description = args.optString("description"),
                    body = args.optString("body"),
                    source = source,
                    enabled = false,
                    createdAt = 0L,
                    updatedAt = 0L
                )
                store.save(
                    name = draft.name,
                    description = draft.description,
                    body = draft.body,
                    source = source,
                    enabled = !requiresReview(draft)
                ).fold(
                    onSuccess = { item ->
                        onChanged()
                        AgentToolResult(
                            success = true,
                            output = JSONObject()
                                .put("saved", true)
                                .put("pending_enable", !item.enabled)
                                .put(
                                    "skill",
                                    JSONObject()
                                        .put("id", item.id)
                                        .put("name", item.name)
                                        .put("enabled", item.enabled)
                                )
                                .toString()
                        )
                    },
                    onFailure = { error ->
                        AgentToolResult(
                            success = false,
                            output = error.message ?: "skill rejected",
                            errorCode = (error as? AgentSkillException)?.code?.uppercase()
                                ?: "SKILL_ERROR"
                        )
                    }
                )
            }
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
}
