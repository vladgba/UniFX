package x.vladgba.fileman

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
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
            setOnItemLongClickListener { _, _, pos, _ -> copyRow(pos); true }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
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
        // Lenient UTF-8, one char per decoded char so search-hit offsets still line up.
        // Unprintables become dim markers: control pictures (␀ ␍ …), · for spaces, → for tabs, ¶ at line end
        // when "show unprintable" is on; otherwise only control/undecodable bytes become a dim middle dot.
        val decoded = String(bytes, Charsets.UTF_8)
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
        if (inv && hasNewline) { marks.add(out.length); out.append('\u00B6') }
        val plain = out.toString()
        val hit = hitOff
        val query = lastQuery
        val l = lang
        val sb = SpannableStringBuilder(plain)
        markDim(sb, marks)
        markHit(sb, bytes, info, hit, query)
        if (l == null || plain.isEmpty()) {
            textCache.put(r, sb)
            return sb
        }
        if (!hlWorker.isShutdown && queued.add(r)) {
            val gen = cacheGen
            hlWorker.execute {
                // Skip rows that scrolled far away before we got to them.
                val colored = if (kotlin.math.abs(r - lastBound) > 80) null else
                    Syntax.spannable(l, plain, colors).also { markDim(it, marks); markHit(it, bytes, info, hit, query) }
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

    private fun markHit(sb: SpannableStringBuilder, bytes: ByteArray, info: LineIndex.RowInfo, hit: Long, query: String?) {
        if (query == null || hit < info.start || hit >= info.end) return
        val from = String(bytes, 0, min(bytes.size.toLong(), hit - info.start).toInt(), Charsets.UTF_8).length
        val to = min(sb.length, from + query.length)
        if (from < to) sb.setSpan(BackgroundColorSpan(0x66F0B429), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun clearTextCache() {
        cacheGen++
        queued.clear()
        textCache.evictAll()
    }

    private fun copyRow(r: Int) {
        val t = try { text(r, info(r)).toString() } catch (e: IOException) { return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(file.name, t))
        toast(R.string.line_copied)
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

        fun setBody(t: CharSequence) { body.text = t }

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
            } catch (e: IOException) {
                num.text = ""
                body.setText(R.string.cannot_open)
            }
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
