package com.koala.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Choix des catégories visibles ou masquées (réglage partagé entre les appareils). */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var kind: Kind
    private var cats: List<Category> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)
        setupBack()
        kind = try {
            Kind.valueOf(intent.getStringExtra("kind") ?: "LIVE")
        } catch (e: Exception) {
            Kind.LIVE
        }
        findViewById<TextView>(R.id.tv_title).text = "Catégories · " + when (kind) {
            Kind.LIVE -> "TV en direct"
            Kind.MOVIE -> "Films"
            Kind.SERIES -> "Séries"
        }
        findViewById<TextView>(R.id.tv_hint).text =
            "Appuie sur une catégorie pour la masquer ou l'afficher."

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.rv_items).apply {
            layoutManager = LinearLayoutManager(this@CategoriesActivity)
            adapter = this@CategoriesActivity.adapter
            itemAnimator = null
        }
        lifecycleScope.launch {
            try {
                Sync.pullCats()
                val d = Lists.get(kind)
                val counts = HashMap<String, Int>()
                for (it in d.items) counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1
                cats = d.cats.filter { (counts[it.id] ?: 0) > 0 }
                adapter.notifyDataSetChanged()
                findViewById<RecyclerView>(R.id.rv_items).post {
                    findViewById<RecyclerView>(R.id.rv_items).findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            } catch (e: Exception) {
                toast("Impossible de charger les catégories")
            } finally {
                findViewById<View>(R.id.progress).visibility = View.GONE
            }
        }
    }

    private fun toggle(pos: Int) {
        val c = cats.getOrNull(pos) ?: return
        Hidden.set(kind, c.id, !Hidden.isHidden(kind, c.id))
        adapter.notifyItemChanged(pos)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_name)
            val state: TextView = v.findViewById(R.id.tv_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun getItemCount() = cats.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = cats[position]
            val hidden = Hidden.isHidden(kind, c.id)
            h.name.text = c.name
            h.name.alpha = if (hidden) 0.45f else 1f
            h.state.text = if (hidden) "Masquée" else "Visible"
            h.state.setTextColor(
                ContextCompat.getColor(
                    this@CategoriesActivity,
                    if (hidden) R.color.accent else R.color.faint,
                ),
            )
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) toggle(p)
            }
        }
    }
}
