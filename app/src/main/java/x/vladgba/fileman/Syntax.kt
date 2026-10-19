package x.vladgba.fileman

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import java.io.File
import java.util.regex.Pattern

/**
 * Tiny regex highlighter. Each language is one combined pattern; each alternative is wrapped in its own
 * capturing group and mapped to a token type, so a single pass colors a line (no named groups: API 21).
 * [blocks] are constructs that can span lines (block comments, triple-quoted strings, fenced code): the
 * tokenizer works line by line and carries "inside block N" from one line to the next as an int state.
 */
class Lang(val name: String, rules: List<Pair<Int, String>>, flags: Int = 0, val blocks: List<Block> = emptyList()) {
    val types: IntArray = rules.map { it.first }.toIntArray()
    val pattern: Pattern = Pattern.compile(rules.joinToString("|") { "(" + it.second + ")" }, flags)
}

class Block(val open: String, val close: String, val type: Int)

/** Growable int buffer; spans are stored as (start, end, type) triples. */
class Ints {
    var a = IntArray(96); private set
    var n = 0; private set

    fun add(x: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = x
    }

    fun add(x: Int, y: Int, z: Int) { add(x); add(y); add(z) }

    fun shift(from: Int, step: Int, by: Int) {
        var i = from
        while (i < n) { a[i] += by; a[i + 1] += by; i += step }
    }
}

/** Highlight span. Not Parcelable on purpose, so colors never leak through copy/paste. */
class Hl(private val color: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) { tp.color = color }
}

object Syntax {
    const val COMMENT = 0
    const val STRING = 1
    const val KEYWORD = 2
    const val NUMBER = 3
    const val META = 4 // annotations, tags, headings, preprocessor
    const val ATTR = 5 // keys, attributes, properties
    const val TYPE = 6

    //                       comment     string      keyword     number      meta        attr        type
    private val LIGHT = intArrayOf(0xFF8C8C8C.i, 0xFF067D17.i, 0xFF0033B3.i, 0xFF1750EB.i, 0xFF9E880D.i, 0xFF871094.i, 0xFF008080.i)
    private val DARK = intArrayOf(0xFF7A7E85.i, 0xFF6AAB73.i, 0xFFCF8E6D.i, 0xFF2AACB8.i, 0xFFB3AE60.i, 0xFFC77DBB.i, 0xFF4EC9B0.i)
    private val Long.i get() = toInt()

    fun palette(night: Boolean) = if (night) DARK else LIGHT

    // ------------------------------------------------------------------ shared fragments

    private const val DQ = "\"(?:[^\"\\\\\\n]|\\\\.)*\"?"
    private const val SQ = "'(?:[^'\\\\\\n]|\\\\.)*'?"
    private const val BT = "`[^`]*`?"
    private const val TRIPLE = "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z)"
    private const val NUM = """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*\.?\d*(?:[eE][+-]?\d+)?)[fFdDlLuU]*\b"""
    private const val BLOCK = """/\*[\s\S]*?(?:\*/|\z)"""

    private fun words(s: String) = "\\b(?:" + s.trim().split(Regex("\\s+")).joinToString("|") + ")\\b"

    private val C_KEYWORDS = words(
        """
        abstract actual annotation as assert async auto await bool boolean break by byte case catch chan char class
        companion const constructor continue crossinline data default defer delegate delete do double dynamic else enum
        expect export extends extern external false final finally float fn for fun func function get go goto if impl
        implements import in infix init inline inner instanceof int interface internal is lateinit let long match mod
        module mut namespace native new nil noinline null object open operator out override package private protected
        pub public range reified return sealed select self set short signed sizeof static string struct super suspend
        switch synchronized tailrec this throw throws trait transient true try type typealias typedef typeof undefined
        union unsafe unsigned use using val var vararg virtual void volatile when where while with yield
        """
    )

    private val HASH_KEYWORDS = words(
        """
        and as assert async await break case class continue def del do done elif else end esac except export fi
        finally for from function global if import in is lambda local module nonlocal not or pass raise readonly
        require return self then try unless until while with yield True False None true false nil echo
        """
    )

    private val SQL_KEYWORDS = "(?i:" + words(
        """
        select from where and or not insert into values update set delete create table drop alter add index view
        join left right inner outer full on as group by order having limit offset union all distinct null is in
        like between case when then else end primary key foreign references default exists begin commit rollback
        integer int text varchar real blob boolean desc asc count sum avg min max pragma trigger
        """
    ) + ")"

    // ------------------------------------------------------------------ languages

