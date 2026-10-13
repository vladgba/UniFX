package x.vladgba.fileman

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Lightweight TAR and GZ archive inspection and extraction.
 * Pure JVM code with zero third-party dependencies.
 */
object TarOps {
    const val BLOCK_SIZE = 512

    fun isTar(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")
    }

    fun isGzOnly(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".gz") && !name.endsWith(".tar.gz")
    }

    fun readTree(file: File): ZipOps.ZipTree {
        val isGz = file.name.lowercase().endsWith(".tar.gz") || file.name.lowercase().endsWith(".tgz")
        val stream = if (isGz) GZIPInputStream(file.inputStream().buffered()) else file.inputStream().buffered()
        stream.use { input ->
            return parseTarStream(input, file.length())
        }
    }

    private fun parseTarStream(input: InputStream, fileLen: Long): ZipOps.ZipTree {
        val block = ByteArray(BLOCK_SIZE)
        var totalFiles = 0
        var totalDirs = 0
        var totalUncompressed = 0L
        var longName: String? = null

        val dirChildren = HashMap<String, LinkedHashMap<String, ZipOps.EntryInfo>>()
        val entriesByPath = HashMap<String, ZipOps.EntryInfo>()

        fun ensureDir(parentDir: String, dirName: String, dirPath: String, time: Long) {
            val map = dirChildren.getOrPut(parentDir) { LinkedHashMap() }
            val existing = map[dirName]
            if (existing == null) {
                val info = ZipOps.EntryInfo(dirName, dirPath, true, 0L, 0L, time)
                map[dirName] = info
                entriesByPath[dirPath] = info
                totalDirs++
            } else if (existing.time <= 0L && time > 0L) {
                val updated = existing.copy(time = time)
                map[dirName] = updated
                entriesByPath[dirPath] = updated
            }
        }

        while (true) {
            val read = readFully(input, block)
            if (read < BLOCK_SIZE || isAllZero(block)) break

            val rawName = parseString(block, 0, 100)
            val size = parseOctal(block, 124, 12)
            val mtime = parseOctal(block, 136, 12) * 1000L
            val typeFlag = block[156].toInt().toChar()
            val magic = parseString(block, 257, 6)
            val prefix = if (magic.startsWith("ustar")) parseString(block, 345, 155) else ""

            val entryName = when {
                longName != null -> {
                    val n = longName!!
                    longName = null
                    n
                }
                prefix.isNotEmpty() -> "$prefix/$rawName"
                else -> rawName
            }

            if (typeFlag == 'L') {
                val nameBytes = readBytes(input, size)
                longName = String(nameBytes, StandardCharsets.UTF_8).trimEnd('\u0000').trim()
                skipPadding(input, size)
                continue
            }

            val isDir = typeFlag == '5' || entryName.endsWith('/')
            val norm = entryName.replace('\\', '/').trimStart('/')

            if (norm.isNotEmpty() && !norm.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
                val clean = if (isDir) norm.trimEnd('/') else norm
                val segments = clean.split('/')

                var curParent = ""
                for (i in 0 until segments.size - 1) {
                    val seg = segments[i]
                    val subPath = "$curParent$seg/"
                    ensureDir(curParent, seg, subPath, 0L)
                    curParent = subPath
                }

                val leafName = segments.last()
                if (isDir) {
                    ensureDir(curParent, leafName, "$clean/", mtime)
                } else {
                    totalFiles++
                    totalUncompressed += size
                    val info = ZipOps.EntryInfo(leafName, clean, false, size, size, mtime)
                    dirChildren.getOrPut(curParent) { LinkedHashMap() }[leafName] = info
                    entriesByPath[clean] = info
                }
            }

            val pad = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
            skipFully(input, pad)
        }

        val comparator = compareBy<ZipOps.EntryInfo>({ !it.isDir }, { it.name.lowercase() }, { it.name })
        val sorted = dirChildren.mapValues { (_, c) -> c.values.sortedWith(comparator) }
        return ZipOps.ZipTree(totalFiles, totalDirs, totalUncompressed, fileLen, sorted, entriesByPath)
    }

    fun extractEntry(file: File, entryPath: String, dest: File) {
        val isGz = file.name.lowercase().endsWith(".tar.gz") || file.name.lowercase().endsWith(".tgz")
        val stream = if (isGz) GZIPInputStream(file.inputStream().buffered()) else file.inputStream().buffered()
        stream.use { input ->
            val block = ByteArray(BLOCK_SIZE)
            val targetPath = entryPath.replace('\\', '/').trimStart('/')
            var longName: String? = null

            while (true) {
                val read = readFully(input, block)
                if (read < BLOCK_SIZE || isAllZero(block)) break

                val rawName = parseString(block, 0, 100)
                val size = parseOctal(block, 124, 12)
                val mtime = parseOctal(block, 136, 12) * 1000L
                val typeFlag = block[156].toInt().toChar()
                val magic = parseString(block, 257, 6)
                val prefix = if (magic.startsWith("ustar")) parseString(block, 345, 155) else ""

                val entryName = when {
                    longName != null -> {
                        val n = longName!!
                        longName = null
                        n
                    }
                    prefix.isNotEmpty() -> "$prefix/$rawName"
                    else -> rawName
                }

                if (typeFlag == 'L') {
                    val nameBytes = readBytes(input, size)
                    longName = String(nameBytes, StandardCharsets.UTF_8).trimEnd('\u0000').trim()
                    skipPadding(input, size)
                    continue
                }

                val norm = entryName.replace('\\', '/').trimStart('/')
                if (norm == targetPath || norm == "$targetPath/") {
                    val isDir = typeFlag == '5' || norm.endsWith('/')
                    if (isDir) {
                        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("can't create directory $dest")
                    } else {
                        val p = dest.parentFile
                        if (p != null && !p.isDirectory && !p.mkdirs()) throw IOException("can't create directory $p")
                        dest.outputStream().use { out ->
                            copyLimited(input, out, size)
                        }
                        if (mtime > 0) dest.setLastModified(mtime)
                    }
                    return
                }

                val pad = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
                skipFully(input, pad)
            }
            throw IOException("Entry not found: $entryPath")
        }
    }

    fun untar(file: File, destDir: File): Int {
        val isGz = file.name.lowercase().endsWith(".tar.gz") || file.name.lowercase().endsWith(".tgz")
        val stream = if (isGz) GZIPInputStream(file.inputStream().buffered()) else file.inputStream().buffered()
        val destRoot = destDir.canonicalPath + File.separator
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("can't create $destDir")
        var failed = 0

        stream.use { input ->
            val block = ByteArray(BLOCK_SIZE)
            var longName: String? = null

            while (true) {
                val read = readFully(input, block)
                if (read < BLOCK_SIZE || isAllZero(block)) break

                val rawName = parseString(block, 0, 100)
                val size = parseOctal(block, 124, 12)
                val mtime = parseOctal(block, 136, 12) * 1000L
                val typeFlag = block[156].toInt().toChar()
                val magic = parseString(block, 257, 6)
                val prefix = if (magic.startsWith("ustar")) parseString(block, 345, 155) else ""

                val entryName = when {
                    longName != null -> {
                        val n = longName!!
                        longName = null
                        n
                    }
                    prefix.isNotEmpty() -> "$prefix/$rawName"
                    else -> rawName
                }

                if (typeFlag == 'L') {
                    val nameBytes = readBytes(input, size)
                    longName = String(nameBytes, StandardCharsets.UTF_8).trimEnd('\u0000').trim()
                    skipPadding(input, size)
                    continue
                }

                val isDir = typeFlag == '5' || entryName.endsWith('/')
                val rel = entryName.replace('\\', '/').trimStart('/')
                if (rel.isEmpty() || rel.split('/').any { it == ".." || it == "." }) {
                    val pad = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
                    skipFully(input, pad)
                    continue
                }

                val target = File(destDir, rel)
                if (!target.canonicalPath.startsWith(destRoot)) {
                    failed++
                    val pad = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
                    skipFully(input, pad)
                    continue
                }

                try {
                    if (isDir) {
                        if (!(target.isDirectory || target.mkdirs())) failed++
                        else if (mtime > 0) target.setLastModified(mtime)
                    } else {
                        val p = target.parentFile
                        if (p != null && !(p.isDirectory || p.mkdirs())) {
                            failed++
                            val pad = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
                            skipFully(input, pad)
                            continue
                        }
                        target.outputStream().use { out ->
                            copyLimited(input, out, size)
                        }
                        if (mtime > 0) target.setLastModified(mtime)
                    }
                } catch (_: IOException) {
                    target.delete()
                    failed++
                }

                val padding = (((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE) - size
                if (padding > 0) skipFully(input, padding)
            }
        }
        return failed
    }

    private fun isAllZero(b: ByteArray): Boolean {
        for (byte in b) {
            if (byte.toInt() != 0) return false
        }
        return true
    }

    private fun parseString(b: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && b[end].toInt() != 0) {
            end++
        }
        return String(b, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseOctal(b: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        for (i in offset until (offset + length)) {
            val byte = b[i].toInt()
            if (byte == 0 || byte == ' '.code) continue
            if (byte in '0'.code..'7'.code) {
                result = (result shl 3) + (byte - '0'.code)
            }
        }
        return result
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val r = input.read(buffer, count, buffer.size - count)
            if (r < 0) break
            count += r
        }
        return count
    }

    private fun readBytes(input: InputStream, count: Long): ByteArray {
        val buf = ByteArray(count.toInt())
        val r = readFully(input, buf)
        if (r < buf.size) throw EOFException("Unexpected EOF in TAR entry body")
        return buf
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val temp = ByteArray(4096)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val r = input.read(temp, 0, minOf(temp.size.toLong(), remaining).toInt())
                if (r < 0) break
                remaining -= r
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE) - size
        if (padding > 0) skipFully(input, padding)
    }

    private fun copyLimited(input: InputStream, out: OutputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(16 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) throw EOFException("Unexpected EOF copying TAR entry")
            out.write(buf, 0, r)
            remaining -= r
        }
    }
}

/**
 * Gzip single-file container helper (.gz).
 */
object GzOps {
    fun readTree(file: File): ZipOps.ZipTree {
        val innerName = file.name.removeSuffix(".gz")
        val size = estimateGzSize(file)
        val info = ZipOps.EntryInfo(innerName, innerName, false, size, file.length(), file.lastModified())
        val map = mapOf("" to listOf(info))
        val byPath = mapOf(innerName to info)
        return ZipOps.ZipTree(1, 0, size, file.length(), map, byPath)
    }

    fun ungz(file: File, dest: File) {
        GZIPInputStream(file.inputStream().buffered()).use { input ->
            dest.outputStream().use { out ->
                input.copyTo(out)
            }
        }
    }

    private fun estimateGzSize(file: File): Long {
        if (file.length() < 4) return file.length()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 4)
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toLong() and 0xFFFFFFFFL
            }
        } catch (_: Exception) {
            file.length()
        }
    }
}
