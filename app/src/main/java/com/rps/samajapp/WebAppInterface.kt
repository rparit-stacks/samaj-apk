package com.rps.samajapp

import android.content.Context
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
}
