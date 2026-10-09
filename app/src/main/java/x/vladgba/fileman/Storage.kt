package x.vladgba.fileman

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

object Storage {
    class Root(val label: String, val dir: File)

    fun primary(): File = Environment.getExternalStorageDirectory()

    fun roots(ctx: Context): List<Root> {
        val out = mutableListOf(Root(ctx.getString(R.string.internal), primary()))
        // Secondary volumes (SD cards, USB) show up as extra app-specific dirs; strip back to the volume root.
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, d ->
            val label = ctx.getString(R.string.sd_card) + if (i > 0) " ${i + 1}" else ""
            out += Root(label, File(d.path.substringBefore("/Android/data/")))
        }
        out += Root(ctx.getString(R.string.app_files), ctx.filesDir)
        out += Root(ctx.getString(R.string.root), File("/"))
        return out
    }

    fun hasAccess(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 ->
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun request(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.packageName))
                )
            } catch (e: ActivityNotFoundException) {
                a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            a.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }
}
