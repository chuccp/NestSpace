package com.coke.nestspace.core.reflection

import android.content.Intent

internal object ReflectionDefaults {
    fun primitive(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    fun collection(type: Class<*>): Any? = primitive(type) ?: when {
        type.isArray -> java.lang.reflect.Array.newInstance(
            requireNotNull(type.componentType),
            0
        )
        type == List::class.java -> emptyList<Any>()
        type == Set::class.java -> emptySet<Any>()
        type == Map::class.java -> emptyMap<Any, Any>()
        type == CharSequence::class.java || type == String::class.java -> ""
        else -> null
    }

    fun shortcut(type: Class<*>): Any? = primitive(type) ?: when (type) {
        String::class.java -> ""
        List::class.java -> emptyList<Any>()
        Intent::class.java -> Intent()
        else -> null
    }
}
