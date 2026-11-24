package com.coke.nestspace.container.activity

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.coke.nestspace.container.loader.ApkClassIndex
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Repairs a player UI whose service-to-activity progress flow stopped.
 *
 * The bridge is capability based: it activates only when the target exposes a
 * play-service clock and the standard current/total/seek-bar view names. It
 * polls the service that remains authoritative while audio is playing and
 * mirrors its position into the visible player controls.
 */
internal class TargetPlaybackUiBridge private constructor(
    activity: Activity,
    private val currentPosition: Method,
    private val duration: Method,
    private val currentTimeViews: List<TextView>,
    private val totalTimeViews: List<TextView>,
    private val seekBars: List<SeekBar>
) {
    private val activityRef = WeakReference(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1
    private var lastLogAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val activity = activityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                close()
                return
            }
            updatePosition()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        activityRef.clear()
    }

    private fun updatePosition() {
        runCatching {
            val position = currentPosition.invoke(null) as? Int ?: return@runCatching
            val total = duration.invoke(null) as? Int ?: return@runCatching
            if (total <= 0) return@runCatching

            val safePosition = position.coerceIn(0, total)
            val currentText = formatPosition(safePosition)
            val totalText = formatPosition(total)
            currentTimeViews.forEach { view ->
                if (view.text?.toString() != currentText) view.text = currentText
            }
            totalTimeViews.forEach { view ->
                if (view.text?.toString() != totalText) view.text = totalText
            }
            seekBars.forEach { seekBar ->
                if (seekBar.isPressed) return@forEach
                if (seekBar.max != total) seekBar.max = total
                if (seekBar.progress != safePosition) seekBar.progress = safePosition
            }

            if (safePosition == lastPosition) return@runCatching
            lastPosition = safePosition
            val now = SystemClock.elapsedRealtime()
            if (now - lastLogAt >= 10_000L) {
                lastLogAt = now
                Log.d(TAG, "synced playback UI position=$safePosition duration=$total")
            }
        }.onFailure {
            Log.d(TAG, "playback clock unavailable: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "PlaybackUiBridge"
        private const val UPDATE_INTERVAL_MS = 500L

        fun start(
            activity: Activity,
            loader: ClassLoader,
            apkPaths: List<String>
        ): TargetPlaybackUiBridge? {
            val views = findPlayerViews(activity.window?.decorView)
            if (views.currentTime.isEmpty() ||
                views.totalTime.isEmpty() ||
                views.seekBars.isEmpty()
            ) {
                return null
            }
            val clock = findPlaybackClock(loader, apkPaths) ?: return null
            return TargetPlaybackUiBridge(
                activity,
                clock.currentPosition,
                clock.duration,
                views.currentTime,
                views.totalTime,
                views.seekBars
            ).also {
                Log.d(
                    TAG,
                    "attached clock=${clock.className} current=${views.currentTime.size} " +
                        "total=${views.totalTime.size} bars=${views.seekBars.size}"
                )
                it.start()
            }
        }

        internal fun formatPosition(milliseconds: Int): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1_000
            val hours = seconds / 3_600
            val minutes = seconds % 3_600 / 60
            val remainingSeconds = seconds % 60
            return if (hours > 0) {
                String.format(
                    Locale.ROOT,
                    "%d:%02d:%02d",
                    hours,
                    minutes,
                    remainingSeconds
                )
            } else {
                String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds)
            }
        }

        private fun findPlaybackClock(
            loader: ClassLoader,
            apkPaths: List<String>
        ): PlaybackClock? {
            return ApkClassIndex.classNames(apkPaths)
                .asSequence()
                .filter {
                    val simpleName = it.substringAfterLast('.')
                    simpleName.endsWith("PlayService") ||
                        simpleName.endsWith("PlayerService")
                }
                .mapNotNull { className ->
                    runCatching {
                        val type = loader.loadClass(className)
                        val position = type.getMethod("getCurrentPosition")
                        val duration = type.getMethod("getSongRealDuration")
                        if (!Modifier.isStatic(position.modifiers) ||
                            !Modifier.isStatic(duration.modifiers) ||
                            position.returnType != Int::class.javaPrimitiveType ||
                            duration.returnType != Int::class.javaPrimitiveType
                        ) {
                            return@runCatching null
                        }
                        PlaybackClock(className, position, duration)
                    }.getOrNull()
                }
                .firstOrNull()
        }

        private fun findPlayerViews(root: View?): PlayerViews {
            val result = PlayerViews()
            fun visit(view: View?) {
                if (view == null) return
                val entry = runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrNull()
                when (entry) {
                    "currentTime" -> (view as? TextView)?.let(result.currentTime::add)
                    "totalTime" -> (view as? TextView)?.let(result.totalTime::add)
                    "playSeekBar" -> (view as? SeekBar)?.let(result.seekBars::add)
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index))
                    }
                }
            }
            visit(root)
            return result
        }
    }

    private data class PlaybackClock(
        val className: String,
        val currentPosition: Method,
        val duration: Method
    )

    private data class PlayerViews(
        val currentTime: MutableList<TextView> = mutableListOf(),
        val totalTime: MutableList<TextView> = mutableListOf(),
        val seekBars: MutableList<SeekBar> = mutableListOf()
    )
}
