package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: FileAdapter
    private lateinit var list: GridView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var banner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val scrollMemory = HashMap<String, Int>()
    private var loadToken = 0
    private var busyCount = 0
    private var shownDir: File? = null
    private var actionMode: ActionMode? = null
    private var searchItem: MenuItem? = null

    private var roots: List<Storage.Root> = emptyList()
    private var cwd: File = File("/")
    private var clip: List<File> = emptyList()
    private var clipMove = false
    private var sortMode = SORT_NAME
    private var sortDesc = false
    private var showHidden = false
    private var gridMode = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("unifx", MODE_PRIVATE)
        sortMode = prefs.getInt("sort", SORT_NAME)
        sortDesc = prefs.getBoolean("desc", false)
        showHidden = prefs.getBoolean("hidden", false)
        gridMode = prefs.getBoolean("grid", false)
        adapter = FileAdapter(this)
        setContentView(buildUi())
        applyViewMode()
        roots = Storage.roots(this)
        val start = state?.getString("cwd") ?: prefs.getString("cwd", null)
        cwd = start?.let(::File)?.takeIf { it.isDirectory } ?: Storage.primary()
    }

    override fun onResume() {
        super.onResume()
        roots = Storage.roots(this)
        val ok = Storage.hasAccess(this)
        banner.visibility = if (ok) View.GONE else View.VISIBLE
        load() // also picks up changes made by other apps while we were away
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString("cwd", cwd.path).apply()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("cwd", cwd.path)
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
        worker.shutdown() // let a running paste finish
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        onResume()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val parent = cwd.parentFile
        if (parent != null && roots.none { it.dir == cwd }) navigate(parent) else super.onBackPressed()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun attrRes(attr: Int): Int = Ui.attrRes(this, attr)

    private fun buildUi(): View {
        banner = TextView(this).apply {
            setText(R.string.grant_access)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFFFE082.toInt())
            setTextColor(0xFF202020.toInt())
            visibility = View.GONE
            setOnClickListener { Storage.request(this@MainActivity) }
        }
        crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        emptyView = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            alpha = 0.6f
        }
        list = GridView(this).apply {
            isFastScrollEnabled = true
            clipToPadding = false
            choiceMode = AbsListView.CHOICE_MODE_MULTIPLE_MODAL
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val e = this@MainActivity.adapter.getItem(pos)
                if (e.isDir) navigate(e.file) else Openers.open(this@MainActivity, e.file)
            }
            setMultiChoiceModeListener(selectionCallback)
        }
        val frame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        list.emptyView = emptyView

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(crumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this)
        }
    }

    /** One GridView serves both modes: a single stretched column is the list, auto-fit tiles are the grid. */
    private fun applyViewMode() {
        val pos = list.firstVisiblePosition
        actionMode?.finish()
        if (gridMode) {
            list.numColumns = GridView.AUTO_FIT
            list.columnWidth = dp(104)
            list.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            list.horizontalSpacing = dp(4)
            list.verticalSpacing = dp(4)
            list.setPadding(dp(4), dp(4), dp(4), dp(4))
        } else {
            list.numColumns = 1
            list.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            list.horizontalSpacing = 0
            list.verticalSpacing = 0
            list.setPadding(0, 0, 0, 0)
        }
        adapter.grid = gridMode
        list.setSelection(pos)
    }

    private fun renderCrumbs() {
        val chain = ArrayList<Pair<String, File>>()
        var d: File? = cwd
        while (d != null) {
            val root = roots.firstOrNull { it.dir == d && it.dir.path != "/" }
            if (root != null) { chain += root.label to d; break }
            chain += (if (d.path == "/") "/" else d.name) to d
            d = d.parentFile
        }
        chain.reverse()
        crumbs.removeAllViews()
        chain.forEachIndexed { i, (label, dir) ->
            if (i > 0) crumbs.addView(TextView(this).apply { text = "›"; alpha = 0.5f })
            crumbs.addView(TextView(this).apply {
                text = label
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundResource(attrRes(android.R.attr.selectableItemBackground))
                if (i == chain.lastIndex) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { if (dir != cwd) navigate(dir) }
            })
        }
        actionBar?.title = chain.last().first
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun busy(on: Boolean) {
        busyCount += if (on) 1 else -1
        progress.visibility = if (busyCount > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun toast(msg: CharSequence) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- navigation & loading

    private fun navigate(dir: File) {
        scrollMemory[cwd.path] = list.firstVisiblePosition
        actionMode?.finish()
        searchItem?.collapseActionView()
        cwd = dir
        load()
    }

    private fun load() {
        val dir = cwd
        val token = ++loadToken
        val hidden = showHidden
        val cmp = comparator()
        renderCrumbs()
        busy(true)
        loader.execute {
            val entries = dir.listFiles()
                ?.filter { hidden || !it.name.startsWith(".") }
                ?.map(::Entry)
                ?.sortedWith(cmp)
            ui.post {
                busy(false)
                if (token != loadToken || isFinishing) return@post
                val pos = scrollMemory.remove(dir.path) ?: if (shownDir == dir) list.firstVisiblePosition else 0
                actionMode?.finish()
                adapter.setData(entries ?: emptyList())
                shownDir = dir
                emptyView.setText(if (entries == null) R.string.cannot_read else R.string.empty)
                actionBar?.subtitle = if (entries == null) null else getString(R.string.items, entries.size)
                list.setSelection(pos)
            }
        }
    }

    private fun comparator(): Comparator<Entry> {
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { e: Entry -> e.name }
        var c: Comparator<Entry> = when (sortMode) {
            SORT_SIZE -> compareBy<Entry> { it.size }.then(byName)
            SORT_DATE -> compareBy<Entry> { it.modified }.then(byName)
            SORT_TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { e: Entry -> e.ext }.then(byName)
            else -> byName
        }
        if (sortDesc) c = c.reversed()
        return compareBy<Entry> { !it.isDir }.then(c) // folders always on top
    }

    // ---------------------------------------------------------------- options menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        searchItem = menu.add(0, M_SEARCH, 0, R.string.search).apply {
            setIcon(android.R.drawable.ic_menu_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            actionView = SearchView(this@MainActivity).apply {
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(q: String) = false
                    override fun onQueryTextChange(q: String): Boolean {
                        actionMode?.finish()
                        adapter.filter(q)
                        return true
                    }
                })
            }
            setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem) = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean { adapter.filter(""); return true }
            })
        }
        menu.add(0, M_PASTE, 1, "").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, M_NEW_DIR, 2, R.string.new_folder)
        menu.add(0, M_NEW_FILE, 3, R.string.new_file)
        menu.addSubMenu(0, M_SORT, 4, R.string.sort).apply {
            add(1, M_SORT_NAME, 0, R.string.sort_name)
            add(1, M_SORT_SIZE, 1, R.string.sort_size)
            add(1, M_SORT_DATE, 2, R.string.sort_date)
            add(1, M_SORT_TYPE, 3, R.string.sort_type)
            setGroupCheckable(1, true, true)
            add(2, M_DESC, 4, R.string.descending).isCheckable = true
        }
        menu.add(0, M_VIEW, 5, "")
        menu.add(0, M_HIDDEN, 5, R.string.show_hidden).isCheckable = true
        menu.addSubMenu(0, M_STORAGE, 6, R.string.storage)
        menu.add(0, M_REFRESH, 7, R.string.refresh)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_PASTE).apply {
            isVisible = clip.isNotEmpty()
            title = getString(R.string.paste, clip.size)
        }
        menu.findItem(M_SORT_NAME + sortMode).isChecked = true
        menu.findItem(M_DESC).isChecked = sortDesc
        menu.findItem(M_HIDDEN).isChecked = showHidden
        menu.findItem(M_VIEW).setTitle(if (gridMode) R.string.view_list else R.string.view_grid)
        menu.findItem(M_STORAGE).subMenu?.apply {
            clear()
            roots.forEachIndexed { i, r -> add(0, M_ROOT_BASE + i, i, r.label) }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when {
            id == M_PASTE -> paste()
            id == M_NEW_DIR -> prompt(R.string.new_folder, "") { name ->
                val f = File(cwd, name)
                when {
                    f.exists() -> toast(R.string.exists)
                    !f.mkdir() -> toast(R.string.failed)
                    else -> load()
                }
            }
            id == M_NEW_FILE -> prompt(R.string.new_file, "") { name ->
                val f = File(cwd, name)
                val ok = try { f.createNewFile() } catch (e: IOException) { false }
                if (f.exists() && !ok) toast(R.string.exists) else if (!ok) toast(R.string.failed) else load()
            }
            id in M_SORT_NAME..M_SORT_TYPE -> { sortMode = id - M_SORT_NAME; savePrefs(); load() }
            id == M_DESC -> { sortDesc = !sortDesc; savePrefs(); load() }
            id == M_VIEW -> {
                gridMode = !gridMode
                prefs.edit().putBoolean("grid", gridMode).apply()
                applyViewMode()
            }
            id == M_HIDDEN -> { showHidden = !showHidden; savePrefs(); load() }
            id == M_REFRESH -> load()
            id >= M_ROOT_BASE -> roots.getOrNull(id - M_ROOT_BASE)?.let { navigate(it.dir) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun savePrefs() {
        prefs.edit().putInt("sort", sortMode).putBoolean("desc", sortDesc).putBoolean("hidden", showHidden).apply()
    }

    // ---------------------------------------------------------------- selection (long-press)

    private fun selected(): List<Entry> {
        val c = list.checkedItemPositions ?: return emptyList()
        return (0 until c.size()).filter { c.valueAt(it) }.map { adapter.getItem(c.keyAt(it)) }
    }

    private val selectionCallback = object : AbsListView.MultiChoiceModeListener {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            menu.add(0, A_COPY, 0, R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_CUT, 1, R.string.cut).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_DELETE, 2, R.string.delete).setIcon(android.R.drawable.ic_menu_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_ALL, 3, R.string.select_all)
            menu.add(0, A_RENAME, 4, R.string.rename)
            menu.add(0, A_SHARE, 5, R.string.share)
            menu.add(0, A_OPEN_WITH, 6, R.string.open_with)
            menu.add(0, A_INFO, 7, R.string.properties)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val sel = selected()
            val one = sel.size == 1
            menu.findItem(A_RENAME).isVisible = one
            menu.findItem(A_INFO).isVisible = one
            menu.findItem(A_OPEN_WITH).isVisible = one && !sel[0].isDir
            menu.findItem(A_SHARE).isVisible = sel.isNotEmpty() && sel.none { it.isDir }
            return true
        }

        override fun onItemCheckedStateChanged(mode: ActionMode, pos: Int, id: Long, checked: Boolean) {
            mode.title = list.checkedItemCount.toString()
            mode.invalidate()
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val sel = selected()
            if (item.itemId == A_ALL) {
                for (i in 0 until adapter.count) list.setItemChecked(i, true)
                return true
            }
            mode.finish()
            if (sel.isEmpty()) return true
            when (item.itemId) {
                A_COPY, A_CUT -> {
                    clip = sel.map { it.file }
                    clipMove = item.itemId == A_CUT
                    toast(getString(R.string.copied, clip.size))
                    invalidateOptionsMenu()
                }
                A_DELETE -> confirmDelete(sel.map { it.file })
                A_RENAME -> rename(sel[0].file)
                A_SHARE -> Openers.share(this@MainActivity, sel.map { it.file })
                A_OPEN_WITH -> Openers.chooser(this@MainActivity, sel[0].file)
                A_INFO -> showInfo(sel[0].file)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) { actionMode = null }
    }

    // ---------------------------------------------------------------- file actions

    private fun prompt(title: Int, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(initial)
            val dot = initial.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else initial.length) // select name, not extension
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (FileOps.validName(name)) onOk(name) else toast(R.string.bad_name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE) }
            .show()
    }

    private fun rename(f: File) = prompt(R.string.rename, f.name) { name ->
        if (name == f.name) return@prompt
        val target = File(f.parentFile, name)
        when {
            target.exists() -> toast(R.string.exists)
            !f.renameTo(target) -> toast(R.string.failed)
            else -> load()
        }
    }

    private fun confirmDelete(files: List<File>) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, files.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                clip = clip - files.toSet()
                invalidateOptionsMenu()
                runOp { FileOps.deleteAll(files) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun paste() {
        val src = clip
        val move = clipMove
        val dest = cwd
        if (src.any { it.isDirectory && (dest.path + "/").startsWith(it.path + "/") }) {
            toast(R.string.into_itself)
            return
        }
        if (move) { clip = emptyList(); invalidateOptionsMenu() }
        runOp { FileOps.transfer(src, dest, move) }
    }

    /** Run a blocking op off the UI thread; [op] returns the number of failures. */
    private fun runOp(op: () -> Int) {
        busy(true)
        worker.execute {
            val failed = try { op() } catch (e: Exception) { -1 }
            ui.post {
                busy(false)
                if (isFinishing) return@post
                when {
                    failed < 0 -> toast(R.string.failed)
                    failed > 0 -> toast(getString(R.string.partly_failed, failed))
                    else -> toast(R.string.done)
                }
                load()
            }
        }
    }

    private fun showInfo(f: File) {
        busy(true)
        worker.execute {
            val (count, bytes) = FileOps.stats(f)
            val perms = (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-") + (if (f.canExecute()) "x" else "-")
            val text = buildString {
                append("Path\n").append(f.absolutePath).append("\n\n")
                append("Size\n").append(Formatter.formatFileSize(this@MainActivity, bytes)).append(" ($bytes bytes)\n\n")
                if (f.isDirectory) append("Contains\n").append(count).append(" files\n\n")
                else append("Type\n").append(Openers.mime(f)).append("\n\n")
                append("Modified\n").append(DateFormat.getDateTimeInstance().format(Date(f.lastModified()))).append("\n\n")
                append("Access\n").append(perms)
                if (FileOps.isLink(f)) append("  (symlink → ").append(f.canonicalPath).append(")")
            }
            ui.post {
                busy(false)
                if (!isFinishing) AlertDialog.Builder(this).setTitle(f.name).setMessage(text)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private companion object {
        const val SORT_NAME = 0
        const val SORT_SIZE = 1
        const val SORT_DATE = 2
        const val SORT_TYPE = 3

        const val M_SEARCH = 1
        const val M_PASTE = 2
        const val M_NEW_DIR = 3
        const val M_NEW_FILE = 4
        const val M_SORT = 5
        const val M_SORT_NAME = 10 // M_SORT_NAME + SORT_* must stay contiguous
        const val M_SORT_SIZE = 11
        const val M_SORT_DATE = 12
        const val M_SORT_TYPE = 13
        const val M_DESC = 14
        const val M_HIDDEN = 20
        const val M_STORAGE = 21
        const val M_REFRESH = 22
        const val M_VIEW = 23
        const val M_ROOT_BASE = 100

        const val A_COPY = 1
        const val A_CUT = 2
        const val A_DELETE = 3
        const val A_ALL = 4
        const val A_RENAME = 5
        const val A_SHARE = 6
        const val A_OPEN_WITH = 7
        const val A_INFO = 8
    }
}
