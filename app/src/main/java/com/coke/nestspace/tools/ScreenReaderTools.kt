package com.coke.nestspace.tools

import android.util.Base64
import com.coke.nestspace.control.ContainerPreviewCapture
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.notebook.NotebookScreenReaderBridge
import com.coke.nestspace.screenreader.ScreenDocumentBuilder
import com.coke.nestspace.screenreader.ScreenReaderActionType
import com.coke.nestspace.screenreader.ScreenReaderSuggestionStore
import com.coke.nestspace.screenreader.ScreenSnapshotStore
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.screenReaderToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "screen_read",
        description = "Read the active container screen through the main agent. Return a compact " +
            "ScreenDocument with page type, title, summary, numbered blocks, numbered actions, " +
            "risks and suggested intents. This is read-only. Use snapshot_id for follow-ups " +
            "unless the screen changed. Request the screenshot only when nodes are incomplete " +
            "or visual content cannot be described structurally.",
        inputSchema = schema(
            "package" to "string",
            "instance_id" to "string",
            "process" to "string",
            "slot" to "integer",
            "snapshot_id" to "string",
            "refresh" to "boolean",
            "include_screenshot" to "boolean"
        ),
        maxWaitMs = 10_000L,
        nativeAsync = true
    ) { args, ctx ->
        val explicitContainer = args.has("slot") ||
            args.optString("package").isNotBlank() ||
            args.optString("instance_id").isNotBlank() ||
            args.optString("process").isNotBlank()
        val hostObservation = if (explicitContainer) {
            null
        } else {
            NotebookScreenReaderBridge.observe()
        }
        val slot = if (hostObservation == null) containerSlot(args) else null
        if (hostObservation == null && missingRequestedContainer(args, slot)) {
            return@ToolSpec noContainerResult(null, args)
        }
        val refresh = args.optBoolean("refresh", false)
        val cached = args.optString("snapshot_id")
            .takeIf(String::isNotBlank)
            ?.let(ScreenSnapshotStore::get)
            ?.takeIf { !refresh && (slot == null || it.slot == slot) }
        var document = cached?.document
        if (document == null) {
            val observation = hostObservation ?: ContainerUiClient.observeJson(context, slot)
                ?: return@ToolSpec noContainerResult(slot, args)
            document = ScreenDocumentBuilder.build(observation)
            ScreenSnapshotStore.save(slot, document)
            if (hostObservation == null) {
                capturePreview(slot, args.optString("package"))
            }
        }
        ScreenReaderSuggestionStore.saveFromDocument(ctx.parentTaskId, document)
        val includeScreenshot = args.optBoolean("include_screenshot", false)
        val jpeg = if (includeScreenshot) {
            ContainerPreviewCapture.capture(
                context = context,
                slot = slot ?: com.coke.nestspace.container.ContainerProcessPool.lastSlot() ?: -1,
                label = args.optString("package"),
                rejectBlank = false
            )
        } else {
            null
        }
        val result = JSONObject(document.toString())
            .put("cached", cached != null)
            .put("screenshot_requested", includeScreenshot)
            .put("screenshot_captured", jpeg != null)
            .put(
                "agent_mode",
                "main_agent_only"
            )
        ToolResult(
            true,
            result.toString(),
            imageBase64 = jpeg?.let {
                Base64.encodeToString(it, Base64.NO_WRAP)
            },
            imageMimeType = "image/jpeg"
        )
    },
    ToolSpec(
        name = "screen_suggest",
        description = "Register one recommended and up to three alternative next actions for " +
            "the current screen-reader result. These labels populate the floating reader panel; " +
            "they do not execute page actions.",
        inputSchema = schema(
            "recommended_type" to "string",
            "recommended" to "string",
            "alternative_1_type" to "string",
            "alternative_1" to "string",
            "alternative_2_type" to "string",
            "alternative_2" to "string",
            "alternative_3_type" to "string",
            "alternative_3" to "string",
            required = listOf("recommended_type", "recommended")
        ),
        nativeAsync = true
    ) { args, ctx ->
        val recommended = args.optString("recommended").trim()
        if (recommended.isBlank()) {
            return@ToolSpec ToolResult(
                false,
                "recommended is required",
                errorCode = "INVALID_ARGUMENT"
            )
        }
        val recommendedType = ScreenReaderActionType.fromApi(
            args.optString("recommended_type")
        ) ?: return@ToolSpec ToolResult(
            false,
            "recommended_type must be explain, extract, compare, navigate, translate, " +
                "search, operate or open_app",
            errorCode = "INVALID_ARGUMENT"
        )
        val suggestions = buildList {
            add(recommendedType to recommended)
            for (index in 1..3) {
                val label = args.optString("alternative_$index").trim()
                if (label.isBlank()) continue
                val type = ScreenReaderActionType.fromApi(
                    args.optString("alternative_${index}_type")
                ) ?: continue
                add(type to label)
            }
        }
        ScreenReaderSuggestionStore.saveFromLabels(
            taskId = ctx.parentTaskId,
            suggestions = suggestions,
            recommendedLabel = recommended
        )
        ToolResult(
            true,
            JSONObject()
                .put(
                    "suggestions",
                    JSONArray().apply {
                        suggestions.forEach { (type, label) ->
                            put(
                                JSONObject()
                                    .put("type", type.api)
                                    .put("label", label)
                            )
                        }
                    }
                )
                .toString()
        )
    },
    ToolSpec(
        name = "screen_highlight",
        description = "Draw temporary numbered highlights over container nodes returned by " +
            "screen_read. Read-only visual aid; it does not click or navigate.",
        inputSchema = schema(
            "node_ids" to "string",
            "slot" to "integer",
            "duration_ms" to "integer",
            required = listOf("node_ids")
        ),
        nativeAsync = true
    ) { args, _ ->
        val ids = args.optString("node_ids")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
        if (ids.isEmpty()) {
            return@ToolSpec ToolResult(
                false,
                "node_ids is required",
                errorCode = "INVALID_ARGUMENT"
            )
        }
        val slot = containerSlot(args)
        val ok = ContainerUiClient.highlightNodes(
            context = context,
            slot = slot,
            ids = ids,
            durationMs = args.optLong("duration_ms", 4_000L)
                .coerceIn(500L, 15_000L)
        ) ?: return@ToolSpec noContainerResult(slot, args)
        ToolResult(
            ok,
            JSONObject()
                .put("highlighted", ids)
                .put("count", ids.size)
                .toString(),
            errorCode = if (ok) null else "HIGHLIGHT_FAILED"
        )
    }
)
