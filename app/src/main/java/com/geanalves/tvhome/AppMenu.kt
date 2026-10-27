package com.geanalves.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Menu ao segurar OK num app: favoritar, mover, ocultar, informações. */
object AppMenu {
    fun show(a: Activity, prefs: Prefs, e: AppEntry, onChanged: () -> Unit) {
        val isFav = e.pkg in prefs.favs
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (isFav) {
            opts += "Mover para a esquerda" to { prefs.moveFav(e.pkg, -1); onChanged() }
            opts += "Mover para a direita" to { prefs.moveFav(e.pkg, 1); onChanged() }
            opts += "Remover dos favoritos" to { prefs.removeFav(e.pkg); onChanged() }
        } else {
            opts += "Adicionar aos favoritos" to { prefs.addFav(e.pkg); onChanged() }
        }
        opts += "Ocultar da tela inicial" to { prefs.hide(e.pkg); onChanged() }
        opts += "Informações do app" to {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
            }
        }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(e.label)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }
}
