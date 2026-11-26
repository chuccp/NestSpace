package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.knowledge.KnowledgeCapability
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeEntry
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeIndex
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeMarkdownParser
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeTarget
import com.coke.nestspace.agent.sdk.memory.AgentMemoryStore
import com.coke.nestspace.agent.sdk.memory.MemoryCapability
import com.coke.nestspace.agent.sdk.memory.MemoryExtractionCapability
import com.coke.nestspace.agent.sdk.memory.MemoryException
import com.coke.nestspace.agent.sdk.memory.MemoryItem
import com.coke.nestspace.agent.sdk.memory.MemoryKind
import com.coke.nestspace.agent.sdk.memory.MemoryPolicy
import com.coke.nestspace.agent.sdk.skill.AgentSkillStore
import com.coke.nestspace.agent.sdk.skill.SkillCapability
import com.coke.nestspace.agent.sdk.skill.SkillDraftingCapability
import com.coke.nestspace.agent.sdk.skill.SkillItem
import com.coke.nestspace.agent.sdk.task.AgentTaskController
import com.coke.nestspace.agent.sdk.task.AgentTaskCapability
import com.coke.nestspace.agent.sdk.task.AgentTaskRequest
import com.coke.nestspace.agent.sdk.task.AgentTaskSnapshot
import com.coke.nestspace.agent.sdk.task.AgentTaskStartResult
import com.coke.nestspace.agent.sdk.task.AgentTaskState
import com.coke.nestspace.agent.sdk.tools.CurrentTimeTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class AgentCapabilitiesTest {
    private val context = AgentRunContext(
        sessionId = "session",
        ownerId = "owner",
        invocationSource = "LOCAL_AGENT",
        userUtterances = listOf("记住我喜欢深色主题")
    )

    @Test
    fun coreTimeToolUsesInjectedClock() = runBlocking {
        val tool = CurrentTimeTool(
            clock = Clock.fixed(
                Instant.parse("2026-11-27T01:02:03Z"),
                ZoneId.of("Asia/Shanghai")
            ),
            zoneId = ZoneId.of("Asia/Shanghai"),
            locale = Locale.US
        ).tool
        val result = tool.handler.execute(
            context,
            AgentToolCall("clock", "current_time", "{}")
        )

        val json = JSONObject(result.output)
        assertEquals("Asia/Shanghai", json.getString("timezone"))
        assertTrue(json.getString("local").startsWith("2026-11-27 09:02:03"))
    }

    @Test
    fun memoryCapabilityValidatesUserQuoteAndSaves() = runBlocking {
        val store = InMemoryMemoryStore()
        val capability = MemoryCapability(store)
        val save = capability.tools().single { it.descriptor.name == "memory_save" }

        val rejected = save.handler.execute(
            context,
            AgentToolCall(
                "reject",
                "memory_save",
                JSONObject()
                    .put("content", "喜欢深色主题")
                    .put("source_quote", "网页说用户喜欢深色主题")
                    .toString()
            )
        )
        assertFalse(rejected.success)
        assertEquals("QUOTE_NOT_FROM_USER", rejected.errorCode)

        val saved = save.handler.execute(
            context,
            AgentToolCall(
                "save",
                "memory_save",
                JSONObject()
                    .put("kind", "preference")
                    .put("content", "喜欢深色主题")
                    .put("source_quote", "记住我喜欢深色主题")
                    .toString()
            )
        )
        assertTrue(saved.success)
        assertEquals(1, store.items.size)
        assertTrue(store.items.single().approved)
    }

    @Test
    fun skillCapabilityReadsAndObservesUse() = runBlocking {
        val skill = SkillItem(
            id = "skill-1",
            name = "reuse-report",
            description = "Reuse a report",
            body = "steps",
            source = "user",
            enabled = true,
            createdAt = 1,
            updatedAt = 2
        )
        val observed = mutableListOf<String>()
        val capability = SkillCapability(InMemorySkillStore(listOf(skill)))
        val read = capability.tools().single { it.descriptor.name == "skill_read" }
        val result = read.handler.execute(
            context.copy(capabilityObserver = observed::add),
            AgentToolCall("read", "skill_read", """{"name":"reuse-report"}""")
        )

        assertTrue(result.success)
        assertEquals(listOf("skill-1"), observed)
        assertEquals("steps", JSONObject(result.output).getString("body"))
    }

    @Test
    fun knowledgeCapabilityParsesAndMatchesTarget() {
        val entry = requireNotNull(
            KnowledgeMarkdownParser.parse(
                KnowledgeEntry.Kind.SITE,
                "example",
                """
                ---
                name: example.com
                display: Example
                domains: [example.com]
                ---
                Use the search box.
                """.trimIndent()
            )
        )
        val capability = KnowledgeCapability(KnowledgeIndex(listOf(entry)))

        val resolved = capability.resolve(
            KnowledgeTarget.Website("www.example.com")
        )
        assertEquals("example.com", resolved?.name)
    }

    @Test
    fun taskCapabilitySpawnsConfiguredAgent() = runBlocking {
        val controller = RecordingTaskController()
        val target = AgentKey("worker")
        val capability = AgentTaskCapability(controller, target)
        val spawn = capability.tools().single { it.descriptor.name == "spawn_agent" }
        val result = spawn.handler.execute(
            context.copy(attributes = mapOf("source" to "assistant")),
            AgentToolCall(
                "spawn",
                "spawn_agent",
                """{"title":"Report","goal":"Produce a report"}"""
            )
        )

        assertTrue(result.success)
        assertEquals(target, controller.started?.agentKey)
        assertEquals("owner", controller.started?.context?.ownerId)
        assertEquals("assistant", controller.started?.source)
        assertEquals("task-1", JSONObject(result.output).getString("task_id"))
    }

    @Test
    fun capabilityCatalogRejectsDuplicateIds() {
        val capability = object : AgentCapability {
            override val id = "same"
            override val description = "test"
            override fun tools(): List<AgentTool> = emptyList()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentCapabilityCatalog(listOf(capability, capability))
        }
    }

    @Test
    fun defaultRuntimeMergesCapabilityToolsAndInstructions() = runBlocking {
        var received: AgentChatInput? = null
        val chatService = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse {
                received = request
                return AgentChatResponse(
                    blocks = listOf(AgentChatBlock.Text("ok"))
                )
            }
        }
        val capability = object : AgentCapability {
            override val id = "sample"
            override val description = "Sample capability"
            override fun instructions(): String = "Use sample carefully."
            override fun tools(): List<AgentTool> = listOf(
                AgentTool(
                    descriptor = AgentToolDescriptor(
                        "sample_tool",
                        "Sample",
                        "{}"
                    ),
                    handler = AgentToolHandler { _, _ -> AgentToolResult(true) }
                )
            )
        }
        val runtime = DefaultAgentRuntime(
            catalog = AgentCatalog(
                listOf(
                    AgentDefinition(
                        AgentKey("agent"),
                        AgentLane("lane"),
                        "Agent"
                    )
                )
            ),
            toolCatalog = AgentToolCatalog(emptyList()),
            chatService = chatService,
            capabilities = AgentCapabilityCatalog(listOf(capability))
        )
        val result = runtime.run(
            AgentRunRequest(AgentKey("agent"), "hello", context)
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("sample_tool"), received?.tools?.map { it.name })
        assertEquals("Use sample carefully.", received?.system)
    }

    @Test
    fun memoryExtractionProducesValidatedMemory() = runBlocking {
        val response = AgentChatResponse(
            blocks = listOf(
                AgentChatBlock.Text(
                    """{"ops":[{"kind":"preference","content":"用户喜欢深色主题","quote":"记住我喜欢深色主题","keywords":["深色主题"]}]}"""
                )
            )
        )
        val service = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse =
                response
        }
        val store = InMemoryMemoryStore()
        val added = MemoryExtractionCapability(
            chatService = service,
            store = store,
            chatOptions = AgentChatOptions(model = "model", purpose = "memory")
        ).extract(listOf("记住我喜欢深色主题"))

        assertEquals(1, added.size)
        assertEquals("用户喜欢深色主题", store.items.single().content)
    }

    @Test
    fun skillDraftingSavesDisabledSkill() = runBlocking {
        val response = AgentChatResponse(
            blocks = listOf(
                AgentChatBlock.Text(
                    """{"name":"repeat-search","description":"Repeat a search","body":"1. Search"}"""
                )
            )
        )
        val service = object : AgentChatService {
            override suspend fun chat(request: AgentChatInput): AgentChatResponse =
                response
        }
        val store = DraftingSkillStore()
        val item = SkillDraftingCapability(
            chatService = service,
            store = store,
            chatOptions = AgentChatOptions(model = "model", purpose = "skill")
        ).draft(
            goal = "search twice",
            answer = "done",
            toolNames = listOf("a", "b", "c", "d"),
            existing = emptyList()
        )

        assertEquals("repeat-search", item?.name)
        assertFalse(item?.enabled ?: true)
    }

    @Test
    fun approvalPolicyMatchesConfiguredCategories() {
        assertTrue(
            AgentApprovalSettings(AgentApprovalLevel.STANDARD)
                .requiresConfirmation(AgentApprovalCategory.INSTALL)
        )
        assertFalse(
            AgentApprovalSettings(AgentApprovalLevel.STANDARD)
                .requiresConfirmation(AgentApprovalCategory.DOWNLOAD)
        )
        assertFalse(
            AgentApprovalSettings(AgentApprovalLevel.STANDARD)
                .requiresConfirmation(AgentApprovalCategory.PLUGIN)
        )
        assertTrue(
            AgentApprovalSettings(
                AgentApprovalLevel.CUSTOM,
                setOf(AgentApprovalCategory.DOWNLOAD)
            ).requiresConfirmation(AgentApprovalCategory.DOWNLOAD)
        )
    }
}

