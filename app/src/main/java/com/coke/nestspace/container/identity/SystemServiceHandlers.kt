package com.coke.nestspace.container.identity

import com.coke.nestspace.container.identity.ContainerIdentity
import android.os.Parcel
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

internal class AppOpsRewriteHandler(
    private val delegate: Any,
    private val owner: SystemServiceIdentity
) : InvocationHandler {
        private val fallback = IdentityRewriteHandler(delegate, owner)

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return fallback.invoke(proxy, method, args)
            }
            val target = owner.targetPackage
            val host = owner.hostPackage
            val targetUid = owner.targetUid
            val hostUid = ContainerIdentity.hostUid()
            val callArgs = args?.map { value ->
                when {
                    value is Int && value == owner.targetUid -> hostUid
                    value is String && value == target ->
                        if (host.isNotEmpty()) host else value
                    else -> value
                }
            }?.toTypedArray()
            return fallback.invokeRewritten(proxy, method, callArgs)
        }
    }

internal class BluetoothPermissionHandler(
    private val delegate: Any,
    private val owner: SystemServiceIdentity
) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "ContainerBluetooth(${delegate.javaClass.name})"
                    else -> null
                }
            }
            return try {
                owner.invokeDelegate(delegate, method, args)
            } catch (security: SecurityException) {
                Log.w(
                    owner.TAG,
                    "Bluetooth ${method.name} denied; returning unavailable state",
                    security
                )
                unavailableValue(method.returnType)
            }
        }

        private fun unavailableValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }

internal class IdentityRewriteHandler(
    private val delegate: Any,
    private val owner: SystemServiceIdentity
) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return invokeObjectMethod(proxy, method, args)
            }
            val rewrittenArgs = args?.map { value ->
                if (method.name == "getPackageGids") {
                    value
                } else {
                    rewriteArgument(value)
                }
            }?.toTypedArray()
            return invokeRewritten(proxy, method, rewrittenArgs)
        }

        fun invokeRewritten(
            proxy: Any,
            method: Method,
            args: Array<Any?>?
        ): Any? {
            if (method.declaringClass == Any::class.java) {
                return invokeObjectMethod(proxy, method, args)
            }
            val result = owner.invokeDelegate(delegate, method, args)
            return rewriteResult(result)
        }

        private fun invokeObjectMethod(
            proxy: Any,
            method: Method,
            args: Array<Any?>?
        ): Any? = when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ContainerService(${delegate.javaClass.name})"
            else -> null
        }

        private fun rewriteArgument(value: Any?): Any? {
            val target = owner.targetPackage
            val host = owner.hostPackage
            if (target.isEmpty() || target == host) return value
            return when (value) {
                is String -> if (value == target) host else value
                is Array<*> -> SystemServiceArrayRewrite.rewrite(
                    value,
                    ::rewriteArgument
                )
                is Collection<*> -> value.map(::rewriteArgument)
                else -> rewriteInputEditorInfo(value)
                    ?: rewriteAttributionSource(value, target, host)
                    ?: value
            }
        }

        private fun rewriteInputEditorInfo(value: Any?): Any? {
            val editorInfoClass =
                owner.findClass("android.view.inputmethod.EditorInfo") ?: return null
            if (!editorInfoClass.isInstance(value)) return null
            val packageField = owner.declaredField(editorInfoClass, "packageName")
                ?: return null
            if (packageField.get(value) != owner.targetPackage) return value
            return runCatching {
                val parcel = Parcel.obtain()
                try {
                    (value as android.os.Parcelable).writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    val creator = editorInfoClass.getField("CREATOR")
                        .get(null) as android.os.Parcelable.Creator<*>
                    val copy = creator.createFromParcel(parcel)
                    packageField.set(copy, owner.hostPackage)
                    copy
                } finally {
                    parcel.recycle()
                }
            }.getOrNull()
        }

        private fun rewriteResult(value: Any?): Any? {
            val target = owner.targetPackage
            val host = owner.hostPackage
            if (target.isEmpty() || target == host) return value
            return when (value) {
                is String -> if (value == host) target else value
                is Array<*> -> SystemServiceArrayRewrite.rewrite(
                    value,
                    ::rewriteResult
                )
                is Iterable<*> -> value.map(::rewriteResult)
                else -> rewritePackageOps(value)
                    ?: rewriteAttributionSource(value, host, target)
                    ?: value
            }
        }

        private fun rewriteAttributionSource(
            value: Any?,
            fromPackage: String,
            toPackage: String
        ): Any? {
            val sourceClass = owner.findClass("android.content.AttributionSource")
                ?: return null
            if (!sourceClass.isInstance(value)) return null
            return runCatching {
                val packageName = owner.method(sourceClass, "getPackageName")
                    ?.invoke(value) as? String
                if (packageName != fromPackage) return@runCatching value
                val builderClass = owner.loadClass(
                    "android.content.AttributionSource\$Builder"
                )
                val builder = owner.constructor(builderClass, sourceClass)
                    ?.newInstance(value)
                    ?: return@runCatching null
                val setPackage = owner.method(
                    builderClass,
                    "setPackageName",
                    String::class.java
                )
                    ?: return@runCatching null
                setPackage.invoke(builder, toPackage)
                owner.method(
                    builderClass,
                    "setUid",
                    Int::class.javaPrimitiveType!!
                )?.invoke(
                    builder,
                    if (toPackage == owner.hostPackage) {
                        ContainerIdentity.hostUid()
                    } else if (toPackage == owner.targetPackage) {
                        owner.targetUid
                    } else {
                        owner.method(sourceClass, "getUid")?.invoke(value)
                    }
                )
                owner.method(builderClass, "build")?.invoke(builder)
            }.getOrNull()
        }

        private fun rewritePackageOps(value: Any?): Any? {
            val packageOpsClass = owner.findClass("android.app.AppOpsManager\$PackageOps")
                ?: return null
            if (!packageOpsClass.isInstance(value)) return null
            return runCatching {
                val packageName = owner.method(packageOpsClass, "getPackageName")
                    ?.invoke(value) as? String
                    ?: return@runCatching null
                if (packageName != owner.hostPackage) return@runCatching value
                owner.method(packageOpsClass, "getUid")?.invoke(value)
                @Suppress("UNCHECKED_CAST")
                val ops = owner.method(packageOpsClass, "getOps")?.invoke(value)
                    as List<Any?>
                owner.constructor(
                    packageOpsClass,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    List::class.java
                )
                    ?.newInstance(
                        owner.targetPackage,
                        owner.targetUid.takeIf { it > 0 }
                            ?: ContainerIdentity.virtualUidFor(owner.targetPackage),
                        ops
                    )
            }.getOrNull()
        }
    }
