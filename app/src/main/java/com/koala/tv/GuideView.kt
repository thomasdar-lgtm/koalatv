package com.koala.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Fenêtre de temps affichée par la grille (partagée par la règle et toutes les lignes). */
class GuideWindow {
    var start = 0L
    var span = 3 * 3600L
    val end get() = start + span
}

/** Une ligne de la grille : les programmes d'une chaîne dessinés comme des blocs proportionnels à leur durée. */
class GuideStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var window: GuideWindow? = null
    var programs: List<Program>? = null
    var selected: Program? = null
    var rowFocused = false

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.row) }
    private val bgNow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface) }
    private val bgSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface_hi) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val nowBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
        textSize = 14.5f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
    }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT
    }
    private val r = RectF()

    fun xOf(sec: Long): Float {
        val w = window ?: return 0f
        return (sec - w.start).toFloat() / w.span * width
    }

    fun programAt(x: Float): Program? {
        val w = window ?: return null
        val t = w.start + (x / width * w.span).toLong()
        return programs?.firstOrNull { it.start <= t && it.end > t }
    }

    override fun onDraw(c: Canvas) {
        val w = window ?: return
        val gap = 3f * d
        val rad = 8f * d
        val h = height.toFloat()
        val list = programs
        if (list == null || list.isEmpty()) {
            r.set(0f, gap, width.toFloat(), h - gap)
            c.drawRoundRect(r, rad, rad, if (rowFocused) bgSel else bg)
            if (rowFocused) c.drawRoundRect(r, rad, rad, ring)
            val txt = if (list == null) "Chargement…" else "Pas de programme"
            c.drawText(txt, 14f * d, h / 2 + sub.textSize / 3, sub)
            return
        }
        val now = Epg.nowSec()
        for (p in list) {
            if (p.end <= w.start || p.start >= w.end) continue
            val x0 = maxOf(0f, xOf(p.start)) + gap / 2
            val x1 = minOf(width.toFloat(), xOf(p.end)) - gap / 2
            if (x1 - x0 < 2f) continue
            r.set(x0, gap, x1, h - gap)
            val isSel = rowFocused && p == selected
            c.drawRoundRect(r, rad, rad, if (isSel) bgSel else if (p.start <= now && p.end > now) bgNow else bg)
            if (isSel) c.drawRoundRect(r, rad, rad, ring)
            val avail = x1 - x0 - 24f * d
            if (avail > 12f * d) {
                val t = TextUtils.ellipsize(p.title, title, avail, TextUtils.TruncateAt.END)
                c.drawText(t, 0, t.length, x0 + 12f * d, h / 2 - 2f * d, title)
                val s = TextUtils.ellipsize("${Epg.hm(p.start)} – ${Epg.hm(p.end)}", sub, avail, TextUtils.TruncateAt.END)
                c.drawText(s, 0, s.length, x0 + 12f * d, h / 2 + sub.textSize + 2f * d, sub)
            }
        }
        // repère « maintenant »
        if (now in w.start until w.end) {
            val x = xOf(now)
            c.drawRect(x - 1f * d, 0f, x + 1f * d, h, nowBar)
        }
    }
}

/** Règle des heures au-dessus de la grille. */
class GuideRuler @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var window: GuideWindow? = null
    private val d = resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT
    }
    private val tick = Paint().apply { color = ContextCompat.getColor(context, R.color.badge) }
    private val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }

    override fun onDraw(c: Canvas) {
        val w = window ?: return
        var t = w.start - w.start % 1800
        while (t < w.end) {
            if (t >= w.start) {
                val x = (t - w.start).toFloat() / w.span * width
                c.drawRect(x, height - 8f * d, x + 1f * d, height.toFloat(), tick)
                c.drawText(Epg.hm(t), x + 6f * d, height - 12f * d, text)
            }
            t += 1800
        }
        val now = Epg.nowSec()
        if (now in w.start until w.end) {
            val x = (now - w.start).toFloat() / w.span * width
            c.drawCircle(x, height - 4f * d, 4f * d, nowP)
        }
    }
}
