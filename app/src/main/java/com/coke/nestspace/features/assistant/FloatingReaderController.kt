package com.coke.nestspace.features.assistant

import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.AppGraph
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.agent.runtime.NestSpaceAgentRunner
import com.coke.nestspace.features.speech.AssistantSpeech
import com.coke.nestspace.features.speech.TtsSettings
import com.coke.nestspace.features.screenreader.ScreenReaderSuggestionStore
import com.coke.nestspace.features.screenreader.ScreenReaderActionType
import com.coke.nestspace.agent.task.OperationTaskRegistry
import com.coke.nestspace.agent.tools.ContainerDisplayMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

    internal fun FloatingDotOverlay.showReaderPanel(app: Context) {
        if (readerView != null) return
        val wm = windowManager ?: return
        val bounds = windowBounds(app, wm)
        val density = app.resources.displayMetrics.density
        val width = minOf(
            (430 * density).toInt(),
            bounds.width() - (32 * density).toInt()
        )
        val height = minOf(
            (320 * density).toInt(),
            bounds.height() - (32 * density).toInt()
        )
        val margin = (16 * density).toInt()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - width) / 2)
                .coerceIn(margin, maxOf(margin, bounds.width() - width - margin))
            y = (bounds.height() - height - (76 * density).toInt())
                .coerceIn(margin, maxOf(margin, bounds.height() - height - margin))
        }
        val panel = ReaderPanelView(
            context = app,
            accentColor = currentColor(app),
            canSpeak = TtsSettings.load(app).enabled,
            onPrimary = {
                val active = readerTaskId
                    ?.let { AssistantTaskCoordinator.get(it, "local-llm") }
                if (active != null && !active.state.terminal) {
                    AssistantTaskCoordinator.cancel(active.id, "local-llm")
                } else {
                    startScreenReaderTask(
                        app,
                        AppLanguage.tr(
                            "重新读取当前容器页面并解释屏幕，不执行页面动作。",
                            "Read and explain the current container screen again without " +
                                "executing page actions."
                        )
                    )
                }
            },
            onSpeak = {
                val result = readerTaskId
                    ?.let { AssistantTaskCoordinator.get(it, "local-llm") }
                    ?.result
                if (!result.isNullOrBlank()) {
                    speakReaderResult(app, result)
                }
            },
            onSuggestion = { suggestion ->
                if (suggestion.type == ScreenReaderActionType.OPEN_APP) {
                    startReaderTask(
                        app = app,
                        goal = suggestion.goal,
                        screenReaderAgent = false,
                        displayTitle = AppLanguage.tr("打开应用", "Open app")
                    )
                } else {
                    startScreenReaderTask(app, suggestion.goal)
                }
            },
            onClose = ::dismissReaderPanel
        )
        val added = runCatching {
            wm.addView(panel, layoutParams)
            true
        }.onFailure { Log.w(TAG, "screen reader overlay failed", it) }
            .getOrDefault(false)
        if (!added) return
        readerView = panel
        dotView?.visibility = View.GONE
        panel.alpha = 0f
        panel.translationY = 18f * density
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    internal fun FloatingDotOverlay.startScreenReaderTask(app: Context, goal: String) {
        startReaderTask(
            app = app,
            goal = goal,
            screenReaderAgent = true,
            displayTitle = AppLanguage.tr("屏幕解读", "Screen reading")
        )
    }

    internal fun FloatingDotOverlay.startReaderTask(
        app: Context,
        goal: String,
        screenReaderAgent: Boolean,
        displayTitle: String
    ) {
        readerSpeechJob?.cancel()
        readerSpeechJob = null
        AssistantSpeech.stop()
        spokenReaderTaskId = null
        readerTaskJob?.cancel()
        readerTaskId?.let(ScreenReaderSuggestionStore::clear)
        readerView?.showWorking()
        val result = if (screenReaderAgent) {
            NestSpaceAgentRunner.startScreenReader(
                context = app,
                registry = AppGraph.registry(app),
                goal = goal
            )
        } else {
            NestSpaceAgentRunner.startWorker(
                context = app,
                registry = AppGraph.registry(app),
                ownerId = NestSpaceAgentRunner.LOCAL_OWNER,
                goal = goal,
                extraContext = "",
                displayMode = ContainerDisplayMode.VISIBLE,
                displayTitle = displayTitle
            )
        }
        when (result) {
            is AssistantTaskCoordinator.EnqueueResult.Error -> {
                readerTaskId = null
                readerView?.showError(result.message)
            }
            is AssistantTaskCoordinator.EnqueueResult.Queued -> {
                readerTaskId = result.task.id
                observeReaderTask(app, result.task.id)
            }
        }
    }

    internal fun FloatingDotOverlay.observeReaderTask(app: Context, taskId: String) {
        readerTaskJob?.cancel()
        readerTaskJob = toolsScope.launch {
            OperationTaskRegistry.tasksFlow.collectLatest {
                val task = AssistantTaskCoordinator.get(taskId, "local-llm")
                    ?: return@collectLatest
                readerView?.update(task)
                val terminal = task.state.terminal
                if (terminal &&
                    spokenReaderTaskId != taskId &&
                    !task.result.isNullOrBlank()
                ) {
                    spokenReaderTaskId = taskId
                    speakReaderResult(app, task.result)
                }
            }
        }
    }

    internal fun FloatingDotOverlay.speakReaderResult(app: Context, text: String) {
        val config = TtsSettings.load(app)
        if (!config.enabled) return
        readerSpeechJob?.cancel()
        AssistantSpeech.stop()
        readerSpeechJob = toolsScope.launch {
            if (readerView == null || isHidden()) return@launch
            runCatching {
                AssistantSpeech.speak(
                    context = app,
                    text = text,
                    providerId = config.providerId,
                    interrupt = true
                )
            }
        }
    }

    internal fun FloatingDotOverlay.dismissReaderPanel() {
        readerSpeechJob?.cancel()
        readerSpeechJob = null
        AssistantSpeech.stop()
        readerTaskJob?.cancel()
        readerTaskJob = null
        readerTaskId?.let(ScreenReaderSuggestionStore::clear)
        readerTaskId = null
        spokenReaderTaskId = null
        val view = readerView ?: return
        readerView = null
        runCatching { windowManager?.removeView(view) }
        dotView?.visibility = if (isHidden()) View.GONE else View.VISIBLE
    }
