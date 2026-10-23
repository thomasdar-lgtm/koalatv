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

/** Choix des catégories visibles, verrouillées (code parental) ou masquées. */
class CategoriesActivity : AppCompatActivity() {

    private lateinit var kind: Kind
    private var cats: List<Category> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_categories)
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
            "Chaque appui change l'état : visible, verrouillée (code parental), masquée."

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.rv_items).apply {
            layoutManager = LinearLayoutManager(this@CategoriesActivity)
            adapter = this@CategoriesActivity.adapter
            itemAnimator = null
        }
        lifecycleScope.launch {
            try {
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

    private fun cycle(pos: Int) {
        val c = cats.getOrNull(pos) ?: return
        val next = when (Parental.state(kind, c.id)) {
            Parental.State.VISIBLE -> Parental.State.LOCKED
            Parental.State.LOCKED -> Parental.State.HIDDEN
            Parental.State.HIDDEN -> Parental.State.VISIBLE
        }
        if (next == Parental.State.LOCKED && !Parental.hasCode) {
            // verrouiller demande un code : on le crée d'abord
            Parental.create(this) {
                Parental.setState(kind, c.id, next)
                adapter.notifyItemChanged(pos)
            }
            return
        }
        Parental.setState(kind, c.id, next)
        adapter.notifyItemChanged(pos)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tv_name)
            val state: TextView = v.findViewById(R.id.tv_count)
            val lock: View = v.findViewById(R.id.iv_lock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

        override fun getItemCount() = cats.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = cats[position]
            val s = Parental.state(kind, c.id)
            h.name.text = c.name
            h.name.alpha = if (s == Parental.State.HIDDEN) 0.45f else 1f
            h.lock.visibility = if (s == Parental.State.LOCKED) View.VISIBLE else View.GONE
            h.state.text = when (s) {
                Parental.State.VISIBLE -> "Visible"
                Parental.State.LOCKED -> "Verrouillée"
                Parental.State.HIDDEN -> "Masquée"
            }
            h.state.setTextColor(
                ContextCompat.getColor(
                    this@CategoriesActivity,
                    if (s == Parental.State.VISIBLE) R.color.faint else R.color.accent,
                ),
            )
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) cycle(p)
            }
        }
    }
}
