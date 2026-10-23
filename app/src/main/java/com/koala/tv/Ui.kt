package com.koala.tv

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

/** Cadre au format affiche (2:3) : la hauteur suit la largeur. */
class RatioFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * 1.5f).toInt(), MeasureSpec.EXACTLY))
    }
}

/** « Koala » en blanc, « TV » en vert. */
fun Context.brandText(): CharSequence {
    val s = SpannableString("KoalaTV")
    s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent)), 5, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}

val Context.isWide: Boolean get() = resources.getBoolean(R.bool.wide)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private val MARKS = Regex("\\p{Mn}+")

/** Minuscules sans accents, pour une recherche tolérante. */
fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD), "")

fun fmtNum(n: Int): String = String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ')

fun plural(n: Int, one: String, many: String): String = "${fmtNum(n)} ${if (n > 1) many else one}"

private val EP_CODE = Regex("\\b[sS]\\d{1,2}\\s*[eE]\\d{1,3}\\b")
private val EDGES = Regex("^[\\s\\-–—:|.·]+|[\\s\\-–—:|.·]+$")

/** Retire le nom de la série et le code S01E02 du titre d'un épisode. */
fun cleanEpisodeTitle(title: String, series: String): String {
    var t = title.trim()
    if (series.isNotEmpty() && fold(t).startsWith(fold(series))) t = t.substring(series.length.coerceAtMost(t.length))
    t = EP_CODE.replace(t, "")
    return EDGES.replace(t, "")
}

/** Appareil tactile (téléphone, tablette), par opposition à une TV pilotée à la télécommande. */
val Context.isTouch: Boolean
    get() = !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

/** Affiche le bouton retour de l'écran (s'il y en a un) sur téléphone et tablette. */
fun androidx.activity.ComponentActivity.setupBack(action: () -> Unit = { onBackPressedDispatcher.onBackPressed() }) {
    val b = findViewById<android.view.View?>(R.id.btn_back) ?: return
    if (!isTouch) return
    b.visibility = android.view.View.VISIBLE
    b.setOnClickListener { action() }
}

/**
 * Tuile de l'accueil : l'icône occupe toute la place disponible en gardant ses proportions,
 * avec un liseré qui épouse ses coins arrondis quand la tuile est sélectionnée.
 */
class TileImage @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    androidx.appcompat.widget.AppCompatImageView(context, attrs) {

    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val gap = context.dp(5)
    private val rect = android.graphics.RectF()

    init {
        scaleType = ScaleType.FIT_XY
        setPadding(gap, gap, gap, gap)
        isDuplicateParentStateEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = drawable
        if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); return
        }
        val ratio = d.intrinsicWidth.toFloat() / d.intrinsicHeight
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - 2 * gap
        val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(heightMeasureSpec) - 2 * gap
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) {
            h = maxH; w = (h * ratio).toInt()
        }
        setMeasuredDimension(w.coerceAtLeast(0) + 2 * gap, h.coerceAtLeast(0) + 2 * gap)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        imageAlpha = if (isPressed) 215 else 255
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!drawableState.contains(android.R.attr.state_focused)) return
        val s = ring.strokeWidth / 2
        rect.set(s, s, width - s, height - s)
        val r = (width - 2 * gap) * 0.223f + gap - s
        canvas.drawRoundRect(rect, r, r, ring)
    }
}

/**
 * Recherche vocale : affiche le micro si l'appareil sait transformer la voix en texte,
 * et place le texte dicté dans le champ. À appeler dans onCreate.
 */
fun androidx.activity.ComponentActivity.voiceInput(btn: android.view.View?, et: android.widget.EditText) {
    if (btn == null) return
    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Dis le nom d'une chaîne, d'un film ou d'une série")
    val ok = try {
        packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }
    if (!ok) return
    val launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            et.setText(text)
            et.setSelection(text.length)
        }
    }
    btn.visibility = android.view.View.VISIBLE
    btn.setOnClickListener {
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            toast("Recherche vocale indisponible")
        }
    }
}
