package x.vladgba.fileman

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
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
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

/** Built-in hex editor module. Never claims files on tap; reached via "Open with…" or from the text editor. */
object HexModule : FileOpener {
    override val title = R.string.hex_editor
    override fun canOpen(f: File) = false
    override fun open(a: Activity, f: File) = Openers.launch(a, HexActivity::class.java, f)
}

/**
 * Paged, overwrite-only view of a file. Reads 4 KiB pages on demand (small LRU), keeps edits in a sparse
 * map and writes only the changed bytes on save, so file size doesn't matter.
 */
class HexDoc(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val size: Long = raf.length()
    val patches = TreeMap<Long, Byte>()

    private val pages = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > 64
    }

    fun original(off: Long): Int {
        val p = off ushr PAGE_BITS
        val page = pages[p] ?: load(p)
        val i = (off and PAGE_MASK).toInt()
        return if (i < page.size) page[i].toInt() and 0xFF else 0
    }

    fun get(off: Long): Int = patches[off]?.let { it.toInt() and 0xFF } ?: original(off)

    fun isPatched(off: Long) = patches.containsKey(off)

    fun set(off: Long, v: Int) {
        if (v == original(off)) patches.remove(off) else patches[off] = v.toByte()
    }

    private fun load(p: Long): ByteArray {
        val start = p shl PAGE_BITS
        val b = ByteArray(min(PAGE.toLong(), size - start).toInt().coerceAtLeast(0))
        raf.seek(start)
        raf.readFully(b)
        pages[p] = b
        return b
    }

    fun save() {
        RandomAccessFile(file, "rw").use { w ->
            var runStart = -1L
            val run = java.io.ByteArrayOutputStream()
            fun flush() { if (run.size() > 0) { w.seek(runStart); w.write(run.toByteArray()); run.reset() } }
            for ((off, v) in patches) {
                if (runStart < 0 || off != runStart + run.size()) { flush(); runStart = off }
                run.write(v.toInt())
            }
            flush()
        }
        patches.clear()
        pages.clear()
    }

    /** First match of [pat] whose start lies in [from, to); patches are overlaid on what's read from disk. */
    fun search(pat: ByteArray, from: Long, to: Long, overlay: TreeMap<Long, Byte>): Long {
        if (pat.isEmpty()) return -1
        RandomAccessFile(file, "r").use { r ->
            val buf = ByteArray(CHUNK + pat.size - 1)
            var pos = from
            while (pos < to) {
                val n = min(buf.size.toLong(), size - pos).toInt()
                if (n < pat.size) return -1
                r.seek(pos)
                r.readFully(buf, 0, n)
                for ((o, v) in overlay.subMap(pos, pos + n)) buf[(o - pos).toInt()] = v
                val last = min(n - pat.size.toLong(), to - 1 - pos).toInt()
                outer@ for (i in 0..last) {
                    for (j in pat.indices) if (buf[i + j] != pat[j]) continue@outer
                    return pos + i
                }
                pos += (n - pat.size + 1).toLong()
            }
        }
        return -1
    }

    override fun close() = raf.close()

    companion object {
        const val PAGE_BITS = 12
        const val PAGE = 1 shl PAGE_BITS
        const val PAGE_MASK = (PAGE - 1).toLong()
        const val CHUNK = 256 * 1024
    }
}

class HexActivity : Activity() {
    private lateinit var doc: HexDoc
    private lateinit var list: ListView
    private lateinit var header: Row
    private lateinit var status: TextView
    private lateinit var keypad: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val undo = ArrayList<List<Edit>>()
    private var cursor = 0L
    private var lowNibble = false
    private var lastTouchX = 0f
    private var readOnly = false
    private var lastPattern: ByteArray? = null
    private var searching = false

    private class Edit(val off: Long, val old: Int, val new: Int)

