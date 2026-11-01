package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptInstallSourceType
import com.ai.assistance.operit.util.AppLogger
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val WEBVIEW_SUPPORT_TAG = "BrowserSessionTools"

internal fun StandardBrowserSessionTools.createSessionOnMain(
    appContext: Context,
    sessionId: String,
    sessionName: String?,
    customUserAgent: String?
): BrowserToolSession {
    val webView = WebView(resolveWebViewContext(appContext))
    val session =
        BrowserToolSession(
            id = sessionId,
            webView = webView,
            sessionName = sessionName,
            customUserAgent = customUserAgent
        )
    if (StandardBrowserSessionTools.desktopModeEnabled) {
        session.viewportWidthPx = StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_WIDTH
        session.viewportHeightPx = StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_HEIGHT
    }
    configureWebView(session, resolveUserAgent(customUserAgent))
    userscriptManager.attachSession(session.id, session.webView)
    return session
}

internal fun StandardBrowserSessionTools.resolveWebViewContext(fallbackContext: Context): Context {
    val currentActivity = ActivityLifecycleManager.getCurrentActivity()
    return if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
        currentActivity
    } else {
        fallbackContext
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun StandardBrowserSessionTools.configureWebView(
    session: BrowserToolSession,
    userAgent: String
) {
    with(session.webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = true
        allowContentAccess = true
        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        setGeolocationEnabled(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                safeBrowsingEnabled = true
            } catch (e: Throwable) {
                AppLogger.w(WEBVIEW_SUPPORT_TAG, "Failed to enable safe browsing: ${e.message}")
            }
        }
    }
    applySessionUserAgent(session, userAgent)
    configureCookiePolicy(session.webView)
    // 内嵌反检测：DOCUMENT_START_SCRIPT 在页面脚本执行前注入指纹补丁，不依赖外部浏览器
    BrowserStealthSupport.attach(session.webView, session.id)

    session.webView.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = false
        isHapticFeedbackEnabled = false
        contentDescription = context.getString(R.string.web_session_accessibility_web_content)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.isLongClickable = false
                    view.isHapticFeedbackEnabled = false
                    if (!view.hasFocus()) {
                        view.requestFocus()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        addJavascriptInterface(BrowserWebDownloadBridge(this@configureWebView, session), "OperitWebDownloadBridge")
        addJavascriptInterface(BrowserAsyncBridge(), "OperitAsyncBridge")
        addJavascriptInterface(BrowserTextSelectionBridge(), "OperitTextSelectionBridge")
        setDownloadListener(createDownloadListener(session))
        setOnLongClickListener { true }
        isLongClickable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isScreenReaderFocusable = true
        }
    }

    session.webView.webChromeClient =
        object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popupSession = runCatching { createPopupSessionOnMain(session) }.getOrNull() ?: return false
                transport.webView = popupSession.webView
                message.sendToTarget()
                refreshSessionUiOnMain(popupSession.id)
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                val popupSession = window?.let(::findSessionByWebView)
                if (popupSession != null) {
                    closeSession(popupSession.id)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                session.pageTitle = title.orEmpty()
                refreshSessionUiOnMain(session.id)
                ioScope.launch {
                    historyStore.updateTitle(session.currentUrl, session.pageTitle)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    appendConsoleEntry(
                        session,
                        BrowserConsoleEntry(
                            level = consoleMessage.messageLevel().name.lowercase(Locale.ROOT),
                            message = consoleMessage.message().orEmpty(),
                            sourceId = consoleMessage.sourceId(),
                            lineNumber = consoleMessage.lineNumber()
                        )
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) {
                    return false
                }

                session.pendingFileChooserCallback?.onReceiveValue(null)
                session.pendingFileChooserCallback = filePathCallback
                session.lastFileChooserRequestAt = System.currentTimeMillis()
                notifySessionStateChanged(session)

                AppLogger.d(
                    WEBVIEW_SUPPORT_TAG,
                    "Captured file chooser request for session=${session.id}, " +
                        "mode=${fileChooserParams?.mode}, multiple=${fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE}"
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) {
                    return
                }
                handleWebPermissionRequest(request)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin.isNullOrBlank() || callback == null) {
                    callback?.invoke(origin.orEmpty(), false, false)
                    return
                }
                handleGeolocationPermissionRequest(origin, callback)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                session.pendingDialog =
                    PendingDialog(
                        type = "alert",
                        message = message.orEmpty(),
                        url = url,
                        jsResult = result
                    )
                notifySessionStateChanged(session)
                refreshSessionUiOnMain(session.id)
                AppLogger.d(WEBVIEW_SUPPORT_TAG, "web_session js alert pending: ${message.orEmpty()}")
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                session.pendingDialog =
                    PendingDialog(
                        type = "confirm",
                        message = message.orEmpty(),
                        url = url,
                        jsResult = result
                    )
                notifySessionStateChanged(session)
                refreshSessionUiOnMain(session.id)
                AppLogger.d(WEBVIEW_SUPPORT_TAG, "web_session js confirm pending: ${message.orEmpty()}")
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                session.pendingDialog =
                    PendingDialog(
                        type = "prompt",
                        message = message.orEmpty(),
                        defaultValue = defaultValue,
                        url = url,
                        jsPromptResult = result
                    )
                notifySessionStateChanged(session)
                refreshSessionUiOnMain(session.id)
                AppLogger.d(WEBVIEW_SUPPORT_TAG, "web_session js prompt pending: ${message.orEmpty()}")
                return true
            }
        }

    session.webView.webViewClient =
        object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                session.currentUrl = url
                session.pageLoaded = false
                session.isLoading = true
                session.hasSslError = false
                session.lastSnapshot = null
                clearEventLogs(session)
                session.pendingDialog = null
                notifySessionStateChanged(session)
                userscriptManager.onPageChanged(session.id, url, forceReset = true)
                syncNavigationStateUi(session)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                session.currentUrl = url
                session.lastSnapshot = null
                notifySessionStateChanged(session)
                userscriptManager.onPageChanged(session.id, url)
                refreshNavigationStateFromWebView(view, session)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                session.currentUrl = url
                userscriptManager.onPageChanged(session.id, url)
                session.pageTitle = view.title ?: ""
                session.pageLoaded = true
                session.isLoading = false
                notifySessionStateChanged(session)
                applyViewportOverride(session)
                refreshNavigationStateFromWebView(view, session)
                injectDownloadHelper(view)
                injectTextSelectionHelper(view)
                ioScope.launch {
                    historyStore.updateTitle(url, session.pageTitle)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "blob") {
                    injectBlobDownloaderScript(session, uri.toString())
                    return true
                }
                if (scheme == "data") {
                    handleInlineDownload(
                        session = session,
                        base64Data = uri.toString(),
                        fileName = "download_${System.currentTimeMillis()}",
                        mimeType = guessMimeTypeFromDataUrl(uri.toString()),
                        type = "data",
                        sourceUrl = uri.toString()
                    )
                    return true
                }
                return handleNavigationOverrideOnMain(session, uri)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                recordNetworkRequest(session, request)
                return userscriptManager.interceptWebRequest(session.id, request)
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                session.currentUrl = url
                val pageTitle = view.title.orEmpty()
                notifySessionStateChanged(session)
                refreshNavigationStateFromWebView(view, session)
                ioScope.launch {
                    historyStore.recordVisit(url, pageTitle, isReload)
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                AppLogger.w(
                    WEBVIEW_SUPPORT_TAG,
                    "web_session SSL error, proceeding anyway. " +
                        "session=${session.id}, url=${error.url}, primaryError=${error.primaryError}"
                )
                session.hasSslError = true
                notifySessionStateChanged(session)
                updateNavigationState(session)
                refreshSessionUiOnMain(session.id)
                handler.proceed()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                AppLogger.e(
                    WEBVIEW_SUPPORT_TAG,
                    "web_session render process gone: session=${session.id}, " +
                        "didCrash=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}"
                )
                session.pageLoaded = false
                session.isLoading = false
                session.lastSnapshot = null
                session.pendingDialog?.jsPromptResult?.cancel()
                session.pendingDialog?.jsResult?.cancel()
                session.pendingDialog = null
                session.pendingFileChooserCallback?.onReceiveValue(null)
                session.pendingFileChooserCallback = null
                notifySessionStateChanged(session)
                closeSession(session.id)
                showToast(
                    if (detail.didCrash()) {
                        context.getString(R.string.web_session_render_process_crashed)
                    } else {
                        context.getString(R.string.web_session_render_process_gone)
                    }
                )
                return true
            }
        }
}

