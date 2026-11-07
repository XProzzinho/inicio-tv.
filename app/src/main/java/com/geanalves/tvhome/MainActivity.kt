package com.geanalves.tvhome

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.view.Choreographer
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Tela inicial: "Para você" com filmes/séries em destaque e a barra de apps embaixo. */
class MainActivity : Activity() {

    private data class Card(
        val title: String,
        val item: Tmdb.Item?,
        val app: AppEntry?,
    )


    private lateinit var prefs: Prefs
    private lateinit var bgImage: ImageView
    private lateinit var cardsScroll: HorizontalScrollView
    private lateinit var cardsRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var clock: TextView
    private lateinit var weatherText: TextView
    private lateinit var dateText: TextView

    // ---------- bolinha da TV ao vivo ----------

    private lateinit var lembreteText: TextView

    private fun mostrarLembretes() {
        if (!::lembreteText.isInitialized) return
        val lista = Lembretes.doPerfil(this, prefs.perfil).take(3)
        if (lista.isEmpty()) {
            lembreteText.visibility = View.GONE
            return
        }
        lembreteText.text = lista.joinToString("\n") { "● ${it.texto} · ${Lembretes.quando(it.data)}" }
        lembreteText.visibility = View.VISIBLE
    }
    private val dateFmt = SimpleDateFormat("EEE, d 'de' MMMM", Locale("pt", "BR"))

