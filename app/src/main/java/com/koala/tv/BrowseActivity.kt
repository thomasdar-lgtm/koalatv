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

        val wide = isWide
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
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("cat", catIdx)
    }

    override fun onResume() {
        super.onResume()
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
                val c = Xtream.categories(kind)
                val list = Xtream.items(kind)
                allItems = list
                val counts = HashMap<String, Int>()
                for (it in list) counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1
                val favs = Prefs.favs()
                val favN = list.count { favs.contains(it.favKey) }
                val built = ArrayList<Category>()
                built.add(Category(FAV, "Favoris", favN))
                built.add(Category(ALL, "Tout", list.size))
                for (cat in c) {
                    val n = counts[cat.id] ?: 0
                    if (n > 0) built.add(Category(cat.id, cat.name, n))
                }
                cats = built
                catAdapter.submit(built)
                val start = when {
                    restoreCat in built.indices -> restoreCat
                    favN > 0 -> 0
                    built.size > 2 -> 2
                    else -> 1
                }
                selectCategory(start)
                rvCats.scrollToPosition(start)
                if (isWide) rvCats.post { rvCats.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }
            } catch (e: Exception) {
                showMessage("Impossible de charger la liste.\n${e.message ?: ""}\n\nToucher pour réessayer", true)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun scheduleSelect(pos: Int) {
        pendingSelect?.let { handler.removeCallbacks(it) }
        if (pos == catAdapter.selected && etSearch.text.isEmpty()) return
        val r = Runnable { selectCategory(pos) }
        pendingSelect = r
        handler.postDelayed(r, 300)
    }

    private fun selectCategory(pos: Int) {
        val c = cats.getOrNull(pos) ?: return
        catIdx = pos
        catAdapter.setSelected(pos)
        if (etSearch.text.isNotEmpty()) {
            ignoreSearch = true
            etSearch.setText("")
            ignoreSearch = false
        }
        val list = when (c.id) {
            FAV -> {
                val favs = Prefs.favs()
                allItems.filter { favs.contains(it.favKey) }
            }
            ALL -> allItems
            else -> allItems.filter { it.categoryId == c.id }
        }
        val empty = if (c.id == FAV) {
            "Aucun favori pour le moment.\nAppui long sur une chaîne, un film ou une série pour l'ajouter."
        } else "Cette catégorie est vide."
        showItems(list, c.name, empty)
        if (!isWide) rvCats.smoothScrollToPosition(pos)
    }

    private fun runSearch(raw: String) {
        val q = fold(raw.trim())
        if (q.length < 2) {
            if (catIdx >= 0) selectCategory(catIdx)
            return
        }
        val list = allItems.filter { fold(it.name).contains(q) }
        catAdapter.setSelected(-1)
        showItems(list, "Recherche : ${raw.trim()}", "Aucun résultat")
    }

    private fun showItems(list: List<Item>, title: String, emptyMsg: String) {
        itemAdapter.submit(list)
        rvItems.scrollToPosition(0)
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
        val n = allItems.count { favs.contains(it.favKey) }
        catAdapter.item(0)?.let { if (it.count != n) catAdapter.update(0, it.copy(count = n)) }
        if (catIdx == 0 && etSearch.text.isEmpty()) {
            val list = allItems.filter { favs.contains(it.favKey) }
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
                val play = itemAdapter.items.map { PlayItem(it.name, Xtream.liveUrl(it.id), true, null, it.id) }
                Nav.play(this, play, pos)
            }
            Kind.MOVIE -> Nav.play(
                this,
                listOf(PlayItem(item.name, Xtream.movieUrl(item.id, item.ext), false, "m${item.id}", item.id)),
                0,
            )
            Kind.SERIES -> startActivity(
                Intent(this, SeriesActivity::class.java)
                    .putExtra("id", item.id)
                    .putExtra("name", item.name)
                    .putExtra("cover", item.icon),
            )
        }
    }

    private fun toggleFav(pos: Int, item: Item) {
        val added = Prefs.toggleFav(item.favKey)
        toast(if (added) "Ajouté aux favoris" else "Retiré des favoris")
        itemAdapter.refreshFavs(pos)
        refreshFavCount()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Touches Menu ou rouge de la télécommande : favori sur l'élément sélectionné
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
