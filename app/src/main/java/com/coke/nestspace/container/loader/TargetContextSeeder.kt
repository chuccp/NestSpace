package com.coke.nestspace.container.loader

import android.content.Context
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Many SDKs expose process-wide builders as a static singleton with an
 * instance or static Context slot, for example:
 *
 * ```
 * static Builder instance;
 * Context context;
 * ```
 *
 * Android normally initializes these from the app's own startup code. A
 * virtual process can load such a builder before the library's normal
 * initializer runs, leaving the slot null in secondary processes. Seed the
 * standard Context slot shape from the target Context without matching any
 * vendor or class name.
 */
internal object TargetContextSeeder {
    private const val TAG = "TargetContextSeeder"
    private val seeding = ThreadLocal<Boolean>()

    fun seed(type: Class<*>, context: Context) {
        if (seeding.get() == true) return
        seeding.set(true)
        try {
            seedInternal(type, context)
        } finally {
            seeding.remove()
        }
    }

    private fun seedInternal(type: Class<*>, context: Context) {
        val fields = type.declaredFields
        val staticContextFields = fields.filter {
            Modifier.isStatic(it.modifiers) &&
                isContextType(it.type)
        }
        val instanceContextFields = fields.filter {
            !Modifier.isStatic(it.modifiers) &&
                isContextType(it.type) &&
                !Modifier.isFinal(it.modifiers)
        }
        if (staticContextFields.isEmpty() && instanceContextFields.isEmpty()) {
            return
        }

        staticContextFields.forEach { field ->
            val current = runCatching { field.get(null) }.getOrNull()
            if (current !is Context ||
                current.packageName != context.packageName
            ) {
                set(field, null, contextValue(field, context), type)
            }
        }
        if (instanceContextFields.isEmpty()) return

        fields.asSequence()
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    !it.type.isPrimitive &&
                    it.type != Any::class.java &&
                    type.isAssignableFrom(it.type)
            }
            .mapNotNull { field ->
                runCatching { field.get(null) }.getOrNull()
            }
            .distinct()
            .forEach { holder ->
                instanceContextFields.forEach { field ->
                    val current = runCatching { field.get(holder) }.getOrNull()
                    if (current == null) {
                        set(
                            field,
                            holder,
                            contextValue(field, context),
                            type
                        )
                    }
                }
            }
    }

    private fun contextValue(field: Field, context: Context): Context =
        if (android.app.Application::class.java.isAssignableFrom(field.type)) {
            context.applicationContext
        } else {
            context
        }

    private fun isContextType(type: Class<*>): Boolean =
        Context::class.java.isAssignableFrom(type)

    private fun set(
        field: Field,
        receiver: Any?,
        context: Context,
        owner: Class<*>
    ) {
        if (!field.type.isInstance(context)) {
            Log.w(
                TAG,
                "skip ${owner.name}.${field.name}: " +
                    "expected ${field.type.name}, got ${context.javaClass.name}"
            )
            return
        }
        runCatching {
            field.isAccessible = true
            field.set(receiver, context)
            Log.d(
                TAG,
                "seeded ${owner.name}.${field.name} from target context"
            )
        }.onFailure {
            Log.w(
                TAG,
                "seed ${owner.name}.${field.name} failed",
                it
            )
        }
    }
}
