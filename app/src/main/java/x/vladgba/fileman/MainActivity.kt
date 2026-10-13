package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
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
    private var selecting = false
    private lateinit var normalIcons: LinearLayout
    private lateinit var selectIcons: LinearLayout
    private val selButtons = HashMap<Int, ImageView>()
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var pasteButton: ImageView
    private lateinit var viewButton: ImageView
    private lateinit var hiddenButton: ImageView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText

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
        adapter.isChecked = { pos -> selecting && list.isItemChecked(pos) }
        setContentView(buildUi())
        applyViewMode()
        refreshBar()
        roots = Storage.roots(this)
        val start = state?.getString("cwd") ?: prefs.getString("cwd", null)
        cwd = start?.let(::File)?.takeIf { it.isDirectory } ?: Storage.primary()
        // Copy/cut list survives the activity being rebuilt (theme switch, process death while in background).
        state?.getStringArrayList("clip")?.let { paths ->
            clip = paths.map(::File)
            clipMove = state.getBoolean("clipMove")
            refreshBar()
        }
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
        out.putStringArrayList("clip", ArrayList(clip.map { it.path }))
        out.putBoolean("clipMove", clipMove)
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
        if (selecting) { finishSelection(); return }
        if (searchRow.visibility == View.VISIBLE) { closeSearch(); return }
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
            setPadding(dp(4), 0, dp(4), 0)
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
            choiceMode = AbsListView.CHOICE_MODE_NONE
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                if (selecting) {
                    // CHOICE_MODE_MULTIPLE already toggled the item.
                    if (list.checkedItemCount == 0) finishSelection() else onSelectionChanged()
                    return@setOnItemClickListener
                }
                val e = this@MainActivity.adapter.getItem(pos)
                if (e.isDir) navigate(e.file) else Openers.open(this@MainActivity, e.file)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                if (!selecting) startSelection()
                list.setItemChecked(pos, !list.isItemChecked(pos))
                if (list.checkedItemCount == 0) finishSelection() else onSelectionChanged()
                true
            }
        }
        val frame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        list.emptyView = emptyView

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildTopLine(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))
            addView(buildBottomBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Edge-to-edge: keep clear of the status bar on top and the navigation bar / keyboard below.
            setOnApplyWindowInsetsListener { v, insets ->
                val top = Ui.topInset(insets)
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(i.left, top, i.right, i.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, top, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
    }

    // ---------------------------------------------------------------- top line + bottom bar
    //
    //   [ Docs · 12 items · 3 to paste                       ]   slim line at the top
    //   [ Internal › Download › Docs                         ]   breadcrumb
    //   ...list...
    //   [ search field (when open)                     ✕ ]
    //   [ 🔍  📁+  📄+  ⇅  ▦  👁  💾  ⟳  📋              ]   every action is an icon, within thumb reach

    private fun primaryColor() = TypedValue().also { theme.resolveAttribute(android.R.attr.colorPrimary, it, true) }.data

    private fun buildTopLine(): View {
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        infoView = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine()
            setPadding(dp(8), 0, 0, 0)
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(2))
            addView(titleView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0f))
            addView(infoView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primaryColor())
            addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(crumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(32)))
        }
    }

    private fun buildBottomBar(): View {
        searchInput = EditText(this).apply {
            setSingleLine()
            setHint(R.string.search)
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    finishSelection()
                    adapter.filter(s.toString())
                }
            })
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, 0, 0)
            addView(searchInput, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Deep search: subfolders and file contents, on its own screen.
            addView(Ui.button(context, getString(R.string.search_subfolders)) { openDeepSearch() }.apply {
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
            addView(barIcon(R.drawable.ic_close, R.string.close) { closeSearch() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        pasteButton = barIcon(R.drawable.ic_paste, R.string.paste_short) { paste() }.apply { visibility = View.GONE }
        viewButton = barIcon(R.drawable.ic_view_grid, R.string.view_grid) { onMenuItem(M_VIEW) }
        hiddenButton = barIcon(R.drawable.ic_hidden, R.string.show_hidden) { onMenuItem(M_HIDDEN) }
        normalIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                barIcon(R.drawable.ic_find, R.string.search) { openSearch() },
                barIcon(R.drawable.ic_new_folder, R.string.new_folder) { onMenuItem(M_NEW_DIR) },
                barIcon(R.drawable.ic_new_file, R.string.new_file) { onMenuItem(M_NEW_FILE) },
                barIcon(R.drawable.ic_sort, R.string.sort) { showSortMenu(it) },
                viewButton,
                hiddenButton,
                barIcon(R.drawable.ic_storage, R.string.storage) { showStorageMenu(it) },
                barIcon(R.drawable.ic_refresh, R.string.refresh) { onMenuItem(M_REFRESH) },
                pasteButton,
            ).forEach { addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        }
        // Selection actions replace the normal icons while items are selected (no top action bar).
        selectIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            listOf(
                Triple(A_CLOSE, R.drawable.ic_close, R.string.close),
                Triple(A_COPY, R.drawable.ic_copy, R.string.copy),
                Triple(A_CUT, R.drawable.ic_cut, R.string.cut),
                Triple(A_DELETE, R.drawable.ic_delete, R.string.delete),
                Triple(A_SHARE, R.drawable.ic_share, R.string.share),
                Triple(A_ALL, R.drawable.ic_select_all, R.string.select_all),
                Triple(A_RENAME, R.drawable.ic_rename, R.string.rename),
                Triple(A_OPEN_WITH, R.drawable.ic_open_with, R.string.open_with),
                Triple(A_INFO, R.drawable.ic_info, R.string.properties),
                Triple(A_MORE, R.drawable.ic_more, R.string.more), // zip / extract
            ).forEach { (id, icon, label) ->
                val b = barIcon(icon, label) { v -> onSelectionAction(id, v) }
                selButtons[id] = b
                addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primaryColor())
            addView(searchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(normalIcons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(selectIcons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun barIcon(icon: Int, label: Int, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        setBackgroundResource(attrRes(android.R.attr.selectableItemBackgroundBorderless))
        contentDescription = getString(label)
        setOnClickListener { onClick(it) }
        setOnLongClickListener { toast(contentDescription); true }
    }

    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        searchInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, 0)
    }

    private fun openDeepSearch() {
        val i = android.content.Intent(this, SearchActivity::class.java)
            .putExtra(Openers.EXTRA_PATH, cwd.path)
            .putExtra(SearchActivity.EXTRA_NAME, searchInput.text.toString())
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SEARCH)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SEARCH || resultCode != RESULT_OK) return
        val dir = data?.getStringExtra(Openers.EXTRA_PATH)?.let(::File)?.takeIf { it.isDirectory } ?: return
        navigate(dir)
    }

    private fun closeSearch() {
        if (searchRow.visibility != View.VISIBLE) return
        searchInput.setText("") // also clears the filter
        searchRow.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** Icon states: paste only with something to paste, view/hidden show their current setting. */
    private fun refreshBar() {
        if (!::pasteButton.isInitialized) return
        pasteButton.visibility = if (clip.isEmpty()) View.GONE else View.VISIBLE
        pasteButton.contentDescription = getString(R.string.paste, clip.size)
        // The view icon shows the mode you switch *to*.
        viewButton.setImageResource(if (gridMode) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        viewButton.contentDescription = getString(if (gridMode) R.string.view_list else R.string.view_grid)
        hiddenButton.imageAlpha = if (showHidden) 255 else 110
        updateInfo()
    }

    private var itemCount: Int? = null

    private fun updateInfo() {
        val parts = ArrayList<String>()
        itemCount?.let { parts += resources.getQuantityString(R.plurals.items, it, it) }
        if (clip.isNotEmpty()) parts += resources.getQuantityString(R.plurals.to_paste, clip.size, clip.size)
        infoView.text = parts.joinToString(" · ")
    }

    private fun showSortMenu(anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menu.apply {
            add(1, M_SORT_NAME, 0, R.string.sort_name)
            add(1, M_SORT_SIZE, 1, R.string.sort_size)
            add(1, M_SORT_DATE, 2, R.string.sort_date)
            add(1, M_SORT_TYPE, 3, R.string.sort_type)
            setGroupCheckable(1, true, true)
            findItem(M_SORT_NAME + sortMode).isChecked = true
            add(2, M_DESC, 4, R.string.descending).apply { isCheckable = true; isChecked = sortDesc }
        }
        p.setOnMenuItemClickListener { onMenuItem(it.itemId) }
        p.show()
    }

    private fun showStorageMenu(anchor: View) {
        val p = PopupMenu(this, anchor)
        roots.forEachIndexed { i, r -> p.menu.add(0, M_ROOT_BASE + i, i, r.label) }
        p.setOnMenuItemClickListener { onMenuItem(it.itemId) }
        p.show()
    }

    /** One GridView serves both modes: a single stretched column is the list, auto-fit tiles are the grid. */
    private fun applyViewMode() {
        val pos = list.firstVisiblePosition
        finishSelection()
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
            if (i > 0) crumbs.addView(TextView(this).apply { text = "›"; setTextColor(Color.WHITE); alpha = 0.5f })
            crumbs.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setBackgroundResource(attrRes(android.R.attr.selectableItemBackground))
                if (i == chain.lastIndex) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { if (dir != cwd) navigate(dir) }
            })
        }
        titleView.text = chain.last().first
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
        finishSelection()
        closeSearch()
        cwd = dir
        load()
    }

    private fun load() {
        if (isDestroyed || loader.isShutdown) return
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
                finishSelection()
                adapter.setData(entries ?: emptyList())
                shownDir = dir
                emptyView.setText(if (entries == null) R.string.cannot_read else R.string.empty)
                itemCount = entries?.size
                updateInfo()
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
        // Not c.reversed(): that's Java 8's Comparator default method, missing below API 24 (minSdk is 21).
        if (sortDesc) c = java.util.Collections.reverseOrder(c)
        return compareBy<Entry> { !it.isDir }.then(c) // folders always on top
    }

    // ---------------------------------------------------------------- options menu

    private fun onMenuItem(id: Int): Boolean {
        when {
            id == M_PASTE -> paste()
            id == M_NEW_DIR -> prompt(R.string.new_folder, "") { name ->
                val f = File(cwd, name)
                when {
                    FileOps.existsNoFollow(f) -> toast(R.string.exists)
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
                refreshBar()
            }
            id == M_HIDDEN -> { showHidden = !showHidden; savePrefs(); refreshBar(); load() }
            id == M_REFRESH -> load()
            id >= M_ROOT_BASE -> roots.getOrNull(id - M_ROOT_BASE)?.let { navigate(it.dir) }
            else -> return false // submenu headers: let the popup open the submenu
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

    private fun startSelection() {
        selecting = true
        list.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        normalIcons.visibility = View.GONE
        selectIcons.visibility = View.VISIBLE
    }

    private fun finishSelection() {
        if (!selecting) return
        selecting = false
        list.clearChoices()
        list.choiceMode = AbsListView.CHOICE_MODE_NONE
        // With choice mode off the list no longer updates the highlight, so clear it on visible rows
        // ourselves; recycled rows are reset by the adapter (isChecked) when they're rebound.
        for (i in 0 until list.childCount) list.getChildAt(i).isActivated = false
        list.invalidateViews()
        selectIcons.visibility = View.GONE
        normalIcons.visibility = View.VISIBLE
        renderCrumbs() // restores the folder title
        updateInfo()
    }

    /** Top line shows the count; actions that need exactly one item / no folders hide otherwise. */
    private fun onSelectionChanged() {
        val sel = selected()
        val one = sel.size == 1
        titleView.text = resources.getQuantityString(R.plurals.selected_count, sel.size, sel.size)
        val bytes = sel.filter { !it.isDir }.sumOf { it.size }
        infoView.text = if (bytes > 0) Formatter.formatShortFileSize(this, bytes) else ""
        fun show(id: Int, v: Boolean) { selButtons[id]?.visibility = if (v) View.VISIBLE else View.GONE }
        show(A_RENAME, one)
        show(A_INFO, one)
        show(A_OPEN_WITH, one && !sel[0].isDir)
        show(A_SHARE, sel.isNotEmpty() && sel.none { it.isDir })
    }

    private fun onSelectionAction(id: Int, anchor: View) {
        if (id == A_ALL) {
            for (i in 0 until adapter.count) list.setItemChecked(i, true)
            onSelectionChanged()
            return
        }
        if (id == A_MORE) { showSelectionMore(anchor); return } // keeps the selection until an item is picked
        val sel = selected()
        finishSelection()
        if (sel.isEmpty() || id == A_CLOSE) return
        when (id) {
            A_COPY, A_CUT -> {
                clip = sel.map { it.file }
                clipMove = id == A_CUT
                toast(resources.getQuantityString(R.plurals.copied, clip.size, clip.size))
                refreshBar()
            }
            A_DELETE -> confirmDelete(sel.map { it.file })
            A_RENAME -> rename(sel[0].file)
            A_SHARE -> Openers.share(this, sel.map { it.file })
            A_OPEN_WITH -> Openers.chooser(this, sel[0].file)
            A_INFO -> showInfo(sel[0].file)
        }
    }

    private fun showSelectionMore(anchor: View) {
        val sel = selected()
        if (sel.isEmpty()) return
        val p = PopupMenu(this, anchor)
        p.menu.add(0, A_BATCH_RENAME, 0, R.string.rename_by_pattern)
        p.menu.add(0, A_ZIP, 1, R.string.zip_compress)
        if (sel.size == 1 && !sel[0].isDir && ArchiveOps.isArchive(sel[0].file)) {
            p.menu.add(0, A_ZIP_PREVIEW, 2, R.string.zip_preview)
            p.menu.add(0, A_UNZIP, 3, R.string.zip_extract)
        }
        p.setOnMenuItemClickListener { item ->
            finishSelection()
            when (item.itemId) {
                A_BATCH_RENAME -> batchRename(sel.map { it.file })
                A_ZIP -> compress(sel.map { it.file })
                A_ZIP_PREVIEW -> ZipModule.open(this, sel[0].file)
                A_UNZIP -> extract(sel[0].file)
            }
            true
        }
        p.show()
    }

    /** Rename by pattern (wildcard or regex, with a counter); see [RenameOps]. */
    private fun batchRename(files: List<File>) {
        BatchRenameDialog.show(this, files) { plan ->
            // Renamed files would no longer be found at their old paths when pasting.
            val moved = plan.renames.map { it.file }.toSet()
            if (clip.any { it in moved }) { clip = clip - moved; refreshBar() }
            runOp { RenameOps.apply(plan) }
        }
    }

    // ---------------------------------------------------------------- zip / unzip

    /** Asks for the archive name (one item: its name; several: the folder's), then packs into the current folder. */
    private fun compress(files: List<File>) {
        val dir = cwd
        val one = files.singleOrNull()
        val base = when {
            one == null -> dir.name.ifEmpty { "archive" }
            one.isDirectory -> one.name
            else -> one.name.substringBeforeLast('.').ifEmpty { one.name }
        }
        prompt(R.string.zip_compress, FileOps.unique(dir, "$base.zip").name) { input ->
            val name = if (input.endsWith(".zip", ignoreCase = true)) input else "$input.zip"
            val out = File(dir, name)
            when {
                !FileOps.validName(name) -> toast(R.string.bad_name)
                FileOps.existsNoFollow(out) -> toast(R.string.exists)
                else -> runOp { ZipOps.zip(files, out) }
            }
        }
    }

    /** Extracts into a new folder named after the archive, next to it ("a.zip" -> "a", or "a (1)" if taken). */
    private fun extract(archive: File) {
        val parent = archive.parentFile ?: return
        val dest = FileOps.unique(parent, archive.name.substringBeforeLast('.').ifEmpty { archive.name })
        runOp {
            try {
                ArchiveOps.extractAll(archive, dest)
            } finally {
                // Nothing usable came out (empty, unreadable or failed archive): don't leave an empty folder behind.
                if (dest.list()?.isEmpty() == true) dest.delete()
            }
        }
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
        // Case-only rename ("a.txt" -> "A.txt") is fine on case-insensitive storage, where target "exists" as f.
        val caseOnly = name.equals(f.name, ignoreCase = true)
        when {
            !caseOnly && FileOps.existsNoFollow(target) -> toast(R.string.exists)
            !f.renameTo(target) -> toast(R.string.failed)
            else -> load()
        }
    }

    private fun confirmDelete(files: List<File>) {
        AlertDialog.Builder(this)
            .setMessage(resources.getQuantityString(R.plurals.delete_confirm, files.size, files.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                clip = clip - files.toSet()
                refreshBar()
                runOp { FileOps.deleteAll(files) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun paste() {
        val src = clip
        val move = clipMove
        val dest = cwd
        if (src.any { FileOps.isInside(dest, it) }) {
            toast(R.string.into_itself)
            return
        }
        if (move) { clip = emptyList(); refreshBar() }
        runOp { FileOps.transfer(src, dest, move) }
    }

    /** Run a blocking op off the UI thread; [op] returns the number of failures. */
    private fun runOp(op: () -> Int) {
        busy(true)
        worker.execute {
            val failed = try { op() } catch (e: Exception) { -1 }
            ui.post {
                // isFinishing is false when the activity is recreated (e.g. dark mode switch); isDestroyed isn't.
                if (isFinishing || isDestroyed) return@post
                busy(false)
                when {
                    failed < 0 -> toast(R.string.failed)
                    failed > 0 -> toast(resources.getQuantityString(R.plurals.partly_failed, failed, failed))
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
                fun field(label: Int, value: String) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(label)).append('\n').append(value)
                }
                field(R.string.prop_path, f.absolutePath)
                field(R.string.prop_size, getString(R.string.prop_size_value,
                    Formatter.formatFileSize(this@MainActivity, bytes), "%,d".format(bytes)))
                if (f.isDirectory) {
                    val n = count.toInt()
                    field(R.string.prop_contains, resources.getQuantityString(R.plurals.files, n, n))
                } else {
                    field(R.string.prop_type, Openers.mime(f))
                }
                field(R.string.prop_modified, DateFormat.getDateTimeInstance().format(Date(f.lastModified())))
                val access = if (FileOps.isLink(f)) getString(R.string.prop_symlink, perms, f.canonicalPath) else perms
                field(R.string.prop_access, access)
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                busy(false)
                AlertDialog.Builder(this).setTitle(f.name).setMessage(text)
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
        const val A_CLOSE = 9
        const val A_MORE = 10
        const val A_ZIP = 11
        const val A_UNZIP = 12
        const val A_BATCH_RENAME = 13
        const val A_ZIP_PREVIEW = 14

        const val REQ_SEARCH = 1
    }
}
