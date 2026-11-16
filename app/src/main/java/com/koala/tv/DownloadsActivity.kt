package com.koala.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Liste des téléchargements : lecture, suppression, nouvel essai, réglages (support, données mobiles). */
class DownloadsActivity : BaseActivity() {
    private val adapter = Adapter()
    private val refresh: () -> Unit = { paint() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        setupBack()
        val rv = findViewById<RecyclerView>(R.id.dl_list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<View>(R.id.dl_settings).setOnClickListener { settings(this) { paint() } }
    }

    override fun onStart() {
        super.onStart()
        Downloads.listen(refresh)
        Downloads.kick()
        paint()
    }

    override fun onStop() {
        Downloads.unlisten(refresh)
        super.onStop()
    }

    private fun paint() {
        val l = Downloads.all()
        adapter.items = l
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.dl_empty).visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        val d = Downloads.dir()
        val used = l.filter { it.file.exists() }.sumOf { if (it.state == Downloads.State.DONE) it.total else it.done + if (it.tailDone) it.total - it.tailFrom else 0 }
        val free = Downloads.freeBytes(d)
        findViewById<TextView>(R.id.dl_space).text = (if (free > 0) "Espace libre : ${fmtSize(free)}" else "Espace libre inconnu") +
            (if (used > 0) " · utilisé : ${fmtSize(used)}" else "") +
            (if (Downloads.isRemovable(d.parentFile ?: d)) " · support amovible" else "") +
            (if (!Prefs.downloadMobile) " · Wi-Fi uniquement" else "")
    }

    private fun open(d: Downloads.Dl) {
        when {
            d.state == Downloads.State.FAILED -> menu(d)
            d.state == Downloads.State.DONE || d.total > 0 -> Nav.play(this, listOf(d.toPlayItem()), 0, startAt = Prefs.getPos(d.key))
            else -> toast("Le téléchargement n'a pas encore commencé")
        }
    }

    private fun menu(d: Downloads.Dl) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (d.state == Downloads.State.DONE || d.total > 0) opts.add("Lire" to { Nav.play(this, listOf(d.toPlayItem()), 0, startAt = Prefs.getPos(d.key)) })
        if (d.state == Downloads.State.FAILED) opts.add("Réessayer" to { Downloads.retry(d.key) })
        opts.add((if (d.state == Downloads.State.DONE) "Supprimer" else "Annuler le téléchargement") to { Downloads.remove(d.key); paint() })
        AlertDialog.Builder(this).setTitle(d.title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Downloads.Dl> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.iv)
            val name: TextView = v.findViewById(R.id.tv_name)
            val state: TextView = v.findViewById(R.id.tv_state)
            val bar: ProgressBar = v.findViewById(R.id.pb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            v.focusZoom(1.015f)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val d = items[position]
            h.name.text = d.title
            h.state.text = stateText(d)
            h.bar.progress = d.percent
            h.bar.visibility = if (d.state == Downloads.State.DONE) View.GONE else View.VISIBLE
            if (d.icon.startsWith("http", true)) h.img.load(d.icon) { crossfade(120) } else h.img.setImageDrawable(null)
            h.itemView.setOnClickListener { open(d) }
            h.itemView.setOnLongClickListener { menu(d); true }
        }
    }

    companion object {
        fun stateText(d: Downloads.Dl): String = when (d.state) {
            Downloads.State.DONE -> "Téléchargé · ${fmtSize(d.total)}"
            Downloads.State.RUNNING -> "Téléchargement · ${d.percent} %" + (if (d.total > 0) " de ${fmtSize(d.total)}" else "") +
                d.pace().let { if (it.isEmpty()) "" else " · $it" }
            Downloads.State.FAILED -> "Échec : ${d.error}"
            Downloads.State.QUEUED -> when {
                Downloads.heldByPlayback -> "En pause pendant la lecture"
                d.error.isNotEmpty() -> d.error
                d.total > 0 -> "En attente · ${d.percent} %"
                else -> "En attente"
            }
        }

        /** Réglages : support de stockage, données mobiles. */
        fun settings(a: android.app.Activity, done: () -> Unit) {
            AlertDialog.Builder(a).setTitle("Téléchargements")
                .setItems(
                    arrayOf(
                        "Emplacement : " + Downloads.dir().let { volumeName(it.parentFile ?: it) },
                        "Données mobiles : " + if (Prefs.downloadMobile) "autorisées" else "non (Wi-Fi uniquement)",
                    ),
                ) { _, w ->
                    if (w == 0) chooseVolume(a, done) else {
                        Prefs.downloadMobile = !Prefs.downloadMobile
                        Downloads.kick()
                        done()
                        settings(a, done)
                    }
                }.show()
        }

        private fun volumeName(f: java.io.File) =
            (if (Downloads.isRemovable(f)) "support amovible" else "mémoire interne") + " (${fmtSize(Downloads.freeBytes(f))} libres)"

        private fun chooseVolume(a: android.app.Activity, done: () -> Unit) {
            val vols = Downloads.volumes()
            AlertDialog.Builder(a).setTitle("Emplacement des nouveaux téléchargements")
                .setItems(vols.map { volumeName(it) }.toTypedArray()) { _, w ->
                    Prefs.downloadDir = vols[w].path
                    done()
                }.show()
        }

        /** Demande d'autorisation des notifications (Android 13+), pour voir la progression. */
        fun askNotifications(a: android.app.Activity) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
