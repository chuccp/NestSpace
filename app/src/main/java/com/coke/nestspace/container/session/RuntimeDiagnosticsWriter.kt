package com.coke.nestspace.container.session

import com.coke.nestspace.container.ContainerSession
import android.util.Log
import com.coke.nestspace.container.identity.ContainerIdentity
import com.coke.nestspace.container.runtime.AndroidStartupCoordinator
import com.coke.nestspace.container.runtime.EnvironmentValidation
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.RuntimeDiagnostics
import com.coke.nestspace.container.runtime.VirtualEnvironment
import com.coke.nestspace.container.runtime.VirtualEnvironmentRegistry
import java.io.File

internal class RuntimeDiagnosticsWriter(
    private val virtualEnvironment: VirtualEnvironment,
    private val environmentValidation: EnvironmentValidation,
    private val startupCoordinator: AndroidStartupCoordinator,
    private val isHeadless: () -> Boolean
) {
    fun write() {
        runCatching {
            val expectedPathProbe = File(virtualEnvironment.dataRoot, "files").absolutePath
            val pathMapProbe = NativeEnvironmentBridge.rewritePathForDiagnostics(
                "/data/user/0/${virtualEnvironment.packageName}/files"
            )?.takeIf { it == expectedPathProbe }
            val apkPathMapProbe = virtualEnvironment.virtualApkPaths.firstOrNull()
                ?.let(NativeEnvironmentBridge::rewritePathForDiagnostics)
                ?.takeIf { it == virtualEnvironment.apkPaths.firstOrNull() }
            val externalPathMapProbe = virtualEnvironment.externalDataRoot
                ?.let { externalRoot ->
                    val expected = File(externalRoot, "files").absolutePath
                    NativeEnvironmentBridge.rewritePathForDiagnostics(
                        "/storage/emulated/0/Android/data/" +
                            "${virtualEnvironment.packageName}/files"
                    )?.takeIf { it == expected }
                }
            val expectedUid = virtualEnvironment.observedUid.toString()
            val procViewProbe = NativeEnvironmentBridge.readVirtualProcForDiagnostics(
                "/proc/self/status"
            )?.takeIf { value ->
                value.lineSequence().any {
                    it.startsWith("Uid:") &&
                        it.removePrefix("Uid:").trim().split(Regex("\\s+"))
                            .all { field -> field == expectedUid }
                } && value.contains("Name:")
            }
            val propertyValueProbe = NativeEnvironmentBridge
                .readPropertyForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val propertyCallbackProbe = NativeEnvironmentBridge
                .readPropertyCallbackForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val snapshot = RuntimeDiagnostics.build(
                environment = virtualEnvironment,
                identity = VirtualEnvironmentRegistry.identitySnapshot(),
                validation = environmentValidation,
                startup = startupCoordinator.report(),
                native = NativeEnvironmentBridge.snapshot(),
                probe = NativeEnvironmentBridge.probe(),
                processUid = ContainerIdentity.hostUid(),
                processPid = android.os.Process.myPid(),
                headless = isHeadless(),
                pathMapProbe = pathMapProbe,
                apkPathMapProbe = apkPathMapProbe,
                externalPathMapProbe = externalPathMapProbe,
                procViewProbe = procViewProbe,
                propertyValueProbe = propertyValueProbe,
                propertyCallbackProbe = propertyCallbackProbe
            )
            val runtimeDir = File(virtualEnvironment.dataRoot, ".nestspace").apply { mkdirs() }
            val fileName = RuntimeDiagnostics.fileName(
                virtualEnvironment.packageName,
                virtualEnvironment.processName
            )
            val destination = File(runtimeDir, fileName)
            val temporary = File(runtimeDir, "$fileName.tmp")
            temporary.writeText(snapshot.toString(2))
            if (!temporary.renameTo(destination)) {
                destination.delete()
                check(temporary.renameTo(destination)) {
                    "cannot publish runtime diagnostics"
                }
            }
            Log.d(
                TAG,
                "runtime diagnostics written capabilities=" +
                    "${virtualEnvironment.capabilities.size} " +
                    "startupMs=${startupCoordinator.report().totalDurationMillis}"
            )
        }.onFailure {
            Log.w(TAG, "write runtime diagnostics failed", it)
        }
    }

    private companion object {
        const val TAG = "ContainerSession"
    }
}
