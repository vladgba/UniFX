package x.vladgba.fileman

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(parent: File, name: String, text: String) = File(parent, name).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    /** Builds a zip with raw entry names, bypassing ZipOps (for hostile / odd archives). */
    private fun rawZip(vararg entries: Pair<String, String>): File {
        val out = tmp.newFile("raw.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out
    }

    @Test fun roundTripKeepsFilesFoldersAndEmptyFolders() {
        val src = tmp.newFolder("src")
        val a = file(src, "a.txt", "alpha")
        val dir = File(src, "dir")
        file(dir, "nested/b.txt", "beta")
        File(dir, "empty").mkdirs()
        val bin = File(src, "bin.dat").apply { writeBytes(ByteArray(300_000) { (it * 31).toByte() }) }

        val zip = File(tmp.root, "out.zip")
        assertEquals(0, ZipOps.zip(listOf(a, dir, bin), zip))

        val dest = File(tmp.root, "dest")
        assertEquals(0, ZipOps.unzip(zip, dest))
        assertEquals("alpha", File(dest, "a.txt").readText())
        assertEquals("beta", File(dest, "dir/nested/b.txt").readText())
        assertTrue(File(dest, "dir/empty").isDirectory)
        assertArrayEquals(bin.readBytes(), File(dest, "bin.dat").readBytes())
    }

    @Test fun entriesSitUnderTheSelectedItemsNames() {
        val src = tmp.newFolder("src")
        val dir = File(src, "photos").apply { mkdirs() }
        file(dir, "x.jpg", "x")
        val zip = File(tmp.root, "p.zip")
        ZipOps.zip(listOf(dir), zip)
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("photos/", "photos/x.jpg"), names)
    }

    @Test fun archiveInsideTheZippedFolderIsNotPackedIntoItself() {
        val dir = tmp.newFolder("dir")
        file(dir, "a.txt", "a")
        val zip = File(dir, "dir.zip")
        ZipOps.zip(listOf(dir), zip)
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertFalse(names.any { it.endsWith("dir.zip") })
    }

    @Test fun zipSlipEntriesAreRefused() {
        val zip = rawZip("../evil.txt" to "x", "ok/../../evil2.txt" to "x", "good.txt" to "fine")
        val dest = File(tmp.root, "dest")
        assertEquals(2, ZipOps.unzip(zip, dest))
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertEquals("fine", File(dest, "good.txt").readText())
    }

    @Test fun absoluteAndBackslashNamesStayInsideDest() {
        val zip = rawZip("/abs.txt" to "a", "win\\path.txt" to "w")
        val dest = File(tmp.root, "dest")
        assertEquals(0, ZipOps.unzip(zip, dest))
        assertEquals("a", File(dest, "abs.txt").readText())
        assertEquals("w", File(dest, "win/path.txt").readText())
    }

    @Test fun existingFileIsNeverOverwritten() {
        val zip = rawZip("a.txt" to "from zip")
        val dest = tmp.newFolder("dest")
        file(dest, "a.txt", "mine")
        assertEquals(1, ZipOps.unzip(zip, dest))
        assertEquals("mine", File(dest, "a.txt").readText())
    }

    @Test fun failedZipLeavesNoPartialArchive() {
        val src = file(tmp.root, "a.txt", "a")
        val out = File(tmp.root, "missing-dir/out.zip")
        try {
            ZipOps.zip(listOf(src), out)
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(out.exists())
        }
    }

    @Test fun notAnArchiveThrows() {
        val junk = file(tmp.root, "junk.zip", "not a zip")
        try {
            ZipOps.unzip(junk, File(tmp.root, "dest"))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
