package com.geanalves.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var ultimo = -1

    // Confere o volume várias vezes por segundo: funciona mesmo que a TV não
    // entregue as teclas de volume ao Android.
    private val vigia = object : Runnable {
        override fun run() {
            val n = lerVolume()
            if (n != null && n != ultimo) {
                if (ultimo != -1) mostrar(n)
                ultimo = n
            }
            handler.postDelayed(this, 200)
        }
    }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            lerVolume()?.let { ultimo = it; mostrar(it) }
        }
    }

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
        ultimo = lerVolume() ?: -1
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, 200)
        try {
            registerReceiver(receptor, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (_: Throwable) {
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_MUTE) return false
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (am.isVolumeFixed) return false
        // Nós mesmos mudamos o volume, sem a barra da TV (flags = 0), e
        // mostramos só o número. Se algo der errado, deixa o sistema tratar.
        if (event.action == KeyEvent.ACTION_DOWN) {
            val dir = when (code) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            } catch (_: Throwable) {
                return false
            }
            lerVolume()?.let { ultimo = it; mostrar(it) }
        }
        return true
    }

    private fun lerVolume(): Int? {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val agora = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mudo = try { am.isStreamMute(AudioManager.STREAM_MUSIC) } catch (_: Throwable) { false }
        return if (mudo) 0 else Math.round(agora * 100f / max)
    }

    private fun mostrar(n: Int) {
        val tv = texto ?: return
        tv.text = n.toString()
        tv.animate().cancel()
        tv.alpha = 1f
        handler.removeCallbacks(esconder)
        handler.postDelayed(esconder, 1800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(vigia)
        try { unregisterReceiver(receptor) } catch (_: Throwable) {}
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
