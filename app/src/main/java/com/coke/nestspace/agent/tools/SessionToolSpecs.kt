package com.coke.nestspace.agent.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.features.browser.BrowserSession
import com.coke.nestspace.agent.task.OperationTaskRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun ToolRegistry.sessionToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "session_acquire",
            description = "Acquire the exclusive control lease for a space.",
            inputSchema = schema("space_id" to "string")
        ) { args, ctx ->
            val ok = leases.acquire(
                args.optString("space_id", ctx.spaceId),
                ctx.ownerId
            )
            ToolResult(ok, if (ok) "lease acquired" else "SPACE_BUSY", errorCode = if (ok) null else "SPACE_BUSY")
        },
        ToolSpec(
            name = "session_release",
            description = "Release all leases held by this session.",
            inputSchema = schema()
        ) { _, ctx ->
            leases.release(ctx.ownerId)
            ToolResult(true, "lease released")
        },
        ToolSpec(
            name = "session_status",
            description = "Show the current lease holder for a space.",
            inputSchema = schema("space_id" to "string")
        ) { args, ctx ->
            ToolResult(true, leases.status(args.optString("space_id", ctx.spaceId)).toString())
        },
        ToolSpec(
            name = "task_status",
            description = "Return one asynchronous operation task by task_id. The terminal state " +
                "distinguishes succeeded, failed and cancelled. It reports progress, speed, ETA and " +
                "idle time; it does not decide that a slow task should be cancelled.",
            inputSchema = schema(
                "task_id" to "string",
                required = listOf("task_id")
            )
        ) { args, ctx ->
            val taskId = args.optString("task_id").trim()
            val task = OperationTaskRegistry.status(taskId, ctx.ownerId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $taskId",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "task_wait",
            description = "Wait until an operation task changes lifecycle state, reaches a terminal " +
                "state, or the timeout expires. Progress-only changes do not wake the wait. A timeout " +
                "is not success and does not cancel the task.",
            inputSchema = schema(
                "task_id" to "string",
                "timeout_s" to "integer",
                required = listOf("task_id")
            )
        ) { args, ctx ->
            val taskId = args.optString("task_id").trim()
            val timeoutSeconds = args.optInt("timeout_s", 30)
                .coerceIn(1, 50)
            val result = OperationTaskRegistry.wait(
                id = taskId,
                timeoutMs = timeoutSeconds * 1_000L,
                ownerId = ctx.ownerId
            )
            val task = result.task
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $taskId",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(
                true,
                taskJson(task)
                    .put("changed", result.changed)
                    .put("timed_out", result.timedOut)
                    .toString()
            )
        },
        ToolSpec(
            name = "task_cancel",
            description = "Request cancellation of an operation task. Returns immediately after " +
                "entering cancelling; use task_wait to observe the final cancelled state.",
            inputSchema = schema(
                "task_id" to "string",
                required = listOf("task_id")
            ),
            mutating = true
        ) { args, ctx ->
            val taskId = args.optString("task_id").trim()
            val result = OperationTaskRegistry.requestCancel(
                id = taskId,
                ownerId = ctx.ownerId
            )
            val task = result.task
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $taskId",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(
                true,
                taskJson(task)
                    .put("accepted", result.accepted)
                    .toString()
            )
        },
        ToolSpec(
            name = "task_list",
            description = "List recent operation tasks owned by this assistant or MCP identity.",
            inputSchema = schema("limit" to "integer")
        ) { args, ctx ->
            val limit = args.optInt("limit", 10).coerceIn(1, 50)
            val array = org.json.JSONArray()
            OperationTaskRegistry.list(ctx.ownerId, limit).forEach {
                array.put(taskJson(it))
            }
            ToolResult(
                true,
                JSONObject().put("tasks", array).toString()
            )
        },
        ToolSpec(
            name = "approval_request",
            description = "Request human approval for a sensitive action. Not implemented in the basic version.",
            inputSchema = schema("reason" to "string")
        ) { _, _ ->
            ToolResult(false, "Approval UI is not implemented in the basic version.", errorCode = "NOT_IMPLEMENTED")
        },
        ToolSpec(
            name = "approval_wait",
            description = "Wait for a human approval. Not implemented in the basic version.",
            inputSchema = schema("approval_id" to "string")
        ) { _, _ ->
            ToolResult(false, "Approval UI is not implemented in the basic version.", errorCode = "NOT_IMPLEMENTED")
        },
        ToolSpec(
            name = "assistant_type",
            description = "Type text directly into the AI assistant input box through the UI state, " +
                "without simulating key events. The text appears in the assistant chat input.",
            inputSchema = schema("text" to "string", required = listOf("text"))
        ) { args, _ ->
            val text = args.optString("text")
            val ok = AssistantBridge.type(text)
            ToolResult(
                ok,
                JSONObject()
                    .put("typed", ok)
                    .put("screen_attached", AssistantBridge.isScreenAttached())
                    .put("busy", AssistantBridge.snapshot.value.busy)
                    .toString(),
                errorCode = if (ok) null else if (text.isBlank()) {
                    "INVALID_ARGUMENT"
                } else {
                    "ASSISTANT_BUSY"
                }
            )
        },
        ToolSpec(
            name = "assistant_send",
            description = "Send text to the AI assistant conversation. If text is omitted, sends the text " +
                "previously injected by assistant_type. Waits for the assistant to finish by default and " +
                "returns its new messages/tool trace.",
            inputSchema = schema("text" to "string", "wait_ms" to "integer"),
            maxWaitMs = 1_000L
        ) { args, _ ->
            val waitMs = args.optInt("wait_ms", 120_000).coerceIn(0, 180_000)
            val before = AssistantBridge.snapshot.value.messages.size
            if (!AssistantBridge.send(args.optString("text"))) {
                return@ToolSpec ToolResult(
                    false,
                    JSONObject()
                        .put("busy", AssistantBridge.snapshot.value.busy)
                        .put("screen_attached", AssistantBridge.isScreenAttached())
                        .put(
                            "input_present",
                            AssistantBridge.currentInput.value.isNotBlank()
                        )
                        .toString(),
                    errorCode = "ASSISTANT_UNAVAILABLE"
                )
            }
            val snapshot = if (waitMs > 0) {
                AssistantBridge.awaitCompletion(waitMs.toLong(), before)
            } else {
                AssistantBridge.snapshot.value
            }
            ToolResult(
                true,
                assistantSnapshotJson(snapshot, before).put("sent", true).toString()
            )
        },
        ToolSpec(
            name = "assistant_status",
            description = "Return the AI assistant's busy state and recent conversation/tool messages. " +
                "Use wait_ms to wait for a running assistant turn to become idle.",
            inputSchema = schema("wait_ms" to "integer"),
            maxWaitMs = 1_000L
        ) { args, _ ->
            val waitMs = args.optInt("wait_ms", 0).coerceIn(0, 180_000)
            val snapshot = if (waitMs > 0) {
                AssistantBridge.awaitIdle(waitMs.toLong())
            } else {
                AssistantBridge.snapshot.value
            }
            ToolResult(true, assistantSnapshotJson(snapshot).toString())
        },
        ToolSpec(
            name = "system_status",
            description = "Check the host Android system: device/Android version, app version, battery, " +
                "network, memory and the current browser mode. Use it when the user asks to inspect " +
                "the system or before reporting a device-level result.",
            inputSchema = schema()
        ) { _, _ ->
            val runtime = Runtime.getRuntime()
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memory)
            val battery = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val network = when {
                capabilities == null -> "offline"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val browserUrl = withContext(Dispatchers.Main) {
                BrowserSession.webView()?.url.orEmpty()
            }
            ToolResult(
                true,
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    .put("app_package", context.packageName)
                    .put("app_version", packageInfo.versionName.orEmpty())
                    .put(
                        "battery_percent",
                        if (level >= 0 && scale > 0) level * 100 / scale else -1
                    )
                    .put("network", network)
                    .put("avail_mem_mb", memory.availMem / (1024 * 1024))
                    .put("total_mem_mb", memory.totalMem / (1024 * 1024))
                    .put("java_used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                    .put("java_max_mb", runtime.maxMemory() / (1024 * 1024))
                    .put("browser_mode", BrowserSession.currentMode())
                    .put("browser_url", browserUrl)
                    .put("browser_tabs", BrowserSession.tabCount())
                    .put(
                        "browser_active_tab",
                        BrowserSession.activeTabId() ?: JSONObject.NULL
                    )
                    .toString()
            )
        },
    )

