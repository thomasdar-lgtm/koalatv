package com.koala.tv

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class SeriesActivity : BaseActivity() {

    private var seriesId = ""
    private var seriesName = ""
    private var cover = ""
    private var episodes: List<Episode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var seasonIdx = 0
    private lateinit var seasonAdapter: CatAdapter
    private lateinit var epAdapter: EpisodeAdapter
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvHead: TextView
    private lateinit var tvCount: TextView

    /** Catégories en colonne à gauche (grand écran) ou en puces au-dessus (téléphone). */
    private val sidePanel: Boolean get() = rvSeasons.tag != "chips"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        setupBack()
        seriesId = intent.getStringExtra("id") ?: ""
        seriesName = intent.getStringExtra("name") ?: ""
        cover = intent.getStringExtra("cover") ?: ""
        seasonIdx = savedInstanceState?.getInt("season", -1) ?: -1

        findViewById<TextView>(R.id.tv_title).text = seriesName
        if (cover.startsWith("http", true)) findViewById<ImageView>(R.id.iv_cover).load(cover) { crossfade(150) }

        rvSeasons = findViewById(R.id.rv_cats)
        rvEpisodes = findViewById(R.id.rv_items)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tv_empty)
        tvHead = findViewById(R.id.tv_head)
        tvCount = findViewById(R.id.tv_count)

        val wide = sidePanel
        seasonAdapter = CatAdapter(
            if (wide) R.layout.item_category else R.layout.item_chip,
            onFocus = { pos -> if (pos != seasonAdapter.selected) showSeason(pos) },
            onClick = { pos ->
                if (pos != seasonAdapter.selected) showSeason(pos)
                if (wide) rvEpisodes.post { rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            },
        )
        rvSeasons.layoutManager = LinearLayoutManager(this, if (wide) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false)
        rvSeasons.adapter = seasonAdapter
        rvSeasons.itemAnimator = null

        epAdapter = EpisodeAdapter { pos -> playEpisode(pos) }
        epAdapter.seriesName = seriesName
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = epAdapter
        rvEpisodes.itemAnimator = null

        tvEmpty.setOnClickListener { if (episodes.isEmpty()) load() }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("season", seasonIdx)
    }

    override fun onResume() {
        super.onResume()
        // retour (du lecteur notamment) : on se replace sur l'épisode à voir, progression à jour
        if (resumedOnce && episodes.isNotEmpty()) focusNext() else epAdapter.notifyDataSetChanged()
        resumedOnce = true
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                episodes = Xtream.seriesEpisodes(seriesId)
                Sync.pullSeen()
                seasons = episodes.map { it.season }.distinct().sorted()
                if (episodes.isEmpty()) {
                    showMessage("Aucun épisode disponible.", false)
                } else {
                    seasonAdapter.submit(seasons.map { s ->
                        // colonne étroite du téléphone : « S1 », « S2 »… sans le nombre d'épisodes
                        if (rvSeasons.layoutParams.width !in 1..dp(150)) Category(s.toString(), if (s <= 0) "Épisodes" else "Saison $s", episodes.count { it.season == s })
                        else Category(s.toString(), if (s <= 0) "Ép." else "S$s", -1)
                    })
                    focusNext(restore = seasonIdx >= 0)
                }
            } catch (e: Exception) {
                showMessage("Impossible de charger la série.\n${e.message ?: ""}\n\nToucher pour réessayer", true)
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** Se place sur l'épisode en cours, sinon sur celui qui suit le dernier vu (`restore` : garder la saison affichée). */
    private fun focusNext(restore: Boolean = false) {
        if (episodes.isEmpty()) return
        val target = if (!restore) nextToWatch() else -1
        val start = if (target >= 0) seasons.indexOf(episodes[target].season).coerceAtLeast(0) else seasonIdx.coerceIn(0, seasons.size - 1)
        showSeason(start)
        rvSeasons.scrollToPosition(start)
        val epPos = if (target >= 0) epAdapter.items.indexOf(episodes[target]) else -1
        if (epPos > 0 || (epPos == 0 && target > 0) || (target >= 0 && started(episodes[target]))) {
            rvEpisodes.scrollToPosition(epPos)
            rvEpisodes.post { rvEpisodes.findViewHolderForAdapterPosition(epPos)?.itemView?.requestFocus() }
        } else if (sidePanel) {
            rvSeasons.post { rvSeasons.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }
        }
    }

    private var resumedOnce = false

    private fun started(e: Episode) = Prefs.getPos("e${e.id}") > 0 || Seen.has("e${e.id}")

    /** Index de l'épisode à proposer : le plus récemment commencé, sinon celui après le dernier vu. */
    private fun nextToWatch(): Int {
        val hist = History.all().associate { it.key to it.at }
        var best = -1
        var bestAt = 0L
        var lastSeen = -1
        episodes.forEachIndexed { i, e ->
            val k = "e${e.id}"
            if (Prefs.getPos(k) > 0) {
                val at = hist[k] ?: 1L
                if (at > bestAt) {
                    bestAt = at; best = i
                }
            }
            if (Seen.has(k)) lastSeen = i
        }
        return when {
            best >= 0 -> best
            lastSeen in 0 until episodes.size - 1 -> lastSeen + 1
            else -> 0
        }
    }

    private fun showMessage(msg: String, retry: Boolean) {
        tvEmpty.text = msg
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.isFocusable = retry
        tvEmpty.isClickable = retry
        if (retry) tvEmpty.requestFocus()
    }

    private fun showSeason(pos: Int) {
        val s = seasons.getOrNull(pos) ?: return
        seasonIdx = pos
        seasonAdapter.setSelected(pos)
        val list = episodes.filter { it.season == s }
        epAdapter.submit(list)
        rvEpisodes.scrollToPosition(0)
        tvHead.text = if (s <= 0) "Épisodes" else "Saison $s"
        tvCount.text = plural(list.size, "épisode", "épisodes")
        if (!sidePanel) rvSeasons.smoothScrollToPosition(pos)
    }

    private fun playEpisode(pos: Int) {
        Nav.play(this, episodePlayList(seriesId, seriesName, cover, epAdapter.items), pos)
    }
}
