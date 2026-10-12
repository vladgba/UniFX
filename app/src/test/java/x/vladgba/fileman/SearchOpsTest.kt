package x.vladgba.fileman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.regex.PatternSyntaxException

class SearchOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File

    private fun file(rel: String, text: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

    @Before fun tree() {
        root = tmp.newFolder("root")
        file("notes.txt", "shopping list\nmilk\nBread\n")
        file("Report 2024.TXT", "quarterly numbers: 42\n")
        file("docs/readme.md", "# Title\nSee the REPORT for details.\n")
        file("docs/deep/inner/todo.txt", "fix the bug in line 7\n")
        file(".hidden/secret.txt", "milk\n")
        file("docs/.dotfile", "milk\n")
        File(root, "docs/bin.dat").writeBytes(byteArrayOf(0x6D, 0x69, 0x6C, 0x6B, 0, 1, 2)) // "milk" + NULs
        File(root, "empty").mkdirs()
    }

    private fun q(
        name: String = "", nameRegex: Boolean = false, content: String = "", contentRegex: Boolean = false,
        matchCase: Boolean = false, hidden: Boolean = false,
    ) = SearchOps.Matcher(SearchOps.Query(name, nameRegex, content, contentRegex, matchCase, hidden))

    private fun run(m: SearchOps.Matcher): List<SearchOps.Hit> {
        val hits = ArrayList<SearchOps.Hit>()
        SearchOps.search(root, m, { false }) { hits += it }
        return hits
    }

    private fun names(m: SearchOps.Matcher) = run(m).map { it.file.relativeTo(root).invariantSeparatorsPath }

    @Test fun nameSubstringIsCaseInsensitiveByDefault() {
        assertEquals(listOf("Report 2024.TXT"), names(q(name = "report")))
        assertEquals(emptyList<String>(), names(q(name = "report", matchCase = true)))
    }

    @Test fun wildcardMustMatchWholeName() {
        assertEquals(listOf("notes.txt", "Report 2024.TXT", "docs/deep/inner/todo.txt"), names(q(name = "*.txt")))
        assertEquals(listOf("docs/deep/inner/todo.txt"), names(q(name = "t?do.*")))
        assertEquals(emptyList<String>(), names(q(name = "*.tx")))
    }

    @Test fun regexIsFoundAnywhereInTheName() {
        assertEquals(listOf("Report 2024.TXT"), names(q(name = """\d{4}""", nameRegex = true)))
    }

    @Test fun nameOnlySearchFindsFoldersToo() {
        assertEquals(listOf("docs/deep"), names(q(name = "deep")))
        assertTrue(run(q(name = "deep")).single().isDir)
    }

    @Test fun hiddenEntriesAreSkippedUnlessAsked() {
        assertEquals(listOf("notes.txt"), names(q(content = "milk")))
        assertEquals(
            listOf("notes.txt", ".hidden/secret.txt", "docs/.dotfile"),
            names(q(content = "milk", hidden = true)),
        )
    }

    @Test fun contentHitReportsLineAndSnippet() {
        val hit = run(q(content = "bread")).single()
        assertEquals("notes.txt", hit.file.name)
        assertEquals(3, hit.line)
        assertEquals("Bread", hit.snippet)
    }

    @Test fun contentMatchCaseAndRegex() {
        assertEquals(emptyList<String>(), names(q(content = "bread", matchCase = true)))
        assertEquals(listOf("Report 2024.TXT", "docs/deep/inner/todo.txt"), names(q(content = """\d+$""", contentRegex = true)))
    }

    @Test fun nameAndContentCombine() {
        assertEquals(listOf("docs/readme.md"), names(q(name = "*.md", content = "report")))
        assertEquals(emptyList<String>(), names(q(name = "*.txt", content = "# Title")))
    }

    @Test fun binaryFilesAreNotSearchedForText() {
        assertTrue(names(q(content = "milk")).none { it.endsWith("bin.dat") })
    }

    @Test fun badRegexFailsBeforeSearching() {
        try {
            q(content = "(", contentRegex = true)
            throw AssertionError("expected PatternSyntaxException")
        } catch (e: PatternSyntaxException) {
            // expected
        }
    }

    @Test fun cancellationStopsTheWalk() {
        var hits = 0
        SearchOps.search(root, q(), { hits >= 2 }) { hits++ }
        assertEquals(2, hits)
    }

    @Test fun snippetCutsLongLines() {
        val line = "a".repeat(100) + "NEEDLE" + "b".repeat(200)
        val s = SearchOps.snippet(line, 100, 106)
        assertTrue(s.startsWith("…") && s.endsWith("…") && "NEEDLE" in s)
        assertEquals(1 + 40 + 6 + 80 + 1, s.length)
    }

    @Test fun findInFileIgnoresEmptyAndMissingFiles() {
        val p = java.util.regex.Pattern.compile("x")
        assertNull(SearchOps.findInFile(File(root, "missing.txt"), p))
        assertNull(SearchOps.findInFile(File(root, "e.txt").apply { writeText("") }, p))
    }
}
