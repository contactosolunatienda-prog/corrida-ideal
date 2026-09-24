package com.yuri.corridaideal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class CapturePermissionActivity : Activity() {
    private val requestCode = 9012

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("Deprecated in Android API; kept here for the minimal Activity implementation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            Toast.makeText(this, "Leitura de tela ativada. Volte para a Uber e toque em 📸.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Leitura de tela não autorizada", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
