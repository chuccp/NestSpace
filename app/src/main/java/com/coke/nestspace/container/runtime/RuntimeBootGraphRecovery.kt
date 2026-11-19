package com.coke.nestspace.container.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Applies a narrowly-scoped recovery plan derived from a previous boot graph.
 * Only unique implementations already present in the failure stack are used.
 */
object RuntimeBootGraphRecovery {
    private const val TAG = "RuntimeBootRecovery"
    private const val GRAPH_FILE = "runtime-boot-graph.json"
    private const val RESULT_FILE = "runtime-boot-recovery.json"

    data class Result(
        val applied: Int,
        val skipped: Int,
        val errors: Int,
        val details: List<String>
    )

    fun apply(
        context: Context,
        packageName: String,
        dataRoot: String,
        classLoader: ClassLoader
    ): Result? {
        val graphFile = File(File(dataRoot, ".nestspace"), GRAPH_FILE)
        if (!graphFile.isFile) return null
        val graph = runCatching {
            JSONObject(graphFile.readText())
        }.getOrElse {
            Log.w(TAG, "could not read boot graph", it)
            return null
        }
        if (graph.optString("package") != packageName) return null
        val failureClasses = graph
            .optJSONObject("failure")
            ?.optJSONArray("stack")
            ?.let { stack ->
                buildSet {
                    for (index in 0 until stack.length()) {
                        val frame = stack.optString(index).substringBefore('(')
                        add(frame.substringBeforeLast('.'))
                    }
                }
            }
            .orEmpty()
        val serviceManager = findServiceManager(graph, classLoader) ?: return null
        val getService = serviceManager.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.name == "getService" &&
                it.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: return null
        val registerService = serviceManager.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.name == "registerService" &&
                it.parameterTypes.contentEquals(
                    arrayOf(Class::class.java, Any::class.java)
                )
        } ?: return null
        val details = mutableListOf<String>()
        var applied = 0
        var skipped = 0
        var errors = 0

        val bindings = graph.optJSONArray("service_bindings") ?: JSONArray()
        for (bindingIndex in 0 until bindings.length()) {
            val binding = bindings.optJSONObject(bindingIndex) ?: continue
            val implementations = binding.optJSONArray("implementations") ?: continue
            if (implementations.length() != 1) {
                skipped += 1
                continue
            }
            val implementation = implementations.optJSONObject(0) ?: continue
            val implementationName = implementation.optString("class")
            if (implementationName !in failureClasses) {
                skipped += 1
                continue
            }
            val interfaceName = binding.optString("interface")
            try {
                val interfaceType = Class.forName(interfaceName, false, classLoader)
                val existingService = getService.invoke(null, interfaceType)
                val implementationType = Class.forName(
                    implementationName,
                    true,
                    classLoader
                )
                if (Modifier.isAbstract(implementationType.modifiers) ||
                    implementationType.isInterface
                ) {
                    skipped += 1
                    continue
                }
                val instance = existingService ?: implementationType
                    .getDeclaredConstructor()
                    .apply { isAccessible = true }
                    .newInstance()
                val repaired = initializeDependencies(
                    context = context,
                    classLoader = classLoader,
                    instance = instance,
                    implementation = implementation
                )
                if (existingService == null) {
                    registerService.invoke(null, interfaceType, instance)
                    details += "$interfaceName -> $implementationName"
                    applied += 1
                } else if (repaired) {
                    details += "$interfaceName repaired $implementationName"
                    applied += 1
                } else {
                    skipped += 1
                }
            } catch (error: Throwable) {
                errors += 1
                details += "$interfaceName: ${error.message ?: error.javaClass.name}"
                Log.w(TAG, "recovery failed for $interfaceName", error)
            }
        }

        val result = Result(applied, skipped, errors, details)
        writeResult(dataRoot, packageName, result)
        Log.d(
            TAG,
            "applied=$applied skipped=$skipped errors=$errors " +
                details.joinToString(" | ")
        )
        return result
    }

    private fun findServiceManager(
        graph: JSONObject,
        classLoader: ClassLoader
    ): Class<*>? {
        val seeds = graph.optJSONArray("seed_types") ?: return null
        for (index in 0 until seeds.length()) {
            val name = seeds.optJSONObject(index)?.optString("name").orEmpty()
            if (!name.contains("ServiceManager")) continue
            val type = runCatching {
                Class.forName(name, false, classLoader)
            }.getOrNull() ?: continue
            val methods = type.declaredMethods
            if (methods.any { it.name == "getService" } &&
                methods.any { it.name == "registerService" }
            ) {
                return type
            }
        }
        return null
    }

    private fun initializeDependencies(
        context: Context,
        classLoader: ClassLoader,
        instance: Any,
        implementation: JSONObject
    ): Boolean {
        val fields = implementation.optJSONArray("fields") ?: return false
        var repaired = false
        for (index in 0 until fields.length()) {
            val fieldInfo = fields.optJSONObject(index) ?: continue
            val dependency = fieldInfo.optJSONObject("dependency") ?: continue
            if (dependency.optBoolean("interface") ||
                dependency.optBoolean("abstract")
            ) {
                continue
            }
            val constructors = dependency.optJSONArray("constructors") ?: continue
            if (!constructors.toString().contains("\"\"")) continue
            val dependencyType = Class.forName(
                dependency.optString("class"),
                true,
                classLoader
            )
            val field = instance.javaClass.declaredFields.firstOrNull {
                it.name == fieldInfo.optString("name") &&
                    it.type == dependencyType
            } ?: continue
            field.isAccessible = true
            val existing = if (Modifier.isStatic(field.modifiers)) {
                field.get(null)
            } else {
                field.get(instance)
            }
            val value = existing ?: dependencyType
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            if (existing == null) {
                if (Modifier.isStatic(field.modifiers)) {
                    field.set(null, value)
                } else {
                    field.set(instance, value)
                }
                repaired = true
            }
            val initMethod = instance.javaClass.declaredMethods.firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0].isAssignableFrom(Context::class.java) &&
                    method.parameterTypes[1].isAssignableFrom(dependencyType)
            }
            if (initMethod != null) {
                initMethod.isAccessible = true
                initMethod.invoke(instance, context, value)
                repaired = true
            }
        }
        return repaired
    }

    private fun writeResult(
        dataRoot: String,
        packageName: String,
        result: Result
    ) {
        val directory = File(dataRoot, ".nestspace").apply { mkdirs() }
        File(directory, RESULT_FILE).writeText(
            JSONObject()
                .put("generated_at", System.currentTimeMillis())
                .put("package", packageName)
                .put("applied", result.applied)
                .put("skipped", result.skipped)
                .put("errors", result.errors)
                .put("details", JSONArray(result.details))
                .toString(2)
        )
    }
}
