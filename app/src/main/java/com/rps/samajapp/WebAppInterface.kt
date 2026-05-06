package com.rps.samajapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Exposes native Android capabilities to the web app via window.SamajNative.*
 *
 * All @JavascriptInterface methods run on a background thread; UI operations
 * must be posted to the main looper.
 */
class WebAppInterface(
    private val context: Context,
    private val window: Window
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called by the web app to initiate native Google Sign-In via Credential Manager.
     * The result is delivered back to JavaScript via window.__samajGoogleCallback(idToken, error).
     */
    var onGoogleSignInRequested: (() -> Unit)? = null

    @JavascriptInterface
    fun startGoogleSignIn() {
        mainHandler.post { onGoogleSignInRequested?.invoke() }
    }

    /** Returns the current FCM device token so the web app can register it with the backend. */
    @JavascriptInterface
    fun getFcmToken(): String =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_FCM_TOKEN, "") ?: ""

    /** Shows a native Android Toast. Safe to call from JavaScript. */
    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the status bar color from JavaScript.
     * The web app can call this when its theme changes.
     * @param hexColor e.g. "#FF6F00" or "rgb(255,111,0)"
     */
    @JavascriptInterface
    fun setStatusBarColor(hexColor: String) {
        mainHandler.post {
            runCatching {
                val color = android.graphics.Color.parseColor(hexColor)
                @Suppress("DEPRECATION")
                window.statusBarColor = color
                val isLight = (0.299 * android.graphics.Color.red(color) +
                    0.587 * android.graphics.Color.green(color) +
                    0.114 * android.graphics.Color.blue(color)) / 255 > 0.5
                WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightStatusBars = isLight
            }
        }
    }

    /**
     * Returns device information as a JSON string.
     * Useful for the web app to tailor its UI to the device.
     */
    @JavascriptInterface
    fun getDeviceInfo(): String =
        """{"platform":"android","osVersion":"${Build.VERSION.RELEASE}","model":"${Build.MODEL}","brand":"${Build.BRAND}","appVersion":"1.0"}"""

    /** Returns true when the app is running inside the native WebView wrapper. */
    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    /**
     * Opens the Android native share sheet.
     * Called by the injected navigator.share() override and directly from the web app via
     *   window.SamajNative.nativeShare(url, title, text)
     *
     * Builds a share text that includes title, body text, and a URL so any messaging app,
     * WhatsApp, clipboard, etc. receives all three in a single tap.
     */
    @JavascriptInterface
    fun nativeShare(url: String, title: String, text: String) {
        mainHandler.post {
            val shareBody = buildString {
                if (title.isNotBlank()) append(title).append("\n\n")
                if (text.isNotBlank()) append(text).append("\n\n")
                val fullUrl = when {
                    url.startsWith("http") -> url
                    url.startsWith("/")    -> "${MainActivity.WEB_URL}$url"
                    else                   -> url
                }
                if (fullUrl.isNotBlank()) append(fullUrl)
            }.trim()

            if (shareBody.isEmpty()) return@post

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Suryavanshi Samaj" })
                putExtra(Intent.EXTRA_TEXT, shareBody)
            }
            val chooser = Intent.createChooser(sendIntent, "Share via")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

}