internal fun StandardBrowserSessionTools.ensureOverlayOnMain(
    appContext: Context,
    initialExpanded: Boolean = false
): WebSessionBrowserHost {
    StandardBrowserSessionTools.browserHost?.let { return it }

    synchronized(StandardBrowserSessionTools.overlayLock) {
        StandardBrowserSessionTools.browserHost?.let { return it }

        val host =
            WebSessionBrowserHost(
                appContext = appContext,
                store = historyStore,
                userscriptStore = userscriptManager.uiStore,
                callbacks = createBrowserHostCallbacks(appContext)
            )
        StandardBrowserSessionTools.browserHost = host
        host.ensureCreated(initialExpanded = initialExpanded)
        refreshSessionUiOnMain()
        return host
    }
}

internal fun StandardBrowserSessionTools.createBrowserHostCallbacks(
    appContext: Context
): WebSessionBrowserHost.Callbacks =
    object : WebSessionBrowserHost.Callbacks {
        override fun onNavigate(url: String) {
            runOnMainSync<Unit> {
                openUrlOnMain(appContext, url)
            }
        }

        override fun onBack() {
            runOnMainSync<Unit> {
                val session = getActiveSessionOnMain() ?: return@runOnMainSync
                ensureSessionAttachedOnMain(session.id)
                if (session.webView.canGoBack()) {
                    session.webView.goBack()
                }
                refreshNavigationStateAsync(session)
            }
        }

        override fun onForward() {
            runOnMainSync<Unit> {
                val session = getActiveSessionOnMain() ?: return@runOnMainSync
                ensureSessionAttachedOnMain(session.id)
                if (session.webView.canGoForward()) {
                    session.webView.goForward()
                }
                refreshNavigationStateAsync(session)
            }
        }

        override fun onRefreshOrStop() {
            runOnMainSync<Unit> {
                val session = getActiveSessionOnMain() ?: return@runOnMainSync
                ensureSessionAttachedOnMain(session.id)
                if (session.isLoading) {
                    session.webView.stopLoading()
                    session.isLoading = false
                } else {
                    session.pageLoaded = false
                    session.isLoading = true
                    session.webView.reload()
                }
                refreshNavigationStateAsync(session)
            }
        }

        override fun onSelectTab(sessionId: String) {
            runOnMainSync<Unit> {
                ensureSessionAttachedOnMain(sessionId)
            }
        }

        override fun onCloseTab(sessionId: String) {
            closeSession(sessionId)
        }

        override fun onNewTab() {
            runOnMainSync<Unit> {
                createSessionTabOnMain(appContext, initialUrl = "about:blank")
            }
        }

        override fun onMinimize() {
            runOnMainSync<Unit> {
                setExpandedOnMain(false)
            }
        }

        override fun onCloseCurrentTab() {
            resolvePreferredSessionId()?.let { closeSession(it) }
        }

        override fun onCloseAllTabs() {
            val ids = orderedSessionIds()
            ids.forEach { closeSession(it) }
        }

        override fun onToggleBookmark(url: String, title: String) {
            ioScope.launch {
                historyStore.toggleBookmark(url, title)
            }
        }

        override fun onRemoveBookmark(url: String) {
            ioScope.launch {
                historyStore.removeBookmark(url)
            }
        }

        override fun onSelectSessionHistory(index: Int) {
            runOnMainSync<Unit> {
                val session = getActiveSessionOnMain() ?: return@runOnMainSync
                ensureSessionAttachedOnMain(session.id)
                val historyList = session.webView.copyBackForwardList()
                val delta = index - historyList.currentIndex
                if (delta != 0 && session.webView.canGoBackOrForward(delta)) {
                    session.webView.goBackOrForward(delta)
                    refreshNavigationStateAsync(session)
                }
            }
        }

        override fun onOpenUrl(url: String) {
            runOnMainSync<Unit> {
                openUrlOnMain(appContext, url)
            }
        }

        override fun onClearHistory() {
            ioScope.launch {
                historyStore.clearHistory()
            }
        }

        override fun onToggleDesktopMode() {
            setDesktopModeEnabled(!StandardBrowserSessionTools.desktopModeEnabled)
        }

        override fun onOpenUserscripts() {
            runOnMainSync<Unit> {
                openUserscriptSheetOnMain()
            }
        }

        override fun onImportUserscript() {
            userscriptManager.beginLocalImport()
        }

        override fun onInstallUserscriptFromUrl(url: String) {
            userscriptManager.beginUrlInstall(url)
        }

        override fun onConfirmUserscriptInstall() {
            userscriptManager.confirmPendingInstall()
        }

        override fun onCancelUserscriptInstall() {
            userscriptManager.cancelPendingInstall()
        }

        override fun onSetUserscriptEnabled(scriptId: Long, enabled: Boolean) {
            userscriptManager.setScriptEnabled(scriptId, enabled)
        }

        override fun onDeleteUserscript(scriptId: Long) {
            userscriptManager.deleteScript(scriptId)
        }

        override fun onCheckUserscriptUpdate(scriptId: Long) {
            userscriptManager.checkForUpdate(scriptId)
        }

        override fun onInvokeUserscriptMenu(commandId: String) {
            userscriptManager.invokeMenuCommand(resolvePreferredSessionId(), commandId)
        }

        override fun onPauseDownload(taskId: String) {
            performBrowserDownloadAction(taskId, BrowserDownloadAction.PAUSE)
        }

        override fun onResumeDownload(taskId: String) {
            performBrowserDownloadAction(taskId, BrowserDownloadAction.RESUME)
        }

        override fun onCancelDownload(taskId: String) {
            performBrowserDownloadAction(taskId, BrowserDownloadAction.CANCEL)
        }

        override fun onRetryDownload(taskId: String) {
            performBrowserDownloadAction(taskId, BrowserDownloadAction.RETRY)
        }

        override fun onDeleteDownload(taskId: String, deleteFile: Boolean) {
            performBrowserDownloadDelete(taskId, deleteFile)
        }

        override fun onOpenDownloadedFile(taskId: String) {
            if (!openDownloadedFile(taskId)) {
                showToast(appContext.getString(R.string.web_session_download_open_failed))
            }
        }

        override fun onOpenDownloadLocation(taskId: String) {
            if (!openDownloadLocation(taskId)) {
                showToast(appContext.getString(R.string.web_session_download_location_open_failed))
            }
        }

        override fun onConfirmExternalOpen(requestId: String) {
            runOnMainSync<Unit> {
                confirmExternalOpenRequest(requestId)
            }
        }

        override fun onCancelExternalOpen(requestId: String) {
            runOnMainSync<Unit> {
                cancelExternalOpenRequest(requestId)
            }
        }

        override fun onHandlePendingDialog(accept: Boolean, promptText: String?) {
            runOnMainSync<Unit> {
                val session = getActiveSessionOnMain() ?: return@runOnMainSync
                resolvePendingDialogOnMain(
                    session = session,
                    accept = accept,
                    promptText = promptText
                )
            }
        }
    }

