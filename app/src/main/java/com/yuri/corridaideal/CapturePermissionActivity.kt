package com.yuri.corridaideal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class CapturePermissionActivity : Activity() {
    private val requestCode = 9012

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 14+: força captura da tela inteira. Isso remove a escolha repetitiva
        // entre "um aplicativo" e "tela inteira" que estava atrapalhando o uso na rua.
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(captureIntent, requestCode)
    }

    @Deprecated("Kept for this minimal permission activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            Toast.makeText(this, "Turno ativado. Abrindo a Uber…", Toast.LENGTH_SHORT).show()

            // Volta direto para o Uber Driver, sem deixar o motorista preso no Corrida Ideal.
            runCatching {
                packageManager.getLaunchIntentForPackage("com.ubercab.driver")?.let { uber ->
                    uber.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(uber)
                }
            }
        } else {
            RuntimeState.captureStatus = "Autorização não concedida"
            RuntimeState.captureReady = false
            RuntimeState.notifyChanged()
            Toast.makeText(this, "Leitura não ativada", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
