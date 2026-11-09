package com.geanalves.tvhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lembretes mandados pelo Controle TV: aparecem embaixo do relógio,
 * só no perfil escolhido, até o dia marcado (depois somem sozinhos).
 */
object Lembretes {
    data class Lembrete(val id: Long, val texto: String, val perfil: Int, val data: String)

    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val curto = SimpleDateFormat("EEE, d MMM", Locale("pt", "BR"))

    /** Avisado quando o controle muda os lembretes. */
    var aoMudar: (() -> Unit)? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    private fun hoje(): String = iso.format(Calendar.getInstance().time)

    /** Todos os lembretes que ainda valem (os vencidos são apagados). */
    fun todos(ctx: Context): List<Lembrete> {
        val arr = try { JSONArray(sp(ctx).getString("lembretes", "[]")) } catch (_: Throwable) { JSONArray() }
        val h = hoje()
        val lista = ArrayList<Lembrete>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val l = Lembrete(o.optLong("id"), o.optString("texto"), o.optInt("perfil"), o.optString("data"))
            if (l.data >= h) lista += l
        }
        if (lista.size != arr.length()) salvar(ctx, lista)
        return lista.sortedBy { it.data }
    }

    fun doPerfil(ctx: Context, perfil: Int) = todos(ctx).filter { it.perfil == perfil }

    private fun salvar(ctx: Context, lista: List<Lembrete>) {
        val arr = JSONArray()
        for (l in lista) {
            arr.put(JSONObject().put("id", l.id).put("texto", l.texto).put("perfil", l.perfil).put("data", l.data))
        }
        sp(ctx).edit().putString("lembretes", arr.toString()).apply()
    }

    fun adicionar(ctx: Context, texto: String, perfil: Int, data: String) {
        val l = Lembrete(System.currentTimeMillis(), texto.trim().take(80), perfil.coerceIn(0, Prefs.PERFIS - 1), data)
        salvar(ctx, todos(ctx) + l)
    }

    fun apagar(ctx: Context, id: Long) = salvar(ctx, todos(ctx).filter { it.id != id })

    /** "Hoje", "Amanhã" ou "sáb., 8 nov". */
    fun quando(data: String): String {
        val c = Calendar.getInstance()
        val h = iso.format(c.time)
        c.add(Calendar.DAY_OF_YEAR, 1)
        val am = iso.format(c.time)
        return when (data) {
            h -> "Hoje"
            am -> "Amanhã"
            else -> try { curto.format(iso.parse(data)!!) } catch (_: Throwable) { data }
        }
    }

    /** Lista para o controle: uma linha por lembrete, "id|perfil|data|texto". */
    fun exportar(ctx: Context): String =
        todos(ctx).joinToString("\n") { "${it.id}|${it.perfil}|${it.data}|${it.texto.replace('\n', ' ')}" }
}
