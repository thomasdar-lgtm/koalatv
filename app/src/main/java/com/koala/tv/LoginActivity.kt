package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.loggedIn) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_login)
        findViewById<TextView>(R.id.brand).text = brandText()

        // Formulaire : 520 dp au maximum, pleine largeur (avec marges) sur téléphone
        val form = findViewById<View>(R.id.form)
        val maxW = dp(520)
        val avail = resources.displayMetrics.widthPixels - dp(40)
        form.layoutParams = form.layoutParams.apply { width = minOf(maxW, avail) }

        val etServer = findViewById<EditText>(R.id.et_server)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btn = findViewById<TextView>(R.id.btn_login)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.tv_status)

        etServer.setText(Prefs.server)
        etUser.setText(Prefs.user)

        fun submit() {
            var server = etServer.text.toString().trim()
            var user = etUser.text.toString().trim()
            var pass = etPass.text.toString().trim()
            Xtream.parseM3uLink(server)?.let { (s, u, p) ->
                server = s; user = u; pass = p
                etServer.setText(s); etUser.setText(u); etPass.setText(p)
            }
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                status.text = "Renseigne les trois champs"
                return
            }
            val norm = Xtream.normalizeServer(server)
            btn.isEnabled = false
            status.text = ""
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val acc = Xtream.login(norm, user, pass)
                    if (norm != Prefs.server || user != Prefs.user) Lists.clear()
                    Prefs.server = norm
                    Prefs.user = user
                    Prefs.pass = pass
                    Prefs.expiry = acc.expiry
                    Prefs.tzOffset = acc.tzOffset
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    status.text = e.message ?: "Connexion impossible"
                    btn.isEnabled = true
                    progress.visibility = View.GONE
                    btn.requestFocus()
                }
            }
        }

        // Connexion rapide : on attend des identifiants envoyés par un autre appareil vers le code affiché
        val tvCode = findViewById<TextView>(R.id.tv_code)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var code = Pair.newCode()
                var since = System.currentTimeMillis()
                tvCode.text = Pair.display(code)
                while (true) {
                    delay(3000)
                    if (System.currentTimeMillis() - since > 600_000) {
                        code = Pair.newCode(); since = System.currentTimeMillis(); tvCode.text = Pair.display(code)
                    }
                    val c = Pair.receive(code) ?: continue
                    etServer.setText(c.server)
                    etUser.setText(c.user)
                    etPass.setText(c.pass)
                    if (btn.isEnabled) submit()
                }
            }
        }

        btn.setOnClickListener { submit() }
        etPass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else false
        }
        if (Prefs.server.isNotEmpty() && Prefs.user.isNotEmpty()) etPass.requestFocus() else etServer.requestFocus()
    }
}