internal fun StandardBrowserSessionTools.destroyOverlayOnMain() {
    StandardBrowserSessionTools.browserHost?.destroy()
    StandardBrowserSessionTools.browserHost = null
    StandardBrowserSessionTools.activeSessionId = null
}

internal fun StandardBrowserSessionTools.setExpandedOnMain(expanded: Boolean) {
    StandardBrowserSessionTools.browserHost?.setExpanded(expanded)
    keepActiveWebViewRunningOnMain(expanded)
    if (expanded) {
        refreshSessionUiOnMain()
    }
}

internal fun StandardBrowserSessionTools.openUserscriptSheetOnMain() {
    ensureOverlayOnMain(context.applicationContext, initialExpanded = true)
    setExpandedOnMain(true)
    StandardBrowserSessionTools.browserHost?.showSheet(WebSessionBrowserSheetRoute.USERSCRIPTS)
    refreshSessionUiOnMain()
}

internal fun StandardBrowserSessionTools.keepActiveWebViewRunningOnMain(expanded: Boolean) {
    val session = getActiveSessionOnMain() ?: return
    try {
        session.webView.onResume()
        session.webView.resumeTimers()
        session.webView.visibility = View.VISIBLE
        session.webView.alpha = 1f
        if (expanded) {
            if (!session.webView.hasFocus()) {
                session.webView.requestFocus()
            }
        } else {
            session.webView.clearFocus()
        }
    } catch (e: Exception) {
        AppLogger.w(WEBVIEW_SUPPORT_TAG, "Failed to keep active WebView running: ${e.message}")
    }
}

internal fun StandardBrowserSessionTools.createSessionTabOnMain(
    appContext: Context,
    initialUrl: String,
    sessionName: String? = null,
    customUserAgent: String? = null
): BrowserToolSession {
    val sessionId = UUID.randomUUID().toString()
    val session = createSessionOnMain(appContext, sessionId, sessionName, customUserAgent)
    StandardBrowserSessionTools.sessions[sessionId] = session
    addSessionOrder(sessionId)
    StandardBrowserSessionTools.activeSessionId = sessionId
    ensureOverlayOnMain(appContext)
    navigateSessionOnMain(session, initialUrl)
    ensureSessionAttachedOnMain(sessionId)
    return session
}

internal fun StandardBrowserSessionTools.openUserscriptTabOnMain(
    appContext: Context,
    url: String,
    active: Boolean
): String {
    val previousActiveId = StandardBrowserSessionTools.activeSessionId
    val newSession = createSessionTabOnMain(appContext, initialUrl = url)
    if (!active && !previousActiveId.isNullOrBlank() && previousActiveId != newSession.id) {
        activateSessionOnMain(previousActiveId)
    }
    return newSession.id
}

