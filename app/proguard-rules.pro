# ── WebView JavaScript Interface ──────────────────────────────────────────────
# Keep all @JavascriptInterface methods so they survive minification
-keepclassmembers class com.rps.samajapp.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Firebase Messaging ────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Stack trace readability ───────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin metadata ───────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
