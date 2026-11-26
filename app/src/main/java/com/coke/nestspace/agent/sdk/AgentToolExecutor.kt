package com.coke.nestspace.agent.sdk

import com.coke.nestspace.agent.sdk.hook.AgentConfirmationRequest
import com.coke.nestspace.agent.sdk.hook.AgentToolExecutionHook
import com.coke.nestspace.agent.sdk.hook.AgentToolHookDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes one Agent tool with optional confirmation, timeout and bounded
 * retry. Retries are disabled by default because tools may mutate external
 * state.
 */
class AgentToolExecutor(
    private val hooks: List<AgentToolExecutionHook> = emptyList()
) {
    suspend fun execute(
        tool: AgentTool,
        context: AgentRunContext,
        call: AgentToolCall
    ): AgentToolResult = try {
        executeInternal(tool, context, call)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AgentToolResult(
            success = false,
            output = error.message ?: error.javaClass.simpleName,
            errorCode = "TOOL_EXCEPTION"
        )
    }

    private suspend fun executeInternal(
        tool: AgentTool,
        context: AgentRunContext,
        call: AgentToolCall
    ): AgentToolResult {
        val policy = tool.execution
        if (policy.requiresConfirmation) {
            val hook = context.confirmationHook
                ?: return AgentToolResult(
                    success = false,
                    output = "Confirmation is unavailable for ${call.name}",
                    errorCode = "CONFIRMATION_UNAVAILABLE"
                )
            val confirmed = try {
                hook.confirm(
                    AgentConfirmationRequest(
                        title = policy.confirmationTitle ?: "Allow ${call.name}?",
                        detail = policy.confirmationDetail
                            ?: "This tool may change external state.",
                        confirmLabel = "Allow once",
                        dismissLabel = "Deny",
                        timeoutMillis = policy.timeoutMillis ?: 120_000L
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return AgentToolResult(
                    success = false,
                    output = error.message ?: error.javaClass.simpleName,
                    errorCode = "CONFIRMATION_ERROR"
                )
            }
            if (!confirmed) {
                return AgentToolResult(
                    success = false,
                    output = "Tool call was not confirmed: ${call.name}",
                    errorCode = "CONFIRMATION_DENIED"
                )
            }
        }

        var executionCall = call
        for (hook in hooks) {
            val decision = try {
                hook.beforeExecute(tool.descriptor, context, executionCall)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return AgentToolResult(
                    success = false,
                    output = error.message ?: error.javaClass.simpleName,
                    errorCode = "TOOL_HOOK_ERROR"
                )
            }
            when (decision) {
                is AgentToolHookDecision.Block -> {
                    val blocked = AgentToolResult(
                        success = false,
                        output = decision.message,
                        errorCode = decision.code
                    )
                    return applyAfterHooks(
                        tool,
                        context,
                        executionCall,
                        blocked
                    )
                }
                is AgentToolHookDecision.Proceed -> executionCall = decision.call
            }
        }

        val polling = policy.polling
        if (polling == null) {
            val result = executeWithRetries(tool, context, executionCall, policy)
            val final = if (result.pending) {
                result.copy(
                    success = false,
                    errorCode = "POLLING_NOT_CONFIGURED"
                )
            } else {
                result
            }
            return applyAfterHooks(tool, context, executionCall, final)
        }
        val polled = pollAgentState(
            policy = polling,
            probe = {
                executeWithRetries(tool, context, executionCall, policy)
            },
            isComplete = { !it.pending }
        )
        val final = if (!polled.timedOut) {
            requireNotNull(polled.value)
        } else {
            polled.value?.copy(
            success = false,
            errorCode = polled.value.errorCode ?: "POLL_TIMEOUT",
            pending = false
            ) ?: AgentToolResult(
                success = false,
                output = "Tool polling produced no result: ${call.name}",
                errorCode = "POLL_TIMEOUT"
            )
        }
        return applyAfterHooks(tool, context, executionCall, final)
    }

    private suspend fun applyAfterHooks(
        tool: AgentTool,
        context: AgentRunContext,
        call: AgentToolCall,
        result: AgentToolResult
    ): AgentToolResult {
        var output = result
        for (hook in hooks.asReversed()) {
            output = try {
                hook.afterExecute(tool.descriptor, context, call, output)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return AgentToolResult(
                    success = false,
                    output = error.message ?: error.javaClass.simpleName,
                    errorCode = "TOOL_HOOK_ERROR"
                )
            }
        }
        return output
    }

    private suspend fun executeWithRetries(
        tool: AgentTool,
        context: AgentRunContext,
        call: AgentToolCall,
        policy: AgentToolExecutionConfig
    ): AgentToolResult {
        var lastFailure: AgentToolResult? = null
        repeat(policy.maxAttempts) {
            val result = executeAttempt(tool, context, call, policy.timeoutMillis)
            if (result.success || result.pending) return result
            if (result.errorCode != "TOOL_TIMEOUT" &&
                result.errorCode != "TOOL_EXCEPTION"
            ) {
                return result
            }
            lastFailure = result
        }
        return lastFailure ?: AgentToolResult(
            success = false,
            output = "Tool execution failed: ${call.name}",
            errorCode = "TOOL_EXCEPTION"
        )
    }

    private suspend fun executeAttempt(
        tool: AgentTool,
        context: AgentRunContext,
        call: AgentToolCall,
        timeoutMillis: Long?
    ): AgentToolResult {
        return try {
            val result = if (timeoutMillis == null) {
                tool.handler.execute(context, call)
            } else {
                withTimeoutOrNull(timeoutMillis) {
                    tool.handler.execute(context, call)
                }
            }
            result ?: AgentToolResult(
                success = false,
                output = "Tool timed out: ${call.name}",
                errorCode = "TOOL_TIMEOUT"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AgentToolResult(
                success = false,
                output = error.message ?: error.javaClass.simpleName,
                errorCode = "TOOL_EXCEPTION"
            )
        }
    }
}