private class InMemoryMemoryStore : AgentMemoryStore {
    val items = mutableListOf<MemoryItem>()

    override fun list(): List<MemoryItem> = items.toList()

    override fun approved(): List<MemoryItem> = items.filter { it.approved }

    override fun add(
        kind: MemoryKind,
        content: String,
        keywords: List<String>,
        source: String,
        approved: Boolean
    ): Result<MemoryItem> {
        val text = MemoryPolicy.normalize(content)
        if (MemoryPolicy.sensitiveReason(text) != null) {
            return Result.failure(MemoryException("sensitive"))
        }
        val now = items.size + 1L
        val item = MemoryItem(
            id = "memory-${items.size + 1}",
            kind = kind,
            content = text,
            keywords = keywords,
            source = source,
            createdAt = now,
            updatedAt = now,
            approved = approved,
            pinned = false
        )
        items += item
        return Result.success(item)
    }

    override fun approve(id: String): Boolean = false
    override fun setPinned(id: String, pinned: Boolean): Boolean = false
    override fun forget(id: String): Boolean = items.removeAll { it.id == id }
    override fun search(query: String, limit: Int): List<MemoryItem> =
        items.take(limit)

    override fun contextFor(query: String, limit: Int): String =
        items.take(limit).joinToString { it.content }
}