    // ---- metrics (recomputed when the width changes)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.mono() }
    private val fill = Paint()
    private var cw = 0f       // one character cell (= widest hex digit)
    private var gap = 0f      // space between hex bytes
    private var byteW = 0f    // one hex byte: two cells + gap
    private var colGap = 0f   // space between offset | hex | ASCII columns
    private val glyphW = FloatArray(128)
    private var rowH = 0
    private var textBase = 0f
    private var pad = 0f
    private var bpr = 16
    private var offDigits = 8
    private var hexX = 0f
    private var asciiX = 0f
    private var cText = 0
    private var cDim = 0
    private var cMod = 0
    private var cOff = 0
    private var cCursor = 0
    private var cAccent = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val f = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        Openers.adoptTarget(this, f)
        doc = try { HexDoc(f) } catch (e: IOException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show(); finish(); return
        }
        readOnly = !f.canWrite()
        actionBar?.setDisplayHomeAsUpEnabled(true)
        initMetrics()
        setContentView(buildUi())
        updateTitle()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::doc.isInitialized) doc.close()
    }

    private fun initMetrics() {
        val night = Ui.isNight(this)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        // Some devices' "monospace" isn't truly fixed-width: size the cell to the widest printable glyph.
        // Cell = widest hex digit, so offset/hex columns stay tight even if the font isn't truly fixed-width.
        // ASCII uses the same cell; the few glyphs wider than it (W, M, @...) are squeezed when drawn.
        cw = "0123456789ABCDEF".maxOf { paint.measureText(it.toString()) }
        for (ch in 0x20..0x7E) glyphW[ch] = paint.measureText(ch.toChar().toString())
        gap = cw * 0.45f
        byteW = cw * 2 + gap
        colGap = cw * 1.1f
        rowH = (paint.fontSpacing * 1.3f).toInt()
        textBase = (rowH - paint.ascent() - paint.descent()) / 2f
        pad = Ui.dp(this, 8).toFloat()
        offDigits = max(4, java.lang.Long.toHexString(max(0, doc.size - 1)).length)
        cText = if (night) 0xFFE0E0E0.toInt() else 0xFF202124.toInt()
        cDim = if (night) 0xFF5F6368.toInt() else 0xFFB0B4B8.toInt()
        cOff = if (night) 0xFF6B9BD1.toInt() else 0xFF5F6B7A.toInt()
        cMod = if (night) 0xFFFF7B72.toInt() else 0xFFD32F2F.toInt()
        cCursor = if (night) 0x40F0B429 else 0x40F0A500
        cAccent = 0xFFF0A500.toInt()
    }

    /** Pick 16, 8 or 4 bytes per row, whichever fits the width. */
    private fun layoutColumns(width: Int): Boolean {
        val old = bpr
        fun need(b: Int) = pad * 2 + offDigits * cw + colGap + b * byteW - gap + colGap + b * cw
        bpr = intArrayOf(16, 12, 8, 4).firstOrNull { need(it) <= width } ?: 4
        hexX = pad + offDigits * cw + colGap
        asciiX = hexX + bpr * byteW - gap + colGap
        return bpr != old
    }

    private fun buildUi(): View {
        header = Row(this).apply { row = -1 }
        list = ListView(this).apply {
            adapter = rows
            isFastScrollEnabled = true
            divider = null
            dividerHeight = 0
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN) lastTouchX = ev.x; false }
            setOnItemClickListener { _, _, pos, _ -> tapAt(pos, lastTouchX) }
            addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
                if (r - l != oldR - oldL && layoutColumns(v.width)) {
                    rows.notifyDataSetChanged()
                    setSelection((cursor / bpr).toInt())
                }
                header.invalidate()
            }
        }
        status = TextView(this).apply {
            typeface = Fonts.mono()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
            setBackgroundColor(if (Ui.isNight(context)) 0xFF1B2124.toInt() else 0xFFF1F3F4.toInt())
        }
        keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (r in 0..1) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (k in 0..7) addView(key(HEX[r * 8 + k].toString()) { type(r * 8 + k) }, keyLp())
                    addView(key(if (r == 0) "◀" else "▶") { move(if (r == 0) -1 else 1) }, keyLp())
                })
            }
            visibility = if (readOnly) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, rowH))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(keypad, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            Ui.applyInsets(this)
        }
    }

    private fun keyLp() = LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f)

    private fun key(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = Fonts.monoBold()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setBackgroundResource(Ui.attrRes(context, android.R.attr.selectableItemBackground))
        setOnClickListener { onClick() }
    }

    private fun updateTitle() {
        actionBar?.title = (if (doc.patches.isNotEmpty()) "• " else "") + doc.file.name
        actionBar?.subtitle = android.text.format.Formatter.formatFileSize(this, doc.size) +
            " · ${doc.size} bytes" + if (readOnly) " · " + getString(R.string.read_only) else ""
    }

    private fun toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- cursor & editing

    private fun tapAt(row: Int, x: Float) {
        val col = when {
            x >= hexX - gap / 2 && x < hexX + bpr * byteW - gap / 2 -> ((x - hexX + gap / 2) / byteW).toInt()
            x >= asciiX && x < asciiX + bpr * cw -> ((x - asciiX) / cw).toInt()
            else -> return
        }
        val off = row.toLong() * bpr + col
        if (off < doc.size) { cursor = off; lowNibble = false; refresh() }
    }

    private fun move(d: Int) {
        if (doc.size == 0L) return
        cursor = (cursor + d).coerceIn(0, doc.size - 1)
        lowNibble = false
        refresh()
    }

    private fun type(nibble: Int) {
        if (readOnly || doc.size == 0L) return
        val old = doc.get(cursor)
        val v = if (!lowNibble) (nibble shl 4) or (old and 0x0F) else (old and 0xF0) or nibble
        write(listOf(cursor to v))
        if (!lowNibble) lowNibble = true else { lowNibble = false; if (cursor < doc.size - 1) cursor++ }
        refresh()
    }

    private fun write(values: List<Pair<Long, Int>>) {
        val group = values.map { (o, v) -> Edit(o, doc.get(o), v) }
        group.forEach { doc.set(it.off, it.new) }
        undo += group
        if (undo.size > 1000) undo.removeAt(0)
        updateTitle()
        invalidateOptionsMenu()
    }

    private fun undoLast() {
        val group = undo.removeLastOrNull() ?: return
        group.asReversed().forEach { doc.set(it.off, it.old) }
        cursor = group.first().off
        lowNibble = false
        updateTitle()
        invalidateOptionsMenu()
        refresh()
    }

    private fun refresh() {
        list.invalidateViews()
        val row = (cursor / bpr).toInt()
        if (row < list.firstVisiblePosition || row > list.lastVisiblePosition - 1) list.setSelection(max(0, row - 2))
        updateStatus()
    }

    private fun updateStatus() {
        if (doc.size == 0L) { status.setText(R.string.empty_file); return }
        val v = doc.get(cursor)
        val sb = StringBuilder()
        sb.append("@ 0x").append(java.lang.Long.toHexString(cursor).uppercase()).append(" (").append(cursor).append(")\n")
        sb.append("u8 ").append(v).append("  i8 ").append(v.toByte().toInt())
        sb.append("  bin ").append(Integer.toBinaryString(v).padStart(8, '0'))
        if (cursor + 1 < doc.size) {
            val u16 = v or (doc.get(cursor + 1) shl 8)
            sb.append("\nu16 ").append(u16).append("  i16 ").append(u16.toShort().toInt())
        }
        if (cursor + 3 < doc.size) {
            var u32 = 0L
            for (i in 3 downTo 0) u32 = (u32 shl 8) or doc.get(cursor + i).toLong()
            sb.append("  u32 ").append(u32).append("  i32 ").append(u32.toInt())
        }
        sb.append("  (LE)")
        status.text = sb
    }

    // ---------------------------------------------------------------- go to / find / write text

    private fun goTo() {
        fun jump(s: String, radix: Int) {
            val off = s.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(radix)
            if (off == null || off < 0 || off >= doc.size) { toast(R.string.out_of_range); return }
            cursor = off; lowNibble = false
            list.setSelection(max(0, (off / bpr).toInt() - 2))
            refresh()
        }
        Ui.input(this, getString(R.string.go_to_offset), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            R.string.hex, { jump(it, 16) }, R.string.decimal, { jump(it, 10) })
    }

    private fun parseHex(s: String): ByteArray? {
        val h = s.replace(Regex("[\\s,]|0x"), "")
        if (h.isEmpty() || h.length % 2 != 0 || h.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(h.length / 2) { ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte() }
    }

    private fun find() {
        Ui.input(this, getString(R.string.find), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            R.string.hex, { s -> parseHex(s)?.let { search(it) } ?: toast(R.string.bad_hex) },
            R.string.text, { s -> if (s.isNotEmpty()) search(s.toByteArray(Charsets.UTF_8)) })
    }

    private fun search(pat: ByteArray) {
        if (searching || doc.size == 0L) return
        lastPattern = pat
        invalidateOptionsMenu()
        searching = true
        status.setText(R.string.searching)
        val start = cursor + 1
        val overlay = TreeMap(doc.patches)
        Thread {
            val hit = try {
                doc.search(pat, start, doc.size, overlay).let { if (it >= 0) it else doc.search(pat, 0, min(start, doc.size), overlay) }
            } catch (e: IOException) { -1L }
            ui.post {
                searching = false
                if (isFinishing) return@post
                if (hit < 0) { toast(R.string.not_found); updateStatus(); return@post }
                cursor = hit; lowNibble = false
                list.setSelection(max(0, (hit / bpr).toInt() - 2))
                refresh()
            }
        }.start()
    }

    private fun writeText() {
        if (readOnly || doc.size == 0L) return
        Ui.input(this, getString(R.string.write_text), "", InputType.TYPE_CLASS_TEXT, android.R.string.ok, { s ->
            val bytes = s.toByteArray(Charsets.UTF_8)
            val n = min(bytes.size.toLong(), doc.size - cursor).toInt()
            if (n <= 0) return@input
            write(List(n) { (cursor + it) to (bytes[it].toInt() and 0xFF) })
            cursor = min(cursor + n, doc.size - 1)
            lowNibble = false
            refresh()
        })
    }

    private fun save(then: (() -> Unit)? = null) {
        try {
            doc.save()
            // Cached copy of another app's file: also write back to the original URI.
            if (!Openers.writeBack(this, doc.file)) throw IOException("write-back failed")
            undo.clear()
            toast(R.string.saved)
            updateTitle()
            invalidateOptionsMenu()
            list.invalidateViews()
            then?.invoke()
        } catch (e: IOException) {
            toast(R.string.failed)
        }
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SAVE, 0, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_UNDO, 1, "↶").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_GOTO, 2, R.string.go_to_offset)
        menu.add(0, M_FIND, 3, R.string.find).setIcon(R.drawable.ic_find)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_FIND_NEXT, 4, R.string.find_next)
        menu.add(0, M_TEXT, 5, R.string.write_text)
        menu.add(0, M_KEYPAD, 6, R.string.keypad).isCheckable = true
        menu.add(0, M_EDITOR, 7, R.string.open_in_text)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_SAVE).isEnabled = doc.patches.isNotEmpty() && !readOnly
        menu.findItem(M_UNDO).isEnabled = undo.isNotEmpty()
        menu.findItem(M_FIND_NEXT).isVisible = lastPattern != null
        menu.findItem(M_TEXT).isVisible = !readOnly
        menu.findItem(M_KEYPAD).apply { isVisible = !readOnly; isChecked = keypad.visibility == View.VISIBLE }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            M_SAVE -> save()
            M_UNDO -> undoLast()
            M_GOTO -> goTo()
            M_FIND -> find()
            M_FIND_NEXT -> lastPattern?.let { search(it) }
            M_TEXT -> writeText()
            M_KEYPAD -> keypad.visibility = if (keypad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            M_EDITOR -> {
                val open = { TextModule.open(this, doc.file); finish() }
                if (doc.patches.isNotEmpty()) Ui.confirmUnsaved(this, doc.file.name, { save(open) }, open) else open()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (doc.patches.isNotEmpty()) Ui.confirmUnsaved(this, doc.file.name, { save { finish() } }, { finish() })
        else super.onBackPressed()
    }

    // ---------------------------------------------------------------- rows

    private val rows = object : BaseAdapter() {
        override fun getCount(): Int = ((doc.size + bpr - 1) / bpr).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            ((convertView as? Row) ?: Row(this@HexActivity)).also { it.row = position.toLong(); it.invalidate() }
    }

    /** One line of the dump, drawn directly: offset | hex bytes | ASCII. Row -1 is the column header. */
    private inner class Row(ctx: Context) : View(ctx) {
        var row = 0L
        private val chars = CharArray(16)

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), rowH)

        private fun hexInto(v: Long, digits: Int) {
            for (i in 0 until digits) chars[i] = HEX[((v ushr ((digits - 1 - i) * 4)) and 0xF).toInt()]
        }

        override fun onDraw(c: Canvas) {
            if (cw == 0f) return
            if (row < 0) { drawHeader(c); return }
            val base = row * bpr
            paint.color = cOff
            hexInto(base, offDigits)
            cells(c, offDigits, pad)
            for (i in 0 until bpr) {
                val off = base + i
                if (off >= doc.size) break
                val v = doc.get(off)
                val hx = hexX + i * byteW
                val ax = asciiX + i * cw
                if (off == cursor) {
                    fill.color = cCursor
                    c.drawRect(hx - gap / 2, 0f, hx + cw * 2 + gap / 2, rowH.toFloat(), fill)
                    c.drawRect(ax, 0f, ax + cw, rowH.toFloat(), fill)
                    fill.color = cAccent
                    val nx = hx + if (lowNibble) cw else 0f
                    c.drawRect(nx, rowH - Ui.dp(context, 2).toFloat(), nx + cw, rowH.toFloat(), fill)
                }
                paint.color = when {
                    doc.isPatched(off) -> cMod
                    v == 0 -> cDim
                    else -> cText
                }
                chars[0] = HEX[v shr 4]; chars[1] = HEX[v and 0xF]
                cells(c, 2, hx)
                if (v !in 0x20..0x7E) paint.color = cDim
                chars[0] = if (v in 0x20..0x7E) v.toChar() else '.'
                cells(c, 1, ax)
            }
        }

        /** Draw chars[0 until n], each centered in its own [cw]-wide cell starting at [x]. */
        private fun cells(c: Canvas, n: Int, x: Float) {
            paint.textAlign = Paint.Align.CENTER
            for (k in 0 until n) {
                val ch = chars[k].code
                val w = if (ch < 128) glyphW[ch] else cw
                paint.textScaleX = if (w > cw) cw / w else 1f
                c.drawText(chars, k, 1, x + k * cw + cw / 2, textBase, paint)
            }
            paint.textScaleX = 1f
            paint.textAlign = Paint.Align.LEFT
        }

        private fun drawHeader(c: Canvas) {
            paint.color = cOff
            val label = if (paint.measureText("Offset") <= offDigits * cw + colGap * 0.6f) "Offset" else "Off"
            c.drawText(label, pad, textBase, paint)
            for (i in 0 until bpr) {
                chars[0] = HEX[(i shr 4) and 0xF]; chars[1] = HEX[i and 0xF]
                cells(c, 2, hexX + i * byteW)
            }
            c.drawText("ASCII", asciiX, textBase, paint)
        }
    }

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()

        const val M_SAVE = 1
        const val M_UNDO = 2
        const val M_GOTO = 3
        const val M_FIND = 4
        const val M_FIND_NEXT = 5
        const val M_TEXT = 6
        const val M_KEYPAD = 7
        const val M_EDITOR = 8
    }
}
