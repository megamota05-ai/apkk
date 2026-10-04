// [context: fleet management app, Android API 26+, ARM64/any]
// MainActivity.kt — entry point: requests MediaProjection consent, stores Intent,
//                   then starts DispatchService and finishes itself (no UI).
package com.fleetdroid.driver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.fleetdroid.driver.service.DispatchService
import com.securesdk.storage.SecureConfig

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PROJECTION = 100
    }

    private lateinit var mpm: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If DispatchService is already running, we're done.
        if (DispatchService.instance != null) {
            Log.i(TAG, "service already running — finishing")
            finish()
            return
        }

        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Launch the system consent dialog.
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Using legacy onActivityResult for API 26 compat")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "MediaProjection consent denied")
            finish()
            return
        }

        // Serialize the consent Intent to Base64 so DispatchService can use it
        // when the server sends "screen_live" or "screen_silent" commands.
        try {
            val parcel = android.os.Parcel.obtain()
            data.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            SecureConfig.getInstance(this).putString("projection_intent_b64", b64)
            Log.i(TAG, "projection intent stored (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to store projection intent", e)
        }

        // Start the foreground service.
        val svc = Intent(this, DispatchService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }

        finish()
    }
}
