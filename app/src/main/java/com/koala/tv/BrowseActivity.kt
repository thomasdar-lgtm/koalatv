package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
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
    private lateinit var etSearch: EditText
    private lateinit var catAdapter: CatAdapter
    private lateinit var itemAdapter: ItemAdapter

    private var allItems: List<Item> = emptyList()
    private var cats: List<Category> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelect: Runnable? = null
    private var pendingSearch: Runnable? = null
    private var ignoreSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = try {
            Kind.valueOf(intent.getStringExtra("kind") ?: "LIVE")
        } catch (e: Exception) {
            Kind.LIVE
        }
        setContentView(R.layout.activity_browse)

        rvCats = findViewById(R.id.rv_cats)
        rvItems = findViewById(R.id.rv_items)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tv_empty)
        etSearch = findViewById(R.id.et_search)
        findViewById<TextView>(R.id.tv_title).text = when (kind) {
            Kind.LIVE -> "📺 TV en direct"
            Kind.MOVIE -> "🎬 Films"
            Kind.SERIES -> "🍿 Séries"
        }

        catAdapter = CatAdapter(
            onFocus = { pos -> scheduleSelect(pos) },
            onClick = { pos ->
                pendingSelect?.let { handler.removeCallbacks(it) }
                if (pos != catAdapter.selected) selectCategory(pos)
                focusFirstItem()
            },
        )
        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        itemAdapter = ItemAdapter(kind, ::openItem, ::toggleFav)
        rvItems.layoutManager = if (kind == Kind.LIVE) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, spanCount())
        }
        rvItems.adapter = itemAdapter
        rvItems.itemAnimator = null
        rvItems.setHasFixedSize(true)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (ignoreSearch) return
                pendingSearch?.let { handler.removeCallbacks(it) }
                val r = Runnable { runSearch(s?.toString() ?: "") }
                pendingSearch = r
                handler.postDelayed(r, 400)
            }
        })
        etSearch.setOnEditorActionListener { _, _, _ ->
            runSearch(etSearch.text.toString())
            focusFirstItem()
            true
        }

        tvEmpty.setOnClickListener { if (allItems.isEmpty()) load() }
        load()
    }

    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density - 330
        return (widthDp / 150).toInt().coerceIn(2, 8)
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
                val built = ArrayList<Category>()
                built.add(Category(FAV, "★ Favoris"))
                built.add(Category(ALL, "Tout (${list.size})"))
                for (cat in c) {
                    val n = counts[cat.id] ?: 0
                    if (n > 0) built.add(Category(cat.id, "${cat.name} ($n)"))
                }
                cats = built
                catAdapter.submit(built)
                val favs = Prefs.favs()
                val start = if (list.any { favs.contains(it.favKey) }) 0 else if (built.size > 2) 2 else 1
                selectCategory(start)
                rvCats.scrollToPosition(start)
                rvCats.post {
                    rvCats.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus()
                }
            } catch (e: Exception) {
                tvEmpty.text = "Impossible de charger la liste.\n${e.message ?: ""}\n\nOK pour réessayer"
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.isFocusable = true
                tvEmpty.requestFocus()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun scheduleSelect(pos: Int) {
        pendingSelect?.let { handler.removeCallbacks(it) }
        if (pos == catAdapter.selected) return
        val r = Runnable { selectCategory(pos) }
        pendingSelect = r
        handler.postDelayed(r, 350)
    }

    private fun selectCategory(pos: Int) {
        if (pos !in cats.indices) return
        catAdapter.setSelected(pos)
        if (etSearch.text.isNotEmpty()) {
            ignoreSearch = true
            etSearch.setText("")
            ignoreSearch = false
        }
        val id = cats[pos].id
        val list = when (id) {
            FAV -> {
                val favs = Prefs.favs()
                allItems.filter { favs.contains(it.favKey) }
            }
            ALL -> allItems
            else -> allItems.filter { it.categoryId == id }
        }
        val empty = if (id == FAV) {
            "Aucun favori.\nAppui long sur OK sur un élément pour l'ajouter."
        } else "Catégorie vide"
        showItems(list, empty)
    }

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.length < 2) {
            if (catAdapter.selected >= 0) selectCategory(catAdapter.selected)
            return
        }
        val list = allItems.filter { it.name.contains(q, ignoreCase = true) }
        showItems(list, "Aucun résultat pour « $q »")
    }

    private fun showItems(list: List<Item>, emptyMsg: String) {
        itemAdapter.submit(list)
        rvItems.scrollToPosition(0)
        tvEmpty.isFocusable = false
        tvEmpty.text = emptyMsg
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun focusFirstItem() {
        if (itemAdapter.itemCount == 0) return
        rvItems.post {
            val lm = rvItems.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            rvItems.findViewHolderForAdapterPosition(first)?.itemView?.requestFocus()
        }
    }

    private fun openItem(pos: Int, item: Item) {
        when (kind) {
            Kind.LIVE -> {
                val play = itemAdapter.items.map {
                    PlayItem(it.name, Xtream.liveUrl(it.id), true, null, it.id)
                }
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
        toast(if (added) "★ Ajouté aux favoris" else "Retiré des favoris")
        itemAdapter.refreshFavs(pos)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Touche Menu de la télécommande = favori sur l'élément sélectionné
        if (keyCode == KeyEvent.KEYCODE_MENU) {
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
