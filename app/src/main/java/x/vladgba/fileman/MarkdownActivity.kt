package x.vladgba.fileman

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Built-in Markdown viewer module supporting Traditional Markdown, GFM, and Markdown Extra (simplemd4php syntax). */
object MarkdownModule : FileOpener {
    override val title = R.string.markdown_viewer
    override fun canOpen(f: File) = MarkdownOps.isMarkdown(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, MarkdownActivity::class.java, f)
}

class MarkdownActivity : Activity() {
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView

    private val ui = Handler(Looper.getMainLooper())
    private var fontSp = 14f
    private var breaksOn = false
    private var anchorsOn = true
    private var lastQuery: String? = null
    private var rawText: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = File(intent.getStringExtra(Openers.EXTRA_PATH) ?: run { finish(); return })
        Openers.adoptTarget(this, file)
        prefs = getSharedPreferences("unifx", MODE_PRIVATE)
        fontSp = prefs.getFloat("ed_font", 14f)
        breaksOn = prefs.getBoolean("md_breaks", false)
        anchorsOn = prefs.getBoolean("md_anchors", true)

        val night = Ui.isNight(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = file.name
        actionBar?.subtitle = "Markdown · ${Formatter.formatShortFileSize(this, file.length())}"

        web = WebView(this).apply {
            setBackgroundColor(if (night) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt())
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = true
                allowContentAccess = false
                builtInZoomControls = true
                displayZoomControls = false
                defaultTextEncodingName = "UTF-8"
                textZoom = (fontSp / 14f * 100f).toInt().coerceIn(50, 250)
            }
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUrl(url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    if (Build.VERSION.SDK_INT >= 24) handleUrl(request.url.toString()) else false
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            Ui.applyInsets(this, underActionBar = true)
        })

        loadFile()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun loadFile() {
        Thread {
            val bytes = try { file.readBytes() } catch (_: Exception) { null }
            if (bytes == null) {
                ui.post {
                    if (!isFinishing) {
                        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                return@Thread
            }
            val text = decodeUtf8OrLatin1(bytes)
            val night = Ui.isNight(this)
            val html = MarkdownOps.renderPage(
                markdown = text,
                fileName = file.name,
                night = night,
                breaks = breaksOn,
                anchors = anchorsOn
            )
            ui.post {
                if (isFinishing) return@post
                rawText = text
                showHtml(html)
            }
        }.start()
    }

    private fun reRender() {
        val text = rawText ?: return
        val night = Ui.isNight(this)
        Thread {
            val html = MarkdownOps.renderPage(
                markdown = text,
                fileName = file.name,
                night = night,
                breaks = breaksOn,
                anchors = anchorsOn
            )
            ui.post {
                if (!isFinishing) showHtml(html)
            }
        }.start()
    }

    private fun showHtml(html: String) {
        val baseDir = file.parentFile
        val baseUrl = if (baseDir != null) {
            val u = baseDir.toURI().toString()
            if (u.endsWith("/")) u else "$u/"
        } else {
            "about:blank"
        }
        web.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private fun decodeUtf8OrLatin1(b: ByteArray): String {
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, b.size - off)).toString()
        } catch (_: CharacterCodingException) {
            String(b, Charsets.ISO_8859_1)
        }
    }

    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("about:blank#") || url.startsWith("#")) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return true }
        val scheme = uri.scheme?.lowercase()

        if (scheme == "file") {
            val path = uri.path ?: return true
            val rawTarget = File(path)
            // Same-file anchor jump
            if (sameFile(rawTarget, file) && !uri.fragment.isNullOrEmpty()) return false

            val target = resolveLocalTarget(rawTarget)
            if (target != null && target.canRead()) {
                if (MarkdownOps.isMarkdown(target)) {
                    Openers.launch(this, MarkdownActivity::class.java, target)
                } else {
                    Openers.open(this, target)
                }
            } else {
                Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        if (scheme == "http" || scheme == "https" || scheme == "mailto") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        return true
    }

    private fun sameFile(a: File, b: File): Boolean = try {
        a.canonicalFile == b.canonicalFile
    } catch (_: Exception) {
        a.absolutePath == b.absolutePath
    }

    private fun resolveLocalTarget(rawTarget: File): File? {
        if (rawTarget.isFile) return rawTarget
        val md = File(rawTarget.path + ".md")
        if (md.isFile) return md
        val markdown = File(rawTarget.path + ".markdown")
        if (markdown.isFile) return markdown
        if (rawTarget.isDirectory) {
            for (indexName in listOf("index.md", "README.md", "readme.md", "index.markdown")) {
                val idx = File(rawTarget, indexName)
                if (idx.isFile) return idx
            }
        }
        return null
    }

    private fun find() {
        Ui.input(this, getString(R.string.find), lastQuery ?: "", InputType.TYPE_CLASS_TEXT, R.string.find, { q ->
            if (q.isNotEmpty()) {
                lastQuery = q
                invalidateOptionsMenu()
                web.findAllAsync(q)
            } else {
                lastQuery = null
                invalidateOptionsMenu()
                web.clearMatches()
            }
        })
    }

    private fun canEdit() = file.canWrite() && file.length() <= EDIT_MAX_BYTES

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_FIND, 0, R.string.find).setIcon(R.drawable.ic_find)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SMALLER, 1, R.string.font_smaller).setIcon(R.drawable.ic_text_smaller)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_BIGGER, 2, R.string.font_bigger).setIcon(R.drawable.ic_text_bigger)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_FIND_NEXT, 3, R.string.find_next)
        menu.add(0, M_BREAKS, 4, R.string.md_breaks).isCheckable = true
        menu.add(0, M_ANCHORS, 5, R.string.md_anchors).isCheckable = true
        menu.add(0, M_EDIT, 6, R.string.edit)
        menu.add(0, M_VIEWER, 7, R.string.text_viewer)
        menu.add(0, M_HEX, 8, R.string.open_in_hex)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_FIND_NEXT).isVisible = lastQuery != null
        menu.findItem(M_BREAKS).isChecked = breaksOn
        menu.findItem(M_ANCHORS).isChecked = anchorsOn
        menu.findItem(M_EDIT).isVisible = canEdit()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_FIND -> find()
            M_FIND_NEXT -> web.findNext(true)
            M_BIGGER, M_SMALLER -> {
                fontSp = (fontSp + if (item.itemId == M_BIGGER) 2f else -2f).coerceIn(8f, 32f)
                prefs.edit().putFloat("ed_font", fontSp).apply()
                web.settings.textZoom = (fontSp / 14f * 100f).toInt().coerceIn(50, 250)
            }
            M_BREAKS -> {
                breaksOn = !breaksOn
                prefs.edit().putBoolean("md_breaks", breaksOn).apply()
                reRender()
            }
            M_ANCHORS -> {
                anchorsOn = !anchorsOn
                prefs.edit().putBoolean("md_anchors", anchorsOn).apply()
                reRender()
            }
            M_EDIT -> { Openers.launch(this, EditorActivity::class.java, file); finish() }
            M_VIEWER -> { ViewerModule.open(this, file); finish() }
            M_HEX -> { HexModule.open(this, file); finish() }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private companion object {
        const val M_FIND = 1
        const val M_FIND_NEXT = 2
        const val M_BIGGER = 3
        const val M_SMALLER = 4
        const val M_BREAKS = 5
        const val M_ANCHORS = 6
        const val M_EDIT = 7
        const val M_VIEWER = 8
        const val M_HEX = 9
    }
}
