package com.geanalves.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Papel de parede do TEMPO DE VERDADE em Pirapora: o céu muda conforme o clima e a hora.
 * Sol com raios, poucas nuvens, nublado, garoa, chuva, tempestade com raios, neblina,
 * amanhecer, pôr do sol, noite estrelada com a lua na fase certa (lua cheia brilha mais).
 *  - claro = versão clarinha (modo claro da tela inicial)
 *  - descanso = versão forte, pra tela de descanso
 */
class Cena(ctx: Context, private val claro: Boolean = false, private val descanso: Boolean = false) : View(ctx) {

    enum class Tempo { LIMPO, PARCIAL, NUBLADO, NEBLINA, GAROA, CHUVA, TEMPESTADE }
    enum class Periodo { AMANHECER, DIA, ENTARDECER, NOITE }

    companion object {
        /** Código do tempo (Open-Meteo) mais recente; -1 = ainda não sabe. */
        @Volatile var codigo = -1
        /** Para testar: força um código e uma hora. */
        var forcaCodigo: Int? = null
        var forcaHora: Int? = null

        fun tempo(c: Int = forcaCodigo ?: codigo): Tempo = when (c) {
            1, 2 -> Tempo.PARCIAL
            3, 71, 73, 75, 77, 85, 86 -> Tempo.NUBLADO
            45, 48 -> Tempo.NEBLINA
            51, 53, 55, 56, 57 -> Tempo.GAROA
            61, 63, 65, 66, 67, 80, 81, 82 -> Tempo.CHUVA
            95, 96, 99 -> Tempo.TEMPESTADE
            else -> Tempo.LIMPO
        }

        fun hora(): Int = forcaHora ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        fun periodo(h: Int = hora()): Periodo = when (h) {
            5, 6 -> Periodo.AMANHECER
            in 7..16 -> Periodo.DIA
            17, 18 -> Periodo.ENTARDECER
            else -> Periodo.NOITE
        }

        /** Fase da lua de 0 (nova) a 1 (nova de novo); 0,5 = cheia. */
        fun faseLua(): Float {
            val dias = (System.currentTimeMillis() - 947182440000L) / 86400000.0
            val f = (dias / 29.530588853) % 1.0
            return (if (f < 0) f + 1 else f).toFloat()
        }

        fun luaCheia() = faseLua() in 0.46f..0.54f

        /** Frase que aparece embaixo na tela de descanso. */
        fun descricao(): String {
            val p = periodo(); val t = tempo()
            val noite = p == Periodo.NOITE
            return when (t) {
                Tempo.TEMPESTADE -> "Tempestade em Pirapora"
                Tempo.CHUVA -> if (noite) "Noite de chuva em Pirapora" else "Chovendo em Pirapora"
                Tempo.GAROA -> "Garoa fininha em Pirapora"
                Tempo.NEBLINA -> "Neblina em Pirapora"
                Tempo.NUBLADO -> if (noite) "Noite nublada em Pirapora" else "Céu nublado em Pirapora"
                Tempo.PARCIAL, Tempo.LIMPO -> when {
                    p == Periodo.AMANHECER -> "Amanhecer em Pirapora"
                    p == Periodo.ENTARDECER -> "Pôr do sol em Pirapora"
                    noite && luaCheia() -> "Lua cheia em Pirapora"
                    noite -> "Noite estrelada em Pirapora"
                    t == Tempo.PARCIAL -> "Sol entre nuvens em Pirapora"
                    else -> "Dia de sol em Pirapora"
                }
            }
        }
    }

    private val rnd = java.util.Random(11)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val sprite = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val corta = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val dst = Rect()
    private val rf = RectF()
    private val raioPath = Path()

    private var fundo: Bitmap? = null
    private var chave = ""
    private var tempo = Tempo.LIMPO
    private var periodo = Periodo.NOITE

