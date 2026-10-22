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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.iv)
        val name: TextView = v.findViewById(R.id.tv_name)
        val fav: View = v.findViewById(R.id.iv_fav)
    }

    fun submit(l: List<Item>) {
        items = l
        favs = Prefs.favs()
        notifyDataSetChanged()
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
        h.name.text = item.name
        h.fav.visibility = if (favs.contains(item.favKey)) View.VISIBLE else View.GONE
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
        h.num.text = e.num.toString()
        h.name.text = cleanEpisodeTitle(e.title, seriesName).ifEmpty { "Épisode ${e.num}" }
        h.plot.text = e.plot
        h.plot.visibility = if (e.plot.isEmpty()) View.GONE else View.VISIBLE
        val pos = Prefs.getPos("e${e.id}")
        h.resume.visibility = if (pos > 0) View.VISIBLE else View.GONE
        h.resume.text = "Reprendre à ${fmtTime(pos)}"
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
    }
}
