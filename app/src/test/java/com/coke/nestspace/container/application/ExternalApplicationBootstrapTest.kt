package com.coke.nestspace.container.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalApplicationBootstrapTest {

    @Test
    fun externalApplicationDetectionUsesPackageBoundary() {
        assertTrue(
            ExternalApplicationBootstrap.isExternalApplication(
                "vendor.runtime.WrapperApplication",
                "com.example.target"
            )
        )
        assertFalse(
            ExternalApplicationBootstrap.isExternalApplication(
                "com.example.target.Application",
                "com.example.target"
            )
        )
        assertFalse(
            ExternalApplicationBootstrap.isExternalApplication(
                null,
                "com.example.target"
            )
        )
    }

    @Test
    fun runtimeLibrariesAreDiscoveredFromApkAssets() {
        val dataRoot = Files.createTempDirectory("nestspace-runtime-").toFile()
        val apk = File(dataRoot, "target.apk")
        val payload = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/runtime/libentry.so"))
            zip.write(payload)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libnormal.so"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val libraries = RuntimeNativeLibraries.prepare(
            listOf(apk.absolutePath),
            dataRoot
        )

        val extracted = File(libraries.root, "libentry.so")
        assertTrue(extracted.isFile)
        assertEquals(payload.toList(), extracted.readBytes().toList())
        assertFalse(File(libraries.root, "libnormal.so").exists())
        assertTrue(libraries.files.contains(extracted))
    }
}
