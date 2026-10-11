package x.vladgba.fileman

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Files above this size (or not writable, or binary) go to the read-only viewer instead of the editor. */
const val EDIT_MAX_BYTES = 1L shl 20

/** Read-only text viewer module: handles big, binary and non-writable files without loading them into memory. */
object ViewerModule : FileOpener {
    override val title = R.string.text_viewer
    override fun canOpen(f: File) = false
    override fun open(a: Activity, f: File) = Openers.launch(a, ViewerActivity::class.java, f)
}

/** Random-access byte reader with a small page cache; the hot path (same page) is a plain array read. */
class ByteSource(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val size: Long = raf.length()
    private var curPage = -1L
    private var cur = ByteArray(0)
    private val pages = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > 8
    }

    fun byteAt(pos: Long): Int {
        val p = pos ushr PAGE_BITS
        if (p != curPage) {
            cur = pages[p] ?: load(p)
            curPage = p
        }
        return cur[(pos and PAGE_MASK).toInt()].toInt() and 0xFF
    }

    private fun load(p: Long): ByteArray {
        val start = p shl PAGE_BITS
        val b = ByteArray(min(PAGE.toLong(), size - start).toInt())
        raf.seek(start)
        raf.readFully(b)
        pages[p] = b
        return b
    }

    fun read(start: Long, end: Long): ByteArray {
        val b = ByteArray((end - start).toInt())
        raf.seek(start)
        raf.readFully(b)
        return b
    }

    override fun close() = raf.close()

    companion object {
        const val PAGE_BITS = 16
        const val PAGE = 1 shl PAGE_BITS
        const val PAGE_MASK = (PAGE - 1).toLong()
    }
}

/**
 * Splits a file into display rows: a row ends after '\n' or after [MAX_ROW] bytes (never inside a UTF-8
 * sequence), so binary files and huge single-line files still produce short rows. Only every [STEP]-th
 * row start is stored; any row is found by jumping to its checkpoint and scanning forward a few rows.
 */
class LineIndex(private val file: File) {
    class RowInfo(val start: Long, val end: Long, val line: Long, val cont: Boolean)

    @Volatile var rows = 0; private set
    @Volatile var lines = 0L; private set
    @Volatile var scanned = 0L; private set
    @Volatile var done = false; private set
    @Volatile var cancelled = false

    private var cpOff = LongArray(1024)
    private var cpLine = LongArray(1024)
    private var cpN = 0

    // forward-scan cursor for sequential lookups (UI thread only)
    private var curRow = -1
    private var curStart = 0L
    private var curLine = 0L

    private fun nextRowEnd(src: ByteSource, start: Long): Long {
        var p = start
        var len = 0
        val size = src.size
        while (p < size) {
            val b = src.byteAt(p)
            if (len >= MAX_ROW && ((b and 0xC0) != 0x80 || len >= MAX_ROW + 4)) return p
            p++
            len++
            if (b == NL) return p
        }
        return p
    }

    @Synchronized private fun addCheckpoint(off: Long, line: Long) {
        if (cpN == cpOff.size) {
            cpOff = cpOff.copyOf(cpN * 2)
            cpLine = cpLine.copyOf(cpN * 2)
        }
        cpOff[cpN] = off
        cpLine[cpN] = line
        cpN++
    }

