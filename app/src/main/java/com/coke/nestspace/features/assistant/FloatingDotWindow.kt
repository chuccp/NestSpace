package com.coke.nestspace.features.assistant

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

    internal fun FloatingDotOverlay.clampToCurrentBounds(app: Context) {
        val wm = windowManager ?: return
        val view = dotView ?: return
        val layoutParams = params ?: return
        val density = app.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        val peek = (18 * density).toInt()
        val bounds = windowBounds(app, wm)
        if (lastWindowHeight > 0 && lastWindowHeight != bounds.height()) {
            layoutParams.y = (
                layoutParams.y.toFloat() /
                    lastWindowHeight *
                    bounds.height()
                ).roundToInt()
        }
        lastWindowHeight = bounds.height()
        layoutParams.x = if (edgeRevealed) {
            revealedX(dockedEdge, bounds, layoutParams.width, margin)
        } else {
            dockedX(dockedEdge, bounds, layoutParams.width, peek)
        }
        clampY(layoutParams, bounds, layoutParams.width, margin)
        runCatching { wm.updateViewLayout(view, layoutParams) }
    }

    internal fun FloatingDotOverlay.windowBounds(app: Context, wm: WindowManager): android.graphics.Rect =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(
                    0,
                    0,
                    app.resources.displayMetrics.widthPixels,
                    app.resources.displayMetrics.heightPixels
                )
            }
        }.getOrElse {
            android.graphics.Rect(
                0,
                0,
                app.resources.displayMetrics.widthPixels,
                app.resources.displayMetrics.heightPixels
            )
        }

    internal fun FloatingDotOverlay.clampY(
        layoutParams: WindowManager.LayoutParams,
        bounds: android.graphics.Rect,
        size: Int,
        margin: Int
    ) {
        val minY = (64 * (size / 64f)).toInt()
        val maxY = maxOf(minY, bounds.height() - size - (100 * (size / 64f)).toInt())
        layoutParams.y = layoutParams.y.coerceIn(minY, maxY)
    }

    internal fun FloatingDotOverlay.dockedX(
        edge: FloatingDotOverlay.DockEdge,
        bounds: android.graphics.Rect,
        size: Int,
        peek: Int
    ): Int = when (edge) {
        FloatingDotOverlay.DockEdge.LEFT -> -size + peek
        FloatingDotOverlay.DockEdge.RIGHT -> bounds.width() - peek
    }

    internal fun FloatingDotOverlay.revealedX(
        edge: FloatingDotOverlay.DockEdge,
        bounds: android.graphics.Rect,
        size: Int,
        margin: Int
    ): Int = when (edge) {
        FloatingDotOverlay.DockEdge.LEFT -> margin
        FloatingDotOverlay.DockEdge.RIGHT -> bounds.width() - size - margin
    }

    internal fun FloatingDotOverlay.revealDot(app: Context, wm: WindowManager) {
        val layoutParams = params ?: return
        val density = app.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        val bounds = windowBounds(app, wm)
        edgeRevealed = true
        layoutParams.x = revealedX(
            dockedEdge,
            bounds,
            layoutParams.width,
            margin
        )
        clampY(layoutParams, bounds, layoutParams.width, margin)
        runCatching { wm.updateViewLayout(dotView, layoutParams) }
        FloatingDotSettings.savePosition(
            app,
            layoutParams.x,
            layoutParams.y,
            docked = false
        )
    }

    internal fun FloatingDotOverlay.scheduleAutoDock(app: Context, wm: WindowManager) {
        cancelAutoDock()
        val runnable = Runnable {
            autoDockRunnable = null
            val layoutParams = params ?: return@Runnable
            val density = app.resources.displayMetrics.density
            val peek = (18 * density).toInt()
            val bounds = windowBounds(app, wm)
            edgeRevealed = false
            layoutParams.x = dockedX(
                dockedEdge,
                bounds,
                layoutParams.width,
                peek
            )
            runCatching { wm.updateViewLayout(dotView, layoutParams) }
            FloatingDotSettings.savePosition(
                app,
                layoutParams.x,
                layoutParams.y,
                docked = true
            )
        }
        autoDockRunnable = runnable
        handler.postDelayed(runnable, EDGE_AUTO_DOCK_DELAY_MS)
    }

    internal fun FloatingDotOverlay.cancelAutoDock() {
        autoDockRunnable?.let(handler::removeCallbacks)
        autoDockRunnable = null
    }

    internal fun FloatingDotOverlay.removeView() = synchronized(this) {
        appContext?.let {
            unregisterModeReceiver(it)
            unregisterConfigurationCallbacks(it)
        }
        cancelAutoDock()
        dismissToolsPanel()
        dismissReaderPanel()
        val view = dotView ?: return
        dotView = null
        params = null
        view.animate().cancel()
        runCatching { windowManager?.removeView(view) }
        windowManager = null
        lastWindowHeight = 0
    }
