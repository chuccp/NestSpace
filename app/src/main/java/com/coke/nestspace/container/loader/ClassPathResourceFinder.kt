package com.coke.nestspace.container.loader

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import java.util.Collections
import java.util.zip.ZipFile

/**
 * Exposes classpath resources from target APK/JAR files.
 *
 * Android's BaseDexClassLoader variants find DEX entries but do not provide
 * the java.lang.ClassLoader resource contract used by libraries such as
 * OkHttp. Those libraries call Class.getResourceAsStream(...) for packaged
 * data and can block forever when the target classpath silently misses it.
 */
internal class ClassPathResourceFinder(dexPath: String) {
    private val roots = dexPath
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter(String::isNotBlank)
        .map(::File)
        .distinctBy(File::getAbsolutePath)
        .toList()

    fun findResource(name: String): URL? {
        val resource = normalize(name) ?: return null
        roots.forEach { root ->
            val url = when {
                root.isDirectory -> directoryResource(root, resource)
                root.isFile -> archiveResource(root, resource)
                else -> null
            }
            if (url != null) return url
        }
        return null
    }

    fun findResources(name: String): List<URL> {
        val resource = normalize(name) ?: return emptyList()
        return roots.mapNotNull { root ->
            when {
                root.isDirectory -> directoryResource(root, resource)
                root.isFile -> archiveResource(root, resource)
                else -> null
            }
        }
    }

    fun openResource(name: String): InputStream? {
        val resource = normalize(name) ?: return null
        roots.forEach { root ->
            if (root.isDirectory) {
                val file = directoryFile(root, resource) ?: return@forEach
                if (file.isFile) return FileInputStream(file)
            } else if (root.isFile) {
                val zip = runCatching { ZipFile(root) }.getOrNull()
                    ?: return@forEach
                val entry = zip.getEntry(resource)
                if (entry == null || entry.isDirectory) {
                    zip.close()
                    return@forEach
                }
                val input = zip.getInputStream(entry)
                return object : FilterInputStream(input) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            zip.close()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun archiveResource(root: File, resource: String): URL? {
        val url = runCatching {
            ZipFile(root).use { zip ->
                val entry = zip.getEntry(resource) ?: return@use null
                if (entry.isDirectory) return@use null
                URL("jar:${root.toURI().toURL()}!/${entry.name}")
            }
        }.getOrNull()
        return url
    }

    private fun directoryResource(root: File, resource: String): URL? {
        val file = directoryFile(root, resource) ?: return null
        return file.takeIf(File::isFile)?.toURI()?.toURL()
    }

    private fun directoryFile(root: File, resource: String): File? {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, resource).canonicalFile
        return file.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun normalize(name: String): String? {
        val resource = name.trimStart('/')
        if (resource.isEmpty()) return null
        val segments = resource.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }
}

internal fun resourceEnumeration(values: List<URL>): java.util.Enumeration<URL> =
    Collections.enumeration(values)