    /** Runs on a background thread; [progress] is called periodically and once at the end. */
    fun build(progress: () -> Unit) {
        ByteSource(file).use { src ->
            val size = src.size
            var start = 0L
            var line = 0L
            var r = 0
            var last = SystemClock.uptimeMillis()
            while (!cancelled) {
                val exists = start < size || (start == size && (size == 0L || src.byteAt(size - 1) == NL))
                if (!exists) break
                if (r % STEP == 0) addCheckpoint(start, line)
                r++
                if (start >= size) break // trailing empty line after a final '\n'
                val end = nextRowEnd(src, start)
                if (src.byteAt(end - 1) == NL) line++
                start = end
                if (r and 0xFFF == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 250) {
                        rows = r; lines = line; scanned = start
                        last = now
                        progress()
                    }
                }
            }
            rows = r
            lines = line + 1
            scanned = size
            done = !cancelled
            progress()
        }
    }

    @Synchronized private fun checkpoint(k: Int) = longArrayOf(cpOff[k], cpLine[k])

    @Synchronized private fun cpCount() = cpN

    fun row(src: ByteSource, r: Int): RowInfo {
        val k = r / STEP
        var i = k * STEP
        var s: Long
        var line: Long
        if (curRow in i..r) {
            i = curRow; s = curStart; line = curLine
        } else {
            val cp = checkpoint(k)
            s = cp[0]; line = cp[1]
        }
        while (i < r) {
            val e = nextRowEnd(src, s)
            if (e > s && src.byteAt(e - 1) == NL) line++
            s = e
            i++
        }
        curRow = i; curStart = s; curLine = line
        val end = if (s >= src.size) s else nextRowEnd(src, s)
        return RowInfo(s, end, line, s > 0 && src.byteAt(s - 1) != NL)
    }

    /** Row containing byte [off] (must already be indexed). */
    fun rowForOffset(src: ByteSource, off: Long): Int {
        var lo = 0
        var hi = cpCount() - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (checkpoint(mid)[0] <= off) lo = mid else hi = mid - 1
        }
        var i = lo * STEP
        var s = checkpoint(lo)[0]
        while (i < rows - 1) {
            val e = nextRowEnd(src, s)
            if (off < e || e >= src.size) break
            s = e
            i++
        }
        return i
    }

    /** First row of 0-based [target] line (clamped to the last line). */
    fun rowForLine(src: ByteSource, target: Long): Int {
        var lo = 0
        var hi = cpCount() - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (checkpoint(mid)[1] < target) lo = mid else hi = mid - 1
        }
        val cp = checkpoint(lo)
        var i = lo * STEP
        var s = cp[0]
        var line = cp[1]
        while (line < target && i < rows - 1) {
            val e = nextRowEnd(src, s)
            if (e > s && src.byteAt(e - 1) == NL) line++
            s = e
            i++
        }
        return i
    }

    companion object {
        const val NL = '\n'.code
        const val MAX_ROW = 1024
        const val STEP = 32
    }
}

class ViewerActivity : Activity() {
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences
    private lateinit var src: ByteSource
    private lateinit var index: LineIndex
    private lateinit var list: ListView

