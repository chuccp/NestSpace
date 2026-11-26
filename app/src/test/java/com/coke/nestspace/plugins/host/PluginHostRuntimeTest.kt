package com.coke.nestspace.plugins.host

import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginCommandCall
import com.coke.nestspace.plugin.PluginCommandPlugins
import com.coke.nestspace.plugin.PluginCommandProvider
import com.coke.nestspace.plugin.PluginCommandResult
import com.coke.nestspace.plugin.PluginCommandSpec
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginEvent
import com.coke.nestspace.plugin.PluginEventListener
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginPageSpec
import com.coke.nestspace.plugin.PluginRendererSpec
import com.coke.nestspace.plugin.PluginTaskBody
import com.coke.nestspace.plugin.PluginTaskRequest
import com.coke.nestspace.plugin.PluginUiPlugins
import com.coke.nestspace.plugin.PluginUiProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PluginHostRuntimeTest {

    private val pluginId = "test.host.runtime"

    @After
    fun cleanup() {
        PluginCommandPlugins.uninstall(pluginId)
        PluginUiPlugins.uninstall(pluginId)
        PluginLlmProviders.uninstallProvider("test-provider")
    }

    @Test
    fun managedTaskReportsProgressAndCompletes() {
        val tasks = AndroidPluginTasks(pluginId)
        try {
            val handle = tasks.submit(
                PluginTaskRequest("index", 5_000),
                PluginTaskBody { context ->
                    context.reportProgress(60, "half")
                    context.checkCancelled()
                }
            )
            val result = handle.getResult().get(3, TimeUnit.SECONDS)
            assertTrue(result.isSuccessful)
            assertEquals(60, handle.getProgressPercent())
            assertEquals("half", handle.getProgressMessage())
        } finally {
            tasks.closeAll()
        }
    }

    @Test
    fun pluginEventsAreNamespacedAndDelivered() {
        val events = AndroidPluginEvents(pluginId)
        val received = AtomicReference<PluginEvent>()
        val latch = CountDownLatch(1)
        val subscription = events.subscribe(
            setOf("agent.run.completed"),
            PluginEventListener {
                received.set(it)
                latch.countDown()
            }
        )
        try {
            events.publish(
                PluginEvent(
                    "agent.run.completed",
                    "spoofed",
                    "{\"ok\":true}",
                    1
                )
            ).get(2, TimeUnit.SECONDS)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(pluginId, received.get().source)
            assertEquals("agent.run.completed", received.get().type)
        } finally {
            subscription.close()
            events.close()
        }
    }

    @Test
    fun commandAndUiRegistriesAreExposedByHostServices() {
        PluginCommandPlugins.install(
            object : PluginCommandProvider {
                override fun getDescriptor(): PluginDescriptor =
                    descriptor(
                        EnumSet.of(PluginCapability.COMMANDS)
                    )

                override fun listCommandsAsync():
                    CompletableFuture<List<PluginCommandSpec>> =
                    CompletableFuture.completedFuture(
                        listOf(
                            PluginCommandSpec(
                                "refresh",
                                "Refresh",
                                "",
                                "{\"type\":\"object\"}"
                            )
                        )
                    )

                override fun invokeCommandAsync(
                    call: PluginCommandCall
                ): CompletableFuture<PluginCommandResult> =
                    CompletableFuture.completedFuture(
                        PluginCommandResult.success("refreshed", "{}")
                    )
            }
        )
        val commandApi = AndroidPluginCommands()
        val command = commandApi.listAsync().get(2, TimeUnit.SECONDS).single()
        assertEquals("refresh", command.id.substringAfter('/'))
        assertEquals(
            "refreshed",
            commandApi.invokeAsync(command.id, "{}")
                .get(2, TimeUnit.SECONDS)
                .message
        )

        val reads = AtomicInteger()
        PluginUiPlugins.install(
            object : PluginUiProvider {
                override fun getDescriptor(): PluginDescriptor =
                    descriptor(
                        EnumSet.of(
                            PluginCapability.UI_PAGES,
                            PluginCapability.UI_RENDERERS
                        )
                    )

                override fun listPagesAsync():
                    CompletableFuture<List<PluginPageSpec>> =
                    CompletableFuture.completedFuture(
                        listOf(
                            PluginPageSpec(
                                "dashboard",
                                "Dashboard",
                                "",
                                "pages/dashboard.json"
                            )
                        )
                    )

                override fun listRenderersAsync():
                    CompletableFuture<List<PluginRendererSpec>> =
                    CompletableFuture.completedFuture(
                        listOf(
                            PluginRendererSpec(
                                "chart",
                                "Chart",
                                listOf("chart.v1")
                            )
                        )
                    )

                override fun readPageAsync(
                    pageId: String
                ): CompletableFuture<String> {
                    reads.incrementAndGet()
                    return CompletableFuture.completedFuture(
                        "{\"type\":\"page\"}"
                    )
                }
            }
        )
        val ui = AndroidPluginUi()
        assertEquals(1, ui.listPagesAsync().get(2, TimeUnit.SECONDS).size)
        assertEquals(1, ui.listRenderersAsync().get(2, TimeUnit.SECONDS).size)
        assertTrue(
            ui.readPageAsync(pluginId, "dashboard")
                .get(2, TimeUnit.SECONDS)
                .contains("page")
        )
        assertEquals(1, reads.get())
    }

    private fun descriptor(
        capabilities: Set<PluginCapability>
    ): PluginDescriptor = PluginDescriptor(
        pluginId,
        "Runtime test",
        "1",
        PluginDescriptor.API_VERSION,
        0,
        capabilities
    )
}
