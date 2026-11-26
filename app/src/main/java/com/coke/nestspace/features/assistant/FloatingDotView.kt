package com.coke.nestspace.features.assistant

import com.coke.nestspace.core.i18n.AppLanguage
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.min

internal class DotView(context: Context) : View(context) {

        var dotColor: Int = FloatingDotSettings.DEFAULT_HOST_COLOR
            set(value) {
                field = value
                invalidate()
            }

        var onTap: (() -> Unit)? = null
        var onDrag: ((Float, Float) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        init {
            isClickable = true
            contentDescription = AppLanguage.tr("AI 工具", "AI tools")
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (!dragging &&
                        (abs(event.rawX - downRawX) > touchSlop ||
                            abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        dragging = true
                    }
                    if (dragging) onDrag?.invoke(dx, dy)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        onDragEnd?.invoke()
                    } else {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) onDragEnd?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            onTap?.invoke()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val side = min(width, height).toFloat() - 6f * density
            val corner = 13f * density
            val bounds = RectF(
                cx - side / 2f,
                cy - side / 2f,
                cx + side / 2f,
                cy + side / 2f
            )

            glowPaint.color = ColorUtils.setAlphaComponent(dotColor, 60)
            canvas.drawRoundRect(
                RectF(
                    bounds.left - 3f * density,
                    bounds.top - 3f * density,
                    bounds.right + 3f * density,
                    bounds.bottom + 3f * density
                ),
                corner + 3f * density,
                corner + 3f * density,
                glowPaint
            )

            fillPaint.shader = RadialGradient(
                cx - side * 0.24f,
                cy - side * 0.24f,
                side * 0.95f,
                intArrayOf(
                    ColorUtils.blendARGB(dotColor, Color.WHITE, 0.30f),
                    dotColor,
                    ColorUtils.blendARGB(dotColor, Color.BLACK, 0.18f)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bounds, corner, corner, fillPaint)
            fillPaint.shader = null

            iconPaint.strokeWidth = side * 0.055f
            val offset = side * 0.17f
            val itemSide = side * 0.19f
            listOf(
                cx - offset to cy - offset,
                cx + offset to cy - offset,
                cx - offset to cy + offset,
                cx + offset to cy + offset
            ).forEach { (x, y) ->
                canvas.drawRoundRect(
                    RectF(
                        x - itemSide / 2f,
                        y - itemSide / 2f,
                        x + itemSide / 2f,
                        y + itemSide / 2f
                    ),
                    itemSide * 0.22f,
                    itemSide * 0.22f,
                    iconPaint
                )
            }
        }
    }