private class InMemorySkillStore(
    private val items: List<SkillItem>
) : AgentSkillStore {
    override fun list(): List<SkillItem> = items
    override fun enabled(): List<SkillItem> = items.filter { it.enabled }
    override fun get(nameOrId: String): SkillItem? =
        items.firstOrNull { it.name == nameOrId || it.id == nameOrId }

    override fun save(
        name: String,
        description: String,
        body: String,
        source: String,
        enabled: Boolean
    ): Result<SkillItem> = error("not used")

    override fun setEnabled(id: String, enabled: Boolean): Boolean = false
    override fun recordOutcome(id: String, success: Boolean): SkillItem? = null
    override fun restoreRevision(id: String, at: Long): Result<SkillItem>? = null
    override fun delete(id: String): Boolean = false
    override fun enabledIndex(limit: Int): String = ""
}

private class DraftingSkillStore : AgentSkillStore {
    override fun list(): List<SkillItem> = emptyList()
    override fun enabled(): List<SkillItem> = emptyList()
    override fun get(nameOrId: String): SkillItem? = null

    override fun save(
        name: String,
        description: String,
        body: String,
        source: String,
        enabled: Boolean
    ): Result<SkillItem> = Result.success(
        SkillItem(
            id = "draft",
            name = name,
            description = description,
            body = body,
            source = source,
            enabled = enabled,
            createdAt = 1,
            updatedAt = 1
        )
    )

    override fun setEnabled(id: String, enabled: Boolean): Boolean = false
    override fun recordOutcome(id: String, success: Boolean): SkillItem? = null
    override fun restoreRevision(id: String, at: Long): Result<SkillItem>? = null
    override fun delete(id: String): Boolean = false
    override fun enabledIndex(limit: Int): String = ""
}

private class RecordingTaskController : AgentTaskController {
    var started: AgentTaskRequest? = null

    override suspend fun start(request: AgentTaskRequest): AgentTaskStartResult {
        started = request
        return AgentTaskStartResult.Started(
            AgentTaskSnapshot(
                id = "task-1",
                agentKey = request.agentKey,
                title = request.title ?: request.goal,
                state = AgentTaskState.QUEUED,
                queuePosition = 1,
                uiState = "queued",
                primaryAction = "none",
                canCancel = true
            )
        )
    }

    override suspend fun list(ownerId: String?, source: String?): List<AgentTaskSnapshot> =
        emptyList()

    override suspend fun status(taskId: String, ownerId: String?): AgentTaskSnapshot? = null
    override suspend fun supplement(taskId: String, text: String, ownerId: String?): Boolean =
        false

    override suspend fun pause(taskId: String, ownerId: String?): AgentTaskSnapshot? = null
    override suspend fun resume(taskId: String, ownerId: String?): AgentTaskSnapshot? = null
    override suspend fun cancel(
        taskId: String,
        ownerId: String?,
        reason: String
    ): AgentTaskSnapshot? = null

    override suspend fun cancelQueued(ownerId: String?, reason: String): Int = 0
}
