package com.geanalves.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppsActivity : Activity() {

    private data class Tile(
        val key: String,
        val title: String,
        val banner: Drawable?,
        val icon: Drawable?,
        val onClick: () -> Unit,
        val onMenu: (() -> Unit)? = null,
    )

    private lateinit var prefs: Prefs
    private lateinit var backdrop: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroHint: TextView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var lastFocusKey: String? = null
    private var firstTile: View? = null
    private val topRowKeys = HashSet<String>()

    private val ptBr = Locale("pt", "BR")
    private val timeFmt = SimpleDateFormat("HH:mm", ptBr)
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", ptBr)

    private val palette = intArrayOf(
        0xFF2B3A67.toInt(), 0xFF3D2C5E.toInt(), 0xFF1F4E5F.toInt(),
        0xFF5A2F3F.toInt(), 0xFF2F4F3A.toInt(), 0xFF4A3B24.toInt(),
    )

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            AppRepo.clearCache()
            rebuild()
        }
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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
    }

    override fun onResume() {
        super.onResume()
        rebuild()
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


    // ---------- montagem da tela ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int): Float = v * resources.displayMetrics.density

    private fun buildShell(): View {
        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Cores.fundoTopo, Cores.fundoBase),
        )

        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.18f
        }
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (Cores.escuro) {
                    intArrayOf(0x991E1E24.toInt(), 0xD91E1E24.toInt(), 0xF20B0B0F.toInt())
                } else {
                    intArrayOf(0x99F7F4F0.toInt(), 0xD9F7F4F0.toInt(), 0xF2F2EEEA.toInt())
                },
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            isSmoothScrollingEnabled = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(64))
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // Cabeçalho: nome do app em foco + relógio
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val heroCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroTitle = TextView(this).apply {
            textSize = 40f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        heroHint = TextView(this).apply {
            textSize = 15f
            setTextColor(GRAY)
            text = "Todos os apps · segure OK num app para favoritar ou ocultar"
        }
        heroCol.addView(heroTitle)
        heroCol.addView(heroHint)
        header.addView(heroCol, LinearLayout.LayoutParams(0, WRAP, 1f))

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
        dateView = TextView(this).apply {
            textSize = 15f
            setTextColor(GRAY)
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        weatherText = TextView(this).apply {
            textSize = 15f
            setTextColor(GRAY)
            gravity = Gravity.END
        }
        clockCol.addView(clock)
        clockCol.addView(dateView)
        clockCol.addView(weatherText)
        header.addView(clockCol, LinearLayout.LayoutParams(WRAP, WRAP))
        body.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // Espaço para o "palco" do app em foco, como na Apple TV
        body.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(140)))

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        body.addView(rows, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun rebuild() {
        val apps = AppRepo.load(this)
        prefs.ensureDefaults(apps)
        val hidden = prefs.hidden
        val byPkg = apps.associateBy { it.pkg }
        val favs = prefs.favs.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }
        val favSet = favs.map { it.pkg }.toSet()
        val others = apps.filter { it.pkg !in hidden && it.pkg !in favSet }
        val hiddenApps = apps.filter { it.pkg in hidden }

        rows.removeAllViews()
        firstTile = null
        topRowKeys.clear()
        var isTop = true

        if (favs.isNotEmpty()) {
            rows.addView(sectionLabel("Favoritos", first = true))
            addGrid(favs.map { appTile(it) }, isTop)
            isTop = false
        }
        if (others.isNotEmpty()) {
            rows.addView(sectionLabel("Todos os apps", first = isTop))
            addGrid(others.map { appTile(it) }, isTop)
            isTop = false
        }

        val system = mutableListOf(
            Tile(
                key = "#settings",
                title = "Configurações",
                banner = null,
                icon = getDrawable(android.R.drawable.ic_menu_preferences),
                onClick = { open(Intent(Settings.ACTION_SETTINGS)) },
            ),
        )
        if (hiddenApps.isNotEmpty()) {
            system += Tile(
                key = "#hidden",
                title = "Apps ocultos (${hiddenApps.size})",
                banner = null,
                icon = getDrawable(android.R.drawable.ic_menu_view),
                onClick = { showHiddenMenu(hiddenApps) },
            )
        }
        rows.addView(sectionLabel("Sistema", first = isTop))
        addGrid(system, isTop)

        rows.post {
            val target = lastFocusKey?.let { rows.findViewWithTag<View>(it) } ?: firstTile
            target?.requestFocus()
        }
    }

    private fun appTile(e: AppEntry): Tile {
        val banner = AppRepo.banner(this, e)
        return Tile(
            key = e.pkg,
            title = e.label,
            banner = banner,
            icon = if (banner == null) AppRepo.icon(this, e) else null,
            onClick = { open(e.intent) },
            onMenu = { showAppMenu(e) },
        )
    }

    private fun sectionLabel(text: String, first: Boolean): TextView =
        TextView(this).apply {
            this.text = text.uppercase(ptBr)
            textSize = 14f
            letterSpacing = 0.12f
            setTextColor(GRAY)
            setPadding(0, if (first) 0 else dp(36), 0, dp(4))
        }

    private fun addGrid(items: List<Tile>, isTopSection: Boolean) {
        val gap = dp(24)
        val usable = resources.displayMetrics.widthPixels - dp(112)
        val tileW = (usable - gap * (COLS - 1)) / COLS
        val tileH = tileW * 9 / 16

        items.chunked(COLS).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            chunk.forEachIndexed { i, t ->
                if (isTopSection && rowIndex == 0) topRowKeys += t.key
                val v = makeTile(t)
                if (firstTile == null) firstTile = v
                val lp = LinearLayout.LayoutParams(tileW, tileH)
                if (i > 0) lp.leftMargin = gap
                row.addView(v, lp)
            }
            val rlp = LinearLayout.LayoutParams(WRAP, WRAP)
            rlp.topMargin = gap
            rows.addView(row, rlp)
        }
    }

    private fun makeTile(t: Tile): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dpf(14)
            setColor(if (t.banner != null) 0xFF2C2C2E.toInt() else Cores.cartaoApp)
        }
        val card = FrameLayout(this).apply {
            background = bg
            clipToOutline = true
            elevation = dpf(2)
            isFocusable = true
            isClickable = true
            tag = t.key
        }

        if (t.banner != null) {
            card.addView(
                ImageView(this).apply {
                    setImageDrawable(t.banner)
                    scaleType = ImageView.ScaleType.FIT_XY
                },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            if (t.icon != null) {
                col.addView(
                    ImageView(this).apply { setImageDrawable(t.icon) },
                    LinearLayout.LayoutParams(dp(52), dp(52)),
                )
            }
            col.addView(
                TextView(this).apply {
                    text = t.title
                    textSize = 14f
                    setTextColor(INK)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val ring = GradientDrawable().apply {
            cornerRadius = dpf(14)
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), INK)
        }

        card.setOnFocusChangeListener { v, has ->
            v.animate()
                .scaleX(if (has) 1.1f else 1f)
                .scaleY(if (has) 1.1f else 1f)
                .translationZ(if (has) dpf(18) else 0f)
                .setDuration(160)
                .start()
            v.foreground = if (has) ring else null
            if (has) {
                lastFocusKey = t.key
                heroTitle.text = t.title
                backdrop.setImageDrawable(t.banner)
                if (t.key in topRowKeys) scroll.smoothScrollTo(0, 0)
            }
        }
        card.setOnClickListener { t.onClick() }
        t.onMenu?.let { menu ->
            card.setOnLongClickListener {
                menu()
                true
            }
            card.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) {
                    menu()
                    true
                } else {
                    false
                }
            }
        }
        return card
    }

    // ---------- ações ----------

    private fun open(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Toast.makeText(this, "Não deu para abrir esse app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(e: AppEntry) {
        AppMenu.show(this, prefs, e) { rebuild() }
    }

    private fun showHiddenMenu(hiddenApps: List<AppEntry>) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Escolha um app para voltar a mostrar")
            .setItems(hiddenApps.map { it.label }.toTypedArray()) { _, which ->
                prefs.unhide(hiddenApps[which].pkg)
                rebuild()
            }
            .show()
    }

    private fun updateClock() {
        val now = Date()
        clock.text = timeFmt.format(now)
        dateView.text = dateFmt.format(now).replaceFirstChar { it.titlecase(ptBr) }
        Weather.now { t -> weatherText.text = t ?: "" }
    }

    companion object {
        private const val COLS = 5
        private val INK: Int get() = Cores.tinta
        private val GRAY: Int get() = Cores.cinza
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
