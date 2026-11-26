package com.coke.nestspace.features.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.task.OperationTaskRegistry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

    internal fun FloatingDotOverlay.toggleToolsPanel(app: Context) {
        if (toolsView != null) {
            dismissToolsPanel()
        } else {
            showToolsPanel(app)
        }
    }

    internal fun FloatingDotOverlay.showToolsPanel(app: Context) {
        val wm = windowManager ?: return
        val dotParams = params ?: return
        val bounds = windowBounds(app, wm)
        val density = app.resources.displayMetrics.density
        val width = (244 * density).toInt()
        val height = (112 * density).toInt()
        val margin = (10 * density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val panelParams = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dotParams.x + dotParams.width / 2 > bounds.width() / 2) {
                dotParams.x + dotParams.width - width
            } else {
                dotParams.x
            }.coerceIn(margin, maxOf(margin, bounds.width() - width - margin))
            val desiredY =
                dotParams.y +
                    dotParams.height / 2 -
                    height / 2
            y = desiredY.coerceIn(
                margin,
                maxOf(margin, bounds.height() - height - margin)
            )
        }
        val panel = ToolsPanelView(
            context = app,
            accentColor = currentColor(app),
            onVoice = {
                dismissToolsPanel()
                openAssistant(app)
            },
            onTasks = {
                dismissToolsPanel()
                openTasks(app)
            },
            onRead = {
                dismissToolsPanel()
                openScreenReader(app)
            },
            onClose = ::dismissToolsPanel
        )
        val added = runCatching {
            wm.addView(panel, panelParams)
            true
        }.onFailure { Log.w(TAG, "floating tools panel failed", it) }
            .getOrDefault(false)
        if (!added) return
        toolsView = panel
        dotView?.visibility = View.GONE
        panel.pivotX =
            if (panelParams.x + panelParams.width / 2 > bounds.width() / 2) {
                panelParams.width.toFloat()
            } else {
                0f
            }
        panel.pivotY = panelParams.height / 2f
        panel.alpha = 0f
        panel.scaleX = 0.18f
        panel.scaleY = 0.18f
        panel.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .start()
        updateToolsTaskCount()
        toolsTaskJob = toolsScope.launch {
            OperationTaskRegistry.tasksFlow.collectLatest {
                updateToolsTaskCount()
            }
        }
    }

    internal fun FloatingDotOverlay.updateToolsTaskCount() {
        val count = OperationTaskRegistry.tasksFlow.value.count {
            it.kind == AssistantTaskCoordinator.TASK_KIND && !it.terminal
        }
        toolsView?.updateTaskCount(count)
    }

    internal fun FloatingDotOverlay.dismissToolsPanel() {
        toolsTaskJob?.cancel()
        toolsTaskJob = null
        val view = toolsView ?: return
        toolsView = null
        runCatching { windowManager?.removeView(view) }
        val app = appContext
        val wm = windowManager
        if (!isHidden() && app != null && wm != null) {
            revealDot(app, wm)
            scheduleAutoDock(app, wm)
        }
        dotView?.visibility = if (isHidden()) View.GONE else View.VISIBLE
    }
