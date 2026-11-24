package com.coke.nestspace.container.loader

import android.content.Context
import android.os.Build
import android.util.Log
import com.coke.nestspace.container.identity.HostIdentity
import java.io.File
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Native-library support for containerized APKs.
 *
 * Stage B used to pass `null` as DexClassLoader#librarySearchPath, so any app
 * with JNI libraries crashed in Application.onCreate. We now extract the ABI
 * directory that matches the device into the instance data dir (cached) and
 * hand that path to the class loader.
 *
 * ABI compatibility is checked before launch: an arm64-only APK cannot run on
 * an x86_64 emulator, and the user should get a clear error instead of an
 * UnsatisfiedLinkError from deep inside the target Application.
 */
object NativeLibs {

    private const val TAG = "NativeLibs"
    private const val READY = ".ready"

    fun supportedAbi(
        abis: Set<String>,
        preferredAbi: String? = null
    ): String? = chooseAbi(
        abis,
        preferredAbi,
        Build.SUPPORTED_ABIS.toList()
    )

    fun preferredHostAbi(context: Context): String? {
        val nativeLibraryDir =
            (HostIdentity.application?.applicationInfo ?: context.applicationInfo)
                .nativeLibraryDir
                ?: return null
        val installedAbi = File(nativeLibraryDir).name
        return Build.SUPPORTED_ABIS.firstOrNull { abi ->
            abi == installedAbi ||
                abi.substringBefore('-') == installedAbi ||
                (installedAbi == "arm" && abi == "armeabi-v7a")
        }
    }

    internal fun chooseAbi(
        abis: Set<String>,
        preferredAbi: String?,
        supportedAbis: List<String>
    ): String? = preferredAbi
        ?.takeIf { it in abis && it in supportedAbis }
        ?: supportedAbis.firstOrNull { it in abis }

    fun abisOf(apkPaths: List<String>): Set<String> =
        apkPaths.flatMap { ApkInspector.nativeAbis(it) }.toSet()

    /**
     * Some APK AppComponentFactory implementations replace the container's
     * PathClassLoader with their own PathClassLoader subclass without carrying
     * over the library search path. Android exposes addNativePath() for this
     * linker namespace update; invoke it reflectively because it remains a
     * hidden framework API.
     */
    fun addNativeSearchPaths(
        loader: ClassLoader,
        directories: Collection<String>
    ): Boolean {
        val paths = directories
            .filter(String::isNotBlank)
            .distinct()
        if (paths.isEmpty()) return true
        val addedWithApi = runCatching {
            val method = Class.forName("dalvik.system.PathClassLoader")
                .getDeclaredMethod(
                    "addNativePath",
                    Collection::class.java
                )
                .apply { isAccessible = true }
            method.invoke(loader, paths)
        }.onFailure { error ->
            val cause = (error as? InvocationTargetException)
                ?.targetException
                ?: error
            if (cause !is NoSuchMethodException) {
                Log.w(TAG, "PathClassLoader.addNativePath failed", cause)
            }
        }.isSuccess
        if (addedWithApi) return true
        return addNativePathsToDexPathList(loader, paths)
    }

