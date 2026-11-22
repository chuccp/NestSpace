package com.coke.nestspace.container.loader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDexBridgeTest {
    private val dataRoot = File("/data/user/0/com.example.target")

    @Test
    fun acceptsAnonymousAndMemfdMappings() {
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("[anon:dalvik-DEX data]", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/memfd:jit-cache (deleted)", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("[heap]", dataRoot))
    }

    @Test
    fun acceptsAnyReadableFileWithinInstanceData() {
        assertTrue(
            ProcessDexBridge.isDexCaptureMappingCandidate(
                "${dataRoot.absolutePath}/files/runtime-payload.bin",
                dataRoot
            )
        )
    }

    @Test
    fun acceptsStandardAndroidCodeContainers() {
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/data/app/base.apk", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/data/local/tmp/runtime.dex", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/data/local/tmp/runtime.jar", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/data/local/tmp/runtime.zip", dataRoot))
        assertTrue(ProcessDexBridge.isDexCaptureMappingCandidate("/data/app/base.apk (deleted)", dataRoot))
    }

    @Test
    fun rejectsUnrelatedNativeMappings() {
        assertFalse(
            ProcessDexBridge.isDexCaptureMappingCandidate(
                "/apex/com.android.runtime/lib64/libart.so",
                dataRoot
            )
        )
        assertFalse(
            ProcessDexBridge.isDexCaptureMappingCandidate(
                "${dataRoot.absolutePath}-other/files/runtime.bin",
                dataRoot
            )
        )
    }
}
