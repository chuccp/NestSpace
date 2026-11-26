package com.coke.nestspace.container

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle

    internal fun ContainerContext.registerContainerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?
    ): Intent? {
        val manager = broadcastManager
        val early = earlyReceiverRegistry
        return if (manager != null) {
            manager.register(receiver, filter, null, null, 0)
        } else if (early != null && receiver != null) {
            early.register(receiver, filter, null, null, 0)
            runCatching { superRegisterReceiver(null, filter) }.getOrNull()
        } else {
            superRegisterReceiver(receiver, filter)
        }
    }

    internal fun ContainerContext.registerContainerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        flags: Int
    ): Intent? {
        val manager = broadcastManager
        val early = earlyReceiverRegistry
        return if (manager != null) {
            manager.register(receiver, filter, null, null, flags)
        } else if (early != null && receiver != null) {
            early.register(receiver, filter, null, null, flags)
            runCatching { superRegisterReceiver(null, filter) }.getOrNull()
        } else {
            superRegisterReceiver(receiver, filter, flags)
        }
    }

    internal fun ContainerContext.registerContainerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        val manager = broadcastManager
        val early = earlyReceiverRegistry
        return if (manager != null) {
            manager.register(receiver, filter, broadcastPermission, scheduler, 0)
        } else if (early != null && receiver != null) {
            early.register(receiver, filter, broadcastPermission, scheduler, 0)
            runCatching { superRegisterReceiver(null, filter) }.getOrNull()
        } else {
            superRegisterReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }

    internal fun ContainerContext.registerContainerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
        flags: Int
    ): Intent? {
        val manager = broadcastManager
        val early = earlyReceiverRegistry
        return if (manager != null) {
            manager.register(receiver, filter, broadcastPermission, scheduler, flags)
        } else if (early != null && receiver != null) {
            early.register(receiver, filter, broadcastPermission, scheduler, flags)
            runCatching { superRegisterReceiver(null, filter) }.getOrNull()
        } else {
            superRegisterReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }
    }

    internal fun ContainerContext.unregisterContainerReceiver(receiver: BroadcastReceiver) {
        if (broadcastManager?.unregister(receiver) == true) return
        if (earlyReceiverRegistry?.unregister(receiver) == true) return
        superUnregisterReceiver(receiver)
    }

    internal fun ContainerContext.sendContainerBroadcast(intent: Intent) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, false)) return
        superSendBroadcast(forwardToHost(intent))
    }

    internal fun ContainerContext.sendContainerBroadcast(intent: Intent, receiverPermission: String?) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        superSendBroadcast(forwardToHost(intent), receiverPermission)
    }

    internal fun ContainerContext.sendContainerBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        superSendBroadcast(forwardToHost(intent), receiverPermission, options)
    }

    internal fun ContainerContext.sendContainerBroadcastAsUser(intent: Intent, user: UserHandle) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, false)) return
        superSendBroadcastAsUser(forwardToHost(intent), user)
    }

    internal fun ContainerContext.sendContainerBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        receiverPermission: String?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        superSendBroadcastAsUser(forwardToHost(intent), user, receiverPermission)
    }
