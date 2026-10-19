package x.vladgba.fileman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MarkdownOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun headingsAndAnchors() {
        val md = """
            # Main Title
            ## Section
            ## Section
            ### Custom Heading {#custom-id .hero}
            Setext H1
            =========
            Setext H2
            ---------
        """.trimIndent()

        val html = MarkdownOps.text(md, anchors = true)
        assertTrue(html.contains("<h1 id=\"main-title\"><a class=\"anchor\" href=\"#main-title\" aria-hidden=\"true\" tabindex=\"-1\"></a>Main Title</h1>"))
        assertTrue(html.contains("<h2 id=\"section\"><a class=\"anchor\" href=\"#section\" aria-hidden=\"true\" tabindex=\"-1\"></a>Section</h2>"))
        assertTrue(html.contains("<h2 id=\"section-1\"><a class=\"anchor\" href=\"#section-1\" aria-hidden=\"true\" tabindex=\"-1\"></a>Section</h2>"))
        assertTrue(html.contains("<h3 id=\"custom-id\" class=\"hero\"><a class=\"anchor\" href=\"#custom-id\" aria-hidden=\"true\" tabindex=\"-1\"></a>Custom Heading</h3>"))
        assertTrue(html.contains("<h1 id=\"setext-h1\">"))
        assertTrue(html.contains("<h2 id=\"setext-h2\">"))

        val noAnchors = MarkdownOps.text(md, anchors = false)
        assertTrue(noAnchors.contains("<h1>Main Title</h1>"))
        assertFalse(noAnchors.contains("class=\"anchor\""))
    }

    @Test
    fun emphasisStrikethroughAndSnakeCase() {
        val md = "Use *italic*, **bold**, ***both***, _under_, __strong__, ~one~ and ~~two~~, but keep snake_case_identifier intact."
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<strong><em>both</em></strong>"))
        assertTrue(html.contains("<em>under</em>"))
        assertTrue(html.contains("<strong>strong</strong>"))
        assertTrue(html.contains("<del>one</del>"))
        assertTrue(html.contains("<del>two</del>"))
        assertTrue(html.contains("snake_case_identifier"))
    }

    @Test
    fun fencedAndIndentedCodeBlocks() {
        val md = """
            ```php {#snippet .highlight}
            echo "<hello>";
            ```

            ~~~kotlin
            val x = 1
            ~~~

                indented <code_block>
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<pre><code id=\"snippet\" class=\"language-php highlight\">echo \"&lt;hello&gt;\";\n</code></pre>"))
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">val x = 1\n</code></pre>"))
        assertTrue(html.contains("<pre><code>indented &lt;code_block&gt;\n</code></pre>"))
    }

    @Test
    fun gfmTablesWithAlignmentEscapedPipesAndCode() {
        val md = """
            | Left | Center | Right |
            | :--- | :---: | ---: |
            | `a | b` | c \| d | 42 |
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<table>\n<thead>\n<tr>"))
        assertTrue(html.contains("<th style=\"text-align: left\">Left</th>"))
        assertTrue(html.contains("<th style=\"text-align: center\">Center</th>"))
        assertTrue(html.contains("<th style=\"text-align: right\">Right</th>"))
        assertTrue(html.contains("<td style=\"text-align: left\"><code>a | b</code></td>"))
        assertTrue(html.contains("<td style=\"text-align: center\">c | d</td>"))
        assertTrue(html.contains("<td style=\"text-align: right\">42</td>"))
    }

    @Test
    fun gfmAlertsAndBlockquotes() {
        val md = """
            > [!NOTE]
            > Useful information.

            > [!WARNING]
            > Watch out!

            > Regular quote
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<div class=\"markdown-alert markdown-alert-note\">\n<p class=\"markdown-alert-title\">Note</p>\n<p>Useful information.</p>\n</div>"))
        assertTrue(html.contains("<div class=\"markdown-alert markdown-alert-warning\">\n<p class=\"markdown-alert-title\">Warning</p>\n<p>Watch out!</p>\n</div>"))
        assertTrue(html.contains("<blockquote>\n<p>Regular quote</p>\n</blockquote>"))
    }

    @Test
    fun listsAndTaskLists() {
        val md = """
            - [x] Done task
            - [ ] Pending task
              - Nested bullet

            3. Ordered three
            4. Ordered four
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<li><input type=\"checkbox\" disabled checked /> Done task</li>"))
        assertTrue(html.contains("<li><input type=\"checkbox\" disabled /> Pending task\n<ul>\n<li>Nested bullet</li>\n</ul></li>"))
        assertTrue(html.contains("<ol start=\"3\">\n<li>Ordered three</li>\n<li>Ordered four</li>\n</ol>"))
    }

    @Test
    fun inlineCodeColorSwatches() {
        val md = "Colors: `#0969da`, `rgb(9, 105, 218)`, `hsl(212, 92%, 45%)`, `not-a-color`."
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<code>#0969da<span class=\"color-swatch\" style=\"background-color:#0969da\"></span></code>"))
        assertTrue(html.contains("<code>rgb(9, 105, 218)<span class=\"color-swatch\" style=\"background-color:rgb(9, 105, 218)\"></span></code>"))
        assertTrue(html.contains("<code>hsl(212, 92%, 45%)<span class=\"color-swatch\" style=\"background-color:hsl(212, 92%, 45%)\"></span></code>"))
        assertTrue(html.contains("<code>not-a-color</code>"))
    }

    @Test
    fun linksAutolinksAndReferenceLinks() {
        val md = """
            Visit https://example.com/path(1), contact user@example.com or <admin@example.org>.
            Check [Docs][doc-ref] and ![Logo](logo.png "App Logo"){#logo .img}.

            [doc-ref]: https://example.com/docs "Documentation" {#docs .ext}
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<a href=\"https://example.com/path(1)\">https://example.com/path(1)</a>,"))
        assertTrue(html.contains("<a href=\"mailto:user@example.com\">user@example.com</a>"))
        assertTrue(html.contains("<a href=\"mailto:admin@example.org\">admin@example.org</a>"))
        assertTrue(html.contains("<a href=\"https://example.com/docs\" title=\"Documentation\" id=\"docs\" class=\"ext\">Docs</a>"))
        assertTrue(html.contains("<img src=\"logo.png\" alt=\"Logo\" title=\"App Logo\" id=\"logo\" class=\"img\" />"))
    }

    @Test
    fun markdownExtraFootnotesDefinitionsAndAbbreviations() {
        val md = """
            *[HTML]: HyperText Markup Language
            *[GML]: General Markup Language

            HTML is based on GML[^1].

            Term
            :   Definition of the term.

            [^1]: Footnote explanation here.
        """.trimIndent()
        val html = MarkdownOps.text(md)
        assertTrue(html.contains("<abbr title=\"HyperText Markup Language\">HTML</abbr>"))
        assertTrue(html.contains("<abbr title=\"General Markup Language\">GML</abbr>"))
        assertTrue(html.contains("<sup id=\"fnref:1\"><a href=\"#fn:1\" class=\"footnote-ref\">1</a></sup>"))
        assertTrue(html.contains("<dl>\n<dt>Term</dt>\n<dd>Definition of the term.</dd>\n</dl>"))
        assertTrue(html.contains("<div class=\"footnotes\">\n<hr />\n<ol>\n<li id=\"fn:1\"><p>Footnote explanation here. <a href=\"#fnref:1\" class=\"footnote-backref\">&#8617;&#xFE0E;</a></p></li>\n</ol>\n</div>"))
    }

    @Test
    fun breaksOptionAndRenderPage() {
        val md = "Line one\nLine two"
        assertFalse(MarkdownOps.text(md, breaks = false).contains("<br />"))
        assertTrue(MarkdownOps.text(md, breaks = true).contains("Line one<br />\nLine two"))

        val page = MarkdownOps.renderPage("# Hello", fileName = "README.md", night = true)
        assertTrue(page.contains("<!DOCTYPE html>"))
        assertTrue(page.contains("<title>Hello</title>"))
        assertTrue(page.contains("<main>"))

        val untitledPage = MarkdownOps.renderPage("Just text", fileName = "README.md", night = false)
        assertTrue(untitledPage.contains("<title>README.md</title>"))
    }

    @Test
    fun relativeFileResolutionAndSyntaxHighlighting() {
        val root = tmp.newFolder("docs")
        val readme = File(root, "README.md").apply { writeText("# Docs") }
        val guide = File(root, "guide.md").apply { writeText("# Guide") }
        val sub = File(root, "sub").apply { mkdirs() }
        val subIndex = File(sub, "index.md").apply { writeText("# Sub") }

        assertTrue(MarkdownOps.isMarkdown(readme))
        assertEquals(guide.canonicalFile, MarkdownOps.resolveRelativeFile(root, "guide")?.canonicalFile)
        assertEquals(guide.canonicalFile, MarkdownOps.resolveRelativeFile(root, "guide.md#intro")?.canonicalFile)
        assertEquals(subIndex.canonicalFile, MarkdownOps.resolveRelativeFile(root, "sub")?.canonicalFile)

        // Ensure Syntax.MARKDOWN compiles and tokenizes without throwing on API 21 group rules
        val lang = Syntax.langFor(readme)
        assertNotNull(lang)
        val out = Ints()
        val state = Syntax.line(lang!!, "# Heading **bold** `code` [link](url) {#id}", 0, out)
        assertEquals(0, state)
        assertTrue(out.n > 0)
    }
}
