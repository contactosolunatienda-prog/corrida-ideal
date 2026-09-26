package com.yuri.corridaideal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Fluxo deliberadamente simples, espelhando a v0.2:
 * abre o seletor padrão do Android e, após consentimento, inicia o serviço de captura.
 * Não abre a Uber automaticamente e não força modo de captura.
 */
class CapturePermissionActivity : Activity() {
    private val requestCode = 9012

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(
            this,
            "Como você já está na Uber, autorize a captura. 'Tudo na tela' é o modo recomendado.",
            Toast.LENGTH_LONG
        ).show()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("Deprecated API kept intentionally for this minimal permission activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            Toast.makeText(this, "Leitura ativada. Agora espere a oferta e toque ANALISAR.", Toast.LENGTH_LONG).show()
        } else {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "Leitura não autorizada"
            RuntimeState.notifyChanged()
            Toast.makeText(this, "Leitura não ativada", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
