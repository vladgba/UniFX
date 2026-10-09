package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

/** Built-in text/code editor module. */
object TextModule : FileOpener {
    override val title = R.string.text_editor
    override fun canOpen(f: File) = Syntax.isText(f)
    override fun open(a: Activity, f: File) {
        // Big or non-writable files open in the paged read-only viewer; everything else in the editor.
        if (!f.canWrite() || f.length() > EDIT_MAX_BYTES) ViewerModule.open(a, f)
        else Openers.launch(a, EditorActivity::class.java, f)
    }
}

class EditorActivity : Activity() {
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences
    private lateinit var edit: CodeEditText
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var replaceInput: EditText
    private lateinit var history: History

    private val ui = Handler(Looper.getMainLooper())
    private var highlighter: Highlighter? = null
    private var lang: Lang? = null
    private var colors = Syntax.palette(false)
    private var charset: Charset = Charsets.UTF_8
    private var bom = false
    private var crlf = false
    private var loaded = false
    private var dirty = false
        set(v) { if (field != v) { field = v; updateTitle(); refreshHeader() } }
    private var fontSp = 14f
    private var wrap = false
    private var highlightOn = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        prefs = getSharedPreferences("unifx", MODE_PRIVATE)
        fontSp = prefs.getFloat("ed_font", 14f)
        wrap = prefs.getBoolean("ed_wrap", false)
        highlightOn = prefs.getBoolean("hl", true)
        lang = Syntax.langFor(file)
        colors = Syntax.palette(Ui.isNight(this))
        setContentView(buildUi())
        updateTitle()
        refreshHeader()
        load()
    }

    private fun buildUi(): View {
        edit = CodeEditText(this).apply {
            setFontSp(fontSp)
            setWrap(wrap)
            lineNumbers = prefs.getBoolean("ed_lines", true)
            showInvisibles = prefs.getBoolean("invis", false)
            isEnabled = false // until loaded
            onScroll = { highlighter?.schedule(80) }
        }
        findInput = EditText(this).apply { setSingleLine(); setHint(R.string.find_hint) }
        replaceInput = EditText(this).apply { setSingleLine(); setHint(R.string.replace_hint) }
        findInput.setOnEditorActionListener { _, _, _ -> find(true); true }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v ->
                addView(v, if (i == 0) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Ui.dp(context, 8), 0, 0, 0)
            addView(row(findInput, Ui.button(context, "▲") { find(false) }, Ui.button(context, "▼") { find(true) },
                Ui.button(context, "✕") { visibility = View.GONE }))
            addView(row(replaceInput, Ui.button(context, getString(R.string.replace)) { replaceOne() },
                Ui.button(context, getString(R.string.replace_all)) { replaceAll() }))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(findBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this)
        }
    }

    // ---------------------------------------------------------------- compact header
    //
    // Row 1: back + file name (small, bold) over info line + undo/redo. Row 2: even row of icons; ⋮ holds the rest.

    private lateinit var nameView: TextView
    private lateinit var infoView: TextView
    private val buttons = HashMap<Int, ImageView>()

    private fun buildHeader(): View {
        fun dp(v: Int) = Ui.dp(this, v)
        val primary = TypedValue().also { theme.resolveAttribute(android.R.attr.colorPrimary, it, true) }.data
        nameView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        infoView = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameView)
            addView(infoView)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_back, M_BACK, R.string.back), LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(2) })
            // Undo / redo sit beside the name + info lines, spanning both.
            addView(iconButton(R.drawable.ic_undo, M_UNDO, R.string.undo), LinearLayout.LayoutParams(dp(44), dp(40)))
            addView(iconButton(R.drawable.ic_redo, M_REDO, R.string.redo), LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                Triple(R.drawable.ic_find, M_FIND, R.string.find),
                Triple(R.drawable.ic_text_smaller, M_SMALLER, R.string.font_smaller),
                Triple(R.drawable.ic_text_bigger, M_BIGGER, R.string.font_bigger),
                Triple(R.drawable.ic_invisibles, M_INVISIBLES, R.string.show_invisibles),
                Triple(R.drawable.ic_save, M_SAVE, R.string.save),
                Triple(R.drawable.ic_more, M_MORE, R.string.more),
            ).forEach { (icon, id, label) -> addView(iconButton(icon, id, label), LinearLayout.LayoutParams(0, dp(40), 1f)) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
            setPadding(dp(2), dp(2), dp(2), 0)
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(icons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Edge-to-edge: the header color runs under the status bar, content starts below it.
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(v.paddingLeft, Ui.topInset(insets) + dp(2), v.paddingRight, 0)
                insets
            }
        }
    }

    private fun iconButton(icon: Int, id: Int, label: Int) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        setBackgroundResource(Ui.attrRes(context, android.R.attr.selectableItemBackgroundBorderless))
        contentDescription = getString(label)
        setOnClickListener { onAction(id, this) }
        setOnLongClickListener { Toast.makeText(context, contentDescription, Toast.LENGTH_SHORT).show(); true }
        buttons[id] = this
    }

    /** Enabled/dimmed state of the header icons. */
    private fun refreshHeader() {
        if (!::edit.isInitialized) return
        fun state(id: Int, enabled: Boolean, on: Boolean = true) {
            buttons[id]?.apply { isEnabled = enabled; imageAlpha = if (enabled && on) 255 else 100 }
        }
        val h = ::history.isInitialized
        state(M_SAVE, loaded && dirty)
        state(M_UNDO, loaded && h && history.canUndo)
        state(M_REDO, loaded && h && history.canRedo)
        state(M_INVISIBLES, true, edit.showInvisibles)
    }

    private fun showMore(anchor: View) {
        val p = PopupMenu(this, anchor)
        val m = p.menu
        m.add(0, M_GOTO, 0, R.string.go_to_line)
        m.add(0, M_WRAP, 1, R.string.word_wrap).apply { isCheckable = true; isChecked = wrap }
        m.add(0, M_LINES, 2, R.string.line_numbers).apply { isCheckable = true; isChecked = edit.lineNumbers }
        if (lang != null) m.add(0, M_HIGHLIGHT, 3, R.string.highlight_code).apply { isCheckable = true; isChecked = highlightOn }
        m.add(0, M_HEX, 4, R.string.open_in_hex)
        p.setOnMenuItemClickListener { onAction(it.itemId, anchor); true }
        p.show()
    }

    private fun updateTitle() {
        if (!::nameView.isInitialized) return
        nameView.text = (if (dirty) "• " else "") + file.name
        val parts = ArrayList<String>()
        parts += lang?.name ?: "Text"
        parts += charset.name().replace("ISO-8859-1", "Latin-1") + if (bom) " BOM" else ""
        parts += if (crlf) "CRLF" else "LF"
        if (!file.canWrite()) parts += getString(R.string.read_only)
        infoView.text = parts.joinToString(" · ")
    }

    private fun toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- load / save

    private fun load() {
        val len = file.length()
        if (len > EDIT_MAX_BYTES) {
            toast(getString(R.string.too_large, Formatter.formatShortFileSize(this, len)))
            ViewerModule.open(this, file)
            finish()
            return
        }
        Thread {
            val bytes = try { file.readBytes() } catch (e: Exception) { null }
            ui.post {
                if (isFinishing) return@post
                when {
                    bytes == null -> { toast(R.string.cannot_open); finish() }
                    looksBinary(bytes) -> AlertDialog.Builder(this)
                        .setTitle(R.string.binary_title)
                        .setItems(arrayOf(getString(R.string.hex_editor), getString(R.string.text_viewer), getString(R.string.open_anyway))) { _, i ->
                            when (i) {
                                0 -> { HexModule.open(this, file); finish() }
                                1 -> { ViewerModule.open(this, file); finish() }
                                else -> show(bytes)
                            }
                        }
                        .setOnCancelListener { finish() }
                        .show()
                    else -> show(bytes)
                }
            }
        }.start()
    }


    private fun looksBinary(b: ByteArray): Boolean {
        for (i in 0 until min(b.size, 8192)) if (b[i] == 0.toByte()) return true
        return false
    }

    private fun show(bytes: ByteArray) {
        var text = decode(bytes)
        crlf = text.contains("\r\n")
        if (crlf) text = text.replace("\r\n", "\n")
        edit.setText(text)
        edit.isEnabled = true
        edit.setSelection(0)
        history = History(edit) { dirty = true; refreshHeader() }
        edit.addTextChangedListener(history)
        if (highlightOn) setHighlight(true)
        loaded = true
        updateTitle()
        refreshHeader()
        edit.post { highlighter?.schedule(0) }
    }

    /** UTF-8 when the bytes are valid UTF-8, otherwise Latin-1 (which round-trips any byte unchanged). */
    private fun decode(b: ByteArray): String {
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) { bom = true; off = 3 }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, b.size - off)).toString()
        } catch (e: CharacterCodingException) {
            charset = Charsets.ISO_8859_1
            bom = false
            String(b, Charsets.ISO_8859_1)
        }
    }

    private fun encode(): ByteArray {
        var s = edit.text.toString()
        if (crlf) s = s.replace("\n", "\r\n")
        val body = s.toByteArray(charset)
        return if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body
    }

    private fun save(then: (() -> Unit)? = null) {
        if (!loaded) return
        val bytes = encode()
        Thread {
            val ok = FileOps.writeAtomic(file, bytes)
            ui.post {
                if (ok) { dirty = false; toast(R.string.saved); then?.invoke() } else toast(R.string.failed)
            }
        }.start()
    }

    /** Attach or detach the async highlighter; when off, all color spans are removed. */
    private fun setHighlight(on: Boolean) {
        highlighter?.let { edit.removeTextChangedListener(it); it.shutdown() }
        highlighter = null
        val l = lang
        if (on && l != null) {
            highlighter = Highlighter(edit, l, colors).also {
                edit.addTextChangedListener(it)
                it.schedule(0)
            }
        } else {
            val e = edit.text
            for (sp in e.getSpans(0, e.length, Hl::class.java)) e.removeSpan(sp)
        }
    }

    // ---------------------------------------------------------------- find / replace / go to

    private fun showFind() {
        findBar.visibility = View.VISIBLE
        val sel = edit.text.subSequence(edit.selectionStart, edit.selectionEnd).toString()
        if (sel.isNotEmpty() && '\n' !in sel) findInput.setText(sel)
        findInput.requestFocus()
        findInput.selectAll()
    }

    private fun find(forward: Boolean): Boolean {
        val q = findInput.text.toString()
        if (q.isEmpty()) return false
        val t = edit.text.toString()
        val i = if (forward) {
            t.indexOf(q, edit.selectionEnd, true).let { if (it < 0) t.indexOf(q, 0, true) else it }
        } else {
            val from = edit.selectionStart - 1
            (if (from >= 0) t.lastIndexOf(q, from, true) else -1).let { if (it < 0) t.lastIndexOf(q, t.length, true) else it }
        }
        if (i < 0) { toast(R.string.not_found); return false }
        edit.requestFocus()
        edit.setSelection(i, i + q.length)
        return true
    }

    private fun replaceOne() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val s = edit.selectionStart
        val e = edit.selectionEnd
        if (e - s == q.length && edit.text.subSequence(s, e).toString().equals(q, true)) {
            edit.text.replace(s, e, replaceInput.text)
        }
        find(true)
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val t = edit.text.toString()
        val m = Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).matcher(t)
        var n = 0
        while (m.find()) n++
        if (n > 0) {
            val out = m.reset().replaceAll(Matcher.quoteReplacement(replaceInput.text.toString()))
            edit.text.replace(0, edit.text.length, out) // one undo step
        }
        toast(getString(R.string.replaced, n))
    }

    private fun goToLine() {
        Ui.input(this, getString(R.string.go_to_line), "", InputType.TYPE_CLASS_NUMBER, android.R.string.ok, { s ->
            val target = s.toIntOrNull() ?: return@input
            val t = edit.text
            var line = 1
            var off = 0
            while (line < target) {
                val nl = TextUtils.indexOf(t, '\n', off)
                if (nl < 0) break
                off = nl + 1
                line++
            }
            edit.requestFocus()
            edit.setSelection(off)
        })
    }

    // ---------------------------------------------------------------- actions (header icons and ⋮ menu)

    private fun onAction(id: Int, anchor: View) {
        if (!loaded && id != M_BACK) return
        when (id) {
            M_BACK -> onBackPressed()
            M_MORE -> showMore(anchor)
            M_SAVE -> save()
            M_UNDO -> history.undo()
            M_REDO -> history.redo()
            M_FIND -> showFind()
            M_GOTO -> goToLine()
            M_WRAP -> { wrap = !wrap; edit.setWrap(wrap); prefs.edit().putBoolean("ed_wrap", wrap).apply(); highlighter?.schedule(50) }
            M_LINES -> { edit.lineNumbers = !edit.lineNumbers; prefs.edit().putBoolean("ed_lines", edit.lineNumbers).apply() }
            M_HIGHLIGHT -> {
                highlightOn = !highlightOn
                prefs.edit().putBoolean("hl", highlightOn).apply()
                setHighlight(highlightOn)
            }
            M_INVISIBLES -> {
                edit.showInvisibles = !edit.showInvisibles
                prefs.edit().putBoolean("invis", edit.showInvisibles).apply()
                refreshHeader()
            }
            M_BIGGER, M_SMALLER -> {
                fontSp = (fontSp + if (id == M_BIGGER) 2f else -2f).coerceIn(8f, 32f)
                edit.setFontSp(fontSp)
                prefs.edit().putFloat("ed_font", fontSp).apply()
                highlighter?.schedule(50)
            }
            M_HEX -> if (dirty) {
                Ui.confirmUnsaved(this, file.name, { save { HexModule.open(this, file); finish() } },
                    { HexModule.open(this, file); finish() })
            } else { HexModule.open(this, file); finish() }
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
            dirty -> Ui.confirmUnsaved(this, file.name, { save { finish() } }, { finish() })
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        highlighter?.shutdown()
        ui.removeCallbacksAndMessages(null)
    }

    /** Undo/redo over raw text edits; consecutive single-character typing merges into one step. */
    private class History(private val et: EditText, private val onEdit: () -> Unit) : TextWatcher {
        private class Op(val start: Int, val before: String, val after: String)

        private val undo = ArrayDeque<Op>()
        private val redo = ArrayDeque<Op>()
        private var muted = false
        private var pending = ""

        val canUndo get() = undo.isNotEmpty()
        val canRedo get() = redo.isNotEmpty()

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (!muted) pending = s.subSequence(start, start + count).toString()
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (muted) return
            val after = s.subSequence(start, start + count).toString()
            val last = undo.lastOrNull()
            if (last != null && pending.isEmpty() && last.before.isEmpty() && count == 1 && after != "\n" &&
                last.start + last.after.length == start && last.after.length < 64
            ) {
                undo[undo.lastIndex] = Op(last.start, "", last.after + after)
            } else {
                undo.addLast(Op(start, pending, after))
                if (undo.size > LIMIT) undo.removeFirst()
            }
            redo.clear()
        }

        override fun afterTextChanged(s: Editable) { onEdit() }

        // Move the op between stacks *before* touching the text: apply() fires onEdit, which refreshes the
        // header buttons, and they must already see the updated stacks.
        fun undo() {
            val op = undo.removeLastOrNull() ?: return
            redo.addLast(op)
            apply(op.start, op.after.length, op.before)
        }

        fun redo() {
            val op = redo.removeLastOrNull() ?: return
            undo.addLast(op)
            apply(op.start, op.before.length, op.after)
        }

        private fun apply(start: Int, len: Int, text: String) {
            muted = true
            et.text.replace(start, start + len, text)
            muted = false
            et.setSelection(start + text.length)
            onEdit()
        }

        companion object { const val LIMIT = 500 }
    }

    private companion object {

        const val M_SAVE = 1
        const val M_UNDO = 2
        const val M_REDO = 3
        const val M_FIND = 4
        const val M_GOTO = 5
        const val M_WRAP = 6
        const val M_LINES = 7
        const val M_BIGGER = 8
        const val M_SMALLER = 9
        const val M_HEX = 10
        const val M_HIGHLIGHT = 11
        const val M_INVISIBLES = 12
        const val M_BACK = 13
        const val M_MORE = 14
    }
}
