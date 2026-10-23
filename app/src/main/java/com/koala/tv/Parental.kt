package com.koala.tv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.security.MessageDigest

/** Catégories masquées ou verrouillées, et code parental. */
object Parental {
    enum class State { VISIBLE, LOCKED, HIDDEN }

    private lateinit var sp: SharedPreferences
    private var states: MutableMap<String, String> = HashMap()

    /** Déverrouillé jusqu'à la fermeture de l'appli. */
    var unlocked = false

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("koalatv_parental", Context.MODE_PRIVATE)
        states = HashMap()
        for (e in sp.getStringSet("cats", emptySet()) ?: emptySet()) {
            val i = e.lastIndexOf('=')
            if (i > 0) states[e.substring(0, i)] = e.substring(i + 1)
        }
    }

    private fun key(kind: Kind, catId: String) = "${kind.name}:$catId"

    fun state(kind: Kind, catId: String): State = when (states[key(kind, catId)]) {
        "h" -> State.HIDDEN
        "l" -> State.LOCKED
        else -> State.VISIBLE
    }

    fun setState(kind: Kind, catId: String, s: State) {
        val k = key(kind, catId)
        when (s) {
            State.VISIBLE -> states.remove(k)
            State.LOCKED -> states[k] = "l"
            State.HIDDEN -> states[k] = "h"
        }
        sp.edit().putStringSet("cats", states.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    val hasCode: Boolean get() = sp.getString("code", "").orEmpty().isNotEmpty()

    private fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256").digest("koalatv:$code".toByteArray()).joinToString("") { "%02x".format(it) }

    fun setCode(code: String?) {
        sp.edit().putString("code", if (code.isNullOrEmpty()) "" else hash(code)).apply()
        unlocked = code.isNullOrEmpty()
    }

    fun check(code: String): Boolean = hasCode && hash(code) == sp.getString("code", "")

    /** Catégorie à ne pas montrer (masquée, ou verrouillée tant que le code n'a pas été saisi). */
    fun blocked(kind: Kind, catId: String): Boolean = when (state(kind, catId)) {
        State.HIDDEN -> true
        State.LOCKED -> hasCode && !unlocked
        State.VISIBLE -> false
    }

    fun locked(kind: Kind, catId: String): Boolean = hasCode && state(kind, catId) == State.LOCKED

    // ---------- Saisie du code ----------

    private fun codeField(a: Activity): Pair<FrameLayout, EditText> {
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER
            textSize = 28f
            letterSpacing = 0.5f
            setTextColor(ContextCompat.getColor(a, R.color.text))
            background = ContextCompat.getDrawable(a, R.drawable.field_bg)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
        }
        val box = FrameLayout(a).apply {
            setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
            addView(et, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        return box to et
    }

    private fun prompt(a: Activity, title: String, msg: String?, onCode: (String) -> Unit) {
        val (box, et) = codeField(a)
        val d = AlertDialog.Builder(a)
            .setTitle(title)
            .apply { if (msg != null) setMessage(msg) }
            .setView(box)
            .setPositiveButton("Valider") { _, _ -> onCode(et.text.toString()) }
            .setNegativeButton("Annuler", null)
            .create()
        et.setOnEditorActionListener { _, _, _ ->
            d.dismiss(); onCode(et.text.toString()); true
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
        et.requestFocus()
    }

    /** Demande le code ; `onOk` si correct (ou s'il n'y a pas de code). */
    fun ask(a: Activity, onOk: () -> Unit) {
        if (!hasCode || unlocked) {
            onOk(); return
        }
        prompt(a, "Code parental", null) { code ->
            if (check(code)) {
                unlocked = true
                onOk()
            } else {
                a.toast("Code incorrect")
            }
        }
    }

    /** Création d'un nouveau code (4 chiffres, saisi deux fois). */
    fun create(a: Activity, onDone: () -> Unit) {
        prompt(a, "Nouveau code parental", "4 chiffres") { first ->
            if (first.length != 4) {
                a.toast("Le code doit faire 4 chiffres"); return@prompt
            }
            prompt(a, "Confirme le code", null) { second ->
                if (second != first) {
                    a.toast("Les deux codes sont différents"); return@prompt
                }
                setCode(first)
                unlocked = true
                a.toast("Code parental enregistré")
                onDone()
            }
        }
    }

    /** Réglages du code : créer, ou changer / supprimer après vérification. */
    fun settings(a: Activity) {
        if (!hasCode) {
            create(a) {}
            return
        }
        prompt(a, "Code parental actuel", null) { code ->
            if (!check(code)) {
                a.toast("Code incorrect"); return@prompt
            }
            unlocked = true
            AlertDialog.Builder(a)
                .setTitle("Code parental")
                .setItems(arrayOf("Changer le code", "Supprimer le code")) { _, which ->
                    if (which == 0) create(a) {} else {
                        setCode(null)
                        a.toast("Code parental supprimé")
                    }
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }
}