internal fun StandardBrowserSessionTools.navigateSessionOnMain(
    session: BrowserToolSession,
    targetUrl: String,
    headers: Map<String, String> = emptyMap()
) {
    session.pageLoaded = false
    session.isLoading = true
    session.currentUrl = targetUrl
    session.hasSslError = false
    session.lastSnapshot = null
    updateNavigationState(session)
    refreshSessionUiOnMain(session.id)
    if (headers.isNotEmpty()) {
        session.webView.loadUrl(targetUrl, headers)
    } else {
        session.webView.loadUrl(targetUrl)
    }
    refreshNavigationStateAsync(session)
}

internal fun StandardBrowserSessionTools.openUrlOnMain(appContext: Context, url: String) {
    val existingSession = getActiveSessionOnMain()
    val session = existingSession ?: createSessionTabOnMain(appContext, initialUrl = url)

    // [UI-NAV-FIX] 2026-11-01 真机第四次复测：
    // 用户在地栏输入地址后界面不跳转，但工具通道 browser:goto 正常。
    // 原因：工具通道在 StandardBrowserSessionTools 里做了引擎分流
    // （BrowserEngineBridge.navigate，CLOAK 走 CDP），
    // 而 UI 这条 openUrlOnMain 只调 navigateSessionOnMain → session.webView.loadUrl()，
    // 是纯 WebView 路径。Cloak 会话根本没有可用的 WebView，
    // loadUrl 打在一个不可见、也没接渲染的 WebView 上，等于什么都没做。
    // 修法：UI 导航同样按引擎分流，CLOAK/CAMOUFOX 走 BrowserEngineBridge。
    val engine = runCatching { session.engine }.getOrNull()
    if (engine != null &&
        engine != com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.WEBVIEW
    ) {
        // [ANR-FIX] 2026-11-02 真机实测定位（ANR 堆栈铁证）：
        //
        // 本方法由 onNavigate 经 runOnMainSync 调用，也就是**跑在主线程上**。
        // 而 BrowserEngineBridge.navigate 内部会 ensureEngine → CloakLauncher.launch
        // → waitPort(30_000)，循环 Thread.sleep(300) 最多 30 秒等端口。
        // 主线程被卡 30 秒 → Input dispatching timed out → ANR。
        // 用户看到的现象就是「输入地址回车后闪退」。
        //
        // 铁证堆栈（data_app_anr@1793571258386）：
        //   "main" prio=5 tid=1 Sleeping
        //     at java.lang.Thread.sleep(Native method)
        //     at CloakLauncher.waitPort(CloakLauncher.kt:202)
        //     at CloakLauncher.launch(CloakLauncher.kt:347)
        //     at BrowserEngineBridge.ensureEngine(BrowserEngineBridge.kt:30)
        //     at BrowserEngineBridge.navigate(BrowserEngineBridge.kt:114)
        //     at openUrlOnMain(BrowserWebViewSupport.kt:775)
        //     at onNavigate(BrowserWebViewSupport.kt:458)
        //
        // 修法：主线程只更新 UI 状态，把真正的引擎启动丢到后台线程。
        // chromium 启动本来就该异步，UI 不该等它。
        session.currentUrl = url
        session.pageLoaded = false
        session.isLoading = true
        session.hasSslError = false
        session.lastSnapshot = null
        updateNavigationState(session)
        refreshSessionUiOnMain(session.id)

        val sessionId = session.id
        BackgroundNavExecutor.submit {
            val routed = runCatching {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge
                    .navigate(appContext, sessionId, engine, url)
            }
            runOnUiThread {
                val s = sessionById(sessionId)
                if (s != null) {
                    if (routed.isSuccess) {
                        s.currentUrl = url
                        s.pageLoaded = true
                        s.isLoading = false
                        updateNavigationState(s)
                        refreshSessionUiOnMain(sessionId)
                    } else {
                        s.isLoading = false
                        updateNavigationState(s)
                        refreshSessionUiOnMain(sessionId)
                        com.ai.assistance.operit.util.AppLogger.w(
                            "BrowserNav",
                            "后台 Cloak 导航失败: ${routed.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
        }
        return
    }

    if (existingSession != null) {
        navigateSessionOnMain(session, url)
    } else if (engine != null &&
        engine != com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngine.WEBVIEW
    ) {
        // [UI-NAV-FIX] 新建 Cloak 标签页时 createSessionTabOnMain 只把 URL 交给 WebView，
        // 而 Cloak 会话不渲染 WebView，因此必须单独向 CDP 发一次导航，
        // 否则新标签会停在 about:blank。
        // [ANR-FIX] 同样必须丢后台：这段也在主线程里，
        // 新标签页首次导航会触发 engine 启动，阻塞 30 秒即 ANR。
        val sessionId = session.id
        BackgroundNavExecutor.submit {
            runCatching {
                com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak.BrowserEngineBridge
                    .navigate(appContext, sessionId, engine, url)
            }.onFailure {
                com.ai.assistance.operit.util.AppLogger.w(
                    "BrowserNav",
                    "新标签 Cloak 导航失败: ${it.message}"
                )
            }
        }
    }
    ensureSessionAttachedOnMain(session.id)
}

internal fun StandardBrowserSessionTools.handleUserscriptDownloadOnMain(
    sessionId: String,
    url: String,
    fileName: String?
) {
    val session = sessionById(sessionId) ?: getActiveSessionOnMain()
    if (session == null) {
        showToast(context.getString(R.string.web_session_userscript_download_failed))
        return
    }
    handleRegularDownload(
        session = session,
        url = url,
        userAgent = session.webView.settings.userAgentString.orEmpty(),
        contentDisposition =
            fileName?.takeIf { it.isNotBlank() }?.let { "attachment; filename=\"$it\"" },
        mimeType = null
    )
}

internal fun StandardBrowserSessionTools.activateSessionOnMain(sessionId: String) {
    val session = sessionById(sessionId) ?: return
    ensureOverlayOnMain(context.applicationContext)
    StandardBrowserSessionTools.activeSessionId = sessionId
    updateNavigationState(session)
    syncProjectedBrowserStateOnMain()
}

internal fun StandardBrowserSessionTools.ensureSessionAttachedOnMain(sessionId: String) {
    val session = sessionById(sessionId) ?: return
    ensureOverlayOnMain(context.applicationContext)
    StandardBrowserSessionTools.activeSessionId = sessionId
    runCatching {
        session.webView.onResume()
        session.webView.resumeTimers()
        session.webView.visibility = View.VISIBLE
        session.webView.alpha = 1f
    }
    updateNavigationState(session)
    syncProjectedBrowserStateOnMain()
}

internal fun StandardBrowserSessionTools.refreshSessionUiOnMain(sessionId: String? = null) {
    sessionId?.let { id ->
        sessionById(id)?.let(::updateNavigationState)
    }
    syncProjectedBrowserStateOnMain()
}

internal fun StandardBrowserSessionTools.syncProjectedBrowserStateOnMain() {
    val registry = buildPageRegistry()
    val resolvedActiveId = registry.activeSessionId
    val activeSession = resolvedActiveId?.let(::sessionById)
    StandardBrowserSessionTools.activeSessionId = resolvedActiveId
    StandardBrowserSessionTools.browserHost?.attachActiveWebView(
        activeSession?.webView,
        activeSession?.viewportWidthPx,
        activeSession?.viewportHeightPx
    )
    activeSession?.let(::applyViewportOverride)
    userscriptManager.updateVisibleSession(
        sessionId = resolvedActiveId,
        pageUrl = activeSession?.currentUrl
    )
    StandardBrowserSessionTools.browserHost?.updateHostProjection(
        browserState = buildBrowserState(registry, buildBrowserDownloadSummary()),
        downloadUiState = buildBrowserDownloadUiState(),
        externalOpenPrompt = StandardBrowserSessionTools.pendingExternalOpenRequest?.toUiState()
    )
}

internal fun StandardBrowserSessionTools.buildBrowserState(
    registry: BrowserPageRegistry,
    downloadSummary: BrowserDownloadSummary
): WebSessionBrowserState {
    val activeId = registry.activeSessionId
    val activeSession = activeId?.let(::sessionById)
    val orderedIds = registry.orderedSessionIds

    // [WHITE-SCREEN-FIX v2] 2026-11-01 真机二次复测定位（决定性证据）：
    // logcat 里 CloakSurface 的「画面流启动」日志从未出现，说明 startLoop 没跑过，
    // 也就是 UI 压根没进 CloakTextureView 分支，仍在走 WebView 分支。
    //
    // 根因是上一版把判定写成 `isCloak && liveCloak != null`，构成一个不可能满足的条件：
    //   · 引擎标记 CLOAK 时，该会话**根本不存在 WebView**（从没创建过）
    //   · liveCloak 为 null（客户端尚未建好）时，判定为 false → 走 WebView 分支
    //   · 于是去渲染一个不存在的 WebView → 正文必然整片白
    // 代码里没有任何地方会把 isCloak 改成 false，所以那个「降级」注释是空想。
    //
    // 正解：只要引擎标记是 CLOAK，就必须用 CloakTextureView。
    // CloakTextureView.pullFrame 自身已容错（clientProvider 返回 null 时直接 return，
    // 下一轮 FRAME_INTERVAL_MS 再试），所以创建时拿不到客户端完全没关系——
    // 先画白底，客户端就绪后下一帧自动出画面。
    val liveCloak =
        runCatching {
            if (activeSession == null || !activeSession.isCloak || activeSession.isCamoufox) {
                null
            } else {
                activeSession.cloakClient
                    ?: com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak
                        .CloakBrowserTools.clientOf(activeSession.id)
            }
        }.getOrNull()

    return WebSessionBrowserState(
        activeSessionId = activeId,
        pageTitle = activeSession?.pageTitle.orEmpty(),
        // 单内核：引擎恒为 Cloak；engineReady 反映 CDP 客户端是否已建立，
        // 用于 UI 区分"已连上进程"与"进程还没起来"。
        // UI 决定用 CloakTextureView 还是 WebView 渲染，必须跟真实引擎状态一致：
        //  - isCloakEngine：会话标记为 CLOAK，且 CDP 客户端确实已建立
        //  - engineReady：CDP 已连上（实心点）／未连上（空心点，下次导航时拉起）
        // 降级到 WebView 后 isCloak 会变成 false，这里自动跟着切成 WebView 渲染，
        // 不会出现「UI 还在等 Cloak 画面、实际 WebView 已经在跑」的黑屏。
        engineLabel = if (activeSession?.isCloak == true) "Cloak" else "WebView",
        engineReady = liveCloak != null,
        // [WHITE-SCREEN-FIX v2] 只看引擎标记，不再附加 liveCloak != null。
        // CLOAK 会话没有 WebView 可渲染，判 false 只会得到白屏；
        // CloakTextureView 自己会在客户端就绪后自动出帧。
        isCloakEngine = activeSession?.isCloak == true,
        currentUrl = activeSession?.currentUrl?.ifBlank { "about:blank" } ?: "about:blank",
        canGoBack = activeSession?.canGoBack == true,
        canGoForward = activeSession?.canGoForward == true,
        isLoading = activeSession?.isLoading == true,
        hasSslError = activeSession?.hasSslError == true,
        isDesktopMode = StandardBrowserSessionTools.desktopModeEnabled,
        activeDownloadCount = downloadSummary.activeCount,
        hasFailedDownloads = downloadSummary.failedCount > 0,
        failedDownloadCount = downloadSummary.failedCount,
        latestCompletedDownloadName = downloadSummary.latestCompletedFileName,
        overallDownloadProgress = downloadSummary.overallProgress,
        pendingDialog =
            activeSession?.pendingDialog?.let { dialog ->
                WebSessionPendingDialogState(
                    type = dialog.type,
                    message = dialog.message,
                    defaultValue = dialog.defaultValue,
                    url = dialog.url
                )
            },
        tabs =
            orderedIds.mapNotNull { id ->
                sessionById(id)?.let { session ->
                    WebSessionBrowserTab(
                        sessionId = session.id,
                        title = sessionDisplayTitle(session),
                        url = session.currentUrl.ifBlank { "about:blank" },
                        isActive = session.id == activeId,
                        hasSslError = session.hasSslError
                    )
                }
            },
        sessionHistory =
            activeSession?.let { buildSessionHistory(it.webView) } ?: emptyList(),
        userscriptMenuCommands = userscriptManager.getMenuCommands(activeId)
    )
}

internal fun StandardBrowserSessionTools.buildSessionHistory(
    webView: WebView
): List<WebSessionSessionHistoryItem> {
    val historyList = webView.copyBackForwardList()
    if (historyList.size == 0) {
        return emptyList()
    }

    return buildList(historyList.size) {
        for (index in 0 until historyList.size) {
            val item = historyList.getItemAtIndex(index)
            val url = item?.url.orEmpty().ifBlank { "about:blank" }
            val title = item?.title.orEmpty().ifBlank { url }
            add(
                WebSessionSessionHistoryItem(
                    index = index,
                    title = title,
                    url = url,
                    isCurrent = index == historyList.currentIndex
                )
            )
        }
    }
}

internal fun StandardBrowserSessionTools.sessionDisplayTitle(
    session: BrowserToolSession
): String {
    val base =
        when {
            session.pageTitle.isNotBlank() -> session.pageTitle
            !session.sessionName.isNullOrBlank() -> session.sessionName
            session.currentUrl.isNotBlank() -> session.currentUrl
            else -> "about:blank"
        }
    val sslBadge = context.getString(R.string.web_ssl_error_badge)
    return if (session.hasSslError) "$sslBadge · $base" else base
}

internal fun StandardBrowserSessionTools.getActiveSessionOnMain(): BrowserToolSession? =
    buildPageRegistry().activeSessionId?.let(::sessionById)

internal fun StandardBrowserSessionTools.updateNavigationState(session: BrowserToolSession) {
    session.canGoBack = runCatching { session.webView.canGoBack() }.getOrDefault(false)
    session.canGoForward = runCatching { session.webView.canGoForward() }.getOrDefault(false)
    notifySessionStateChanged(session)
}

internal fun StandardBrowserSessionTools.syncNavigationStateUi(session: BrowserToolSession) {
    updateNavigationState(session)
    refreshSessionUiOnMain(session.id)
}

internal fun StandardBrowserSessionTools.refreshNavigationStateFromWebView(
    view: WebView,
    session: BrowserToolSession
) {
    syncNavigationStateUi(session)
    view.post {
        if (session.webView === view) {
            syncNavigationStateUi(session)
        }
    }
}

internal fun StandardBrowserSessionTools.refreshNavigationStateAsync(session: BrowserToolSession) {
    session.webView.post {
        syncNavigationStateUi(session)
    }
}

internal fun StandardBrowserSessionTools.ensureDesktopModeInitialized() {
    if (StandardBrowserSessionTools.desktopModeInitialized) {
        return
    }

    synchronized(StandardBrowserSessionTools.sessionConfigLock) {
        if (StandardBrowserSessionTools.desktopModeInitialized) {
            return
        }
        StandardBrowserSessionTools.desktopModeEnabled =
            runBlocking(Dispatchers.IO) {
                historyStore.desktopModeFlow.first()
            }
        StandardBrowserSessionTools.desktopModeInitialized = true
    }
}

internal fun StandardBrowserSessionTools.resolveUserAgent(customUserAgent: String?): String =
    customUserAgent
        ?: if (StandardBrowserSessionTools.desktopModeEnabled) {
            StandardBrowserSessionTools.DEFAULT_USER_AGENT
        } else {
            StandardBrowserSessionTools.MOBILE_USER_AGENT
        }

internal fun StandardBrowserSessionTools.applySessionUserAgent(
    session: BrowserToolSession,
    userAgent: String
) {
    val isDesktopMode = StandardBrowserSessionTools.desktopModeEnabled
    with(session.webView.settings) {
        userAgentString = userAgent
        useWideViewPort = isDesktopMode && session.viewportWidthPx == null
        loadWithOverviewMode = isDesktopMode && session.viewportWidthPx == null

        if (
            session.customUserAgent == null &&
                WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        ) {
            val userAgentMetadataBuilder =
                UserAgentMetadata.Builder()
                    .setMobile(!isDesktopMode)
                    .setPlatform(if (isDesktopMode) "Windows" else "Android")
            if (isDesktopMode) {
                // Null fields inherit Android WebView defaults, which would contradict the desktop UA.
                val brandVersions =
                    listOf("Chromium", "Google Chrome").map { brand ->
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand(brand)
                            .setMajorVersion(StandardBrowserSessionTools.DESKTOP_CHROME_MAJOR_VERSION)
                            .setFullVersion(StandardBrowserSessionTools.DESKTOP_CHROME_FULL_VERSION)
                            .build()
                    }
                userAgentMetadataBuilder
                    .setBrandVersionList(brandVersions)
                    .setFullVersion(StandardBrowserSessionTools.DESKTOP_CHROME_FULL_VERSION)
                    .setPlatformVersion("10.0.0")
                    .setArchitecture("x86")
                    .setModel("")
                    .setBitness(64)
                    .setWow64(false)
            }
            WebSettingsCompat.setUserAgentMetadata(this, userAgentMetadataBuilder.build())
        }
    }
}

internal fun StandardBrowserSessionTools.applyViewportOverride(session: BrowserToolSession) {
    val hasViewportOverride = session.viewportWidthPx != null && session.viewportHeightPx != null
    with(session.webView.settings) {
        // Page zoom does not change Chromium's layout viewport. With an explicit viewport,
        // size the WebView itself and let its control width define the CSS layout width.
        useWideViewPort = StandardBrowserSessionTools.desktopModeEnabled && !hasViewportOverride
        loadWithOverviewMode = StandardBrowserSessionTools.desktopModeEnabled && !hasViewportOverride
    }
}

internal fun StandardBrowserSessionTools.configureCookiePolicy(webView: WebView) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }
}

internal fun StandardBrowserSessionTools.createPopupSessionOnMain(
    parentSession: BrowserToolSession
): BrowserToolSession {
    val popupSession =
        createSessionOnMain(
            appContext = parentSession.webView.context ?: context.applicationContext,
            sessionId = UUID.randomUUID().toString(),
            sessionName = parentSession.sessionName,
            customUserAgent = parentSession.customUserAgent
        )
    StandardBrowserSessionTools.sessions[popupSession.id] = popupSession
    addSessionOrder(popupSession.id)
    StandardBrowserSessionTools.activeSessionId = popupSession.id
    ensureOverlayOnMain(context.applicationContext)
    syncProjectedBrowserStateOnMain()
    return popupSession
}

internal fun StandardBrowserSessionTools.findSessionByWebView(
    webView: WebView
): BrowserToolSession? = StandardBrowserSessionTools.sessions.values.firstOrNull { it.webView === webView }

internal fun StandardBrowserSessionTools.handleNavigationOverrideOnMain(
    session: BrowserToolSession,
    uri: Uri
): Boolean {
    val rawUrl = uri.toString()
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    return when (scheme) {
        "http", "https" -> {
            if (isUserscriptInstallUri(uri)) {
                userscriptManager.beginUrlInstall(rawUrl, UserscriptInstallSourceType.PAGE_LINK)
                openUserscriptSheetOnMain()
                true
            } else {
                false
            }
        }
        "about" -> false
        "intent" -> handleIntentSchemeOnMain(session, rawUrl)
        else -> {
            queueExternalOpenRequest(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                },
                title = context.getString(R.string.web_session_external_open_title),
                target = rawUrl
            )
            true
        }
    }
}

internal fun StandardBrowserSessionTools.isUserscriptInstallUri(uri: Uri): Boolean =
    uri.path?.endsWith(".user.js", ignoreCase = true) == true

internal fun StandardBrowserSessionTools.handleIntentSchemeOnMain(
    session: BrowserToolSession,
    rawUrl: String
): Boolean {
    val intent =
        runCatching { Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME) }.getOrElse { error ->
            AppLogger.w(WEBVIEW_SUPPORT_TAG, "Failed to parse intent url: ${error.message}")
            showToast(context.getString(R.string.web_session_external_open_failed, rawUrl))
            return true
        }

    val sanitizedIntent =
        intent.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
    queueExternalOpenRequest(
        sanitizedIntent,
        title = context.getString(R.string.web_session_external_open_title),
        target = sanitizedIntent.`package`?.takeIf { it.isNotBlank() } ?: rawUrl
    )
    return true
}

