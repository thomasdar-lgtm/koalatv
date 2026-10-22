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

class SeriesActivity : AppCompatActivity() {

    private var seriesId = ""
    private var seriesName = ""
    private var episodes: List<Episode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private lateinit var seasonAdapter: CatAdapter
    private lateinit var epAdapter: EpisodeAdapter
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        seriesId = intent.getStringExtra("id") ?: ""
        seriesName = intent.getStringExtra("name") ?: ""
        val cover = intent.getStringExtra("cover") ?: ""

        findViewById<TextView>(R.id.tv_title).text = seriesName
        if (cover.startsWith("http", true)) findViewById<ImageView>(R.id.iv_cover).load(cover)

        rvSeasons = findViewById(R.id.rv_seasons)
        rvEpisodes = findViewById(R.id.rv_episodes)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tv_empty)

        seasonAdapter = CatAdapter(
            onFocus = { pos -> if (pos != seasonAdapter.selected) showSeason(pos) },
            onClick = { pos ->
                if (pos != seasonAdapter.selected) showSeason(pos)
                rvEpisodes.post { rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            },
        )
        rvSeasons.layoutManager = LinearLayoutManager(this)
        rvSeasons.adapter = seasonAdapter
        rvSeasons.itemAnimator = null

        epAdapter = EpisodeAdapter { pos -> playEpisode(pos) }
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = epAdapter
        rvEpisodes.itemAnimator = null

        tvEmpty.setOnClickListener { if (episodes.isEmpty()) load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        epAdapter.notifyDataSetChanged()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                episodes = Xtream.seriesEpisodes(seriesId)
                seasons = episodes.map { it.season }.distinct().sorted()
                if (episodes.isEmpty()) {
                    tvEmpty.text = "Aucun épisode disponible"
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    seasonAdapter.submit(seasons.map { Category(it.toString(), if (it <= 0) "Épisodes" else "Saison $it") })
                    showSeason(0)
                    rvSeasons.post { rvSeasons.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            } catch (e: Exception) {
                tvEmpty.text = "Impossible de charger la série.\n${e.message ?: ""}\n\nOK pour réessayer"
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.isFocusable = true
                tvEmpty.requestFocus()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun showSeason(pos: Int) {
        if (pos !in seasons.indices) return
        seasonAdapter.setSelected(pos)
        val s = seasons[pos]
        epAdapter.submit(episodes.filter { it.season == s })
        rvEpisodes.scrollToPosition(0)
    }

    private fun playEpisode(pos: Int) {
        val list = epAdapter.items.map { e ->
            val label = if (e.season > 0) "S${e.season}E${e.num}" else "E${e.num}"
            PlayItem("$seriesName · $label", Xtream.episodeUrl(e.id, e.ext), false, "e${e.id}", e.id)
        }
        Nav.play(this, list, pos)
    }
}
