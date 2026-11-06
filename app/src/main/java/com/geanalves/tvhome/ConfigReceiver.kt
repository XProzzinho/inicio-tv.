package com.geanalves.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * Configurações mandadas pelo Controle TV (celular):
 *   --es tmdb "CHAVE"        guarda a chave do TMDB ("" apaga)
 *   --es icones "pkg1,pkg2"  salva os ícones desses apps para o controle buscar
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        intent.getStringExtra("tmdb")?.let { chave ->
            Tmdb.salvarChave(app, chave)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    app,
                    if (chave.isBlank()) "Chave do TMDB removida" else "Chave do TMDB salva: carregando cartazes",
                    Toast.LENGTH_LONG,
                ).show()
            }
            resultData = if (chave.isBlank()) "removida" else "salva"
        }
        intent.getStringExtra("icones")?.let { lista ->
            resultData = salvarIcones(app, lista.split(',').map { it.trim() }.filter { it.isNotBlank() })
        }
    }

    private fun salvarIcones(ctx: Context, pkgs: List<String>): String {
        val dir = File(ctx.getExternalFilesDir(null), "icones").apply { mkdirs() }
        val pm = ctx.packageManager
        val indice = StringBuilder()
        for (pkg in pkgs) {
            try {
                val banner = pm.getApplicationBanner(pkg)
                    ?: pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
                val (d, w, h, tipo) = if (banner != null) {
                    Quatro(banner, 320, 180, "b")
                } else {
                    Quatro(pm.getApplicationIcon(pkg), 144, 144, "i")
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(bmp))
                File(dir, "$pkg.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                indice.append(pkg).append('|').append(tipo).append('\n')
            } catch (_: Throwable) {
            }
        }
        File(dir, "indice.txt").writeText(indice.toString())
        return dir.absolutePath
    }

    private data class Quatro(val d: Drawable, val w: Int, val h: Int, val tipo: String)
}
