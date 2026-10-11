package x.vladgba.fileman

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.io.IOException

/**
 * Entry point for other apps: ACTION_VIEW / ACTION_EDIT / ACTION_SEND with a file:// or content:// URI.
 * Reached through two manifest aliases, "UniFX Editor" (text) and "UniFX Hex" (any file).
 *
 * 1. If the URI resolves to a real, readable file (file://, our own provider, the external-storage and
 *    downloads document providers, or a provider exposing a `_data` path), that file is opened directly.
 * 2. Otherwise the content is copied into cache/incoming/ and the copy is opened. If the sender granted
 *    write access, saves are written back to the URI (see Openers.target / writeBack); if not, the copy is
 *    made read-only so it opens in the viewer.
 *
 * Paths inside this app's private data directory are never opened from outside, so another app can't use
 * UniFX to read or rewrite its private files.
 */
class OpenActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val hex = intent.component?.className?.endsWith(".OpenHex") == true
        val uri = incomingUri()
        if (uri == null) {
            // Plain-text share (no file attached): open the text as a read-only copy.
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            if (intent.action == Intent.ACTION_SEND && text != null) sharedText(text.toString(), hex) else fail()
            return
        }

        resolveFile(uri)?.let { open(it, hex); return }

        if (uri.scheme != ContentResolver.SCHEME_CONTENT) { fail(); return }
        setContentView(FrameLayout(this).apply {
            addView(ProgressBar(context), FrameLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48), Gravity.CENTER))
        })
        val writable = intent.action != Intent.ACTION_SEND && Openers.canWrite(this, uri)
        Thread {
            val copy = try { copyToCache(uri) } catch (e: Exception) { null }
            ui.post {
                if (isFinishing) return@post
                if (copy == null) { fail(); return@post }
                if (writable) Openers.setTarget(copy, uri) else copy.setReadOnly()
                open(copy, hex)
            }
        }.start()
    }

    private fun incomingUri(): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> intent.data
    }

    private fun open(f: File, hex: Boolean) {
        if (hex) HexModule.open(this, f) else TextModule.open(this, f)
        finish()
    }

    private fun fail() {
        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------------------------------------------------------------- URI → real file

    private fun resolveFile(uri: Uri): File? {
        val path = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path
            uri.scheme != ContentResolver.SCHEME_CONTENT -> null
            uri.authority == "$packageName.files" -> uri.path
            DocumentsContract.isDocumentUri(this, uri) -> documentPath(uri)
            else -> dataColumn(uri)
        } ?: return null
        val f = File(path)
        if (!f.isFile || !f.canRead() || isPrivate(f)) return null
        return f
    }

    /** Paths for the two system document providers whose IDs encode the path. */
    private fun documentPath(uri: Uri): String? {
        val id = try { DocumentsContract.getDocumentId(uri) } catch (e: IllegalArgumentException) { return null }
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                if (volume.equals("primary", ignoreCase = true)) File(Environment.getExternalStorageDirectory(), rel).path
                else File("/storage/$volume", rel).path
            }
            "com.android.providers.downloads.documents" -> if (id.startsWith("raw:")) id.removePrefix("raw:") else dataColumn(uri)
            else -> dataColumn(uri)
        }
    }

    /** Legacy `_data` column (MediaStore and many file-manager providers still fill it). */
    private fun dataColumn(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            val i = c.getColumnIndex("_data")
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun isPrivate(f: File): Boolean {
        val own = File(applicationInfo.dataDir).canonicalPath + "/"
        return try { f.canonicalPath.startsWith(own) } catch (e: IOException) { true }
    }

    // ---------------------------------------------------------------- copy to cache

    private fun displayName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: "file"
        val clean = name.substringAfterLast('/').replace('\u0000', '_').trim()
        return if (FileOps.validName(clean)) clean else "file"
    }

    private fun copyToCache(uri: Uri): File? {
        val root = File(cacheDir, "incoming")
        cleanOld(root)
        val dir = File(root, System.currentTimeMillis().toString())
        if (!dir.mkdirs()) return null
        val out = File(dir, displayName(uri))
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        return out
    }

    private fun sharedText(text: String, hex: Boolean) {
        val f = try {
            val dir = File(File(cacheDir, "incoming").also { cleanOld(it) }, System.currentTimeMillis().toString())
            dir.mkdirs()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { FileOps.validName(it + ".txt") }
            File(dir, (subject ?: "shared") + ".txt").apply { writeText(text); setReadOnly() }
        } catch (e: Exception) {
            fail(); return
        }
        open(f, hex)
    }

    /** Drop copies from earlier sessions (older than a day). */
    private fun cleanOld(root: File) {
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        root.listFiles()?.forEach { d ->
            if (d.lastModified() < cutoff) {
                d.listFiles()?.forEach { it.setWritable(true); it.delete() }
                d.delete()
            }
        }
    }
}
