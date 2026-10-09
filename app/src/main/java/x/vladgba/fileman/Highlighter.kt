package x.vladgba.fileman

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.widget.EditText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Asynchronous, line-by-line highlighter for an EditText.
 *
 * - Tokenizing runs on a worker thread over a snapshot of just the needed lines; the UI thread only swaps spans.
 * - Only the visible lines plus about one screen above and below are colored.
 * - For every line already scanned it remembers (line start offset, state at line start). An edit only
 *   invalidates lines after the edited one, so after typing, scanning resumes from the edited line instead
 *   of the top of the file, and block comments / multi-line strings stay correct across lines.
 */
class Highlighter(private val edit: EditText, private val lang: Lang, private val colors: IntArray) : TextWatcher {
    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val kick = Runnable { request() }

    // Known lines: starts[i] = offset of line i, states[i] = tokenizer state at its start. Valid for i < known.
    private var starts = IntArray(1024)
    private var states = IntArray(1024)
    private var known = 1 // line 0 starts at 0 in state 0

    private var generation = 0
    private var running = false
    private var pending = false
    private var dead = false

    fun schedule(delayMs: Long) {
        ui.removeCallbacks(kick)
        ui.postDelayed(kick, delayMs)
    }

    fun shutdown() {
        dead = true
        ui.removeCallbacks(kick)
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- TextWatcher

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        generation++
        // Lines starting at or before the edit keep their start and entry state; everything after is stale.
        known = lastKnownAtOrBefore(start) + 1
    }

    override fun afterTextChanged(s: Editable) = schedule(150)

    private fun lastKnownAtOrBefore(off: Int): Int {
        var lo = 0
        var hi = known - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    // ---------------------------------------------------------------- work

    private fun request() {
        if (dead) return
        if (running) { pending = true; return }
        val l = edit.layout ?: return
        val t = edit.text
        if (t.isEmpty()) return
        val top = l.getLineStart(l.getLineForVertical(max(0, edit.scrollY - edit.totalPaddingTop)))
        val bottom = l.getLineEnd(l.getLineForVertical(edit.scrollY + edit.height))
        val screen = max(bottom - top, 2000)
        // Color window = visible text +- one screen, snapped to whole lines.
        var from = max(0, top - screen)
        if (from > 0) from = TextUtils.lastIndexOf(t, '\n', from - 1) + 1
        var to = min(t.length, bottom + screen)
        if (to < t.length) to = TextUtils.indexOf(t, '\n', to).let { if (it < 0) t.length else it }

        // Resume from the last line whose state is known at or before the window.
        val k = lastKnownAtOrBefore(from)
        val base = starts[k]
        val entry = states[k]
        val snapshot = t.subSequence(base, to).toString()
        val endsAtNewline = to < t.length
        val gen = generation
        running = true

        worker.execute {
            val spans = Ints()
            val lineStarts = Ints()
            val lineStates = Ints()
            var state = entry
            var i = 0
            val n = snapshot.length
            while (true) {
                if (Thread.currentThread().isInterrupted) return@execute
                val nl = snapshot.indexOf('\n', i)
                val end = if (nl < 0) n else nl
                val color = base + i >= from
                val mark = spans.n
                state = Syntax.line(lang, snapshot.substring(i, end), state, if (color) spans else null)
                if (color) spans.shift(mark, 3, base + i)
                if (nl < 0) break
                i = nl + 1
                lineStarts.add(base + i)
                lineStates.add(state)
            }
            if (endsAtNewline) { lineStarts.add(to + 1); lineStates.add(state) }
            ui.post { apply(gen, k, lineStarts, lineStates, spans, from, to) }
        }
    }

    private fun apply(gen: Int, k: Int, lineStarts: Ints, lineStates: Ints, spans: Ints, from: Int, to: Int) {
        running = false
        if (dead) return
        if (gen != generation) {
            schedule(0) // text changed while we worked; redo with fresh text
            return
        }
        // Remember line states for the next pass.
        val need = k + 1 + lineStarts.n
        if (need > starts.size) {
            val cap = max(need, starts.size * 2)
            starts = starts.copyOf(cap)
            states = states.copyOf(cap)
        }
        for (j in 0 until lineStarts.n) {
            starts[k + 1 + j] = lineStarts.a[j]
            states[k + 1 + j] = lineStates.a[j]
        }
        known = max(known, need)

        // Swap spans inside the window only.
        val e = edit.text
        for (sp in e.getSpans(from, to, Hl::class.java)) {
            val s = e.getSpanStart(sp)
            if (s >= from && s < to) e.removeSpan(sp)
        }
        var i = 0
        val len = e.length
        while (i < spans.n) {
            val s = spans.a[i]
            val en = min(spans.a[i + 1], len)
            if (s < en) e.setSpan(Hl(colors[spans.a[i + 2]]), s, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i += 3
        }

        if (pending) { pending = false; schedule(0) }
    }
}