    private val estrelas = Array(90) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.7f, 0.6f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f, 0.4f + rnd.nextFloat() * 0.9f) }
    private var nuvemImg: Array<Bitmap> = emptyArray()
    /** x, y, escala, velocidade, qual sprite, profundidade */
    private var nuvens: Array<FloatArray> = emptyArray()
    /** x, y, comprimento, velocidade */
    private val gotas = Array(320) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 0.8f, 0.8f + rnd.nextFloat() * 0.6f) }
    private var nevoa: Bitmap? = null
    private val faixas = Array(4) { floatArrayOf(rnd.nextFloat(), 0.45f + it * 0.14f, 0.012f + rnd.nextFloat() * 0.02f) }

    private val t0 = System.currentTimeMillis()
    private var ultimo = t0
    private var media = 0.04f
    private var leve = false
    private var flashT = -100f; private var proxFlash = 3f
    private var cadT = -100f; private var proxCad = 4f; private var cadX = 0f; private var cadY = 0f

    private val d get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { chave = ""; nuvemImg = emptyArray() }

    // ---------- peças desenhadas uma vez ----------

    /** Nuvem fofinha branca (depois é pintada de branco, cinza ou chumbo). */
    private fun fazNuvem(w: Int, h: Int, semente: Long): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val r = java.util.Random(semente); val g = Paint(Paint.ANTI_ALIAS_FLAG)
        val W = w.toFloat(); val H = h.toFloat()
        fun bolha(x: Float, y: Float, rr: Float, a: Int) {
            g.shader = RadialGradient(x, y, rr, intArrayOf(Color.argb(a, 255, 255, 255), Color.argb((a * 0.85f).toInt(), 255, 255, 255), 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rr, g)
        }
        // base larga e achatada + bolhas em cima
        for (i in 0 until 7) bolha(W * (0.14f + i * 0.12f), H * 0.70f, H * (0.26f + r.nextFloat() * 0.08f), 200)
        for (i in 0 until 5) bolha(W * (0.24f + i * 0.13f + r.nextFloat() * 0.05f), H * (0.48f - r.nextFloat() * 0.12f), H * (0.28f + r.nextFloat() * 0.14f), 215)
        bolha(W * (0.42f + r.nextFloat() * 0.15f), H * 0.36f, H * 0.34f, 225)
        return b
    }

    private fun fazNevoa(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val g = Paint(Paint.ANTI_ALIAS_FLAG)
        g.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0, Color.WHITE, 0), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        // pontas apagadas
        g.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Color.BLACK, 0, 0, Color.BLACK), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
        g.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        return b
    }

    private fun cor(a: Int, rgb: Int) = (a shl 24) or (rgb and 0xFFFFFF)

    /** Céu de fundo (cor conforme hora + tempo), feito em meia resolução e guardado. */
    private fun montaFundo(w: Int, h: Int) {
        val b = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val g = Paint(Paint.ANTI_ALIAS_FLAG)
        val W = b.width.toFloat(); val H = b.height.toFloat()
        val noite = periodo == Periodo.NOITE
        val cores: IntArray = when (tempo) {
            Tempo.TEMPESTADE -> if (noite) intArrayOf(0xFF07090E.toInt(), 0xFF141A24.toInt(), 0xFF1E2530.toInt()) else intArrayOf(0xFF1A2029.toInt(), 0xFF2D3540.toInt(), 0xFF434C57.toInt())
            Tempo.CHUVA -> if (noite) intArrayOf(0xFF0A0F18.toInt(), 0xFF151D2A.toInt(), 0xFF222B38.toInt()) else intArrayOf(0xFF2E3A48.toInt(), 0xFF4A5664.toInt(), 0xFF6A7480.toInt())
            Tempo.GAROA -> if (noite) intArrayOf(0xFF0E1420.toInt(), 0xFF1A2230.toInt(), 0xFF283141.toInt()) else intArrayOf(0xFF485666.toInt(), 0xFF6B7886.toInt(), 0xFF8E98A3.toInt())
            Tempo.NUBLADO -> if (noite) intArrayOf(0xFF0F1522.toInt(), 0xFF1B2333.toInt(), 0xFF2A3344.toInt()) else intArrayOf(0xFF5D6B7D.toInt(), 0xFF8592A1.toInt(), 0xFFB8C2CE.toInt())
            Tempo.NEBLINA -> if (noite) intArrayOf(0xFF161C28.toInt(), 0xFF262E3C.toInt(), 0xFF3A4352.toInt()) else intArrayOf(0xFF7F8B98.toInt(), 0xFFA5AFBA.toInt(), 0xFFCDD3DA.toInt())
            else -> when (periodo) {
                Periodo.AMANHECER -> intArrayOf(0xFF2B3A78.toInt(), 0xFFB06A9A.toInt(), 0xFFFFA866.toInt())
                Periodo.ENTARDECER -> intArrayOf(0xFF1F2560.toInt(), 0xFFA0457E.toInt(), 0xFFFF7A45.toInt())
                Periodo.DIA -> intArrayOf(0xFF1560C8.toInt(), 0xFF3D8FE6.toInt(), 0xFF9ACFFF.toInt())
                Periodo.NOITE -> intArrayOf(0xFF050A1C.toInt(), 0xFF0E1A36.toInt(), 0xFF1D2C4E.toInt())
            }
        }
        g.shader = LinearGradient(0f, 0f, 0f, H, cores, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, g)
        fun glow(x: Float, y: Float, r: Float, cr: Int) { g.shader = RadialGradient(x, y, r, cr, 0, Shader.TileMode.CLAMP); c.drawCircle(x, y, r, g) }
        val limpo = tempo == Tempo.LIMPO || tempo == Tempo.PARCIAL
        when {
            limpo && periodo == Periodo.DIA -> glow(W * (if (descanso) 0.80f else 0.635f), H * (if (descanso) 0.18f else 0.11f), W * 0.45f, 0x66FFF2C0)
            limpo && periodo != Periodo.NOITE -> { glow(W * 0.88f, H * 0.86f, W * 0.55f, 0x88FFB060.toInt()); glow(W * 0.88f, H * 0.86f, W * 0.2f, 0x99FFE0A0.toInt()) }
            limpo && noite -> {
                glow(W * 0.30f, H * 0.55f, W * 0.40f, 0x222E5BFF)
                glow(W * (if (descanso) 0.80f else 0.635f), H * (if (descanso) 0.18f else 0.11f), W * (if (luaCheia()) 0.42f else 0.25f), if (luaCheia()) 0x40C8DCFF else 0x22C8DCFF)
            }
            tempo == Tempo.TEMPESTADE || tempo == Tempo.CHUVA -> glow(W * 0.5f, H * 1.1f, W * 0.7f, 0x22000000)
            tempo == Tempo.NEBLINA -> glow(W * 0.5f, H * 0.9f, W * 0.8f, 0x55FFFFFF)
            else -> {}
        }
        // véu pra o texto da tela ficar legível
        when {
            claro -> c.drawColor(if (noite) 0xB8EEF2F8.toInt() else 0x99F7F8FB.toInt())
            descanso -> if (!noite) c.drawColor(0x26000000)
            else -> c.drawColor(if (noite) 0x14000000 else 0x66101828)
        }
        fundo = b
    }

    /** Quantas nuvens e de que cor, conforme o tempo. */
    private fun montaNuvens(w: Int, h: Int) {
        if (nuvemImg.isEmpty()) nuvemImg = Array(3) { fazNuvem((w * 0.20f).toInt().coerceAtLeast(40), (h * 0.11f).toInt().coerceAtLeast(20), 31L + it) }
        val n = when (tempo) {
            Tempo.LIMPO -> if (periodo == Periodo.NOITE) 0 else 2
            Tempo.PARCIAL -> 6; Tempo.NUBLADO -> 11; Tempo.NEBLINA -> 4
            Tempo.GAROA -> 10; Tempo.CHUVA -> 13; Tempo.TEMPESTADE -> 14
        }
        val r = java.util.Random(5)
        nuvens = Array(n) {
            val prof = r.nextFloat()
            floatArrayOf(r.nextFloat() * 1.3f - 0.15f, -0.02f + prof * (if (n > 8) 0.45f else 0.35f), 0.9f + prof * 1.6f, 0.004f + prof * 0.010f, (it % 3).toFloat(), prof)
        }.sortedBy { it[5] }.toTypedArray()
        if (tempo == Tempo.NEBLINA && nevoa == null) nevoa = fazNevoa((w * 0.7f).toInt(), (h * 0.18f).toInt())
    }

    private fun corNuvem(prof: Float): Int {
        val noite = periodo == Periodo.NOITE
        val base = when (tempo) {
            Tempo.TEMPESTADE -> if (noite) 0x262C36 else 0x3A424D
            Tempo.CHUVA -> if (noite) 0x2C3442 else 0x5A6572
            Tempo.GAROA -> if (noite) 0x343D4C else 0x7D8894
            Tempo.NUBLADO -> if (noite) 0x3A4354 else 0xC3CBD5
            else -> when (periodo) {
                Periodo.NOITE -> 0x5A6680
                Periodo.AMANHECER -> 0xFFD2C0
                Periodo.ENTARDECER -> 0xFFB89A
                Periodo.DIA -> 0xFFFFFF
            }
        }
        val a = when {
            claro -> 110 + (prof * 60).toInt()
            else -> 170 + (prof * 80).toInt()
        }
        return cor(a.coerceAtMost(255), base)
    }

    // ---------- desenho a cada quadro ----------

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
        val agora = System.currentTimeMillis(); val dt = ((agora - ultimo) / 1000f).coerceIn(0f, 0.1f); ultimo = agora
        val t = (agora - t0) / 1000f
        media = media * 0.95f + dt * 0.05f
        if (!leve && media > 0.07f) leve = true else if (leve && media < 0.045f) leve = false

        val novoT = tempo(); val novoP = periodo()
        val k = "$novoT/$novoP/${hora()}/${luaCheia()}"
        if (k != chave) { tempo = novoT; periodo = novoP; montaFundo(width, height); montaNuvens(width, height); chave = k }

        fundo?.let { dst.set(0, 0, width, height); c.drawBitmap(it, null, dst, sprite) }
        val noite = periodo == Periodo.NOITE
        val limpo = tempo == Tempo.LIMPO || tempo == Tempo.PARCIAL

        // ESTRELAS (noite limpa; com poucas nuvens aparecem menos)
        if (noite && (limpo || tempo == Tempo.NUBLADO)) {
            val forca = when (tempo) { Tempo.LIMPO -> if (luaCheia()) 0.7f else 1f; Tempo.PARCIAL -> 0.6f; else -> 0.18f }
            for ((ix, s) in estrelas.withIndex()) {
                if (leve && ix % 2 == 1) continue
                val a = forca * (0.3f + 0.7f * (0.5f + 0.5f * sin(t * s[4] + s[3])))
                val x = s[0] * w; val y = s[1] * h; val r = s[2] * d * 0.9f
                p.color = if (claro) Color.argb((a * 120).toInt(), 110, 130, 180) else Color.argb((a * 220).toInt(), 255, 246, 220)
                c.drawCircle(x, y, r, p)
                if (s[2] > 2.1f && a > 0.75f) {
                    traco.strokeWidth = d * 0.8f
                    traco.color = if (claro) Color.argb(((a - 0.75f) * 4 * 100).toInt(), 110, 130, 180) else Color.argb(((a - 0.75f) * 4 * 160).toInt(), 255, 248, 225)
                    val l = r * 5f; c.drawLine(x - l, y, x + l, y, traco); c.drawLine(x, y - l, x, y + l, traco)
                }
            }
        }

        // LUA na fase de verdade (cheia = maior e com luar forte)
        if (noite && tempo != Tempo.CHUVA && tempo != Tempo.TEMPESTADE) desenhaLua(c, w, h, t)

        // SOL com raios girando devagar
        if (!noite && limpo) desenhaSol(c, w, h, t)

        // ESTRELA CADENTE só na noite limpa
        if (noite && tempo == Tempo.LIMPO) cadente(c, w, h, t)

        // NUVENS passando
        for (n in nuvens) {
            n[0] += n[3] * dt * (if (tempo == Tempo.TEMPESTADE) 2.2f else 1f)
            if (n[0] > 1.15f) n[0] = -0.35f
            val img = nuvemImg.getOrNull(n[4].toInt()) ?: continue
            val sw = img.width * n[2]; val sh = img.height * n[2]
            val x = n[0] * w - sw * 0.2f; val y = n[1] * h
            sprite.colorFilter = PorterDuffColorFilter(corNuvem(n[5]), PorterDuff.Mode.SRC_IN)
            rf.set(x, y, x + sw, y + sh)
            c.drawBitmap(img, null, rf, sprite)
        }
        sprite.colorFilter = null

        // NEBLINA: faixas de névoa andando
        if (tempo == Tempo.NEBLINA) nevoa?.let { nb ->
            for (f in faixas) {
                f[0] += f[2] * dt; if (f[0] > 1.1f) f[0] = -0.7f
                val x = f[0] * w; val y = f[1] * h
                sprite.alpha = if (claro) 150 else 120
                rf.set(x, y, x + w * 0.9f, y + h * 0.22f); c.drawBitmap(nb, null, rf, sprite)
                rf.offset(-w * 1.6f, 0f); c.drawBitmap(nb, null, rf, sprite)
            }
            sprite.alpha = 255
        }

        // CHUVA / GAROA
        if (tempo == Tempo.CHUVA || tempo == Tempo.TEMPESTADE || tempo == Tempo.GAROA) chuva(c, w, h, dt)

        // RELÂMPAGO
        if (tempo == Tempo.TEMPESTADE) relampago(c, w, h, t)

        postInvalidateDelayed(if (leve) 80 else 40)
    }

    private fun desenhaLua(c: Canvas, w: Float, h: Float, t: Float) {
        val f = faseLua(); if (f < 0.03f || f > 0.97f) return
        val cheia = f in 0.46f..0.54f
        val r = (if (descanso) 34f else 24f) * d * (if (cheia) 1.15f else 1f)
        val lx = w * (if (descanso) 0.80f else 0.635f); val ly = h * (if (descanso) 0.18f else 0.11f)
        val atras = if (tempo == Tempo.NUBLADO || tempo == Tempo.NEBLINA || tempo == Tempo.GAROA) 0.35f else 1f
        if (!claro) {
            val pul = 0.85f + 0.15f * sin(t * 0.5f)
            p.shader = RadialGradient(lx, ly, r * (if (cheia) 6f else 3.2f), Color.argb((atras * pul * (if (cheia) 90 else 50)).toInt(), 210, 225, 255), 0, Shader.TileMode.CLAMP)
            c.drawCircle(lx, ly, r * (if (cheia) 6f else 3.2f), p); p.shader = null
        } else { p.color = 0x14000000; c.drawCircle(lx, ly + d, r * 1.05f, p) }
        val camada = c.saveLayer(lx - r * 2, ly - r * 2, lx + r * 2, ly + r * 2, null)
        p.color = if (claro) 0xFFDCE3EE.toInt() else Color.argb((255 * atras).toInt().coerceAtLeast(110), 255, 246, 220)
        c.drawCircle(lx, ly, r, p)
        // manchinhas da lua
        p.color = if (claro) 0x22566080 else 0x1E8A7A55
        c.drawCircle(lx - r * 0.3f, ly - r * 0.2f, r * 0.22f, p); c.drawCircle(lx + r * 0.25f, ly + r * 0.3f, r * 0.16f, p); c.drawCircle(lx + r * 0.1f, ly - r * 0.45f, r * 0.1f, p)
        val k = f * 2f
        val desloca = if (k < 1f) -2f * r * k else 2f * r * (2f - k)
        if (abs(desloca) < 2f * r) c.drawCircle(lx + desloca, ly, r * 1.02f, corta)
        c.restoreToCount(camada)
    }

    private fun desenhaSol(c: Canvas, w: Float, h: Float, t: Float) {
        val baixo = periodo != Periodo.DIA
        val sx = w * (if (baixo) 0.88f else if (descanso) 0.80f else 0.635f); val sy = h * (if (baixo) 0.86f else if (descanso) 0.18f else 0.11f)
        val r = (if (descanso) 46f else 28f) * d
        val corSol = if (baixo) 0xFFFFC27A.toInt() else 0xFFFFF4C8.toInt()
        val fraco = if (claro) 0.55f else 1f
        // raios girando
        if (!leve) {
            traco.strokeWidth = r * 0.22f
            for (i in 0 until 12) {
                val ang = t * 0.05f + i * (PI.toFloat() / 6f)
                val pul = 0.6f + 0.4f * sin(t * 0.8f + i)
                val l1 = r * 1.5f; val l2 = r * (2.6f + 0.5f * pul)
                traco.shader = LinearGradient(sx + cos(ang) * l1, sy + sin(ang) * l1, sx + cos(ang) * l2, sy + sin(ang) * l2, cor((70 * fraco * pul).toInt(), corSol), 0, Shader.TileMode.CLAMP)
                c.drawLine(sx + cos(ang) * l1, sy + sin(ang) * l1, sx + cos(ang) * l2, sy + sin(ang) * l2, traco)
            }
            traco.shader = null
        }
        p.shader = RadialGradient(sx, sy, r * 3f, cor((110 * fraco).toInt(), corSol), 0, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, r * 3f, p); p.shader = null
        p.color = cor((255 * (if (claro) 0.85f else 1f)).toInt(), corSol); c.drawCircle(sx, sy, r, p)
        p.color = cor((200 * fraco).toInt(), 0xFFFFFF); c.drawCircle(sx, sy, r * 0.7f, p)
    }

    private fun cadente(c: Canvas, w: Float, h: Float, t: Float) {
        if (t > proxCad) { cadT = t; cadX = w * (0.30f + rnd.nextFloat() * 0.5f); cadY = h * (0.03f + rnd.nextFloat() * 0.2f); proxCad = t + 5f + rnd.nextFloat() * 6f }
        val u = (t - cadT) / 1.4f
        if (u !in 0f..1f) return
        val dx = -w * 0.40f; val dy = h * 0.28f
        val hx = cadX + dx * u; val hy = cadY + dy * u
        val forca = sin(PI.toFloat() * u)
        val tx = hx - dx * 0.35f * (0.4f + 0.6f * forca); val ty = hy - dy * 0.35f * (0.4f + 0.6f * forca)
        traco.shader = LinearGradient(hx, hy, tx, ty, if (claro) Color.argb((forca * 200).toInt(), 110, 140, 200) else Color.argb((forca * 255).toInt(), 255, 252, 240), 0x00FFFFFF, Shader.TileMode.CLAMP)
        traco.strokeWidth = d * 2.6f; c.drawLine(hx, hy, tx, ty, traco); traco.shader = null
        p.color = if (claro) Color.argb((forca * 230).toInt(), 100, 130, 200) else Color.argb((forca * 255).toInt(), 255, 255, 255); c.drawCircle(hx, hy, d * 2.6f, p)
    }

    private fun chuva(c: Canvas, w: Float, h: Float, dt: Float) {
        val garoa = tempo == Tempo.GAROA
        val n = when (tempo) { Tempo.GAROA -> 110; Tempo.CHUVA -> 230; else -> 320 }.let { if (leve) it / 2 else it }
        val inclina = if (tempo == Tempo.TEMPESTADE) 0.28f else 0.14f
        val vel = if (garoa) 0.55f else 1.5f
        val comp = (if (garoa) 10f else 26f) * d
        traco.strokeWidth = d * (if (garoa) 1.1f else 1.6f)
        for (i in 0 until n) {
            val g = gotas[i]
            g[1] += vel * g[3] * dt
            g[0] += vel * g[3] * dt * inclina * h / w
            if (g[1] > 1.05f) { g[1] = -0.05f - rnd.nextFloat() * 0.1f; g[0] = rnd.nextFloat() * 1.2f - 0.2f }
            if (g[0] > 1.05f) g[0] -= 1.25f
            val x = g[0] * w; val y = g[1] * h; val l = comp * g[2]
            val a = (0.5f + 0.5f * g[2]) * (if (garoa) 0.7f else 1f)
            traco.color = if (claro) Color.argb((a * 120).toInt(), 70, 95, 130) else Color.argb((a * 120).toInt(), 200, 218, 240)
            c.drawLine(x, y, x - l * inclina, y - l, traco)
        }
    }

    private fun relampago(c: Canvas, w: Float, h: Float, t: Float) {
        if (t > proxFlash) {
            flashT = t; proxFlash = t + 4f + rnd.nextFloat() * 7f
            raioPath.reset()
            var x = w * (0.2f + rnd.nextFloat() * 0.6f); var y = 0f
            raioPath.moveTo(x, y)
            while (y < h * 0.62f) { x += (rnd.nextFloat() - 0.5f) * w * 0.06f; y += h * (0.03f + rnd.nextFloat() * 0.05f); raioPath.lineTo(x, y) }
        }
        val u = t - flashT
        if (u < 0f || u > 0.6f) return
        // dois piscos rápidos
        val f = when { u < 0.08f -> 1f; u < 0.16f -> 0.25f; u < 0.24f -> 0.85f; else -> (0.6f - u) / 0.36f * 0.5f }.coerceIn(0f, 1f)
        c.drawColor(Color.argb((f * (if (claro) 70 else 110)).toInt(), 225, 232, 255))
        if (u < 0.3f) {
            traco.style = Paint.Style.STROKE
            traco.strokeWidth = d * 7f; traco.color = Color.argb((f * 70).toInt(), 190, 205, 255); c.drawPath(raioPath, traco)
            traco.strokeWidth = d * 2.2f; traco.color = Color.argb((f * 255).toInt(), 255, 255, 255); c.drawPath(raioPath, traco)
            traco.style = Paint.Style.FILL
        }
    }
}
