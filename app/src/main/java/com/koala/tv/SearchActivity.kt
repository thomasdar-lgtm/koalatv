package com.koala.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Recherche globale : chaînes, films et séries en une seule recherche. */
class SearchActivity : AppCompatActivity() {

    private var pool: List<Item> = emptyList()
    private val adapter = Adapter()
    private lateinit var et: EditText
    private lateinit var rv: RecyclerView
    private lateinit var tvHead: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        setupBack()
        et = findViewById(R.id.et_search)
        et.hint = "Chaîne, film ou série"
        rv = findViewById(R.id.rv_items)
        tvHead = findViewById(R.id.tv_head)
        tvCount = findViewById(R.id.tv_count)
        tvEmpty = findViewById(R.id.tv_empty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.itemAnimator = null
        voiceInput(findViewById(R.id.btn_mic), et)

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable { run(s?.toString() ?: "") }
                pending = r
                handler.postDelayed(r, 300)
            }
        })
        et.setOnEditorActionListener { _, _, _ ->
            run(et.text.toString()); focusResults(); true
        }
        // télécommande : bas, ou droite en fin de saisie, mènent aux résultats
        et.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN || adapter.itemCount == 0) return@setOnKeyListener false
            val atEnd = et.selectionStart == et.text.length && et.selectionEnd == et.text.length
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || (code == KeyEvent.KEYCODE_DPAD_RIGHT && atEnd)) {
                focusResults(); true
            } else false
        }
        showEmpty("Tape le nom d'une chaîne, d'un film ou d'une série")

        lifecycleScope.launch {
            val all = ArrayList<Item>()
            for (k in Kind.values()) {
                try {
                    Lists.get(k).items.filterTo(all) { !Hidden.isHidden(k, it.categoryId) }
                } catch (e: Exception) {
                    // liste indisponible : recherche sur les autres
                }
            }
            pool = all
            if (et.text.isNotBlank()) run(et.text.toString())
        }
        et.requestFocus()
    }

    private fun focusResults() {
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(et.windowToken, 0)
        rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun showEmpty(msg: String) {
        tvEmpty.text = msg
        tvEmpty.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun run(raw: String) {
        val q = fold(raw.trim())
        if (q.isEmpty()) {
            adapter.submit(emptyList()); tvHead.text = ""; tvCount.text = ""
            showEmpty("Tape le nom d'une chaîne, d'un film ou d'une série")
            return
        }
        val groups = Array(4) { ArrayList<Item>() }
        for (it in pool) {
            val g = searchRank(it, q)
            if (g >= 0) groups[g].add(it)
        }
        val list = groups.flatMap { it }.take(300)
        adapter.submit(list)
        rv.scrollToPosition(0)
        tvHead.text = "Résultats"
        tvCount.text = if (list.isEmpty()) "" else plural(list.size, "résultat", "résultats")
        showEmpty(if (list.isNotEmpty()) "" else if (pool.isEmpty()) "Chargement des listes…" else "Aucun résultat")
    }

    private fun open(item: Item) {
        if (item.kind != Kind.LIVE) {
            Nav.openDetails(this, item); return
        }
        // chaîne : on zappe parmi les chaînes trouvées
        val lives = adapter.items.filter { it.kind == Kind.LIVE }
        Nav.play(this, lives.map { liveItem(it) }, lives.indexOf(item).coerceAtLeast(0))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Item> = emptyList()
            private set

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val kind: TextView = v.findViewById(R.id.tv_kind)
            val box: View = v.findViewById(R.id.box)
            val img: ImageView = v.findViewById(R.id.iv)
            val name: TextView = v.findViewById(R.id.tv_name)
        }

        fun submit(l: List<Item>) {
            items = l
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search, parent, false)
            v.focusZoom(1.015f)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.kind.text = when (it.kind) {
                Kind.LIVE -> "Chaîne"
                Kind.MOVIE -> "Film"
                Kind.SERIES -> "Série"
            }
            h.name.text = it.name
            val live = it.kind == Kind.LIVE
            h.box.layoutParams = h.box.layoutParams.apply { width = dp(if (live) 76 else 40) }
            h.img.scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            h.img.setPadding(if (live) dp(5) else 0, if (live) dp(5) else 0, if (live) dp(5) else 0, if (live) dp(5) else 0)
            if (it.icon.startsWith("http", true)) h.img.load(it.icon) else h.img.setImageDrawable(null)
            h.itemView.setOnClickListener { _ -> open(it) }
        }
    }
}
