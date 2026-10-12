package x.vladgba.fileman

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Blocking file operations. Call from a background thread. Each returns the number of top-level items that failed. */
object FileOps {
    private const val MAX_DEPTH = 64

    fun transfer(src: List<File>, destDir: File, move: Boolean): Int {
        var failed = 0
        val destCanon = canon(destDir)
        for (f in src) {
            if (move && f.parentFile?.let(::canon) == destCanon) continue // cut + paste in place: nothing to do
            if (isInside(destDir, f)) { failed++; continue } // a folder into itself would recurse until MAX_DEPTH
            val target = unique(destDir, f.name)
            val ok = if (move) {
                f.renameTo(target) || (copy(f, target, 0) && delete(f))
            } else {
                copy(f, target, 0)
            }
            if (!ok) failed++
        }
        return failed
    }

    fun deleteAll(files: List<File>): Int = files.count { !delete(it) }

    /** Recursive delete that never follows symlinks into their targets. */
    fun delete(f: File): Boolean {
        if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { delete(it) }
        // Not !f.exists(): that follows a link, so a dangling symlink we failed to delete would count as gone.
        return f.delete() || !existsNoFollow(f)
    }

    private fun copy(src: File, dst: File, depth: Int): Boolean = try {
        if (src.isDirectory) {
            if (depth > MAX_DEPTH || !(dst.isDirectory || dst.mkdirs())) {
                false
            } else {
                var ok = true
                src.listFiles()?.forEach { if (!copy(it, File(dst, it.name), depth + 1)) ok = false } ?: run { ok = false }
                ok
            }
        } else {
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            dst.setLastModified(src.lastModified())
            true
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** "name.ext" -> "name (1).ext" until it doesn't collide. */
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        // existsNoFollow: a dangling symlink is taken, or writing to it would create its target elsewhere.
        if (!existsNoFollow(f)) return f
        val dot = name.lastIndexOf('.')
        val isFileWithExt = dot > 0 && !File(dir, name).isDirectory
        val base = if (isFileWithExt) name.substring(0, dot) else name
        val ext = if (isFileWithExt) name.substring(dot) else ""
        var n = 1
        while (existsNoFollow(f)) f = File(dir, "$base (${n++})$ext")
        return f
    }

    private fun canon(f: File): String = try { f.canonicalPath } catch (e: IOException) { f.absolutePath }

    /** True if [dir] is [f] itself or lies inside it, resolving symlinks (/sdcard vs /storage/emulated/0). */
    fun isInside(dir: File, f: File): Boolean {
        if (!f.isDirectory) return false
        return (canon(dir) + "/").startsWith(canon(f) + "/")
    }

    /** lstat-based, so it also sees symlinks whose target is missing (File.exists() follows the link). */
    fun isLink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
    } catch (e: ErrnoException) {
        false
    }

    /** Whether anything, a dangling symlink included, is at this path. */
    fun existsNoFollow(f: File): Boolean = try {
        Os.lstat(f.path)
        true
    } catch (e: ErrnoException) {
        false
    }

    /** (file count, total bytes), not following symlinked folders. */
    fun stats(f: File, depth: Int = 0): LongArray {
        if (!f.isDirectory) return longArrayOf(1, f.length())
        val out = longArrayOf(0, 0)
        if (depth > MAX_DEPTH || (depth > 0 && isLink(f))) return out
        f.listFiles()?.forEach {
            val s = stats(it, depth + 1)
            out[0] += s[0]; out[1] += s[1]
        }
        return out
    }

    /**
     * Write via a temp file + rename so a crash never leaves a half-written file; falls back to a direct write.
     * A symlink is resolved first so the link itself survives, and the executable bit is carried over.
     */
    fun writeAtomic(file: File, bytes: ByteArray): Boolean = try {
        val f = try { file.canonicalFile } catch (e: IOException) { file }
        val tmp = File(f.parentFile, "." + f.name + ".unifx~")
        try {
            tmp.writeBytes(bytes)
            if (f.canExecute()) tmp.setExecutable(true, false)
            if (!tmp.renameTo(f)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            f.writeBytes(bytes)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun validName(n: String) = n.isNotEmpty() && '/' !in n && '\u0000' !in n && n != "." && n != ".."
}
