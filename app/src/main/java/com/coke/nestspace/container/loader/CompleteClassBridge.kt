package com.coke.nestspace.container.loader

import android.util.Log
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Resolves a class against the complete runtime DEX set captured from the
 * target process. It is used only to answer JNI field lookups that failed on
 * a partially initialized framework-facing class.
 */
internal object CompleteClassBridge {
    private const val TAG = "CompleteClassBridge"
    private var dataRootPath: String? = null
    private var targetLoader: ClassLoader? = null
    private var completeLoader: ClassLoader? = null
    private val classNamesByLoader =
        WeakHashMap<ClassLoader, List<String>>()

    fun prepare(dataRoot: File, loader: ClassLoader) {
        val path = dataRoot.absolutePath
        synchronized(this) {
            if (dataRootPath == path && targetLoader === loader) return
            dataRootPath = path
            targetLoader = loader
            completeLoader = null
            classNamesByLoader.remove(loader)
        }
    }

    /**
     * Keeps JNI class lookups on the same class identity as the application
     * class loader. Native runtimes may carry a second class table populated
     * while dynamic code was still being attached; resolving through this
     * loader returns the current, complete definition for the same descriptor.
     */
    @JvmStatic
    fun resolveName(descriptor: String): Class<*>? {
        if (descriptor.isBlank() ||
            descriptor.startsWith("[") ||
            descriptor.startsWith("java/") ||
            descriptor.startsWith("android/") ||
            descriptor.startsWith("javax/") ||
            descriptor.startsWith("dalvik/") ||
            descriptor.startsWith("libcore/") ||
            descriptor.startsWith("sun/") ||
            descriptor.startsWith("com/android/")
        ) {
            return null
        }
        val loader = synchronized(this) { targetLoader } ?: return null
        val className = descriptor.replace('/', '.')
        return runCatching {
            loader.loadClass(className)
        }.onSuccess {
            if (NativeEnvironmentBridge.readPropertyForDiagnostics(
                    "debug.nestspace.class_resolution_trace"
                ) == "1"
            ) {
                Log.d(
                    TAG,
                    "reconciled class name=$className identity=${System.identityHashCode(it)} " +
                        "loader=${it.classLoader?.javaClass?.name}@" +
                        "${System.identityHashCode(it.classLoader)}"
                )
            }
        }.onFailure {
            Log.d(TAG, "class-loader reconciliation unavailable: $className")
        }.getOrNull()?.takeIf(::isConstantContainer)
    }

    @JvmStatic
    fun shouldReconcile(type: Class<*>?): Boolean =
        type != null && isConstantContainer(type)

    private fun isConstantContainer(type: Class<*>): Boolean {
        if (type.superclass != Any::class.java) return false
        if (type.declaredMethods.any { !it.isSynthetic }) return false
        val fields = type.declaredFields
        if (fields.isEmpty()) return false
        return fields.all { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type == Int::class.javaPrimitiveType
        }
    }

    fun recordClassNames(loader: ClassLoader, names: Iterable<String>) {
        synchronized(classNamesByLoader) {
            classNamesByLoader[loader] = names.distinct()
        }
    }

    @JvmStatic
    fun describe(owner: Class<*>, fieldName: String?): String {
        val ownerFields = runCatching { owner.declaredFields.toList() }
            .getOrDefault(emptyList())
        val ownerLoader = owner.classLoader
        val candidate = if (!fieldName.isNullOrBlank()) {
            runCatching {
                findProviderInTargetLoader(owner, fieldName, "I")
            }.getOrNull()
        } else {
            null
        }
        val candidateFields = candidate
            ?.let { runCatching { it.declaredFields.toList() }.getOrNull() }
            .orEmpty()
        return "owner=${owner.name}@${System.identityHashCode(owner)} " +
            "ownerLoader=${ownerLoader?.javaClass?.name}@" +
            "${System.identityHashCode(ownerLoader)} fields=${ownerFields.size} " +
            "hasTarget=${ownerFields.any { it.name == fieldName }} " +
            "candidate=${candidate?.name}@" +
            "${System.identityHashCode(candidate)} " +
            "candidateLoader=${candidate?.classLoader?.javaClass?.name}@" +
            "${System.identityHashCode(candidate?.classLoader)} " +
            "candidateFields=${candidateFields.size}"
    }

