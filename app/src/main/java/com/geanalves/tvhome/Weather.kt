package com.geanalves.tvhome

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Clima atual de Pirapora (MG) pelo Open-Meteo (grátis, sem chave). */
object Weather {
    private const val CITY = "Pirapora"
    private const val URL_NOW = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=-17.345&longitude=-44.942" +
        "&current=temperature_2m,weather_code&timezone=America%2FSao_Paulo"

    private val main = Handler(Looper.getMainLooper())
    private var last: String? = null
    private var lastAt = 0L

    /** Devolve algo como "31° · Céu limpo · Pirapora". Atualiza no máximo a cada 20 minutos. */
    fun now(cb: (String?) -> Unit) {
        val fresh = System.currentTimeMillis() - lastAt < 20 * 60_000
        if (last != null && fresh) {
            cb(last)
            return
        }
        Thread {
            val text = try {
                val c = URL(URL_NOW).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                val json = try {
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
                val cur = json.getJSONObject("current")
                val temp = cur.getDouble("temperature_2m").roundToInt()
                "$temp° · ${describe(cur.optInt("weather_code", -1))} · $CITY"
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (text != null) {
                    last = text
                    lastAt = System.currentTimeMillis()
                }
                cb(text ?: last)
            }
        }.start()
    }

    private const val URL_WEEK = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=-17.345&longitude=-44.942" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
        "&forecast_days=7&timezone=America%2FSao_Paulo"

    /** Previsão de 7 dias: "Qui, 29 · 33° / 21° · Céu limpo". Lista vazia se não conseguir. */
    fun week(cb: (List<String>) -> Unit) {
        Thread {
            val lines = try {
                val c = URL(URL_WEEK).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                val json = try {
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    c.disconnect()
                }
                val d = json.getJSONObject("daily")
                val days = d.getJSONArray("time")
                val codes = d.getJSONArray("weather_code")
                val max = d.getJSONArray("temperature_2m_max")
                val min = d.getJSONArray("temperature_2m_min")
                val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val outFmt = java.text.SimpleDateFormat("EEE, d", java.util.Locale("pt", "BR"))
                (0 until days.length()).map { i ->
                    val date = inFmt.parse(days.getString(i))
                    val dia = if (i == 0) "Hoje" else outFmt.format(date!!)
                        .replace(".", "")
                        .replaceFirstChar { it.uppercase() }
                    val hi = max.getDouble(i).roundToInt()
                    val lo = min.getDouble(i).roundToInt()
                    "$dia  ·  $hi° / $lo°  ·  ${describe(codes.optInt(i, -1))}"
                }
            } catch (_: Throwable) {
                emptyList()
            }
            main.post { cb(lines) }
        }.start()
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Céu limpo"
        1 -> "Poucas nuvens"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Neblina"
        51, 53, 55, 56, 57 -> "Garoa"
        61, 63, 66, 80, 81 -> "Chuva"
        65, 67, 82 -> "Chuva forte"
        71, 73, 75, 77, 85, 86 -> "Neve"
        95 -> "Trovoadas"
        96, 99 -> "Tempestade"
        else -> "Tempo agora"
    }
}
