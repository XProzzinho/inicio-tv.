package com.geanalves.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Brilho da tela: um véu preto por cima de tudo (filmes, apps, menus).
 * Funciona na TCL e na Fire TV Stick. 100 = normal, 5 = bem escuro (para dormir).
 */
object Brilho {
    private var veu: View? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    fun atual(ctx: Context): Int = prefs(ctx).getInt("brilho", 100)

    fun aplicar(ctx: Context, nivel: Int): Int {
        val app = ctx.applicationContext
        val n = nivel.coerceIn(MINIMO, 100)
        prefs(app).edit().putInt("brilho", n).apply()
        val wm = app.getSystemService(WindowManager::class.java) ?: return n
        if (n >= 100) {
            veu?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
            veu = null
            return n
        }
        // A permissão de desenhar por cima é liberada pelo Controle TV (appops)
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return n
        val escuro = ((100 - n) / 100f * 0.94f * 255).toInt()
        val v = veu ?: View(app).also { nova ->
            val tipo = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                tipo,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            try {
                wm.addView(nova, lp)
                veu = nova
            } catch (_: Throwable) {
                return n
            }
        }
        v.setBackgroundColor(Color.argb(escuro, 0, 0, 0))
        return n
    }

    /** Depois de ligar a TV, volta o brilho que estava. */
    fun restaurar(ctx: Context) {
        val n = atual(ctx)
        if (n < 100 && veu == null) aplicar(ctx, n)
    }

    const val MINIMO = 5
}

/**
 * Recebe o brilho do Controle TV:
 *   am broadcast -n com.geanalves.tvhome/.BrilhoReceiver --ei nivel 30
 *   am broadcast -n com.geanalves.tvhome/.BrilhoReceiver --ei passo -10
 * Responde com o nível novo.
 */
class BrilhoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val alvo = when {
            intent.hasExtra("nivel") -> intent.getIntExtra("nivel", 100)
            intent.hasExtra("passo") -> Brilho.atual(ctx) + intent.getIntExtra("passo", 0)
            else -> Brilho.atual(ctx)
        }
        val n = Brilho.aplicar(ctx, alvo)
        if (intent.hasExtra("nivel") || intent.hasExtra("passo")) {
            Toast.makeText(ctx.applicationContext, "Brilho $n%", Toast.LENGTH_SHORT).show()
        }
        resultData = n.toString()
    }
}
