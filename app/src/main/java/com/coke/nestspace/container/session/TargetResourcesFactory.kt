package com.coke.nestspace.container.session

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import java.io.File

internal object TargetResourcesFactory {
    fun create(
        host: Context,
        paths: List<String>,
        classLoader: ClassLoader
    ): Resources {
        Log.d(
            TAG,
            "create target resources paths=${
                paths.joinToString(File.pathSeparator)
            }"
        )
        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)

        paths.forEachIndexed { index, path ->
            val cookie = addAssetPath.invoke(assetManager, path) as Int
            if (cookie == 0) {
                error(
                    if (index == 0) {
                        "AssetManager.addAssetPath failed: $path"
                    } else {
                        "split addAssetPath failed: $path"
                    }
                )
            }
            Log.d(
                TAG,
                "target AssetManager.addAssetPath index=$index " +
                    "cookie=$cookie path=$path"
            )
        }

        val base = Resources(
            assetManager,
            host.resources.displayMetrics,
            host.resources.configuration
        )
        bindClassLoader(base, classLoader)
        return base
    }

    private fun bindClassLoader(
        resources: Resources,
        classLoader: ClassLoader
    ) {
        fun patch(owner: Any, type: Class<*>) {
            runCatching {
                type.getDeclaredField("mClassLoader").apply {
                    isAccessible = true
                    set(owner, classLoader)
                }
            }.onFailure {
                Log.w(
                    TAG,
                    "bind ${type.simpleName}.mClassLoader failed",
                    it
                )
            }
        }
        patch(resources, Resources::class.java)
        runCatching {
            val implField = Resources::class.java
                .getDeclaredField("mResourcesImpl")
                .apply { isAccessible = true }
            implField.get(resources)?.let { impl ->
                patch(impl, impl.javaClass)
            }
        }.onFailure {
            Log.w(TAG, "read ResourcesImpl for classloader binding failed", it)
        }
    }

    private const val TAG = "ContainerSession"
}
