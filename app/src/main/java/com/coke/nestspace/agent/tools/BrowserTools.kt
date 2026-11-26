package com.coke.nestspace.agent.tools

import android.content.Context
import android.webkit.WebView
import com.coke.nestspace.features.browser.BrowserSession
import com.coke.nestspace.container.control.LivePreview
import com.coke.nestspace.agent.task.OperationTaskRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Agent-facing operations for the built-in multi-tab browser.
 *
 * browser_open creates a new headless tab by default, so the assistant can
 * keep several pages alive (compare results, read one while another loads)
 * without covering the chat. Every page tool accepts tab_id; browser_tabs
 * lists them, browser_switch_tab selects one and browser_close_tab releases it.
 */
internal object BrowserTools {

    internal fun tabIdArg(args: JSONObject): String? =
        args.optString("tab_id").takeIf { it.isNotBlank() }

    internal fun tabById(id: String?): BrowserSession.Tab? =
        id?.let { wanted -> BrowserSession.snapshot().firstOrNull { it.id == wanted } }

    internal fun currentTab(args: JSONObject): BrowserSession.Tab? =
        tabById(tabIdArg(args))
            ?: BrowserSession.snapshot().firstOrNull { it.active }
            ?: BrowserSession.snapshot().firstOrNull { it.selected }
            ?: BrowserSession.snapshot().firstOrNull()

    internal fun tabJson(tab: BrowserSession.Tab): JSONObject = JSONObject()
        .put("tab_id", tab.id)
        .put("url", tab.url)
        .put("title", tab.title)
        .put("mode", BrowserSession.modeFor(tab.id))
        .put("loaded", tab.loaded)
        .put("loading", tab.loading)
        .put("selected", tab.selected)
        .put("active", tab.active)
        .put("can_go_back", tab.canGoBack)
        .put("can_go_forward", tab.canGoForward)

    internal fun tabsJson(): JSONArray = JSONArray().apply {
        BrowserSession.snapshot().forEach { put(tabJson(it)) }
    }

