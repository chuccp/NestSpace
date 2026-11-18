package com.coke.nestspace.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.task.OperationTaskRegistry

internal object AssistantTaskNotifications {
    private const val CHANNEL_ID = "assistant_task_results"

    fun notifyTerminal(
        context: Context,
        task: OperationTaskRegistry.Snapshot
    ) {
        if (!task.terminal || AssistantBridge.isScreenAttached()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppLanguage.tr("任务完成提醒", "Task completion alerts"),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val title = when (task.state) {
            OperationTaskRegistry.State.SUCCEEDED ->
                AppLanguage.tr("有任务完成", "Task completed")
            OperationTaskRegistry.State.CANCELLED ->
                AppLanguage.tr("任务已取消", "Task cancelled")
            else -> AppLanguage.tr("任务没有完成", "Task did not finish")
        }
        val content = task.result?.take(180)
            ?: task.error?.take(180)
            ?: task.title.take(180)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
            .putExtra(MainActivity.EXTRA_TASK_ID, task.id)
        val notificationId = task.id.hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(notificationId, notification)
    }
}
