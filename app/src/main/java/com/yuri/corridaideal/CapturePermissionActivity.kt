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
 * v1.0.2
 *
 * Mantém esta Activity viva por alguns instantes após o consentimento. Isso evita
 * uma corrida entre o fechamento do seletor do Android e a criação do foreground
 * service de MediaProjection em Android recente. Quando a captura realmente fica
 * pronta, devolve o usuário diretamente para a Uber.
 */
class CapturePermissionActivity : Activity() {
    private val requestCode = 9012
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("Deprecated API kept intentionally for this minimal permission activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode) return

        if (resultCode == RESULT_OK && data != null) {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "INICIANDO LEITURA"
            RuntimeState.notifyChanged()

            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service)
                } else {
                    startService(service)
                }
                waitUntilProjectionIsReady(0)
            } catch (t: Throwable) {
                RuntimeState.captureReady = false
                RuntimeState.captureStatus = "FALHA AO INICIAR: ${t.javaClass.simpleName}"
                RuntimeState.notifyChanged()
                Toast.makeText(this, "A leitura não iniciou. Tente novamente.", Toast.LENGTH_LONG).show()
                returnToUberAndFinish()
            }
        } else {
            RuntimeState.captureReady = false
            RuntimeState.captureStatus = "Leitura não autorizada"
            RuntimeState.notifyChanged()
            Toast.makeText(this, "Leitura não ativada", Toast.LENGTH_SHORT).show()
            returnToUberAndFinish()
        }
    }

    private fun waitUntilProjectionIsReady(attempt: Int) {
        if (completed) return

        if (RuntimeState.captureReady) {
            Toast.makeText(this, "Leitura pronta. Agora espere a oferta e toque ANALISAR.", Toast.LENGTH_LONG).show()
            returnToUberAndFinish()
            return
        }

        // ~3 segundos. Mantemos uma Activity visível enquanto o FGS conclui a
        // ativação; se falhar, o painel passa a exibir o motivo em vez de ficar
        // silenciosamente preso em ATIVAR LEITURA.
        if (attempt >= 30) {
            val status = RuntimeState.captureStatus
            Toast.makeText(this, "Não ativou: $status", Toast.LENGTH_LONG).show()
            returnToUberAndFinish()
            return
        }

        handler.postDelayed({ waitUntilProjectionIsReady(attempt + 1) }, 100L)
    }

    private fun returnToUberAndFinish() {
        if (completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)

        runCatching {
            packageManager.getLaunchIntentForPackage(UBER_PACKAGE)?.let { uber ->
                uber.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(uber)
            }
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val UBER_PACKAGE = "com.ubercab.driver"
    }
}
