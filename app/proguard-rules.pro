# ──────────────────────────────────────────────────────────────────────
# proguard-rules.pro — R8/ProGuard rules for Tvgram
# ──────────────────────────────────────────────────────────────────────

# Keep TDLib classes (JNI uses reflection) — no longer used (D-027 JSON interface)
# -keep class org.drinkless.tdlib.** { *; }

# Keep Compose runtime metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion {
    public <fields>;
}

# Keep BuildConfig (we read TG_API_ID / TG_API_HASH)
-keep class tv.telegram.BuildConfig { *; }

# ExoPlayer / Media3 — these are mostly fine, but be safe on DASH/HLS
-keep class androidx.media3.** { *; }

# Keep our app classes
-keep class tv.telegram.** { *; }

# Standard Android
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Strip logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
