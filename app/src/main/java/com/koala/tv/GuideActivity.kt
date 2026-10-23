package com.koala.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chaînes transmises par l'écran de la liste. */
object Guide {
    var channels: List<Item> = emptyList()
    var title = ""
    var startIndex = 0
}

/** Guide TV en grille : chaînes en lignes, programmes sur l'axe du temps. */
class GuideActivity : AppCompatActivity() {

    private val win = GuideWindow()
    private val channels get() = Guide.channels
    private var selRow = 0
    private var selTime = 0L
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var ruler: GuideRuler
    private lateinit var adapter: RowAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val day = SimpleDateFormat("EEEE d", Locale.FRANCE)
    /** Début le plus ancien : jusqu'à 7 jours en arrière si des chaînes ont du replay. */
    private var minStart = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refreshAll()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (channels.isEmpty()) {
            finish(); return
        }
        setContentView(R.layout.activity_guide)
        setupBack()
        win.span = if (isWide) 3 * 3600L else 90 * 60L
        win.start = floor30(Epg.nowSec())
        val days = channels.maxOf { it.archive }.coerceIn(0, 7)
        minStart = floor30(Epg.nowSec() - days * 86400L)
        selRow = Guide.startIndex.coerceIn(0, channels.size - 1)
        selTime = Epg.nowSec()

