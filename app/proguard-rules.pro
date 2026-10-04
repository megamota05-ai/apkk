# ── Keep all project classes intact ─────────────────────────────────────────
-keep class com.fleetdroid.** { *; }
-keep class com.empresa.realtime.** { *; }
-keep class com.securesdk.storage.** { *; }
-keep class com.temu.shopassist.service.** { *; }
-keep class com.corp.mdm.inventory.** { *; }

# ── org.json (used for command parsing) ──────────────────────────────────────
-keep class org.json.** { *; }
-dontwarn org.json.**

# ── Android Keystore / crypto (SecureConfig) ─────────────────────────────────
-keep class android.security.keystore.** { *; }
-keepclassmembers class * extends android.app.Service { *; }
-keepclassmembers class * extends android.content.BroadcastReceiver { *; }
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService { *; }

# ── Preserve generic signatures (needed for Parcelable deserialization) ───────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── Parcelable ────────────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Kotlin metadata ───────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
