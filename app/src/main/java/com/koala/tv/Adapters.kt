package com.koala.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class CatAdapter(
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<CatAdapter.VH>() {

    private var data: List<Category> = emptyList()
    var selected = -1
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tv_name)
    }

    fun submit(l: List<Category>) {
        data = l
        notifyDataSetChanged()
    }

    fun setSelected(p: Int) {
        val old = selected
        selected = p
        if (old in data.indices) notifyItemChanged(old)
        if (p in data.indices) notifyItemChanged(p)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.name.text = data[position].name
        h.itemView.isActivated = position == selected
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
        val fav: View = v.findViewById(R.id.tv_fav)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (kind == Kind.LIVE) R.layout.item_channel else R.layout.item_poster
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.focusZoom(if (kind == Kind.LIVE) 1.02f else 1.07f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.name.text = item.name
        h.fav.visibility = if (favs.contains(item.favKey)) View.VISIBLE else View.GONE
        if (item.icon.startsWith("http", true)) {
            h.img.load(item.icon) {
                crossfade(false)
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
        } else {
            h.img.dispose()
            h.img.setImageResource(R.drawable.ic_placeholder)
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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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
        v.focusZoom(1.02f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = "Épisode ${e.num}" + if (e.title.isNotEmpty()) " · ${e.title}" else ""
        h.plot.text = e.plot
        h.plot.visibility = if (e.plot.isEmpty()) View.GONE else View.VISIBLE
        val pos = Prefs.getPos("e${e.id}")
        h.resume.visibility = if (pos > 0) View.VISIBLE else View.GONE
        h.resume.text = "▶ Reprendre à ${fmtTime(pos)}"
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
    }
}
