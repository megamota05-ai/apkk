// [Android API 26+, Kotlin] — Hardware ID generator
// HwidUtil.kt — Produces a stable, per-device identifier tied to the hardware.
// Algorithm: SHA-256( ANDROID_ID + ":" + MANUFACTURER + ":" + MODEL + ":" + BOARD )
// Result: 64-char lowercase hex string.
package com.fleetdroid.driver

import android.content.Context
import android.provider.Settings
import android.os.Build
import java.security.MessageDigest

object HwidUtil {

    /**
     * Returns a 64-char hex HWID derived from hardware constants.
     * Stable across reboots; changes on factory reset (ANDROID_ID resets).
     */
    fun generate(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "unknown"

        val raw = buildString {
            append(androidId)
            append(":")
            append(Build.MANUFACTURER)
            append(":")
            append(Build.MODEL)
            append(":")
            append(Build.BOARD)
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