    private val ui = Handler(Looper.getMainLooper())
    private val infoCache = LruCache<Int, LineIndex.RowInfo>(512)
    private val textCache = LruCache<Int, CharSequence>(256)
    private val offsetsCache = LruCache<Int, IntArray>(256)
    private var _count = 0
    private var binary = false
    private var lang: Lang? = null
    private var highlightOn = true
    private var invisibles = false
    private var colors = Syntax.palette(false)
    private var fontSp = 14f
    private var gutterDigits = 4
    private var lastQuery: String? = null
    private var hitOff = -1L
    private var searching = false
    private val hlWorker = Executors.newSingleThreadExecutor()
    private val queued = HashSet<Int>()
    private var cacheGen = 0
    @Volatile private var lastBound = 0 // most recently displayed row; queued rows far from it are skipped
    private var textColor = 0
    private var dimColor = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        Openers.adoptTarget(this, file)
        src = try { ByteSource(file) } catch (e: IOException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); finish(); return
        }
        prefs = getSharedPreferences("unifx", MODE_PRIVATE)
        fontSp = prefs.getFloat("ed_font", 14f)
        binary = (0 until min(src.size, 8192L)).any { src.byteAt(it) == 0 }
        highlightOn = prefs.getBoolean("hl", true)
        invisibles = prefs.getBoolean("invis", false)
        lang = if (binary || !highlightOn) null else Syntax.langFor(file)
        val night = Ui.isNight(this)
        colors = Syntax.palette(night)
        textColor = if (night) 0xFFE0E0E0.toInt() else 0xFF202124.toInt()
        dimColor = if (night) 0xFF6B7378.toInt() else 0xFF9AA0A6.toInt()
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        gutterDigits = max(3, (src.size / 40 + 1).toString().length) // estimate until indexing finishes

        list = ListView(this).apply {
            adapter = rows
            divider = null
            dividerHeight = 0
            isFastScrollEnabled = true
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }; false }
            setOnItemLongClickListener { _, view, _, _ ->
                (view as? RowView)?.let { startSelection(it, downX - it.left, downY - it.top) }
                true
            }
            setOnItemClickListener { _, _, _, _ -> selMode?.finish() }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) {}
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    if (selActive) placeHandles()
                }
            })
        }
        startHandle = Handle(this, true)
        endHandle = Handle(this, false)
        val frame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(startHandle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(endHandle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this)
        })

        index = LineIndex(file)
        updateSubtitle()
        Thread {
            try {
                index.build { ui.post { onIndexProgress() } }
            } catch (e: IOException) {
                ui.post { Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        hlWorker.shutdownNow()
        if (::index.isInitialized) index.cancelled = true
        ui.removeCallbacksAndMessages(null)
        if (::src.isInitialized) src.close()
    }

    private fun onIndexProgress() {
        if (isFinishing) return
        _count = index.rows
        if (index.done) {
            val d = max(3, index.lines.toString().length)
            if (d != gutterDigits) gutterDigits = d
            invalidateOptionsMenu()
        }
        rows.notifyDataSetChanged()
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val parts = ArrayList<String>()
        parts += getString(R.string.read_only)
        if (binary) parts += getString(R.string.binary)
        parts += if (index.done) getString(R.string.lines_count, index.lines)
        else getString(R.string.indexing, if (src.size == 0L) 100 else (index.scanned * 100 / src.size).toInt())
        actionBar?.subtitle = parts.joinToString(" · ")
    }

    private fun toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- rows

    private fun info(r: Int): LineIndex.RowInfo =
        infoCache.get(r) ?: index.row(src, r).also { infoCache.put(r, it) }

    /**
     * Row text. Plain text (cheap) is returned at once; for code files the row is colored on [hlWorker] and
     * swapped into its view when ready, so scrolling never waits on regex work.
     */
    private fun text(r: Int, info: LineIndex.RowInfo): CharSequence {
        textCache.get(r)?.let { return it }
        val inv = invisibles
        var end = info.end
        val hasNewline = end > info.start && src.byteAt(end - 1) == LineIndex.NL
        if (hasNewline) end--
        if (!inv && end > info.start && src.byteAt(end - 1) == '\r'.code) end--
        val bytes = src.read(info.start, end)
        // Lenient UTF-8 that records each char's byte offset (for selection, copy and search hits).
        // Unprintables become dim markers, one char each: control pictures (␀ ␍ …), · for spaces, → for tabs,
        // ¶ at line end when "show unprintable" is on; otherwise only control/undecodable bytes become a dim ·.
        val (decoded, decodedOffs) = decodeRow(bytes)
        val out = StringBuilder(decoded.length + 1)
        val marks = Ints()
        for (c in decoded) {
            val m = when {
                inv && c == ' ' -> '\u00B7'
                inv && c == '\t' -> '\u2192'
                inv && c < ' ' -> (0x2400 + c.code).toChar()
                inv && c == '\u007F' -> '\u2421'
                inv && c == '\u00A0' -> '\u00B0'
                (c < ' ' && c != '\t') || c == '\u007F' || c == '\uFFFD' -> '\u00B7'
                else -> 0.toChar()
            }
            if (m.code != 0) { marks.add(out.length); out.append(m) } else out.append(c)
        }
        val offs: IntArray
        if (inv && hasNewline) {
            marks.add(out.length); out.append('\u00B6')
            offs = decodedOffs.copyOf(decodedOffs.size + 1).also { it[it.size - 1] = (info.end - info.start).toInt() }
        } else {
            offs = decodedOffs
        }
        offsetsCache.put(r, offs)
        val plain = out.toString()
        val hit = hitOff
        val query = lastQuery
        val l = lang
        val sb = SpannableStringBuilder(plain)
        markDim(sb, marks)
        markHit(sb, offs, info, hit, query)
        if (l == null || plain.isEmpty()) {
            textCache.put(r, sb)
            return sb
        }
        if (!hlWorker.isShutdown && queued.add(r)) {
            val gen = cacheGen
            hlWorker.execute {
                // Skip rows that scrolled far away before we got to them.
                val colored = if (kotlin.math.abs(r - lastBound) > 80) null else
                    Syntax.spannable(l, plain, colors).also { markDim(it, marks); markHit(it, offs, info, hit, query) }
                ui.post {
                    queued.remove(r)
                    if (colored == null || gen != cacheGen || isFinishing) return@post
                    textCache.put(r, colored)
                    val child = list.getChildAt(r - list.firstVisiblePosition) as? RowView
                    if (child != null && child.boundRow == r) child.setBody(colored)
                }
            }
        }
        return sb
    }

    /** Dim the marker characters (added after syntax colors, so it wins). */
    private fun markDim(sb: SpannableStringBuilder, marks: Ints) {
        for (i in 0 until marks.n) {
            val p = marks.a[i]
            sb.setSpan(ForegroundColorSpan(dimColor), p, p + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun markHit(sb: SpannableStringBuilder, offs: IntArray, info: LineIndex.RowInfo, hit: Long, query: String?) {
        if (query == null || hit < info.start || hit >= info.end) return
        val from = charIndex(offs, (hit - info.start).toInt())
        val to = min(sb.length, from + query.length)
        if (from < to) sb.setSpan(BackgroundColorSpan(0x66F0B429), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun clearTextCache() {
        cacheGen++
        queued.clear()
        textCache.evictAll()
        offsetsCache.evictAll()
    }

    /** Byte offset (within the row) of every displayed char, plus one trailing entry for the row end. */
    private fun rowOffsets(r: Int, info: LineIndex.RowInfo): IntArray {
        offsetsCache.get(r)?.let { return it }
        textCache.remove(r) // rebuild the row; text() refills offsetsCache
        text(r, info)
        return offsetsCache.get(r) ?: intArrayOf(0)
    }

    /** Lenient UTF-8 decode; invalid bytes become U+FFFD (one per byte). Returns text and per-char byte offsets. */
    private fun decodeRow(b: ByteArray): Pair<String, IntArray> {
        val sb = StringBuilder(b.size)
        val offs = IntArray(b.size * 2 + 1)
        var n = 0
        var i = 0
        fun cont(k: Int): Boolean {
            for (j in 1..k) if (i + j >= b.size || (b[i + j].toInt() and 0xC0) != 0x80) return false
            return true
        }
        fun bits(k: Int) = b[i + k].toInt() and 0x3F
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            var cp = -1
            var len = 1
            when {
                c < 0x80 -> { cp = c }
                c in 0xC2..0xDF && cont(1) -> { cp = ((c and 0x1F) shl 6) or bits(1); len = 2 }
                c in 0xE0..0xEF && cont(2) -> {
                    val v = ((c and 0x0F) shl 12) or (bits(1) shl 6) or bits(2)
                    if (v >= 0x800 && v !in 0xD800..0xDFFF) { cp = v; len = 3 }
                }
                c in 0xF0..0xF4 && cont(3) -> {
                    val v = ((c and 0x07) shl 18) or (bits(1) shl 12) or (bits(2) shl 6) or bits(3)
                    if (v in 0x10000..0x10FFFF) { cp = v; len = 4 }
                }
            }
            if (cp < 0) {
                offs[n++] = i; sb.append('\uFFFD')
            } else if (cp >= 0x10000) {
                offs[n++] = i; offs[n++] = i; sb.appendCodePoint(cp)
            } else {
                offs[n++] = i; sb.append(cp.toChar())
            }
            i += len
        }
        offs[n] = b.size
        return sb.toString() to offs.copyOf(n + 1)
    }

    /** First char whose byte offset is >= [byteRel] (clamped to the row end). */
    private fun charIndex(offs: IntArray, byteRel: Int): Int {
        var lo = 0
        var hi = offs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offs[mid] < byteRel) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private val rows = object : BaseAdapter() {
        override fun getCount() = _count
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? RowView ?: RowView(this@ViewerActivity)
            row.bind(position)
            return row
        }
    }

    private inner class RowView(ctx: Context) : LinearLayout(ctx) {
        private val num = TextView(ctx)
        private val body = TextView(ctx)
        private var digits = -1
        private var size = -1f

        init {
            orientation = HORIZONTAL
            num.typeface = Fonts.mono()
            num.gravity = Gravity.END
            num.setTextColor(dimColor)
            body.typeface = Fonts.mono()
            body.setTextColor(textColor)
            val p = Ui.dp(ctx, 6)
            num.setPadding(p, 0, p, 0)
            body.setPadding(p, 0, p, 0)
            addView(num, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(body, LayoutParams(0, WRAP_CONTENT, 1f))
        }

        var boundRow = -1
        var rowStart = 0L
        var rowEnd = 0L
        private var offs = IntArray(1)
        private val path = Path()

        init { setWillNotDraw(true) }

        fun setBody(t: CharSequence) { body.text = t }

        fun shownText(): CharSequence = body.text

        /** Absolute file offset of displayed char [ch]. */
        fun byteOfChar(ch: Int): Long = rowStart + offs[ch.coerceIn(0, offs.size - 1)]

        /** Char index under a point in this row's coordinates. */
        fun charAt(x: Float, y: Float): Int {
            val l = body.layout ?: return 0
            val by = (y - body.top - body.totalPaddingTop).toInt().coerceIn(0, max(0, l.height - 1))
            val line = l.getLineForVertical(by)
            return l.getOffsetForHorizontal(line, x - body.left - body.totalPaddingLeft).coerceIn(0, offs.size - 1)
        }

        fun byteAt(x: Float, y: Float): Long = byteOfChar(charAt(x, y))

        /** Bottom-of-line point (row coordinates) for an absolute file offset inside this row. */
        fun pointFor(off: Long): FloatArray? {
            val l = body.layout ?: return null
            val ch = charIndex(offs, (off - rowStart).toInt()).coerceAtMost(body.text.length)
            val line = l.getLineForOffset(ch)
            return floatArrayOf(
                body.left + body.totalPaddingLeft + l.getPrimaryHorizontal(ch),
                (body.top + body.totalPaddingTop + l.getLineBottom(line)).toFloat(),
            )
        }

        // Selection highlight is drawn behind the text, straight from the selection's byte range,
        // so it never touches the cached spannables.
        override fun dispatchDraw(c: Canvas) {
            val l = body.layout
            if (selActive && l != null && selEnd > rowStart && selStart < rowEnd) {
                val len = body.text.length
                val cs = if (selStart <= rowStart) 0 else charIndex(offs, (selStart - rowStart).toInt()).coerceAtMost(len)
                val ce = if (selEnd >= rowEnd) len else charIndex(offs, (selEnd - rowStart).toInt()).coerceAtMost(len)
                if (cs < ce) {
                    path.reset()
                    l.getSelectionPath(cs, ce, path)
                    c.save()
                    c.translate((body.left + body.totalPaddingLeft).toFloat(), (body.top + body.totalPaddingTop).toFloat())
                    c.drawPath(path, selPaint)
                    c.restore()
                }
            }
            super.dispatchDraw(c)
        }

        fun bind(r: Int) {
            boundRow = r
            lastBound = r
            if (size != fontSp) {
                size = fontSp
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp * 0.85f)
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
                digits = -1
            }
            if (digits != gutterDigits) {
                digits = gutterDigits
                num.layoutParams.width = (num.paint.measureText("0") * digits).toInt() + num.paddingLeft + num.paddingRight
                num.requestLayout()
            }
            try {
                val info = info(r)
                num.text = if (info.cont) "" else (info.line + 1).toString()
                body.text = text(r, info)
                offs = rowOffsets(r, info)
                rowStart = info.start
                rowEnd = info.end
            } catch (e: IOException) {
                num.text = ""
                body.setText(R.string.cannot_open)
            }
        }
    }

    // ---------------------------------------------------------------- text selection
    //
    // Rows are separate views, so selection is our own: a byte range in the file, highlighted by each row,
    // with two drag handles in an overlay and a Copy action bar. It works across any number of rows and in
    // files of any size; Copy reads the range straight from the file.

    private var selActive = false
    private var selStart = 0L
    private var selEnd = 0L
    private var selMode: ActionMode? = null
    private val selPaint = Paint().apply { color = 0x664A90E2 }
    private lateinit var startHandle: Handle
    private lateinit var endHandle: Handle
    private var downX = 0f
    private var downY = 0f
    private val loc = IntArray(2)

    private fun startSelection(row: RowView, x: Float, y: Float) {
        val t = row.shownText()
        val ch = row.charAt(x, y)
        fun word(c: Char) = c.isLetterOrDigit() || c == '_'
        var a = ch
        var b = ch
        if (ch < t.length && word(t[ch])) {
            while (a > 0 && word(t[a - 1])) a--
            while (b < t.length && word(t[b])) b++
        } else {
            b = min(ch + 1, t.length)
        }
        selStart = row.byteOfChar(a)
        selEnd = row.byteOfChar(b)
        if (selEnd <= selStart) selEnd = min(selStart + 1, src.size)
        if (selEnd <= selStart) return // empty file
        selActive = true
        if (selMode == null) selMode = startActionMode(selectionCallback)
        onSelectionChanged()
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, S_COPY, 0, R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, S_ALL, 1, R.string.select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                S_COPY -> { copySelection(); mode.finish() }
                S_ALL -> { selStart = 0; selEnd = src.size; onSelectionChanged() }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selMode = null
            selActive = false
            startHandle.visibility = View.GONE
            endHandle.visibility = View.GONE
            stopAutoScroll()
            redrawRows()
        }
    }

    private fun onSelectionChanged() {
        redrawRows()
        placeHandles()
        selMode?.title = android.text.format.Formatter.formatShortFileSize(this, selEnd - selStart)
    }

    private fun redrawRows() {
        for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
    }

    /** Put each handle under its end of the selection, or hide it when that end is off screen. */
    private fun placeHandles() {
        place(startHandle, selStart, start = true)
        place(endHandle, selEnd, start = false)
    }

    private fun place(h: Handle, off: Long, start: Boolean) {
        if (!selActive) { h.visibility = View.GONE; return }
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? RowView ?: continue
            val inside = if (start) off >= row.rowStart && off < row.rowEnd else off > row.rowStart && off <= row.rowEnd
            if (!inside) continue
            val p = row.pointFor(off) ?: break
            h.translationX = list.left + row.left + p[0] - h.widthPx / 2f
            h.translationY = list.top + row.top + p[1]
            h.visibility = if (row.top + p[1] in 0f..list.height.toFloat()) View.VISIBLE else View.INVISIBLE
            return
        }
        h.visibility = View.INVISIBLE
    }

    // ---- dragging a handle (with auto-scroll near the top/bottom edge)

    private var dragStart = true
    private var dragX = 0f
    private var dragY = 0f
    private var scrollStep = 0
    private val autoScroll = object : Runnable {
        override fun run() {
            if (scrollStep == 0) return
            list.scrollListBy(scrollStep)
            dragUpdate(dragX, dragY)
            ui.postDelayed(this, 16)
        }
    }

    private fun stopAutoScroll() {
        scrollStep = 0
        ui.removeCallbacks(autoScroll)
    }

    private fun dragTo(h: Handle, rawX: Float, rawY: Float) {
        list.getLocationOnScreen(loc)
        dragStart = h.isStart
        dragX = rawX - loc[0]
        // Aim at the text just above the finger, not under it.
        dragY = rawY - loc[1] - h.heightPx - Ui.dp(this, 4)
        val edge = Ui.dp(this, 28)
        val step = when {
            dragY < edge -> -Ui.dp(this, 10)
            dragY > list.height - edge -> Ui.dp(this, 10)
            else -> 0
        }
        if (step != scrollStep) {
            scrollStep = step
            ui.removeCallbacks(autoScroll)
            if (step != 0) ui.post(autoScroll)
        }
        dragUpdate(dragX, dragY)
    }

    private fun dragUpdate(x: Float, y: Float) {
        if (list.childCount == 0) return
        val yy = y.coerceIn(0f, list.height - 1f)
        var row: RowView? = null
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (yy >= c.top && yy < c.bottom) { row = c as? RowView; break }
        }
        row ?: return
        val off = row.byteAt(x - row.left, yy - row.top)
        // Handles don't swap roles: each one stops one byte short of the other.
        if (dragStart) selStart = off.coerceAtMost(selEnd - 1) else selEnd = off.coerceAtLeast(selStart + 1)
        onSelectionChanged()
    }

    private fun copySelection() {
        val len = selEnd - selStart
        val n = min(len, MAX_COPY)
        val text = try { String(src.read(selStart, selStart + n), Charsets.UTF_8) } catch (e: IOException) { toast(R.string.failed); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(file.name, text))
        if (n < len) toast(getString(R.string.copied_truncated, android.text.format.Formatter.formatShortFileSize(this, n)))
        else toast(R.string.copied_text)
    }

    /** Teardrop selection handle; its tip sits at the bottom of the selected line. */
    private inner class Handle(ctx: Context, val isStart: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A90E2.toInt() }
        private val r = Ui.dp(ctx, 11).toFloat()
        private val stem = Ui.dp(ctx, 5).toFloat()
        val widthPx get() = (r * 2).toInt()
        val heightPx get() = (r * 2 + stem).toInt()

        init { visibility = View.GONE }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(widthPx, heightPx)

        override fun onDraw(c: Canvas) {
            c.drawRect(r - Ui.dp(context, 1), 0f, r + Ui.dp(context, 1), stem + r, paint)
            c.drawCircle(r, stem + r, r, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> dragTo(this, e.rawX, e.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopAutoScroll()
            }
            return true
        }
    }

    // ---------------------------------------------------------------- find / go to

    private fun find() {
        Ui.input(this, getString(R.string.find), lastQuery ?: "", InputType.TYPE_CLASS_TEXT, R.string.find, { q ->
            if (q.isNotEmpty()) { lastQuery = q; invalidateOptionsMenu(); search(q, fromStart = false) }
        })
    }

    /** Byte search (ASCII case-insensitive), from just after the last hit / top visible row, wrapping once. */
    private fun search(q: String, fromStart: Boolean) {
        if (searching) return
        val pat = q.toByteArray(Charsets.UTF_8).map { lower(it.toInt() and 0xFF) }.toIntArray()
        val from = when {
            fromStart -> 0L
            hitOff >= 0 -> hitOff + 1
            _count > 0 -> info(list.firstVisiblePosition).start
            else -> 0L
        }
        searching = true
        actionBar?.subtitle = getString(R.string.searching)
        Thread {
            val hit = try {
                ByteSource(file).use { s -> scan(s, pat, from, s.size).let { if (it >= 0) it else scan(s, pat, 0, min(from, s.size)) } }
            } catch (e: IOException) { -1L }
            ui.post {
                searching = false
                if (isFinishing) return@post
                updateSubtitle()
                if (hit < 0) { toast(R.string.not_found); return@post }
                if (hit >= index.scanned && !index.done) { toast(R.string.still_indexing); return@post }
                hitOff = hit
                clearTextCache()
                val r = index.rowForOffset(src, hit)
                rows.notifyDataSetChanged()
                list.setSelection(max(0, r - 3))
            }
        }.start()
    }

    private fun lower(b: Int) = if (b in 'A'.code..'Z'.code) b + 32 else b

    private fun scan(s: ByteSource, pat: IntArray, from: Long, to: Long): Long {
        if (pat.isEmpty()) return -1
        val last = min(to, s.size - pat.size + 1)
        val first = pat[0]
        var p = from
        while (p < last) {
            if (lower(s.byteAt(p)) == first) {
                var j = 1
                while (j < pat.size && lower(s.byteAt(p + j)) == pat[j]) j++
                if (j == pat.size) return p
            }
            p++
        }
        return -1
    }

    private fun goToLine() {
        if (!index.done) { toast(R.string.still_indexing); return }
        Ui.input(this, getString(R.string.go_to_line), "", InputType.TYPE_CLASS_NUMBER, android.R.string.ok, { s ->
            val n = s.toLongOrNull() ?: return@input
            list.setSelection(index.rowForLine(src, (n - 1).coerceIn(0, index.lines - 1)))
        })
    }

    // ---------------------------------------------------------------- menu

    private fun canEdit() = file.canWrite() && file.length() <= EDIT_MAX_BYTES && !binary

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_FIND, 0, R.string.find).setIcon(R.drawable.ic_find)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SMALLER, 1, R.string.font_smaller).setIcon(R.drawable.ic_text_smaller)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_BIGGER, 2, R.string.font_bigger).setIcon(R.drawable.ic_text_bigger)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_FIND_NEXT, 3, R.string.find_next)
        menu.add(0, M_GOTO, 4, R.string.go_to_line)
        menu.add(0, M_HIGHLIGHT, 5, R.string.highlight_code).isCheckable = true
        menu.add(0, M_INVISIBLES, 6, R.string.show_invisibles).isCheckable = true
        menu.add(0, M_EDIT, 7, R.string.edit)
        menu.add(0, M_HEX, 8, R.string.open_in_hex)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_FIND_NEXT).isVisible = lastQuery != null
        menu.findItem(M_GOTO).isEnabled = index.done
        menu.findItem(M_EDIT).isVisible = canEdit()
        menu.findItem(M_HIGHLIGHT).apply { isVisible = !binary && Syntax.langFor(file) != null; isChecked = highlightOn }
        menu.findItem(M_INVISIBLES).isChecked = invisibles
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_FIND -> find()
            M_FIND_NEXT -> lastQuery?.let { search(it, fromStart = false) }
            M_GOTO -> goToLine()
            M_BIGGER, M_SMALLER -> {
                fontSp = (fontSp + if (item.itemId == M_BIGGER) 2f else -2f).coerceIn(8f, 32f)
                prefs.edit().putFloat("ed_font", fontSp).apply()
                rows.notifyDataSetChanged()
            }
            M_HIGHLIGHT -> {
                highlightOn = !highlightOn
                prefs.edit().putBoolean("hl", highlightOn).apply()
                lang = if (binary || !highlightOn) null else Syntax.langFor(file)
                clearTextCache()
                rows.notifyDataSetChanged()
            }
            M_INVISIBLES -> {
                invisibles = !invisibles
                prefs.edit().putBoolean("invis", invisibles).apply()
                clearTextCache()
                rows.notifyDataSetChanged()
            }
            M_EDIT -> { Openers.launch(this, EditorActivity::class.java, file); finish() }
            M_HEX -> { HexModule.open(this, file); finish() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private companion object {
        const val S_COPY = 1
        const val S_ALL = 2
        const val MAX_COPY = 1L shl 20 // clipboard data goes through a ~1 MB binder transaction
        const val M_FIND = 1
        const val M_FIND_NEXT = 2
        const val M_GOTO = 3
        const val M_BIGGER = 4
        const val M_SMALLER = 5
        const val M_EDIT = 6
        const val M_HEX = 7
        const val M_HIGHLIGHT = 8
        const val M_INVISIBLES = 9
    }
}
