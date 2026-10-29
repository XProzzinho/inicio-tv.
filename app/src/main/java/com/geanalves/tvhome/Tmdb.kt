package com.geanalves.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Filmes e séries do TMDB (themoviedb.org), em português e com onde assistir no Brasil. */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"

    private val key: String = BuildConfig.TMDB_KEY.trim()
    val enabled: Boolean get() = key.isNotBlank()

    data class Item(val id: Int, val type: String, val title: String, val backdrop: String)
    data class Details(val meta: String, val providerId: Int?, val providerName: String?)

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val lists = HashMap<Int, List<Item>>()
    private val details = HashMap<String, Details>()
    private val images = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap): Int = v.byteCount
    }

    /** Serviço do TMDB -> app de TV correspondente. */
    private val providerPackages = mapOf(
        8 to "com.netflix.ninja",
        119 to "com.amazon.amazonvideo.livingroom",
        9 to "com.amazon.amazonvideo.livingroom",
        337 to "com.disney.disneyplus",
        1899 to "com.wbd.stream",
        384 to "com.wbd.stream",
        307 to "com.globo.globotv",
        350 to "com.apple.atve.androidtv.appletv",
        2 to "com.apple.atve.androidtv.appletv",
        531 to "com.cbs.ca",
        283 to "com.crunchyroll.crunchyroid",
    )

    fun packageFor(providerId: Int): String? = providerPackages[providerId]

    private fun get(path: String, extra: String = ""): JSONObject {
        val bearer = key.length > 60 // token longo (v4) em vez da chave curta
        val auth = if (bearer) "" else "&api_key=$key"
        val url = URL("$API$path?language=pt-BR$auth$extra")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Accept", "application/json")
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun str(o: JSONObject?, k: String): String? {
        if (o == null || o.isNull(k)) return null
        return o.optString(k).takeIf { it.isNotBlank() }
    }

    /** tab: 0 = Explorar (em alta), 1 = Filmes, 2 = Séries */
    fun list(tab: Int, cb: (List<Item>) -> Unit) {
        lists[tab]?.let { cb(it); return }
        pool.execute {
            val result = try {
                val streaming = "&watch_region=BR&with_watch_monetization_types=flatrate&sort_by=popularity.desc"
                val (json, defType) = when (tab) {
                    1 -> get("/discover/movie", streaming) to "movie"
                    2 -> get("/discover/tv", streaming) to "tv"
                    else -> get("/trending/all/week") to "movie"
                }
                val arr = json.optJSONArray("results")
                val out = ArrayList<Item>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val type = str(o, "media_type") ?: defType
                        if (type != "movie" && type != "tv") continue
                        val backdrop = str(o, "backdrop_path") ?: continue
                        val title = str(o, "title") ?: str(o, "name") ?: continue
                        out += Item(o.optInt("id"), type, title, backdrop)
                    }
                }
                out
            } catch (t: Throwable) {
                emptyList()
            }
            main.post {
                if (result.isNotEmpty()) lists[tab] = result
                cb(result)
            }
        }
    }

    fun details(item: Item, cb: (Details) -> Unit) {
        val k = "${item.type}/${item.id}"
        details[k]?.let { cb(it); return }
        pool.execute {
            val d = try {
                val j = get("/$k", "&append_to_response=watch/providers")
                val genre = j.optJSONArray("genres")?.optJSONObject(0)?.let { str(it, "name") }
                val length = if (item.type == "movie") {
                    val m = j.optInt("runtime", 0)
                    if (m > 0) (if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m") else null
                } else {
                    val s = j.optInt("number_of_seasons", 0)
                    when {
                        s == 1 -> "1 temporada"
                        s > 1 -> "$s temporadas"
                        else -> null
                    }
                }
                val year = (str(j, "release_date") ?: str(j, "first_air_date"))?.take(4)
                val provider = j.optJSONObject("watch/providers")
                    ?.optJSONObject("results")
                    ?.optJSONObject("BR")
                    ?.optJSONArray("flatrate")
                    ?.optJSONObject(0)
                val providerName = str(provider, "provider_name")
                val providerId = provider?.optInt("provider_id")
                Details(
                    meta = listOfNotNull(genre, length ?: year, providerName).joinToString(" · "),
                    providerId = providerId,
                    providerName = providerName,
                )
            } catch (t: Throwable) {
                null
            }
            main.post {
                if (d != null) {
                    details[k] = d
                    cb(d)
                }
            }
        }
    }

    fun image(path: String, size: String, cb: (Bitmap?) -> Unit) {
        val k = size + path
        images.get(k)?.let { cb(it); return }
        pool.execute {
            val bmp = try {
                val c = URL("$IMG$size$path").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                try {
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    c.disconnect()
                }
            } catch (t: Throwable) {
                null
            }
            main.post {
                if (bmp != null) images.put(k, bmp)
                cb(bmp)
            }
        }
    }

    /** Desfoque barato: reduz muito a imagem e deixa a tela ampliar suavizando. */
    fun blur(b: Bitmap): Bitmap {
        val w = 72
        val h = (w * b.height / b.width.coerceAtLeast(1)).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}
