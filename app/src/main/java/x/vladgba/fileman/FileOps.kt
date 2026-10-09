package x.vladgba.fileman

import java.io.File
import java.io.IOException

/** Blocking file operations. Call from a background thread. Each returns the number of top-level items that failed. */
object FileOps {
    private const val MAX_DEPTH = 64

    fun transfer(src: List<File>, destDir: File, move: Boolean): Int {
        var failed = 0
        for (f in src) {
            if (move && f.parentFile == destDir) continue // cut + paste in place: nothing to do
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
        return f.delete() || !f.exists()
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
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val isFileWithExt = dot > 0 && !File(dir, name).isDirectory
        val base = if (isFileWithExt) name.substring(0, dot) else name
        val ext = if (isFileWithExt) name.substring(dot) else ""
        var n = 1
        while (f.exists()) f = File(dir, "$base (${n++})$ext")
        return f
    }

    fun isLink(f: File): Boolean = try {
        val parent = f.parentFile?.canonicalFile ?: return false
        File(parent, f.name).canonicalPath != File(parent, f.name).absolutePath
    } catch (e: IOException) {
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

    /** Write via a temp file + rename so a crash never leaves a half-written file; falls back to a direct write. */
    fun writeAtomic(f: File, bytes: ByteArray): Boolean = try {
        val tmp = File(f.parentFile, "." + f.name + ".unifx~")
        try {
            tmp.writeBytes(bytes)
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
