package com.coke.nestspace.knowledge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgePackTest {

    private val root: File = listOf(
        File("src/main/assets/knowledge"),
        File("app/src/main/assets/knowledge")
    ).firstOrNull { it.isDirectory }
        ?: error("knowledge assets not found from ${File(".").absolutePath}")

    @Test
    fun appGuidesParse() {
        val entries = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        assertTrue("expected app guides", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("blank display in ${entry.name}", entry.display.isNotBlank())
            assertTrue("no package in ${entry.name}", entry.packages.isNotEmpty())
            assertTrue("body too long in ${entry.name}", entry.body.length <= 4_000)
        }
    }

    @Test
    fun siteGuidesParse() {
        val entries = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        assertTrue("expected site guides", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("blank display in ${entry.name}", entry.display.isNotBlank())
            assertTrue("no domain in ${entry.name}", entry.domains.isNotEmpty())
            assertTrue("directory name missing from domains: ${entry.name}", entry.name in entry.domains)
            assertTrue("body too long in ${entry.name}", entry.body.length <= 4_000)
        }
    }

    @Test
    fun packagesAndDomainsAreUnique() {
        val apps = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        val sites = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        val packages = apps.flatMap { it.packages }
        assertEquals("duplicate package", packages.size, packages.distinct().size)
        val domains = sites.flatMap { it.domains }
        assertEquals("duplicate domain", domains.size, domains.distinct().size)
    }

    @Test
    fun guidesDoNotContainLocalPaths() {
        val all = read(KnowledgeEntry.Kind.APP, "apps", "APP.md") +
            read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        for (entry in all) {
            assertTrue("local path in ${entry.name}", !entry.body.contains("/Users/"))
        }
    }

    @Test
    fun matchesByPackageAndMostSpecificHost() {
        val appEntries = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        val siteEntries = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        val store = KnowledgeStore.of(
            appEntries + siteEntries
        )
        val app = appEntries.first { it.packages.isNotEmpty() }
        val site = siteEntries.first { it.domains.isNotEmpty() }

        assertNotNull(store.forPackage(app.packages.first()))
        assertNull(store.forPackage("com.example.unknown"))
        assertNotNull(store.forHost(site.domains.first()))

        val broad = KnowledgeEntry(
            KnowledgeEntry.Kind.SITE,
            "example",
            "Example",
            emptyList(),
            listOf("example.com"),
            emptyList(),
            "broad"
        )
        val specific = broad.copy(
            name = "search-example",
            display = "Search Example",
            domains = listOf("search.example.com"),
            body = "specific"
        )
        val specificityStore = KnowledgeStore.of(listOf(broad, specific))
        assertEquals(
            specific.name,
            specificityStore.forHost("search.example.com")?.name
        )
        assertEquals(
            broad.name,
            specificityStore.forHost("www.example.com")?.name
        )
        assertNull(store.forHost("example.com"))
    }

    @Test
    fun matchesToolCallsAndInjectsGuideOnce() {
        val appEntries = read(KnowledgeEntry.Kind.APP, "apps", "APP.md")
        val siteEntries = read(KnowledgeEntry.Kind.SITE, "sites", "SITE.md")
        val store = KnowledgeStore.of(appEntries + siteEntries)
        val appEntry = appEntries.first { it.packages.isNotEmpty() }
        val siteEntry = siteEntries.first { it.domains.isNotEmpty() }

        val app = store.forTool(
            "space_launch",
            JSONObject().put("package", appEntry.packages.first()),
            "{}"
        )
        assertNotNull(app)

        val url = "https://${siteEntry.domains.first()}/"
        val site = store.forTool(
            "browser_open",
            JSONObject().put("url", url),
            JSONObject().put("url", url).toString()
        )
        assertEquals(siteEntry.name, site?.name)

        val withGuide = JSONObject(site!!.withGuide("{\"opened\":true}"))
        assertTrue(withGuide.getBoolean("opened"))
        assertTrue(withGuide.getString("guide").contains("NestSpace guide"))
    }

    private fun read(kind: KnowledgeEntry.Kind, dir: String, fileName: String): List<KnowledgeEntry> {
        val base = File(root, dir)
        assertTrue("missing $base", base.isDirectory)
        return base.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .map { sub ->
                val text = File(sub, fileName)
                assertTrue("missing $text", text.isFile)
                requireNotNull(KnowledgeStore.parse(kind, sub.name, text.readText())) {
                    "cannot parse $text"
                }
            }
    }
}