    internal fun downloadResult(
        download: BrowserSession.DownloadState,
        checks: Int,
        maxChecks: Int,
        intervalMs: Long,
        timedOut: Boolean = false
    ): ToolResult {
        val percent = download.progress?.let {
            (it * 100f).toInt().coerceIn(0, 100)
        } ?: -1
        val terminal = download.state == "done" ||
            download.state == "failed" ||
            download.state == "cancelled"
        val text = JSONObject()
            .put("state", download.state)
            .put("terminal", terminal)
            .put("downloaded", download.state == "done")
            .put("download_id", download.id)
            .put("task_id", download.taskId.orEmpty())
            .put("url", download.url)
            .put("name", download.name)
            .put("path", download.path.orEmpty())
            .put("uri", download.uri.orEmpty())
            .put("size", download.size)
            .put("downloaded_bytes", download.downloadedBytes)
            .put("total_bytes", download.totalBytes)
            .put("progress_percent", percent)
            .put("checks", checks)
            .put("max_checks", maxChecks)
            .put("interval_ms", intervalMs)
            .put("poll_timed_out", timedOut)
            .put(
                "message",
                when (download.state) {
                    "done" -> "download completed"
                    "failed" -> "download failed"
                    "cancelled" -> "download cancelled"
                    "cancelling" -> "download cancellation in progress"
                    else -> "download is still running; use task_wait with this task_id"
                }
            )
            .put("error", download.error.orEmpty())
            .toString()
        return when (download.state) {
            "done" -> ToolResult(true, text)
            "failed" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_FAILED"
            )
            "cancelled" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_CANCELLED"
            )
            "cancelling" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_CANCELLING"
            )
            else -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_IN_PROGRESS"
            )
        }
    }

    internal fun startedDownloadResult(
        download: BrowserSession.DownloadState,
        started: Boolean
    ): ToolResult {
        val failed = download.state == "failed"
        val terminal = download.state == "done" ||
            failed ||
            download.state == "cancelled"
        return ToolResult(
            !failed && download.state != "cancelled",
            JSONObject()
                .put("started", started)
                .put("attached", !started)
                .put("state", download.state)
                .put("terminal", terminal)
                .put("download_id", download.id)
                .put("task_id", download.taskId.orEmpty())
                .put("url", download.url)
                .put("name", download.name)
                .put(
                    "next",
                    if (download.state == "downloading" || download.state == "cancelling") {
                        "Use task_wait with this task_id. Do not report completion until " +
                            "terminal=true and state=succeeded."
                    } else {
                        "Inspect state before continuing."
                    }
                )
                .put("error", download.error.orEmpty())
                .toString(),
            errorCode = if (failed) "DOWNLOAD_FAILED" else null
        )
    }

    internal fun startedTaskResult(
        task: OperationTaskRegistry.Snapshot,
        started: Boolean,
        errorCode: String? = null
    ): ToolResult = ToolResult(
        errorCode == null &&
            (!task.terminal || task.state == OperationTaskRegistry.State.SUCCEEDED),
        JSONObject()
            .put("started", started)
            .put("attached", !started)
            .put("state", task.state.api)
            .put("terminal", task.terminal)
            .put("task_id", task.id)
            .put("request_id", task.requestId ?: JSONObject.NULL)
            .put("state_revision", task.stateRevision)
            .put("progress_revision", task.progressRevision)
            .put("message", task.message)
            .put("result", task.result ?: "")
            .put("error", task.error ?: "")
            .put("cleanup_error", task.cleanupError ?: "")
            .put("next", "Use task_wait until terminal=true.")
            .toString(),
        errorCode = errorCode
    )

    /**
     * Publish the page the assistant is working on so the live preview card
     * can render a small chrome-less WebView of it.
     */
    internal fun publishPreview(tabId: String? = null) {
        val tab = tabById(tabId)
            ?: BrowserSession.snapshot().firstOrNull { it.active }
            ?: BrowserSession.snapshot().firstOrNull()
            ?: return
        LivePreview.publishBrowser(
            url = tab.url,
            title = tab.title,
            label = tab.title.ifBlank { tab.url }
        )
    }

    internal fun noTabResult(args: JSONObject): ToolResult {
        val id = tabIdArg(args)
        return if (id != null && tabById(id) == null) {
            ToolResult(false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND")
        } else {
            ToolResult(false, "browser is not open", errorCode = "BROWSER_NOT_OPEN")
        }
    }

fun create(context: Context): List<ToolSpec> =
    browserNavigationToolSpecs(context) +
        browserDownloadToolSpecs(context) +
        browserReadToolSpecs(context)

    internal suspend fun readText(webView: WebView): String =
        evaluate(webView, "(function(){return document.body ? document.body.innerText : '';})()")

    internal data class BrowserChallenge(val reason: String, val evidence: String)

    /**
     * Conservative anti-bot detection: interactive widgets or strong block
     * wording on a short page. Long articles that merely mention captchas do
     * not trigger the handoff hint.
     */
    internal suspend fun detectChallenge(webView: WebView): BrowserChallenge? {
        val raw = evaluate(webView, CHALLENGE_SCRIPT)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val signals = root.optJSONArray("signals").stringList()
        val words = root.optJSONArray("words").stringList()
        val url = root.optString("url").lowercase()
        val title = root.optString("title").lowercase()
        val textLength = root.optInt("text_length", 0)
        val captchaWord = words.any { CAPTCHA_WORDS.contains(it) }
        val blockedWord = words.any { BLOCKED_WORDS.contains(it) }
        val captchaUrl = CAPTCHA_URL_HINTS.any { url.contains(it) }
        val blockedUrl = BLOCKED_URL_HINTS.any { url.contains(it) }
        val titleHit = (CAPTCHA_WORDS + BLOCKED_WORDS).any { title.contains(it) }
        val shortPage = textLength in 1..1_500
        val textEvidence = (captchaWord || blockedWord) && (shortPage || titleHit)
        if (signals.isEmpty() && !textEvidence && !captchaUrl && !blockedUrl) return null
        val reason = if (signals.isNotEmpty() || captchaWord || captchaUrl) {
            "captcha"
        } else {
            "blocked"
        }
        val evidence = buildList {
            addAll(signals)
            addAll(words)
            if (captchaUrl || blockedUrl) add("url")
        }.distinct().joinToString(", ").take(240)
        return BrowserChallenge(reason, evidence)
    }

    internal fun JSONObject.annotateChallenge(
        challenge: BrowserChallenge?,
        tabId: String?
    ): JSONObject {
        if (challenge == null) return this
        val target = tabId?.let { " with tab_id=$it" }.orEmpty()
        return put("needs_user", true)
            .put("user_reason", challenge.reason)
            .put(
                "challenge",
                JSONObject()
                    .put("reason", challenge.reason)
                    .put("evidence", challenge.evidence)
            )
            .put(
                "hint",
                "This page looks like an anti-bot or verification page. Call " +
                    "browser_hand_to_user$target to let the user complete it; " +
                    "do not try to bypass it."
            )
    }

    private fun JSONArray?.stringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private const val CHALLENGE_SCRIPT = """
        (function(){
          function visible(el){
            if(!el) return false;
            var r=el.getBoundingClientRect();
            if(r.width<=0 || r.height<=0) return false;
            var s=window.getComputedStyle(el);
            return s.display!=='none' && s.visibility!=='hidden' && s.opacity!=='0';
          }
          function shown(sel){
            try{
              var list=document.querySelectorAll(sel);
              for(var i=0;i<list.length;i++){ if(visible(list[i])) return true; }
            }catch(e){}
            return false;
          }
          var signals=[];
          if(window._cf_chl_opt || shown('#challenge-form') || shown('.cf-turnstile') ||
             shown('#cf-please-wait')) signals.push('cloudflare');
          if(shown('iframe[src*="recaptcha"]') || shown('.g-recaptcha') ||
             shown('iframe[src*="hcaptcha"]') || shown('.h-captcha')) signals.push('recaptcha');
          if(shown('.geetest_panel') || shown('.geetest_holder') ||
             shown('#nc_1_wrapper') || shown('[class*="nc_wrapper"]') ||
             shown('[id*="captcha"]') || shown('[class*="captcha"]') ||
             shown('img[src*="captcha"]')) signals.push('captcha-element');
          var text=((document.body&&document.body.innerText)||'')
            .replace(/\s+/g,' ').slice(0,4000);
          var hay=(text+' '+(document.title||'')+' '+(location.href||'')).toLowerCase();
          var checks=[
            '验证码','安全验证','人机验证','滑动验证','拖动滑块','完成验证','请完成验证','身份验证',
            '环境异常','异常流量','访问过于频繁','请求过于频繁','操作过于频繁','访问受限','拒绝访问',
            'captcha','verify you are human','are you a robot','human verification','unusual traffic',
            'access denied','too many requests','checking your browser','just a moment','security check'
          ];
          var words=[];
          for(var i=0;i<checks.length;i++){ if(hay.indexOf(checks[i])>=0) words.push(checks[i]); }
          return JSON.stringify({
            signals:signals,
            words:words,
            title:document.title||'',
            url:location.href||'',
            text_length:text.length
          });
        })()
    """

    private val CAPTCHA_WORDS = listOf(
        "验证码", "安全验证", "人机验证", "滑动验证", "拖动滑块", "完成验证",
        "请完成验证", "身份验证", "captcha", "verify you are human",
        "are you a robot", "human verification", "checking your browser",
        "just a moment", "security check"
    )

    private val BLOCKED_WORDS = listOf(
        "环境异常", "异常流量", "访问过于频繁", "请求过于频繁", "操作过于频繁",
        "访问受限", "拒绝访问", "unusual traffic", "access denied",
        "too many requests"
    )

    private val CAPTCHA_URL_HINTS = listOf(
        "captcha", "challenge", "/verify", "verify?", "antispider",
        "punish", "_security_check"
    )

    private val BLOCKED_URL_HINTS = listOf(
        "/sorry/", "403", "412", "blocked", "rate-limit", "too-many-requests"
    )

    internal suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                if (!continuation.isActive) return@evaluateJavascript
                val decoded = runCatching {
                    JSONTokener(value).nextValue() as? String ?: value
                }.getOrDefault(value)
                continuation.resume(decoded)
            }
        }

    internal fun resolveUrl(base: String?, input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        if (base.isNullOrBlank()) return input
        return runCatching {
            java.net.URI(base).resolve(input).toString()
        }.getOrDefault(input)
    }

    internal fun requestHash(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
