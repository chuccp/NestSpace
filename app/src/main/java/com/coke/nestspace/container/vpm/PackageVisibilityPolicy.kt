package com.coke.nestspace.container.vpm

import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.util.Log
import org.xmlpull.v1.XmlPullParser

/**
 * Package visibility declared by the target APK's <queries> element.
 *
 * The host process always sees the host PackageManager's complete visibility
 * set. This policy keeps implicit external Intent results and non-system
 * third-party package lookups aligned with the target package instead of the
 * host package.
 */
internal class PackageVisibilityPolicy private constructor(
    private val packages: Set<String>,
    private val authorities: Set<String>,
    private val intents: List<IntentFilter>
) {
    fun allowsPackage(packageName: String?): Boolean =
        packageName != null && packageName in packages

    fun allowsAuthority(authority: String?): Boolean =
        authority != null && authority in authorities

    fun allowsIntent(intent: Intent): Boolean {
        if (packages.isEmpty() && authorities.isEmpty() && intents.isEmpty()) {
            return false
        }
        val type = intent.type
        val scheme = intent.scheme
        val data = intent.data
        val categories = intent.categories.orEmpty()
        return intents.any { filter ->
            filter.match(
                intent.action,
                type,
                scheme,
                data,
                categories,
                TAG
            ) >= 0
        }
    }

    companion object {
        private const val TAG = "ContainerVisibility"
        private const val ANDROID_NS =
            "http://schemas.android.com/apk/res/android"

        fun load(apkPath: String): PackageVisibilityPolicy = runCatching {
            val packages = linkedSetOf<String>()
            val authorities = linkedSetOf<String>()
            val intents = mutableListOf<IntentFilter>()
            val assets = AssetManager::class.java
                .getDeclaredConstructor()
                .newInstance()
            AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)
                .invoke(assets, apkPath)
            assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
                var queryDepth = -1
                var currentIntent: IntentFilter? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "queries" -> queryDepth = parser.depth
                                "package" -> if (queryDepth >= 0 &&
                                    parser.depth == queryDepth + 1
                                ) {
                                    parser.getAttributeValue(
                                        ANDROID_NS,
                                        "name"
                                    )?.takeIf(String::isNotBlank)?.let(packages::add)
                                }
                                "provider" -> if (queryDepth >= 0 &&
                                    parser.depth == queryDepth + 1
                                ) {
                                    parser.getAttributeValue(
                                        ANDROID_NS,
                                        "authorities"
                                    )?.split(';')?.filter(String::isNotBlank)
                                        ?.let(authorities::addAll)
                                }
                                "intent" -> if (queryDepth >= 0 &&
                                    parser.depth == queryDepth + 1
                                ) {
                                    currentIntent = IntentFilter()
                                }
                                "action" -> parser.getAttributeValue(
                                    ANDROID_NS,
                                    "name"
                                )?.let { currentIntent?.addAction(it) }
                                "category" -> parser.getAttributeValue(
                                    ANDROID_NS,
                                    "name"
                                )?.let { currentIntent?.addCategory(it) }
                                "data" -> applyData(parser, currentIntent)
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "intent" && currentIntent != null) {
                                intents += currentIntent
                                currentIntent = null
                            }
                            if (parser.name == "queries") queryDepth = -1
                        }
                    }
                    event = parser.next()
                }
            }
            PackageVisibilityPolicy(packages, authorities, intents)
        }.onFailure {
            Log.w(TAG, "read package visibility failed: $apkPath", it)
        }.getOrDefault(
            PackageVisibilityPolicy(emptySet(), emptySet(), emptyList())
        )

        private fun applyData(
            parser: XmlPullParser,
            filter: IntentFilter?
        ) {
            filter ?: return
            val scheme = parser.getAttributeValue(ANDROID_NS, "scheme")
            val host = parser.getAttributeValue(ANDROID_NS, "host")
            val port = parser.getAttributeValue(ANDROID_NS, "port")
            val path = parser.getAttributeValue(ANDROID_NS, "path")
            val pathPrefix = parser.getAttributeValue(
                ANDROID_NS,
                "pathPrefix"
            )
            val pathPattern = parser.getAttributeValue(
                ANDROID_NS,
                "pathPattern"
            )
            val mimeType = parser.getAttributeValue(ANDROID_NS, "mimeType")
            if (!scheme.isNullOrBlank()) filter.addDataScheme(scheme)
            if (!host.isNullOrBlank()) {
                filter.addDataAuthority(host, port)
            }
            if (!path.isNullOrBlank()) filter.addDataPath(path, 0)
            if (!pathPrefix.isNullOrBlank()) filter.addDataPath(pathPrefix, 1)
            if (!pathPattern.isNullOrBlank()) filter.addDataPath(pathPattern, 2)
            if (!mimeType.isNullOrBlank()) {
                runCatching { filter.addDataType(mimeType) }
            }
        }
    }
}
