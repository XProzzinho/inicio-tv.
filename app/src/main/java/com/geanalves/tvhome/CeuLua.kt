package com.geanalves.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Céu do MODO ESCURO com o tempo de verdade de Pirapora:
 *  - Via Láctea e estrelas piscando (céu limpo)
 *  - a LUA de verdade (foto), na fase do dia e virada como a gente vê do Brasil
 *  - a lua SOBE e DESCE no cantinho da direita conforme a altura dela no céu de Pirapora;
 *    quando está baixa some atrás dos filmes, quando não nasceu não aparece
 *  - nuvens passando, chuva caindo e relâmpago conforme o clima
 */
class CeuLua(ctx: Context) : View(ctx) {

    companion object {
        /** Código do tempo (Open-Meteo); -1 = ainda não sabe. Atualizado pelo Weather. */
        @Volatile var codigo = -1
        /** Para teste: força o clima, a fase e a altura da lua. */
        var forcaCodigo: Int? = null
        var forcaFase: Float? = null
        var forcaAlt: Float? = null

        private const val LAT = -17.35   // Pirapora
        private const val LON = -44.94

        fun faseLua(): Float {
            forcaFase?.let { return it }
            val dias = (System.currentTimeMillis() - 947182440000L) / 86400000.0
            val f = (dias / 29.530588853) % 1.0
            return (if (f < 0) f + 1 else f).toFloat()
        }

        /** Altura da lua no céu de Pirapora, em graus (negativo = abaixo do horizonte). */
        fun alturaLua(ms: Long = System.currentTimeMillis()): Float {
            forcaAlt?.let { return it }
            val rad = PI / 180
            val d = ms / 86400000.0 + 2440587.5 - 2451545.0
            val L = 218.316 + 13.176396 * d
            val M = 134.963 + 13.064993 * d
            val F = 93.272 + 13.229350 * d
            val lam = (L + 6.289 * sin(M * rad)) * rad
            val bet = (5.128 * sin(F * rad)) * rad
            val eps = (23.439 - 0.0000004 * d) * rad
            val ra = atan2(sin(lam) * cos(eps) - tan(bet) * sin(eps), cos(lam))
            val dec = asin(sin(bet) * cos(eps) + cos(bet) * sin(eps) * sin(lam))
            val gmst = (280.46061837 + 360.98564736629 * d) * rad
            val h = gmst + LON * rad - ra
            val phi = LAT * rad
            return (asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)) / rad).toFloat()
        }
    }

    private enum class Tipo { LIMPO, POUCAS, MUITAS, FECHADO }
    private class Clima(val nuvem: Tipo, val chuva: Float, val raio: Boolean)

    private fun clima(): Clima = when (forcaCodigo ?: codigo) {
        1 -> Clima(Tipo.POUCAS, 0f, false)
        2 -> Clima(Tipo.MUITAS, 0f, false)
        3, 45, 48 -> Clima(Tipo.FECHADO, 0f, false)
        51, 53, 55, 56, 57 -> Clima(Tipo.MUITAS, 0.6f, false)
        61, 80 -> Clima(Tipo.POUCAS, 1f, false)
        63, 81 -> Clima(Tipo.MUITAS, 1.5f, false)
        65, 66, 67, 82 -> Clima(Tipo.FECHADO, 2.2f, false)
        95, 96, 99 -> Clima(Tipo.FECHADO, 2.4f, true)
        else -> Clima(Tipo.LIMPO, 0f, false)
    }

    private val opc = BitmapFactory.Options().apply { inScaled = false }
    private fun carrega(id: Int): Bitmap? = try { BitmapFactory.decodeResource(resources, id, opc) } catch (e: Throwable) { null }
    private val fundo = carrega(R.drawable.ceu_noite)
    private val lua = carrega(R.drawable.lua_cheia)
    private var nuvemImg: Bitmap? = null
    private var nuvemTipo: Tipo? = null

    private val rnd = java.util.Random(9)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val tela = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val dst = Rect(); private val rf = RectF()
    private val luzPath = Path(); private val espelho = Matrix(); private val raioPath = Path()
    private val tonsBaixa = LightingColorFilter(0xFFFFE2C2.toInt(), 0x00140800)

    private val estrelas = Array(130) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.8f, 0.7f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f, 0.5f + rnd.nextFloat() * 1.4f, rnd.nextFloat()) }
    private val gotas = Array(700) { floatArrayOf(rnd.nextFloat() * 1.3f - 0.2f, rnd.nextFloat(), rnd.nextFloat(), 0.7f + rnd.nextFloat() * 0.6f, rnd.nextFloat()) }

    private val t0 = System.currentTimeMillis()
    private var ultimo = t0
    private var media = 0.04f
    private var leve = false
    private var desloc = 0f
    private var altCache = 0f; private var altEm = 0L
    private var cadT = -100f; private var proxCad = 5f; private var cadX = 0f; private var cadY = 0f
    private var flashT = -100f; private var proxFlash = 4f

    private val d get() = resources.displayMetrics.density

    private fun nuvemDe(t: Tipo): Bitmap? {
        if (t == Tipo.LIMPO) return null
        if (t != nuvemTipo) {
            nuvemImg = carrega(when (t) { Tipo.POUCAS -> R.drawable.nuvem_poucas; Tipo.MUITAS -> R.drawable.nuvem_muitas; else -> R.drawable.nuvem_fechado })
            nuvemTipo = t
        }
        return nuvemImg
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
        val agora = System.currentTimeMillis(); val dt = ((agora - ultimo) / 1000f).coerceIn(0f, 0.1f); ultimo = agora
        val t = (agora - t0) / 1000f
        media = media * 0.95f + dt * 0.05f
        if (!leve && media > 0.07f) leve = true else if (leve && media < 0.045f) leve = false
        val cl = clima()

        // 1) céu com a Via Láctea
        if (fundo != null) { dst.set(0, 0, width, height); c.drawBitmap(fundo, null, dst, img) } else c.drawColor(0xFF0A1430.toInt())
        if (cl.chuva > 0f) c.drawColor(Color.argb((255 * (0.45f + 0.12f * cl.chuva)).toInt().coerceAtMost(220), 8, 12, 22))
        else if (cl.nuvem == Tipo.FECHADO) c.drawColor(0x8C080C16.toInt())

        // 2) estrelas piscando (somem com nuvem e chuva)
        val visEst = when { cl.chuva > 0f -> 0.15f; cl.nuvem == Tipo.FECHADO -> 0.1f; cl.nuvem == Tipo.MUITAS -> 0.5f; cl.nuvem == Tipo.POUCAS -> 0.8f; else -> 1f }
        for ((i, s) in estrelas.withIndex()) {
            if (leve && i % 2 == 1) continue
            val a = visEst * (0.35f + 0.65f * (0.5f + 0.5f * sin(t * s[4] + s[3])))
            if (a < 0.04f) continue
            p.color = when { s[5] < 0.15f -> Color.argb((a * 255).toInt(), 255, 215, 180); s[5] < 0.3f -> Color.argb((a * 255).toInt(), 190, 210, 255); else -> Color.argb((a * 255).toInt(), 255, 250, 240) }
            c.drawCircle(s[0] * w, s[1] * h, s[2] * d * 0.8f, p)
        }

        // 3) estrela cadente (só com céu limpo)
        if (cl.nuvem == Tipo.LIMPO && cl.chuva == 0f) cadente(c, w, h, t)

        // 4) LUA no cantinho da direita, na altura de verdade
        if (agora - altEm > 30000 || forcaAlt != null) { altCache = alturaLua(agora); altEm = agora }
        val alt = altCache
        val f = faseLua()
        val luaVisivel = alt > -4f && f > 0.025f && f < 0.975f
        val lx = w * 0.9635f
        val horiz = h * 0.389f; val topo = h * 0.104f
        val ly = horiz - (alt.coerceAtLeast(-6f) / 90f) * (horiz - topo)
        val baixa = alt < 18f
        val r = h * 0.0593f * (if (baixa) 1.15f else 1f)
        val cheia = 1f - abs(f - 0.5f) * 2f
        val tampa = when (cl.nuvem) { Tipo.FECHADO -> 0.35f; Tipo.MUITAS -> 0.85f; else -> 1f } * (if (cl.chuva >= 2f) 0.8f else 1f)
        if (luaVisivel) {
            // luar em volta
            val halo = r * (if (cl.chuva > 0f) 7.5f else 5f)
            val hc = if (baixa) Color.rgb(255, 210, 160) else Color.rgb(200, 215, 255)
            val ha = ((0.10f + 0.22f * cheia) * tampa * 255).toInt()
            p.shader = RadialGradient(lx, ly, halo, intArrayOf(Color.argb(ha, Color.red(hc), Color.green(hc), Color.blue(hc)), 0), floatArrayOf(0.12f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(lx, ly, halo, p); p.shader = null
            desenhaLua(c, lx, ly, r, f, baixa, tampa)
        }

        // 5) NUVENS passando
        nuvemDe(cl.nuvem)?.let { nb ->
            desloc = (desloc + dt * w * (if (cl.raio) 0.012f else 0.006f)) % (w * 2f)
            val nh = h * 1.05f
            img.alpha = 255
            rf.set(-desloc, 0f, -desloc + w * 2f, nh); c.drawBitmap(nb, null, rf, img)
            rf.offset(w * 2f, 0f); c.drawBitmap(nb, null, rf, img)
            // luar iluminando as nuvens perto da lua
            if (luaVisivel) {
                val lr = r * 6f
                tela.shader = RadialGradient(lx, ly, lr, intArrayOf(Color.argb((70 * (0.4f + 0.6f * cheia)).toInt(), 190, 205, 235), 0), null, Shader.TileMode.CLAMP)
                c.drawCircle(lx, ly, lr, tela); tela.shader = null
                // a lua aparece por cima das nuvens finas (como na prévia)
                if (cl.nuvem != Tipo.FECHADO) desenhaLua(c, lx, ly, r, f, baixa, 0.55f * tampa)
            }
        }

        // 6) CHUVA
        if (cl.chuva > 0f) chuva(c, w, h, dt, cl.chuva)
        // 7) RELÂMPAGO
        if (cl.raio) relampago(c, w, h, t)

        // véu leve pra os textos aparecerem
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x1A060A14, 0x00060A14, 0x59060A14), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null

        postInvalidateDelayed(if (leve) 80 else 40)
    }

    /** Lua com a fase: parte acesa desenhada forte, parte escura bem fraquinha (luz da Terra). */
    private fun desenhaLua(c: Canvas, lx: Float, ly: Float, r: Float, f: Float, baixa: Boolean, alfa: Float) {
        val b = lua ?: return
        rf.set(lx - r, ly - r, lx + r, ly + r)
        img.colorFilter = if (baixa) tonsBaixa else null
        // lado escuro (quase invisível)
        img.alpha = (22 * alfa).toInt(); c.drawBitmap(b, null, rf, img)
        // lado aceso
        luzPath.reset()
        val e = r * cos(2.0 * PI * f).toFloat()     // meia-largura da linha de sombra
        val crescente = f < 0.5f
        // monta no jeito do hemisfério norte (acesa à direita quando crescente)...
        luzPath.arcTo(RectF(lx - r, ly - r, lx + r, ly + r), -90f, 180f, true)
        val ae = abs(e).coerceAtLeast(0.5f)
        luzPath.arcTo(RectF(lx - ae, ly - r, lx + ae, ly + r), 90f, if (e > 0) -180f else 180f, false)
        luzPath.close()
        // ...e espelha: minguante acende do outro lado; no hemisfério SUL tudo fica invertido
        val norteDireita = crescente
        val sulDireita = !norteDireita
        if (!sulDireita) { espelho.reset(); espelho.setScale(-1f, 1f, lx, ly); luzPath.transform(espelho) }
        c.save(); c.clipPath(luzPath)
        img.alpha = (255 * alfa).toInt().coerceIn(0, 255); c.drawBitmap(b, null, rf, img)
        c.restore()
        img.alpha = 255; img.colorFilter = null
    }

    private fun cadente(c: Canvas, w: Float, h: Float, t: Float) {
        if (t > proxCad) { cadT = t; cadX = w * (0.25f + rnd.nextFloat() * 0.55f); cadY = h * (0.03f + rnd.nextFloat() * 0.2f); proxCad = t + 4f + rnd.nextFloat() * 6f }
        val u = (t - cadT) / 1.4f
        if (u !in 0f..1f) return
        val dx = -w * 0.42f; val dy = h * 0.30f
        val hx = cadX + dx * u; val hy = cadY + dy * u
        val forca = sin(PI.toFloat() * u)
        val tx = hx - dx * 0.35f * (0.4f + 0.6f * forca); val ty = hy - dy * 0.35f * (0.4f + 0.6f * forca)
        traco.shader = LinearGradient(hx, hy, tx, ty, Color.argb((forca * 255).toInt(), 255, 252, 240), 0x00FFFFFF, Shader.TileMode.CLAMP)
        traco.strokeWidth = d * 2.6f; c.drawLine(hx, hy, tx, ty, traco); traco.shader = null
        p.color = Color.argb((forca * 255).toInt(), 255, 255, 255); c.drawCircle(hx, hy, d * 2.6f, p)
    }

    private fun chuva(c: Canvas, w: Float, h: Float, dt: Float, forca: Float) {
        val n = (260 * forca).toInt().coerceAtMost(gotas.size).let { if (leve) it / 2 else it }
        val incl = 0.22f
        val vel = if (forca < 0.8f) 0.75f else 1.45f
        for (i in 0 until n) {
            val g = gotas[i]
            g[1] += vel * g[3] * dt
            g[0] += vel * g[3] * dt * incl * h / w
            if (g[1] > 1.05f) { g[1] = -0.05f - rnd.nextFloat() * 0.15f; g[0] = rnd.nextFloat() * 1.3f - 0.25f }
            val x = g[0] * w; val y = g[1] * h
            val l = h * (if (forca < 0.8f) 0.018f else 0.036f + 0.028f * g[2])
            traco.strokeWidth = d * (0.8f + g[2] * 0.6f)
            traco.color = Color.argb((255 * (0.10f + 0.16f * g[4])).toInt(), 205, 220, 240)
            c.drawLine(x, y, x - l * incl, y - l, traco)
        }
        // névoa da chuva embaixo
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0x00788CAA, Color.argb((255 * 0.07f * forca).toInt().coerceAtMost(255), 120, 140, 170), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.45f, w, h, p); p.shader = null
    }

    private fun relampago(c: Canvas, w: Float, h: Float, t: Float) {
        if (t > proxFlash) {
            flashT = t; proxFlash = t + 5f + rnd.nextFloat() * 8f
            raioPath.reset()
            var x = w * (0.15f + rnd.nextFloat() * 0.5f); var y = 0f
            raioPath.moveTo(x, y)
            while (y < h * 0.40f) { x += (rnd.nextFloat() - 0.5f) * w * 0.05f; y += h * (0.03f + rnd.nextFloat() * 0.04f); raioPath.lineTo(x, y) }
        }
        val u = t - flashT
        if (u < 0f || u > 0.6f) return
        val f = when { u < 0.08f -> 1f; u < 0.16f -> 0.25f; u < 0.24f -> 0.85f; else -> (0.6f - u) / 0.36f * 0.5f }.coerceIn(0f, 1f)
        c.drawColor(Color.argb((f * 90).toInt(), 215, 225, 255))
        if (u < 0.3f) {
            traco.style = Paint.Style.STROKE
            traco.strokeWidth = d * 6f; traco.color = Color.argb((f * 70).toInt(), 190, 205, 255); c.drawPath(raioPath, traco)
            traco.strokeWidth = d * 2f; traco.color = Color.argb((f * 255).toInt(), 255, 255, 255); c.drawPath(raioPath, traco)
            traco.style = Paint.Style.FILL
        }
    }
}
