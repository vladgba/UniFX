package x.vladgba.fileman

import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.min

/**
 * Markdown parser and HTML renderer modelled on SimpleMd4PHP.
 *
 * Supports:
 * - Traditional Markdown: ATX & Setext headings, emphasis (*em*, **strong**, ***both***, _em_, __strong__),
 *   lists, blockquotes, indented code, inline code, links, images, reference links, autolinks, inline/block HTML.
 * - GitHub Flavored Markdown (GFM): fenced code blocks (``` and ~~~), tables with column alignment,
 *   strikethrough (~one~ and ~~two~~), bare URL & email autolinks, task lists (- [ ] / - [x]),
 *   alerts (> [!NOTE], [!TIP], [!IMPORTANT], [!WARNING], [!CAUTION]), and inline code color swatches
 *   (`#0969da`, `rgb(9, 105, 218)`, `hsl(212, 92%, 45%)`), plus optional newline-as-<br>.
 * - Markdown Extra: definition lists (term / : definition), footnotes ([^id]), abbreviations (*[ABBR]: text),
 *   {#id .class} attributes on headings, fenced code, links and images.
 * - Heading anchors: GitHub-style unique heading ids (#my-heading, -1, -2) and "#" permalinks.
 */
object MarkdownOps {
    private val EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn")

    fun isMarkdown(f: File): Boolean =
        !f.isDirectory && f.name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun parse(text: String, breaks: Boolean = false, anchors: Boolean = true): String =
        Parser(breaks, anchors).parse(text)

    fun text(text: String, breaks: Boolean = false, anchors: Boolean = true): String =
        parse(text, breaks, anchors)

