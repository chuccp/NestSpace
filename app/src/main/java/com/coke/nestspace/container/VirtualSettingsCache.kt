package com.coke.nestspace.container

import android.provider.Settings
import android.util.Log
import java.lang.reflect.Field

/**
 * Settings.Secure caches values in a process-wide static NameValueCache.
 * NestSpace can read the host Android ID before a target instance starts,
 * which would otherwise pin every virtual app to the host SSAID even after
 * its target ContentResolver is installed.
 */
internal object VirtualSettingsCache {
    private const val TAG = "VirtualSettingsCache"

    fun seedAndroidId(context: ContainerContext): Boolean =
        runCatching {
            settingsCaches().forEach(::clearCache)
            // SQLite reads this value through Settings.Global before target
            // providers finish attaching. Warm each namespace through the
            // virtual resolver so their static NameValueCache entries retain
            // a target-attributed provider instead of rebinding directly.
            Settings.Global.getString(
                context.targetContentResolver,
                "compatibility_wal_supported"
            )
            Settings.System.getString(
                context.targetContentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val value = Settings.Secure.getString(
                context.targetContentResolver,
                Settings.Secure.ANDROID_ID
            )
            Log.d(TAG, "target androidId=$value package=${context.packageName}")
            !value.isNullOrBlank()
        }.onFailure {
            Log.w(TAG, "settings cache seed failed", it)
        }.getOrDefault(false)

    private fun settingsCaches(): List<Any> = listOf(
        Settings.Global::class.java,
        Settings.Secure::class.java,
        Settings.System::class.java
    ).mapNotNull { type ->
        runCatching {
            val field = type.getDeclaredField("sNameValueCache")
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    private fun clearCache(cache: Any) {
        declaredField(cache.javaClass, "mValues").get(cache)
            ?.let { (it as? MutableMap<*, *>)?.clear() }
        declaredField(cache.javaClass, "mGenerationTrackers").get(cache)
            ?.let { (it as? MutableMap<*, *>)?.clear() }

        val holder = declaredField(cache.javaClass, "mProviderHolder").get(cache)
            ?: return
        declaredField(holder.javaClass, "mContentProvider").set(holder, null)
    }

    private fun declaredField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Field $name not found on ${type.name}")
    }
}
