package com.geanalves.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.util.Collections

data class AppEntry(
    val pkg: String,
    val activity: String,
    val label: String,
    val intent: Intent,
)

/** Lê os apps instalados na TV (apps de TV primeiro, depois apps de celular). */
object AppRepo {
    private val bannerCache = HashMap<String, Drawable?>()
    private val iconCache = HashMap<String, Drawable?>()

    fun clearCache() {
        bannerCache.clear()
        iconCache.clear()
    }

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (found.containsKey(ai.packageName)) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                found[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    activity = ai.name,
                    label = ri.loadLabel(pm)?.toString() ?: ai.packageName,
                    intent = launch,
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun banner(ctx: Context, e: AppEntry): Drawable? = bannerCache.getOrPut(e.pkg) {
        try {
            val pm = ctx.packageManager
            val ai = pm.getActivityInfo(ComponentName(e.pkg, e.activity), 0)
            ai.loadBanner(pm) ?: pm.getApplicationBanner(e.pkg)
        } catch (t: Throwable) {
            null
        }
    }

    fun icon(ctx: Context, e: AppEntry): Drawable? = iconCache.getOrPut(e.pkg) {
        try {
            ctx.packageManager.getActivityIcon(ComponentName(e.pkg, e.activity))
        } catch (t: Throwable) {
            null
        }
    }
}

/** Favoritos (em ordem) e apps ocultos, guardados na própria TV. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    var favs: List<String>
        get() = sp.getString("favs", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) {
            sp.edit().putString("favs", v.joinToString(",")).apply()
        }

    val hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()

    fun hide(p: String) {
        sp.edit().putStringSet("hidden", hidden + p).apply()
        favs = favs - p
    }

    fun unhide(p: String) {
        sp.edit().putStringSet("hidden", hidden - p).apply()
    }

    fun addFav(p: String) {
        if (p !in favs) favs = favs + p
    }

    fun removeFav(p: String) {
        favs = favs - p
    }

    fun moveFav(p: String, delta: Int) {
        val l = favs.toMutableList()
        val i = l.indexOf(p)
        val j = i + delta
        if (i < 0 || j < 0 || j >= l.size) return
        Collections.swap(l, i, j)
        favs = l
    }

    /** Na primeira vez, já coloca os apps de streaming mais comuns nos favoritos. */
    fun ensureDefaults(apps: List<AppEntry>) {
        if (sp.contains("favs")) return
        val installed = apps.map { it.pkg }.toSet()
        val known = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.globo.globotv",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
        )
        var f = known.filter { it in installed }
        if (f.isEmpty()) f = apps.take(5).map { it.pkg }
        favs = f
    }
}
