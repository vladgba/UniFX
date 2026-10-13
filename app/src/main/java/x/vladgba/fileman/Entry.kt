package x.vladgba.fileman

import java.io.File

/** Snapshot of a file's attributes, taken once on the loader thread so sorting and binding never hit the disk. */
class Entry(val file: File) {
    val name: String = file.name
    val isDir: Boolean = file.isDirectory
    val size: Long = if (isDir) 0L else file.length()
    val modified: Long = file.lastModified()
    val ext: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

object Kinds {
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico")
    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts")
    private val AUDIO = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "mid", "amr")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar", "aar")
    private val CODE = setOf(
        "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "c", "cpp", "h",
        "hpp", "py", "sh", "gradle", "yml", "yaml", "toml", "ini", "cfg", "conf", "log", "csv", "properties",
        "sql", "rs", "go", "smali", "lua", "php", "rb",
    )
    private val DOC = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub")

    fun color(isDir: Boolean, ext: String): Int = when {
        isDir -> 0xFFF0B429.toInt()
        ext in IMAGE -> 0xFF43A047.toInt()
        ext in VIDEO -> 0xFFE53935.toInt()
        ext in AUDIO -> 0xFF8E24AA.toInt()
        ext in ARCHIVE -> 0xFF795548.toInt()
        ext == "apk" -> 0xFF00897B.toInt()
        ext == "pdf" -> 0xFFC62828.toInt()
        ext in CODE -> 0xFF1E88E5.toInt()
        ext in DOC -> 0xFF3949AB.toInt()
        else -> 0xFF78909C.toInt()
    }

    fun color(e: Entry): Int = color(e.isDir, e.ext)
}