    @JvmStatic
    fun resolve(
        owner: Class<*>,
        fieldName: String?,
        signature: String?
    ): Class<*>? {
        if (!fieldName.isNullOrBlank() && !signature.isNullOrBlank()) {
            findProviderInTargetLoader(
                owner,
                fieldName,
                signature
            )?.let { return it }
        }
        val loader = loaderForCurrentTarget()
        if (loader == null) {
            Log.d(TAG, "complete loader unavailable for ${owner.name}")
            return null
        }
        return runCatching {
            loader.loadClass(owner.name)
        }.onSuccess {
            Log.d(TAG, "resolved complete class ${owner.name}")
        }.onFailure {
            Log.w(TAG, "complete class unavailable: ${owner.name}", it)
        }.getOrNull()
    }

    private fun findProviderInTargetLoader(
        owner: Class<*>,
        fieldName: String,
        signature: String
    ): Class<*>? {
        val enumerationLoader =
            targetLoader ?: owner.classLoader ?: return null
        val loadingLoader = owner.classLoader ?: enumerationLoader
        val simpleName = owner.name.substringAfterLast('.')
        val allNames = classNames(enumerationLoader)
        val matching = allNames
            .asSequence()
            .filter { name ->
                name != owner.name &&
                    name.endsWith(".$simpleName")
            }
            .toList()
        Log.d(
            TAG,
            "field provider search owner=${owner.name} classes=${allNames.size} " +
                "matching=${matching.size}"
        )
        val candidates = matching
            .asSequence()
            .sortedBy { name ->
                -commonPrefixLength(name, owner.name)
            }
            .take(MAX_PROVIDER_CANDIDATES)
        for (name in candidates) {
            val candidate = runCatching {
                loadingLoader.loadClass(name)
            }.getOrNull() ?: continue
            if (hasField(candidate, fieldName, signature)) {
                Log.d(
                    TAG,
                    "using matching-loader field provider ${candidate.name} " +
                        "classIdentity=${System.identityHashCode(candidate)} " +
                        "loader=${candidate.classLoader?.javaClass?.name}@" +
                        "${System.identityHashCode(candidate.classLoader)}"
                )
                return candidate
            }
        }
        return null
    }

    private fun hasField(
        owner: Class<*>,
        name: String,
        signature: String
    ): Boolean = owner.declaredFields.any { field ->
        field.name == name &&
            field.type.descriptor() == signature
    }

    private fun classNames(loader: ClassLoader): List<String> =
        synchronized(classNamesByLoader) {
            classNamesByLoader[loader] ?: run {
                val pathList = findField(loader.javaClass, "pathList")
                val elements = pathList
                    ?.apply { isAccessible = true }
                    ?.let { runCatching { it.get(loader) }.getOrNull() }
                    ?.let { list ->
                        findField(list.javaClass, "dexElements")
                            ?.let { runCatching { it.get(list) as? Array<*> }.getOrNull() }
                    }
                    .orEmpty()
                Log.d(
                    TAG,
                    "class enumeration loader=${loader.javaClass.name} " +
                        "pathList=${pathList != null} elements=${elements.size}"
                )
                val names = elements.asSequence()
                    .filterNotNull()
                    .mapNotNull { element ->
                        runCatching {
                            findField(element.javaClass, "dexFile")
                                ?.get(element) as? DexFile
                        }.getOrNull()
                    }
                    .flatMap { dex ->
                        runCatching { dex.entries().asSequence() }
                            .getOrDefault(emptySequence())
                    }
                    .distinct()
                    .toList()
                classNamesByLoader[loader] = names
                names
            }
        }

    private fun findField(owner: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = owner
        while (current != null) {
            runCatching { current.getDeclaredField(name) }
                .getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun Class<*>.descriptor(): String? = when (this) {
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        java.lang.Void.TYPE -> "V"
        else -> name.replace('.', '/').let { "L$it;" }
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        var index = 0
        while (index < first.length && index < second.length &&
            first[index] == second[index]
        ) {
            index += 1
        }
        return index
    }

    private fun loaderForCurrentTarget(): ClassLoader? {
        synchronized(this) {
            targetLoader?.let { return it }
            completeLoader?.let { return it }
            val root = dataRootPath?.let(::File) ?: return null
            val files = ProcessDexBridge.cachedDexFiles(root)
            if (files.isEmpty()) return null
            return runCatching {
                PathClassLoader(
                    files.joinToString(File.pathSeparator) { it.absolutePath },
                    null,
                    ClassLoader.getSystemClassLoader().parent
                )
            }.onSuccess {
                completeLoader = it
            }.onFailure {
                Log.w(TAG, "create complete DEX loader failed", it)
            }.getOrNull()
        }
    }

    private const val MAX_PROVIDER_CANDIDATES = 32

}
