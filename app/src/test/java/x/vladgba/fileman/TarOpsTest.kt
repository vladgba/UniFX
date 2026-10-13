package x.vladgba.fileman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class TarOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun buildTar(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        for ((name, body) in entries) {
            val isDir = name.endsWith('/')
            val data = body.toByteArray(StandardCharsets.UTF_8)
            val header = ByteArray(512)

            // Name
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

            // Mode, UID, GID
            writeAscii(header, 100, "0000644\u0000")
            writeAscii(header, 108, "0000000\u0000")
            writeAscii(header, 116, "0000000\u0000")

            // Size
            val sizeOctal = if (isDir) "00000000000 " else String.format("%011o ", data.size)
            writeAscii(header, 124, sizeOctal)

            // MTime
            writeAscii(header, 136, "14000000000 ")

            // Typeflag
            header[156] = if (isDir) '5'.code.toByte() else '0'.code.toByte()

            // Magic & Version
            writeAscii(header, 257, "ustar\u0000")
            writeAscii(header, 263, "00")

            // Checksum (calculated with checksum field filled with spaces)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0L
            for (b in header) sum += (b.toInt() and 0xFF)
            writeAscii(header, 148, String.format("%06o\u0000 ", sum))

            bos.write(header)
            if (!isDir && data.isNotEmpty()) {
                bos.write(data)
                val pad = ((data.size + 511) / 512 * 512) - data.size
                if (pad > 0) bos.write(ByteArray(pad))
            }
        }
        // Two 512-byte zero blocks for EOF
        bos.write(ByteArray(1024))
        return bos.toByteArray()
    }

    private fun writeAscii(target: ByteArray, offset: Int, str: String) {
        val bytes = str.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, target, offset, bytes.size)
    }

    @Test fun readTarTreeBuildsHierarchy() {
        val tarBytes = buildTar(
            "dir/" to "",
            "dir/sub/file.txt" to "hello in sub",
            "root.txt" to "root content"
        )
        val tarFile = tmp.newFile("sample.tar")
        tarFile.writeBytes(tarBytes)

        val tree = TarOps.readTree(tarFile)
        assertEquals(2, tree.totalFiles)
        assertTrue(tree.totalDirs >= 2)

        val rootList = tree.list("")
        assertEquals(2, rootList.size)
        assertEquals("dir", rootList[0].name)
        assertTrue(rootList[0].isDir)
        assertEquals("root.txt", rootList[1].name)
        assertFalse(rootList[1].isDir)

        val dirList = tree.list("dir/")
        assertEquals(1, dirList.size)
        assertEquals("sub", dirList[0].name)
        assertTrue(dirList[0].isDir)

        val subList = tree.list("dir/sub/")
        assertEquals(1, subList.size)
        assertEquals("file.txt", subList[0].name)
        assertEquals("txt", subList[0].ext)
        assertEquals("hello in sub".length.toLong(), subList[0].size)
    }

    @Test fun readTarGzTreeAndExtract() {
        val tarBytes = buildTar("doc.md" to "# Header")
        val tgzFile = tmp.newFile("bundle.tar.gz")
        GZIPOutputStream(tgzFile.outputStream()).use { gz ->
            gz.write(tarBytes)
        }

        val tree = TarOps.readTree(tgzFile)
        assertEquals(1, tree.totalFiles)
        val list = tree.list("")
        assertEquals(1, list.size)
        assertEquals("doc.md", list[0].name)

        val target = File(tmp.root, "extracted_doc.md")
        TarOps.extractEntry(tgzFile, "doc.md", target)
        assertTrue(target.exists())
        assertEquals("# Header", target.readText())
    }

    @Test fun untarRefusesTarSlip() {
        val tarBytes = buildTar(
            "safe.txt" to "safe",
            "../slip.txt" to "evil"
        )
        val tarFile = tmp.newFile("slip.tar")
        tarFile.writeBytes(tarBytes)

        val destDir = tmp.newFolder("untar_dest")
        val failed = TarOps.untar(tarFile, destDir)
        assertEquals(0, failed)
        assertTrue(File(destDir, "safe.txt").exists())
        assertFalse(File(tmp.root, "slip.txt").exists())
    }

    @Test fun gzSingleFileHelper() {
        val gzFile = tmp.newFile("notes.txt.gz")
        GZIPOutputStream(gzFile.outputStream()).use { gz ->
            gz.write("compressed notes".toByteArray(StandardCharsets.UTF_8))
        }

        val tree = GzOps.readTree(gzFile)
        assertEquals(1, tree.totalFiles)
        val items = tree.list("")
        assertEquals("notes.txt", items[0].name)

        val dest = File(tmp.root, "notes.txt")
        GzOps.ungz(gzFile, dest)
        assertTrue(dest.exists())
        assertEquals("compressed notes", dest.readText())
    }

    @Test fun archiveOpsDetection() {
        assertTrue(ArchiveOps.isArchive(File("app.apk")))
        assertTrue(ArchiveOps.isArchive(File("data.tar")))
        assertTrue(ArchiveOps.isArchive(File("data.tar.gz")))
        assertTrue(ArchiveOps.isArchive(File("data.tgz")))
        assertTrue(ArchiveOps.isArchive(File("logs.gz")))
        assertTrue(ArchiveOps.isArchive(File("bundle.zip")))
        assertTrue(ArchiveOps.isApk(File("app.apk")))
        assertFalse(ArchiveOps.isApk(File("bundle.zip")))
    }
}