    private val C_LIKE = Lang(
        "Code",
        listOf(
            COMMENT to "//[^\\n]*|$BLOCK",
            STRING to "$TRIPLE|$DQ|$SQ|$BT",
            META to "@\\w+|#\\s*(?:include|define|ifdef|ifndef|endif|if|else|elif|pragma|undef|import)\\b[^\\n]*",
            KEYWORD to C_KEYWORDS,
            TYPE to "\\b[A-Z][A-Za-z0-9_]*\\b",
            NUMBER to NUM,
        ),
        blocks = listOf(Block("/*", "*/", COMMENT), Block("\"\"\"", "\"\"\"", STRING)),
    )

    private val HASH = Lang(
        "Script",
        listOf(
            COMMENT to "#[^\\n]*",
            STRING to "$TRIPLE|$DQ|$SQ",
            META to "@\\w+|\\$\\{?\\w+\\}?",
            KEYWORD to HASH_KEYWORDS,
            TYPE to "\\b[A-Z][A-Za-z0-9_]*\\b",
            NUMBER to NUM,
        ),
        blocks = listOf(Block("\"\"\"", "\"\"\"", STRING), Block("'''", "'''", STRING)),
    )

    private val CONFIG = Lang(
        "Config",
        listOf(
            COMMENT to "^[ \\t]*[#;!][^\\n]*|\\s#[^\\n]*",
            META to "^[ \\t]*\\[[^\\]\\n]*\\]",
            ATTR to "^[ \\t-]*[^\\s=:#;!\\[][^=:\\n]*?(?=[ \\t]*[=:])",
            STRING to "$DQ|$SQ",
            KEYWORD to words("true false yes no on off null none"),
            NUMBER to NUM,
        ),
        Pattern.MULTILINE
    )

    private val XML = Lang(
        "Markup",
        listOf(
            COMMENT to "<!--[\\s\\S]*?(?:-->|\\z)",
            META to "<[?!][\\w-]*|</?[\\w:.\\-]+|/?>|\\?>",
            ATTR to "[\\w:.\\-]+(?=\\s*=)",
            STRING to "\"[^\"\\n]*\"|'[^'\\n]*'",
            NUMBER to "&[#\\w]+;",
        ),
        blocks = listOf(Block("<!--", "-->", COMMENT)),
    )

    private val JSON = Lang(
        "JSON",
        listOf(
            COMMENT to "//[^\\n]*|$BLOCK",
            ATTR to "\"(?:[^\"\\\\\\n]|\\\\.)*\"(?=\\s*:)",
            STRING to DQ,
            KEYWORD to words("true false null"),
            NUMBER to "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b",
        ),
        blocks = listOf(Block("/*", "*/", COMMENT)),
    )

    private val CSS = Lang(
        "CSS",
        listOf(
            COMMENT to BLOCK,
            STRING to "$DQ|$SQ",
            META to "@[\\w-]+",
            NUMBER to "#[0-9a-fA-F]{3,8}\\b|-?\\b\\d*\\.?\\d+(?:px|em|rem|%|vh|vw|ms|s|deg|dp|sp|fr)?\\b",
            KEYWORD to "!important",
            ATTR to "[\\w-]+(?=\\s*:)",
            TYPE to "[.#][\\w-]+",
        ),
        blocks = listOf(Block("/*", "*/", COMMENT)),
    )

    private val SQL = Lang(
        "SQL",
        listOf(
            COMMENT to "--[^\\n]*|$BLOCK",
            STRING to "$SQ|$DQ",
            KEYWORD to SQL_KEYWORDS,
            NUMBER to NUM,
        ),
        blocks = listOf(Block("/*", "*/", COMMENT)),
    )

    private val MARKDOWN = Lang(
        "Markdown",
        listOf(
            STRING to "(?:```|~~~)[\\s\\S]*?(?:```|~~~|\\z)|``[^`\\n]+``|`[^`\\n]+`",
            META to "^#{1,6}[ \\t]+[^\\n]*|^[=-]{2,}[ \\t]*$|^>[ \\t]*\\[!(?:NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]|^\\*\\[[^\\]\\n]+\\]:",
            COMMENT to "^>[^\\n]*|<!--[\\s\\S]*?(?:-->|\\z)",
            KEYWORD to "\\*\\*\\*[^*\\n]+\\*\\*\\*|___[^_\\n]+___|(?<!\\w)\\*\\*[^*\\n]+\\*\\*(?!\\w)|(?<!\\w)__[^_\\n]+__(?!\\w)|(?<!\\w)\\*[^*\\n]+\\*(?!\\w)|(?<!\\w)_[^_\\n]+_(?!\\w)|~~[^~\\n]+~~|~[^~\\s\\n][^~\\n]*~",
            ATTR to "\\{\\s*(?:[#.][-\\w]+\\s*)+\\}|\\[\\^[^\\]\\n]+\\]:?|!?\\[[^\\]\\n]*\\](?:\\([^)\\n]*\\)|\\[[^\\]\\n]*\\])|^\\[[^\\^\\]\\n][^\\]\\n]*\\]:[ \\t]+\\S+",
            NUMBER to "^[ \\t]*(?:[-*+](?:[ \\t]+\\[[ xX]\\])?|\\d+[.)]|:[ \\t])(?=\\s)",
        ),
        Pattern.MULTILINE,
        blocks = listOf(Block("```", "```", STRING), Block("~~~", "~~~", STRING), Block("<!--", "-->", COMMENT)),
    )

