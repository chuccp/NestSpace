package com.coke.nestspace.container.loader

import android.os.Build
import android.util.Log
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Array as ReflectionArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.zip.Adler32

/**
 * Registers decrypted in-memory DEX with the target Activity ClassLoader.
 *
 * Some Android runtimes execute generated DEX outside the framework-facing
 * ClassLoader. Framework entry points must still see the same DEX elements as
 * a normally installed application.
 */
internal object ProcessDexBridge {
    private const val TAG = "ProcessDexBridge"
    private const val CHUNK_SIZE = 1024 * 1024
    private const val MAX_DEX_SIZE = 256L * 1024 * 1024
    private const val GENERATION_FILE = "generation.txt"
    private val registeredDexByLoader =
        WeakHashMap<ClassLoader, Set<String>>()

    fun cachedDexFiles(dataRoot: File): List<File> =
        cachedDex(cacheDirectory(dataRoot))
            .sortedWith(
                compareByDescending<File>(File::length)
                    .thenBy(File::getName)
            )

    /**
     * Finds cached runtime DEX that define one of [classNames]. This is used
     * only to bootstrap a wrapper Application while preserving the exact
     * framework class-loader shape; the complete runtime DEX set is appended
     * after attach.
     */
    fun cachedDexFilesContaining(
        dataRoot: File,
        classNames: Collection<String>
    ): List<File> {
        val requested = classNames.filter(String::isNotBlank).toSet()
        if (requested.isEmpty()) return emptyList()
        return cachedDexFiles(dataRoot).filter { file ->
            runCatching {
                val dex = DexFile(file.absolutePath)
                try {
                    val entries = dex.entries()
                    while (entries.hasMoreElements()) {
                        if (entries.nextElement() in requested) return@runCatching true
                    }
                    false
                } finally {
                    dex.close()
                }
            }.getOrDefault(false)
        }
    }

    fun isBoundTo(loader: ClassLoader, dataRoot: File): Boolean =
        loader.toString().contains(cacheDirectory(dataRoot).absolutePath)

    fun bindTargetDex(
        targetLoader: ClassLoader,
        targetPackage: String,
        entryApplication: String?,
        launcherActivity: String?,
        dataRoot: File
    ): Int {
        // Android runtimes may register additional DEX after the Application
        // has been attached. Always rescan, then append only DEX that is not
        // already represented by the target class path.
        val dexFiles = capture(dataRoot)
        if (dexFiles.isEmpty()) {
            Log.d(TAG, "no process DEX available for $targetPackage")
            return 0
        }
        val existingClassPath = targetLoader.toString()
        val alreadyRegistered = synchronized(registeredDexByLoader) {
            registeredDexByLoader[targetLoader].orEmpty()
        }
        val additions = dexFiles.filterNot { dex ->
            existingClassPath.contains(dex.absolutePath) ||
                dex.name in alreadyRegistered
        }
        val allNames = dexFiles.map(File::getName).toSet()
        if (additions.isEmpty()) {
            synchronized(registeredDexByLoader) {
                registeredDexByLoader[targetLoader] = allNames
            }
            Log.d(
                TAG,
                "process DEX already complete count=${dexFiles.size} " +
                    "application=$entryApplication launcher=$launcherActivity"
            )
            return 0
        }

        val buffers = additions.map { ByteBuffer.wrap(it.readBytes()) }
        val source: ClassLoader = if (Build.VERSION.SDK_INT >= 26) {
            InMemoryDexClassLoader(
                buffers.toTypedArray(),
                targetLoader
            )
        } else {
            DexClassLoader(
                dexFiles.joinToString(File.pathSeparator) { it.absolutePath },
                File(dataRoot, "code_cache").absolutePath,
                null,
                targetLoader
            )
        }
        mergeElements(source, targetLoader)
        synchronized(registeredDexByLoader) {
            registeredDexByLoader[targetLoader] = allNames
        }
        Log.d(
            TAG,
            "registered in-memory DEX added=${additions.size} " +
                "total=${dexFiles.size} bytes=${additions.sumOf(File::length)}"
        )
        return additions.size
    }

