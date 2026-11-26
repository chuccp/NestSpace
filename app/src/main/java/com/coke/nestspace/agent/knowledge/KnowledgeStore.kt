package com.coke.nestspace.agent.knowledge

import android.content.Context
import android.util.Log
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeEntry
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeIndex
import com.coke.nestspace.agent.sdk.knowledge.KnowledgeMarkdownParser
import org.json.JSONObject

/**
 * Loads bundled app/site guides from assets and maps business tool calls to a
 * provider-neutral [KnowledgeIndex].
 */
class KnowledgeStore private constructor(entries: List<KnowledgeEntry>) {
    private val index = KnowledgeIndex(entries)

    fun all(): List<KnowledgeEntry> = index.all()

    fun forPackage(packageName: String): KnowledgeEntry? =
        index.forPackage(packageName)

    fun forHost(host: String): KnowledgeEntry? = index.forHost(host)

    fun forTool(
        toolName: String,
        args: JSONObject,
        resultText: String
    ): KnowledgeEntry? = when (toolName) {
        "space_launch", "space_multi_open" ->
            args.optString("package")
                .takeIf { it.isNotBlank() }
                ?.let(::forPackage)

        "ui_observe" -> {
            val fromArgs = args.optString("package").takeIf { it.isNotBlank() }
            val fromResult = runCatching {
                JSONObject(resultText).optString("package")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            (fromArgs ?: fromResult)?.let(::forPackage)
        }

        "browser_open", "browser_navigate" -> {
            val fromResult = runCatching {
                JSONObject(resultText).optString("url")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val url = fromResult ?: args.optString("url")
            hostOf(url)?.let(::forHost)
        }

        else -> null
    }

    private fun hostOf(url: String): String? {
        val value = url.trim()
        if (value.isEmpty()) return null
        runCatching { java.net.URI(value).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return runCatching { android.net.Uri.parse(value).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "KnowledgeStore"
        private const val APPS_DIR = "knowledge/apps"
        private const val SITES_DIR = "knowledge/sites"
        private const val APP_FILE = "APP.md"
        private const val SITE_FILE = "SITE.md"

        @Volatile
        private var cached: KnowledgeStore? = null

        fun get(context: Context): KnowledgeStore {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: load(context.applicationContext).also { cached = it }
            }
        }

        fun load(context: Context): KnowledgeStore {
            val entries = mutableListOf<KnowledgeEntry>()
            readKind(context, APPS_DIR, APP_FILE, KnowledgeEntry.Kind.APP, entries)
            readKind(context, SITES_DIR, SITE_FILE, KnowledgeEntry.Kind.SITE, entries)
            return KnowledgeStore(entries)
        }

        fun of(entries: List<KnowledgeEntry>): KnowledgeStore =
            KnowledgeStore(entries)

        fun parse(
            kind: KnowledgeEntry.Kind,
            dirName: String,
            text: String
        ): KnowledgeEntry? = KnowledgeMarkdownParser.parse(kind, dirName, text)

        private fun readKind(
            context: Context,
            dir: String,
            fileName: String,
            kind: KnowledgeEntry.Kind,
            out: MutableList<KnowledgeEntry>
        ) {
            val names = runCatching {
                context.assets.list(dir)?.sorted().orEmpty()
            }.getOrElse {
                Log.w(TAG, "Cannot list $dir", it)
                emptyList()
            }
            for (name in names) {
                val text = runCatching {
                    context.assets.open("$dir/$name/$fileName")
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrElse {
                    Log.w(TAG, "Cannot read $dir/$name/$fileName", it)
                    continue
                }
                val entry = parse(kind, name, text)
                if (entry == null) {
                    Log.w(TAG, "Ignoring invalid guide $dir/$name/$fileName")
                } else {
                    out += entry
                }
            }
        }
    }
}