internal fun StandardBrowserSessionTools.queueExternalOpenRequest(
    intent: Intent,
    title: String,
    target: String
) {
    StandardBrowserSessionTools.pendingExternalOpenRequest =
        PendingExternalOpenRequest(
            requestId = UUID.randomUUID().toString(),
            intent =
                Intent(intent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            title = title,
            target = target
        )
    refreshSessionUiOnMain()
}

internal fun StandardBrowserSessionTools.confirmExternalOpenRequest(requestId: String) {
    val request = StandardBrowserSessionTools.pendingExternalOpenRequest ?: return
    if (request.requestId != requestId) {
        return
    }
    StandardBrowserSessionTools.pendingExternalOpenRequest = null
    refreshSessionUiOnMain()
    if (!launchBrowserExternalIntent(request.intent)) {
        showToast(context.getString(R.string.web_session_external_open_failed, request.target))
    }
}

internal fun StandardBrowserSessionTools.cancelExternalOpenRequest(requestId: String) {
    val request = StandardBrowserSessionTools.pendingExternalOpenRequest ?: return
    if (request.requestId != requestId) {
        return
    }
    StandardBrowserSessionTools.pendingExternalOpenRequest = null
    refreshSessionUiOnMain()
}

private fun PendingExternalOpenRequest.toUiState(): ExternalOpenPromptState =
    ExternalOpenPromptState(
        requestId = requestId,
        title = title,
        target = target
    )

internal fun StandardBrowserSessionTools.handleWebPermissionRequest(request: PermissionRequest) {
    val requestedResources = request.resources?.distinct().orEmpty()
    if (requestedResources.isEmpty()) {
        request.deny()
        return
    }

    val requiredPermissions =
        requestedResources
            .flatMap(::androidPermissionsForWebResource)
            .toCollection(LinkedHashSet())

    if (requiredPermissions.isEmpty()) {
        request.grant(requestedResources.toTypedArray())
        return
    }

    ioScope.launch {
        val permissionResults = ensureAndroidPermissions(requiredPermissions)
        val grantableResources =
            requestedResources
                .filter { resource ->
                    val required = androidPermissionsForWebResource(resource)
                    required.isEmpty() || required.all { permissionResults[it] == true }
                }
                .toTypedArray()

        StandardBrowserSessionTools.mainHandler.post {
            if (grantableResources.isNotEmpty()) {
                request.grant(grantableResources)
            } else {
                request.deny()
                showToast(context.getString(R.string.web_session_permission_denied))
            }
        }
    }
}

internal fun StandardBrowserSessionTools.handleGeolocationPermissionRequest(
    origin: String,
    callback: GeolocationPermissions.Callback
) {
    ioScope.launch {
        val permissionResults =
            ensureAndroidPermissions(
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        val granted =
            permissionResults[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissionResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        StandardBrowserSessionTools.mainHandler.post {
            callback.invoke(origin, granted, false)
            if (!granted) {
                showToast(context.getString(R.string.web_session_location_permission_denied))
            }
        }
    }
}

internal suspend fun StandardBrowserSessionTools.ensureAndroidPermissions(
    permissions: Collection<String>
): Map<String, Boolean> {
    val requested =
        permissions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    if (requested.isEmpty()) {
        return emptyMap()
    }

    val currentResults =
        requested.associateWith { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    val missingPermissions = currentResults.filterValues { granted -> !granted }.keys
    if (missingPermissions.isEmpty()) {
        return currentResults
    }

    val requestedResults =
        WebSessionPermissionRequestCoordinator.requestPermissions(
            context = context.applicationContext,
            permissions = missingPermissions
        )

    return requested.associateWith { permission ->
        currentResults[permission] == true || requestedResults[permission] == true
    }
}

internal fun StandardBrowserSessionTools.androidPermissionsForWebResource(
    resource: String
): List<String> =
    when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

internal fun StandardBrowserSessionTools.showToast(message: String) {
    if (message.isBlank()) {
        return
    }
    StandardBrowserSessionTools.mainHandler.post {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

internal fun StandardBrowserSessionTools.setDesktopModeEnabled(enabled: Boolean) {
    if (StandardBrowserSessionTools.desktopModeEnabled == enabled) {
        return
    }

    StandardBrowserSessionTools.desktopModeEnabled = enabled
    ioScope.launch {
        historyStore.setDesktopMode(enabled)
    }

    runOnMainSync<Unit> {
        StandardBrowserSessionTools.sessions.values.forEach { session ->
            if (!session.hasExplicitViewportSize) {
                session.viewportWidthPx =
                    StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_WIDTH.takeIf { enabled }
                session.viewportHeightPx =
                    StandardBrowserSessionTools.DEFAULT_DESKTOP_VIEWPORT_HEIGHT.takeIf { enabled }
            }
            if (session.customUserAgent == null) {
                applySessionUserAgent(session, resolveUserAgent(null))
            }
            applyViewportOverride(session)
        }
        syncProjectedBrowserStateOnMain()

        val activeSession = getActiveSessionOnMain()
        if (activeSession != null && activeSession.customUserAgent == null) {
            activeSession.pageLoaded = false
            activeSession.isLoading = true
            activeSession.webView.reload()
            refreshNavigationStateAsync(activeSession)
        } else {
            refreshSessionUiOnMain(activeSession?.id)
        }
    }
}

internal fun StandardBrowserSessionTools.resolvePendingDialogOnMain(
    session: BrowserToolSession,
    accept: Boolean,
    promptText: String? = null
): PendingDialog? {
    val pending = session.pendingDialog ?: return null
    if (pending.jsPromptResult != null) {
        if (accept) {
            pending.jsPromptResult.confirm(promptText ?: pending.defaultValue.orEmpty())
        } else {
            pending.jsPromptResult.cancel()
        }
    } else if (pending.jsResult != null) {
        if (accept) {
            pending.jsResult.confirm()
        } else {
            pending.jsResult.cancel()
        }
    }
    session.pendingDialog = null
    notifySessionStateChanged(session)
    refreshSessionUiOnMain(session.id)
    return pending
}

internal fun StandardBrowserSessionTools.closeSession(sessionId: String): Boolean {
    val orderedBeforeClose = orderedSessionIds()
    val closedIndex = orderedBeforeClose.indexOf(sessionId)
    val wasActive = StandardBrowserSessionTools.activeSessionId == sessionId
    val previouslyActiveId = StandardBrowserSessionTools.activeSessionId
    val session = StandardBrowserSessionTools.sessions.remove(sessionId) ?: return false
    removeSessionOrder(sessionId)

    runOnMainSync<Unit> {
        userscriptManager.detachSession(sessionId)
        if (wasActive) {
            StandardBrowserSessionTools.activeSessionId = null
            StandardBrowserSessionTools.browserHost?.attachActiveWebView(null)
        }

        val parent = session.webView.parent
        if (parent is ViewGroup) {
            parent.removeView(session.webView)
        }
        session.pendingFileChooserCallback?.onReceiveValue(null)
        session.pendingFileChooserCallback = null
        session.pendingDialog?.jsPromptResult?.cancel()
        session.pendingDialog?.jsResult?.cancel()
        session.pendingDialog = null
        notifySessionStateChanged(session)
        cleanupWebViewOnMain(session.webView)

        val remainingIds = orderedSessionIds().filter { sessionById(it) != null }
        val nextSessionId =
            if (wasActive) {
                when {
                    remainingIds.isEmpty() -> null
                    closedIndex >= 0 && closedIndex < remainingIds.size -> remainingIds[closedIndex]
                    closedIndex > 0 && closedIndex - 1 < remainingIds.size -> remainingIds[closedIndex - 1]
                    else -> remainingIds.firstOrNull()
                }
            } else {
                previouslyActiveId?.takeIf { sessionById(it) != null } ?: remainingIds.firstOrNull()
            }
        if (nextSessionId != null) {
            if (wasActive) {
                activateSessionOnMain(nextSessionId)
            } else {
                refreshSessionUiOnMain(nextSessionId)
            }
        } else {
            destroyOverlayOnMain()
        }
        refreshSessionUiOnMain()
    }

    return true
}

internal fun StandardBrowserSessionTools.cleanupWebViewOnMain(webView: WebView) {
    try {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    } catch (e: Exception) {
        AppLogger.w(WEBVIEW_SUPPORT_TAG, "Error during WebView cleanup: ${e.message}")
    }
}

/**
 * 浏览器导航专用的后台执行器。
 *
 * [ANR-FIX] 2026-11-02：
 * UI 侧 onNavigate 跑在主线程，而 Cloak 会话的导航会触发
 * CloakLauncher.launch → waitPort(30_000)，阻塞式等待 chromium 端口。
 * 30 秒不动主线程就是标准 ANR，用户体感是「输入地址回车后闪退」。
 *
 * 用单线程：多标签页同时导航会排队，避免多个 chromium 抢同一端口。
 * 用 daemon 线程：App 退出时不阻止进程收尾。
 */
internal object BackgroundNavExecutor {
    private val executor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "browser-nav").apply { isDaemon = true }
        }

    fun submit(block: () -> Unit) {
        runCatching { executor.execute(block) }
            .onFailure { AppLogger.w(WEBVIEW_SUPPORT_TAG, "导航任务提交失败: ${it.message}") }
    }
}

/** 切回主线程执行（导航完成后刷新 UI 状态）。 */
internal fun runOnUiThread(block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        block()
    } else {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
