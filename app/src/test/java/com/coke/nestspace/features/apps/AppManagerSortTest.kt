package com.coke.nestspace.features.apps

import com.coke.nestspace.data.VirtualApp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppManagerSortTest {
    @Test
    fun installedTimeSortsNewestFirst() {
        val apps = listOf(
            app("old", "Old app", 100L),
            app("new", "New app", 300L),
            app("middle", "Middle app", 200L)
        )

        assertEquals(
            listOf("new", "middle", "old"),
            sortVirtualApps(apps, AppSortMode.INSTALLED_TIME)
                .map { it.packageName }
        )
    }

    @Test
    fun alphabeticalSortIgnoresCaseAndIsStableByPackage() {
        val apps = listOf(
            app("z", "zebra", 400L),
            app("a2", "Alpha", 200L),
            app("a1", "alpha", 300L)
        )

        assertEquals(
            listOf("a1", "a2", "z"),
            sortVirtualApps(apps, AppSortMode.ALPHABETICAL)
                .map { it.packageName }
        )
    }

    private fun app(
        packageName: String,
        label: String,
        installedAt: Long
    ) = VirtualApp(
        spaceId = "space",
        packageName = packageName,
        label = label,
        versionName = "1.0",
        versionCode = 1L,
        launcherActivity = ".MainActivity",
        signatureSha256 = "",
        hasNativeLibs = false,
        installedAt = installedAt,
        updatedAt = installedAt,
        instances = emptyList()
    )
}
