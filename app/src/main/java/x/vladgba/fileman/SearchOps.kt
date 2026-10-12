package x.vladgba.fileman

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.regex.Pattern

/**
 * Recursive search by file name and/or text inside files. Plain JVM code (unit-testable); blocking, so run it
 * on a background thread and pass a [cancelled] check.
 *
 * Name: empty matches everything; with `*` or `?` it is a wildcard that must match the whole name (`*.txt`);
 * otherwise a substring (`report`); or a regex found anywhere in the name.
 * Content: a literal or regex searched line by line in text files (UTF-8, undecodable bytes ignored). Files
 * that look binary (a NUL byte in the first 8 KiB) or are larger than [MAX_CONTENT_BYTES] are skipped.
 * When content is given, only files can match; otherwise folders whose name matches are found too.
 */
object SearchOps {
    const val MAX_CONTENT_BYTES = 64L * 1024 * 1024
    private const val SNIPPET_BEFORE = 40
    private const val SNIPPET_AFTER = 80
    private const val BINARY_PROBE = 8192

    class Query(
        val name: String,
        val nameRegex: Boolean,
        val content: String,
        val contentRegex: Boolean,
        val matchCase: Boolean,
        val includeHidden: Boolean,
    )

    /** [line] is 1-based and 0 for a name-only hit; [snippet] is the text around the first content match. */
    class Hit(val file: File, val isDir: Boolean, val line: Int, val snippet: String?)

    /** Compiled query. Building it throws PatternSyntaxException for a bad regex, before any disk work. */
    class Matcher(q: Query) {
        private val flags = if (q.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        private val name: Pattern? = q.name.trim().takeIf { it.isNotEmpty() }?.let { n ->
            when {
                q.nameRegex -> Pattern.compile(n, flags)
                '*' in n || '?' in n -> Pattern.compile(wildcard(n), flags)
                else -> Pattern.compile(Pattern.quote(n), flags)
            }
        }
        private val nameIsWildcard = !q.nameRegex && q.name.trim().let { '*' in it || '?' in it }
        val content: Pattern? = q.content.takeIf { it.isNotEmpty() }?.let {
            Pattern.compile(if (q.contentRegex) it else Pattern.quote(it), flags)
        }
        val includeHidden = q.includeHidden

        fun nameMatches(n: String): Boolean {
            val p = name ?: return true
            val m = p.matcher(n)
            return if (nameIsWildcard) m.matches() else m.find()
        }

        private fun wildcard(w: String): String {
            val sb = StringBuilder()
            for (c in w) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Pattern.quote(c.toString()))
            }
            return sb.toString()
        }
    }

    /**
     * Walks [root] depth-first (names sorted, folders after the files of their parent), calling [onHit] for every
     * match and [onScanned] with the running count of files and folders looked at. Symlinked folders are entered
     * once (by real path), so link loops end. Returns the number of entries scanned.
     */
    fun search(
        root: File,
        q: Matcher,
        cancelled: () -> Boolean,
        onScanned: (Int) -> Unit = {},
        onHit: (Hit) -> Unit,
    ): Int {
        val seen = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.push(root)
        var scanned = 0
        while (stack.isNotEmpty() && !cancelled()) {
            val dir = stack.pop()
            if (!seen.add(canon(dir))) continue
            val children = dir.listFiles() ?: continue
            children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val subdirs = ArrayList<File>()
            for (f in children) {
                if (cancelled()) break
                if (!q.includeHidden && f.name.startsWith(".")) continue
                scanned++
                if (scanned and 0xFF == 0) onScanned(scanned)
                val isDir = f.isDirectory
                if (isDir) subdirs += f
                if (!q.nameMatches(f.name)) continue
                val content = q.content
                when {
                    content == null -> onHit(Hit(f, isDir, 0, null))
                    !isDir -> findInFile(f, content, cancelled)?.let { onHit(it) }
                }
            }
            for (d in subdirs.asReversed()) stack.push(d) // pop order = alphabetical
        }
        onScanned(scanned)
        return scanned
    }

    /** First line of [f] matching [p], or null (also for binary, oversized or unreadable files). */
    fun findInFile(f: File, p: Pattern, cancelled: () -> Boolean = { false }): Hit? {
        val len = f.length()
        if (len == 0L || len > MAX_CONTENT_BYTES) return null
        return try {
            f.inputStream().buffered(64 * 1024).use { input ->
                input.mark(BINARY_PROBE)
                val probe = ByteArray(BINARY_PROBE)
                val n = input.read(probe)
                for (i in 0 until maxOf(n, 0)) if (probe[i] == 0.toByte()) return null
                input.reset()
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                InputStreamReader(input, decoder).buffered().useLines { lines ->
                    var no = 0
                    for (line in lines) {
                        no++
                        if (no and 0x3FF == 0 && cancelled()) return null
                        val m = p.matcher(line)
                        if (m.find()) return Hit(f, false, no, snippet(line, m.start(), m.end()))
                    }
                }
                null
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** The match with a little context, on one line, with … where text was cut. */
    fun snippet(line: String, start: Int, end: Int): String {
        val from = maxOf(0, start - SNIPPET_BEFORE)
        val to = minOf(line.length, maxOf(end, start) + SNIPPET_AFTER)
        val sb = StringBuilder()
        if (from > 0) sb.append('…')
        sb.append(line, from, to)
        if (to < line.length) sb.append('…')
        return sb.toString().replace('\t', ' ').trim()
    }

    private fun canon(f: File): String = try { f.canonicalPath } catch (e: IOException) { f.absolutePath }
}
