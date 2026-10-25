package com.koala.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Catégories ou saisons : ligne verticale (TV/tablette) ou puce horizontale (mobile). */
class CatAdapter(
    private val layout: Int,
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<CatAdapter.VH>() {

    private var data: List<Category> = emptyList()
    var selected = -1
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_name)
        val count: TextView? = v.findViewById(R.id.tv_count)
        val bar: View? = v.findViewById(R.id.v_bar)
    }

    fun submit(l: List<Category>) {
        data = l
        selected = -1
        notifyDataSetChanged()
    }

    fun update(pos: Int, c: Category) {
        if (pos !in data.indices) return
        data = data.toMutableList().also { it[pos] = c }
        notifyItemChanged(pos)
    }

    fun item(pos: Int): Category? = data.getOrNull(pos)

    fun setSelected(p: Int) {
        val old = selected
        selected = p
        if (old in data.indices) notifyItemChanged(old)
        if (p in data.indices) notifyItemChanged(p)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = data[position]
        val sel = position == selected
        h.name.text = c.name
        h.count?.text = fmtNum(c.count)
        h.count?.visibility = if (c.count < 0) View.GONE else View.VISIBLE
        h.bar?.visibility = if (sel) View.VISIBLE else View.INVISIBLE
        h.itemView.isActivated = sel
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnFocusChangeListener { _, f ->
            val p = h.bindingAdapterPosition
            if (f && p != RecyclerView.NO_POSITION) onFocus(p)
        }
    }
}

/** Chaînes (lignes) ou films / séries (affiches). */
class ItemAdapter(
    private val kind: Kind,
    private val onClick: (Int, Item) -> Unit,
    private val onLong: (Int, Item) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    var items: List<Item> = emptyList()
        private set
    private var favs: Set<String> = emptySet()
    private var positions: Map<String, Int> = emptyMap()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.iv)
        val name: TextView = v.findViewById(R.id.tv_name)
        val fav: View = v.findViewById(R.id.iv_fav)
        val epg: TextView? = v.findViewById(R.id.tv_epg)
        val epgBar: android.widget.ProgressBar? = v.findViewById(R.id.pb_epg)
        val tag: View? = v.findViewById(R.id.tv_tag)
        var id: String = ""
    }

    fun submit(l: List<Item>) {
        items = l
        favs = Prefs.favs()
        if (kind == Kind.LIVE) positions = HashMap<String, Int>(l.size).also { m -> l.forEachIndexed { i, it -> m[it.id] = i } }
        notifyDataSetChanged()
    }

    /** Le programme d'une chaîne est arrivé : on met à jour sa ligne. */
    fun epgLoaded(id: String) {
        positions[id]?.let { notifyItemChanged(it, EPG) }
    }

    /** Toutes les minutes : avance des barres de progression. */
    fun epgTick() {
        if (kind == Kind.LIVE && items.isNotEmpty()) notifyItemRangeChanged(0, items.size, EPG)
    }

    private fun bindEpg(h: VH, item: Item) {
        val epg = h.epg ?: return
        val p = Epg.now(item.id)
        if (p == null) {
            epg.visibility = View.GONE
            h.epgBar?.visibility = View.GONE
            return
        }
        epg.text = Epg.line(p)
        epg.visibility = View.VISIBLE
        h.epgBar?.progress = p.progress(System.currentTimeMillis() / 1000)
        h.epgBar?.visibility = View.VISIBLE
    }

    override fun onViewRecycled(h: VH) {
        if (kind == Kind.LIVE && h.id.isNotEmpty()) Epg.drop(h.id)
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == EPG }) bindEpg(h, items[position])
        else onBindViewHolder(h, position)
    }

    companion object {
        private const val EPG = "epg"
    }

    fun refreshFavs(position: Int) {
        favs = Prefs.favs()
        if (position in items.indices) notifyItemChanged(position)
    }

    fun reloadFavs() {
        favs = Prefs.favs()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (kind == Kind.LIVE) R.layout.item_channel else R.layout.item_poster
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.focusZoom(if (kind == Kind.LIVE) 1.015f else 1.06f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.id = item.id
        h.name.text = item.name
        h.fav.visibility = if (favs.contains(item.favKey)) View.VISIBLE else View.GONE
        if (kind == Kind.LIVE) bindEpg(h, item)
        h.tag?.visibility = if (item.archive > 0) View.VISIBLE else View.GONE
        if (item.icon.startsWith("http", true)) {
            h.img.load(item.icon) { crossfade(150) }
        } else {
            h.img.dispose()
            h.img.setImageDrawable(null)
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p, items[p])
            true
        }
    }
}

class EpisodeAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    var items: List<Episode> = emptyList()
        private set
    var seriesName = ""

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.tv_num)
        val name: TextView = v.findViewById(R.id.tv_name)
        val plot: TextView = v.findViewById(R.id.tv_plot)
        val resume: TextView = v.findViewById(R.id.tv_resume)
        val big: TextView = v.findViewById(R.id.tv_big)
        val img: ImageView = v.findViewById(R.id.iv)
        val bar: android.widget.ProgressBar = v.findViewById(R.id.pb)
    }

    fun submit(l: List<Episode>) {
        items = l
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        v.focusZoom(1.015f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        // « Épisode 3 · 52 min », vrai titre (sinon « Série · Épisode 3 »), miniature ou grand numéro
        h.num.text = "Épisode ${e.num}" + if (e.durMin > 0) " · ${e.durMin} min" else ""
        h.name.text = episodeTitle(e, seriesName).ifEmpty { "$seriesName · Épisode ${e.num}" }
        h.big.text = e.num.toString()
        h.big.visibility = View.VISIBLE
        h.img.dispose()
        h.img.setImageDrawable(null)
        if (e.img.startsWith("http", true)) h.img.load(e.img) {
            crossfade(150)
            listener(onSuccess = { _, _ -> h.big.visibility = View.INVISIBLE })
        }
        h.plot.text = e.plot
        h.plot.visibility = if (e.plot.isEmpty()) View.GONE else View.VISIBLE
        val pos = Prefs.getPos("e${e.id}")
        val seen = pos <= 0 && Seen.has("e${e.id}")
        h.resume.visibility = if (pos > 0 || seen) View.VISIBLE else View.GONE
        h.resume.text = if (seen) "Vu" else "Reprendre à ${fmtTime(pos)}"
        val ctx = h.itemView.context
        h.resume.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, if (seen) R.color.muted else R.color.accent))
        h.resume.setCompoundDrawablesRelativeWithIntrinsicBounds(if (seen) R.drawable.ic_check else 0, 0, 0, 0)
        h.resume.compoundDrawablePadding = ctx.dp(4)
        h.name.alpha = if (seen) 0.6f else 1f
        h.img.alpha = if (seen) 0.5f else 1f
        h.bar.visibility = if (pos > 0 && e.durMin > 0) View.VISIBLE else View.GONE
        if (pos > 0 && e.durMin > 0) h.bar.progress = (pos * 1000 / (e.durMin * 60_000L)).toInt().coerceIn(0, 1000)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
    }
}

/** Cartes de la rangée « Reprendre ». */
class ResumeAdapter(
    private val onClick: (History.Entry) -> Unit,
    private val onLong: (History.Entry) -> Unit,
) : RecyclerView.Adapter<ResumeAdapter.VH>() {

    var items: List<History.Entry> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.iv)
        val name: TextView = v.findViewById(R.id.tv_name)
        val bar: android.widget.ProgressBar = v.findViewById(R.id.pb)
        val left: TextView = v.findViewById(R.id.tv_left)
    }

    fun submit(l: List<History.Entry>) {
        items = l
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_resume, parent, false)
        v.focusZoom(1.04f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.title
        h.bar.progress = e.progress
        val m = e.remainingMin
        h.left.text = when {
            m >= 60 -> "Reste ${m / 60} h ${String.format("%02d", m % 60)}"
            m > 0 -> "Reste $m min"
            else -> "Reprendre à ${fmtTime(e.pos)}"
        }
        if (e.icon.startsWith("http", true)) h.img.load(e.icon) { crossfade(150) } else {
            h.img.dispose(); h.img.setImageDrawable(null)
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items[p])
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(items[p])
            true
        }
    }
}

/** Rangée « Ajoutés récemment » de l'accueil : affiches. */
class RecentAdapter(private val widthPx: Int, private val onClick: (Item) -> Unit) : RecyclerView.Adapter<RecentAdapter.VH>() {
    var items: List<Item> = emptyList()
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.iv)
        val name: TextView = v.findViewById(R.id.tv_name)
    }

    fun submit(l: List<Item>) {
        items = l
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        v.layoutParams = RecyclerView.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        v.focusZoom(1.06f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.name.text = it.name
        if (it.icon.startsWith("http", true)) h.img.load(it.icon) { crossfade(150) } else h.img.setImageDrawable(null)
        h.itemView.setOnClickListener { _ -> onClick(it) }
    }
}
