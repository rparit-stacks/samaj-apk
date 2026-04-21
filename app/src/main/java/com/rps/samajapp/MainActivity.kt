package com.rps.samajapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import com.google.firebase.messaging.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging

import com.rps.samajapp.ui.theme.SamajAppTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val WEB_URL = "https://web.suryavanshisamaj.online"
        const val PREFS_NAME = "samaj_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val EXTRA_DEEP_LINK = "deep_link_url"
    }

    // Held at activity level so WebView survives recompositions
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var backPressedAt = 0L

    // Compose-observable state
    private val pageLoaded = mutableStateOf(false)
    private val isLoading = mutableStateOf(true)
    private val progress = mutableIntStateOf(0)
    private val hasError = mutableStateOf(false)

    // Status bar colour (observed by Compose so the web page's meta[theme-color] can drive it)
    private val statusBarColor = mutableStateOf(Color(0xFF7A1F2D)) // brand maroon, overridden by theme color
    private val navigationBarColor = mutableStateOf(Color.White)

    // File chooser result
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?: cameraUri?.takeIf { result.resultCode == RESULT_OK }?.let { arrayOf(it) }
            ?: arrayOf()
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    // Generic permission request (camera/storage for file picker)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled passively; user can retry via browser */ }

    // Notification permission (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* non-blocking */ }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold splash screen until first page finishes loading
        splash.setKeepOnScreenCondition { !pageLoaded.value }

        // Enable Service Worker caching for PWA-style assets if the site registers one.
        // This is a big win for repeat loads and slow networks.
        configureServiceWorker()

        // Edge-to-edge is enforced on Android 15+ (API 35+), so opt in explicitly and
        // handle insets ourselves. Drawing the status/nav bar colours as Compose
        // backdrops keeps behaviour consistent across API levels (window.statusBarColor
        // is deprecated / no-op on API 35+).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInitialStatusBar()

        requestNotificationPermission()
        initFcm()

        // Skip reload on configuration changes
        if (savedInstanceState != null) {
            isLoading.value = false
            pageLoaded.value = true
        }

        val deepLink = resolveDeepLink(intent)

        setContent {
            SamajAppTheme {
                SamajScreen(startUrl = deepLink ?: WEB_URL, savedState = savedInstanceState)
            }
        }

        // Remote debugging in debug builds only
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private fun configureServiceWorker() {
        runCatching {
            val controller = ServiceWorkerControllerCompat.getInstance()
            controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    // Allow service worker to fetch/cache as normal.
                    // We don't override responses here; just ensuring SW is enabled in WebView.
                    return null
                }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveDeepLink(intent)?.let { webView?.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView?.restoreState(savedInstanceState)
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun resolveDeepLink(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_DEEP_LINK)
            ?: intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString

    private fun applyInitialStatusBar() {
        val primary = ContextCompat.getColor(this, R.color.primary)
        statusBarColor.value = Color(primary)
        navigationBarColor.value = Color.White

        // Icons: light on dark status bar, dark on light nav bar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun initFcm() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FCM_TOKEN, token).apply()
        }
        // Subscribe to community-wide push topics (idempotent — safe to call every launch)
        SamajFirebaseMessagingService.subscribeToTopics()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ──────────────────────────────────────────────
    //  Compose UI
    // ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun SamajScreen(startUrl: String, savedState: Bundle?) {
        val loading by isLoading
        val prog by progress
        val error by hasError
        val topBarColor by statusBarColor
        val bottomBarColor by navigationBarColor

        Box(Modifier.fillMaxSize()) {

            // ── App content (WebView + overlays), padded below status bar,
            // above navigation bar, and above the keyboard when it's open.
            Box(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            ) {
                // WebView fills the safe content area
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            webView = wv
                            wv.layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            wv.setupWebView()
                            wv.webViewClient = buildWebViewClient()
                            wv.webChromeClient = buildChromeClient()
                            wv.addJavascriptInterface(WebAppInterface(ctx, window), "SamajNative")

                            if (savedState != null) {
                                wv.restoreState(savedState)
                            } else {
                                wv.loadUrl(startUrl)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Thin loading progress bar at top of content
                AnimatedVisibility(
                    visible = loading && !error,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    LinearProgressIndicator(
                        progress = { prog / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }

                // Offline / error screen
                AnimatedVisibility(
                    visible = error,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    OfflineScreen {
                        hasError.value = false
                        isLoading.value = true
                        if (isOnline()) webView?.reload() else webView?.loadUrl(WEB_URL)
                    }
                }
            }

            // ── Status-bar scrim (drawn behind the real status bar).
            // Height == status-bar inset so it never overlaps content.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(topBarColor)
            )

            // ── Navigation-bar scrim (drawn behind the system gesture/nav bar).
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(bottomBarColor)
            )
        }

        // Back navigation
        BackHandler {
            when {
                webView?.canGoBack() == true -> webView?.goBack()
                System.currentTimeMillis() - backPressedAt < 2000 -> finish()
                else -> {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.back_exit_hint),
                        Toast.LENGTH_SHORT
                    ).show()
                    backPressedAt = System.currentTimeMillis()
                }
            }
        }
    }

    @Composable
    private fun OfflineScreen(onRetry: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "\uD83D\uDCE1", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = getString(R.string.error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = getString(R.string.error_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onRetry) {
                    Text(getString(R.string.retry))
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  WebView configuration
    // ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.setupWebView() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Use local HTTP cache when available; better perceived speed on repeat loads.
            setGeolocationEnabled(true)
            // Reduce blocking on slow connections.
            loadsImagesAutomatically = true
            // Allow better layout/scale behaviour on mobile pages.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "SamajApp/1.0 Android/${Build.VERSION.RELEASE} (${Build.MODEL})"
        }
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isScrollbarFadingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun buildWebViewClient() = object : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            isLoading.value = true
            hasError.value = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            isLoading.value = false
            pageLoaded.value = true
            syncFcmToken(view)
            syncThemeColor(view)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                hasError.value = true
                isLoading.value = false
                pageLoaded.value = true
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            // Always cancel — never bypass SSL errors
            handler.cancel()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            return when {
                // Internal links stay in WebView
                url.startsWith("https://web.suryavanshisamaj.online") -> false
                url.startsWith("http://web.suryavanshisamaj.online") -> false

                // System scheme handlers
                url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("intent:") -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    true
                }

                // All other HTTP links open in system browser
                url.startsWith("http://") || url.startsWith("https://") -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    true
                }

                else -> false
            }
        }
    }

    private fun buildChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.intValue = newProgress
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            requestMediaPermissions()
            fileChooserLauncher.launch(buildFileIntent(fileChooserParams))
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.grant(request.resources)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, true, false)
        }
    }

    // ──────────────────────────────────────────────
    //  File upload helpers
    // ──────────────────────────────────────────────

    private fun buildFileIntent(params: WebChromeClient.FileChooserParams): Intent {
        val extras = mutableListOf<Intent>()
        val acceptsImage = params.acceptTypes.any { it.contains("image") || it == "*/*" }

        if (acceptsImage) {
            runCatching {
                val imgFile = createImageFile()
                cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imgFile)
                extras.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                })
            }
        }

        val mimeType = params.acceptTypes.firstOrNull { it.isNotEmpty() } ?: "*/*"
        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }

        return Intent.createChooser(pickIntent, getString(R.string.choose_file)).apply {
            if (extras.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            }
        }
    }

    private fun createImageFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile(
            "IMG_${stamp}_", ".jpg",
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        )
    }

    private fun requestMediaPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.READ_MEDIA_IMAGES
                    ) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ──────────────────────────────────────────────
    //  JS injections
    // ──────────────────────────────────────────────

    private fun syncFcmToken(view: WebView) {
        val token = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null) ?: return
        view.evaluateJavascript(
            "window.__fcmToken='$token';" +
                "window.dispatchEvent(new CustomEvent('fcmToken',{detail:'$token'}));",
            null
        )
    }

    private fun syncThemeColor(view: WebView) {
        view.evaluateJavascript(
            "document.querySelector('meta[name=\"theme-color\"]')?.getAttribute('content') || '';"
        ) { raw ->
            val colorStr = raw?.trim('"')?.trim()
            if (!colorStr.isNullOrEmpty()) {
                runCatching {
                    val parsed = android.graphics.Color.parseColor(colorStr)
                    runOnUiThread {
                        statusBarColor.value = Color(parsed)
                        WindowInsetsControllerCompat(window, window.decorView)
                            .isAppearanceLightStatusBars = isColorLight(parsed)
                    }
                }
            }
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.5
    }
}
