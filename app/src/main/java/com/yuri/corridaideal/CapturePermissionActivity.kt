package com.yuri.corridaideal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Pede a autorização de captura usando o seletor PADRÃO do Android.
 * Isso replica o caminho que já funcionou nas versões iniciais e, no Android 14+,
 * deixa o próprio sistema oferecer Tela inteira ou Um único app.
 */
class CapturePermissionActivity : Activity() {
    private val requestCode = 9012

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Toast.makeText(
            this,
            "Escolha UM ÚNICO APP e selecione Uber. Faça isso antes de ficar online.",
            Toast.LENGTH_LONG
        ).show()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Não força nenhum modo. O Android decide a interface compatível com o aparelho.
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
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

            // O serviço precisa consumir o token de projeção e entrar em primeiro plano
            // antes de o app ser abandonado. Em alguns aparelhos, sair imediatamente daqui
            // torna a sessão instável. Mantemos esta Activity viva por ~1,2 s.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
            Toast.makeText(this, "Ativando leitura… aguarde um instante.", Toast.LENGTH_SHORT).show()

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing) finish()
            }, 1200L)
        } else {
            RuntimeState.captureStatus = "Autorização não concedida"
            RuntimeState.captureReady = false
            RuntimeState.notifyChanged()
            Toast.makeText(this, "Leitura não ativada", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
