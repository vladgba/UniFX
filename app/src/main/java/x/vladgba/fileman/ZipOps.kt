package x.vladgba.fileman

import android.annotation.SuppressLint
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Zip / unzip on java.util.zip alone (no Android APIs, so it is unit-testable on the JVM).
 * Blocking: call from a background thread.
 */
object ZipOps {
    private const val MAX_DEPTH = 64
    private const val BUFFER = 64 * 1024

    /** Extensions offered "Extract": all of these are plain zip containers. */
    val EXTRACTABLE = setOf("zip", "jar", "aar", "apk", "epub")

    fun isArchive(f: File) = f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE

    /**
     * Packs [files] (folders recursively, empty ones included) into [out], each under its own name at the
     * archive root. Symlinked folders are followed, but a folder already packed is never entered again, so
     * link loops end. Returns how many items couldn't be read and were left out. If the archive itself
     * can't be written, throws and deletes the partial [out].
     */
    fun zip(files: List<File>, out: File): Int {
        val outPath = canon(out)
        val seenDirs = HashSet<String>()
        var skipped = 0
        try {
            ZipOutputStream(out.outputStream().buffered(BUFFER)).use { zos ->
                val names = HashSet<String>()

                fun add(f: File, name: String, depth: Int) {
                    if (canon(f) == outPath) return // never pack the archive into itself
                    if (f.isDirectory) {
                        if (depth > MAX_DEPTH || !seenDirs.add(canon(f))) { skipped++; return }
                        val children = f.listFiles()
                        if (children == null) { skipped++; return }
                        if (names.add("$name/")) {
                            zos.putNextEntry(ZipEntry("$name/").apply { time = f.lastModified() })
                            zos.closeEntry()
                        }
                        children.sortedBy { it.name }.forEach { add(it, "$name/${it.name}", depth + 1) }
                    } else {
                        // Open before putNextEntry: an unreadable file is skipped instead of leaving a broken entry.
                        val input = try { f.inputStream() } catch (e: IOException) { skipped++; return }
                        input.use { i ->
                            if (!names.add(name)) { skipped++; return }
                            zos.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                            i.copyTo(zos, BUFFER)
                            zos.closeEntry()
                        }
                    }
                }

                for (f in files) add(f, f.name, 0)
            }
        } catch (e: IOException) {
            out.delete()
            throw e
        }
        return skipped
    }

    /**
     * Extracts [zip] into [dest] (created if needed). Entries that would land outside [dest] ("zip slip":
     * `../x`, absolute paths) are refused, as are duplicates of something already extracted. Returns how many
     * entries were refused or failed. [charset] decodes entry names; null means the platform default (UTF-8).
     * Throws if the archive can't be opened or its names don't decode in [charset].
     */
    @SuppressLint("NewApi") // ZipFile(File, Charset) is API 24+: callers pass a charset only there
    fun unzip(zip: File, dest: File, charset: Charset? = null): Int {
        val zf = if (charset == null) ZipFile(zip) else ZipFile(zip, charset)
        zf.use {
            // Read every entry first: a name that fails to decode throws here, before anything is written,
            // so the caller can retry with another charset on a clean folder.
            val entries = zf.entries().toList()
            if (!dest.isDirectory && !dest.mkdirs()) throw IOException("can't create $dest")
            val root = canon(dest) + File.separator
            var failed = 0
            val dirTimes = ArrayList<Pair<File, Long>>()
            for (e in entries) {
                val rel = e.name.replace('\\', '/').trimStart('/')
                if (rel.isEmpty()) continue
                val target = File(dest, rel)
                if (!(canon(target) + File.separator).startsWith(root)) { failed++; continue } // zip slip
                try {
                    if (e.isDirectory) {
                        if (!(target.isDirectory || target.mkdirs())) { failed++; continue }
                        if (e.time > 0) dirTimes += target to e.time
                    } else {
                        val parent = target.parentFile
                        if (parent != null && !(parent.isDirectory || parent.mkdirs())) { failed++; continue }
                        if (target.exists()) { failed++; continue } // duplicate entry: keep the first
                        zf.getInputStream(e).use { i -> target.outputStream().use { o -> i.copyTo(o, BUFFER) } }
                        if (e.time > 0) target.setLastModified(e.time)
                    }
                } catch (ex: IOException) {
                    target.delete()
                    failed++
                }
            }
            // Folder times last: writing files into a folder bumps its modified time.
            for ((d, t) in dirTimes.asReversed()) d.setLastModified(t)
            return failed
        }
    }

    private fun canon(f: File): String = try { f.canonicalPath } catch (e: IOException) { f.absolutePath }
}