    private fun capture(dataRoot: File): List<File> {
        val output = cacheDirectory(dataRoot).apply { mkdirs() }
        val result = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        val nativeFiles = NativeEnvironmentBridge
            .captureProcessDex(dataRoot.absolutePath)
            .map(::File)
            .filter { it.isFile && it.length() > 0L }
        if (nativeFiles.isNotEmpty()) {
            val previous = cachedDex(output)
            val complete = mergeFiles(previous, nativeFiles)
            Log.d(
                TAG,
                "captured process DEX through native reader " +
                    "new=${nativeFiles.size} total=${complete.size}"
            )
            writeGeneration(output, complete)
            return complete
        }
        val memory = runCatching {
            RandomAccessFile("/proc/self/mem", "r")
        }.onFailure {
            Log.w(TAG, "open process memory failed", it)
        }.getOrNull() ?: return cachedDex(output)
        memory.use { source ->
            File("/proc/self/maps").forEachLine { line ->
                val mapping = parseMapping(line, dataRoot) ?: return@forEachLine
                if (!mapping.readable || mapping.size < 0x70 ||
                    mapping.size > MAX_DEX_SIZE ||
                    !mapping.candidate
                ) {
                    return@forEachLine
                }
                scan(
                    source,
                    mapping,
                    output,
                    seen,
                    result
                )
            }
        }
        if (result.isNotEmpty()) {
            val complete = mergeFiles(cachedDex(output), result)
            writeGeneration(output, complete)
            return complete
        }
        return cachedDex(output)
    }

    private fun mergeFiles(first: List<File>, second: List<File>): List<File> =
        (first + second)
            .filter { it.isFile && it.length() > 0L }
            .associateBy(File::getName)
            .values
            .sortedWith(
                compareByDescending<File>(File::length)
                    .thenBy(File::getName)
            )

