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
            clock.text = timeFmt.format(Date())
            Weather.now { t -> weatherText.text = t ?: "" }
            handler.postDelayed(this, 10_000)
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
        setContentView(buildShell())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        buildDock()
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        buildDock()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
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
                intArrayOf(0xFFF6F3EF.toInt(), 0xFFE7E2DC.toInt()),
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
                    intArrayOf(0xB3F7F4F0.toInt(), 0x66F7F4F0, 0xD9F2EEEA.toInt()),
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
        listOf("Explorar", "Filmes", "Séries").forEachIndexed { i, s ->
            val t = makeTab(s, i)
            tabs += t
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(6)
            tabRow.addView(t, lp)
        }
        left.addView(tabRow)
        top.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Relógio grande com o clima de Pirapora embaixo
        val clockCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
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
        clp.rightMargin = dp(22)
        top.addView(clockCol, clp)
        // Igual ao modelo: Entradas (HDMI), Wi-Fi e Configurações
        top.addView(iconButton(R.drawable.ic_input) { openInputs() })
        top.addView(iconButton(R.drawable.ic_wifi) { openWifi() })
        top.addView(iconButton(R.drawable.ic_tune) { open(Intent(Settings.ACTION_SETTINGS)) })
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
            background = rounded(0xBFFFFFFF.toInt(), 30)
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
                    t.setTextColor(Color.WHITE)
                }
                i == currentTab -> {
                    bg.setColor(0x1A000000)
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

    private fun iconButton(res: Int, onClick: () -> Unit): View {
        val bg = rounded(0xCCFFFFFF.toInt(), 24)
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
                bg.setColor(if (has) INK else 0xCCFFFFFF.toInt())
                icon.setColorFilter(if (has) Color.WHITE else INK)
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
                showCards(items.take(DESTAQUES).map { Card(it.title, it, null) }, null)
            }
        }
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
            background = rounded(0xFFD9D3CC.toInt(), 18)
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
        if (b == null) {
            bgImage.animate().alpha(0f).setDuration(300).start()
            return
        }
        bgImage.setImageBitmap(Tmdb.blur(b))
        bgImage.alpha = 0.25f
        bgImage.animate().alpha(0.7f).setDuration(350).start()
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

    private fun dockLp(): LinearLayout.LayoutParams {
        val h = (resources.displayMetrics.heightPixels * 0.13f).toInt()
        val lp = LinearLayout.LayoutParams(h * 16 / 9, h)
        lp.rightMargin = dp(18)
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
            background = rounded(0x14000000, 14)
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
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        col.addView(
            TextView(this).apply {
                text = "Todos os apps"
                textSize = 11f
                setTextColor(INK)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            },
        )
        card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        styleDockFocus(card)
        card.layoutParams = dockLp()
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Entradas")
            .setItems(labels.toTypedArray()) { _, which -> switchInput(inputs[which]) }
            .show()
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
        private const val INK = 0xFF1C1C1E.toInt()
        private const val GRAY = 0xFF7C7C82.toInt()
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