    fun renderPage(
        markdown: String,
        fileName: String = "",
        night: Boolean = false,
        breaks: Boolean = false,
        anchors: Boolean = true,
    ): String {
        val body = parse(markdown, breaks = breaks, anchors = anchors)
        val h1 = H1_RE.matcher(body).let { if (it.find()) decodeEntities(STRIP_TAGS_RE.matcher(it.group(1) ?: "").replaceAll("")).trim() else "" }
        val pageTitle = escAttr(h1.ifEmpty { fileName.ifEmpty { "Markdown" } })
        val themeVars = if (night) {
            ":root{--fg:#e6edf3;--bg:#0d1117;--muted:#9198a1;--line:#3d444d;--code:#151b23;--link:#4493f8}" +
                ".markdown-alert-note{--alert:#4493f8}.markdown-alert-tip{--alert:#3fb950}" +
                ".markdown-alert-important{--alert:#ab7df8}.markdown-alert-warning{--alert:#d29922}.markdown-alert-caution{--alert:#f85149}"
        } else {
            ":root{--fg:#1f2328;--bg:#fff;--muted:#59636e;--line:#d1d9e0;--code:#f6f8fa;--link:#0969da}" +
                "@media (prefers-color-scheme:dark){:root{--fg:#e6edf3;--bg:#0d1117;--muted:#9198a1;--line:#3d444d;--code:#151b23;--link:#4493f8}}" +
                ".markdown-alert-note{--alert:#0969da}.markdown-alert-tip{--alert:#1a7f37}.markdown-alert-important{--alert:#8250df}" +
                ".markdown-alert-warning{--alert:#9a6700}.markdown-alert-caution{--alert:#d1242f}" +
                "@media (prefers-color-scheme:dark){.markdown-alert-note{--alert:#4493f8}.markdown-alert-tip{--alert:#3fb950}" +
                ".markdown-alert-important{--alert:#ab7df8}.markdown-alert-warning{--alert:#d29922}.markdown-alert-caution{--alert:#f85149}}"
        }
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$pageTitle</title>
<style>
$themeVars
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:48rem;margin:0 auto;padding:1.25rem 1rem 3rem;overflow-wrap:break-word}
a{color:var(--link)}
h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.5em 0 .6em}
h1,h2{padding-bottom:.3em;border-bottom:1px solid var(--line)}
[id]{scroll-margin-top:1rem}
.anchor{float:left;width:1em;margin-left:-1em;color:var(--muted);text-decoration:none;opacity:0}
.anchor::before{content:"#"}
:hover>.anchor,.anchor:focus,:target>.anchor{opacity:1}
img,video{max-width:100%;height:auto}
code,pre{font:.875em/1.45 ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;background:var(--code);border-radius:6px}
code{padding:.15em .35em}
pre{padding:1em;overflow:auto}
pre code{padding:0;font-size:1em;background:none}
blockquote{margin:0 0 1em;padding:0 1em;color:var(--muted);border-left:.25em solid var(--line)}
table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:0 0 1em}
th,td{padding:.4em .8em;border:1px solid var(--line)}
hr{height:0;margin:2em 0;border:0;border-top:1px solid var(--line)}
dt{font-weight:600}
dd{margin:0 0 .5em 1.5em}
.footnotes{font-size:.875em;color:var(--muted)}
.color-swatch{display:inline-block;width:.75em;height:.75em;margin-left:.4em;border:1px solid var(--line);border-radius:50%;vertical-align:-.05em}
.markdown-alert{margin:0 0 1em;padding:.5em 1em;border-left:.25em solid var(--alert)}
.markdown-alert>:last-child{margin-bottom:0}
.markdown-alert-title{margin:0 0 .25em;font-weight:600;color:var(--alert)}
</style>
</head>
<body>
<main>
$body</main>
</body>
</html>
"""
    }

    private const val TAGS =
        "address|article|aside|audio|blockquote|body|canvas|center|dd|details|dialog|div|dl|dt|fieldset|figcaption|figure|footer|form|h[1-6]|header|hr|html|iframe|legend|li|link|main|menu|nav|noscript|ol|p|pre|script|section|style|summary|svg|table|tbody|td|template|textarea|tfoot|th|thead|tr|ul|video"

    private val LI = Pattern.compile("^( {0,3})([*+-]|(\\d{1,9})[.)])([ \\t]+|$)")
    private val HR = Pattern.compile("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val FENCE = Pattern.compile("^( {0,3})(`{3,}(?=[^`]*$)|~{3,})[ \\t]*(.*?)[ \\t]*$")
    private val ATX = Pattern.compile("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$")
    private val SETEXT = Pattern.compile("^ {0,3}(=+|-+)[ \\t]*$")
    private val DD = Pattern.compile("^ {0,3}:[ \\t]+")
    private val SEP = Pattern.compile("^ *\\|? *:?-+:? *(?:\\| *:?-+:? *)*\\|? *$")
    private const val ALPHA = "(?:\\s*,\\s*(?:0|1|0?\\.\\d+|\\d{1,3}%))?\\s*\\)"
    private val COLOR = Pattern.compile(
        "^(?:#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})" +
            "|rgba?\\(\\s*\\d{1,3}%?\\s*,\\s*\\d{1,3}%?\\s*,\\s*\\d{1,3}%?" + ALPHA +
            "|hsla?\\(\\s*\\d{1,3}(?:deg)?\\s*,\\s*\\d{1,3}%\\s*,\\s*\\d{1,3}%" + ALPHA + ")$",
        Pattern.CASE_INSENSITIVE
    )

    private val FN_DEF = Pattern.compile("^ {0,3}\\[\\^([^\\]]+)\\]:[ \\t]?(.*)$")
    private val DEF_START = Pattern.compile("^ {0,3}\\*?\\[[^\\]]+\\]:")
    private val ABBR_DEF = Pattern.compile("^ {0,3}\\*\\[([^\\]]+)\\]:[ \\t]*(.*)$")
    private val REF_DEF = Pattern.compile(
        "^ {0,3}\\[([^\\]]+)\\]:[ \\t]*<?([^\\s>]+)>?(?:[ \\t]+(?:\"(.*)\"|'(.*)'|\\((.*)\\)))?(?:[ \\t]+\\{([#.][^}]*)\\})?[ \\t]*$"
    )
    private val REF_TITLE_NEXT = Pattern.compile("^[ \\t]+(?:\"(.*)\"|'(.*)'|\\((.*)\\))[ \\t]*$")

    private val QUOTE_START = Pattern.compile("^ {0,3}>")
    private val QUOTE_LINE = Pattern.compile("^ {0,3}> ?(.*)$")
    private val ALERT_LINE = Pattern.compile("^[ \\t]*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\][ \\t]*$", Pattern.CASE_INSENSITIVE)
    private val TASK_ITEM = Pattern.compile("^\\[([ xX])\\][ \\t]+")

    private val HTML_BLOCK_START = Pattern.compile("^ {0,3}<(?:(!--)|(/?)($TAGS)(?=[\\s/>]|$))", Pattern.CASE_INSENSITIVE)
    private val CUSTOM_TAG_LINE = Pattern.compile("^ {0,3}</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?>[ \\t]*$")
    private val STARTS_BLOCK = Pattern.compile(
        "^ {0,3}(?:`{3,}|~{3,}|#{1,6}(?:[ \\t]|$)|>|[*+-][ \\t]+\\S|1[.)][ \\t]+\\S|([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$|<(?:!--|/?(?:$TAGS)(?:[\\s/>]|$)))",
        Pattern.CASE_INSENSITIVE
    )

    private val HEADING_ATTR = Pattern.compile("^(.*?)[ \\t]*\\{([#.][^}]*)\\}$")
    private val ATX_TRAIL = Pattern.compile("(?:^|[ \\t]+)#+$")
    private val EXPLICIT_ID = Pattern.compile("(?:^|\\s)#(\\S+)")
    private val EXTRACT_ID = Pattern.compile(" id=\"([^\"]*)\"")
    private val FENCE_ATTR = Pattern.compile("\\{([^}]*)\\}$")
    private val SLUG_STRIP = Pattern.compile("[^\\p{L}\\p{M}\\p{N}_\\- ]+")
    private val H1_RE = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
    private val STRIP_TAGS_RE = Pattern.compile("<[^>]*>")
    private val ENTITY_RE = Pattern.compile("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([A-Za-z][A-Za-z0-9]*));")
    private val PLACEHOLDER_RE = Pattern.compile("\u001A(\\d+)\u001A")
    private val HARD_BR_RE = Pattern.compile(" {2,}\n")
    private val SOFT_BR_RE = Pattern.compile("(?<!<br />)\n")
    private val URL_UNESC_RE = Pattern.compile("\\\\([!-/:-@\\[-`{-~])")
    private val WS_RE = Pattern.compile("\\s+")

    // Emph patterns anchored at region start (^)
    private val RE_DEL2 = Pattern.compile("^~~(?=[^\\s~])(.+?)(?<=[^\\s~])~~(?!~)", Pattern.DOTALL)
    private val RE_DEL1 = Pattern.compile("^~(?=[^\\s~])(.+?)(?<=[^\\s~])~(?!~)", Pattern.DOTALL)
    private val RE_STAR2 = Pattern.compile("^\\*\\*(?=\\S)((?>[^*]|\\*[^*]*\\*)+?)(?<=\\S)\\*\\*", Pattern.DOTALL)
    private val RE_STAR1 = Pattern.compile("^\\*(?=\\S)((?>[^*]|\\*\\*[^*]+?\\*\\*)+?)(?<=\\S)\\*(?!\\*[^*])", Pattern.DOTALL)
    private val RE_UNDER2 = Pattern.compile("^__(?=\\S)((?>[^_]|_[^_]*_)+?)(?<=\\S)__(?![\\p{L}\\p{N}])", Pattern.DOTALL)
    private val RE_UNDER1 = Pattern.compile("^_(?=\\S)((?>[^_]|__[^_]*__)+?)(?<=\\S)_(?![\\p{L}\\p{N}_])", Pattern.DOTALL)

    // Numbered groups for API 21 compatibility (no named groups):
    // 1: esc
    // 2: tick, 3: code
    // 4: auto
    // 5: mail
    // 6: html
    // 7: fn
    // 8: img, 9: txt, 10: url, 11: titleDbl, 12: titleSgl, 13: titlePar, 14: ref, 15: attr
    // 16: bmail
    // 17: www
    private val INLINE = Pattern.compile(
        "\\\\([\\\\`*_{}\\[\\]()#+\\-.!<>|:~\"'\\n])" +
            "|(`+)(.+?)(?<!`)\\2(?!`)" +
            "|<((?:https?|ftp)://[^\\s<>]+|mailto:[^\\s<>]+)>" +
            "|<([^\\s<>@/]+@[^\\s<>@]+\\.[^\\s<>@]+)>" +
            "|(<!--.*?-->|</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?>)" +
            "|\\[\\^([^\\]\\s]+)\\]" +
            "|(!?)\\[((?:[^\\[\\]]|\\[(?:[^\\[\\]]|\\[[^\\[\\]]*\\])*\\])*)\\]" +
            "(?:\\([ \\t]*<?((?:[^\\s()<>]|\\([^\\s()<>]*\\))*)>?" +
            "(?:\\s+\"(.*?)\"|\\s+'(.*?)'|\\s+\\(([^)]*)\\))?[ \\t]*\\)" +
            "|\\s?\\[([^\\]]*)\\])?" +
            "(?:\\{([#.][^}]*)\\})?" +
            "|(?<![\\w.+\\-/@:])([\\w.+\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)+)(?![\\w@\\-]|\\.[A-Za-z0-9])" +
            "|(?<![\\w/\"'=@])((?:https?://|www\\.)(?:[^\\s<()]|\\([^\\s<()]*\\))+(?<![?!.,:;*_~'\"]))",
        Pattern.DOTALL
    )

    private class Ref(val url: String, val title: String, val attr: String)

    private class Parser(val breaks: Boolean, val anchors: Boolean) {
        private val refs = HashMap<String, Ref>()
        private val notes = HashMap<String, List<String>>()
        private val used = HashMap<String, IntArray>() // key -> [num, count]
        private val usedOrder = ArrayList<String>()
        private val abbr = LinkedHashMap<String, String>()
        private var abbrKeys = emptyList<String>()
        private val tok = ArrayList<String>()
        private val ids = HashSet<String>()
        private var inLink = false

        fun parse(raw: String): String {
            var text = if (raw.startsWith("\uFEFF")) raw.substring(1) else raw
            text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u001A", "")
            val rawLines = text.split('\n').map { expandLeadingTabs(it) }
            val lines = definitions(rawLines)

            if (abbr.isNotEmpty()) {
                abbrKeys = abbr.keys.sortedByDescending { it.length }
            }

            var html = blocks(lines)

            if (usedOrder.isNotEmpty()) {
                val li = ArrayList<String>()
                var k = 0
                while (k < usedOrder.size) { // notes may reference further notes
                    val key = usedOrder[k++]
                    val id = escAttr(key)
                    var h = blocks(notes[key] ?: emptyList())
                    val back = " <a href=\"#fnref:$id\" class=\"footnote-backref\">&#8617;&#xFE0E;</a>"
                    h = if (h.endsWith("</p>")) h.substring(0, h.length - 4) + back + "</p>" else h + back
                    li += "<li id=\"fn:$id\">$h</li>"
                }
                html += "\n<div class=\"footnotes\">\n<hr />\n<ol>\n" + li.joinToString("\n") + "\n</ol>\n</div>"
            }
            return "$html\n"
        }

        private fun expandLeadingTabs(line: String): String {
            var i = 0
            var hasTab = false
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
                if (line[i] == '\t') hasTab = true
                i++
            }
            if (!hasTab) return line
            val sb = StringBuilder(line.length + 8)
            for (k in 0 until i) {
                if (line[k] == '\t') {
                    val spaces = 4 - (sb.length % 4)
                    for (s in 0 until spaces) sb.append(' ')
                } else {
                    sb.append(' ')
                }
            }
            sb.append(line, i, line.length)
            return sb.toString()
        }

        private fun definitions(L: List<String>): List<String> {
            val out = ArrayList<String>(L.size)
            var fence: Pattern? = null
            val n = L.size
            var i = 0
            while (i < n) {
                val l = L[i]
                if (fence != null) {
                    if (fence.matcher(l).matches()) fence = null
                    out += l
                    i++
                    continue
                }
                val fm = FENCE.matcher(l)
                val fnm = FN_DEF.matcher(l)
                val abm = ABBR_DEF.matcher(l)
                val rfm = REF_DEF.matcher(l)
                when {
                    fm.matches() -> {
                        val f = fm.group(2)!!
                        fence = Pattern.compile("^ {0,3}${f[0]}{${f.length},}[ \\t]*$")
                        out += l
                    }
                    fnm.matches() -> {
                        val note = ArrayList<String>()
                        note += fnm.group(2) ?: ""
                        while (i + 1 < n) {
                            val x = L[i + 1]
                            if (x.trim().isEmpty()) {
                                var j = i + 1
                                while (j < n && L[j].trim().isEmpty()) j++
                                if (j < n && L[j].startsWith("    ")) {
                                    while (i + 1 < j) { note += ""; i++ }
                                    continue
                                }
                                break
                            }
                            if (x.startsWith("    ")) {
                                note += x.substring(4)
                            } else if (note.last().trim().isNotEmpty() && !DEF_START.matcher(x).find()) {
                                note += x
                            } else {
                                break
                            }
                            i++
                        }
                        notes[key(fnm.group(1)!!)] = note
                    }
                    abm.matches() -> {
                        abbr[esc(abm.group(1)!!)] = (abm.group(2) ?: "").trim()
                    }
                    rfm.matches() -> {
                        var title = (rfm.group(3) ?: "") + (rfm.group(4) ?: "") + (rfm.group(5) ?: "")
                        if (title.isEmpty() && i + 1 < n) {
                            val tm = REF_TITLE_NEXT.matcher(L[i + 1])
                            if (tm.matches()) {
                                title = (tm.group(1) ?: "") + (tm.group(2) ?: "") + (tm.group(3) ?: "")
                                i++
                            }
                        }
                        refs[key(rfm.group(1)!!)] = Ref(rfm.group(2)!!, title, rfm.group(6) ?: "")
                    }
                    else -> out += l
                }
                i++
            }
            return out
        }

        private fun blocks(L: List<String>, tight: Boolean = false): String {
            val out = ArrayList<String>()
            val n = L.size
            var i = 0
            while (i < n) {
                val l = L[i]
                if (l.trim().isEmpty()) { i++; continue }

                val fm = FENCE.matcher(l)
                val atxm = ATX.matcher(l)
                val hm = HTML_BLOCK_START.matcher(l)
                when {
                    fm.matches() -> {
                        val indent = fm.group(1)!!.length
                        val f = fm.group(2)!!
                        val info = fm.group(3) ?: ""
                        val close = Pattern.compile("^ {0,3}${f[0]}{${f.length},}[ \\t]*$")
                        val code = ArrayList<String>()
                        i++
                        while (i < n && !close.matcher(L[i]).matches()) {
                            code += stripLeadingSpaces(L[i], indent)
                            i++
                        }
                        if (i < n) i++
                        val body = if (code.isNotEmpty()) esc(code.joinToString("\n") + "\n") else ""
                        out += "<pre><code" + fenceAttr(info) + ">$body</code></pre>"
                    }
                    l.startsWith("    ") -> {
                        val code = ArrayList<String>()
                        while (i < n && (L[i].startsWith("    ") || L[i].trim().isEmpty())) {
                            code += if (L[i].startsWith("    ")) L[i].substring(4) else ""
                            i++
                        }
                        while (code.isNotEmpty() && code.last().trim().isEmpty()) code.removeAt(code.lastIndex)
                        out += "<pre><code>" + esc(code.joinToString("\n") + "\n") + "</code></pre>"
                    }
                    atxm.matches() -> {
                        out += heading(atxm.group(1)!!.length, atxm.group(2) ?: "", atx = true)
                        i++
                    }
                    HR.matcher(l).matches() -> {
                        out += "<hr />"
                        i++
                    }
                    QUOTE_START.matcher(l).find() -> {
                        val q = ArrayList<String>()
                        while (i < n) {
                            val qm = QUOTE_LINE.matcher(L[i])
                            if (qm.matches()) {
                                q += qm.group(1) ?: ""
                            } else if (L[i].trim().isNotEmpty() && q.isNotEmpty() && q.last().trim().isNotEmpty() && !startsBlock(L[i])) {
                                q += L[i]
                            } else {
                                break
                            }
                            i++
                        }
                        val am = if (q.isNotEmpty()) ALERT_LINE.matcher(q[0]) else null
                        if (am != null && am.matches()) {
                            val type = am.group(1)!!.lowercase()
                            val title = type.replaceFirstChar { it.uppercase() }
                            val body = blocks(q.subList(1, q.size))
                            out += "<div class=\"markdown-alert markdown-alert-$type\">\n" +
                                "<p class=\"markdown-alert-title\">$title</p>\n" +
                                (if (body.isEmpty()) "" else "$body\n") + "</div>"
                        } else {
                            out += "<blockquote>\n" + blocks(q) + "\n</blockquote>"
                        }
                    }
                    LI.matcher(l).find() -> {
                        val (html, nextI) = listBlock(L, i)
                        out += html
                        i = nextI
                    }
                    hm.find() -> {
                        val (html, nextI) = htmlBlock(L, i, hm)
                        out += html
                        i = nextI
                    }
                    CUSTOM_TAG_LINE.matcher(l).matches() -> {
                        val q = ArrayList<String>()
                        while (i < n && L[i].trim().isNotEmpty()) q += L[i++]
                        out += q.joinToString("\n")
                    }
                    isTable(L, i) -> {
                        val (html, nextI) = table(L, i)
                        out += html
                        i = nextI
                    }
                    isDl(L, i) -> {
                        val (html, nextI) = dl(L, i)
                        out += html
                        i = nextI
                    }
                    else -> {
                        val p = ArrayList<String>()
                        var setext = false
                        while (i < n) {
                            val x = L[i]
                            if (x.trim().isEmpty() || (p.isNotEmpty() && (startsBlock(x) || isTable(L, i)))) break
                            if (i + 1 < n) {
                                val sm = SETEXT.matcher(L[i + 1])
                                if (sm.matches()) {
                                    if (p.isNotEmpty()) out += para(p, tight)
                                    out += heading(if (sm.group(1)!![0] == '=') 1 else 2, x.trim())
                                    i += 2
                                    setext = true
                                    break
                                }
                            }
                            p += x
                            i++
                        }
                        if (!setext) out += para(p, tight)
                    }
                }
            }
            return out.joinToString("\n")
        }

        private fun stripLeadingSpaces(s: String, maxSpaces: Int): String {
            var k = 0
            while (k < maxSpaces && k < s.length && s[k] == ' ') k++
            return if (k == 0) s else s.substring(k)
        }

        private fun startsBlock(x: String): Boolean = STARTS_BLOCK.matcher(x).find()

        private fun para(p: List<String>, tight: Boolean): String {
            val h = inline(p.joinToString("\n").trim())
            return if (tight) h else "<p>$h</p>"
        }

        private fun heading(lvl: Int, rawText: String, atx: Boolean = false): String {
            var t = rawText
            var spec = ""
            val am = HEADING_ATTR.matcher(t)
            if (am.matches()) {
                t = am.group(1) ?: ""
                spec = am.group(2) ?: ""
            }
            if (atx) t = ATX_TRAIL.matcher(t).replaceFirst("")
            val h = inline(t.trim())
            if (!anchors) return "<h$lvl" + attrs(spec) + ">$h</h$lvl>"

            val em = EXPLICIT_ID.matcher(spec)
            if (em.find()) {
                ids += em.group(1)!!
            } else {
                spec += " #" + slug(h)
            }
            val attr = attrs(spec)
            val idm = EXTRACT_ID.matcher(attr)
            val idVal = if (idm.find()) idm.group(1)!! else ""
            return "<h$lvl$attr><a class=\"anchor\" href=\"#$idVal\" aria-hidden=\"true\" tabindex=\"-1\"></a>$h</h$lvl>"
        }

        private fun slug(html: String): String {
            var s = decodeEntities(STRIP_TAGS_RE.matcher(html).replaceAll("")).lowercase().trim()
            s = SLUG_STRIP.matcher(s).replaceAll("").replace(' ', '-')
            if (s.isEmpty()) s = "section"
            var id = s
            var k = 1
            while (id in ids) id = "$s-${k++}"
            ids += id
            return id
        }

        private fun listBlock(L: List<String>, startI: Int): Pair<String, Int> {
            val n = L.size
            var i = startI
            val firstM = LI.matcher(L[i])
            firstM.find()
            val ol = firstM.group(3) != null
            val start = if (ol) firstM.group(3)!!.toInt() else 1
            val base = firstM.group(1)!!.length
            val items = ArrayList<MutableList<String>>()
            var c = -1
            var ind = 0
            var loose = false
            var blank = false
            var blanks = 0

            while (i < n) {
                val l = L[i]
                if (l.trim().isEmpty()) {
                    blank = true
                    blanks++
                    if (c >= 0) items[c] += ""
                    i++
                    continue
                }
                var sp = 0
                while (sp < l.length && l[sp] == ' ') sp++
                val lim = LI.matcher(l)
                val matchesLi = lim.find()
                if (c >= 0 && (sp >= ind || (sp > base + 1 && matchesLi))) {
                    items[c] += l.substring(min(sp, ind))
                } else if (matchesLi && (lim.group(3) != null) == ol && !HR.matcher(l).matches()) {
                    if (blanks > 1) break
                    var w = (lim.group(4) ?: "").length
                    if (w == 0 || w > 4) w = 1
                    ind = lim.group(1)!!.length + lim.group(2)!!.length + w
                    items += mutableListOf(if (ind <= l.length) l.substring(ind) else "")
                    c = items.lastIndex
                } else if (!blank && !startsBlock(l)) {
                    items[c] += l.trimStart()
                } else {
                    break
                }
                if (blank) loose = true
                blank = false
                blanks = 0
                i++
            }

            val html = ArrayList<String>(items.size)
            for (it in items) {
                var task = ""
                if (it.isNotEmpty()) {
                    val tm = TASK_ITEM.matcher(it[0])
                    if (tm.find()) {
                        task = "<input type=\"checkbox\" disabled" + (if (tm.group(1) == " ") "" else " checked") + " /> "
                        it[0] = it[0].substring(tm.end())
                    }
                }
                var h = blocks(it, !loose)
                if (task.isNotEmpty()) {
                    h = if (h.startsWith("<p>")) "<p>" + task + h.substring(3) else task + h
                }
                html += "<li>$h</li>"
            }
            val tag = if (ol) "ol" else "ul"
            val startAttr = if (ol && start != 1) " start=\"$start\"" else ""
            return ("<$tag$startAttr>\n" + html.joinToString("\n") + "\n</$tag>") to i
        }

        private fun htmlBlock(L: List<String>, startI: Int, m: Matcher): Pair<String, Int> {
            val n = L.size
            var i = startI
            val buf = ArrayList<String>()
            if (m.group(1) == "!--") {
                while (i < n) {
                    val line = L[i++]
                    buf += line
                    if (line.contains("-->")) break
                }
            } else if (m.group(2) == "/" || m.group(3)!!.lowercase() in setOf("hr", "link")) {
                while (i < n && L[i].trim().isNotEmpty()) buf += L[i++]
            } else {
                val tag = m.group(3)!!.lowercase()
                val openRe = Pattern.compile("<$tag(?=[\\s>]|$)(?:[^>]*[^/])?>", Pattern.CASE_INSENSITIVE)
                val closeRe = Pattern.compile("</$tag\\s*>", Pattern.CASE_INSENSITIVE)
                var depth = 0
                while (i < n) {
                    val x = L[i++]
                    buf += x
                    depth += countMatches(openRe, x) - countMatches(closeRe, x)
                    if (depth <= 0) break
                }
            }
            return buf.joinToString("\n") to i
        }

        private fun countMatches(p: Pattern, s: String): Int {
            val m = p.matcher(s)
            var c = 0
            while (m.find()) c++
            return c
        }

        private fun isTable(L: List<String>, i: Int): Boolean =
            i + 1 < L.size && '|' in L[i] && '|' in L[i + 1] && SEP.matcher(L[i + 1]).matches()

        private fun table(L: List<String>, startI: Int): Pair<String, Int> {
            var i = startI
            val head = cells(L[i])
            val cols = head.size
            val align = cells(L[i + 1]).map { raw ->
                val s = raw.trim()
                val a = if (s.isNotEmpty() && s.startsWith(':')) {
                    if (s.endsWith(':')) "center" else "left"
                } else if (s.endsWith(':')) {
                    "right"
                } else {
                    ""
                }
                if (a.isEmpty()) "" else " style=\"text-align: $a\""
            }
            fun row(cells: List<String>, tag: String): String {
                val sb = StringBuilder("<tr>")
                for (k in 0 until cols) {
                    val al = align.getOrElse(k) { "" }
                    val cell = cells.getOrElse(k) { "" }.trim()
                    sb.append("<$tag$al>").append(inline(cell)).append("</$tag>")
                }
                sb.append("</tr>\n")
                return sb.toString()
            }
            val h = "<table>\n<thead>\n" + row(head, "th") + "</thead>\n"
            val body = StringBuilder()
            i += 2
            val n = L.size
            while (i < n && L[i].trim().isNotEmpty() && '|' in L[i]) {
                body.append(row(cells(L[i]), "td"))
                i++
            }
            return (h + (if (body.isEmpty()) "" else "<tbody>\n$body</tbody>\n") + "</table>") to i
        }

        /** Split a table row on "|", ignoring escaped pipes and pipes inside `code`. */
        private fun cells(rawRow: String): List<String> {
            var row = rawRow.trim()
            if (row.startsWith("|")) row = row.substring(1)
            if (row.endsWith("|") && !row.endsWith("\\|")) row = row.substring(0, row.length - 1)
            val res = ArrayList<String>()
            var start = 0
            var i = 0
            val len = row.length
            while (i < len) {
                when (row[i]) {
                    '\\' -> i += 2
                    '`' -> {
                        var ticks = 0
                        while (i + ticks < len && row[i + ticks] == '`') ticks++
                        var j = i + ticks
                        var closed = false
                        while (j < len) {
                            if (row[j] == '`') {
                                var r = 0
                                while (j + r < len && row[j + r] == '`') r++
                                if (r == ticks) {
                                    i = j + r
                                    closed = true
                                    break
                                }
                                j += r
                            } else {
                                j++
                            }
                        }
                        if (!closed) i += ticks
                    }
                    '|' -> {
                        res += row.substring(start, i).replace("\\|", "|")
                        i++
                        start = i
                    }
                    else -> i++
                }
            }
            res += row.substring(start).replace("\\|", "|")
            return res
        }

        private fun isDl(L: List<String>, i: Int): Boolean {
            val n = L.size
            var k = i
            while (k < n && L[k].trim().isNotEmpty() && !DD.matcher(L[k]).find()) k++
            if (k == i) return false
            if (k < n && L[k].trim().isEmpty()) k++
            return k < n && DD.matcher(L[k]).find()
        }

        private fun dl(L: List<String>, startI: Int): Pair<String, Int> {
            val n = L.size
            var i = startI
            val h = StringBuilder("<dl>\n")
            while (true) {
                while (i < n && L[i].trim().isNotEmpty() && !DD.matcher(L[i]).find()) {
                    h.append("<dt>").append(inline(L[i++].trim())).append("</dt>\n")
                }
                var loose = false
                if (i < n && L[i].trim().isEmpty()) { loose = true; i++ }
                while (i < n) {
                    val ddm = DD.matcher(L[i])
                    if (!ddm.find()) break
                    val dd = ArrayList<String>()
                    dd += L[i].substring(ddm.end())
                    var ddLoose = loose
                    i++
                    while (i < n) {
                        if (L[i].trim().isNotEmpty()) {
                            if (DD.matcher(L[i]).find()) break
                            dd += stripLeadingSpaces(L[i++], 4)
                            continue
                        }
                        var j = i
                        while (j < n && L[j].trim().isEmpty()) j++
                        if (j < n && L[j].startsWith("    ")) {
                            ddLoose = true
                            while (i < j) { dd += ""; i++ }
                            continue
                        }
                        if (j < n && DD.matcher(L[j]).find()) {
                            ddLoose = true
                            loose = true
                            i = j
                        }
                        break
                    }
                    h.append("<dd>").append(blocks(dd, !ddLoose)).append("</dd>\n")
                }
                var j = i
                while (j < n && L[j].trim().isEmpty()) j++
                if (j < n && isDl(L, j)) {
                    i = j
                    continue
                }
                return h.append("</dl>").toString() to i
            }
        }

        private fun fenceAttr(rawInfo: String): String {
            var info = rawInfo
            var attr = ""
            val m = FENCE_ATTR.matcher(info)
            if (m.find()) {
                attr = m.group(1) ?: ""
                info = info.substring(0, m.start())
            }
            var lang = WS_RE.split(info.trim()).firstOrNull() ?: ""
            if (lang.isNotEmpty() && (lang[0] == '.' || lang[0] == '#')) {
                attr = "$info $attr"
                lang = ""
            }
            return attrs(attr, if (lang.isEmpty()) "" else "language-$lang")
        }

        private fun attrs(s: String, clsInit: String = ""): String {
            var id = ""
            val cls = ArrayList<String>()
            if (clsInit.isNotEmpty()) cls += clsInit
            for (a in WS_RE.split(s.trim())) {
                if (a.isEmpty()) continue
                if (a[0] == '#') id = a.substring(1)
                else if (a[0] == '.') cls += a.substring(1)
            }
            val idStr = if (id.isEmpty()) "" else " id=\"${escAttr(id)}\""
            val clsStr = if (cls.isEmpty()) "" else " class=\"${escAttr(cls.joinToString(" "))}\""
            return idStr + clsStr
        }

        /* ----------------------------------------------------------- inline ---- */

        private fun inline(raw: String): String {
            val m = INLINE.matcher(raw)
            val sb = StringBuffer()
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token(m)))
            }
            m.appendTail(sb)
            var s = escPreserveEntities(sb.toString())

            if (abbrKeys.isNotEmpty()) {
                s = replaceAbbr(s)
            }

            s = emph(s)
            s = HARD_BR_RE.matcher(s).replaceAll("<br />\n")
            if (breaks) s = SOFT_BR_RE.matcher(s).replaceAll("<br />\n")

            val pm = PLACEHOLDER_RE.matcher(s)
            val out = StringBuffer()
            while (pm.find()) {
                val idx = pm.group(1)!!.toInt()
                pm.appendReplacement(out, Matcher.quoteReplacement(tok[idx]))
            }
            pm.appendTail(out)
            return out.toString()
        }

        private fun replaceAbbr(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            val n = s.length
            while (i < n) {
                var matchedKey: String? = null
                val prevWord = if (i > 0) isWordChar(Character.codePointBefore(s, i)) else false
                if (!prevWord) {
                    for (k in abbrKeys) {
                        if (s.regionMatches(i, k, 0, k.length)) {
                            val end = i + k.length
                            val nextWord = if (end < n) isWordChar(Character.codePointAt(s, end)) else false
                            if (!nextWord) {
                                matchedKey = k
                                break
                            }
                        }
                    }
                }
                if (matchedKey != null) {
                    sb.append(keep("<abbr title=\"${escAttr(abbr[matchedKey] ?: "")}\">$matchedKey</abbr>"))
                    i += matchedKey.length
                } else {
                    sb.append(s[i])
                    i++
                }
            }
            return sb.toString()
        }

        private fun isWordChar(cp: Int): Boolean = Character.isLetterOrDigit(cp) || cp == '_'.code

        private fun emph(s: String): String {
            val out = StringBuilder(s.length)
            val n = s.length
            var i = 0
            while (i < n) {
                var k = i
                while (k < n && s[k] != '*' && s[k] != '_' && s[k] != '~') k++
                out.append(s, i, k)
                i = k
                if (i >= n) break
                val c = s[i]
                val prev = if (i > 0) s[i - 1] else ' '
                if (c == '_' && (prev.isLetterOrDigit() || prev.code > 127)) {
                    var u = i
                    while (u < n && s[u] == '_') u++
                    out.append(s, i, u)
                    i = u
                    continue
                }
                val double = i + 1 < n && s[i + 1] == c
                var tildeRun = 0
                val re: Pattern? = when (c) {
                    '~' -> {
                        var r = i
                        while (r < n && s[r] == '~') r++
                        tildeRun = r - i
                        if (tildeRun > 2) null else if (double) RE_DEL2 else RE_DEL1
                    }
                    '*' -> if (double) RE_STAR2 else RE_STAR1
                    else -> if (double) RE_UNDER2 else RE_UNDER1
                }
                val matcher = re?.matcher(s)?.apply {
                    region(i, n)
                    useTransparentBounds(true)
                    useAnchoringBounds(true)
                }
                if (matcher != null && matcher.find() && matcher.group(1)!!.trim('*', '_', '~', ' ').isNotEmpty()) {
                    val tag = if (c == '~') "del" else if (double) "strong" else "em"
                    out.append("<$tag>").append(emph(matcher.group(1)!!)).append("</$tag>")
                    i = matcher.end()
                } else if (c == '~') {
                    for (t in 0 until tildeRun) out.append('~')
                    i += tildeRun
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private fun keep(html: String): String {
            tok += html
            return "\u001A${tok.lastIndex}\u001A"
        }

        private fun token(m: Matcher): String {
            val escGrp = m.group(1)
            if (escGrp != null) return keep(if (escGrp == "\n") "<br />\n" else esc(escGrp))

            val codeGrp = m.group(3)
            if (codeGrp != null) {
                val c = codeGrp.trim()
                val swatch = if (COLOR.matcher(c).matches()) {
                    "<span class=\"color-swatch\" style=\"background-color:${escAttr(c)}\"></span>"
                } else ""
                return keep("<code>" + esc(c) + swatch + "</code>")
            }

            val autoGrp = m.group(4)
            val mailGrp = m.group(5)
            val htmlGrp = m.group(6)
            val fnGrp = m.group(7)
            val imgGrp = m.group(8)
            val txtGrp = m.group(9)
            val bmailGrp = m.group(16)
            val wwwGrp = m.group(17)

            if (inLink && (autoGrp != null || wwwGrp != null || bmailGrp != null || (txtGrp != null && imgGrp != "!"))) {
                return keep(esc(m.group(0)!!))
            }
            if (autoGrp != null) return keep("<a href=\"${escAttr(autoGrp)}\">${esc(autoGrp)}</a>")
            if (mailGrp != null) return keep("<a href=\"mailto:${escAttr(mailGrp)}\">${esc(mailGrp)}</a>")
            if (bmailGrp != null) return keep("<a href=\"mailto:${escAttr(bmailGrp)}\">${esc(bmailGrp)}</a>")
            if (htmlGrp != null) return keep(htmlGrp)
            if (wwwGrp != null) {
                val href = if (wwwGrp.startsWith("www.")) "http://$wwwGrp" else wwwGrp
                return keep("<a href=\"${escAttr(href)}\">${esc(wwwGrp)}</a>")
            }
            if (fnGrp != null) {
                val k = key(fnGrp)
                if (k !in notes) return keep(esc(m.group(0)!!))
                val entry = used.getOrPut(k) {
                    usedOrder += k
                    intArrayOf(used.size + 1, 0)
                }
                val num = entry[0]
                val nth = ++entry[1]
                val id = escAttr(k)
                val refSuffix = if (nth > 1) nth.toString() else ""
                return keep("<sup id=\"fnref$refSuffix:$id\"><a href=\"#fn:$id\" class=\"footnote-ref\">$num</a></sup>")
            }

            // links and images
            val img = imgGrp == "!"
            val text = txtGrp ?: ""
            val urlGrp = m.group(10)
            val refGrp = m.group(14)
            var attrGrp = m.group(15)
            val url: String
            val title: String
            if (urlGrp != null) {
                url = URL_UNESC_RE.matcher(urlGrp).replaceAll("$1")
                title = m.group(11) ?: m.group(12) ?: m.group(13) ?: ""
            } else {
                val lookup = key(if (refGrp != null && refGrp.trim().isNotEmpty()) refGrp else text)
                val ref = refs[lookup]
                if (ref == null) {
                    return keep(
                        (if (img) "!" else "") + "[" + inline(text) + "]" +
                            (if (refGrp != null) "[" + esc(refGrp) + "]" else "") +
                            (if (attrGrp != null) esc("{$attrGrp}") else "")
                    )
                }
                url = ref.url
                title = ref.title
                if (attrGrp == null && ref.attr.isNotEmpty()) attrGrp = ref.attr
            }
            val a = (if (title.isEmpty()) "" else " title=\"${escAttr(title)}\"") + (if (attrGrp != null) attrs(attrGrp) else "")
            val safeUrl = escAttr(url.replace(" ", "%20"))
            return keep(
                if (img) "<img src=\"$safeUrl\" alt=\"${escAttr(text)}\"$a />"
                else "<a href=\"$safeUrl\"$a>${linkText(text)}</a>"
            )
        }

        private fun linkText(text: String): String {
            inLink = true
            val h = inline(text)
            inLink = false
            return h
        }

        private fun key(k: String): String = WS_RE.matcher(k.trim()).replaceAll(" ").lowercase()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun escPreserveEntities(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            when (val c = s[i]) {
                '&' -> {
                    val m = ENTITY_RE.matcher(s).apply { region(i, n) }
                    if (m.lookingAt()) {
                        sb.append(s, i, m.end())
                        i = m.end()
                        continue
                    } else {
                        sb.append("&amp;")
                    }
                }
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun escAttr(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            when (val c = s[i]) {
                '&' -> {
                    val m = ENTITY_RE.matcher(s).apply { region(i, n) }
                    if (m.lookingAt()) {
                        sb.append(s, i, m.end())
                        i = m.end()
                        continue
                    } else {
                        sb.append("&amp;")
                    }
                }
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val m = ENTITY_RE.matcher(s)
        val sb = StringBuffer()
        while (m.find()) {
            val dec = m.group(1)
            val hex = m.group(2)
            val named = m.group(3)
            val rep = when {
                dec != null -> dec.toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.group(0)!!
                hex != null -> hex.toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.group(0)!!
                named != null -> when (named) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> "\u00A0"
                    else -> m.group(0)!!
                }
                else -> m.group(0)!!
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    /**
     * Resolve a relative link path against [baseDir] using SimpleMd4PHP's lookup rules:
     * exact file, `path.md`, `path/index.md`, or `path/README.md`.
     */
    fun resolveRelativeFile(baseDir: File, rawPath: String): File? {
        val clean = rawPath.substringBefore('#').substringBefore('?').trim('/')
        if (clean.isEmpty()) return null
        val direct = File(baseDir, clean)
        if (direct.isFile) return direct
        val stripped = if (clean.lowercase().endsWith(".md")) clean.substring(0, clean.length - 3) else clean
        val dir = File(baseDir, stripped)
        val candidates = listOf(
            File(baseDir, "$stripped.md"),
            File(dir, "index.md"),
            File(dir, "README.md"),
        )
        return candidates.firstOrNull { it.isFile }
    }
}
