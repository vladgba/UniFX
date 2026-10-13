package x.vladgba.fileman

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archive viewer: browses entries and folders inside zip/jar/apk/aar/epub/tar/tgz/gz archives without extracting.
 * For APK files, an application header banner with app icon, package id, version, and an "Install" button is shown.
 * Tapping any file extracts it to cache and opens it with the appropriate viewer; tapping a folder navigates
 * inside; breadcrumbs allow quick jumps up the tree.
 */
class ZipActivity : Activity() {
    private lateinit var file: File
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private val adapter = EntryAdapter()
    private val ui = Handler(Looper.getMainLooper())

    private var tree: ZipOps.ZipTree? = null
    private var currentDir = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val path = intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentDir = state?.getString("dir") ?: ""

        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        updateSubtitle()

        setContentView(buildUi())
        loadTree()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("dir", currentDir)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentDir.isNotEmpty()) {
            navigateUp()
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (currentDir.isNotEmpty()) navigateUp() else finish()
                return true
            }
            M_EXTRACT_ALL -> { extractAll(); return true }
            M_PROPERTIES -> { showArchiveProperties(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_EXTRACT_ALL, 0, R.string.zip_extract_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, M_PROPERTIES, 1, R.string.properties)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi(): View {
        crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }

        list = ListView(this).apply {
            this.adapter = this@ZipActivity.adapter
            divider = null
            dividerHeight = 0
            isFastScrollEnabled = true
            setOnItemClickListener { _, _, position, _ ->
                val entry = this@ZipActivity.adapter.getItem(position)
                if (entry.isDir) navigate(entry.path) else openEntry(entry)
            }
            setOnItemLongClickListener { _, view, position, _ ->
                showEntryMenu(view, this@ZipActivity.adapter.getItem(position))
                true
            }
        }

        emptyView = TextView(this).apply {
            gravity = Gravity.CENTER
            setText(R.string.empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            alpha = 0.5f
            visibility = View.GONE
        }
        list.emptyView = emptyView

        progress = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }

        val frame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }

        val primary = TypedValue().also { theme.resolveAttribute(android.R.attr.colorPrimary, it, true) }.data
        val crumbBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
            addView(crumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
        }

        val apkBanner = buildApkBanner()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (apkBanner != null) {
                addView(apkBanner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            addView(crumbBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        }
    }

    private fun buildApkBanner(): View? {
        if (!ArchiveOps.isApk(file)) return null
        val pm = packageManager
        val pi = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.path, 0)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val appInfo = pi.applicationInfo ?: return null
        appInfo.sourceDir = file.path
        appInfo.publicSourceDir = file.path
        val appLabel = try { appInfo.loadLabel(pm)?.toString() } catch (_: Exception) { null } ?: file.nameWithoutExtension
        val appIcon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null }
        val pkgName = pi.packageName ?: ""
        val verName = pi.versionName ?: ""
        val verCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()

        val iconView = ImageView(this).apply {
            if (appIcon != null) setImageDrawable(appIcon)
            else setImageResource(R.drawable.ic_launcher)
        }

        val labelView = TextView(this).apply {
            text = appLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }

        val pkgView = TextView(this).apply {
            text = if (verName.isNotEmpty()) "$pkgName  ·  v$verName ($verCode)" else "$pkgName ($verCode)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }

        val installBtn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
            setText(R.string.install)
            isAllCaps = false
            setOnClickListener { installApk() }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(labelView)
            addView(pkgView)
        }

        val bg = TypedValue().also { theme.resolveAttribute(android.R.attr.colorBackground, it, true) }.data
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(bg)
            addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(installBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun installApk() {
        val uri = FilesProvider.uri(this, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSubtitle() {
        actionBar?.subtitle = if (currentDir.isEmpty()) "/" else "/$currentDir"
    }

    private fun updateCrumbs() {
        crumbs.removeAllViews()
        val segments = currentDir.trimEnd('/').split('/').filter { it.isNotEmpty() }

        fun addCrumb(label: String, targetDir: String, isLast: Boolean) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                if (isLast) {
                    alpha = 1f
                    setTypeface(typeface, Typeface.BOLD)
                } else {
                    alpha = 0.75f
                    setOnClickListener { navigate(targetDir) }
                }
            }
            crumbs.addView(tv)
        }

        addCrumb(file.name, "", segments.isEmpty())

        var cur = ""
        for (i in segments.indices) {
            crumbs.addView(TextView(this).apply {
                text = "›"
                setTextColor(Color.WHITE)
                alpha = 0.5f
                setPadding(dp(2), 0, dp(2), 0)
            })
            val seg = segments[i]
            cur = "$cur$seg/"
            addCrumb(seg, cur, i == segments.lastIndex)
        }

        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ---------------------------------------------------------------- Navigation

    private fun navigate(dir: String) {
        currentDir = ZipOps.normalizeDir(dir).let { if (it == "/") "" else it }
        updateSubtitle()
        updateCrumbs()
        displayCurrentDir()
    }

    private fun navigateUp() {
        val trimmed = currentDir.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        navigate(if (parent.isEmpty()) "" else "$parent/")
    }

    private fun displayCurrentDir() {
        val t = tree ?: return
        val items = t.list(currentDir)
        adapter.setEntries(items)
        emptyView.setText(if (t.totalFiles == 0 && t.totalDirs == 0) R.string.zip_empty else R.string.empty)
    }

    // ---------------------------------------------------------------- Loading

    private fun loadTree() {
        progress.visibility = View.VISIBLE
        Thread {
            val loaded = try {
                ArchiveOps.readTree(file)
            } catch (_: Exception) {
                null
            }

            ui.post {
                if (isFinishing) return@post
                progress.visibility = View.GONE
                if (loaded == null) {
                    Toast.makeText(this, R.string.zip_cannot_read, Toast.LENGTH_SHORT).show()
                    finish()
                    return@post
                }
                tree = loaded
                updateCrumbs()
                displayCurrentDir()
            }
        }.start()
    }

    // ---------------------------------------------------------------- Entry actions

    private fun openEntry(entry: ZipOps.EntryInfo) {
        val tempDir = File(cacheDir, "preview/${file.name}")
        val target = File(tempDir, entry.path)
        if (target.exists()) {
            Openers.open(this, target)
            return
        }

        progress.visibility = View.VISIBLE
        Thread {
            val ok = try {
                ArchiveOps.extractEntry(file, entry.path, target)
                target.setReadOnly()
                true
            } catch (_: Exception) {
                target.delete()
                false
            }

            ui.post {
                if (isFinishing) return@post
                progress.visibility = View.GONE
                if (!ok) {
                    Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                } else {
                    Openers.open(this, target)
                }
            }
        }.start()
    }

    private fun showEntryMenu(anchor: View, entry: ZipOps.EntryInfo) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, R.string.zip_extract_item)
        p.menu.add(0, 2, 1, R.string.properties)
        if (!entry.isDir) p.menu.add(0, 3, 2, R.string.open_with)
        p.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> extractEntryToDisk(entry)
                2 -> showEntryProperties(entry)
                3 -> openEntryWith(entry)
            }
            true
        }
        p.show()
    }

    private fun openEntryWith(entry: ZipOps.EntryInfo) {
        val tempDir = File(cacheDir, "preview/${file.name}")
        val target = File(tempDir, entry.path)
        if (target.exists()) {
            Openers.chooser(this, target)
            return
        }

        progress.visibility = View.VISIBLE
        Thread {
            val ok = try {
                ArchiveOps.extractEntry(file, entry.path, target)
                target.setReadOnly()
                true
            } catch (_: Exception) {
                target.delete()
                false
            }

            ui.post {
                if (isFinishing) return@post
                progress.visibility = View.GONE
                if (ok) Openers.chooser(this, target)
                else Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun extractEntryToDisk(entry: ZipOps.EntryInfo) {
        val destFolder = File(file.parentFile ?: Storage.primary(), file.name.substringBeforeLast('.'))
        progress.visibility = View.VISIBLE
        Toast.makeText(this, R.string.zip_extracting, Toast.LENGTH_SHORT).show()

        Thread {
            val ok = try {
                if (entry.isDir) {
                    val targetDir = File(destFolder, entry.name)
                    ZipOps.extractSubtree(file, entry.path, targetDir) == 0
                } else {
                    if (!destFolder.isDirectory) destFolder.mkdirs()
                    val targetFile = File(destFolder, entry.name)
                    ArchiveOps.extractEntry(file, entry.path, targetFile)
                    true
                }
            } catch (_: Exception) {
                false
            }

            ui.post {
                if (isFinishing) return@post
                progress.visibility = View.GONE
                if (ok) {
                    Toast.makeText(this, getString(R.string.zip_extracted, destFolder.path), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun extractAll() {
        val dest = FileOps.unique(file.parentFile ?: Storage.primary(), file.name.substringBeforeLast('.'))
        progress.visibility = View.VISIBLE
        Toast.makeText(this, R.string.zip_extracting, Toast.LENGTH_SHORT).show()

        Thread {
            val ok = try {
                ArchiveOps.extractAll(file, dest) == 0
            } catch (_: Exception) {
                false
            }

            ui.post {
                if (isFinishing) return@post
                progress.visibility = View.GONE
                if (ok) {
                    Toast.makeText(this, getString(R.string.zip_extracted, dest.path), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showEntryProperties(entry: ZipOps.EntryInfo) {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = if (entry.time > 0) dateFmt.format(Date(entry.time)) else "-"
        val lines = ArrayList<String>()
        lines += "${getString(R.string.prop_path)}: ${entry.path}"
        lines += "${getString(R.string.prop_type)}: " + if (entry.isDir) getString(R.string.new_folder) else entry.ext.uppercase().ifEmpty { "File" }
        if (!entry.isDir) {
            lines += "${getString(R.string.prop_size)}: ${Formatter.formatShortFileSize(this, entry.size)} (${entry.size} B)"
            if (entry.compressedSize > 0) {
                lines += "Compressed: ${Formatter.formatShortFileSize(this, entry.compressedSize)} (${entry.compressedSize} B)"
                if (entry.size > 0) {
                    val ratio = 100 - (entry.compressedSize * 100 / entry.size)
                    lines += "Ratio: $ratio%"
                }
            }
        }
        lines += "${getString(R.string.prop_modified)}: $date"

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showArchiveProperties() {
        val t = tree ?: return
        val lines = ArrayList<String>()
        lines += "${getString(R.string.prop_path)}: ${file.path}"
        lines += "${getString(R.string.prop_size)}: ${Formatter.formatShortFileSize(this, file.length())}"
        lines += "${getString(R.string.prop_contains)}: ${t.totalFiles} files, ${t.totalDirs} folders"
        lines += "Uncompressed: ${Formatter.formatShortFileSize(this, t.totalUncompressedSize)}"
        if (t.totalCompressedSize > 0 && t.totalUncompressedSize > 0) {
            val ratio = 100 - (t.totalCompressedSize * 100 / t.totalUncompressedSize)
            lines += "Compression ratio: $ratio%"
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------------------------------------------------------------- Adapter

    private class EntryAdapter : BaseAdapter() {
        private var items = emptyList<ZipOps.EntryInfo>()
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun setEntries(newItems: List<ZipOps.EntryInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ZipOps.EntryInfo = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = parent.context
            val row = convertView as? RowView ?: RowView(ctx)
            val e = items[position]
            row.badge.set(e.isDir, e.ext)
            row.name.text = e.name
            val date = if (e.time > 0) dateFmt.format(Date(e.time)) else ""
            row.meta.text = if (e.isDir) date else "${Formatter.formatShortFileSize(ctx, e.size)}  ·  $date"
            return row
        }
    }

    @SuppressLint("ResourceType") // obtainStyledAttributes index, not a resource id
    private class RowView(ctx: Context) : LinearLayout(ctx) {
        val badge = FileAdapter.Badge(ctx)
        val name = TextView(ctx)
        val meta = TextView(ctx)

        init {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(8), dp(16), dp(8))

            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)

            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
            name.setTextColor(a.getColorStateList(0))
            meta.setTextColor(a.getColorStateList(1))
            a.recycle()

            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            name.setSingleLine()
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            meta.setSingleLine()

            addView(badge, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(name)
                addView(meta)
            }
            addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    companion object {
        private const val M_EXTRACT_ALL = 100
        private const val M_PROPERTIES = 101
    }
}
