package x.vladgba.fileman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import x.vladgba.fileman.RenameOps.Problem
import x.vladgba.fileman.RenameOps.Rule as R

class RenameOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun wild(find: String, replace: String, matchCase: Boolean = false) = R(find, replace, false, matchCase)
    private fun regex(find: String, replace: String, matchCase: Boolean = false) = R(find, replace, true, matchCase)

    // ---------------------------------------------------------------- single names

    @Test fun wildcardChangesExtension() {
        assertEquals("photo.jpg", RenameOps.newName("photo.jpeg", wild("*.jpeg", "*.jpg")))
        assertEquals("a.b.jpg", RenameOps.newName("a.b.jpeg", wild("*.jpeg", "*.jpg")))
    }

    @Test fun wildcardMustMatchWholeName() {
        assertNull(RenameOps.newName("photo.jpeg.bak", wild("*.jpeg", "*.jpg")))
        assertNull(RenameOps.newName("notes.txt", wild("IMG_*", "Photo *")))
    }

    @Test fun wildcardGroupsFillInOrder() {
        assertEquals("Photo 0042.jpg", RenameOps.newName("IMG_0042.jpg", wild("IMG_*", "Photo *")))
        assertEquals("notes_backup.txt", RenameOps.newName("notes.txt", wild("*.*", "*_backup.*")))
        assertEquals("a-b", RenameOps.newName("a1b", wild("?1?", "?-?")))
    }

    @Test fun extraWildcardsInReplacementInsertNothing() {
        assertEquals("a-", RenameOps.newName("a.txt", wild("*.txt", "*-*")))
    }

    @Test fun wildcardIgnoresCaseUnlessAsked() {
        assertEquals("x.jpg", RenameOps.newName("x.JPEG", wild("*.jpeg", "*.jpg")))
        assertNull(RenameOps.newName("x.JPEG", wild("*.jpeg", "*.jpg", matchCase = true)))
    }

    @Test fun regexReplacesEveryMatch() {
        assertEquals("my_holiday_photo.jpg", RenameOps.newName("my holiday photo.jpg", regex(" ", "_")))
        assertEquals("2024-05-01 trip", RenameOps.newName("01.05.2024 trip", regex("""(\d\d)\.(\d\d)\.(\d{4})""", "$3-$2-$1")))
        assertNull(RenameOps.newName("abc", regex("z", "y")))
    }

    @Test fun counterIsPaddedToItsWidth() {
        assertEquals("img 7.jpg", RenameOps.newName("x.jpg", wild("*.jpg", "img [N].jpg"), counter = 7))
        assertEquals("img 007.jpg", RenameOps.newName("x.jpg", wild("*.jpg", "img [NNN].jpg"), counter = 7))
        assertEquals("007_x.jpg", RenameOps.newName("x.jpg", regex("^", "[NNN]_"), counter = 7))
    }

    // ---------------------------------------------------------------- plans

    private fun files(vararg names: String) = names.map { File("/d", it) }

    @Test fun planNumbersInSelectionOrder() {
        val plan = RenameOps.plan(files("b.jpg", "a.jpg"), wild("*.jpg", "pic [NN].jpg")) { false }
        assertEquals(listOf("pic 01.jpg", "pic 02.jpg"), plan.items.map { it.newName })
        assertEquals(0, plan.problems)
    }

    @Test fun unchangedFilesAreLeftAlone() {
        val plan = RenameOps.plan(files("a.jpeg", "b.png"), wild("*.jpeg", "*.jpg")) { false }
        assertEquals(listOf("a.jpg", null), plan.items.map { it.newName })
        assertEquals(1, plan.renames.size)
    }

    @Test fun duplicateTargetsAreFlagged() {
        val plan = RenameOps.plan(files("a.txt", "b.txt"), wild("*.txt", "same.txt")) { false }
        assertTrue(plan.items.all { it.problem == Problem.DUPLICATE })
        assertTrue(plan.renames.isEmpty())
    }

    @Test fun duplicatesAreCaseInsensitive() {
        val plan = RenameOps.plan(files("a.txt", "B.txt"), regex("(?i)[ab]", "X")) { false }
        assertTrue(plan.items.all { it.problem == Problem.DUPLICATE })
    }

    @Test fun clashWithAnUnselectedFileIsFlagged() {
        val plan = RenameOps.plan(files("a.txt"), wild("a.txt", "taken.txt")) { it.name == "taken.txt" }
        assertEquals(Problem.EXISTS, plan.items[0].problem)
    }

    @Test fun clashWithAnUnchangedSelectedFileIsFlagged() {
        // b.txt is selected but doesn't match, so it keeps its name and a.txt can't take it.
        val plan = RenameOps.plan(files("a.txt", "b.txt"), regex("^a", "b")) { it.name == "b.txt" }
        assertEquals(Problem.DUPLICATE, plan.items[0].problem)
    }

    @Test fun swapIsAllowed() {
        // 2.txt -> 1.txt and 1.txt -> 2.txt: each target is a name another selected file is leaving.
        val plan = RenameOps.plan(files("2.txt", "1.txt"), regex("""^\d""", "[N]")) { true }
        assertEquals(listOf("1.txt", "2.txt"), plan.items.map { it.newName })
        assertEquals(0, plan.problems)
    }

    @Test fun invalidNamesAreFlagged() {
        val plan = RenameOps.plan(files("a.txt"), regex("a", "x/y")) { false }
        assertEquals(Problem.INVALID_NAME, plan.items[0].problem)
        val empty = RenameOps.plan(files("a.txt"), regex(".*", "")) { false }
        assertEquals(Problem.INVALID_NAME, empty.items[0].problem)
    }

    @Test fun badRegexIsReportedNotThrown() {
        assertNotNull(RenameOps.plan(files("a"), regex("(", "x")) { false }.error)
        assertNotNull(RenameOps.plan(files("a"), regex("(a)", "$5")) { false }.error)
    }

    // ---------------------------------------------------------------- applying on disk

    @Test fun applySwapsNamesThroughTemporaryNames() {
        val dir = tmp.newFolder()
        val a = File(dir, "a.txt").apply { writeText("A") }
        val b = File(dir, "b.txt").apply { writeText("B") }
        // a.txt -> b.txt and b.txt -> a.txt
        val plan = RenameOps.Plan(
            listOf(RenameOps.Item(a, "b.txt", null), RenameOps.Item(b, "a.txt", null)), null,
        )
        assertEquals(0, RenameOps.apply(plan))
        assertEquals("B", File(dir, "a.txt").readText())
        assertEquals("A", File(dir, "b.txt").readText())
        assertEquals(setOf("a.txt", "b.txt"), dir.list()!!.toSet())
    }

    @Test fun applyRenamesAndNumbers() {
        val dir = tmp.newFolder()
        val fs = listOf("x.jpeg", "y.jpeg", "keep.png").map { File(dir, it).apply { writeText(it) } }
        val plan = RenameOps.plan(fs, wild("*.jpeg", "photo [NN].jpg")) { File(dir, it.name).exists() }
        assertEquals(0, RenameOps.apply(plan))
        assertEquals(setOf("photo 01.jpg", "photo 02.jpg", "keep.png"), dir.list()!!.toSet())
        assertEquals("x.jpeg", File(dir, "photo 01.jpg").readText())
    }
}
