// [Android API 26+, Kotlin, stdlib-only HTTP] — operator authentication
// LoginManager.kt — POSTs credentials + HWID to /auth/login, caches the JWT.
// Called from FleetApp on first launch; result stored in SecureConfig.
package com.fleetdroid.driver

import android.content.Context
import android.util.Log
import com.securesdk.storage.SecureConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object LoginManager {

    private const val TAG = "LoginManager"
    private const val TIMEOUT_MS = 15_000

    data class LoginResult(
        val token: String,
        val nextKey: String,
        val subType: String,
        val expireAt: String,
        val userId: Long
    )

    /**
     * Performs a synchronous login against the C2 server.
     * Must be called from a background thread.
     *
     * @param baseUrl  HTTP(S) base URL, e.g. "https://your-server.com" (no trailing slash)
     * @param username Operator username baked into Config
     * @param password Operator password baked into Config
     * @param hwid     Device HWID from HwidUtil.generate()
     * @return LoginResult on success, null on any failure
     */
    fun login(baseUrl: String, username: String, password: String, hwid: String): LoginResult? {
        return try {
            val endpoint = "$baseUrl/auth/login"
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("hwid", hwid)
            }.toString()

            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "FleetDroid/1.0")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "login failed: HTTP $code")
                return null
            }

            val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }

            val json = JSONObject(resp)
            LoginResult(
                token     = json.getString("token"),
                nextKey   = json.optString("next_key", ""),
                subType   = json.optString("sub_type", "active"),
                expireAt  = json.optString("expire_at", ""),
                userId    = json.optLong("user_id", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "login error", e)
            null
        }
    }

    /**
     * Convenience: performs login and stores the JWT + next_key in SecureConfig.
     * Returns true on success.
     */
    fun loginAndSave(ctx: Context, baseUrl: String, username: String, password: String): Boolean {
        val hwid = HwidUtil.generate(ctx)
        val result = login(baseUrl, username, password, hwid) ?: return false
        val cfg = SecureConfig.getInstance(ctx)
        cfg.putString("auth_token", result.token)
        cfg.putString("next_key", result.nextKey)
        cfg.putString("sub_type", result.subType)
        cfg.putString("expire_at", result.expireAt)
        cfg.putString("hwid", hwid)
        Log.i(TAG, "login ok — sub=${result.subType} exp=${result.expireAt}")
        return true
    }
}
