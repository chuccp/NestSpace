package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentConfirmationHook
import com.coke.nestspace.agent.sdk.hook.AgentToolExecutionHook
import com.coke.nestspace.agent.sdk.hook.AgentToolHookDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorTest {
    private val context = AgentRunContext(sessionId = "session")

    @Test
    fun confirmationCanBlockToolExecution() = runBlocking {
        var calls = 0
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("danger", "Danger", "{}"),
            handler = AgentToolHandler { _, _ ->
                calls++
                AgentToolResult(true)
            },
            execution = AgentToolExecutionConfig(requiresConfirmation = true)
        )
        val result = AgentToolExecutor().execute(
            tool,
            context.copy(
                confirmationHook = AgentConfirmationHook { false }
            ),
            AgentToolCall("1", "danger", "{}")
        )

        assertFalse(result.success)
        assertEquals("CONFIRMATION_DENIED", result.errorCode)
        assertEquals(0, calls)
    }

    @Test
    fun timeoutAndRetryAreConfiguredPerTool() = runBlocking {
        var calls = 0
        val timedOut = AgentTool(
            descriptor = AgentToolDescriptor("slow", "Slow", "{}"),
            handler = AgentToolHandler { _, _ ->
                delay(100)
                AgentToolResult(true)
            },
            execution = AgentToolExecutionConfig(timeoutMillis = 10)
        )
        val timeoutResult = AgentToolExecutor().execute(
            timedOut,
            context,
            AgentToolCall("1", "slow", "{}")
        )
        assertFalse(timeoutResult.success)
        assertEquals("TOOL_TIMEOUT", timeoutResult.errorCode)

        val retried = AgentTool(
            descriptor = AgentToolDescriptor("retry", "Retry", "{}"),
            handler = AgentToolHandler { _, _ ->
                calls++
                if (calls == 1) error("first")
                AgentToolResult(true, "ok")
            },
            execution = AgentToolExecutionConfig(maxAttempts = 2)
        )
        val retryResult = AgentToolExecutor().execute(
            retried,
            context,
            AgentToolCall("2", "retry", "{}")
        )
        assertTrue(retryResult.success)
        assertEquals(2, calls)
    }

    @Test
    fun pollingIsPartOfTheExecutionPipeline() = runBlocking {
        var calls = 0
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("status", "Status", "{}"),
            handler = AgentToolHandler { _, _ ->
                calls++
                if (calls < 3) {
                    AgentToolResult(success = true, pending = true)
                } else {
                    AgentToolResult(success = true, output = "ready")
                }
            },
            execution = AgentToolExecutionConfig(
                polling = AgentPollingPolicy(
                    maxChecks = 3,
                    intervalMillis = 1
                )
            )
        )
        val result = AgentToolExecutor().execute(
            tool,
            context,
            AgentToolCall("1", "status", "{}")
        )

        assertTrue(result.success)
        assertFalse(result.pending)
        assertEquals("ready", result.output)
        assertEquals(3, calls)
    }

    @Test
    fun pollingTimeoutIsReturnedAsToolFailure() = runBlocking {
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("status", "Status", "{}"),
            handler = AgentToolHandler { _, _ ->
                AgentToolResult(success = true, pending = true)
            },
            execution = AgentToolExecutionConfig(
                polling = AgentPollingPolicy(
                    maxChecks = 2,
                    intervalMillis = 1
                )
            )
        )
        val result = AgentToolExecutor().execute(
            tool,
            context,
            AgentToolCall("1", "status", "{}")
        )

        assertFalse(result.success)
        assertEquals("POLL_TIMEOUT", result.errorCode)
    }

    @Test
    fun executionHooksCanRewriteCallAndResult() = runBlocking {
        val hook = object : AgentToolExecutionHook {
            override suspend fun beforeExecute(
                tool: AgentToolDescriptor,
                context: AgentRunContext,
                call: AgentToolCall
            ): AgentToolHookDecision = AgentToolHookDecision.Proceed(
                call.copy(inputJson = """{"value":"rewritten"}""")
            )

            override suspend fun afterExecute(
                tool: AgentToolDescriptor,
                context: AgentRunContext,
                call: AgentToolCall,
                result: AgentToolResult
            ): AgentToolResult = result.copy(output = result.output + "-hooked")
        }
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("sample", "Sample", "{}"),
            handler = AgentToolHandler { _, call ->
                AgentToolResult(true, call.inputJson)
            }
        )
        val result = AgentToolExecutor(listOf(hook)).execute(
            tool,
            context,
            AgentToolCall("1", "sample", "{}")
        )

        assertEquals("""{"value":"rewritten"}-hooked""", result.output)
    }

    @Test
    fun confirmationHookFailureIsReturnedAsToolFailure() = runBlocking {
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("danger", "Danger", "{}"),
            handler = AgentToolHandler { _, _ -> AgentToolResult(true) },
            execution = AgentToolExecutionConfig(requiresConfirmation = true)
        )

        val result = AgentToolExecutor().execute(
            tool,
            context.copy(
                confirmationHook = AgentConfirmationHook {
                    error("confirmation backend unavailable")
                }
            ),
            AgentToolCall("1", "danger", "{}")
        )

        assertFalse(result.success)
        assertEquals("CONFIRMATION_ERROR", result.errorCode)
    }

    @Test
    fun executionHookFailureIsReturnedAsToolFailure() = runBlocking {
        val brokenHook = object : AgentToolExecutionHook {
            override suspend fun beforeExecute(
                tool: AgentToolDescriptor,
                context: AgentRunContext,
                call: AgentToolCall
            ): AgentToolHookDecision = error("hook backend unavailable")
        }
        val tool = AgentTool(
            descriptor = AgentToolDescriptor("sample", "Sample", "{}"),
            handler = AgentToolHandler { _, _ -> AgentToolResult(true) }
        )

        val result = AgentToolExecutor(listOf(brokenHook)).execute(
            tool,
            context,
            AgentToolCall("1", "sample", "{}")
        )

        assertFalse(result.success)
        assertEquals("TOOL_HOOK_ERROR", result.errorCode)
    }
}
