package x.vladgba.fileman

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * Search the current folder and everything below it by name and/or by text inside files (see [SearchOps]).
 * Results stream in while the walk runs and it can be stopped at any time. Tap a result to open it (a folder
 * opens in the browser); long-press to show it in its folder.
 */
class SearchActivity : Activity() {
    private lateinit var root: File
    private lateinit var prefs: SharedPreferences
    private lateinit var nameInput: EditText
    private lateinit var nameRegex: CheckBox
    private lateinit var contentInput: EditText
    private lateinit var contentRegex: CheckBox
    private lateinit var matchCase: CheckBox
    private lateinit var hidden: CheckBox
    private lateinit var goButton: Button
    private lateinit var status: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val results = ArrayList<SearchOps.Hit>()
    private val pending = ArrayList<SearchOps.Hit>() // filled by the search thread, drained on the UI thread
    @Volatile private var cancelled = false
    @Volatile private var scanned = 0
    private var running = false
    @Volatile private var generation = 0 // bumped per run; an older run's thread sees the change and stops
    @Volatile private var truncated = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: Storage.primary().path)
        prefs = getSharedPreferences("unifx", MODE_PRIVATE)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setTitle(R.string.search_title)
        actionBar?.subtitle = root.path
        setContentView(buildUi())
        intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { nameInput.setText(it) }
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        ui.removeCallbacksAndMessages(null)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi(): View {
        val onGo = TextView.OnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { start(); true } else false
        }
        fun input(hint: Int, key: String) = EditText(this).apply {
            setSingleLine()
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(prefs.getString(key, ""))
            setOnEditorActionListener(onGo)
        }
        fun check(label: Int, key: String) = CheckBox(this).apply {
            setText(label)
            isChecked = prefs.getBoolean(key, false)
        }
        nameInput = input(R.string.search_name_hint, "s_name")
        contentInput = input(R.string.search_content_hint, "s_content")
        nameRegex = check(R.string.regex_short, "s_name_re")
        contentRegex = check(R.string.regex_short, "s_content_re")
        matchCase = check(R.string.match_case, "s_case")
        hidden = check(R.string.search_hidden, "s_hidden")
        goButton = Button(this).apply {
            setText(R.string.search_go)
            setOnClickListener { if (running) stop() else start() }
        }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.75f
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun row(vararg v: View, firstWeight: Boolean = true) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            v.forEachIndexed { i, view ->
                addView(view, if (i == 0 && firstWeight) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(row(nameInput, nameRegex))
            addView(row(contentInput, contentRegex))
            addView(row(matchCase, hidden, firstWeight = false))
            addView(row(status, goButton))
        }
        val list = ListView(this).apply {
            adapter = this@SearchActivity.adapter
            isFastScrollEnabled = true
            setOnItemClickListener { _, _, pos, _ -> open(results[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showInFolder(results[pos]); true }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(form, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        }
    }

    private fun updateStatus() {
        val n = results.size
        status.text = when {
            running -> getString(R.string.search_running, scanned, resources.getQuantityString(R.plurals.search_found, n, n))
            generation == 0 -> getString(R.string.search_idle)
            else -> getString(R.string.search_done, resources.getQuantityString(R.plurals.search_found, n, n), scanned) +
                if (truncated) " " + getString(R.string.search_truncated, MAX_RESULTS) else ""
        }
        goButton.setText(if (running) R.string.search_stop else R.string.search_go)
    }

    // ---------------------------------------------------------------- searching

    private fun start() {
        val q = SearchOps.Query(
            nameInput.text.toString(), nameRegex.isChecked,
            contentInput.text.toString(), contentRegex.isChecked,
            matchCase.isChecked, hidden.isChecked,
        )
        if (q.name.isBlank() && q.content.isEmpty()) { toast(R.string.search_need_input); return }
        val matcher = try { SearchOps.Matcher(q) } catch (e: PatternSyntaxException) {
            toast(getString(R.string.rename_bad_pattern, e.description)); return
        }
        prefs.edit()
            .putString("s_name", q.name).putBoolean("s_name_re", q.nameRegex)
            .putString("s_content", q.content).putBoolean("s_content_re", q.contentRegex)
            .putBoolean("s_case", q.matchCase).putBoolean("s_hidden", q.includeHidden)
            .apply()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(status.windowToken, 0)

        val gen = ++generation // a previous run stops at its next check, and its results are dropped
        val stop = BooleanArray(1)
        cancelled = false
        results.clear()
        synchronized(pending) { pending.clear() }
        truncated = false
        scanned = 0
        running = true
        adapter.notifyDataSetChanged()
        updateStatus()
        scheduleDrain(gen)
        Thread {
            var found = 0
            SearchOps.search(root, matcher, { cancelled || gen != generation || stop[0] }, { scanned = it }) { hit ->
                synchronized(pending) { pending += hit }
                if (++found >= MAX_RESULTS) { stop[0] = true; truncated = true }
            }
            ui.post {
                if (gen != generation || isDestroyed) return@post
                running = false
                drain()
                updateStatus()
            }
        }.start()
    }

    private fun stop() {
        cancelled = true
    }

    /** Move results from the search thread to the list a few times a second, not once per hit. */
    private fun scheduleDrain(gen: Int) {
        ui.postDelayed({
            if (gen != generation || isDestroyed) return@postDelayed
            drain()
            updateStatus()
            if (running) scheduleDrain(gen)
        }, 150)
    }

    private fun drain() {
        val batch = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        if (batch.isEmpty()) return
        results += batch
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- results

    private fun open(hit: SearchOps.Hit) {
        if (hit.isDir) navigateTo(hit.file) else Openers.open(this, hit.file)
    }

    private fun showInFolder(hit: SearchOps.Hit) {
        navigateTo(hit.file.parentFile ?: return)
    }

    /** Back to the browser, which opens [dir]. */
    private fun navigateTo(dir: File) {
        setResult(RESULT_OK, Intent().putExtra(Openers.EXTRA_PATH, dir.path))
        finish()
    }

    private fun toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: newRow()
            val hit = results[position]
            val name = row.getChildAt(0) as TextView
            val detail = row.getChildAt(1) as TextView
            name.text = if (hit.isDir) hit.file.name + "/" else hit.file.name
            val dir = hit.file.parentFile?.let { relative(it) } ?: ""
            detail.text = if (hit.snippet != null) "$dir : ${hit.line}  ${hit.snippet}" else dir
            return row
        }

        private fun newRow() = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    /** Folder path relative to the search root ("." for the root itself). */
    private fun relative(dir: File): String {
        val r = root.path.trimEnd('/')
        return when {
            dir.path == r || dir.path == root.path -> "."
            dir.path.startsWith("$r/") -> dir.path.substring(r.length + 1)
            else -> dir.path
        }
    }

    companion object {
        const val EXTRA_NAME = "x.vladgba.fileman.SEARCH_NAME"
        const val MAX_RESULTS = 5000
    }
}
