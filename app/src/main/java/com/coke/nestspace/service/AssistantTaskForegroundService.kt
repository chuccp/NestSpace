package com.coke.nestspace.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.AppGraph
import com.coke.nestspace.agent.AssistantTaskCoordinator
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.task.OperationTaskRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the NestSpace process alive while formal assistant worker tasks are
 * queued or running. Direct one-shot assistant work is intentionally excluded.
 */
class AssistantTaskForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.coke.nestspace.ASSISTANT_TASK_START"
        private const val ACTION_PAUSE = "com.coke.nestspace.ASSISTANT_TASK_PAUSE"
        private const val ACTION_RESUME = "com.coke.nestspace.ASSISTANT_TASK_RESUME"
        private const val ACTION_CANCEL = "com.coke.nestspace.ASSISTANT_TASK_CANCEL"
        private const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "assistant_tasks"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "AssistantTaskService"

        fun ensureStarted(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AssistantTaskForegroundService::class.java)
                        .setAction(ACTION_START)
                )
            }.onFailure {
                Log.w(TAG, "assistant task foreground start failed", it)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
        scope.launch {
            OperationTaskRegistry.tasksFlow.collect { tasks ->
                val active = tasks.filter {
                    it.kind == "assistant_worker" && !it.terminal
                }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
            ?.takeIf(String::isNotBlank)
        if (taskId != null) {
            when (intent.action) {
                ACTION_PAUSE -> AssistantTaskCoordinator.pause(taskId)
                ACTION_RESUME -> AssistantTaskCoordinator.resume(
                    this,
                    AppGraph.registry(this),
                    taskId
                )
                ACTION_CANCEL -> AssistantTaskCoordinator.cancel(taskId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppLanguage.tr("助手任务", "Assistant tasks"),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(
        tasks: List<OperationTaskRegistry.Snapshot>
    ): android.app.Notification {
        val active = tasks.firstOrNull {
            it.state != OperationTaskRegistry.State.QUEUED &&
                it.state != OperationTaskRegistry.State.CANCELLING
        } ?: tasks.firstOrNull()
        val queued = tasks.count { it.state == OperationTaskRegistry.State.QUEUED }
        val content = when {
            active == null -> AppLanguage.tr("正在准备任务…", "Preparing tasks…")
            queued > 0 -> AppLanguage.tr(
                "${active.title.take(80)} · 等待 $queued",
                "${active.title.take(80)} · $queued waiting"
            )
            else -> active.title.take(100)
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(AppLanguage.tr("NestSpace 助手任务", "NestSpace assistant tasks"))
            .setContentText(content)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (active != null &&
            active.state != OperationTaskRegistry.State.CANCELLING
        ) {
            val action = if (active.state == OperationTaskRegistry.State.PAUSED) {
                Triple(
                    ACTION_RESUME,
                    AppLanguage.tr("继续", "Resume"),
                    NOTIFICATION_ID + 1
                )
            } else {
                Triple(
                    ACTION_PAUSE,
                    AppLanguage.tr("暂停", "Pause"),
                    NOTIFICATION_ID + 1
                )
            }
            builder.addAction(
                0,
                action.second,
                servicePendingIntent(action.first, active.id, action.third)
            )
            builder.addAction(
                0,
                AppLanguage.tr("取消", "Cancel"),
                servicePendingIntent(ACTION_CANCEL, active.id, NOTIFICATION_ID + 2)
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(
        action: String,
        taskId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(this, AssistantTaskForegroundService::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
