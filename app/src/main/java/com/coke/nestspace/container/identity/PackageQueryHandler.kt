package com.coke.nestspace.container.identity

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

internal class PackageQueryHandler(
    private val delegate: Any,
    private val owner: SystemServiceIdentity
) : InvocationHandler {

        private class Routed(val value: Any?)

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val values = args ?: emptyArray()
            if (owner.packageManagerDelegation.get()) {
                return owner.invokeDelegate(delegate, method, values)
            }
            val name = method.name
            val requestedPackage = values.firstNotNullOfOrNull {
                when (it) {
                    is String -> it
                    is VersionedPackage -> it.packageName
                    is ComponentName -> it.packageName
                    else -> null
                }
            }
            if (requestedPackage == owner.hostPackage && isHostPackageQuery(name)) {
                return hiddenHostPackageResult(method.returnType, name)
            }
            val legacy = owner.legacyPackageManager
            if (legacy != null) {
                val routed = routeThroughPackageManager(legacy, name, values)
                if (routed !== owner.NOT_ROUTED) return (routed as Routed).value
            }
            val target = owner.targetPackage
            if (target.isEmpty()) {
                return owner.invokeDelegate(delegate, method, values)
            }
            val queried = values.firstNotNullOfOrNull {
                when (it) {
                    is String -> it
                    is VersionedPackage -> it.packageName
                    else -> null
                }
            }
            val virtualRegistry = owner.registry
            // checkPermission(permission, packageName, ...): the first string
            // is the permission, so handle it before the package lookup.
            if (name.startsWith("checkPermission")) {
                val strings = values.filterIsInstance<String>()
                val permission = strings.getOrNull(0)
                val pkg = strings.getOrNull(1)
                val permissions = owner.virtualPermissions
                if (permission != null && pkg != null && permissions != null &&
                    (pkg == target || virtualRegistry?.isVirtualPackage(pkg) == true)
                ) {
                    return permissions.checkPackagePermission(pkg, permission)
                }
            }
            if (queried != null &&
                (queried == target || virtualRegistry?.isVirtualPackage(queried) == true)
            ) {
                val packageInfo = if (queried == target) {
                    owner.targetPackageInfo
                } else {
                    virtualRegistry?.findPackageInfo(queried)
                }
                val appInfo = if (queried == target) {
                    owner.targetAppInfo
                } else {
                    virtualRegistry?.findApplicationInfo(queried)
                }
                when {
                    name.startsWith("getPackageInfo") -> packageInfo?.let { return it }
                    name.startsWith("getApplicationInfo") -> appInfo?.let { return it }
                    name.startsWith("getPackageUid") -> {
                        if (queried == target) return Process.myUid()
                        virtualRegistry?.packageUid(queried)?.let { return it }
                    }
                }
            }
            val uid = values.firstOrNull() as? Int
            if (uid != null) {
                val packageForUid = when {
                    uid == Process.myUid() -> target
                    else -> owner.registry?.packageForUid(uid)
                }
                if (packageForUid != null) {
                    when (name) {
                        "getPackagesForUid" -> return arrayOf(packageForUid)
                        "getNameForUid" -> return packageForUid
                    }
                }
            }
            return owner.invokeDelegate(delegate, method, values)
        }

        private fun isHostPackageQuery(name: String): Boolean =
            name.startsWith("getPackageInfo") ||
                name.startsWith("getApplicationInfo") ||
                name.startsWith("getPackageUid") ||
                name.startsWith("getPackageGids") ||
                name.startsWith("getResourcesForApplication") ||
                name == "getLaunchIntentForPackage" ||
                name == "getLeanbackLaunchIntentForPackage" ||
                name == "isPackageAvailable"

        private fun hiddenHostPackageResult(
            returnType: Class<*>,
            name: String
        ): Any? = when {
            returnType == Boolean::class.javaPrimitiveType -> false
            returnType == Int::class.javaPrimitiveType -> -1
            returnType == Long::class.javaPrimitiveType -> -1L
            name.startsWith("getPackageGids") -> IntArray(0)
            else -> null
        }

        private fun routeThroughPackageManager(
            pm: PackageManager,
            name: String,
            args: Array<Any?>
        ): Any? {
            fun intFlags(value: Any?): Int = when (value) {
                is Int -> value
                is Long -> value.toInt()
                else -> 0
            }

            fun viaPm(block: () -> Any?): Any = Routed(owner.viaDelegation { block() })

            // IPackageManager component lookups return null when absent;
            // ApplicationPackageManager turns null into NameNotFoundException.
            // The wrapper throws the checked exception, which an AIDL proxy
            // cannot propagate, so convert it back to null here.
            fun viaPmNullable(block: () -> Any?): Any = Routed(
                owner.viaDelegation {
                    try {
                        block()
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            )

            return when (name) {
                "getPackageInfo" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPmNullable { pm.getPackageInfo(pkg, flags) }
                }
                "getApplicationInfo" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPmNullable { pm.getApplicationInfo(pkg, flags) }
                }
                "getPackageUid" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPm { pm.getPackageUid(pkg, flags) }
                }
                "getInstallSourceInfo" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        return owner.NOT_ROUTED
                    }
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    viaPmNullable { pm.getInstallSourceInfo(pkg) }
                }
                "getActivityInfo", "getReceiverInfo", "getServiceInfo", "getProviderInfo" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    if (isContainerPackage(component.packageName)) {
                        viaPmNullable {
                            componentInfo(pm, name, component, flags)
                        }
                    } else {
                        Routed(owner.viaDelegation {
                            runCatching {
                                componentInfo(pm, name, component, flags)
                            }.getOrNull() ?: findInstalledComponent(
                                pm,
                                name,
                                component,
                                flags
                            )
                        })
                    }
                }
                "resolveIntent" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { pm.resolveActivity(intent, flags) }
                }
                "resolveService" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { pm.resolveService(intent, flags) }
                }
                "queryIntentActivities" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentActivities(intent, flags)) }
                }
                "queryIntentServices" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentServices(intent, flags)) }
                }
                "queryIntentReceivers" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryBroadcastReceivers(intent, flags)) }
                }
                "queryIntentContentProviders" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return owner.NOT_ROUTED
                    if (!isContainerIntent(intent)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentContentProviders(intent, flags)) }
                }
                "resolveContentProvider" -> {
                    val authority = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    // Route every authority through the wrapper under the
                    // delegation guard. Probing it first would recurse through
                    // the host ApplicationPackageManager's own mPM call.
                    viaPm { pm.resolveContentProvider(authority, flags) }
                }
                "getInstalledPackages" -> {
                    val flags = intFlags(args.getOrNull(0))
                    viaPm { slice(pm.getInstalledPackages(flags)) }
                }
                "getInstalledApplications" -> {
                    val flags = intFlags(args.getOrNull(0))
                    viaPm { slice(pm.getInstalledApplications(flags)) }
                }
                "getPackagesHoldingPermissions" -> {
                    val permissions = args.getOrNull(0) as? Array<*> ?: return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    val names = permissions.filterIsInstance<String>().toTypedArray()
                    viaPm { slice(pm.getPackagesHoldingPermissions(names, flags)) }
                }
                "checkSignatures" -> {
                    val first = args.getOrNull(0)
                    val second = args.getOrNull(1)
                    if (first !is String || second !is String) return owner.NOT_ROUTED
                    if (!isContainerPackage(first) && !isContainerPackage(second)) {
                        return owner.NOT_ROUTED
                    }
                    viaPm { pm.checkSignatures(first, second) }
                }
                "getPackageGids" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPmNullable { pm.getPackageGids(pkg, flags) }
                }
                "getApplicationEnabledSetting" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    Routed(
                        owner.componentStates["$pkg/*"]
                            ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                }
                "setApplicationEnabledSetting" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    val state = (args.getOrNull(1) as? Int)
                        ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    owner.componentStates["$pkg/*"] = state
                    Routed(null)
                }
                "getComponentEnabledSetting" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(component.packageName)) return owner.NOT_ROUTED
                    Routed(
                        owner.componentStates[component.flattenToString()]
                            ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                }
                "setComponentEnabledSetting" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(component.packageName)) return owner.NOT_ROUTED
                    val state = (args.getOrNull(1) as? Int)
                        ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    owner.componentStates[component.flattenToString()] = state
                    Routed(null)
                }
                "getInstallerPackageName" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    viaPm { pm.getInstallerPackageName(pkg) }
                }
                "getLaunchIntentSenderForPackage" -> {
                    val pkg = args.getOrNull(0) as? String ?: return owner.NOT_ROUTED
                    if (!isContainerPackage(pkg)) return owner.NOT_ROUTED
                    Routed(null)
                }
                else -> owner.NOT_ROUTED
            }
        }

        private fun isContainerPackage(packageName: String?): Boolean =
            packageName != null &&
                (packageName == owner.targetPackage || owner.registry?.isVirtualPackage(packageName) == true)

        private fun componentInfo(
            pm: PackageManager,
            query: String,
            component: ComponentName,
            flags: Int
        ): Any? = when (query) {
            "getActivityInfo" -> pm.getActivityInfo(component, flags)
            "getReceiverInfo" -> pm.getReceiverInfo(component, flags)
            "getServiceInfo" -> pm.getServiceInfo(component, flags)
            else -> pm.getProviderInfo(component, flags)
        }

        /**
         * Some system package visibility policies expose an installed package
         * and its manifest components via getPackageInfo(), while a direct
         * component lookup still returns null. Recover the same component
         * information from package metadata before returning null to the AIDL
         * caller. Native SDKs frequently invoke this Java lookup through JNI
         * and abort the process if it receives a pending checked exception.
         */
        private fun findInstalledComponent(
            pm: PackageManager,
            query: String,
            component: ComponentName,
            flags: Int
        ): Any? {
            val componentFlag = when (query) {
                "getReceiverInfo" -> PackageManager.GET_RECEIVERS
                "getServiceInfo" -> PackageManager.GET_SERVICES
                "getProviderInfo" -> PackageManager.GET_PROVIDERS
                else -> PackageManager.GET_ACTIVITIES
            }
            val packageInfo = runCatching {
                pm.getPackageInfo(
                    component.packageName,
                    flags or componentFlag
                )
            }.getOrNull() ?: return null
            val className = component.className
            return when (query) {
                "getReceiverInfo" -> packageInfo.receivers
                    .orEmpty()
                    .firstOrNull { it.name == className }
                "getServiceInfo" -> packageInfo.services
                    .orEmpty()
                    .firstOrNull { it.name == className }
                "getProviderInfo" -> packageInfo.providers
                    .orEmpty()
                    .firstOrNull { it.name == className }
                else -> packageInfo.activities
                    .orEmpty()
                    .firstOrNull { it.name == className }
            }
        }

        private fun isContainerIntent(intent: Intent): Boolean {
            val component = intent.component
            if (component != null) return isContainerPackage(component.packageName)
            return isContainerPackage(intent.`package`)
        }

        private fun slice(list: List<*>?): Any? {
            if (list == null) return null
            return runCatching {
                val sliceClass = owner.loadClass("android.content.pm.ParceledListSlice")
                owner.constructor(sliceClass, List::class.java)?.newInstance(list)
            }.onFailure {
                Log.w(owner.TAG, "ParceledListSlice unavailable", it)
            }.getOrNull()
        }
    }
