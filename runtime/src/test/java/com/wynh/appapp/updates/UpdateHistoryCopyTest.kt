package com.wynh.appapp.updates

import org.junit.Assert.*
import org.junit.Test

/** Checks what the review copy says (which facts, in which order, what it leaves out), not its exact wording. */
class UpdateHistoryCopyTest {
    private val sha = "fbb03a0673400e96c5ff7ec9fdc331572292c1d4"
    private fun comparison(status: String, total: Int, subjects: List<String>, behind: Int = 0) =
        Comparison(status, if (status == "ahead") total else 0, behind, total, subjects, emptyList())

    @Test fun whatsNewListsNewestCommitsFirstAndCountsTheRest() {
        val subjects = (12 downTo 1).map { "Change $it" }
        val lines = UpdateReviewCopy.whatsNew(comparison("ahead", 12, subjects), limit = 10)
        assertTrue(lines.first().contains("Change 12"))
        assertEquals(10, lines.count { line -> subjects.any { line.contains(it) } })
        assertTrue("the hidden commits are counted", lines.last().contains("2"))

        val all = UpdateReviewCopy.whatsNew(comparison("ahead", 1, listOf("Only")))
        assertEquals("nothing hidden, nothing counted", 1, all.size)
    }

    @Test fun commitsAreListedOnlyWhenTheUpdateIsAhead() {
        assertTrue(UpdateReviewCopy.whatsNew(null).isEmpty())
        assertTrue(UpdateReviewCopy.whatsNew(comparison("behind", 0, emptyList(), behind = 3)).isEmpty())
        assertTrue(UpdateReviewCopy.whatsNew(comparison("diverged", 2, listOf("Elsewhere"))).isEmpty())
    }

    @Test fun historyNoteExplainsEachCaseWhereCommitsAreMissing() {
        val behind = UpdateReviewCopy.historyNote(comparison("behind", 0, emptyList(), behind = 3), false)
        val diverged = UpdateReviewCopy.historyNote(comparison("diverged", 1, emptyList()), false)
        val otherRepository = UpdateReviewCopy.historyNote(null, true)
        assertTrue("names how far behind", behind!!.contains("3"))
        assertNotNull(diverged); assertNotNull(otherRepository)
        assertEquals("each case has its own explanation", 3, setOf(behind, diverged, otherRepository).size)
        assertNull(UpdateReviewCopy.historyNote(comparison("ahead", 1, listOf("x")), false))
        assertNull(UpdateReviewCopy.historyNote(null, false))
    }

    @Test fun aPinnedCommitIsShownShortButStoredInFull() {
        val pinned = GitHubSource("javiercmh", "lingomage", sha)
        assertFalse(pinned.display.contains(sha))
        assertTrue(pinned.display.contains(sha.take(7)))
        assertTrue(pinned.key.contains(sha))
        assertTrue(GitHubSource("javiercmh", "lingomage", "main").display.contains("main"))
    }

    @Test fun aBranchChangeNamesBothBranchesWithoutRepeatingTheRepository() {
        val change = UpdateReviewCopy.sourceChange(GitHubSource("javiercmh", "lingomage", sha), GitHubSource("javiercmh", "lingomage", "main"))
        assertTrue(change.contains(sha.take(7)) && change.contains("main"))
        assertFalse(change.contains(sha))
        assertFalse(change.contains("lingomage"))
        assertTrue("old before new", change.indexOf(sha.take(7)) < change.indexOf("main"))
    }

    @Test fun aRepositoryChangeNamesBothRepositories() {
        val change = UpdateReviewCopy.sourceChange(GitHubSource("javiercmh", "lingomage", "main"), GitHubSource("fork", "lingomage", "main"))
        assertTrue(change.contains("javiercmh/lingomage") && change.contains("fork/lingomage"))
        val publisher = UpdateReviewCopy.publisherChange("a.b", "c.d")
        assertTrue(publisher.contains("a.b") && publisher.contains("c.d"))
    }

    @Test fun updateLabelsNameTheAppAndVersionButNoCommit() {
        val labels = UpdateReviewCopy.updateLabels("LingoMage", "1.3.0")
        for (label in listOf(labels.progress, labels.before, labels.after)) {
            assertTrue(label.contains("LingoMage") && label.contains("1.3.0"))
            assertFalse(Regex("[a-f0-9]{7,}").containsMatchIn(label))
        }
        assertEquals("before and after are told apart in Version history", 3, setOf(labels.progress, labels.before, labels.after).size)
        assertTrue(UpdateReviewCopy.updateLabels(null, "2").progress.contains("2"))
    }
}
