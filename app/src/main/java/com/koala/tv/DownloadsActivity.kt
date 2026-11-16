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
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

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

    private var pcLoop: kotlinx.coroutines.Job? = null

    override fun onStart() {
        super.onStart()
        Downloads.listen(refresh)
        Downloads.kick()
        paint()
        // PC de la maison : état relu toutes les 3 s
        pcLoop = lifecycleScope.launch {
            var first = true
            while (true) {
                Relay.refresh(force = first)
                first = false
                paint()
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    override fun onStop() {
        pcLoop?.cancel()
        Downloads.unlisten(refresh)
        super.onStop()
    }

    private fun paint() {
        val l = Downloads.all()
        val rows = ArrayList<Any>()
        val pcItems = Relay.all()
        if (Relay.known) {
            rows.add(
                "Sur le PC · ${Relay.name}" + when {
                    Relay.base.isEmpty() -> " · injoignable d'ici (PC éteint, ou pas sur ce réseau)"
                    Relay.pauseWhy.isNotEmpty() -> " · " + Relay.pauseWhy.replaceFirstChar { it.lowercase() }
                    else -> ""
                },
            )
            rows.addAll(pcItems)
            if (pcItems.isEmpty()) rows.add("Rien sur le PC pour le moment")
            if (l.isNotEmpty()) rows.add("Sur cet appareil")
        }
        rows.addAll(l)
        adapter.items = rows
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.dl_empty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
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

    private fun openPc(e: Relay.Entry) {
        if (e.state == "echec") { menuPc(e); return }
        if (!e.playable) { toast("Le PC n'a pas encore commencé ce téléchargement"); return }
        if (Relay.base.isEmpty()) { toast("Le PC est injoignable : il doit être allumé, sur le même réseau que cet appareil"); return }
        Nav.play(this, listOf(e.toPlayItem()), 0, startAt = Prefs.getPos(e.key))
    }

    private fun menuPc(e: Relay.Entry) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (e.playable) opts.add("Lire" to { openPc(e) })
        if (e.state == "echec") opts.add("Réessayer" to { Relay.retry(e.key) })
        opts.add((if (e.state == "fini") "Supprimer du PC" else "Annuler") to { Relay.remove(e.key); paint() })
        AlertDialog.Builder(this).setTitle(e.title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
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

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        /** Titres de partie (String), téléchargements du PC (Relay.Entry) et de l'appareil (Downloads.Dl). */
        var items: List<Any> = emptyList()

        inner class Head(v: TextView) : RecyclerView.ViewHolder(v)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.iv)
            val name: TextView = v.findViewById(R.id.tv_name)
            val state: TextView = v.findViewById(R.id.tv_state)
            val bar: ProgressBar = v.findViewById(R.id.pb)
        }

        override fun getItemViewType(position: Int) = if (items[position] is String) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val t = TextView(parent.context).apply {
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.muted))
                    textSize = 14f
                    setPadding(0, context.dp(14), 0, context.dp(8))
                }
                return Head(t)
            }
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            v.focusZoom(1.015f)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val x = items[position]
            if (holder is Head) { (holder.itemView as TextView).text = x as String; return }
            val h = holder as VH
            if (x is Relay.Entry) {
                h.name.text = x.title
                h.state.text = Relay.stateText(x)
                h.bar.progress = x.pct
                h.bar.visibility = if (x.state == "fini" || x.state == "echec") View.GONE else View.VISIBLE
                if (x.icon.startsWith("http", true)) h.img.load(x.icon) { crossfade(120) } else h.img.setImageDrawable(null)
                h.itemView.setOnClickListener { openPc(x) }
                h.itemView.setOnLongClickListener { menuPc(x); true }
                return
            }
            val d = x as Downloads.Dl
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

        /**
         * Où télécharger : sur cet appareil, ou sur le PC de la maison (s'il est relié au compte).
         */
        fun chooseWhere(a: android.app.Activity, items: List<PlayItem>, done: () -> Unit) {
            fun here() {
                val n = Downloads.add(items)
                askNotifications(a)
                a.toast(if (n == 0) "Déjà dans les téléchargements" else if (n == 1) "Ajouté aux téléchargements" else "$n éléments ajoutés aux téléchargements")
                done()
            }
            fun pc() {
                val n = Relay.send(items)
                a.toast(if (n == 0) "Déjà sur le PC" else if (n == 1) "Envoyé au PC" else "$n éléments envoyés au PC")
                done()
            }
            if (!Relay.known) { here(); return }
            AlertDialog.Builder(a).setTitle("Télécharger")
                .setItems(arrayOf("Sur le PC (${Relay.name})", "Sur cet appareil")) { _, w -> if (w == 0) pc() else here() }
                .show()
        }

        /** Demande d'autorisation des notifications (Android 13+), pour voir la progression. */
        fun askNotifications(a: android.app.Activity) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
