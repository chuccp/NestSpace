package com.coke.nestspace.container.application

import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Discovers native libraries that an application may extract or unpack while
 * starting. The scanner is intentionally based on ELF library entries and
 * runtime directories, not on a particular packer's names or fingerprints.
 */
internal data class RuntimeNativeLibraries(
    val root: File,
    val directories: List<File>,
    val files: List<File>
) {
    companion object {
        private const val TAG = "RuntimeNativeLibraries"
        private const val MAX_LIBRARY_BYTES = 64L * 1024L * 1024L
        private const val MAX_SCAN_DEPTH = 5

        fun prepare(apkPaths: List<String>, dataRoot: File): RuntimeNativeLibraries {
            val apk = apkPaths.firstOrNull()?.let(::File)
            val version = listOf(
                apk?.length() ?: 0L,
                apk?.lastModified() ?: 0L
            ).joinToString("-")
            val root = File(
                dataRoot,
                ".nestspace/runtime-libs/$version"
            ).apply { mkdirs() }
            apkPaths.forEach { path ->
                extractLibraries(File(path), root)
            }
            val files = discoverLibraries(dataRoot)
            val directories = files
                .mapTo(linkedSetOf()) { it.parentFile }
                .filterNotNull()
                .sortedBy { it.absolutePath }
            return RuntimeNativeLibraries(root, directories, files)
        }

        private fun extractLibraries(apk: File, outputRoot: File) {
            if (!apk.isFile) return
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.size in 1..MAX_LIBRARY_BYTES &&
                                !entry.name.startsWith("lib/") &&
                                entry.name.endsWith(".so", ignoreCase = true)
                        }
                        .forEach { entry ->
                            val name = entry.name.substringAfterLast('/')
                            if (name.isBlank()) return@forEach
                            val output = File(outputRoot, name)
                            if (output.isFile && output.length() == entry.size) {
                                return@forEach
                            }
                            val temporary = File(
                                outputRoot,
                                ".$name-${System.nanoTime()}.tmp"
                            )
                            runCatching {
                                zip.getInputStream(entry).use { input ->
                                    temporary.outputStream().use { stream ->
                                        input.copyTo(stream)
                                    }
                                }
                                if (!temporary.renameTo(output)) {
                                    temporary.copyTo(output, overwrite = true)
                                    temporary.delete()
                                }
                            }.onFailure {
                                temporary.delete()
                                Log.w(
                                    TAG,
                                    "extract runtime library failed entry=${entry.name}",
                                    it
                                )
                            }
                        }
                }
            }.onFailure {
                Log.w(TAG, "scan APK runtime libraries failed: ${apk.absolutePath}", it)
            }
        }

        private fun discoverLibraries(dataRoot: File): List<File> =
            dataRoot.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .onEnter { directory ->
                    directory.name != "code_cache" &&
                        directory.name != "cache"
                }
                .filter { file ->
                    file.isFile &&
                        file.length() > 0L &&
                        file.name.endsWith(".so", ignoreCase = true)
                }
                .distinctBy { it.canonicalPath }
                .sortedBy { it.absolutePath }
                .toList()
    }
}
