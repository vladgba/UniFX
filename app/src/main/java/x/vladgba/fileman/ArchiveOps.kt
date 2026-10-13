package x.vladgba.fileman

import java.io.File

/**
 * Unified gateway for all supported archives: ZIP, JAR, AAR, APK, EPUB, TAR, TGZ/TAR.GZ, and GZ.
 */
object ArchiveOps {
    val EXTRACTABLE = setOf("zip", "jar", "aar", "apk", "epub", "tar", "tgz", "gz")
    val PREVIEWABLE = setOf("zip", "jar", "aar", "apk", "epub", "tar", "tgz", "gz")

    fun isArchive(f: File): Boolean {
        if (f.isDirectory) return false
        val name = f.name.lowercase()
        return name.endsWith(".tar.gz") || f.name.substringAfterLast('.', "").lowercase() in EXTRACTABLE
    }

    fun isPreviewable(f: File): Boolean {
        if (f.isDirectory) return false
        val name = f.name.lowercase()
        return name.endsWith(".tar.gz") || f.name.substringAfterLast('.', "").lowercase() in PREVIEWABLE
    }

    fun isTar(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")
    }

    fun isGzOnly(f: File): Boolean {
        val name = f.name.lowercase()
        return name.endsWith(".gz") && !name.endsWith(".tar.gz")
    }

    fun isApk(f: File): Boolean = !f.isDirectory && f.name.substringAfterLast('.', "").lowercase() == "apk"

    fun readTree(f: File): ZipOps.ZipTree = when {
        isTar(f) -> TarOps.readTree(f)
        isGzOnly(f) -> GzOps.readTree(f)
        else -> ZipOps.openTree(f)
    }

    fun extractEntry(archive: File, entryPath: String, dest: File) {
        when {
            isTar(archive) -> TarOps.extractEntry(archive, entryPath, dest)
            isGzOnly(archive) -> GzOps.ungz(archive, dest)
            else -> ZipOps.extractEntry(archive, entryPath, dest)
        }
    }

    fun extractAll(archive: File, destDir: File): Int = when {
        isTar(archive) -> TarOps.untar(archive, destDir)
        isGzOnly(archive) -> {
            val innerName = archive.name.removeSuffix(".gz")
            val target = File(destDir, innerName)
            if (!destDir.isDirectory) destDir.mkdirs()
            GzOps.ungz(archive, target)
            0
        }
        else -> ZipOps.unzip(archive, destDir)
    }
}
