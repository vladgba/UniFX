package x.vladgba.fileman

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal stand-in for androidx FileProvider: hands other apps a content:// URI for a file.
 * Not exported, so it is reachable only through URIs we explicitly grant.
 */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri) = File(uri.path ?: throw FileNotFoundException(uri.toString()))

    override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
        val f = file(uri)
        val defaultCols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val reqCols = projection ?: defaultCols
        val cols = ArrayList<String>()
        val vals = ArrayList<Any?>()
        for (c in reqCols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> { cols += c; vals += f.name }
                OpenableColumns.SIZE -> { cols += c; vals += f.length() }
                "_data" -> { cols += c; vals += f.absolutePath }
            }
        }
        return MatrixCursor(cols.toTypedArray(), 1).apply { addRow(vals.toTypedArray()) }
    }

    override fun getType(uri: Uri): String = Openers.mime(file(uri))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val f = file(uri)
        val pfd = openFile(uri, mode)
        return AssetFileDescriptor(pfd, 0, f.length())
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor? =
        openAssetFile(uri, "r")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0

    companion object {
        fun uri(ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(ctx.packageName + ".files").path(f.absolutePath).build()
    }
}