    /** Fonte de serifa do relógio (Playfair Display, baixada na compilação); senão a serifa do Android. */
    private fun fonteRelogio(): Typeface = try {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            Typeface.Builder(assets, "relogio.ttf").setFontVariationSettings("'wght' 600").build()
                ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
        } else {
            Typeface.createFromAsset(assets, "relogio.ttf")
        }
    } catch (_: Throwable) {
        Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private lateinit var greeting: TextView
    private lateinit var avatarText: TextView
    private lateinit var avatarBg: GradientDrawable
    private lateinit var saver: FrameLayout
    private lateinit var saverImg: ImageView
    private lateinit var saverClock: TextView
    private lateinit var saverTitle: TextView
    private var saverItems: List<Tmdb.Item> = emptyList()
    private var saverIndex = 0
    private val tabs = ArrayList<TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private var currentTab = -1
    private var selected: View? = null
    private var showingFallback = false
    private var loadToken = 0
    private var firstLoad = true

    private var cardH = 0
    private var wideW = 0
    private var narrowW = 0
    private var bigW = 0
    private var fixedLayout = false

    // Fileira infinita: a lista se repete em "voltas" para os dois lados
    private var loopCards: List<Card> = emptyList()
    private var looping = false
    private var loopNote: String? = null

    private val ptBr = Locale("pt", "BR")
    private val timeFmt = SimpleDateFormat("HH:mm", ptBr)

    private val tick = object : Runnable {
        override fun run() {
            // Virou noite/dia: refaz a tela com as cores certas
            if (Cores.atualizar()) {
                recreate()
                return
            }
            trocarHora(timeFmt.format(Date()))
            dateText.text = dateFmt.format(Date())
            mostrarLembretes()
            greeting.text = saudacao()
            if (saver.visibility == View.VISIBLE) saverClock.text = clock.text
            Weather.now { t -> weatherText.text = t ?: "" }
            handler.postDelayed(this, 10_000)
        }
    }

    private val idle = Runnable { showSaver() }

    private val saverNext = object : Runnable {
        override fun run() {
            nextSaverImage()
            handler.postDelayed(this, 12_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            AppRepo.clearCache()
            buildDock()
            if (showingFallback) loadCards(force = true)
        }
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Cores.manual = prefs.escuro
        Tmdb.carregarChave(this)
        Tmdb.aoMudarChave = { if (!isFinishing) loadCards(force = true) }
        Lembretes.aoMudar = { if (!isFinishing) mostrarLembretes() }
        val dm = resources.displayMetrics
        cardH = (dm.heightPixels * 0.34f).toInt()
        wideW = cardH * 16 / 9
        narrowW = (cardH * 0.78f).toInt()
        // Dois cartazes grandes + um pequeno cabendo na largura da tela
        val usable = dm.widthPixels - dp(112) - dp(26) * 2 - narrowW
        bigW = (usable / 2).coerceAtMost(wideW)
        Cores.atualizar()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Cores.fundoTopo))
        setContentView(buildShell())
        cardsRow.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onRowFocus(now) }
        buildMeter()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        refreshProfile()
        buildDock()
        selectTab(0)
    }

    override fun onResume() {
        handler.postDelayed({ if (!isFinishing) offerVolumeService() }, 1500)
        super.onResume()
        Brilho.restaurar(this)
        Atualizacao.conferir(this) { v -> if (!isFinishing) avisarAtualizacao(v) }
        startMeter()
        buildDock()
        handler.removeCallbacks(tick)
        handler.post(tick)
        resetIdle()
    }

    override fun onPause() {
        super.onPause()
        stopMeter()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(idle)
        handler.removeCallbacks(saverNext)
        saver.visibility = View.GONE
    }

    // Qualquer tecla do controle tira o descanso de tela e reinicia a contagem
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::saver.isInitialized && saver.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) hideSaver()
            return true
        }
        resetIdle()
        return super.dispatchKeyEvent(event)
    }


    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdle()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        goHome()
    }

    @Deprecated("Tela inicial não fecha com o botão Voltar")
    override fun onBackPressed() {
        goHome()
    }

    /** Botão da lua: liga/desliga o modo escuro e redesenha a tela. */
    /** Bolinha branca do topo; fica preta quando selecionada. */
    private fun botaoAjustes(): View {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Cores.janela)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_grade)
            setColorFilter(PRETO)
        }
        return FrameLayout(this).apply {
            background = bg
            elevation = dpf(3)
            isFocusable = true
            isClickable = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { abrirAjustes() }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) PRETO else Cores.janela)
                icon.setColorFilter(if (has) Cores.papel else PRETO)
                v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(120).start()
            }
        }
    }

    /** Grade 2×2 de ajustes rápidos, abaixo da bolinha, no canto direito. */
    private fun abrirAjustes() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Cores.janela, 26)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.12f)
            setGravity(Gravity.TOP or Gravity.END)
            attributes = attributes.apply {
                x = dp(50)
                y = dp(96)
            }
        }
        fun bloco(res: Int, nome: String, acao: () -> Unit): View {
            val bg = rounded(Cores.campo, 18)
            val ic = ImageView(this).apply {
                setImageResource(res)
                setColorFilter(PRETO)
            }
            val tx = TextView(this).apply {
                text = nome
                textSize = 15f
                setTextColor(PRETO)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(10), 0, 0)
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bg
                setPadding(dp(16), dp(16), dp(16), dp(14))
                isFocusable = true
                isClickable = true
                addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(tx)
                setOnClickListener {
                    d.dismiss()
                    acao()
                }
                setOnFocusChangeListener { v, has ->
                    bg.setColor(if (has) PRETO else Cores.campo)
                    ic.setColorFilter(if (has) Cores.papel else PRETO)
                    tx.setTextColor(if (has) Cores.papel else PRETO)
                    v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
                }
            }
        }
        val blocos = ArrayList<View>()
        // O Fire Stick não tem entradas HDMI
        if (!isFireTv()) blocos += bloco(R.drawable.ic_input, "Entradas") { openInputs() }
        blocos += bloco(R.drawable.ic_wifi, "Wi-Fi") { openWifi() }
        blocos += bloco(if (Cores.escuro) R.drawable.ic_claro else R.drawable.ic_lua, if (Cores.escuro) "Modo claro" else "Modo escuro") { trocarModo() }
        blocos += bloco(R.drawable.ic_menu, "Configurações") { open(Intent(Settings.ACTION_SETTINGS)) }
        blocos.chunked(2).forEachIndexed { r, par ->
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            par.forEachIndexed { c, v ->
                val lp = LinearLayout.LayoutParams(dp(156), dp(104))
                if (c == 0) lp.rightMargin = dp(10)
                linha.addView(v, lp)
            }
            val llp = LinearLayout.LayoutParams(WRAP, WRAP)
            if (r > 0) llp.topMargin = dp(10)
            box.addView(linha, llp)
        }
        d.show()
        blocos.first().requestFocus()
    }

    private fun trocarModo() {
        prefs.escuro = !prefs.escuro
        Cores.manual = prefs.escuro
        Cores.atualizar()
        recreate()
    }

    /** Relógio vivo: a hora velha sobe e some, a nova chega de baixo (animação 6). */
    private fun trocarHora(nova: String) {
        if (clock.text.toString() == nova) return
        if (clock.text.isNullOrEmpty() || clock.height == 0) {
            clock.text = nova
            return
        }
        val h = clock.height * 0.45f
        clock.animate().translationY(-h).alpha(0f).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                clock.text = nova
                clock.translationY = h
                clock.animate().translationY(0f).alpha(1f).setDuration(320)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
            }.start()
    }

    private fun goHome() {
        if (looping) {
            // Volta para o começo da fileira infinita
            showCards(loopCards, loopNote)
            val n = loopCards.size
            (cardsRow.getChildAt(n) as? ViewGroup)?.getChildAt(0)?.requestFocus()
            return
        }
        cardsScroll.smoothScrollTo(0, 0)
        (cardsRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }

    // ---------- montagem ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(radius)
            setColor(color)
        }

    private fun ring(radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(radius)
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.WHITE)
        }

    private fun buildShell(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Cores.fundoTopo, Cores.fundoBase),
            )
        }
        bgImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        root.addView(bgImage, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    Cores.veu,
                )
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(26))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // --- topo: título + abas | relógio + botões
        // Nada aqui corta a bolinha do perfil quando ela cresce
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // Perfil (bolinha com a inicial) + saudação
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), 0, dp(6))
        }
        avatarBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val avatarRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), INK)
        }
        avatarText = TextView(this).apply {
            background = avatarBg
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            setOnClickListener { showProfiles() }
            // Sempre redonda: corta a foto em círculo e tira o destaque quadrado da TV
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { v, has ->
                v.foreground = if (has) avatarRing else null
                v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(120).start()
            }
        }
        profileRow.addView(avatarText, LinearLayout.LayoutParams(dp(36), dp(36)))
        greeting = TextView(this).apply {
            textSize = 18f
            setTextColor(GRAY)
            setPadding(dp(12), 0, 0, 0)
        }
        profileRow.addView(greeting)
        left.addView(profileRow)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        listOf("Explorar", "Filmes", "Séries", "Continuar").forEachIndexed { i, s ->
            val t = makeTab(s, i)
            tabs += t
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(6)
            tabRow.addView(t, lp)
        }
        // Pílula que escorrega por trás das abas (animação 4)
        tabPill = View(this).apply { background = tabPillBg }
        val tabFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(tabPill, FrameLayout.LayoutParams(0, 0))
            addView(tabRow)
        }
        tabRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> styleTabs(animar = false) }
        // As abas cabem numa faixa que rola para o lado se faltar espaço
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(tabFrame)
        }
        left.addView(tabScroll)
        top.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Relógio grande com o clima de Pirapora embaixo
        // Toque no relógio/clima para ver a previsão da semana
        val clockBg = rounded(Color.TRANSPARENT, 18)
        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            background = clockBg
            setPadding(dp(14), dp(4), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            setOnClickListener { showForecast() }
            setOnFocusChangeListener { _, has -> clockBg.setColor(if (has) Cores.sutil else Color.TRANSPARENT) }
        }
        // Relógio estilo tela de bloqueio: número de serifa, preto como antes
        clock = TextView(this).apply {
            textSize = 60f
            setTextColor(INK)
            typeface = fonteRelogio()
            includeFontPadding = false
            gravity = Gravity.END
            letterSpacing = -0.01f
        }
        // Data embaixo, em verde-água: "sex., 6 de novembro"
        // Relógio 2: data branca dentro de uma pílula preta (no escuro, a pílula fica clara)
        dateText = TextView(this).apply {
            textSize = 15f
            setTextColor(Cores.papel)
            background = rounded(INK, 14)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(3), dp(12), dp(4))
        }
        weatherText = TextView(this).apply {
            textSize = 16f
            setTextColor(GRAY)
            gravity = Gravity.END
            setPadding(0, dp(4), dp(2), 0)
        }
        clockCol.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        clockCol.addView(dateText, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END
            topMargin = dp(6)
            bottomMargin = dp(4)
        })
        clockCol.addView(weatherText, LinearLayout.LayoutParams(WRAP, WRAP))
        // Lembretes do perfil, pequenos embaixo do relógio
        lembreteText = TextView(this).apply {
            textSize = 15f
            setTextColor(INK)
            gravity = Gravity.END
            maxLines = 3
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = rounded(0x26FF9500, 12)
            visibility = View.GONE
        }
        clockCol.addView(lembreteText, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        val clp = LinearLayout.LayoutParams(WRAP, WRAP)
        clp.rightMargin = dp(8)
        top.addView(clockCol, clp)
        // Uma bolinha só: abre a grade com Entradas, Wi-Fi, Modo escuro e Configurações
        val group = FrameLayout(this).apply {
            // Folga em volta para a bolinha crescer sem ser cortada
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = false
            clipToPadding = false
        }
        group.addView(botaoAjustes(), FrameLayout.LayoutParams(dp(54), dp(54)))
        top.addView(group, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // --- carrossel
        val cardsArea = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        cardsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = true
            clipChildren = false
            clipToPadding = false
        }
        cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(18), resources.displayMetrics.widthPixels, dp(18))
        }
        cardsScroll.addView(cardsRow, FrameLayout.LayoutParams(WRAP, WRAP))
        // Cartazes no meio do espaço entre as abas e a barra de apps (nomes sem cortar)
        cardsArea.addView(cardsScroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cardsArea.addView(
            statusText,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM),
        )
        content.addView(cardsArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // --- barra de apps
        // Faixa branca lisa de ponta a ponta, sem cantos, com uma linha fina em cima (barra 1)
        val dock = FrameLayout(this).apply {
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.ColorDrawable(Cores.sutil),
                    android.graphics.drawable.ColorDrawable(Cores.barra),
                ),
            ).apply { setLayerInset(1, 0, dp(1), 0, 0) }
            elevation = dpf(4)
            setPadding(dp(48), dp(14), dp(48), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        val dockScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        dockScroll.addView(dockRow, FrameLayout.LayoutParams(WRAP, WRAP))
        dock.addView(dockScroll, FrameLayout.LayoutParams(MATCH, WRAP))
        // Margens negativas: a faixa encosta nas bordas da tela (esquerda, direita e embaixo)
        content.addView(
            dock,
            LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = -dp(56)
                rightMargin = -dp(56)
                bottomMargin = -dp(26)
                topMargin = dp(6)
            },
        )

        saver = buildSaver()
        root.addView(saver, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun makeTab(label: String, index: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 22f
            setPadding(dp(18), dp(8), dp(18), dp(8))
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, has ->
                if (has) selectTab(index)
                styleTabs()
            }
            setOnClickListener { selectTab(index) }
        }

    private lateinit var tabPill: View
    private val tabPillBg = GradientDrawable().apply { cornerRadius = 1000f }
    private var pillCor = Color.TRANSPARENT
    private var pillAnim: android.animation.ValueAnimator? = null

    /** A pílula preta (selecionada) ou cinza clarinha (aba atual) escorrega até a aba certa. */
    private fun styleTabs(animar: Boolean = true) {
        var alvo: TextView? = null
        var corAlvo = Cores.sutil
        tabs.forEachIndexed { i, t ->
            when {
                t.isFocused -> {
                    t.setTextColor(Cores.papel)
                    alvo = t
                    corAlvo = INK
                }
                i == currentTab -> t.setTextColor(INK)
                else -> t.setTextColor(GRAY)
            }
            t.typeface = if (i == currentTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val t = alvo ?: tabs.getOrNull(currentTab) ?: return
        if (!::tabPill.isInitialized || t.width == 0) return
        val lp = tabPill.layoutParams as FrameLayout.LayoutParams
        val x0 = tabPill.translationX
        val w0 = lp.width.toFloat()
        val c0 = pillCor
        val x1 = t.left.toFloat()
        val w1 = t.width.toFloat()
        lp.height = t.height
        lp.topMargin = t.top
        pillAnim?.cancel()
        if (!animar || w0 == 0f) {
            tabPill.translationX = x1
            lp.width = t.width
            tabPill.layoutParams = lp
            pillCor = corAlvo
            tabPillBg.setColor(corAlvo)
            return
        }
        val cores = android.animation.ArgbEvaluator()
        pillAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                tabPill.translationX = x0 + (x1 - x0) * f
                lp.width = (w0 + (w1 - w0) * f).toInt()
                tabPill.layoutParams = lp
                pillCor = cores.evaluate(f, c0, corAlvo) as Int
                tabPillBg.setColor(pillCor)
            }
            start()
        }
    }

    /** Botão dentro da barra branca: sem fundo, fica preto quando selecionado. */
    private fun groupButton(res: Int, first: Boolean = false, onClick: () -> Unit): View {
        val bg = rounded(Color.TRANSPARENT, 24)
        val icon = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(PRETO)
        }
        val b = FrameLayout(this).apply {
            background = bg
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) PRETO else Color.TRANSPARENT)
                icon.setColorFilter(if (has) Cores.papel else PRETO)
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            }
        }
        b.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        val lp = LinearLayout.LayoutParams(dp(46), dp(46))
        if (!first) lp.leftMargin = dp(4)
        b.layoutParams = lp
        return b
    }

    private fun iconButton(res: Int, onClick: () -> Unit): View {
        val bg = rounded(Cores.botao, 24)
        val icon = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(INK)
        }
        val b = FrameLayout(this).apply {
            background = bg
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) INK else Cores.botao)
                icon.setColorFilter(if (has) Cores.papel else INK)
                v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(120).start()
            }
        }
        b.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        val lp = LinearLayout.LayoutParams(dp(48), dp(48))
        lp.leftMargin = dp(12)
        b.layoutParams = lp
        return b
    }

    // ---------- carrossel ----------

    private fun selectTab(i: Int) {
        if (i == currentTab) return
        currentTab = i
        styleTabs()
        loadCards()
    }

    private fun loadCards(force: Boolean = false) {
        val token = ++loadToken
        if (currentTab == TAB_CONTINUAR) {
            val recentes = recentCards()
            showCards(recentes, if (recentes.isEmpty()) "Os apps que você abrir vão aparecer aqui" else null)
            return
        }
        if (!Tmdb.enabled) {
            showCards(fallbackCards(), null)
            return
        }
        if (!force) status("Carregando…")
        Tmdb.list(currentTab, infantil = prefs.crianca(prefs.perfil)) { items ->
            if (token != loadToken) return@list
            if (items.isEmpty()) {
                showCards(fallbackCards(), "Sem conexão com o TMDB agora · mostrando seus apps")
            } else {
                showCards(items.map { Card(it.title, it, null) }, null)
            }
        }
    }

    /** Apps usados por último neste perfil. */
    private fun recentCards(): List<Card> {
        val byPkg = AppRepo.load(this).associateBy { it.pkg }
        val hidden = prefs.hidden
        return prefs.recentes.mapNotNull { byPkg[it] }
            .filter { it.pkg !in hidden }
            .map { Card(it.label, null, it) }
    }

    private fun fallbackCards(): List<Card> {
        val apps = AppRepo.load(this)
        prefs.ensureDefaults(apps)
        val byPkg = apps.associateBy { it.pkg }
        val hidden = prefs.hidden
        return prefs.favs.mapNotNull { byPkg[it] }
            .filter { it.pkg !in hidden }
            .map { Card(it.label, null, it) }
    }

    private fun status(msg: String?) {
        statusText.text = msg ?: ""
        statusText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun showCards(cards: List<Card>, note: String?) {
        showingFallback = cards.any { it.app != null }
        fixedLayout = !showingFallback
        // Explorar, Filmes e Séries: fileira infinita (depois do último vem o primeiro de novo)
        looping = currentTab != TAB_CONTINUAR && cards.size >= 3
        loopCards = cards
        loopNote = note
        // Sem espaço em branco no fim da fileira de filmes
        cardsRow.setPadding(0, dp(26), if (fixedLayout || looping) dp(8) else resources.displayMetrics.widthPixels, dp(8))
        status(note)
        val focusWasHere = cardsRow.hasFocus()
        cardsRow.removeAllViews()
        selected = null
        cardsScroll.scrollTo(0, 0)
        // Três voltas: começa na do meio, para poder ir para os dois lados
        repeat(if (looping) 3 else 1) { addRound(atStart = false) }
        val first = cardsRow.getChildAt(if (looping) cards.size else 0) ?: return
        select(first, animate = false)
        if (looping) afterLayout { cardsScroll.scrollTo((first.left - if (fixedLayout) 0 else dp(4)).coerceAtLeast(0), 0) }
        if (firstLoad || focusWasHere || currentFocus == null) {
            firstLoad = false
            first.post { (first as ViewGroup).getChildAt(0).requestFocus() }
        }
    }

    private fun cardWidth(i: Int): Int = if (fixedLayout && i < 2) bigW else narrowW

    private fun roundWidth(): Int = loopCards.indices.sumOf { cardWidth(it) + dp(26) }

    /** Coloca uma volta inteira da lista no fim ou no começo da fileira. */
    private fun addRound(atStart: Boolean) {
        loopCards.forEachIndexed { i, c ->
            val lp = LinearLayout.LayoutParams(cardWidth(i), WRAP)
            lp.rightMargin = dp(26)
            if (atStart) cardsRow.addView(makeCard(c), i, lp) else cardsRow.addView(makeCard(c), lp)
        }
    }

    private fun removeRound(atStart: Boolean) {
        val n = loopCards.size
        if (atStart) cardsRow.removeViews(0, n) else cardsRow.removeViews(cardsRow.childCount - n, n)
    }

    private fun afterLayout(block: () -> Unit) {
        cardsScroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cardsScroll.viewTreeObserver.removeOnPreDrawListener(this)
                block()
                return true
            }
        })
    }

    /** Desloca a rolagem quando a fileira cresce/encolhe à esquerda, sem pulo na tela. */
    private fun shiftScroll(before: Int, dx: Int) {
        afterLayout {
            cardsScroll.smoothScrollBy(0, 0) // para a animação antiga
            cardsScroll.scrollTo(before + dx, 0)
            val item = selected ?: return@afterLayout
            val r = android.graphics.Rect(item.left, item.top, item.right, item.bottom)
            cardsScroll.requestChildRectangleOnScreen(cardsRow, r, false)
        }
    }

    /** Perto da ponta da fileira? Coloca mais uma volta para nunca acabar. */
    private fun onRowFocus(now: View?) {
        if (!looping) return
        val item = now?.parent as? View ?: return
        if (item.parent !== cardsRow) return
        val n = loopCards.size
        val edge = minOf(n, 8)
        val idx = cardsRow.indexOfChild(item)
        if (idx >= cardsRow.childCount - edge) {
            cardsRow.post {
                if (!looping) return@post
                addRound(atStart = false)
                if (cardsRow.childCount > n * MAX_VOLTAS) {
                    val before = cardsScroll.scrollX
                    removeRound(atStart = true)
                    shiftScroll(before, -roundWidth())
                }
            }
        } else if (idx < edge) {
            cardsRow.post {
                if (!looping) return@post
                val before = cardsScroll.scrollX
                addRound(atStart = true)
                shiftScroll(before, roundWidth())
                if (cardsRow.childCount > n * MAX_VOLTAS) removeRound(atStart = false)
            }
        }
    }

    private fun makeCard(c: Card): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val frame = FrameLayout(this).apply {
            background = rounded(Cores.cartao, 18)
            clipToOutline = true
            isFocusable = true
            isClickable = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

        val title = TextView(this).apply {
            text = c.title
            textSize = 17f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(12), dp(2), 0)
        }
        val meta = TextView(this).apply {
            textSize = 13f
            setTextColor(GRAY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
            visibility = View.INVISIBLE
        }
        item.addView(frame, LinearLayout.LayoutParams(MATCH, cardH))
        item.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        item.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))

        val tmdb = c.item
        val app = c.app
        if (tmdb != null) {
            Tmdb.image(tmdb.backdrop, "w780") { b ->
                if (b != null) {
                    img.setImageBitmap(b)
                    if (selected === item) setBackdrop(b)
                }
            }
        } else if (app != null) {
            val banner = AppRepo.banner(this, app)
            if (banner != null) {
                frame.background = rounded(0xFF1C1C1E.toInt(), 18)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.setImageDrawable(banner)
            } else {
                img.scaleType = ImageView.ScaleType.CENTER_INSIDE
                img.setImageDrawable(AppRepo.icon(this, app))
            }
        }

        val focusRing = ring(18)
        frame.setOnFocusChangeListener { v, has ->
            v.foreground = if (has) focusRing else null
            v.animate()
                .scaleX(if (has) 1.03f else 1f)
                .scaleY(if (has) 1.03f else 1f)
                .translationZ(if (has) dpf(20) else 0f)
                .setDuration(180)
                .start()
            if (has) {
                select(item, animate = true)
                onCardSelected(c, img, meta)
            }
        }
        frame.setOnClickListener { openCard(c) }
        if (app != null) {
            frame.setOnLongClickListener {
                AppMenu.show(this, prefs, app) { refreshApps() }
                true
            }
        }
        return item
    }

    /** O card selecionado fica largo (como um destaque); os outros ficam estreitos. */
    private fun select(item: View, animate: Boolean) {
        if (selected === item) return
        val old = selected
        selected = item
        if (fixedLayout) {
            // Destaques fixos: dois grandes e um pequeno, só troca a legenda
            if (old != null) ((old as ViewGroup).getChildAt(2)).visibility = View.INVISIBLE
            ((item as ViewGroup).getChildAt(2)).visibility = View.VISIBLE
            return
        }
        if (old != null) {
            resize(old, narrowW, animate, null)
            ((old as ViewGroup).getChildAt(2)).visibility = View.INVISIBLE
        }
        resize(item, wideW, animate) {
            cardsScroll.smoothScrollTo((item.left - dp(4)).coerceAtLeast(0), 0)
        }
        ((item as ViewGroup).getChildAt(2)).visibility = View.VISIBLE
    }

    private fun resize(v: View, target: Int, animate: Boolean, done: (() -> Unit)?) {
        val lp = v.layoutParams ?: return
        if (!animate) {
            lp.width = target
            v.layoutParams = lp
            v.post { done?.invoke() }
            return
        }
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 220
            addUpdateListener {
                lp.width = it.animatedValue as Int
                v.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    done?.invoke()
                }
            })
            start()
        }
    }

    private fun onCardSelected(c: Card, img: ImageView, meta: TextView) {
        setBackdrop((img.drawable as? BitmapDrawable)?.bitmap)
        val tmdb = c.item
        if (tmdb != null) {
            if (meta.text.isNullOrEmpty()) meta.text = if (tmdb.type == "tv") "Série" else "Filme"
            Tmdb.details(tmdb) { d -> if (d.meta.isNotBlank()) meta.text = d.meta }
        } else {
            meta.text = "Abrir app"
        }
    }

    private fun setBackdrop(b: Bitmap?) {
        // Fundo sempre branco e limpo: não mostra mais o filme atrás
        if (!FUNDO_COM_FILME || b == null) {
            bgImage.animate().alpha(0f).setDuration(300).start()
            return
        }
        bgImage.setImageBitmap(Tmdb.blur(b))
        bgImage.alpha = 0.35f
        bgImage.animate().alpha(0.95f).setDuration(450).start()
    }

    private fun openCard(c: Card) {
        val app = c.app
        if (app != null) {
            open(app.intent)
            return
        }
        val tmdb = c.item ?: return
        Tmdb.details(tmdb) { d ->
            val pkg = d.providerId?.let { Tmdb.packageFor(it) }
            val launch = pkg?.let {
                packageManager.getLeanbackLaunchIntentForPackage(it)
                    ?: packageManager.getLaunchIntentForPackage(it)
            }
            when {
                launch != null -> {
                    toast("Procure por \"${tmdb.title}\" no ${d.providerName}")
                    open(launch)
                }
                d.providerName != null -> toast("Disponível no ${d.providerName} (app não instalado)")
                else -> toast("Não encontrei onde assistir no Brasil")
            }
        }
    }

    // ---------- barra de apps ----------

    private fun refreshApps() {
        buildDock()
        if (showingFallback) loadCards(force = true)
    }

    private fun buildDock() {
        val focusedPkg = (currentFocus?.tag as? String)
        dockRow.removeAllViews()
        val apps = AppRepo.load(this)
        prefs.ensureDefaults(apps)
        val byPkg = apps.associateBy { it.pkg }
        val hidden = prefs.hidden
        val favs = prefs.favs.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }
        favs.forEach { dockRow.addView(dockTile(it)) }
        dockRow.addView(moreTile())
        if (focusedPkg != null) dockRow.findViewWithTag<View>(focusedPkg)?.requestFocus()
    }

    /** Tamanho dos blocos do dock: 7 apps + "Todos" cabem sem rolar. */
    private fun dockSize(): Pair<Int, Int> {
        val gap = dp(DOCK_GAP)
        val avail = resources.displayMetrics.widthPixels - dp(96) - dp(16)
        val w = ((avail - gap * DOCK_APPS) / (DOCK_APPS + 9f / 16f)).toInt()
        val h = w * 9 / 16
        return w to h
    }

    private fun dockLp(): LinearLayout.LayoutParams {
        val (w, h) = dockSize()
        val lp = LinearLayout.LayoutParams(w, h)
        lp.rightMargin = dp(DOCK_GAP)
        return lp
    }

    private fun dockTile(e: AppEntry): View {
        val card = FrameLayout(this).apply {
            background = rounded(0xFF2C2C2E.toInt(), 14)
            clipToOutline = true
            isFocusable = true
            isClickable = true
            tag = e.pkg
        }
        val banner = AppRepo.banner(this, e)
        if (banner != null) {
            card.addView(
                ImageView(this).apply {
                    setImageDrawable(banner)
                    scaleType = ImageView.ScaleType.FIT_XY
                },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            col.addView(
                ImageView(this).apply { setImageDrawable(AppRepo.icon(this@MainActivity, e)) },
                LinearLayout.LayoutParams(dp(30), dp(30)),
            )
            col.addView(
                TextView(this).apply {
                    text = e.label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), dp(3), dp(4), 0)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        styleDockFocus(card)
        card.setOnClickListener { open(e.intent) }
        card.setOnLongClickListener {
            AppMenu.show(this, prefs, e) { refreshApps() }
            true
        }
        card.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) {
                AppMenu.show(this, prefs, e) { refreshApps() }
                true
            } else {
                false
            }
        }
        card.layoutParams = dockLp()
        return card
    }

    private fun moreTile(): View {
        val card = FrameLayout(this).apply {
            background = rounded(Cores.sutil, 14)
            clipToOutline = true
            isFocusable = true
            isClickable = true
            tag = "#more"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppsActivity::class.java)) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_apps)
                setColorFilter(INK)
            },
            LinearLayout.LayoutParams(dp(20), dp(20)),
        )
        col.addView(
            TextView(this).apply {
                text = "Todos"
                textSize = 10f
                setTextColor(INK)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            },
        )
        card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        styleDockFocus(card)
        val h = dockSize().second
        card.layoutParams = LinearLayout.LayoutParams(h, h)
        return card
    }

    private fun styleDockFocus(card: View) {
        val r = ring(14)
        card.setOnFocusChangeListener { v, has ->
            v.foreground = if (has) r else null
            v.animate()
                .scaleX(if (has) 1.15f else 1f)
                .scaleY(if (has) 1.15f else 1f)
                .translationY(if (has) -dpf(6) else 0f)
                .translationZ(if (has) dpf(16) else 0f)
                .setDuration(150)
                .start()
        }
    }

    // ---------- saudação, previsão e descanso de tela ----------

    private fun saudacao(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val p = when {
            h < 5 -> "Boa noite"
            h < 12 -> "Bom dia"
            h < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
        return "$p, ${prefs.nome(prefs.perfil)}"
    }

    private fun refreshProfile() {
        val i = prefs.perfil
        val nome = prefs.nome(i)
        val av = prefs.avatar(i)
        if (av in AVATARES.indices) {
            avatarText.text = ""
            avatarText.background = getDrawable(AVATARES[av])
        } else {
            avatarText.text = nome.trim().take(1).uppercase(ptBr)
            avatarBg.setColor(Prefs.CORES_PERFIL[i])
            avatarText.background = avatarBg
        }
        greeting.text = saudacao()
    }

    // ---------- perfis: janela branca e limpa ----------

    // ---------- versão nova ----------

    private fun avisarAtualizacao(v: Atualizacao.Versao) {
        val (d, box) = whiteDialog()
        box.addView(dialogTitle("Versão nova!"))
        val info = TextView(this).apply {
            text = "${v.nome} está disponível.\nVocê tem a versão 1.${BuildConfig.VERSION_CODE}."
            textSize = 16f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        }
        box.addView(info)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        var baixando = false
        val atualizar = pill("Atualizar") {
            if (baixando) return@pill
            if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                // Primeira vez: a TV precisa deixar o Início TV instalar atualizações
                toast("Permita o Início TV instalar apps e aperte Atualizar de novo")
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {
                    toast("Libere \"Fontes desconhecidas\" para o Início TV nas Configurações")
                }
                return@pill
            }
            baixando = true
            info.text = "Baixando… 0%"
            Atualizacao.baixar(this, v, progresso = { info.text = "Baixando… $it%" }) { f ->
                baixando = false
                if (f == null) {
                    info.text = "Não consegui baixar. Confira a internet e tente de novo."
                } else {
                    d.dismiss()
                    if (!Atualizacao.instalar(this)) toast("Não consegui abrir o instalador")
                }
            }
        }
        val depois = pill("Depois") {
            Atualizacao.adiar(this, v)
            d.dismiss()
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = dp(12)
        row.addView(atualizar, lp)
        row.addView(depois, LinearLayout.LayoutParams(WRAP, WRAP))
        box.addView(row)
        d.show()
        atualizar.requestFocus()
    }

    private fun whiteDialog(): Pair<android.app.Dialog, LinearLayout> {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Cores.janela, 28)
            setPadding(dp(44), dp(30), dp(44), dp(28))
            elevation = dpf(24)
        }
        d.setContentView(box)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.35f)
        return d to box
    }

    private fun dialogTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(PRETO)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

    /** Botão em pílula: cinza-claro, fica preto quando selecionado. */
    private fun pill(text: String, onClick: () -> Unit): TextView {
        val bg = rounded(Cores.campo, 22)
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(PRETO)
            gravity = Gravity.CENTER
            background = bg
            setPadding(dp(26), dp(10), dp(26), dp(10))
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) PRETO else Cores.campo)
                setTextColor(if (has) Cores.papel else PRETO)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
            }
        }
    }

    /** Bolinha colorida com a inicial do nome. */
    private fun avatarCircle(i: Int, sizeDp: Int, textSp: Float, avatar: Int = prefs.avatar(i)): TextView =
        TextView(this).apply {
            text = if (avatar in AVATARES.indices) "" else prefs.nome(i).trim().take(1).uppercase(ptBr)
            textSize = textSp
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = if (avatar in AVATARES.indices) {
                getDrawable(AVATARES[avatar])
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Prefs.CORES_PERFIL[i])
                }
            }
            layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
        }

    /** Quem está assistindo? Três carinhas grandes e o botão Editar perfil. */
    private fun showProfiles() {
        val atual = prefs.perfil
        val (d, box) = whiteDialog()
        box.setPadding(dp(56), dp(34), dp(56), dp(30))
        box.clipChildren = false
        box.clipToPadding = false
        box.addView(dialogTitle("Quem está assistindo?"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(26))
            clipChildren = false
            clipToPadding = false
        }
        var focusMe: View? = null
        for (i in 0 until Prefs.PERFIS) {
            // Anel com folga em volta da carinha: cinza no perfil atual, preto e grosso no selecionado
            val ringBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                if (i == atual) setStroke(dp(3), Cores.sutil or 0x33000000)
            }
            val holder = FrameLayout(this).apply {
                background = ringBg
                setPadding(dp(8), dp(8), dp(8), dp(8))
                clipChildren = false
            }
            holder.addView(avatarCircle(i, 100, 38f))
            val name = TextView(this).apply {
                text = prefs.nome(i)
                textSize = 17f
                setTextColor(if (i == atual) PRETO else GRAY)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = if (i == atual) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
                setPadding(0, dp(14), 0, 0)
            }
            // Etiqueta embaixo do nome: "Assistindo" no atual, "Criança" no perfil infantil
            val etiqueta = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                when {
                    prefs.crianca(i) -> {
                        text = "Criança"
                        setTextColor(Color.WHITE)
                        background = rounded(0xFFFF9500.toInt(), 12)
                    }
                    i == atual -> {
                        text = "Assistindo"
                        setTextColor(GRAY)
                        background = rounded(Cores.campo, 12)
                    }
                    else -> visibility = View.INVISIBLE
                }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    val mudouTipo = prefs.crianca(i) != prefs.crianca(prefs.perfil)
                    prefs.perfil = i
                    refreshProfile()
                    mostrarLembretes()
                    refreshApps()
                    // Trocou entre adulto e criança: troca os cartazes também
                    if (mudouTipo) loadCards(force = true)
                    d.dismiss()
                }
                setOnFocusChangeListener { _, has ->
                    ringBg.setStroke(
                        if (has) dp(5) else dp(3),
                        if (has) PRETO else if (i == atual) (Cores.sutil or 0x33000000) else Color.TRANSPARENT,
                    )
                    name.setTextColor(if (has || i == atual) PRETO else GRAY)
                    holder.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                        .translationZ(if (has) dpf(6) else 0f).setDuration(160).start()
                }
            }
            col.addView(holder, LinearLayout.LayoutParams(dp(116), dp(116)))
            col.addView(name, LinearLayout.LayoutParams(dp(150), WRAP))
            val elp = LinearLayout.LayoutParams(WRAP, WRAP)
            elp.topMargin = dp(6)
            col.addView(etiqueta, elp)
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.leftMargin = dp(8)
            lp.rightMargin = dp(8)
            row.addView(col, lp)
            if (i == atual) focusMe = col
        }
        box.addView(row)
        box.addView(pill("Editar perfil") {
            d.dismiss()
            renameProfile(atual)
        })
        d.show()
        focusMe?.post { focusMe?.requestFocus() }
    }

    /** Editar perfil: nome e avatar, numa janela branca. */
    private fun renameProfile(i: Int) {
        var escolhido = prefs.avatar(i)
        val (d, box) = whiteDialog()
        box.setPadding(dp(40), dp(22), dp(40), dp(20))
        box.addView(dialogTitle("Editar perfil"))
        val preview = FrameLayout(this).apply { setPadding(0, dp(12), 0, dp(10)) }
        fun showPreview() {
            preview.removeAllViews()
            preview.addView(avatarCircle(i, 52, 22f, escolhido))
        }
        showPreview()
        box.addView(preview, LinearLayout.LayoutParams(WRAP, WRAP))
        val input = android.widget.EditText(this).apply {
            setText(prefs.nome(i))
            setSelectAllOnFocus(true)
            setSingleLine()
            textSize = 17f
            setTextColor(PRETO)
            setHintTextColor(0xFF8E8E93.toInt())
            hint = "Nome"
            gravity = Gravity.CENTER
            background = rounded(Cores.campo, 14)
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        box.addView(input, LinearLayout.LayoutParams(dp(300), WRAP))

        // Grade de avatares: Feminino, Masculino, Infantil
        val rings = ArrayList<GradientDrawable>()
        fun ringColor(idx: Int, focused: Boolean): Int = when {
            focused -> PRETO
            idx == escolhido -> 0xFFC7C7CC.toInt()
            else -> Color.TRANSPARENT
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        listOf("Feminino", "Masculino", "Infantil").forEachIndexed { r, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(0xFF8E8E93.toInt())
                },
                LinearLayout.LayoutParams(dp(88), WRAP),
            )
            for (c in 0 until 5) {
                val idx = r * 5 + c
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), ringColor(idx, false))
                }
                rings += ring
                val cell = FrameLayout(this).apply {
                    background = ring
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        escolhido = idx
                        rings.forEachIndexed { k, g -> g.setStroke(dp(3), ringColor(k, k == idx && hasFocus())) }
                        showPreview()
                    }
                    setOnFocusChangeListener { v, has ->
                        ring.setStroke(dp(3), ringColor(idx, has))
                        v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(120).start()
                    }
                }
                cell.addView(
                    ImageView(this).apply { setImageResource(AVATARES[idx]) },
                    FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER),
                )
                val lp = LinearLayout.LayoutParams(dp(48), dp(48))
                lp.leftMargin = dp(6)
                lp.topMargin = dp(3)
                lp.bottomMargin = dp(3)
                row.addView(cell, lp)
            }
            grid.addView(row)
        }
        box.addView(grid)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        buttons.addView(pill("Cancelar") { d.dismiss() })
        val save = pill("Salvar") {
            prefs.setNome(i, input.text.toString())
            prefs.setAvatar(i, escolhido)
            refreshProfile()
            d.dismiss()
        }
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.leftMargin = dp(14)
        buttons.addView(save, slp)
        box.addView(buttons)
        d.show()
        input.post { input.requestFocus() }
    }

    /** Previsão da semana numa janela branca, com ícone do tempo em cada dia. */
    private fun showForecast() {
        Weather.week { dias ->
            if (dias.isEmpty()) {
                toast("Não deu para pegar a previsão agora")
                return@week
            }
            val (d, box) = whiteDialog()
            box.addView(dialogTitle("Previsão · Pirapora (MG)"))
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, dp(18))
            }
            dias.forEachIndexed { i, dia ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(5), dp(6), dp(5))
                }
                row.addView(
                    TextView(this).apply {
                        text = dia.label
                        textSize = 16f
                        setTextColor(PRETO)
                        typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    },
                    LinearLayout.LayoutParams(dp(84), WRAP),
                )
                row.addView(
                    ImageView(this).apply { setImageResource(Weather.icon(dia.code)) },
                    LinearLayout.LayoutParams(dp(32), dp(32)),
                )
                row.addView(
                    TextView(this).apply {
                        text = dia.desc
                        textSize = 15f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(dp(14), 0, dp(14), 0)
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(dp(190), WRAP),
                )
                row.addView(
                    TextView(this).apply {
                        text = "${dia.max}°"
                        textSize = 16f
                        setTextColor(PRETO)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.END
                    },
                    LinearLayout.LayoutParams(dp(44), WRAP),
                )
                row.addView(
                    TextView(this).apply {
                        text = "${dia.min}°"
                        textSize = 16f
                        setTextColor(0xFF8E8E93.toInt())
                        gravity = Gravity.END
                    },
                    LinearLayout.LayoutParams(dp(44), WRAP),
                )
                list.addView(row)
            }
            box.addView(list)
            val close = pill("Fechar") { d.dismiss() }
            box.addView(close)
            d.show()
            close.post { close.requestFocus() }
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, IDLE_MS)
    }

    private fun buildSaver(): FrameLayout {
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideSaver() }
        }
        saverImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(saverImg, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x22000000, 0xCC000000.toInt()),
                )
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), dp(44))
        }
        saverClock = TextView(this).apply {
            textSize = 76f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        saverTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(4), dp(6), 0, 0)
        }
        col.addView(saverClock)
        col.addView(saverTitle)
        f.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return f
    }

    private fun showSaver() {
        if (saver.visibility == View.VISIBLE) return
        saverClock.text = timeFmt.format(Date())
        saverTitle.text = weatherText.text
        saver.alpha = 0f
        saver.visibility = View.VISIBLE
        saver.animate().alpha(1f).setDuration(600).start()
        if (Tmdb.enabled) {
            Tmdb.list(0, infantil = prefs.crianca(prefs.perfil)) { items ->
                saverItems = items
                handler.removeCallbacks(saverNext)
                handler.post(saverNext)
            }
        }
    }

    private fun nextSaverImage() {
        saverClock.text = timeFmt.format(Date())
        if (saverItems.isEmpty()) return
        val item = saverItems[saverIndex % saverItems.size]
        saverIndex++
        Tmdb.image(item.backdrop, "w1280") { b ->
            if (b != null && saver.visibility == View.VISIBLE) {
                saverImg.alpha = 0f
                saverImg.setImageBitmap(b)
                saverImg.animate().alpha(1f).setDuration(900).start()
                saverTitle.text = item.title
            }
        }
    }

    private fun hideSaver() {
        handler.removeCallbacks(saverNext)
        saver.animate().alpha(0f).setDuration(250).withEndAction {
            saver.visibility = View.GONE
            saverImg.setImageDrawable(null)
        }.start()
        resetIdle()
    }

    // ---------- ações ----------

    /** Lista as entradas da TV (HDMI, AV, TV) e troca para a escolhida. */
    private fun openInputs() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val inputs: List<TvInputInfo> = try {
            tim?.tvInputList.orEmpty().filter { !it.isHidden(this) }
        } catch (_: Throwable) {
            emptyList()
        }
        if (inputs.isEmpty()) {
            openLiveTv()
            return
        }
        val labels = inputs.map { info ->
            val custom = try { info.loadCustomLabel(this)?.toString() } catch (_: Throwable) { null }
            if (!custom.isNullOrBlank()) custom else info.loadLabel(this).toString()
        }
        val (d, box) = whiteDialog()
        box.addView(dialogTitle("Entradas"))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        var first: View? = null
        inputs.forEachIndexed { k, info ->
            val p = pill(labels[k]) {
                d.dismiss()
                switchInput(info)
            }
            val lp = LinearLayout.LayoutParams(dp(260), WRAP)
            lp.topMargin = dp(8)
            list.addView(p, lp)
            if (first == null) first = p
        }
        box.addView(list)
        d.show()
        first?.post { first?.requestFocus() }
    }

    private fun switchInput(info: TvInputInfo) {
        if (!info.isPassthroughInput) {
            openLiveTv()
            return
        }
        try {
            val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            openLiveTv()
        }
    }

    /** Abre o app de TV da própria televisão (canais e entradas). */
    private fun openLiveTv() {
        val pkgs = listOf("com.tcl.tv", "com.google.android.tv", "com.android.tv", "com.tcl.tvinput")
        for (p in pkgs) {
            val i = packageManager.getLeanbackLaunchIntentForPackage(p)
                ?: packageManager.getLaunchIntentForPackage(p)
                ?: continue
            open(i)
            return
        }
        toast("Use o botão de Entrada (Input) do controle")
    }

    /** true quando o aparelho é um Fire TV / Fire Stick da Amazon. */
    private fun isFireTv(): Boolean =
        android.os.Build.MANUFACTURER.equals("Amazon", true) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    // ---------- medidor de FPS no canto de baixo ----------

    private lateinit var meterDot: GradientDrawable
    private var meterOn = false
    private var frames = 0
    private var lastRx = 0L
    private var lastT = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            if (meterOn) Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val meterTick = object : Runnable {
        override fun run() {
            if (!meterOn) return
            val now = android.os.SystemClock.elapsedRealtime()
            val secs = ((now - lastT) / 1000f).coerceAtLeast(0.2f)
            val fps = Math.round(frames / secs)
            lastT = now
            frames = 0
            // Verde = liso, laranja = médio, vermelho = travando
            meterDot.setColor(
                when {
                    fps >= 50 -> 0xFF34C759.toInt()
                    fps >= 30 -> 0xFFFF9500.toInt()
                    else -> 0xFFFF3B30.toInt()
                },
            )
            handler.postDelayed(this, 1000)
        }
    }

    /** Só um pontinho no canto de baixo, abaixo da barra de apps. */
    private fun buildMeter() {
        meterDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF34C759.toInt())
        }
        val dot = View(this).apply {
            background = meterDot
            elevation = dpf(1)
        }
        val lp = FrameLayout.LayoutParams(dp(9), dp(9), Gravity.BOTTOM or Gravity.END)
        lp.marginEnd = dp(24)
        lp.bottomMargin = dp(9)
        addContentView(dot, lp)
    }

    private fun startMeter() {
        if (meterOn) return
        meterOn = true
        frames = 0
        lastT = android.os.SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback(frameCb)
        handler.postDelayed(meterTick, 1000)
    }

    private fun stopMeter() {
        meterOn = false
        handler.removeCallbacks(meterTick)
    }

    /** Pergunta (uma vez) se quer ativar o volume em número, que usa o Acessibilidade da TV. */
    private fun offerVolumeService() {
        val sp = getSharedPreferences("volume_numero", Context.MODE_PRIVATE)
        if (isFireTv()) return // o volume do Fire Stick é da TV/controle, não dele
        if (sp.getBoolean("perguntado", false) || VolumeService.enabled(this)) return
        sp.edit().putBoolean("perguntado", true).apply()
        val (d, box) = whiteDialog()
        box.addView(dialogTitle("Volume em número"))
        val msg = TextView(this).apply {
            text = "Mostra o volume de 0 a 100 na tela, em qualquer app.\nNa próxima tela, escolha \"Início\" e ative."
            textSize = 17f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(6))
        }
        box.addView(msg)
        val ativar = pill("Ativar agora") {
            d.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                toast("Abra Configurações > Acessibilidade > Início")
            }
        }
        val depois = pill("Agora não") { d.dismiss() }
        val lp = LinearLayout.LayoutParams(dp(300), WRAP)
        lp.topMargin = dp(12)
        box.addView(ativar, lp)
        box.addView(depois, LinearLayout.LayoutParams(dp(300), WRAP).apply { topMargin = dp(8) })
        d.show()
        ativar.post { ativar.requestFocus() }
    }

    /** Abre as configurações de Wi-Fi do sistema (para trocar de rede e digitar senha). */
    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            open(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Janela branca do Wi-Fi: rede atual, redes por perto e botão das configurações. */
    private fun openWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            openWifiSettings()
            return
        }
        val temPermissao = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!temPermissao) {
            try {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 77)
            } catch (_: Throwable) {
            }
        }
        var atual: String? = null
        try {
            if (wm.isWifiEnabled) {
                val ssid = wm.connectionInfo?.ssid?.trim('"')
                if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && wm.connectionInfo.networkId != -1) {
                    atual = ssid
                }
            }
        } catch (_: Throwable) {
        }
        val redes: List<Pair<String, Int>> = try {
            if (temPermissao && wm.isWifiEnabled) {
                wm.scanResults.orEmpty()
                    .filter { !it.SSID.isNullOrBlank() }
                    .groupBy { it.SSID }
                    .map { (nome, lista) -> nome to lista.maxOf { it.level } }
                    .sortedByDescending { it.second }
                    .take(6)
            } else emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val (d, box) = whiteDialog()
        box.addView(dialogTitle("Wi-Fi"))
        val estado = TextView(this).apply {
            text = when {
                !wm.isWifiEnabled -> "Wi-Fi desligado"
                atual != null -> "Conectado a $atual"
                else -> "Conectado"
            }
            textSize = 17f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
        }
        box.addView(estado)
        var first: View? = null
        for ((nome, nivel) in redes) {
            val barras = when {
                nivel >= -55 -> "▂▄▆█"
                nivel >= -67 -> "▂▄▆"
                nivel >= -80 -> "▂▄"
                else -> "▂"
            }
            val marca = if (nome == atual) "✓  " else ""
            val p = pill("$marca$nome   $barras") {
                d.dismiss()
                openWifiSettings()
            }
            val lp = LinearLayout.LayoutParams(dp(340), WRAP)
            lp.topMargin = dp(8)
            box.addView(p, lp)
            if (first == null) first = p
        }
        val config = pill("Configurações do Wi-Fi") {
            d.dismiss()
            openWifiSettings()
        }
        val lp = LinearLayout.LayoutParams(dp(340), WRAP)
        lp.topMargin = dp(14)
        box.addView(config, lp)
        if (first == null) first = config
        d.show()
        first?.post { first?.requestFocus() }
    }

    private fun openSearch() {
        for (action in listOf(Intent.ACTION_ASSIST, "android.search.action.GLOBAL_SEARCH")) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
        toast("Pesquisa não disponível nessa TV")
    }

    private fun open(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            i.component?.packageName?.let { if (it != "com.android.settings") prefs.marcarUso(it) }
        } catch (t: Throwable) {
            toast("Não deu para abrir esse app")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Quantos cartazes aparecem em cada aba */
        private const val DESTAQUES = 3
        private val INK: Int get() = Cores.tinta
        private val GRAY: Int get() = Cores.cinza
        /** Texto e ícones: preto no claro, branco no escuro. */
        private val PRETO: Int get() = Cores.tinta
        private const val TAB_CONTINUAR = 3
        private const val MAX_VOLTAS = 5
        /** Mude para true para o fundo pegar as cores do filme selecionado */
        private const val FUNDO_COM_FILME = false
        /** Os 15 avatares desenhados: 0-4 feminino, 5-9 masculino, 10-14 infantil */
        private val AVATARES = intArrayOf(
            R.drawable.avatar_00, R.drawable.avatar_01, R.drawable.avatar_02, R.drawable.avatar_03, R.drawable.avatar_04,
            R.drawable.avatar_05, R.drawable.avatar_06, R.drawable.avatar_07, R.drawable.avatar_08, R.drawable.avatar_09,
            R.drawable.avatar_10, R.drawable.avatar_11, R.drawable.avatar_12, R.drawable.avatar_13, R.drawable.avatar_14,
        )
        private const val DOCK_APPS = 7
        private const val DOCK_GAP = 12
        /** Tempo parado até o descanso de tela (3 minutos) */
        private const val IDLE_MS = 3 * 60_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
