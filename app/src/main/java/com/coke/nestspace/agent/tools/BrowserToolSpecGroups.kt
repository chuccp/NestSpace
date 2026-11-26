package com.coke.nestspace.agent.tools

import android.content.Context
import android.net.Uri
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.features.browser.BrowserSession
import androidx.core.content.FileProvider
import com.coke.nestspace.container.control.LivePreview
import com.coke.nestspace.core.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun BrowserTools.browserNavigationToolSpecs(context: Context): List<ToolSpec> = listOf(
        ToolSpec(
            name = "browser_open",
            description = "Open a URL in the built-in browser. It creates a NEW tab by default and " +
                "runs headless (no browser window), so several pages can stay open at once. " +
                "Pass tab_id to navigate an existing tab, or new_tab=false to reuse the active " +
                "tab. Set headless=false to show the browser UI. Non-URL text becomes a Bing search.",
            inputSchema = toolSchema(
                "url" to "string",
                "headless" to "boolean",
                "new_tab" to "boolean",
                "tab_id" to "string",
                "wait_ms" to "integer",
                required = listOf("url")
            ),
            maxWaitMs = 1_500L
        ) { args, ctx ->
            val url = BrowserSession.normalizeInput(args.optString("url"))
            val headless = args.optBoolean("headless", true)
            val newTab = args.optBoolean("new_tab", true)
            val requestedTab = tabIdArg(args)
            val waitMs = args.optInt("wait_ms", 15_000).coerceIn(0, 60_000)
            val opened = runCatching {
                withContext(Dispatchers.Main) {
                    BrowserSession.open(
                        context = context,
                        url = url,
                        headless = headless,
                        newTab = newTab,
                        tabId = requestedTab,
                        ownerId = ctx.ownerId
                    )
                }
            }.getOrElse { error ->
                val code = (error as? BrowserSession.BrowserException)?.code
                    ?: "BROWSER_OPEN_FAILED"
                return@ToolSpec ToolResult(
                    false,
                    error.message ?: error.javaClass.simpleName,
                    errorCode = code
                )
            }
            if (waitMs > 0) {
                BrowserSession.awaitPageLoad(opened, waitMs.toLong())
            }
            val tab = tabById(opened)
            val challenge = withContext(Dispatchers.Main) {
                BrowserSession.webView(opened)?.let { detectChallenge(it) }
            }
            publishPreview(opened)
            ToolResult(
                true,
                JSONObject()
                    .put("opened", true)
                    .put("tab_id", opened)
                    .put("tab_count", BrowserSession.tabCount())
                    .put("mode", BrowserSession.modeFor(opened))
                    .put("loaded", tab?.loaded ?: false)
                    .put("url", tab?.url ?: url)
                    .put("title", tab?.title.orEmpty())
                    .put("tabs", tabsJson())
                    .annotateChallenge(challenge, opened)
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_hand_to_user",
            description = "Hand a browser tab to the user when a page shows a captcha, human " +
                "verification, login wall, or blocked/anti-bot page. Asks the user to confirm, " +
                "then opens the visible browser on that tab, waits while the user completes the " +
                "step, then returns the page state. " +
                "Page tools add needs_user/user_reason when they detect such a page; call this " +
                "instead of trying to bypass the check. Call browser_read_text afterwards to verify.",
            inputSchema = toolSchema(
                "tab_id" to "string",
                "reason" to "string",
                "message" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
                ?: BrowserSession.activeTabId()
                ?: BrowserSession.selectedTabId()
                ?: return@ToolSpec ToolResult(
                    false,
                    "browser is not open",
                    errorCode = "BROWSER_NOT_OPEN"
                )
            if (tabById(id) == null) {
                return@ToolSpec ToolResult(
                    false,
                    "tab not found: $id",
                    errorCode = "BROWSER_TAB_NOT_FOUND"
                )
            }
            val detected = withContext(Dispatchers.Main) {
                BrowserSession.webView(id)?.let { detectChallenge(it) }
            }
            val reason = args.optString("reason").trim().lowercase()
                .ifBlank { detected?.reason ?: "verification" }
            val title = when (reason) {
                "captcha", "verification" ->
                    AppLanguage.tr("需要你完成验证", "Verification required")
                "blocked" -> AppLanguage.tr("需要你处理网站拦截", "Website needs your action")
                "login" -> AppLanguage.tr("需要你完成登录", "Login required")
                else -> AppLanguage.tr("需要你操作网页", "Your action is needed")
            }
            val detail = args.optString("message").takeIf { it.isNotBlank() }
                ?: AppLanguage.tr(
                    "浏览器已停在这个页面。请在页面中完成验证或操作，然后点“完成”。",
                    "The browser is on this page. Complete the check or action there, then tap Done."
                )
            val result = UserGateManager.takeOver(
                context = context,
                title = title,
                detail = detail,
                packageName = "",
                instanceId = "",
                slot = -1,
                reason = reason,
                target = UserGateManager.TakeoverTarget.BROWSER,
                tabId = id,
                url = tabById(id)?.url,
                bringToFront = {
                    withContext(Dispatchers.Main) {
                        BrowserSession.showTab(context, id)
                    }
                }
            )
            when (result) {
                UserGateManager.TakeoverResult.COMPLETED -> {
                    val tab = tabById(id)
                    val remaining = withContext(Dispatchers.Main) {
                        BrowserSession.webView(id)?.let { detectChallenge(it) }
                    }
                    val text = withContext(Dispatchers.Main) {
                        BrowserSession.webView(id)?.let { readText(it) }
                    }.orEmpty()
                    ToolResult(
                        true,
                        JSONObject()
                            .put("takeover", "completed")
                            .put("reason", reason)
                            .put("tab_id", id)
                            .put("url", tab?.url.orEmpty())
                            .put("title", tab?.title.orEmpty())
                            .put("text", text.take(2_000))
                            .put("still_blocked", remaining != null)
                            .annotateChallenge(remaining, id)
                            .toString()
                    )
                }
                UserGateManager.TakeoverResult.SKIPPED -> ToolResult(
                    false,
                    "The user skipped the browser takeover.",
                    errorCode = "TAKEOVER_SKIPPED"
                )
                UserGateManager.TakeoverResult.CANCELLED -> ToolResult(
                    false,
                    "The browser is not reachable for takeover.",
                    errorCode = "TAKEOVER_CANCELLED"
                )
                UserGateManager.TakeoverResult.TIMEOUT -> ToolResult(
                    false,
                    "Timed out waiting for the user to finish.",
                    errorCode = "TAKEOVER_TIMEOUT"
                )
            }
        },
        ToolSpec(
            name = "browser_navigate",
            description = "Navigate a browser tab to a URL or Bing search. Defaults to the active tab; " +
                "pass tab_id to target another open page.",
            inputSchema = toolSchema(
                "url" to "string",
                "tab_id" to "string",
                required = listOf("url")
            ),
            maxWaitMs = 1_500L
        ) { args, _ ->
            val url = BrowserSession.normalizeInput(args.optString("url"))
            val tabId = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.navigate(url, tabId) }
            if (ok) publishPreview(tabId)
            ToolResult(
                ok,
                if (ok) {
                    JSONObject()
                        .put("navigated", true)
                        .put("tab_id", tabId ?: BrowserSession.activeTabId())
                        .put("url", url)
                        .toString()
                } else {
                    "browser tab is not open"
                },
                errorCode = if (ok) null else "BROWSER_NOT_OPEN"
            )
        },
        ToolSpec(
            name = "browser_status",
            description = "Return the active browser tab and every open tab (id, URL, title, mode, " +
                "navigation state).",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val tab = currentTab(args) ?: return@ToolSpec noTabResult(args)
            publishPreview(tab.id)
            ToolResult(
                true,
                JSONObject()
                    .put("open", true)
                    .put("mode", BrowserSession.currentMode())
                    .put("active_tab_id", BrowserSession.activeTabId() ?: JSONObject.NULL)
                    .put("selected_tab_id", BrowserSession.selectedTabId() ?: JSONObject.NULL)
                    .put("tab", tabJson(tab))
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_tabs",
            description = "List all open browser tabs with their tab_id, URL, title and state.",
            inputSchema = toolSchema()
        ) { _, _ ->
            publishPreview()
            ToolResult(
                true,
                JSONObject()
                    .put("tabs", tabsJson())
                    .put("active_tab_id", BrowserSession.activeTabId() ?: JSONObject.NULL)
                    .put("selected_tab_id", BrowserSession.selectedTabId() ?: JSONObject.NULL)
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_switch_tab",
            description = "Make an open tab active and, when the browser UI is visible, show it.",
            inputSchema = toolSchema(
                "tab_id" to "string",
                required = listOf("tab_id")
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            if (id == null) return@ToolSpec ToolResult(
                false, "tab_id is required", errorCode = "INVALID_ARGUMENT"
            )
            val ok = withContext(Dispatchers.Main) { BrowserSession.selectTab(id) }
            if (!ok) return@ToolSpec ToolResult(
                false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND"
            )
            val tab = tabById(id)
            publishPreview(id)
            ToolResult(
                true,
                JSONObject()
                    .put("switched", true)
                    .put("tab", tab?.let(::tabJson) ?: JSONObject.NULL)
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_close_tab",
            description = "Close a browser tab. Defaults to the selected tab. Returns the remaining tabs.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args) ?: BrowserSession.selectedTabId()
                ?: BrowserSession.activeTabId()
                ?: return@ToolSpec ToolResult(
                    false, "browser is not open", errorCode = "BROWSER_NOT_OPEN"
                )
            val ok = withContext(Dispatchers.Main) { BrowserSession.closeTab(id) }
            if (!ok) return@ToolSpec ToolResult(
                false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND"
            )
            if (BrowserSession.tabCount() == 0) {
                if (LivePreview.frame.value?.kind == LivePreview.KIND_BROWSER) {
                    LivePreview.clear()
                }
            } else {
                publishPreview()
            }
            ToolResult(
                true,
                JSONObject()
                    .put("closed", id)
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_back",
            description = "Go back in a browser tab's history. Defaults to the active tab; pass tab_id " +
                "to target another page.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.back(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser went back" else "browser cannot go back",
                errorCode = if (ok) null else "BROWSER_BACK_FAILED"
            )
        },
        ToolSpec(
            name = "browser_forward",
            description = "Go forward in a browser tab's history. Defaults to the active tab; pass tab_id " +
                "to target another page.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.forward(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser went forward" else "browser cannot go forward",
                errorCode = if (ok) null else "BROWSER_FORWARD_FAILED"
            )
        },
        ToolSpec(
            name = "browser_reload",
            description = "Reload a browser tab. Defaults to the active tab; pass tab_id to target " +
                "another page.",
            inputSchema = toolSchema("tab_id" to "string"),
            maxWaitMs = 1_500L
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.reload(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser reloaded" else "browser is not open",
                errorCode = if (ok) null else "BROWSER_NOT_OPEN"
            )
        },
        ToolSpec(
            name = "browser_links",
            description = "List links, buttons and image-buttons on a page. Each item includes a CSS " +
                "selector that can be passed to browser_click. Defaults to the active tab; pass " +
                "tab_id to inspect another page.",
            inputSchema = toolSchema(
                "max_links" to "integer",
                "tab_id" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                val limit = args.optInt("max_links", 80).coerceIn(1, 300)
                val script = """
                    (function(){
                      var out=[],seen={};
                      function add(n,kind){
                        var text=(n.innerText||n.value||n.getAttribute('aria-label')||n.getAttribute('alt')||'')
                          .replace(/\s+/g,' ').trim();
                        var url=n.href||'';
                        var selector='';
                        if(n.id) selector='#'+n.id;
                        else if(n.classList && n.classList.length) selector='.'+n.classList[0];
                        var key=kind+'|'+url+'|'+selector+'|'+text;
                        if(seen[key]) return;
                        seen[key]=1;
                        out.push({kind:kind,text:text.slice(0,160),url:url,selector:selector});
                      }
                      var links=document.querySelectorAll('a[href]');
                      for(var i=0;i<links.length && out.length<$limit;i++) add(links[i],'link');
                      var clickable=document.querySelectorAll(
                        'button,[role=button],input[type=submit],img[data-log],img[class*=Btn],img[class*=btn]'
                      );
                      for(var j=0;j<clickable.length && out.length<$limit;j++) add(clickable[j],'button');
                      return JSON.stringify(out);
                    })()
                """.trimIndent()
                val raw = evaluate(webView, script)
                val links = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
                val challenge = detectChallenge(webView)
                publishPreview(id)
                ToolResult(
                    true,
                    JSONObject()
                        .put("tab_id", id ?: BrowserSession.activeTabId())
                        .put("mode", BrowserSession.modeFor(id))
                        .put("url", webView.url.orEmpty())
                        .put("links", links)
                        .annotateChallenge(challenge, id ?: BrowserSession.activeTabId())
                        .toString()
                )
            }
        },
        ToolSpec(
            name = "browser_click",
            description = "Click an element by its visible text or by a CSS selector. Image-only " +
                "download buttons often need a selector from browser_links. Defaults to the active " +
                "tab; pass tab_id to click in another page.",
            inputSchema = toolSchema(
                "text" to "string",
                "selector" to "string",
                "tab_id" to "string"
            )
        ) { args, ctx ->
            val text = args.optString("text").trim()
            val selector = args.optString("selector").trim()
            if (text.isBlank() && selector.isBlank()) return@ToolSpec ToolResult(
                false,
                "text or selector is required",
                errorCode = "INVALID_ARGUMENT"
            )
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                BrowserSession.clearDownload(ctx.ownerId)
                BrowserSession.setDownloadOwner(
                    id ?: BrowserSession.activeTabId(),
                    ctx.ownerId
                )
                val needle = if (text.isBlank()) "null" else JSONObject.quote(text)
                val sel = if (selector.isBlank()) "null" else JSONObject.quote(selector)
                val script = """
                    (function(){
                      var needle=$needle, sel=$sel;
                      var best=null,bestText='',bestScore=1e18;
                      if (sel) {
                        best=document.querySelector(sel);
                        if (best) bestText=(best.innerText||best.value||best.getAttribute('aria-label')||best.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
                      } else {
                        var nodes=document.querySelectorAll(
                          'a,button,[role=button],input[type=submit],[onclick],div,span,p,li,img'
                        );
                        for (var i=0;i<nodes.length;i++) {
                          var n=nodes[i];
                          var t=(n.innerText||n.value||n.getAttribute('aria-label')||n.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
                          if (t.indexOf(needle)<0) continue;
                          var r=n.getBoundingClientRect();
                          var visible=(r.width>0 && r.height>0) ? 0 : 1e9;
                          var score=visible + t.length*1000 + (r.width*r.height)/1000;
                          if (score<bestScore) { best=n; bestText=t; bestScore=score; }
                        }
                      }
                      if (!best) return JSON.stringify({clicked:false});
                      best.scrollIntoView({block:'center'});
                      best.click();
                      return JSON.stringify({
                        clicked:true,
                        text:bestText.slice(0,160),
                        url:best.href||'',
                        selector:sel||''
                      });
                    })()
                """.trimIndent()
                val raw = evaluate(webView, script)
                val clicked = runCatching { JSONObject(raw) }
                    .getOrDefault(JSONObject().put("clicked", false))
                clicked.put("tab_id", id ?: BrowserSession.activeTabId())
                if (clicked.optBoolean("clicked", false)) publishPreview(id)
                ToolResult(
                    clicked.optBoolean("clicked", false),
                    clicked.toString(),
                    errorCode = if (clicked.optBoolean("clicked", false)) null else "CLICK_NOT_FOUND"
                )
            }
        },
    )

internal fun BrowserTools.browserDownloadToolSpecs(context: Context): List<ToolSpec> = listOf(
        ToolSpec(
            name = "browser_download",
            description = "Start a file download from a URL and return its download_id immediately. " +
                "Use task_wait with task_id until terminal=true. " +
                "If url is omitted, attach to the page-triggered download started by browser_click. " +
                "Starting a new download cancels only the previous download owned by the same " +
                "assistant session.",
            inputSchema = toolSchema(
                "url" to "string",
                "tab_id" to "string",
                "request_id" to "string"
            ),
            nativeAsync = true
        ) { args, ctx ->
            val input = args.optString("url").trim()
            if (input.isBlank()) {
                val state = BrowserSession.latestDownload(ctx.ownerId)
                    ?: return@ToolSpec ToolResult(
                        false,
                        "no browser download has started",
                        errorCode = "DOWNLOAD_NOT_FOUND"
                    )
                return@ToolSpec startedDownloadResult(
                    download = state,
                    started = false
                )
            }
            val id = tabIdArg(args)
            val currentUrl = withContext(Dispatchers.Main) {
                BrowserSession.webView(id)?.url
            }
            val url = resolveUrl(currentUrl, input)
            val requestId = args.optString("request_id")
                .trim()
                .takeIf(String::isNotBlank)
            if (ctx.caller != Caller.USER &&
                ToolPermissionSettings.shouldConfirm(
                    context,
                    ToolConfirmationCategory.DOWNLOAD
                )
            ) {
                val allowed = UserGateManager.askConfirmation(
                    title = AppLanguage.tr("需要确认", "Confirmation needed"),
                    detail = AppLanguage.tr(
                        "下载这个文件？\n$url",
                        "Download this file?\n$url"
                    )
                )
                if (!allowed) {
                    return@ToolSpec ToolResult(
                        false,
                        AppLanguage.tr("用户拒绝了这次下载", "The user denied this download"),
                        errorCode = "USER_DENIED"
                    )
                }
            }
            val started = BrowserSession.enqueueDownload(
                context = context,
                url = url,
                ownerId = ctx.ownerId,
                requestId = requestId,
                requestHash = requestHash(url),
                parentTaskId = ctx.parentTaskId
            )
            when {
                started.errorCode != null -> ToolResult(
                    false,
                    startedTaskResult(
                        task = started.task,
                        started = false,
                        errorCode = started.errorCode
                    ).text,
                    errorCode = started.errorCode
                )
                started.download != null -> startedDownloadResult(
                    download = started.download,
                    started = started.created
                )
                else -> startedTaskResult(
                    task = started.task,
                    started = started.created
                )
            }
        },
        ToolSpec(
            name = "browser_download_status",
            description = "Read one browser download snapshot by download_id for detailed file and " +
                "byte information. Use task_wait for lifecycle waiting. State=downloading is not " +
                "success; state=done is complete and " +
                "state=failed is terminal failure.",
            inputSchema = toolSchema(
                "download_id" to "string",
                required = listOf("download_id")
            )
        ) { args, ctx ->
            val downloadId = args.optString("download_id").trim()
            if (downloadId.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "download_id is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            val state = BrowserSession.downloadStatus(
                id = downloadId,
                ownerId = ctx.ownerId
            ) ?: return@ToolSpec ToolResult(
                false,
                "download not found: $downloadId",
                errorCode = "DOWNLOAD_NOT_FOUND"
            )
            downloadResult(
                download = state,
                checks = 1,
                maxChecks = 1,
                intervalMs = 0L
            )
        },
        ToolSpec(
            name = "browser_upload",
            description = "Complete a pending browser file-upload chooser with a local file. " +
                "First tap or click the page's upload control so its file chooser opens, then " +
                "pass either path (absolute file path) or uri (content:// or file://).",
            inputSchema = toolSchema(
                "path" to "string",
                "uri" to "string"
            ),
            mutating = true
        ) { args, ctx ->
            val rawPath = args.optString("path").trim()
            val rawUri = args.optString("uri").trim()
            if (rawPath.isBlank() && rawUri.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "path or uri is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            if (!BrowserSession.hasPendingFileChooser()) {
                return@ToolSpec ToolResult(
                    false,
                    "No pending file chooser. Tap or click the page's upload button first.",
                    errorCode = "NO_FILE_CHOOSER"
                )
            }
            val uri = if (rawUri.isNotBlank()) {
                Uri.parse(rawUri)
            } else {
                val path = if (rawPath.startsWith("file://")) {
                    Uri.parse(rawPath).path.orEmpty()
                } else {
                    rawPath
                }
                val file = File(path)
                if (!file.isFile || !file.canRead()) {
                    return@ToolSpec ToolResult(
                        false,
                        "file is missing or not readable: $path",
                        errorCode = "FILE_NOT_FOUND"
                    )
                }
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }.getOrElse {
                    return@ToolSpec ToolResult(
                        false,
                        "cannot expose file to the browser: ${it.message}",
                        errorCode = "UPLOAD_FAILED"
                    )
                }
            }
            val readable = runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } == true
            }.getOrDefault(false)
            if (!readable) {
                return@ToolSpec ToolResult(
                    false,
                    "cannot read upload URI: $uri",
                    errorCode = "FILE_NOT_FOUND"
                )
            }
            if (ctx.caller != Caller.USER &&
                ToolPermissionSettings.shouldConfirm(
                    context,
                    ToolConfirmationCategory.RISKY
                )
            ) {
                val allowed = UserGateManager.askConfirmation(
                    title = AppLanguage.tr("上传文件？", "Upload a file?"),
                    detail = AppLanguage.tr(
                        "即将把文件上传到当前网页：${uri.lastPathSegment ?: uri}",
                        "Upload this file to the current page: ${uri.lastPathSegment ?: uri}"
                    )
                )
                if (!allowed) {
                    return@ToolSpec ToolResult(
                        false,
                        AppLanguage.tr("用户拒绝了这次上传", "The user denied this upload"),
                        errorCode = "USER_DENIED"
                    )
                }
            }
            val delivered = withContext(Dispatchers.Main) {
                BrowserSession.deliverUpload(uri)
            }
            if (!delivered) {
                return@ToolSpec ToolResult(
                    false,
                    "The file chooser is no longer pending.",
                    errorCode = "NO_FILE_CHOOSER"
                )
            }
            ToolResult(
                true,
                JSONObject()
                    .put("uploaded", true)
                    .put("uri", uri.toString())
                    .toString()
            )
        },
    )

internal fun BrowserTools.browserReadToolSpecs(context: Context): List<ToolSpec> = listOf(
        ToolSpec(
            name = "browser_read_text",
            description = "Read the visible text of a browser tab. Defaults to the active tab; pass " +
                "tab_id to read another page. Useful after browser_open to let the model inspect a page.",
            inputSchema = toolSchema(
                "max_chars" to "integer",
                "tab_id" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                val text = readText(webView)
                val limit = args.optInt("max_chars", 20_000).coerceIn(500, 100_000)
                val challenge = detectChallenge(webView)
                publishPreview(id)
                ToolResult(
                    true,
                    JSONObject()
                        .put("tab_id", id ?: BrowserSession.activeTabId())
                        .put("mode", BrowserSession.modeFor(id))
                        .put("url", webView.url.orEmpty())
                        .put("title", webView.title.orEmpty())
                        .put("text", text.take(limit))
                        .annotateChallenge(challenge, id ?: BrowserSession.activeTabId())
                        .toString()
                )
            }
        }
    )
