package com.coke.nestspace.features.assistant

import android.annotation.SuppressLint
import com.coke.nestspace.core.i18n.AppLanguage
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/** Programmatic overlay view; it is never inflated from XML. */
@SuppressLint("ViewConstructor")
internal class ToolsPanelView(
        context: Context,
        private val accentColor: Int,
        onVoice: () -> Unit,
        onTasks: () -> Unit,
        onRead: () -> Unit,
        onClose: () -> Unit
    ) : LinearLayout(context) {

        private val density = resources.displayMetrics.density
        private val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val primaryText = if (night) Color.WHITE else 0xFF1D1B20.toInt()
        private val secondaryText = if (night) 0xFFCAC4D0.toInt() else 0xFF625B71.toInt()
        private val tasksAction: ToolActionView

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(
                    ColorUtils.setAlphaComponent(
                        if (night) 0xFF202025.toInt() else 0xFFFFFFFF.toInt(),
                        if (night) 238 else 244
                    )
                )
                setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 58))
            }

            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    orientation = HORIZONTAL
                    addView(
                        TextView(context).apply {
                            text = "×"
                            contentDescription = AppLanguage.tr("关闭工具", "Close tools")
                            gravity = Gravity.CENTER
                            setTextColor(secondaryText)
                            textSize = 20f
                            isClickable = true
                            setOnClickListener { onClose() }
                            layoutParams = LayoutParams(dp(28), dp(24))
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(24))
            )

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        ToolActionView(
                            kind = ToolIcon.VOICE,
                            label = AppLanguage.tr("语音", "Voice"),
                            onClick = onVoice
                        ),
                        LayoutParams(0, dp(56), 1f).apply {
                            marginEnd = dp(4)
                        }
                    )
                    tasksAction = ToolActionView(
                        kind = ToolIcon.TASKS,
                        label = AppLanguage.tr("任务", "Tasks"),
                        onClick = onTasks
                    ).also {
                        addView(
                            it,
                            LayoutParams(0, dp(56), 1f).apply {
                                marginStart = dp(4)
                            }
                        )
                    }
                    addView(
                        ToolActionView(
                            kind = ToolIcon.SCREEN,
                            label = AppLanguage.tr("读屏", "Read"),
                            onClick = onRead
                        ),
                        LayoutParams(0, dp(56), 1f).apply {
                            marginStart = dp(8)
                        }
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply {
                    topMargin = dp(2)
                }
            )
        }

        fun updateTaskCount(count: Int) {
            tasksAction.badgeCount = count
        }

        private inner class ToolActionView(
            private val kind: ToolIcon,
            private val label: String,
            onClick: () -> Unit
        ) : View(context) {
            var badgeCount: Int = 0
                set(value) {
                    field = value.coerceAtLeast(0)
                    contentDescription = when (kind) {
                        ToolIcon.VOICE ->
                            AppLanguage.tr("打开语音助手", "Open voice assistant")
                        ToolIcon.TASKS -> AppLanguage.tr(
                            "查看任务，当前执行中 ${field.coerceAtMost(99)} 个",
                            "View tasks, ${field.coerceAtMost(99)} active"
                        )
                        ToolIcon.SCREEN -> AppLanguage.tr(
                            "读取并理解当前屏幕",
                            "Read and understand this screen"
                        )
                    }
                    invalidate()
                }

            private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
            }
            private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = dp(10).toFloat()
                typeface = Typeface.DEFAULT_BOLD
            }
            private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryText
                textAlign = Paint.Align.CENTER
                textSize = dp(13).toFloat()
                typeface = Typeface.DEFAULT_BOLD
            }

            init {
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ColorUtils.setAlphaComponent(accentColor, 48)
                    ),
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(18).toFloat()
                        setColor(
                            ColorUtils.setAlphaComponent(
                                accentColor,
                                if (night) 52 else 26
                            )
                        )
                    },
                    null
                )
                setOnClickListener { onClick() }
                badgeCount = 0
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val centerX = width / 2f
                val centerY = height * 0.42f
                val iconSize = min(width, height) * 0.30f
                when (kind) {
                    ToolIcon.VOICE -> drawMicrophone(
                        canvas,
                        centerX,
                        centerY,
                        iconSize
                    )
                    ToolIcon.TASKS -> {
                        drawTaskList(
                            canvas,
                            centerX - iconSize * 0.12f,
                            centerY,
                            iconSize
                        )
                        drawBadge(canvas, centerX, centerY, iconSize)
                    }
                    ToolIcon.SCREEN -> drawScreenReader(
                        canvas,
                        centerX,
                        centerY,
                        iconSize
                    )
                }
                val baseline = height * 0.82f
                canvas.drawText(label, centerX, baseline, labelPaint)
            }

            private fun drawMicrophone(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val bodyWidth = size * 0.34f
                canvas.drawRoundRect(
                    RectF(
                        cx - bodyWidth / 2f,
                        cy - size * 0.48f,
                        cx + bodyWidth / 2f,
                        cy + size * 0.10f
                    ),
                    bodyWidth / 2f,
                    bodyWidth / 2f,
                    iconPaint
                )
                canvas.drawArc(
                    RectF(
                        cx - size * 0.30f,
                        cy - size * 0.14f,
                        cx + size * 0.30f,
                        cy + size * 0.38f
                    ),
                    0f,
                    180f,
                    false,
                    iconPaint
                )
                canvas.drawLine(
                    cx,
                    cy + size * 0.38f,
                    cx,
                    cy + size * 0.55f,
                    iconPaint
                )
                canvas.drawLine(
                    cx - size * 0.16f,
                    cy + size * 0.55f,
                    cx + size * 0.16f,
                    cy + size * 0.55f,
                    iconPaint
                )
            }

            private fun drawTaskList(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val left = cx - size * 0.38f
                val lineStart = cx - size * 0.08f
                val lineEnd = cx + size * 0.38f
                listOf(-0.32f, 0f, 0.32f).forEachIndexed { index, offset ->
                    val y = cy + size * offset
                    canvas.drawCircle(
                        left,
                        y,
                        size * 0.075f,
                        iconPaint
                    )
                    canvas.drawLine(
                        lineStart,
                        y,
                        lineEnd - index * size * 0.06f,
                        y,
                        iconPaint
                    )
                }
            }

            private fun drawScreenReader(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val left = cx - size * 0.38f
                val right = cx + size * 0.38f
                val top = cy - size * 0.40f
                val bottom = cy + size * 0.40f
                val corner = size * 0.18f
                canvas.drawLine(left, top + corner, left, top, iconPaint)
                canvas.drawLine(left, top, left + corner, top, iconPaint)
                canvas.drawLine(right - corner, top, right, top, iconPaint)
                canvas.drawLine(right, top, right, top + corner, iconPaint)
                canvas.drawLine(right, bottom - corner, right, bottom, iconPaint)
                canvas.drawLine(right, bottom, right - corner, bottom, iconPaint)
                canvas.drawLine(left, bottom, left + corner, bottom, iconPaint)
                canvas.drawLine(left, bottom - corner, left, bottom, iconPaint)
                listOf(-0.15f, 0.08f, 0.27f).forEachIndexed { index, offset ->
                    canvas.drawLine(
                        left + size * 0.18f,
                        cy + size * offset,
                        right - size * (0.24f + index * 0.05f),
                        cy + size * offset,
                        iconPaint
                    )
                }
            }

            private fun drawBadge(
                canvas: Canvas,
                cx: Float,
                cy: Float,
                size: Float
            ) {
                val label = if (badgeCount > 99) "99+" else badgeCount.toString()
                badgeTextPaint.textSize = if (label.length >= 2) {
                    dp(9).toFloat()
                } else {
                    dp(10).toFloat()
                }
                val minWidth = dp(18).toFloat()
                val badgeWidth = maxOf(
                    minWidth,
                    badgeTextPaint.measureText(label) + dp(8)
                )
                val badgeHeight = dp(18).toFloat()
                val badgeLeft = cx + size * 0.18f
                val badgeTop = cy - size * 0.58f
                canvas.drawRoundRect(
                    RectF(
                        badgeLeft,
                        badgeTop,
                        badgeLeft + badgeWidth,
                        badgeTop + badgeHeight
                    ),
                    badgeHeight / 2f,
                    badgeHeight / 2f,
                    badgePaint
                )
                val baseline = badgeTop +
                    badgeHeight / 2f -
                    (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
                canvas.drawText(
                    label,
                    badgeLeft + badgeWidth / 2f,
                    baseline,
                    badgeTextPaint
                )
            }
        }

        private enum class ToolIcon { VOICE, TASKS, SCREEN }

        private fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            ).toInt()
    }
