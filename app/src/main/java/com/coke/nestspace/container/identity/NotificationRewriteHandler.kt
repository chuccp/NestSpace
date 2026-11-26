package com.coke.nestspace.container.identity

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.coke.nestspace.container.notify.ContainerNotificationBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

internal class NotificationRewriteHandler(
    private val delegate: Any,
    private val owner: SystemServiceIdentity
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        if (method.name.startsWith("areNotificationsEnabled")) {
            val permission = owner.virtualPermissions?.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            )
            return permission == null ||
                permission == PackageManager.PERMISSION_GRANTED
        }
        if (isNotificationReadMethod(method.name)) {
            val env = notificationEnv(owner)
                ?: return owner.invokeDelegate(delegate, method, args)
            val callArgs = ContainerNotificationBridge.rewriteCall(
                method.name,
                args,
                env
            )
            val result = owner.invokeDelegate(delegate, method, callArgs)
            return ContainerNotificationBridge.rewriteResult(
                method.name,
                result,
                env
            )
        }
        val env = notificationEnv(owner)
            ?: return owner.invokeDelegate(delegate, method, args)
        val callArgs = ContainerNotificationBridge.rewriteCall(
            method.name,
            args,
            env
        )
        val result = owner.invokeDelegate(delegate, method, callArgs)
        return ContainerNotificationBridge.rewriteResult(
            method.name,
            result,
            env
        )
    }
}

private fun isNotificationReadMethod(name: String): Boolean =
    name.startsWith("areNotificationsEnabled") ||
        name.startsWith("getImportance") ||
        name == "getNotificationChannel" ||
        name == "getNotificationChannels" ||
        name == "getNotificationChannelsForPackage" ||
        name == "getEnabledNotificationListeners"

private fun notificationEnv(
    owner: SystemServiceIdentity
): ContainerNotificationBridge.Env? {
    val context = owner.hostContext ?: owner.systemContext() ?: return null
    val target = owner.targetPackage
    if (target.isEmpty()) return null
    return ContainerNotificationBridge.Env(
        targetPackage = target,
        instanceId = owner.targetInstance,
        hostPackage = owner.hostPackage,
        resources = owner.targetResources,
        context = context,
        launchPendingIntent = {
            launchPendingIntent(owner, context)
        }
    )
}

private fun launchPendingIntent(
    owner: SystemServiceIdentity,
    context: Context
): PendingIntent? = runCatching {
    val intent = Intent()
        .setClassName(owner.hostPackage, owner.CONTAINER_LAUNCH_PROXY)
        .putExtra("space_id", owner.targetSpace)
        .putExtra("package", owner.targetPackage)
        .putExtra("instance", owner.targetInstance)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    PendingIntent.getActivity(
        context,
        (owner.targetPackage + '@' + owner.targetInstance).hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}.onFailure {
    Log.w(owner.TAG, "notification launch PendingIntent failed", it)
}.getOrNull()
