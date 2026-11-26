package com.coke.nestspace.container.runtime

import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.loader.ApkClassIndex
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a secret-free compatibility report after a target initialization
 * failure. It never initializes target classes and never changes app state.
 */
object RuntimeBootGraphDiagnostics {
    private const val TAG = "RuntimeBootGraph"
    private const val FILE_NAME = "runtime-boot-graph.json"
    private const val MAX_SCANNED_CLASSES = 50_000
    private const val MAX_SCAN_MILLIS = 30_000L
    private const val MAX_IMPLEMENTATIONS = 12

    private val running = ConcurrentHashMap.newKeySet<String>()

    fun analyzeAsync(
        packageName: String,
        processName: String,
        dataRoot: String,
        classLoader: ClassLoader,
        apkPaths: List<String>,
        stage: String,
        failure: Throwable
    ) {
        val key = "$packageName|$processName|$stage"
        if (!running.add(key)) return
        Thread(
            {
                try {
                    val report = analyze(
                        packageName = packageName,
                        processName = processName,
                        classLoader = classLoader,
                        apkPaths = apkPaths,
                        stage = stage,
                        failure = failure
                    )
                    write(dataRoot, report)
                } catch (error: Throwable) {
                    Log.w(TAG, "boot graph analysis failed", error)
                } finally {
                    running.remove(key)
                }
            },
            "nestspace-boot-graph"
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun analyze(
        packageName: String,
        processName: String,
        classLoader: ClassLoader,
        apkPaths: List<String>,
        stage: String,
        failure: Throwable
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        val classNames = ApkClassIndex.classNames(apkPaths)
        val seeds = discoverSeedTypes(failure, classLoader)
        val interfaces = linkedMapOf<String, Class<*>>()
        seeds.forEach { type -> collectReferencedInterfaces(type, interfaces) }
        val implementations = linkedMapOf<String, MutableList<Class<*>>>()
        addSeedImplementations(seeds, interfaces, implementations)
        val startupCandidates = mutableListOf<String>()
        val serviceManagers = mutableListOf<String>()
        var scanned = 0
        var loadFailures = 0
        var truncated = false

        for (name in classNames.asSequence().filter(::isCandidateClass)) {
            if (scanned >= MAX_SCANNED_CLASSES ||
                SystemClock.elapsedRealtime() - startedAt >= MAX_SCAN_MILLIS
            ) {
                truncated = true
                break
            }
            scanned += 1
            val type = runCatching {
                Class.forName(name, false, classLoader)
            }.getOrElse {
                loadFailures += 1
                continue
            }
            if (Modifier.isAbstract(type.modifiers) ||
                type.isInterface ||
                type.isEnum ||
                type.isAnnotation
            ) {
                recordBootCandidate(type, startupCandidates, serviceManagers)
                continue
            }
            interfaces.forEach { (interfaceName, interfaceType) ->
                if (!runCatching { interfaceType.isAssignableFrom(type) }
                        .getOrDefault(false)
                ) {
                    return@forEach
                }
                val candidates = implementations.getOrPut(interfaceName) {
                    mutableListOf()
                }
                if (candidates.size < MAX_IMPLEMENTATIONS &&
                    candidates.none { it === type }
                ) {
                    candidates += type
                }
            }
            recordBootCandidate(type, startupCandidates, serviceManagers)
        }

        return JSONObject()
            .put("schema_version", 1)
            .put("generated_at", System.currentTimeMillis())
            .put("package", packageName)
            .put("process", processName)
            .put("stage", stage)
            .put("duration_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("class_count", classNames.size)
            .put("scanned", scanned)
            .put("load_failures", loadFailures)
            .put("truncated", truncated)
            .put(
                "failure",
                JSONObject()
                    .put("type", failure.javaClass.name)
                    .put("message", failure.message ?: JSONObject.NULL)
                    .put(
                        "stack",
                        JSONArray(failure.stackTrace.take(24).map { it.toString() })
                    )
            )
            .put(
                "seed_types",
                JSONArray(seeds.map { describeClass(it) })
            )
            .put(
                "service_bindings",
                JSONArray(
                    implementations.map { (interfaceName, candidates) ->
                        JSONObject()
                            .put("interface", interfaceName)
                            .put(
                                "implementations",
                                JSONArray(candidates.map(::describeImplementation))
                            )
                    }
                )
            )
            .put("service_managers", JSONArray(serviceManagers.distinct().take(40)))
            .put("startup_candidates", JSONArray(startupCandidates.distinct().take(80)))
    }

    private fun discoverSeedTypes(
        failure: Throwable,
        classLoader: ClassLoader
    ): List<Class<*>> {
        val names = linkedSetOf<String>()
        extractClassNames(failure.message.orEmpty()).forEach(names::add)
        failure.stackTrace.take(32).forEach { element ->
            element.className.takeIf { it.contains('.') }?.let(names::add)
        }
        return names.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }.distinct()
    }

    private fun collectReferencedInterfaces(
        type: Class<*>,
        output: MutableMap<String, Class<*>>
    ) {
        val queue = ArrayDeque<Class<*>>()
        queue += type
        var inspected = 0
        while (queue.isNotEmpty() && inspected < 24) {
            val current = queue.removeFirst()
            inspected += 1
            if (current.isInterface) output.putIfAbsent(current.name, current)
            current.interfaces.forEach { queue += it }
            current.superclass?.let { queue += it }
            runCatching {
                current.declaredFields.take(64).forEach { field ->
                    if (field.type.isInterface) {
                        output.putIfAbsent(field.type.name, field.type)
                    }
                    queue += field.type
                }
                current.declaredMethods.take(64).forEach { method ->
                    method.parameterTypes.forEach { parameter ->
                        if (parameter.isInterface) {
                            output.putIfAbsent(parameter.name, parameter)
                        }
                    }
                    val returnType = method.returnType
                    if (returnType.isInterface) {
                        output.putIfAbsent(returnType.name, returnType)
                    }
                }
            }
        }
    }

    private fun recordBootCandidate(
        type: Class<*>,
        startupCandidates: MutableList<String>,
        serviceManagers: MutableList<String>
    ) {
        val names = buildList {
            add(type.name)
            addAll(type.interfaces.map { it.name })
            type.superclass?.let { add(it.name) }
        }
        if (names.any { name ->
                name.contains("InitTask", true) ||
                    name.contains("StartupTask", true) ||
                    name.contains("Initializer", true)
            }
        ) {
            startupCandidates += type.name
        }
        if (runCatching {
                type.declaredMethods.any { method ->
                    method.name in setOf("registerService", "getService", "getServiceCache")
                }
            }.getOrDefault(false)
        ) {
            serviceManagers += type.name
        }
    }

    private fun addSeedImplementations(
        seeds: List<Class<*>>,
        interfaces: Map<String, Class<*>>,
        implementations: MutableMap<String, MutableList<Class<*>>>
    ) {
        seeds.filterNot(::isNonConcreteType).forEach { type ->
            interfaces.forEach { (interfaceName, interfaceType) ->
                if (runCatching { interfaceType.isAssignableFrom(type) }
                        .getOrDefault(false)
                ) {
                    implementations.getOrPut(interfaceName) {
                        mutableListOf()
                    } += type
                }
            }
        }
    }

    private fun describeClass(type: Class<*>): JSONObject =
        JSONObject()
            .put("name", type.name)
            .put("interface", type.isInterface)
            .put("abstract", Modifier.isAbstract(type.modifiers))
            .put("interfaces", JSONArray(type.interfaces.map { it.name }))

    private fun describeImplementation(type: Class<*>): JSONObject {
        val constructors = type.declaredConstructors.map { constructor ->
            constructor.parameterTypes.joinToString(",") { it.name }
        }.sorted()
        val methods = runCatching {
            type.declaredMethods
                .filterNot { it.isBridge || it.isSynthetic }
                .take(80)
                .map { method ->
                    JSONObject()
                        .put("name", method.name)
                        .put(
                            "parameters",
                            JSONArray(method.parameterTypes.map { it.name })
                        )
                        .put("returns", method.returnType.name)
                        .put("static", Modifier.isStatic(method.modifiers))
                }
        }.getOrDefault(emptyList())
        val fields = runCatching {
            type.declaredFields
                .filter { field ->
                    field.type != String::class.java &&
                        field.type != Int::class.javaPrimitiveType &&
                        field.type != Long::class.javaPrimitiveType &&
                        field.type != Boolean::class.javaPrimitiveType
                }
                .take(40)
                .map { field ->
                    JSONObject()
                        .put("name", field.name)
                        .put("type", field.type.name)
                        .put("static", Modifier.isStatic(field.modifiers))
                        .put("dependency", describeDependency(field.type))
                }
        }.getOrDefault(emptyList())
        return JSONObject()
            .put("class", type.name)
            .put("constructors", JSONArray(constructors))
            .put("methods", JSONArray(methods))
            .put("fields", JSONArray(fields))
    }

    private fun describeDependency(type: Class<*>): JSONObject? {
        if (type.isPrimitive || type.name.startsWith("java.") ||
            type.name.startsWith("android.") ||
            type.name.startsWith("kotlin.")
        ) {
            return null
        }
        return JSONObject()
            .put("class", type.name)
            .put("interface", type.isInterface)
            .put("abstract", Modifier.isAbstract(type.modifiers))
            .put(
                "constructors",
                JSONArray(
                    runCatching {
                        type.declaredConstructors.map { constructor ->
                            constructor.parameterTypes.joinToString(",") { it.name }
                        }.sorted()
                    }.getOrDefault(emptyList())
                )
            )
            .put(
                "methods",
                JSONArray(
                    runCatching {
                        type.declaredMethods
                            .filterNot { it.isBridge || it.isSynthetic }
                            .take(50)
                            .map { method ->
                                method.name + "(" +
                                    method.parameterTypes.joinToString(",") { it.name } +
                                    "):" + method.returnType.name
                            }
                    }.getOrDefault(emptyList())
                )
            )
    }

    private fun write(dataRoot: String, report: JSONObject) {
        val directory = File(dataRoot, ".nestspace").apply { mkdirs() }
        val target = File(directory, FILE_NAME)
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeText(report.toString(2))
        if (!temporary.renameTo(target)) {
            target.writeText(report.toString(2))
            temporary.delete()
        }
        Log.d(
            TAG,
            "boot graph written classes=${report.optInt("class_count")} " +
                "scanned=${report.optInt("scanned")} " +
                "duration=${report.optLong("duration_ms")}ms"
        )
    }

    private fun isCandidateClass(name: String): Boolean =
        ignoredPrefixes.none(name::startsWith) &&
            !name.endsWith("R") &&
            !name.endsWith("R$")

    private fun isNonConcreteType(type: Class<*>): Boolean =
        type.isInterface ||
            type.isEnum ||
            type.isAnnotation ||
            Modifier.isAbstract(type.modifiers)

    private fun extractClassNames(message: String): List<String> =
        classPattern.findAll(message).map { it.value }.toList()

    private val ignoredPrefixes = listOf(
        "android.",
        "androidx.",
        "dalvik.",
        "java.",
        "javax.",
        "kotlin.",
        "kotlinx.",
        "libcore.",
        "org.json.",
        "org.xmlpull.",
        "sun."
    )

    private val classPattern = Regex(
        """(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*"""
    )
}
