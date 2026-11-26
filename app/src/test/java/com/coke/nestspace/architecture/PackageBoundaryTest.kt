package com.coke.nestspace.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageBoundaryTest {

    @Test
    fun coreDoesNotDependOnHigherLayers() {
        assertNoForbiddenImports(
            root = sourceRoot().resolve("com/coke/nestspace/core"),
            forbidden = listOf(
                "com.coke.nestspace.agent",
                "com.coke.nestspace.features.assistant",
                "com.coke.nestspace.features.browser",
                "com.coke.nestspace.container",
                "com.coke.nestspace.container.control",
                "com.coke.nestspace.data",
                "com.coke.nestspace.container.engine",
                "com.coke.nestspace.integrations.mcp",
                "com.coke.nestspace.plugin",
                "com.coke.nestspace.features"
            )
        )
    }

    @Test
    fun apkInputStagerDoesNotDependOnUiOrAgent() {
        assertNoForbiddenImports(
            root = sourceRoot().resolve("com/coke/nestspace/container/engine/install"),
            forbidden = listOf(
                "com.coke.nestspace.agent",
                "com.coke.nestspace.features.assistant",
                "com.coke.nestspace.features.browser",
                "com.coke.nestspace.container.control",
                "com.coke.nestspace.integrations.mcp",
                "com.coke.nestspace.plugin",
                "com.coke.nestspace.features"
            )
        )
    }

    @Test
    fun agentSdkPackageDoesNotDependOnHostOrAndroid() {
        val root = sourceRoot().resolve("com/coke/nestspace/agent/sdk")
        assertTrue("Missing source root: $root", root.isDirectory)
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { line ->
                        line.startsWith("import android.") ||
                            line.startsWith("import androidx.") ||
                            (
                                line.startsWith("import com.coke.nestspace.") &&
                                    !line.startsWith(
                                        "import com.coke.nestspace.agent.sdk"
                                    )
                                )
                    }
                    .map { "${file.relativeTo(sourceRoot())}: $it" }
            }
            .toList()
        assertTrue(
            "Agent SDK must stay independent from host packages:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun assertNoForbiddenImports(root: File, forbidden: List<String>) {
        assertTrue("Missing source root: $root", root.isDirectory)
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { line -> line.startsWith("import ") }
                    .filter { line ->
                        forbidden.any { prefix -> line.startsWith("import $prefix") }
                    }
                    .map { "${file.relativeTo(sourceRoot())}: $it" }
            }
            .toList()
        assertTrue("Forbidden package imports:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun sourceRoot(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate app source root from ${File(".").absolutePath}")
}
