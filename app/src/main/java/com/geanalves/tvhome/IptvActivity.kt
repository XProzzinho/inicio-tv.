package com.geanalves.tvhome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Ajudas de desenho iguais às da tela inicial. */
private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
private fun Activity.dpf(v: Int) = v * resources.displayMetrics.density
private fun redondo(cor: Int, raio: Float) = GradientDrawable().apply {
    cornerRadius = raio
    setColor(cor)
}

/**
 * TV ao vivo: grupos em pílulas no topo e os canais em cartões brancos,
 * com o mesmo visual do Início TV (preto quando seleciona).
 */
class IptvActivity : Activity() {

    private lateinit var grade: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var info: TextView
    private val chips = ArrayList<TextView>()
    private var grupoAtual = Iptv.TODOS
    private val colunas = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cores.manual = getSharedPreferences("inicio", MODE_PRIVATE).getBoolean("modo_escuro", false)
        Cores.atualizar()
        setContentView(montar())
        carregar(false)
    }

    override fun onResume() {
        super.onResume()
        // Volta do player: atualiza os favoritos
        if (Iptv.canais.isNotEmpty() && grupoAtual == Iptv.FAVORITOS) mostrarCanais()
    }

    private fun montar(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Cores.fundoTopo, Cores.fundoBase))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), 0)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        col.addView(TextView(this).apply {
            text = "TV ao vivo"
            textSize = 36f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        info = TextView(this).apply {
            textSize = 16f
            setTextColor(Cores.cinza)
            setPadding(0, dp(2), 0, dp(10))
            text = "Carregando a lista…"
        }
        col.addView(info)

        // Grupos (Favoritos, Todos, Notícias…) em pílulas
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        chipsScroll.addView(chipsRow)
        col.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(8), dp(6), dp(40))
        }
        grade = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(grade)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun carregar(forcar: Boolean) {
        info.text = "Carregando a lista…"
        Iptv.carregar(this, forcar) { erro ->
            if (isFinishing) return@carregar
            when (erro) {
                null -> {
                    montarChips()
                    // Começa em Favoritos se tiver algum
                    grupoAtual = if (Iptv.doGrupo(this, Iptv.FAVORITOS).isNotEmpty()) Iptv.FAVORITOS else Iptv.TODOS
                    pintarChips()
                    mostrarCanais()
                }
                "sem lista" -> vazio(
                    "Nenhuma lista de canais ainda.\n" +
                        "No celular, abra o Controle TV › Administração › TV ao vivo e cole o link da sua lista M3U.",
                )
                else -> vazio("$erro. Confira a internet e o link da lista, e aperte OK para tentar de novo.")
            }
        }
    }

    private fun vazio(msg: String) {
        info.text = ""
        chipsRow.removeAllViews()
        grade.removeAllViews()
        grade.addView(cartaoTexto(msg) { carregar(true) }, LinearLayout.LayoutParams(-1, -2))
        grade.getChildAt(0).requestFocus()
    }

    private fun cartaoTexto(msg: String, acao: () -> Unit): View {
        val bg = redondo(Cores.janela, dpf(22))
        return TextView(this).apply {
            text = msg
            textSize = 20f
            setTextColor(Cores.tinta)
            background = bg
            setPadding(dp(28), dp(24), dp(28), dp(24))
            isFocusable = true
            isClickable = true
            setOnClickListener { acao() }
        }
    }

    // ---------- grupos ----------

    private fun montarChips() {
        chipsRow.removeAllViews()
        chips.clear()
        for (g in Iptv.grupos(this)) {
            val bg = redondo(Color.TRANSPARENT, dpf(20))
            val t = TextView(this).apply {
                text = g
                textSize = 17f
                setPadding(dp(16), dp(7), dp(16), dp(7))
                background = bg
                isFocusable = true
                isClickable = true
                tag = g
                setOnFocusChangeListener { _, has ->
                    if (has && grupoAtual != g) {
                        grupoAtual = g
                        mostrarCanais()
                    }
                    pintarChips()
                }
                setOnClickListener {
                    grupoAtual = g
                    mostrarCanais()
                    pintarChips()
                }
            }
            chips += t
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(6)
            chipsRow.addView(t, lp)
        }
    }

    private fun pintarChips() {
        for (t in chips) {
            val bg = t.background as GradientDrawable
            when {
                t.isFocused -> {
                    bg.setColor(Cores.tinta)
                    t.setTextColor(Cores.papel)
                }
                t.tag == grupoAtual -> {
                    bg.setColor(Cores.sutil)
                    t.setTextColor(Cores.tinta)
                }
                else -> {
                    bg.setColor(Color.TRANSPARENT)
                    t.setTextColor(Cores.cinza)
                }
            }
        }
    }

    // ---------- canais ----------

    private fun mostrarCanais() {
        grade.removeAllViews()
        val lista = Iptv.doGrupo(this, grupoAtual)
        info.text = "${Iptv.canais.size} canais · $grupoAtual: ${lista.size}"
        if (lista.isEmpty()) {
            grade.addView(
                TextView(this).apply {
                    text = if (grupoAtual == Iptv.FAVORITOS) {
                        "Sem favoritos. Assistindo um canal, segure OK para favoritar."
                    } else {
                        "Nenhum canal aqui."
                    }
                    textSize = 18f
                    setTextColor(Cores.cinza)
                },
            )
            return
        }
        val favs = Iptv.favoritos(this)
        val larg = (resources.displayMetrics.widthPixels - dp(112 + 12) - dp(16) * (colunas - 1)) / colunas
        var linha: LinearLayout? = null
        // Até 600 canais por grupo para não pesar a TV
        lista.take(600).forEachIndexed { i, c ->
            if (i % colunas == 0) {
                linha = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                grade.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
            val lp = LinearLayout.LayoutParams(larg, (larg * 0.72f).toInt())
            if (i % colunas != colunas - 1) lp.rightMargin = dp(16)
            linha!!.addView(cartao(c, i, c.url in favs), lp)
        }
    }

    private fun cartao(c: Iptv.Canal, i: Int, fav: Boolean): View {
        val bg = redondo(Cores.janela, dpf(18))
        val anel = GradientDrawable().apply {
            cornerRadius = dpf(18)
            setColor(Color.TRANSPARENT)
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(12), dp(14), dp(4))
        }
        // Sem logo: a primeira letra grande
        val letra = TextView(this).apply {
            text = c.nome.trim().take(1).uppercase()
            textSize = 30f
            setTextColor(Cores.cinza)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val nome = TextView(this).apply {
            text = (if (fav) "★ " else "") + c.nome
            textSize = 14f
            setTextColor(Cores.tinta)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        val topo = FrameLayout(this)
        topo.addView(letra, FrameLayout.LayoutParams(-1, -1))
        topo.addView(img, FrameLayout.LayoutParams(-1, -1))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            foreground = anel
            elevation = dpf(2)
            isFocusable = true
            isClickable = true
            addView(topo, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nome)
            setOnClickListener {
                startActivity(
                    Intent(this@IptvActivity, PlayerActivity::class.java)
                        .putExtra("grupo", grupoAtual)
                        .putExtra("indice", i),
                )
            }
            setOnFocusChangeListener { v, has ->
                anel.setStroke(if (has) dp(4) else 0, Cores.tinta)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                    .translationZ(if (has) dpf(8) else 0f).setDuration(140).start()
            }
        }
        c.logo?.let { u ->
            Iptv.logo(u) { b ->
                if (b != null) {
                    img.setImageBitmap(b)
                    letra.visibility = View.INVISIBLE
                }
            }
        }
        return card
    }
}

