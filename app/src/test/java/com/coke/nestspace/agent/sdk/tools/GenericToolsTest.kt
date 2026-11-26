package com.coke.nestspace.agent.sdk.tools

import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentTool
import com.coke.nestspace.agent.sdk.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericToolsTest {
    private val context = AgentRunContext(sessionId = "session")

    @Test
    fun systemStatusReadsInjectedState() = runBlocking {
        val tool = SystemStatusTool {
            SystemStatusSnapshot(
                platform = "android",
                batteryPercent = 72,
                network = "wifi"
            )
        }.tool
        val result = execute(tool, "{}")

        val json = JSONObject(result.output)
        assertEquals("android", json.getString("platform"))
        assertEquals(72, json.getInt("battery_percent"))
        assertEquals("wifi", json.getString("network"))
    }

    @Test
    fun clipboardAndSpeechDelegateToHost() = runBlocking {
        var clipboard = ""
        val clipboardTools = ClipboardTools(
            object : ClipboardProvider {
                override suspend fun read(): String = clipboard
                override suspend fun write(text: String) {
                    clipboard = text
                }
            }
        )
        assertTrue(execute(clipboardTools.write, """{"text":"hello"}""").success)
        assertEquals(
            "hello",
            JSONObject(execute(clipboardTools.read, "{}").output).getString("text")
        )

        var spoken = ""
        val speech = SpeechTools(
            object : SpeechProvider {
                override suspend fun speak(text: String, interrupt: Boolean) {
                    spoken = text
                }
                override suspend fun stop() = Unit
                override suspend fun status(): AgentSpeechStatus =
                    AgentSpeechStatus(speaking = spoken.isNotEmpty(), provider = "test")
            }
        )
        assertTrue(execute(speech.speak, """{"text":"read this"}""").success)
        assertEquals("read this", spoken)
    }

    @Test
    fun httpToolReturnsStatusAndBody() = runBlocking {
        val tool = HttpRequestTool {
            AgentHttpResponse(statusCode = 201, body = "created")
        }.tool
        val result = execute(
            tool,
            """{"method":"POST","url":"https://example.com"}"""
        )

        assertTrue(result.success)
        assertEquals(201, JSONObject(result.output).getInt("status_code"))
        assertEquals("created", JSONObject(result.output).getString("body"))
    }

    @Test
    fun scheduleToolDelegatesCreateAndList() = runBlocking {
        val controller = RecordingScheduleController()
        val tool = ScheduleTool(controller).tool

        val created = execute(
            tool,
            """{"action":"create","goal":"remind me","trigger":"09:00"}"""
        )
        assertEquals("schedule-1", JSONObject(created.output).getString("id"))

        val listed = execute(tool, """{"action":"list"}""")
        assertEquals(
            1,
            JSONObject(listed.output).getJSONArray("tasks").length()
        )
    }

    @Test
    fun handoffReportsSkippedState() = runBlocking {
        val tool = HandoffTool { HandoffResult.SKIPPED }.tool
        val result = execute(tool, """{"reason":"login"}""")

        assertFalse(result.success)
        assertEquals("HANDOFF_SKIPPED", result.errorCode)
    }

    @Test
    fun askUserQuestionReturnsStructuredAnswers() = runBlocking {
        var asked: List<AgentQuestion> = emptyList()
        val tool = AskUserQuestionTool { questions ->
            asked = questions
            AgentQuestionResult(
                AgentQuestionStatus.ANSWERED,
                listOf(
                    AgentQuestionAnswer(
                        prompt = questions.single().prompt,
                        selected = listOf("Light")
                    )
                )
            )
        }.tool
        val result = execute(
            tool,
            JSONObject()
                .put(
                    "questions",
                    """[{"question":"Theme?","options":[{"label":"Light"},{"label":"Dark"}]}]"""
                )
                .toString()
        )

        assertTrue(result.success)
        assertEquals("Theme?", asked.single().prompt)
        assertEquals(
            "Light",
            JSONObject(result.output)
                .getJSONArray("answers")
                .getJSONObject(0)
                .getJSONArray("selected")
                .getString(0)
        )
    }

    private suspend fun execute(tool: AgentTool, input: String) =
        tool.handler.execute(
            context,
            AgentToolCall("call", tool.descriptor.name, input)
        )
}

private class RecordingScheduleController : ScheduleController {
    private val items = mutableListOf<AgentSchedule>()

    override suspend fun list(): List<AgentSchedule> = items.toList()

    override suspend fun create(goal: String, trigger: String): AgentSchedule =
        AgentSchedule("schedule-1", goal, trigger, enabled = true).also(items::add)

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean = false
    override suspend fun delete(id: String): Boolean = false
    override suspend fun runNow(id: String): Boolean = false
}
