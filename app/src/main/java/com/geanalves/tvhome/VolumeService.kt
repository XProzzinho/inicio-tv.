package com.geanalves.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * Mostra o volume (0 a 100) como um número grande na lateral direita da tela,
 * em qualquer app. Usa o serviço de Acessibilidade só para enxergar as teclas
 * de volume; o volume em si continua sendo mudado pelo sistema.
 */
class VolumeService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var texto: TextView? = null
    private var wm: WindowManager? = null
    private val esconder = Runnable { texto?.animate()?.alpha(0f)?.setDuration(350)?.start() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 66f
            setTextColor(0xFF0B0B0D.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.04f
            setShadowLayer(16f * d, 0f, 0f, Color.WHITE)
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.x = (64 * d).toInt()
        try {
            wm?.addView(tv, lp)
            texto = tv
        } catch (_: Throwable) {
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // o sistema muda o volume; lemos o valor novo um instante depois
                handler.postDelayed({ mostrar() }, 140)
            }
        }
        return false // deixa o sistema tratar a tecla normalmente
    }

    private fun mostrar() {
        val tv = texto ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val agora = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mudo = try { am.isStreamMute(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { false }
        val n = if (mudo) 0 else Math.round(agora * 100f / max)
        tv.text = n.toString()
        tv.animate().cancel()
        tv.alpha = 1f
        handler.removeCallbacks(esconder)
        handler.postDelayed(esconder, 1800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { texto?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        texto = null
        super.onDestroy()
    }

    companion object {
        fun enabled(c: Context): Boolean {
            val lista = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val nome = ComponentName(c, VolumeService::class.java).flattenToString()
            return lista.split(':').any { it.equals(nome, true) || it.endsWith("VolumeService") && it.startsWith(c.packageName) }
        }
    }
}