/**
 * Assistindo um canal: tela cheia.
 * Cima = próximo canal, baixo = anterior, OK = informações, segurar OK = favorito.
 */
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var faixa: LinearLayout
    private lateinit var faixaNome: TextView
    private lateinit var faixaSub: TextView
    private lateinit var faixaLogo: ImageView
    private lateinit var aviso: TextView
    private var lista: List<Iptv.Canal> = emptyList()
    private var indice = 0
    private val h = Handler(Looper.getMainLooper())
    private val esconder = Runnable { faixa.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lista = Iptv.doGrupo(this, intent.getStringExtra("grupo") ?: Iptv.TODOS)
        indice = intent.getIntExtra("indice", 0)
        if (lista.isEmpty()) {
            finish()
            return
        }

        val fonte = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("InicioTV")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(fonte))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                aviso.text = "Este canal está fora do ar agora.\nUse cima/baixo para trocar."
                aviso.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) aviso.visibility = View.GONE
                if (state == Player.STATE_BUFFERING && aviso.visibility != View.VISIBLE) {
                    // nada: o vídeo mostra o carregamento sozinho
                }
            }
        })

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val tela = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            this.player = this@PlayerActivity.player
        }
        root.addView(tela, FrameLayout.LayoutParams(-1, -1))

        // Faixa branca embaixo com o canal (some sozinha)
        faixa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = redondo(Cores.janela, dpf(24))
            elevation = dpf(6)
            setPadding(dp(14), dp(12), dp(24), dp(12))
        }
        faixaLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = redondo(Cores.campo, dpf(14))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        faixa.addView(faixaLogo, LinearLayout.LayoutParams(dp(72), dp(52)))
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        faixaNome = TextView(this).apply {
            textSize = 22f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        faixaSub = TextView(this).apply {
            textSize = 14f
            setTextColor(Cores.cinza)
        }
        txt.addView(faixaNome)
        txt.addView(faixaSub)
        faixa.addView(txt)
        root.addView(
            faixa,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(48)
                bottomMargin = dp(40)
            },
        )

        aviso = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = redondo(0xCC000000.toInt(), dpf(20))
            setPadding(dp(28), dp(18), dp(28), dp(18))
            visibility = View.GONE
        }
        root.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        tocar()
    }

    private fun tocar() {
        val c = lista[indice]
        Iptv.salvarUltimo(this, c)
        aviso.visibility = View.GONE
        val item = MediaItem.Builder().setUri(Uri.parse(c.url)).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        mostrarFaixa()
    }

    private fun mostrarFaixa() {
        val c = lista[indice]
        val fav = c.url in Iptv.favoritos(this)
        faixaNome.text = "${indice + 1}  ${c.nome}"
        faixaSub.text = c.grupo + (if (fav) "  ·  ★ Favorito" else "  ·  Segure OK para favoritar")
        faixaLogo.setImageDrawable(null)
        c.logo?.let { u -> Iptv.logo(u) { b -> if (b != null && lista[indice] == c) faixaLogo.setImageBitmap(b) } }
        faixa.animate().cancel()
        faixa.alpha = 1f
        h.removeCallbacks(esconder)
        h.postDelayed(esconder, 4000)
    }

    private fun trocar(passo: Int) {
        indice = (indice + passo + lista.size) % lista.size
        tocar()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                trocar(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                trocar(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val agora = Iptv.trocarFavorito(this, lista[indice])
            Toast.makeText(this, if (agora) "★ Favorito" else "Tirado dos favoritos", Toast.LENGTH_SHORT).show()
            mostrarFaixa()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) mostrarFaixa()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onDestroy() {
        h.removeCallbacks(esconder)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
