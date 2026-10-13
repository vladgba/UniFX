package x.vladgba.fileman

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * Module hook. Built-in viewers implement this and are listed in [Openers.modules]; on tap, the first module
 * whose [canOpen] matches handles the file. Unclaimed files go to an external app via ACTION_VIEW, and
 * "Open with…" lists every module plus "Other app".
 */
interface FileOpener {
    /** Name shown in "Open with…". */
    val title: Int
    /** Claim this file on a plain tap. */
    fun canOpen(f: File): Boolean
    fun open(a: Activity, f: File)
}

object ZipModule : FileOpener {
    override val title = R.string.zip_preview
    override fun canOpen(f: File) = ArchiveOps.isPreviewable(f)
    override fun open(a: Activity, f: File) = Openers.launch(a, ZipActivity::class.java, f)
}

object Openers {
    const val EXTRA_PATH = "x.vladgba.fileman.PATH"

    val modules: MutableList<FileOpener> = mutableListOf(TextModule, ViewerModule, HexModule, ZipModule)

    fun open(a: Activity, f: File) {
        modules.firstOrNull { it.canOpen(f) }?.let { it.open(a, f); return }
        if (!external(a, f, chooser = false)) chooser(a, f) // no external app: offer the built-in modules
    }

    /** "Open with…": built-in modules first, then any installed app. */
    fun chooser(a: Activity, f: File) {
        val labels = (modules.map { a.getString(it.title) } + a.getString(R.string.other_app)).toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(R.string.open_with)
            .setItems(labels) { _, i ->
                if (i < modules.size) modules[i].open(a, f) else if (!external(a, f, chooser = true)) toast(a)
            }
            .show()
    }

    fun launch(a: Activity, cls: Class<out Activity>, f: File) {
        val i = Intent(a, cls).putExtra(EXTRA_PATH, f.absolutePath)
        // Carry the write-back target (and our grant on it) along when switching editor ↔ viewer ↔ hex.
        target(f)?.let { uri ->
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (canWrite(a, uri)) i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        a.startActivity(i)
    }

    // ---------------------------------------------------------------- files opened from other apps
    //
    // A content:// URI that can't be resolved to a real path is copied into the cache and the copy is edited.
    // If the sender granted write access, the copy is registered here and every save is written back.

    private val targets = HashMap<String, Uri>()

    /** Write-back URI for a cached copy, or null for ordinary files. */
    fun target(f: File): Uri? = synchronized(targets) { targets[f.absolutePath] }

    fun setTarget(f: File, uri: Uri) = synchronized(targets) { targets[f.absolutePath] = uri }

    /** Re-register a target passed in an activity's intent (survives process death with the task). */
    fun adoptTarget(a: Activity, f: File) {
        val uri = a.intent.data ?: return
        if (uri.scheme == "content" && canWrite(a, uri)) setTarget(f, uri)
    }

    fun canWrite(ctx: Context, uri: Uri): Boolean =
        ctx.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Copy [f] back to its target URI. True if there is no target or the copy succeeded. Blocking. */
    fun writeBack(ctx: Context, f: File): Boolean {
        val uri = target(f) ?: return true
        return try {
            val out = try { ctx.contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { null }
                ?: ctx.contentResolver.openOutputStream(uri, "w")
                ?: return false
            out.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns false if no app can handle the file. Read access only: a viewer has no business changing the
     * file, and since we hold all-files access, a write grant would let any picked app rewrite anything we pass.
     */
    fun external(a: Activity, f: File, chooser: Boolean): Boolean {
        if (ArchiveOps.isApk(f)) {
            install(a, f)
            return true
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FilesProvider.uri(a, f), mime(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(a, if (chooser) Intent.createChooser(view, f.name) else view)
    }

    /** Dispatches APK installation via system package installer, prompting for unknown sources if needed. */
    fun install(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!a.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${a.packageName}")
                }
                try {
                    a.startActivity(intent)
                    Toast.makeText(a, R.string.install_permission_prompt, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    try {
                        a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        Toast.makeText(a, R.string.install_permission_prompt, Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        Toast.makeText(a, R.string.failed, Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }
        val uri = FilesProvider.uri(a, f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val res = a.packageManager.queryIntentActivities(intent, 0)
        for (r in res) {
            a.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(a, R.string.failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(a: Activity, files: List<File>) {
        val uris = files.map { FilesProvider.uri(a, it) }
        val type = files.map { mime(it) }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!start(a, Intent.createChooser(send, null))) toast(a)
    }

    private fun start(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun toast(a: Activity) = Toast.makeText(a, R.string.no_app, Toast.LENGTH_SHORT).show()

    fun mime(f: File): String {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (Syntax.isText(f)) "text/plain" else "*/*"
    }
}
