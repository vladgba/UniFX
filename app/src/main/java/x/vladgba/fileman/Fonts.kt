package x.vladgba.fileman

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import kotlin.math.abs

/**
 * A truly fixed-width typeface. Some vendors map Typeface.MONOSPACE to a font where glyphs like ` ' . i l
 * are narrower than m W @, so code columns drift. We test the default first, then the monospace fonts most
 * Android builds still ship in /system/fonts, and pick the first whose glyph widths really are equal.
 */
object Fonts {
    private val CANDIDATES = listOf(
        "DroidSansMono.ttf",
        "RobotoMono-Regular.ttf",
        "RobotoMono-VariableFont_wght.ttf",
        "NotoSansMono-Regular.ttf",
        "NotoSansMono-VF.ttf",
        "SourceCodePro-Regular.ttf",
        "CutiveMono.ttf",
    )

    /** Glyphs that commonly come out narrow or wide in fake monospace fonts. */
    private const val PROBE = "`'.,:;!|iIlj1fmMWw@_-~^\"()[]{}#%&*+=<>?/\\$"

    private var cached: Typeface? = null

    @Synchronized
    fun mono(): Typeface {
        cached?.let { return it }
        val pick = sequence {
            yield(Typeface.MONOSPACE)
            yield(Typeface.create("monospace", Typeface.NORMAL))
            for (name in CANDIDATES) {
                val f = File("/system/fonts", name)
                val tf = if (f.canRead()) try { Typeface.createFromFile(f) } catch (e: RuntimeException) { null } else null
                if (tf != null) yield(tf)
            }
        }.firstOrNull { isFixedWidth(it) } ?: Typeface.MONOSPACE
        cached = pick
        return pick
    }

    fun monoBold(): Typeface = Typeface.create(mono(), Typeface.BOLD)

    private fun isFixedWidth(t: Typeface): Boolean {
        val p = Paint().apply { typeface = t; textSize = 48f }
        val w = p.measureText("0")
        if (w <= 0f) return false
        return PROBE.all { abs(p.measureText(it.toString()) - w) < 0.5f }
    }
}
