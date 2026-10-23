package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    companion object {
        private const val FAV = "__fav"
        private const val ALL = "__all"
    }

    private lateinit var kind: Kind
    private lateinit var rvCats: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvHead: TextView
    private lateinit var tvCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var catAdapter: CatAdapter
    private lateinit var itemAdapter: ItemAdapter

    private var allItems: List<Item> = emptyList()
    /** Éléments affichables : sans les catégories masquées ni verrouillées (tant que le code n'est pas saisi). */
    private var pool: List<Item> = emptyList()
    private var lastData: Lists.Data? = null
    private var cats: List<Category> = emptyList()
    private var catIdx = -1
    private var restoreCat = -1
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelect: Runnable? = null
    private var pendingSearch: Runnable? = null
    private var ignoreSearch = false

    private val units: Pair<String, String>
        get() = when (kind) {
            Kind.LIVE -> "chaîne" to "chaînes"
            Kind.MOVIE -> "film" to "films"
            Kind.SERIES -> "série" to "séries"
        }

    /** Catégories en colonne à gauche (grand écran) ou en puces au-dessus (téléphone). */
    private val sidePanel: Boolean get() = rvCats.tag != "chips"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = try {
            Kind.valueOf(intent.getStringExtra("kind") ?: "LIVE")
        } catch (e: Exception) {
            Kind.LIVE
        }
        restoreCat = savedInstanceState?.getInt("cat", -1) ?: -1
        setContentView(R.layout.activity_browse)

        rvCats = findViewById(R.id.rv_cats)
        rvItems = findViewById(R.id.rv_items)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tv_empty)
        tvHead = findViewById(R.id.tv_head)
        tvCount = findViewById(R.id.tv_count)
        etSearch = findViewById(R.id.et_search)
        findViewById<TextView>(R.id.tv_title).text = when (kind) {
            Kind.LIVE -> "TV en direct"
            Kind.MOVIE -> "Films"
            Kind.SERIES -> "Séries"
        }

        val wide = sidePanel
        catAdapter = CatAdapter(
            if (wide) R.layout.item_category else R.layout.item_chip,
            onFocus = { pos -> scheduleSelect(pos) },
            onClick = { pos ->
                pendingSelect?.let { handler.removeCallbacks(it) }
                if (pos != catAdapter.selected || etSearch.text.isNotEmpty()) selectCategory(pos)
                if (wide) focusFirstItem()
            },
        )
        rvCats.layoutManager = LinearLayoutManager(this, if (wide) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        itemAdapter = ItemAdapter(kind, ::openItem, ::toggleFav)
        rvItems.adapter = itemAdapter
        rvItems.itemAnimator = null
        if (kind == Kind.LIVE) {
            rvItems.layoutManager = LinearLayoutManager(this)
        } else {
            val glm = GridLayoutManager(this, 3)
            rvItems.layoutManager = glm
            val minW = resources.getDimensionPixelSize(R.dimen.poster_min)
            rvItems.addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
                if (r - l != oldR - oldL) {
                    val span = ((v.width - v.paddingLeft - v.paddingRight) / minW).coerceIn(2, 10)
                    if (glm.spanCount != span) v.post { glm.spanCount = span }
                }
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreSearch) return
                pendingSearch?.let { handler.removeCallbacks(it) }
                val r = Runnable { runSearch(s?.toString() ?: "") }
                pendingSearch = r
                handler.postDelayed(r, 300)
            }
        })
        etSearch.setOnEditorActionListener { _, _, _ ->
            pendingSearch?.let { handler.removeCallbacks(it) }
            runSearch(etSearch.text.toString())
            hideKeyboard()
            focusFirstItem()
            true
        }

        tvEmpty.setOnClickListener { if (allItems.isEmpty()) load() }
        if (kind == Kind.LIVE) {
            findViewById<View>(R.id.btn_guide).apply {
                visibility = View.VISIBLE
                setOnClickListener { openGuide() }
            }
        }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("cat", catIdx)
    }

    private val epgTicker = object : Runnable {
        override fun run() {
            itemAdapter.epgTick()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (kind == Kind.LIVE) {
            Epg.onLoaded = { id -> itemAdapter.epgLoaded(id) }
            handler.removeCallbacks(epgTicker)
            handler.postDelayed(epgTicker, 60_000)
            itemAdapter.epgTick()
        }
        Lists.onUpdated = { k ->
            val d = Lists.peek(k)
            if (k == kind && d != null && allItems.isNotEmpty() && etSearch.text.isEmpty()) apply(d, initial = false)
        }
        if (allItems.isNotEmpty()) {
            itemAdapter.reloadFavs()
            refreshFavCount()
        }
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                apply(Lists.get(kind), initial = true)
            } catch (e: Exception) {
                showMessage("Impossible de charger la liste.\n${e.message ?: ""}\n\nToucher pour réessayer", true)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** Construit les catégories (Favoris, Tout, puis celles du serveur qui ne sont pas vides). */
    private fun apply(data: Lists.Data, initial: Boolean) {
        val list = data.items
        val prevId = cats.getOrNull(catIdx)?.id
        lastData = data
        allItems = list
        pool = list.filter { !Hidden.isHidden(kind, it.categoryId) }
        val counts = HashMap<String, Int>()
        for (it in list) counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1
        val favs = Prefs.favs()
        val favN = pool.count { favs.contains(it.favKey) }
        val built = ArrayList<Category>()
        built.add(Category(FAV, "Favoris", favN))
        built.add(Category(ALL, "Tout", pool.size))
        for (cat in data.cats) {
            val n = counts[cat.id] ?: 0
            if (n == 0 || Hidden.isHidden(kind, cat.id)) continue
            built.add(Category(cat.id, cat.name, n))
        }
        cats = built
        catAdapter.submit(built)
        if (initial) {
            val start = when {
                restoreCat in built.indices -> restoreCat
                favN > 0 -> 0
                built.size > 2 -> 2
                else -> 1
            }
            selectCategory(start)
            rvCats.scrollToPosition(start)
            if (sidePanel) rvCats.post { rvCats.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }
        } else {
            // liste rafraîchie en arrière-plan : on reste sur la même catégorie, sans remonter en haut
            val idx = built.indexOfFirst { it.id == prevId }.takeIf { it >= 0 } ?: 1
            selectCategory(idx, clearSearch = false, keepScroll = true)
        }
    }

    private fun scheduleSelect(pos: Int) {
        pendingSelect?.let { handler.removeCallbacks(it) }
        if (pos == catAdapter.selected && etSearch.text.isEmpty()) return
        val r = Runnable { selectCategory(pos) }
        pendingSelect = r
        handler.postDelayed(r, 300)
    }

    private fun selectCategory(pos: Int, clearSearch: Boolean = true, keepScroll: Boolean = false) {
        val c = cats.getOrNull(pos) ?: return
        catIdx = pos
        catAdapter.setSelected(pos)
        if (clearSearch && etSearch.text.isNotEmpty()) {
            ignoreSearch = true
            etSearch.setText("")
            ignoreSearch = false
        }
        val list = when (c.id) {
            FAV -> {
                val favs = Prefs.favs()
                pool.filter { favs.contains(it.favKey) }
            }
            ALL -> pool
            else -> allItems.filter { it.categoryId == c.id }
        }
        val empty = if (c.id == FAV) {
            "Aucun favori pour le moment.\nAppui long sur une chaîne, un film ou une série pour l'ajouter."
        } else "Cette catégorie est vide."
        showItems(list, c.name, empty, keepScroll)
        if (!sidePanel && !keepScroll) rvCats.smoothScrollToPosition(pos)
    }

    private fun runSearch(raw: String) {
        val q = fold(raw.trim())
        if (q.length < 2) {
            if (catIdx >= 0) selectCategory(catIdx, clearSearch = false)
            return
        }
        val list = pool.filter { it.searchKey.contains(q) }
        catAdapter.setSelected(-1)
        showItems(list, "Recherche : ${raw.trim()}", "Aucun résultat")
    }

    private fun showItems(list: List<Item>, title: String, emptyMsg: String, keepScroll: Boolean = false) {
        itemAdapter.submit(list)
        if (!keepScroll) rvItems.scrollToPosition(0)
        tvHead.text = title
        tvCount.text = if (list.isEmpty()) "" else plural(list.size, units.first, units.second)
        if (list.isEmpty()) showMessage(emptyMsg, false) else tvEmpty.visibility = View.GONE
    }

    private fun showMessage(msg: String, retry: Boolean) {
        tvEmpty.text = msg
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.isFocusable = retry
        tvEmpty.isClickable = retry
        if (retry) tvEmpty.requestFocus()
    }

    private fun refreshFavCount() {
        val favs = Prefs.favs()
        val n = pool.count { favs.contains(it.favKey) }
        catAdapter.item(0)?.let { if (it.count != n) catAdapter.update(0, it.copy(count = n)) }
        if (catIdx == 0 && etSearch.text.isEmpty()) {
            val list = pool.filter { favs.contains(it.favKey) }
            if (list.size != itemAdapter.itemCount) showItems(list, cats[0].name, "Aucun favori pour le moment.")
        }
        cats = cats.mapIndexed { i, c -> if (i == 0) c.copy(count = n) else c }
    }

    private fun focusFirstItem() {
        if (itemAdapter.itemCount == 0) return
        rvItems.post {
            val lm = rvItems.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            rvItems.findViewHolderForAdapterPosition(first)?.itemView?.requestFocus()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun openItem(pos: Int, item: Item) {
        when (kind) {
            Kind.LIVE -> {
                val play = itemAdapter.items.map { liveItem(it) }
                Nav.play(this, play, pos)
            }
            Kind.MOVIE -> startActivity(
                Intent(this, MovieActivity::class.java)
                    .putExtra("id", item.id)
                    .putExtra("name", item.name)
                    .putExtra("icon", item.icon)
                    .putExtra("ext", item.ext)
                    .putExtra("rating", item.rating),
            )
            Kind.SERIES -> startActivity(
                Intent(this, SeriesActivity::class.java)
                    .putExtra("id", item.id)
                    .putExtra("name", item.name)
                    .putExtra("cover", item.icon),
            )
        }
    }

    /** Guide TV des chaînes affichées (catégorie ou recherche en cours). */
    private fun openGuide() {
        if (itemAdapter.items.isEmpty()) return
        Guide.channels = itemAdapter.items
        Guide.title = tvHead.text.toString()
        val f = currentFocus
        Guide.startIndex = if (f != null && f.parent == rvItems) rvItems.getChildAdapterPosition(f).coerceAtLeast(0) else 0
        startActivity(Intent(this, GuideActivity::class.java))
    }

    private fun toggleFav(pos: Int, item: Item) {
        val added = Prefs.toggleFav(item.favKey)
        toast(if (added) "Ajouté aux favoris" else "Retiré des favoris")
        itemAdapter.refreshFavs(pos)
        refreshFavCount()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Touches Menu ou rouge de la télécommande : favori sur l'élément sélectionné
        if (kind == Kind.LIVE && (keyCode == KeyEvent.KEYCODE_GUIDE || keyCode == KeyEvent.KEYCODE_PROG_GREEN)) {
            openGuide(); return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_RED) {
            val focused = currentFocus
            if (focused != null && focused.parent == rvItems) {
                val p = rvItems.getChildAdapterPosition(focused)
                if (p != RecyclerView.NO_POSITION) {
                    toggleFav(p, itemAdapter.items[p]); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        Lists.onUpdated = null
        Epg.onLoaded = null
        handler.removeCallbacks(epgTicker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
