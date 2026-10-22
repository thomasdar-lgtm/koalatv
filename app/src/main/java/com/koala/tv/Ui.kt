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
