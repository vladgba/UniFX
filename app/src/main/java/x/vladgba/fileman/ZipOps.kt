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

    /** Extensions opened by default in the archive previewer on tap. */
    val PREVIEWABLE = setOf("zip", "jar", "aar")

    fun isArchive(f: File) = f.isFile && f.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE

    fun isPreviewable(f: File) = f.isFile && f.name.substringAfterLast('.', "").lowercase() in PREVIEWABLE

    data class EntryInfo(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val compressedSize: Long,
        val time: Long
    ) {
        val ext: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    }

    class ZipTree(
        val totalFiles: Int,
        val totalDirs: Int,
        val totalUncompressedSize: Long,
        val totalCompressedSize: Long,
        private val childrenByDir: Map<String, List<EntryInfo>>,
        private val entriesByPath: Map<String, EntryInfo>
    ) {
        fun list(dir: String): List<EntryInfo> = childrenByDir[normalizeDir(dir)] ?: emptyList()
        fun find(path: String): EntryInfo? = entriesByPath[path.replace('\\', '/').trimStart('/')]
    }

    fun normalizeDir(d: String): String {
        val s = d.replace('\\', '/').trimStart('/').trimEnd('/')
        return if (s.isEmpty()) "" else "$s/"
    }

    /** Reads archive tree, retrying with IBM437 if standard UTF-8 throws on non-standard encoding. */
    @SuppressLint("NewApi")
    fun openTree(zip: File): ZipTree {
        return try {
            readTree(zip)
        } catch (e: IllegalArgumentException) {
            try {
                readTree(zip, Charset.forName("IBM437"))
            } catch (_: Throwable) {
                throw e
            }
        }
    }

    /**
     * Reads the archive structure into a [ZipTree], synthesizing intermediate directories if missing.
     * Dangerous paths (zip slip) are skipped.
     */
    @SuppressLint("NewApi")
    fun readTree(zip: File, charset: Charset? = null): ZipTree {
        val zf = if (charset == null) ZipFile(zip) else ZipFile(zip, charset)
        zf.use {
            var totalFiles = 0
            var totalDirs = 0
            var totalUncompressed = 0L
            var totalCompressed = 0L

            val dirChildren = HashMap<String, LinkedHashMap<String, EntryInfo>>()
            val entriesByPath = HashMap<String, EntryInfo>()

            fun ensureDir(parentDir: String, dirName: String, dirPath: String, time: Long) {
                val map = dirChildren.getOrPut(parentDir) { LinkedHashMap() }
                val existing = map[dirName]
                if (existing == null) {
                    val info = EntryInfo(dirName, dirPath, true, 0L, 0L, time)
                    map[dirName] = info
                    entriesByPath[dirPath] = info
                    totalDirs++
                } else if (existing.time <= 0L && time > 0L) {
                    val updated = existing.copy(time = time)
                    map[dirName] = updated
                    entriesByPath[dirPath] = updated
                }
            }

            val entries = zf.entries().toList()
            for (e in entries) {
                val rawName = e.name.replace('\\', '/').trimStart('/')
                if (rawName.isEmpty()) continue
                val isDir = e.isDirectory || rawName.endsWith('/')
                val cleanName = if (isDir) rawName.trimEnd('/') else rawName
                val segments = cleanName.split('/')
                if (segments.any { it == ".." || it == "." || it.isEmpty() }) continue

                var curParent = ""
                for (i in 0 until segments.size - 1) {
                    val seg = segments[i]
                    val subPath = "$curParent$seg/"
                    ensureDir(curParent, seg, subPath, 0L)
                    curParent = subPath
                }

                val leafName = segments.last()
                if (isDir) {
                    val dirPath = "$cleanName/"
                    ensureDir(curParent, leafName, dirPath, e.time)
                } else {
                    val filePath = cleanName
                    val size = e.size.coerceAtLeast(0L)
                    val compSize = e.compressedSize.coerceAtLeast(0L)
                    totalFiles++
                    totalUncompressed += size
                    if (compSize > 0) totalCompressed += compSize
                    val info = EntryInfo(leafName, filePath, false, e.size, e.compressedSize, e.time)
                    dirChildren.getOrPut(curParent) { LinkedHashMap() }[leafName] = info
                    entriesByPath[filePath] = info
                }
            }

            val comparator = compareBy<EntryInfo>({ !it.isDir }, { it.name.lowercase() }, { it.name })
            val sortedChildren = dirChildren.mapValues { (_, children) ->
                children.values.sortedWith(comparator)
            }

            return ZipTree(totalFiles, totalDirs, totalUncompressed, totalCompressed, sortedChildren, entriesByPath)
        }
    }

    /** Extracts a single entry [entryPath] from [zip] into [dest]. */
    @SuppressLint("NewApi")
    fun extractEntry(zip: File, entryPath: String, dest: File, charset: Charset? = null) {
        val zf = if (charset == null) ZipFile(zip) else ZipFile(zip, charset)
        zf.use {
            val norm = entryPath.replace('\\', '/').trimStart('/')
            val e = zf.getEntry(norm) ?: zf.getEntry("$norm/") ?: throw IOException("Entry not found: $entryPath")
            if (e.isDirectory) {
                if (!dest.isDirectory && !dest.mkdirs()) throw IOException("can't create directory $dest")
            } else {
                val parent = dest.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("can't create directory $parent")
                zf.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o, BUFFER) } }
                if (e.time > 0) dest.setLastModified(e.time)
            }
        }
    }

    /** Extracts all entries inside [subPath] from [zip] into [destDir]. */
    @SuppressLint("NewApi")
    fun extractSubtree(zip: File, subPath: String, destDir: File, charset: Charset? = null): Int {
        val zf = if (charset == null) ZipFile(zip) else ZipFile(zip, charset)
        zf.use {
            val prefix = normalizeDir(subPath)
            val destRoot = canon(destDir) + File.separator
            if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("can't create $destDir")
            var failed = 0
            val entries = zf.entries().toList()
            for (e in entries) {
                val rel = e.name.replace('\\', '/').trimStart('/')
                if (!rel.startsWith(prefix)) continue
                val localRel = rel.removePrefix(prefix).trimStart('/')
                if (localRel.isEmpty()) continue
                val target = File(destDir, localRel)
                if (!(canon(target) + File.separator).startsWith(destRoot)) { failed++; continue } // zip slip
                try {
                    if (e.isDirectory) {
                        if (!(target.isDirectory || target.mkdirs())) failed++
                        else if (e.time > 0) target.setLastModified(e.time)
                    } else {
                        val p = target.parentFile
                        if (p != null && !(p.isDirectory || p.mkdirs())) { failed++; continue }
                        zf.getInputStream(e).use { i -> target.outputStream().use { o -> i.copyTo(o, BUFFER) } }
                        if (e.time > 0) target.setLastModified(e.time)
                    }
                } catch (_: IOException) {
                    target.delete()
                    failed++
                }
            }
            return failed
        }
    }

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
