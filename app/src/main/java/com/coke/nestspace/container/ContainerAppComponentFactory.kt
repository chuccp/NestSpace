package com.coke.nestspace.container

import android.app.Activity
import android.app.Application
import android.app.AppComponentFactory
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.coke.nestspace.container.identity.HostIdentity

/**
 * Keeps host framework components loadable after a slot adopts a target
 * ClassLoader.
 *
 * Android uses the process-wide LoadedApk component factory when AMS creates a
 * service or receiver. Internal slot services belong to the host package but
 * run in the same process as the target, so they must be loaded by the host
 * ClassLoader. Target components continue through the existing factory and the
 * target ClassLoader.
 */
internal class ContainerAppComponentFactory(
    private val delegate: AppComponentFactory?
) : AppComponentFactory() {

    override fun instantiateClassLoader(
        cl: ClassLoader,
        appInfo: ApplicationInfo
    ): ClassLoader = delegate?.instantiateClassLoader(cl, appInfo)
        ?: super.instantiateClassLoader(cl, appInfo)

    override fun instantiateApplication(
        cl: ClassLoader,
        className: String
    ): Application = if (isHostComponent(className)) {
        hostInstance(className, Application::class.java)
    } else {
        delegate?.instantiateApplication(cl, className)
            ?: super.instantiateApplication(cl, className)
    }

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Activity = if (isHostComponent(className)) {
        hostInstance(className, Activity::class.java)
    } else {
        delegate?.instantiateActivity(cl, className, intent)
            ?: super.instantiateActivity(cl, className, intent)
    }

    override fun instantiateReceiver(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): BroadcastReceiver = if (isHostComponent(className)) {
        hostInstance(className, BroadcastReceiver::class.java)
    } else {
        delegate?.instantiateReceiver(cl, className, intent)
            ?: super.instantiateReceiver(cl, className, intent)
    }

    override fun instantiateService(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Service = if (isHostComponent(className)) {
        hostInstance(className, Service::class.java)
    } else {
        delegate?.instantiateService(cl, className, intent)
            ?: super.instantiateService(cl, className, intent)
    }

    override fun instantiateProvider(
        cl: ClassLoader,
        className: String
    ): ContentProvider = if (isHostComponent(className)) {
        hostInstance(className, ContentProvider::class.java)
    } else {
        delegate?.instantiateProvider(cl, className)
            ?: super.instantiateProvider(cl, className)
    }

    private fun isHostComponent(className: String): Boolean =
        className == HostIdentity.packageName ||
            className.startsWith("${HostIdentity.packageName}.")

    private fun <T> hostInstance(
        className: String,
        baseType: Class<T>
    ): T = hostClassLoader
        .loadClass(className)
        .asSubclass(baseType)
        .getDeclaredConstructor()
        .newInstance()

    private companion object {
        val hostClassLoader: ClassLoader =
            ContainerAppComponentFactory::class.java.classLoader
                ?: ClassLoader.getSystemClassLoader()
    }
}