    private fun addNativePathsToDexPathList(
        loader: ClassLoader,
        paths: List<String>
    ): Boolean {
        val directories = paths
            .map(::File)
            .filter(File::isDirectory)
        if (directories.isEmpty()) return false
        return runCatching {
            val pathListField = findField(loader.javaClass, "pathList")
                ?: error("DexPathList field not found")
            val pathList = pathListField.get(loader)
                ?: error("DexPathList is null")
            var changed = false

            findField(pathList.javaClass, "nativeLibraryDirectories")
                ?.let { field ->
                    val current = field.get(pathList)
                    field.set(
                        pathList,
                        mergeDirectories(current, directories)
                    )
                    changed = true
                }

            findField(pathList.javaClass, "nativeLibraryPathElements")
                ?.let { field ->
                    val current = field.get(pathList) as? Array<*>
                    if (current != null) {
                        val componentType = current.javaClass.componentType
                            ?: return@let
                        val additions = directories.mapNotNull { directory ->
                            newNativeLibraryElement(
                                componentType,
                                directory
                            )
                        }
                        if (additions.isNotEmpty()) {
                            val merged = java.lang.reflect.Array.newInstance(
                                componentType,
                                current.size + additions.size
                            ) as Array<Any?>
                            System.arraycopy(
                                current,
                                0,
                                merged,
                                0,
                                current.size
                            )
                            additions.forEachIndexed { index, value ->
                                merged[current.size + index] = value
                            }
                            field.set(pathList, merged)
                            changed = true
                        }
                    }
                }
            if (changed) {
                Log.d(
                    TAG,
                    "native paths appended loader=${loader.javaClass.name} " +
                        "count=${directories.size}"
                )
            }
            changed
        }.onFailure {
            Log.w(
                TAG,
                "append native paths failed loader=${loader.javaClass.name}",
                it
            )
        }.getOrDefault(false)
    }

    private fun mergeDirectories(
        current: Any?,
        additions: List<File>
    ): Any {
        val currentPaths = when (current) {
            is Collection<*> -> current.mapNotNull { value ->
                (value as? File)?.absolutePath
                    ?: value?.toString()
            }
            is Array<*> -> current.mapNotNull { value ->
                (value as? File)?.absolutePath
                    ?: value?.toString()
            }
            else -> emptyList()
        }
        val merged = (currentPaths + additions.map(File::getAbsolutePath))
            .distinct()
            .map(::File)
        return when (current) {
            is Array<*> -> {
                val type = current.javaClass.componentType
                    ?: File::class.java
                val result = java.lang.reflect.Array.newInstance(
                    type,
                    merged.size
                ) as Array<Any?>
                merged.forEachIndexed { index, value ->
                    result[index] = value
                }
                result
            }
            else -> ArrayList(merged)
        }
    }

