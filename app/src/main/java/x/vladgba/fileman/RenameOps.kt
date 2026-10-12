package x.vladgba.fileman

import java.io.File
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Mass rename: compute new names from a pattern, check them, then apply in two phases so swaps and chains
 * (a -> b, b -> a) work. No Android APIs except through [FileOps], so the planning is unit-testable.
 *
 * Wildcard mode (default): [Rule.find] must match the whole name; `*` matches any run of characters, `?`
 * exactly one. In [Rule.replace], each `*` / `?` inserts what the corresponding wildcard matched, in order:
 * find `*.jpeg`, replace `*.jpg`; find `IMG_*`, replace `Photo *`.
 * Regex mode: [Rule.find] is a Java regex replaced everywhere it matches; `$1` etc. refer to groups.
 * Both modes: `[N]` in the replacement is a counter (1, 2, 3 in selection order); `[NNN]` pads to 3 digits.
 */
object RenameOps {
    class Rule(val find: String, val replace: String, val regex: Boolean, val matchCase: Boolean)

    enum class Problem { INVALID_NAME, DUPLICATE, EXISTS }

    /** One selected file: [newName] is null when the rule leaves it unchanged. */
    class Item(val file: File, val newName: String?, val problem: Problem?)

    class Plan(val items: List<Item>, val error: String?) {
        /** Renames that will be performed: changed and without a problem. */
        val renames: List<Item> get() = items.filter { it.newName != null && it.problem == null }
        val problems: Int get() = items.count { it.problem != null }
    }

    private val COUNTER = Regex("""\[(N+)]""")

    private fun expandCounter(template: String, n: Int): String =
        COUNTER.replace(template) { m -> n.toString().padStart(m.groupValues[1].length, '0') }

    /** Wildcard [find] as an anchored regex: each `*` / `?` becomes a capturing group. */
    private fun wildcardPattern(find: String, matchCase: Boolean): Pattern {
        val sb = StringBuilder()
        val lit = StringBuilder()
        fun flush() { if (lit.isNotEmpty()) { sb.append(Pattern.quote(lit.toString())); lit.setLength(0) } }
        for (c in find) when (c) {
            '*' -> { flush(); sb.append("(.*)") }
            '?' -> { flush(); sb.append("(.)") }
            else -> lit.append(c)
        }
        flush()
        return Pattern.compile(sb.toString(), flags(matchCase))
    }

    private fun flags(matchCase: Boolean) =
        if (matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** Compiled form of a rule, so planning many files compiles the pattern once. */
    private class Compiled(val rule: Rule) {
        val pattern: Pattern = if (rule.regex) Pattern.compile(rule.find, flags(rule.matchCase))
        else wildcardPattern(rule.find.ifEmpty { "*" }, rule.matchCase)

        fun apply(name: String, counter: Int): String? {
            val template = expandCounter(rule.replace, counter)
            val m = pattern.matcher(name)
            return if (rule.regex) {
                if (!m.find()) null else m.reset().replaceAll(template)
            } else {
                if (!m.matches()) null else fillWildcards(template, m)
            }
        }

        /** Each `*` / `?` in the template takes the next captured group; extra ones insert nothing. */
        private fun fillWildcards(template: String, m: Matcher): String {
            val sb = StringBuilder()
            var g = 1
            for (c in template) {
                if (c == '*' || c == '?') {
                    if (g <= m.groupCount()) sb.append(m.group(g))
                    g++
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    /** New name for [name] under [rule] (counter value [counter]), or null if the rule doesn't match. */
    fun newName(name: String, rule: Rule, counter: Int = 1): String? = Compiled(rule).apply(name, counter)

    /**
     * Plans renaming [files] (all in one folder, in selection order). [exists] says whether a name is taken in
     * that folder, so tests can run without a file system. A target taken by a file that is itself being
     * renamed away is fine; the two-phase [apply] frees it first.
     */
    fun plan(files: List<File>, rule: Rule, exists: (File) -> Boolean): Plan {
        if (rule.regex && rule.find.isEmpty()) return Plan(files.map { Item(it, null, null) }, null)
        val compiled = try {
            Compiled(rule)
        } catch (e: PatternSyntaxException) {
            return Plan(files.map { Item(it, null, null) }, e.description ?: e.message)
        }
        val raw = files.mapIndexed { i, f ->
            val n = try { compiled.apply(f.name, i + 1) } catch (e: RuntimeException) {
                // A bad group reference ("$3" with two groups) only shows up when the replacement runs.
                return Plan(files.map { Item(it, null, null) }, e.message)
            }
            f to n?.takeIf { it != f.name }
        }
        fun key(s: String) = s.lowercase(Locale.ROOT) // shared storage is case-insensitive: be safe
        val counts = HashMap<String, Int>()
        // Names after the rename: changed files take their new name, unchanged ones keep theirs.
        for ((f, n) in raw) { val k = key(n ?: f.name); counts[k] = (counts[k] ?: 0) + 1 } // not merge(): API 24
        val leaving = raw.filter { it.second != null }.map { key(it.first.name) }.toSet()
        val items = raw.map { (f, n) ->
            val problem = when {
                n == null -> null
                !FileOps.validName(n) -> Problem.INVALID_NAME
                (counts[key(n)] ?: 0) > 1 -> Problem.DUPLICATE
                // Taken by a file outside the selection, or by a selected one that keeps its name.
                key(n) !in leaving && exists(File(f.parentFile, n)) -> Problem.EXISTS
                else -> null
            }
            Item(f, n, problem)
        }
        return Plan(items, null)
    }

    /**
     * Renames every item in [Plan.renames]: first each to a temporary name, then to its final name, so targets
     * freed by other renames in the same batch are available. A failed step is undone where possible.
     * Returns the number of files that couldn't be renamed. Blocking.
     */
    fun apply(plan: Plan): Int {
        val todo = plan.renames
        var failed = 0
        val staged = ArrayList<Pair<Item, File>>()
        for ((i, item) in todo.withIndex()) {
            val tmp = File(item.file.parentFile, ".unifx-rename-$i-${System.nanoTime()}")
            if (item.file.renameTo(tmp)) staged += item to tmp else failed++
        }
        for ((item, tmp) in staged) {
            val target = File(item.file.parentFile, item.newName!!)
            if (!target.exists() && tmp.renameTo(target)) continue
            failed++
            if (!tmp.renameTo(item.file)) tmp.renameTo(FileOps.unique(item.file.parentFile!!, item.file.name))
        }
        return failed
    }
}