    private val BY_EXT: Map<String, Lang> = HashMap<String, Lang>().apply {
        fun reg(lang: Lang, exts: String) = exts.split(' ').forEach { put(it, lang) }
        reg(C_LIKE, "kt kts java js mjs cjs jsx ts tsx c h cpp cc cxx hpp hh cs go rs swift dart gradle groovy scala php m mm proto aidl glsl vert frag")
        reg(HASH, "py pyw sh bash zsh fish rb pl r cmake ps1 mk makefile dockerfile gitignore gitattributes dockerignore")
        reg(CONFIG, "yml yaml toml ini cfg conf properties prop env editorconfig")
        reg(XML, "xml html htm xhtml svg xsl xslt plist vue")
        reg(JSON, "json jsonc json5 webmanifest")
        reg(CSS, "css scss less")
        reg(SQL, "sql")
        reg(MARKDOWN, "md markdown mdown mkd mkdn")
    }

    private val PLAIN_EXT = setOf("txt", "log", "csv", "tsv", "nfo", "srt", "diff", "patch", "bat", "cmd", "lua", "smali", "asm", "s")
    private val TEXT_NAMES = setOf("makefile", "dockerfile", "license", "readme", "changelog", "authors", "notice", "copying")

    private fun key(f: File): String {
        val n = f.name.lowercase()
        return if (n in TEXT_NAMES) n else n.substringAfterLast('.', "")
    }

    fun langFor(f: File): Lang? = BY_EXT[key(f)]

    fun isText(f: File): Boolean {
        val k = key(f)
        return k in BY_EXT || k in PLAIN_EXT || k in TEXT_NAMES
    }

    /**
     * Tokenize one line (no '\n'). [entry] is the state at the line start (0 = normal, N = inside
     * lang.blocks[N-1]). Appends (start, end, type) triples to [out] when non-null; returns the state at the
     * line end, so the next line can continue an open block comment / multi-line string.
     */
    fun line(lang: Lang, s: String, entry: Int, out: Ints?): Int {
        var pos = 0
        if (entry > 0) {
            val b = lang.blocks[entry - 1]
            val idx = s.indexOf(b.close)
            if (idx < 0) { out?.add(0, s.length, b.type); return entry }
            pos = idx + b.close.length
            out?.add(0, pos, b.type)
            if (pos >= s.length) return 0
        }
        val m = lang.pattern.matcher(s)
        m.useAnchoringBounds(false) // so '^' still means real line start after a block close
        m.region(pos, s.length)
        val groups = m.groupCount()
        var state = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            for (g in 1..groups) {
                val st = m.start(g)
                if (st < 0) continue
                val en = m.end(g)
                val type = lang.types[g - 1]
                out?.add(st, en, type)
                if (en == s.length) state = openBlock(lang, s, st, en, type)
                break
            }
        }
        return state
    }

    /** If the token [st, en) that reaches the line end opens a block without closing it, return that block's state. */
    private fun openBlock(lang: Lang, s: String, st: Int, en: Int, type: Int): Int {
        lang.blocks.forEachIndexed { i, b ->
            if (b.type == type && s.startsWith(b.open, st)) {
                val closed = en - st >= b.open.length + b.close.length && s.startsWith(b.close, en - b.close.length)
                return if (closed) 0 else i + 1
            }
        }
        return 0
    }

    /** Highlight a standalone line into a new spannable (used by the viewer, off the UI thread). */
    fun spannable(lang: Lang, text: CharSequence, colors: IntArray): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        val out = Ints()
        line(lang, text.toString(), 0, out)
        var i = 0
        while (i < out.n) {
            sb.setSpan(Hl(colors[out.a[i + 2]]), out.a[i], out.a[i + 1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i += 3
        }
        return sb
    }
}