    private fun cachedDex(output: File): List<File> {
        val available = output.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("dex", ignoreCase = true) }
            .onEach { file -> runCatching { file.setReadOnly() } }
            .associateBy(File::getName)
        val generation = File(output, GENERATION_FILE)
        if (generation.isFile) {
            val selected = runCatching {
                generation.readLines()
                    .filter(String::isNotBlank)
                    .mapNotNull(available::get)
            }.getOrDefault(emptyList())
            if (selected.isNotEmpty()) return selected
        }
        return available.values.sortedBy(File::getName)
    }

    private fun writeGeneration(output: File, files: List<File>) {
        runCatching {
            output.mkdirs()
            val temporary = File(output, "$GENERATION_FILE.tmp")
            temporary.writeText(
                files.map(File::getName).distinct().joinToString("\n", postfix = "\n")
            )
            val target = File(output, GENERATION_FILE)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) {
                    "failed to publish DEX generation"
                }
            }
        }.onFailure {
            Log.w(TAG, "write process DEX generation failed", it)
        }
    }

    private fun isResourceOnlyDex(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L ||
            file.length() > 2L * 1024L * 1024L
        ) {
            return false
        }
        return runCatching {
            val dex = DexFile(file.absolutePath)
            try {
                var total = 0
                var resources = 0
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    total += 1
                    val simple = name.substringAfterLast('.')
                    if (simple == "R" || simple.startsWith("R\$")) {
                        resources += 1
                    }
                }
                total > 0 && resources == total
            } finally {
                dex.close()
            }
        }.getOrDefault(false)
    }

    private fun scan(
        memory: RandomAccessFile,
        mapping: Mapping,
        output: File,
        seen: MutableSet<String>,
        result: MutableList<File>
    ) {
        var offset = 0L
        while (offset < mapping.size) {
            val size = minOf(CHUNK_SIZE.toLong(), mapping.size - offset).toInt()
            val buffer = ByteArray(size)
            try {
                memory.seek(mapping.start + offset)
                memory.readFully(buffer)
            } catch (_: Throwable) {
                return
            }
            var index = 0
            while (index + 0x70 <= buffer.size) {
                if (!isDexHeader(buffer, index)) {
                    index += 4
                    continue
                }
                val fileSize = ByteBuffer.wrap(buffer, index + 32, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val headerSize = ByteBuffer.wrap(buffer, index + 36, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val address = mapping.start + offset + index
                if (fileSize < 0x70 || fileSize > MAX_DEX_SIZE ||
                    headerSize < 0x70 || headerSize > fileSize ||
                    address + fileSize > mapping.end
                ) {
                    index += 4
                    continue
                }
                val dex = ByteArray(fileSize.toInt())
                try {
                    memory.seek(address)
                    memory.readFully(dex)
                } catch (_: Throwable) {
                    index += 0x70
                    continue
                }
                if (!valid(dex)) {
                    index += 0x70
                    continue
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(dex)
                    .joinToString("") { "%02x".format(it) }
                if (seen.add(digest)) {
                    val file = File(output, "$digest.dex")
                    if (!file.isFile || file.length() != dex.size.toLong()) {
                        file.writeBytes(dex)
                    }
                    file.setReadOnly()
                    result += file
                }
                index += 0x70
            }
            if (size < CHUNK_SIZE) break
            offset += size - 0x70
        }
    }

    private fun mergeElements(source: ClassLoader, target: ClassLoader) {
        val targetPathList = field(target.javaClass, "pathList")
            ?.apply { isAccessible = true }?.get(target) ?: return
        val sourcePathList = field(source.javaClass, "pathList")
            ?.apply { isAccessible = true }?.get(source) ?: return
        val targetField = field(targetPathList.javaClass, "dexElements")
            ?.apply { isAccessible = true } ?: return
        val sourceField = field(sourcePathList.javaClass, "dexElements")
            ?.apply { isAccessible = true } ?: return
        val original = targetField.get(targetPathList) as Array<*>
        val generated = sourceField.get(sourcePathList) as Array<*>
        val merged = ReflectionArray.newInstance(
            original.javaClass.componentType,
            original.size + generated.size
        ) as Array<Any?>
        // Match Android's installed application order: the APK code path is
        // authoritative and generated DEX only fills classes it does not
        // provide. Prepending a partial generated resource class would hide
        // the complete class compiled into the APK.
        original.forEachIndexed { index, element ->
            merged[index] = element
        }
        generated.forEachIndexed { index, element ->
            merged[original.size + index] = element
        }
        targetField.set(targetPathList, merged)
    }

    private fun field(owner: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = owner
        while (current != null) {
            runCatching { current.getDeclaredField(name) }
                .getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun isDexHeader(buffer: ByteArray, offset: Int): Boolean =
        buffer[offset] == 0x64.toByte() &&
            buffer[offset + 1] == 0x65.toByte() &&
            buffer[offset + 2] == 0x78.toByte() &&
            buffer[offset + 3] == 0x0a.toByte() &&
            buffer[offset + 7] == 0.toByte()

    private fun valid(dex: ByteArray): Boolean {
        if (dex.size < 0x70) return false
        val checksum = ByteBuffer.wrap(dex, 8, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val actual = Adler32().apply {
            update(dex, 12, dex.size - 12)
        }.value
        if (checksum != actual) return false
        val signature = MessageDigest.getInstance("SHA-1")
            .digest(dex.copyOfRange(32, dex.size))
        return dex.copyOfRange(12, 32).contentEquals(signature)
    }

    private fun parseMapping(line: String, dataRoot: File): Mapping? {
        val parts = line.split(Regex("\\s+"), limit = 6)
        if (parts.size < 2) return null
        val range = parts[0].split('-', limit = 2)
        if (range.size != 2) return null
        val start = range[0].toLongOrNull(16) ?: return null
        val end = range[1].toLongOrNull(16) ?: return null
        val label = parts.getOrNull(5).orEmpty()
        return Mapping(
            start,
            end,
            parts[1].startsWith("r"),
            isDexCaptureMappingCandidate(label, dataRoot)
        )
    }

    internal fun isDexCaptureMappingCandidate(
        label: String,
        dataRoot: File
    ): Boolean {
        val path = label.removeSuffix(" (deleted)")
        if (path.isBlank() ||
            path.startsWith("[") ||
            path.contains("memfd", ignoreCase = true)
        ) {
            return true
        }
        val root = dataRoot.absolutePath.trimEnd(File.separatorChar)
        if (root.isNotEmpty() &&
            (path == root || path.startsWith(root + File.separatorChar))
        ) {
            return true
        }
        return GENERIC_DEX_CONTAINER_SUFFIXES.any {
            path.endsWith(it, ignoreCase = true)
        }
    }

    private fun cacheDirectory(dataRoot: File): File =
        File(dataRoot, ".nestspace/process-dex")

    private data class Mapping(
        val start: Long,
        val end: Long,
        val readable: Boolean,
        val candidate: Boolean
    ) {
        val size: Long get() = end - start
    }

    private val GENERIC_DEX_CONTAINER_SUFFIXES =
        listOf(".dex", ".apk", ".jar", ".zip")
}