    private fun newNativeLibraryElement(
        type: Class<*>,
        directory: File
    ): Any? {
        val constructors = type.declaredConstructors.sortedBy {
            it.parameterCount
        }
        for (constructor in constructors) {
            val arguments = arrayOfNulls<Any>(constructor.parameterCount)
            var supported = true
            constructor.parameterTypes.forEachIndexed { index, parameter ->
                arguments[index] = when {
                    parameter == File::class.java -> directory
                    parameter == String::class.java -> directory.absolutePath
                    !parameter.isPrimitive -> null
                    else -> {
                        supported = false
                        null
                    }
                }
            }
            if (!supported) continue
            runCatching {
                constructor.isAccessible = true
                return constructor.newInstance(*arguments)
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current!!.getDeclaredField(name)
                    .apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Extracts native `.so` files under lib/<abi>/ from the base + split APKs
     * into [targetDir].
     * Returns null when the APK has no libs or no ABI matches this device.
     */
    fun extractIfNeeded(
        apkPaths: List<String>,
        abis: Set<String>,
        cacheRoot: File,
        excludedNames: Set<String> = emptySet(),
        preferredAbi: String? = null
    ): File? {
        val abi = supportedAbi(abis, preferredAbi) ?: return null
        return extractAbiIfNeeded(apkPaths, abi, cacheRoot, excludedNames)
    }

    /**
     * Internal ABI-specific entry point that can be unit tested without
     * depending on the host device's supported ABI list.
     */
    internal fun extractAbiIfNeeded(
        apkPaths: List<String>,
        abi: String,
        cacheRoot: File,
        excludedNames: Set<String> = emptySet()
    ): File? {
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) return null
        val expectedNames = expectedLibraryNames(
            apkPaths,
            abi,
            excludedNames
        )
        if (expectedNames.isEmpty()) return null
        val fingerprint = fingerprint(apkPaths, abi, excludedNames)
        val targetDir = File(cacheRoot, fingerprint)
        val marker = File(targetDir, READY)
        if (cacheReady(targetDir, marker, expectedNames)) {
            lockDown(targetDir)
            return targetDir
        }

        // Build in a process-unique directory. Two instances can cold-start at
        // the same time without observing a partially extracted cache.
        val tempDir = File(
            cacheRoot,
            ".build-$fingerprint-${UUID.randomUUID()}"
        )
        deleteTree(tempDir)
        if (!tempDir.mkdirs()) return null

        val extractedNames = linkedSetOf<String>()
        try {
            apkPaths.forEach { apkPath ->
                runCatching {
                    ZipFile(File(apkPath)).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            if (!entry.name.startsWith("lib/$abi/") ||
                                !entry.name.endsWith(".so")
                            ) {
                                continue
                            }
                            // Flatten to one directory; the loader searches by
                            // file name. Later splits intentionally win if a
                            // file name occurs in more than one APK.
                            val name = entry.name.substringAfterLast('/')
                            if (name.isBlank() || name in excludedNames) continue
                            val out = File(tempDir, name)
                            zip.getInputStream(entry).use { input ->
                                out.outputStream().use { output -> input.copyTo(output) }
                            }
                            out.setReadable(true, false)
                            out.setExecutable(true, false)
                            // Android 16+ refuses to dlopen writable files.
                            out.setWritable(false, false)
                            extractedNames += name
                        }
                    }
                }.onFailure { Log.w(TAG, "read native libs from $apkPath failed", it) }
            }
            if (!extractedNames.containsAll(expectedNames)) {
                Log.w(
                    TAG,
                    "native cache incomplete extracted=${extractedNames.size} " +
                        "expected=${expectedNames.size}"
                )
                deleteTree(tempDir)
                return null
            }
            lockDown(tempDir)
            marker(tempDir).writeText(
                expectedNames.sorted().joinToString("\n")
            )

            // A crash can leave a cache directory without its ready marker.
            // Re-check first in case another instance completed the entry.
            if (targetDir.exists()) {
                if (cacheReady(targetDir, marker(targetDir), expectedNames)) {
                    deleteTree(tempDir)
                    lockDown(targetDir)
                    return targetDir
                }
                deleteTree(targetDir)
            }
            if (tempDir.renameTo(targetDir)) {
                lockDown(targetDir)
                return targetDir
            }

            // Another process may have completed the same cache entry first.
            deleteTree(tempDir)
            if (cacheReady(targetDir, marker(targetDir), expectedNames)) {
                lockDown(targetDir)
                return targetDir
            }
            return null
        } catch (t: Throwable) {
            deleteTree(tempDir)
            throw t
        }
    }

    private fun expectedLibraryNames(
        apkPaths: List<String>,
        abi: String,
        excludedNames: Set<String>
    ): Set<String> = buildSet {
        apkPaths.forEach { apkPath ->
            runCatching {
                ZipFile(File(apkPath)).use { zip ->
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith("lib/$abi/") &&
                                entry.name.endsWith(".so")
                        }
                        .mapTo(this) { it.name.substringAfterLast('/') }
                }
            }.onFailure {
                Log.w(TAG, "read native library manifest from $apkPath failed", it)
            }
        }
        removeAll(excludedNames)
        remove("")
    }

    private fun cacheReady(
        dir: File,
        marker: File,
        expectedNames: Set<String>
    ): Boolean {
        if (!marker.isFile) return false
        val recorded = runCatching {
            marker.readLines()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        }.getOrDefault(emptySet())
        return recorded == expectedNames &&
            expectedNames.all { name -> File(dir, name).isFile }
    }

    private fun fingerprint(
        apkPaths: List<String>,
        abi: String,
        excludedNames: Set<String>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(abi.toByteArray())
        digest.update(0)
        excludedNames.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update(0)
        }
        apkPaths.forEach { path ->
            val file = File(path)
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(file.length().toString().toByteArray())
            digest.update(0)
            digest.update(file.lastModified().toString().toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun marker(dir: File): File = File(dir, READY)

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteTree)
        }
        file.setWritable(true)
        file.delete()
    }

    private fun lockDown(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".so")) {
                runCatching {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                    file.setWritable(false, false)
                }
            }
        }
    }
}