        ruler = findViewById(R.id.ruler)
        ruler.window = win
        rv = findViewById(R.id.rv_rows)
        lm = LinearLayoutManager(this)
        rv.layoutManager = lm
        adapter = RowAdapter()
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        lm.scrollToPositionWithOffset(selRow, dp(66))
        updateDetails()
    }

    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        Epg.onLoaded = { id ->
            val i = channels.indexOfFirst { it.id == id }
            if (i >= 0) adapter.notifyItemChanged(i)
            if (i == selRow) updateDetails()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Epg.onLoaded = null
        handler.removeCallbacks(ticker)
    }

    private fun floor30(t: Long) = t - t % 1800

    private fun refreshAll() {
        findViewById<TextView>(R.id.tv_clock).text = Epg.hm(Epg.nowSec())
        findViewById<TextView>(R.id.tv_day).text = day.format(Date(win.start * 1000)).replaceFirstChar { it.uppercase() }
        ruler.invalidate()
        adapter.notifyItemRangeChanged(0, channels.size, PAYLOAD)
    }

    /** Programmes d'une ligne : passé compris pour les chaînes avec replay. */
    private fun rowPrograms(row: Int): List<Program>? {
        val ch = channels[row]
        if (ch.archive <= 0) return Epg.programs(ch.id)
        val from = Epg.nowSec() - ch.archive * 86400L
        return Epg.full(ch.id)?.filter { it.end > from }
    }

    private fun canReplay(row: Int, p: Program): Boolean {
        val ch = channels[row]
        val now = Epg.nowSec()
        return ch.archive > 0 && p.end <= now && p.start >= now - ch.archive * 86400L
    }

    /** Programme sélectionné dans une ligne : celui qui couvre l'heure choisie, sinon le suivant. */
    private fun selectedIn(row: Int): Program? {
        val list = rowPrograms(row) ?: return null
        return list.firstOrNull { it.start <= selTime && it.end > selTime }
            ?: list.firstOrNull { it.start > selTime }
            ?: list.lastOrNull()
    }

    private fun updateDetails() {
        val ch = channels[selRow]
        val p = selectedIn(selRow)
        val now = Epg.nowSec()
        findViewById<TextView>(R.id.tv_title).text = p?.title ?: ch.name
        val tvWhen = findViewById<TextView>(R.id.tv_when)
        tvWhen.text = if (p == null) {
            if (rowPrograms(selRow) == null) "Chargement du programme…" else "Pas de programme pour cette chaîne"
        } else {
            val state = when {
                p.end <= now -> if (canReplay(selRow, p)) "Replay · OK pour regarder" else "terminé"
                p.start <= now -> "en cours · reste ${((p.end - now) / 60).coerceAtLeast(1)} min"
                else -> "dans ${((p.start - now) / 60).coerceAtLeast(1)} min"
            }
            "${ch.name}  ·  ${Epg.hm(p.start)} – ${Epg.hm(p.end)}  ·  $state"
        }
        val desc = findViewById<TextView>(R.id.tv_desc)
        desc.text = p?.desc ?: ""
        desc.visibility = if (p?.desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        refreshAll()
    }

    private fun moveRow(d: Int) {
        val n = (selRow + d).coerceIn(0, channels.size - 1)
        if (n == selRow) return
        val old = selRow
        selRow = n
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(n)
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        val rowH = dp(66)
        if (first >= 0 && n <= first) lm.scrollToPositionWithOffset(n, rowH)
        else if (last >= 0 && n >= last) lm.scrollToPositionWithOffset(n, rv.height - rv.paddingBottom - 2 * rowH)
        updateDetails()
    }

    private fun moveProg(d: Int) {
        val list = rowPrograms(selRow).orEmpty()
        val cur = selectedIn(selRow)
        val idx = if (cur == null) -1 else list.indexOf(cur)
        val next = list.getOrNull(idx + d)
        val now = Epg.nowSec()
        if (next == null) {
            // ligne vide ou bout de la liste : on fait défiler le temps par demi-heure
            val s = (win.start + d * 1800L).coerceAtLeast(minStart)
            if (s == win.start) return
            win.start = s
            selTime = if (d < 0) s + 60 else maxOf(now, s)
        } else {
            selTime = if (next.start <= now && next.end > now) now else next.start
            if (next.start >= win.end - 1800) win.start = floor30(next.start) - 1800
            if (next.end <= win.start || (next.start < win.start && d < 0)) win.start = maxOf(minStart, floor30(next.start))
        }
        updateDetails()
    }

    private fun play(row: Int) {
        val p = if (row == selRow) selectedIn(row) else null
        if (p != null && canReplay(row, p)) {
            val ch = channels[row]
            val min = ((p.end - p.start + 59) / 60).toInt()
            val item = PlayItem("${ch.name} · ${p.title}", Xtream.catchupUrl(ch.id, p.start, min), false, null, ch.id, ch.icon)
            Nav.play(this, listOf(item), 0)
            return
        }
        Nav.play(this, channels.map { liveItem(it) }, row)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                else -> super.dispatchKeyEvent(event)
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-6)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(6)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProg(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProg(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> play(selRow)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cell: View = v.findViewById(R.id.cell)
            val img: ImageView = v.findViewById(R.id.iv)
            val name: TextView = v.findViewById(R.id.tv_name)
            val strip: GuideStrip = v.findViewById(R.id.strip)
            var id = ""
        }

        override fun getItemCount() = channels.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))
            h.strip.window = win
            h.cell.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) play(p)
            }
            h.strip.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val row = h.bindingAdapterPosition
                    if (row != RecyclerView.NO_POSITION) {
                        val prog = h.strip.programAt(e.x)
                        if (row == selRow && prog != null && prog == selectedIn(row)) {
                            play(row) // deuxième appui sur le même programme : lecture
                        } else {
                            if (prog != null) selTime = if (prog.start <= Epg.nowSec() && prog.end > Epg.nowSec()) Epg.nowSec() else prog.start
                            val old = selRow
                            selRow = row
                            adapter.notifyItemChanged(old)
                            adapter.notifyItemChanged(row)
                            updateDetails()
                        }
                    }
                    v.performClick()
                }
                true
            }
            return h
        }

        private fun bindStrip(h: VH, position: Int) {
            h.strip.programs = rowPrograms(position)
            h.strip.replay = channels[position].archive > 0
            h.strip.rowFocused = position == selRow
            h.strip.selected = if (position == selRow) selectedIn(position) else null
            h.cell.isSelected = position == selRow
            h.strip.invalidate()
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) bindStrip(h, position) else onBindViewHolder(h, position)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            h.id = ch.id
            h.name.text = ch.name
            if (ch.icon.startsWith("http", true)) h.img.load(ch.icon) else {
                h.img.dispose(); h.img.setImageDrawable(null)
            }
            bindStrip(h, position)
        }

        override fun onViewRecycled(h: VH) {
            if (h.id.isNotEmpty()) Epg.drop(h.id)
        }
    }

    companion object {
        private const val PAYLOAD = "tick"
    }
}
