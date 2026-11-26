package com.coke.nestspace.features.assistant

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import com.coke.nestspace.core.i18n.AppLanguage
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.features.screenreader.ScreenReaderSuggestion
import com.coke.nestspace.features.screenreader.ScreenReaderSuggestionStore
import com.coke.nestspace.agent.task.OperationTaskRegistry

/** Programmatic overlay view; it is never inflated from XML. */
@SuppressLint("ViewConstructor")
internal class ReaderPanelView(
        context: Context,
        private val accentColor: Int,
        private val canSpeak: Boolean,
        private val onPrimary: () -> Unit,
        private val onSpeak: () -> Unit,
        private val onSuggestion: (ScreenReaderSuggestion) -> Unit,
        private val onClose: () -> Unit
    ) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        private val statusText: TextView
        private val speakButton: View
        private val bodyText: TextView
        private val bodyScroll: ScrollView
        private val readingIndicator: ReadingIndicatorView
        private val footerRow: LinearLayout
        private var terminal = false
        private var footerKey: String? = null

        init {
            orientation = VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            elevation = dp(14).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(0xE61B1C24.toInt())
                setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 100))
            }

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = AppLanguage.tr("智能读屏", "Screen reader")
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            setTypeface(typeface, Typeface.BOLD)
                            layoutParams = LayoutParams(0, dp(32), 1f)
                        }
                    )
                    speakButton = speakerButton(onSpeak)
                    addView(
                        speakButton,
                        LayoutParams(dp(30), dp(30)).apply {
                            marginEnd = dp(5)
                        }
                    )
                    statusText = TextView(context).apply {
                        text = AppLanguage.tr("准备中", "Preparing")
                        setTextColor(ColorUtils.setAlphaComponent(accentColor, 255))
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setPadding(dp(9), 0, dp(9), 0)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setColor(ColorUtils.setAlphaComponent(accentColor, 48))
                        }
                        layoutParams = LayoutParams(dp(68), dp(24))
                    }
                    addView(statusText)
                    addView(
                        TextView(context).apply {
                            text = "×"
                            contentDescription = AppLanguage.tr("关闭读屏", "Close screen reader")
                            gravity = Gravity.CENTER
                            setTextColor(0xFFCAC7D2.toInt())
                            textSize = 22f
                            isClickable = true
                            setOnClickListener { onClose() }
                            layoutParams = LayoutParams(dp(34), dp(30)).apply {
                                marginStart = dp(5)
                            }
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(34))
            )

            readingIndicator = ReadingIndicatorView(context).apply {
                visibility = View.VISIBLE
            }
            addView(
                readingIndicator,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(18)).apply {
                    topMargin = dp(7)
                }
            )

            bodyText = TextView(context).apply {
                setTextColor(0xFFF4F4F8.toInt())
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1.08f)
                text = AppLanguage.tr(
                    "正在读取当前页面…",
                    "Reading the current screen…"
                )
            }
            bodyScroll = ScrollView(context).apply {
                isFillViewport = true
                addView(
                    bodyText,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(
                bodyScroll,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(10)
                }
            )

            footerRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(
                HorizontalScrollView(context).apply {
                    isFillViewport = true
                    isHorizontalScrollBarEnabled = false
                    addView(
                        footerRow,
                        LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.MATCH_PARENT
                        )
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).apply {
                    topMargin = dp(12)
                }
            )

            renderFooter(
                key = "working",
                stop = { onPrimary() }
            )
            speakButton.visibility = View.GONE
        }

        fun showWorking() {
            terminal = false
            statusText.text = AppLanguage.tr("读屏中", "Reading")
            setBody("", AppLanguage.tr(
                "正在理解当前页面，请稍候…",
                "Understanding the current screen…"
            ))
            readingIndicator.show()
            renderFooter(
                key = "working",
                stop = { onPrimary() }
            )
        }

        fun showError(message: String) {
            terminal = true
            statusText.text = AppLanguage.tr("失败", "Failed")
            setBody("error:$message", message)
            readingIndicator.hide()
            speakButton.visibility = View.GONE
            renderFooter(
                key = "error:$message",
                retry = { onPrimary() }
            )
        }

        fun update(task: AssistantTaskCoordinator.TaskView) {
            terminal = task.state.terminal
            statusText.text = when (task.state) {
                OperationTaskRegistry.State.QUEUED,
                OperationTaskRegistry.State.PREPARING ->
                    AppLanguage.tr("等待中", "Waiting")
                OperationTaskRegistry.State.RUNNING,
                OperationTaskRegistry.State.FINALIZING ->
                    AppLanguage.tr("读取中", "Reading")
                OperationTaskRegistry.State.PAUSED ->
                    AppLanguage.tr("已暂停", "Paused")
                OperationTaskRegistry.State.WAITING_USER ->
                    AppLanguage.tr("待处理", "Needs you")
                OperationTaskRegistry.State.CANCELLING ->
                    AppLanguage.tr("停止中", "Stopping")
                OperationTaskRegistry.State.SUCCEEDED ->
                    AppLanguage.tr("已完成", "Done")
                OperationTaskRegistry.State.FAILED ->
                    AppLanguage.tr("失败", "Failed")
                OperationTaskRegistry.State.CANCELLED ->
                    AppLanguage.tr("已停止", "Stopped")
            }
            val workingBody = when (task.state) {
                OperationTaskRegistry.State.QUEUED,
                OperationTaskRegistry.State.PREPARING ->
                    AppLanguage.tr(
                        "正在准备读屏，请稍候…",
                        "Preparing the screen reader…"
                    )
                OperationTaskRegistry.State.CANCELLING ->
                    AppLanguage.tr("正在停止读屏…", "Stopping the screen reader…")
                OperationTaskRegistry.State.PAUSED ->
                    AppLanguage.tr("读屏已暂停。", "Screen reading is paused.")
                else -> AppLanguage.tr(
                    "正在理解当前页面，请稍候…",
                    "Understanding the current screen…"
                )
            }
            val body = when {
                task.state == OperationTaskRegistry.State.CANCELLED ->
                    AppLanguage.tr(
                        "读屏已停止。",
                        "Screen reading stopped."
                    )
                !task.result.isNullOrBlank() -> task.result
                !task.error.isNullOrBlank() -> task.error
                !terminal -> workingBody
                else -> AppLanguage.tr(
                    "读屏已完成。",
                    "Screen reading finished."
                )
            }
            setBody("${task.state}:${task.progressRevision}:$body", body)
            if (!terminal) {
                readingIndicator.show()
                speakButton.visibility = View.GONE
                renderFooter(
                    key = "running:${task.state}",
                    stop = { onPrimary() }
                )
                return
            }
            readingIndicator.hide()
            speakButton.visibility = if (
                canSpeak &&
                task.state == OperationTaskRegistry.State.SUCCEEDED &&
                !task.result.isNullOrBlank()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            val suggestions = ScreenReaderSuggestionStore.get(task.id)
            val key = buildString {
                append(task.id)
                append('|')
                append(task.state)
                append('|')
                append(canSpeak && !task.result.isNullOrBlank())
                suggestions.forEach {
                    append('|').append(it.label)
                }
            }
            when {
                task.state == OperationTaskRegistry.State.SUCCEEDED &&
                    suggestions.isNotEmpty() -> renderFooter(
                        key = key,
                        suggestions = suggestions,
                        onSuggestion = onSuggestion
                    )
                else -> renderFooter(
                    key = key,
                    retry = { onPrimary() }
                )
            }
        }

        private fun setBody(key: String, value: String) {
            if (bodyText.text.toString() == value && bodyText.tag == key) return
            val old = bodyText.text
            bodyText.text = value
            bodyText.tag = key
            if (old != value) bodyScroll.scrollTo(0, 0)
        }

        private fun renderFooter(
            key: String,
            stop: (() -> Unit)? = null,
            retry: (() -> Unit)? = null,
            suggestions: List<ScreenReaderSuggestion> = emptyList(),
            onSuggestion: ((ScreenReaderSuggestion) -> Unit)? = null
        ) {
            if (footerKey == key) return
            footerKey = key
            footerRow.removeAllViews()
            when {
                stop != null -> footerRow.addView(
                    actionButton(
                        label = AppLanguage.tr("停止", "Stop"),
                        contentDescription = AppLanguage.tr("停止读屏", "Stop screen reader"),
                        onClick = stop
                    )
                )
                retry != null -> footerRow.addView(
                    actionButton(
                        label = AppLanguage.tr("重试", "Retry"),
                        contentDescription = AppLanguage.tr("重试读屏", "Retry screen reader"),
                        onClick = retry
                    )
                )
                else -> {
                    suggestions.forEach { suggestion ->
                        footerRow.addView(
                            actionButton(
                                label = suggestion.label,
                                contentDescription = AppLanguage.tr(
                                    "建议：${suggestion.label}",
                                    "Suggestion: ${suggestion.label}"
                                ),
                                onClick = {
                                    onSuggestion?.invoke(suggestion)
                                }
                            )
                        )
                    }
                }
            }
        }

        private fun actionButton(
            label: String,
            contentDescription: String,
            onClick: () -> Unit
        ): LinearLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(78)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dp(7)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(accentColor, 70)
                ),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accentColor, 42))
                },
                null
            )
            addView(
                TextView(context).apply {
                    text = label
                    maxWidth = dp(154)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                }
            )
            setOnClickListener { onClick() }
        }

        private fun speakerButton(onClick: () -> Unit): View =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = AppLanguage.tr(
                    "朗读当前读屏结果",
                    "Speak the screen-reader result"
                )
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(accentColor, 70)
                    ),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.setAlphaComponent(accentColor, 42))
                    },
                    null
                )
                addView(
                    SpeakerGlyph(context),
                    LayoutParams(dp(17), dp(17))
                )
                setOnClickListener { onClick() }
            }

        private inner class SpeakerGlyph(context: Context) : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(1.6f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val width = width.toFloat()
                val height = height.toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(width * 0.08f, height * 0.40f)
                    lineTo(width * 0.30f, height * 0.40f)
                    lineTo(width * 0.54f, height * 0.20f)
                    lineTo(width * 0.54f, height * 0.80f)
                    lineTo(width * 0.30f, height * 0.60f)
                    lineTo(width * 0.08f, height * 0.60f)
                    close()
                }
                canvas.drawPath(path, fill)
                canvas.drawArc(
                    width * 0.48f,
                    height * 0.30f,
                    width * 0.78f,
                    height * 0.70f,
                    -55f,
                    110f,
                    false,
                    paint
                )
                canvas.drawArc(
                    width * 0.42f,
                    height * 0.17f,
                    width * 0.95f,
                    height * 0.83f,
                    -55f,
                    110f,
                    false,
                    paint
                )
            }
        }

        private inner class ReadingIndicatorView(context: Context) : View(context) {
            private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(accentColor, 48)
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                strokeCap = Paint.Cap.ROUND
            }
            private val movingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = dp(2.5f)
                strokeCap = Paint.Cap.ROUND
            }
            private var animator: ValueAnimator? = null
            private var running = false
            private var phase = 0f

            fun show() {
                if (visibility == View.VISIBLE && running) return
                visibility = View.VISIBLE
                running = true
                animator?.cancel()
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1_250L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }

            fun hide() {
                running = false
                animator?.cancel()
                animator = null
                visibility = View.GONE
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerY = height / 2f
                val inset = dp(2f)
                canvas.drawLine(inset, centerY, width - inset, centerY, trackPaint)
                val span = (width * 0.18f).coerceAtLeast(dp(24f))
                val start = -span + phase * (width + span * 2f)
                val left = start.coerceIn(inset, (width - inset).toFloat())
                val right = (start + span)
                    .coerceIn(inset, (width - inset).toFloat())
                if (right > left) {
                    canvas.drawLine(left, centerY, right, centerY, movingPaint)
                }
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (visibility == View.VISIBLE) show()
            }

            override fun onDetachedFromWindow() {
                animator?.cancel()
                animator = null
                running = false
                super.onDetachedFromWindow()
            }
        }

        private fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            ).toInt()

        private fun dp(value: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                resources.displayMetrics
            )
    }
