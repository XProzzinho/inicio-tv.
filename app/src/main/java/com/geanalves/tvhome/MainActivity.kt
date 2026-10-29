package com.geanalves.tvhome

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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

    private data class Card(val title: String, val item: Tmdb.Item?, val app: AppEntry?)

    private lateinit var prefs: Prefs
    private lateinit var bgImage: ImageView
    private lateinit var cardsScroll: HorizontalScrollView
    private lateinit var cardsRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var dockRow: LinearLayout
    private lateinit var clock: TextView
    private lateinit var weatherText: TextView
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

    private val ptBr = Locale("pt", "BR")
    private val timeFmt = SimpleDateFormat("HH:mm", ptBr)

    private val tick = object : Runnable {
        override fun run() {
            // Virou noite/dia: refaz a tela com as cores certas
            if (Cores.atualizar()) {
                recreate()
                return
            }
            clock.text = timeFmt.format(Date())
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
        val dm = resources.displayMetrics
        cardH = (dm.heightPixels * 0.36f).toInt()
        wideW = cardH * 16 / 9
        narrowW = (cardH * 0.78f).toInt()
        // Dois cartazes grandes + um pequeno cabendo na largura da tela
        val usable = dm.widthPixels - dp(112) - dp(26) * 2 - narrowW
        bigW = (usable / 2).coerceAtMost(wideW)
        Cores.atualizar()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Cores.fundoTopo))
        setContentView(buildShell())
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
        super.onResume()
        buildDock()
        handler.removeCallbacks(tick)
        handler.post(tick)
        resetIdle()
    }

    override fun onPause() {
        super.onPause()
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

    private fun goHome() {
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Perfil (bolinha com a inicial) + saudação
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
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
        left.addView(
            TextView(this).apply {
                text = "Para você"
                textSize = 36f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
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
        left.addView(tabRow)
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
        clock = TextView(this).apply {
            textSize = 52f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.END
        }
        weatherText = TextView(this).apply {
            textSize = 16f
            setTextColor(GRAY)
            gravity = Gravity.END
            setPadding(0, dp(4), dp(2), 0)
        }
        clockCol.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        clockCol.addView(weatherText, LinearLayout.LayoutParams(WRAP, WRAP))
        val clp = LinearLayout.LayoutParams(WRAP, WRAP)
        clp.rightMargin = dp(8)
        top.addView(clockCol, clp)
        // Entradas (HDMI), Wi-Fi e Configurações
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 30)
            elevation = dpf(3)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = false
            clipToPadding = false
        }
        group.addView(groupButton(R.drawable.ic_input, first = true) { openInputs() })
        group.addView(groupButton(R.drawable.ic_wifi) { openWifi() })
        group.addView(groupButton(R.drawable.ic_menu) { open(Intent(Settings.ACTION_SETTINGS)) })
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
            setPadding(0, dp(26), resources.displayMetrics.widthPixels, dp(8))
        }
        cardsScroll.addView(cardsRow, FrameLayout.LayoutParams(WRAP, MATCH))
        cardsArea.addView(cardsScroll, FrameLayout.LayoutParams(MATCH, MATCH))
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
        val dock = FrameLayout(this).apply {
            background = rounded(Cores.barra, 30)
            elevation = dpf(3)
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
        content.addView(dock, LinearLayout.LayoutParams(MATCH, WRAP))

        saver = buildSaver()
        root.addView(saver, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun makeTab(label: String, index: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            background = rounded(Color.TRANSPARENT, 20)
            setOnFocusChangeListener { _, has ->
                if (has) selectTab(index)
                styleTabs()
            }
            setOnClickListener { selectTab(index) }
        }

    private fun styleTabs() {
        tabs.forEachIndexed { i, t ->
            val bg = t.background as GradientDrawable
            when {
                t.isFocused -> {
                    bg.setColor(INK)
                    t.setTextColor(Cores.papel)
                }
                i == currentTab -> {
                    bg.setColor(Cores.sutil)
                    t.setTextColor(INK)
                }
                else -> {
                    bg.setColor(Color.TRANSPARENT)
                    t.setTextColor(GRAY)
                }
            }
            t.typeface = if (i == currentTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
                icon.setColorFilter(if (has) Color.WHITE else PRETO)
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
        Tmdb.list(currentTab) { items ->
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
        // Sem espaço em branco no fim da fileira de filmes
        cardsRow.setPadding(0, dp(26), if (fixedLayout) dp(8) else resources.displayMetrics.widthPixels, dp(8))
        status(note)
        val focusWasHere = cardsRow.hasFocus()
        cardsRow.removeAllViews()
        selected = null
        cardsScroll.scrollTo(0, 0)
        cards.forEachIndexed { i, c ->
            val v = makeCard(c)
            val w = if (fixedLayout && i < 2) bigW else narrowW
            val lp = LinearLayout.LayoutParams(w, WRAP)
            lp.rightMargin = dp(26)
            cardsRow.addView(v, lp)
        }
        val first = cardsRow.getChildAt(0) ?: return
        select(first, animate = false)
        if (firstLoad || focusWasHere || currentFocus == null) {
            firstLoad = false
            first.post { (first as ViewGroup).getChildAt(0).requestFocus() }
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
        val avail = resources.displayMetrics.widthPixels - dp(112) - dp(32) - dp(16)
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

    private fun whiteDialog(): Pair<android.app.Dialog, LinearLayout> {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.WHITE, 28)
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
        val bg = rounded(0xFFF2F2F7.toInt(), 22)
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
                bg.setColor(if (has) PRETO else 0xFFF2F2F7.toInt())
                setTextColor(if (has) Color.WHITE else PRETO)
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

    /** Quem está assistindo? Três bolinhas e o botão Editar perfil. */
    private fun showProfiles() {
        val atual = prefs.perfil
        val (d, box) = whiteDialog()
        box.addView(dialogTitle("Quem está assistindo?"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, dp(24))
            clipChildren = false
            clipToPadding = false
        }
        var focusMe: View? = null
        for (i in 0 until Prefs.PERFIS) {
            val ringBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                if (i == atual) setStroke(dp(3), 0xFFD1D1D6.toInt())
            }
            val holder = FrameLayout(this).apply { background = ringBg }
            holder.addView(avatarCircle(i, 76, 30f))
            val name = TextView(this).apply {
                text = prefs.nome(i)
                textSize = 16f
                setTextColor(PRETO)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = if (i == atual) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, dp(10), 0, 0)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isClickable = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    prefs.perfil = i
                    refreshProfile()
                    refreshApps()
                    d.dismiss()
                }
                setOnFocusChangeListener { _, has ->
                    ringBg.setStroke(dp(3), if (has) PRETO else if (i == atual) 0xFFD1D1D6.toInt() else Color.TRANSPARENT)
                    holder.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(140).start()
                }
            }
            col.addView(holder, LinearLayout.LayoutParams(dp(88), dp(88)))
            col.addView(name, LinearLayout.LayoutParams(dp(110), WRAP))
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.leftMargin = dp(10)
            lp.rightMargin = dp(10)
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
            background = rounded(0xFFF2F2F7.toInt(), 14)
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
            Tmdb.list(0) { items ->
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

    private fun openWifi() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            open(Intent(Settings.ACTION_SETTINGS))
        }
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
        private const val PRETO = 0xFF1C1C1E.toInt()
        private const val TAB_CONTINUAR = 3
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