private fun taskJson(task: OperationTaskRegistry.Snapshot): JSONObject =
    JSONObject()
        .put("task_id", task.id)
        .put("request_id", task.requestId ?: JSONObject.NULL)
        .put("parent_task_id", task.parentTaskId ?: JSONObject.NULL)
        .put("kind", task.kind)
        .put("title", task.title)
        .put("state", task.state.api)
        .put("terminal", task.terminal)
        .put("state_revision", task.stateRevision)
        .put("progress_revision", task.progressRevision)
        .put("progress_percent", task.progressPercent ?: JSONObject.NULL)
        .put("progress_current", task.progressCurrent ?: JSONObject.NULL)
        .put("progress_total", task.progressTotal ?: JSONObject.NULL)
        .put("bytes_per_second", task.bytesPerSecond ?: JSONObject.NULL)
        .put("eta_seconds", task.etaSeconds ?: JSONObject.NULL)
        .put(
            "idle_seconds",
            if (task.terminal) 0L else {
                ((System.currentTimeMillis() - task.updatedAt) / 1_000L)
                    .coerceAtLeast(0L)
            }
        )
        .put("message", task.message)
        .put("result", task.result ?: "")
        .put("error", task.error ?: "")
        .put("cleanup_error", task.cleanupError ?: "")
        .put("idle_ms", task.idleMilliseconds)
        .put("created_at", task.createdAt)
        .put("updated_at", task.updatedAt)
        .put("finished_at", task.finishedAt)
