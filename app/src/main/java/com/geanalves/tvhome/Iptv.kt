package com.geanalves.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * TV ao vivo: lê uma lista M3U (link mandado pelo Controle TV) e guarda os canais.
 * O Início TV só toca a lista que a pessoa escolher.
 */
object Iptv {
    data class Canal(val nome: String, val grupo: String, val logo: String?, val url: String)

    const val FAVORITOS = "Favoritos"
    const val TODOS = "Todos"
    private const val VALIDADE = 12L * 60 * 60 * 1000 // baixa a lista de novo a cada 12 horas

    var canais: List<Canal> = emptyList()
        private set

    /** Avisado quando o controle troca o link da lista (a bolinha muda de cor). */
    var aoMudar: (() -> Unit)? = null

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val logos = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)
    private fun cache(ctx: Context) = File(ctx.filesDir, "lista.m3u")

    fun link(ctx: Context): String? = sp(ctx).getString("iptv_url", null)

    fun salvarLink(ctx: Context, url: String) {
        val e = sp(ctx).edit()
        if (url.isBlank()) e.remove("iptv_url") else e.putString("iptv_url", url.trim())
        e.apply()
        cache(ctx).delete()
        canais = emptyList()
        main.post { aoMudar?.invoke() }
    }

    // ---------- favoritos e último canal ----------

    fun favoritos(ctx: Context): Set<String> = sp(ctx).getStringSet("iptv_fav", emptySet())!!

    /** Liga/desliga o favorito. Devolve true se agora é favorito. */
    fun trocarFavorito(ctx: Context, c: Canal): Boolean {
        val f = favoritos(ctx).toMutableSet()
        val agora = if (c.url in f) { f.remove(c.url); false } else { f.add(c.url); true }
        sp(ctx).edit().putStringSet("iptv_fav", f).apply()
        return agora
    }

    fun ultimo(ctx: Context): String? = sp(ctx).getString("iptv_ultimo", null)
    fun salvarUltimo(ctx: Context, c: Canal) = sp(ctx).edit().putString("iptv_ultimo", c.url).apply()

    /** Canais que o player percorre com cima/baixo (a lista que estava na tela). */
    var fila: List<Canal> = emptyList()

    /** Abas da TV ao vivo, na mesma ordem da foto. */
    val ABAS = listOf("Jogos", "Destaques", "Filmes", "Séries", "Kids", "Anime", "Explorar")

    private val chaves = mapOf(
        0 to listOf("esporte", "sport", "futebol", "football", "soccer", "premiere", "combate", "ufc", "luta", "nba", "espn", "jogos", "game"),
        2 to listOf("filme", "movie", "cine", "telecine", "hbo", "megapix", "cinema"),
        3 to listOf("serie", "series", "novela", "seriado", "warner", "sony", "universal", "axn"),
        4 to listOf("kid", "infantil", "crianca", "desenho", "cartoon", "disney", "nick", "gloob", "discovery kids", "animation"),
        5 to listOf("anime", "crunchyroll", "otaku"),
    )

    private fun semAcento(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\p{Mn}+"), "").lowercase()

    /** Canais de cada aba: separa pelo grupo da lista (e pelo nome do canal). */
    fun daAba(ctx: Context, aba: Int): List<Canal> = when (aba) {
        // Destaques: favoritos primeiro, depois os primeiros canais da lista
        1 -> {
            val f = favoritos(ctx)
            val favs = canais.filter { it.url in f }
            (favs + canais.filter { it.url !in f }.take(40)).distinct()
        }
        6 -> canais
        else -> {
            val ks = chaves[aba].orEmpty()
            canais.filter { c ->
                val g = semAcento(c.grupo)
                ks.any { g.contains(it) }
            }
        }
    }

    /** Grupos na ordem: Favoritos, Todos e depois os da lista. */
    fun grupos(ctx: Context): List<String> {
        val g = canais.map { it.grupo }.filter { it.isNotBlank() }.distinct()
        return listOf(FAVORITOS, TODOS) + g
    }

    fun doGrupo(ctx: Context, grupo: String): List<Canal> = when (grupo) {
        FAVORITOS -> favoritos(ctx).let { f -> canais.filter { it.url in f } }
        TODOS -> canais
        else -> canais.filter { it.grupo == grupo }
    }

    // ---------- carregar a lista ----------

    /** [cb] recebe a mensagem de erro (null = deu certo). */
    fun carregar(ctx: Context, forcar: Boolean = false, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        val url = link(app)
        if (url == null) {
            canais = emptyList()
            cb("sem lista")
            return
        }
        if (!forcar && canais.isNotEmpty()) {
            cb(null)
            return
        }
        pool.execute {
            val arq = cache(app)
            var erro: String? = null
            try {
                val velho = !arq.exists() || System.currentTimeMillis() - arq.lastModified() > VALIDADE
                if (forcar || velho) {
                    try {
                        baixar(url, arq)
                    } catch (t: Throwable) {
                        if (!arq.exists()) throw t // sem internet: usa a cópia antiga se tiver
                    }
                }
                val lista = ler(arq)
                if (lista.isEmpty()) erro = "A lista não tem canais" else canais = lista
            } catch (t: Throwable) {
                erro = "Não consegui baixar a lista"
            }
            main.post { cb(erro) }
        }
    }

    private fun baixar(link: String, destino: File) {
        var url = URL(link)
        var c: HttpURLConnection
        var pulos = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "InicioTV")
            val code = c.responseCode
            if (code in 300..399 && pulos < 8) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                pulos++
                continue
            }
            if (code !in 200..299) throw java.io.IOException("erro $code")
            break
        }
        val tmp = File(destino.parentFile, destino.name + ".tmp")
        c.inputStream.use { e -> tmp.outputStream().use { e.copyTo(it) } }
        tmp.renameTo(destino)
    }

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /** Lê o M3U: #EXTINF com tvg-logo, group-title e o nome depois da vírgula; a linha seguinte é o link. */
    private fun ler(arq: File): List<Canal> {
        val out = ArrayList<Canal>()
        var nome: String? = null
        var grupo = ""
        var logo: String? = null
        arq.bufferedReader().useLines { linhas ->
            for (bruta in linhas) {
                val l = bruta.trim()
                when {
                    l.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        nome = l.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Canal" }
                        grupo = attrs["group-title"]?.split(';')?.firstOrNull()?.trim().orEmpty()
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    }
                    l.startsWith("#EXTGRP", ignoreCase = true) -> grupo = l.substringAfter(':').trim()
                    l.isEmpty() || l.startsWith("#") -> Unit
                    else -> {
                        if (nome != null && (l.startsWith("http") || l.startsWith("rtmp") || l.startsWith("rtsp"))) {
                            out += Canal(nome!!, grupo.ifBlank { "Outros" }, logo, l)
                        }
                        nome = null
                        grupo = ""
                        logo = null
                    }
                }
            }
        }
        return out
    }

    // ---------- logos dos canais ----------

    fun logo(url: String, cb: (Bitmap?) -> Unit) {
        logos.get(url)?.let { cb(it); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.instanceFollowRedirects = true
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                // Reduz para no máximo ~200px: economiza memória da TV
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 200 && o.outHeight / (s * 2) >= 120) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (bmp != null) logos.put(url, bmp)
                cb(bmp)
            }
        }
    }
}
