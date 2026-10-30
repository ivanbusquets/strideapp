package dev.stride.hud

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * When the summary file is written, and how many times.
 *
 * **What this checks.** REQ-11 moved the write from the end of the walk to the
 * moment the summary screen is left, so that the rating tapped on that screen
 * is in the file the first time it is written. The rules worth holding down
 * are: one file per walk carrying the rating, no file at all when the console
 * never leaves the screen, and no summary without the CSV that makes it a
 * sendable pair.
 *
 * A console is not needed for any of that: [PendingSummary] is the whole of
 * the decision, and the walk-end and screen-exit calls in [WalkLog] only hand
 * it the walk and ask it to close.
 */
class WalkSummaryTest {

    private val dir = Files.createTempDirectory("walk-summary").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `the done path writes one summary carrying the rating`() {
        val id = "walk-1"
        csvFor(id)
        val pending = PendingSummary(dir)

        assertTrue(pending.hold(id, walk()))
        pending.rate(7)
        val closed = pending.close()

        assertTrue("DONE did not write the summary", closed is PendingSummary.Close.Written)
        assertEquals(id, (closed as PendingSummary.Close.Written).id)
        assertEquals(7, (closed).rpe)
        assertEquals(1, summaries().size)
        assertEquals(7, ratingIn(summaries().single()))

        // A second exit — the panel dimming after DONE, say — has nothing left.
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals("a second exit wrote another summary", 1, summaries().size)
    }

    @Test
    fun `the sleep path writes one summary carrying the rating`() {
        val id = "walk-2"
        csvFor(id)
        val pending = PendingSummary(dir)

        // Sleep without DONE: the rating was tapped, the screen was left the
        // other way. It must still be in the file.
        assertTrue(pending.hold(id, walk()))
        pending.rate(4)
        val closed = pending.close()

        assertTrue(closed is PendingSummary.Close.Written)
        assertEquals(4, (closed as PendingSummary.Close.Written).rpe)
        assertEquals(1, summaries().size)
        assertEquals(4, ratingIn(summaries().single()))
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(1, summaries().size)
    }

    @Test
    fun `a new walk writes the previous summary before holding its own`() {
        val first = "walk-3"
        val second = "walk-4"
        csvFor(first)
        csvFor(second)
        val pending = PendingSummary(dir)

        assertTrue(pending.hold(first, walk()))
        pending.rate(5)
        // beginWalk() closes the previous summary before opening the new CSV.
        assertTrue(pending.close() is PendingSummary.Close.Written)
        assertTrue(pending.hold(second, walk()))

        assertEquals("the second walk must not have a summary yet", 1, summaries().size)
        assertEquals(first, summaries().single().name.substringBefore("."))
        assertEquals(5, ratingIn(summaries().single()))

        // And the second walk is still waiting to be closed.
        assertTrue(pending.close() is PendingSummary.Close.Written)
        assertEquals(2, summaries().size)
    }

    @Test
    fun `a console switched off at the wall leaves the csv with no summary`() {
        val id = "walk-5"
        csvFor(id)
        val pending = PendingSummary(dir)

        // Held, then nothing: the summary screen was still up when the power
        // went. This is an interrupted walk, not a walk to send.
        assertTrue(pending.hold(id, walk()))
        pending.rate(6)

        assertEquals(0, summaries().size)
        assertTrue("a csv with no summary must not be a pair",
            WalkFiles.pairsIn(dir).isEmpty())
    }

    @Test
    fun `a walk with no csv is not held and cannot leave a summary`() {
        // The recorder could not open the CSV; there is nothing to pair a
        // summary with, so writing one would only leave an orphan.
        val pending = PendingSummary(dir)
        assertFalse(pending.hold("walk-6", walk()))
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(0, summaries().size)
    }

    @Test
    fun `a race between two exits writes one summary`() {
        val id = "walk-7"
        csvFor(id)
        val pending = PendingSummary(dir)
        assertTrue(pending.hold(id, walk()))
        pending.rate(8)

        val threads = 8
        val start = CountDownLatch(1)
        val written = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit {
                    start.await()
                    if (pending.close() is PendingSummary.Close.Written) written.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("more than one exit wrote a summary", 1, written.get())
        assertEquals(1, summaries().size)
        assertEquals(8, ratingIn(summaries().single()))
    }

    @Test
    fun `a rating outside one to ten is not written`() {
        val id = "walk-8"
        csvFor(id)
        val pending = PendingSummary(dir)
        assertTrue(pending.hold(id, walk()))

        pending.rate(0)
        pending.rate(11)
        assertTrue(pending.close() is PendingSummary.Close.Written)

        val json = JSONObject(summaries().single().readText())
        assertFalse("an out-of-range rating reached the file: $json", json.has("rpe"))
    }

    // --- walks the console does not keep ------------------------------------

    @Test
    fun `a guest walk or a short walk may not carry a rating`() {
        // The selector is still shown for these walks; the console just has
        // nowhere to put the number. `allowed` is what every caller asks.
        assertFalse("a guest's walk is not kept",
            SummaryRating.allowed(walk().copy(who = "Guest"), hasProfile = false))
        assertFalse("under 300 m is not kept",
            SummaryRating.allowed(walk().copy(distance = 299.0), hasProfile = true))
        assertFalse("under three minutes is not kept",
            SummaryRating.allowed(walk().copy(elapsed = 179.0), hasProfile = true))
    }

    @Test
    fun `the boundary is unchanged - exactly 300 m and exactly three minutes are kept`() {
        // History.recordable uses >=, and this must match it rather than
        // quietly move the line: 300 m / 180 s is the first kept walk.
        val edge = walk().copy(distance = 300.0, elapsed = 180.0)
        assertTrue(SummaryRating.allowed(edge, hasProfile = true))
        assertFalse(SummaryRating.allowed(edge.copy(distance = 299.9), hasProfile = true))
        assertFalse(SummaryRating.allowed(edge.copy(elapsed = 179.9), hasProfile = true))
    }

    @Test
    fun `a rating is only one to ten`() {
        assertNull(SummaryRating.clean(null))
        assertNull(SummaryRating.clean(0))
        assertNull(SummaryRating.clean(11))
        assertEquals(1, SummaryRating.clean(1))
        assertEquals(10, SummaryRating.clean(10))
    }

    @Test
    fun `a rating tapped with no walk held is discarded`() {
        // The guest and short paths reach the summary screen but endWalk never
        // held their walk, so this is exactly the object they see. Tapping is
        // harmless and stores nothing.
        val pending = PendingSummary(dir)
        pending.rate(9)

        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(0, summaries().size)
        assertTrue("a walk with no summary must not be a pair",
            WalkFiles.pairsIn(dir).isEmpty())
    }

    @Test
    fun `a guest walk with a rating leaves no summary on done sleep or a new walk`() {
        val id = "walk-guest"
        csvFor(id)
        val guest = walk().copy(who = "Guest")

        // WalkLog.endWalk takes this branch for anything History.recordable
        // rejects: the walk is not held and its CSV is thrown away, so both
        // the screen exits and the send have nothing to find.
        assertFalse(History.recordable(guest, hasProfile = false))
        val pending = PendingSummary(dir)
        if (History.recordable(guest, hasProfile = false)) {
            pending.hold(id, guest.copy(rpe = null))
        } else {
            File(dir, "$id${WalkFiles.CSV_SUFFIX}").delete()
        }
        pending.rate(9)

        // DONE, then the panel sleeping, then a new walk beginning.
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(PendingSummary.Close.Nothing, pending.close())

        assertEquals(0, summaries().size)
        assertTrue(WalkFiles.pairsIn(dir).isEmpty())
        assertFalse("the guest's CSV must be gone",
            File(dir, "$id${WalkFiles.CSV_SUFFIX}").exists())
    }

    @Test
    fun `a short walk with a rating leaves no summary on any exit`() {
        val shortWalks = mapOf(
            "walk-short-distance" to walk().copy(distance = 299.0),
            "walk-short-time" to walk().copy(elapsed = 179.0),
        )
        for ((id, short) in shortWalks) {
            csvFor(id)
            assertFalse("$id is not recordable", History.recordable(short, hasProfile = true))

            val pending = PendingSummary(dir)
            if (History.recordable(short, hasProfile = true)) {
                pending.hold(id, short.copy(rpe = null))
            } else {
                File(dir, "$id${WalkFiles.CSV_SUFFIX}").delete()
            }
            pending.rate(8)

            assertEquals(PendingSummary.Close.Nothing, pending.close())
            assertEquals(PendingSummary.Close.Nothing, pending.close())
            assertEquals(0, summaries().size)
            assertTrue(WalkFiles.pairsIn(dir).isEmpty())
            assertFalse(File(dir, "$id${WalkFiles.CSV_SUFFIX}").exists())
        }
    }

    @Test
    fun `a borderline walk is kept and its rating is written once`() {
        // The other side of the same boundary: 300 m and 180 s exactly is a
        // walk the console keeps, so its rating is written like any other.
        val id = "walk-edge"
        csvFor(id)
        val edge = walk().copy(distance = 300.0, elapsed = 180.0)
        assertTrue(History.recordable(edge, hasProfile = true))

        val pending = PendingSummary(dir)
        assertTrue(pending.hold(id, edge.copy(rpe = null)))
        pending.rate(10)
        val closed = pending.close()

        assertTrue(closed is PendingSummary.Close.Written)
        assertEquals(10, (closed as PendingSummary.Close.Written).rpe)
        assertEquals(1, summaries().size)
        assertEquals(10, ratingIn(summaries().single()))
        assertEquals(PendingSummary.Close.Nothing, pending.close())
        assertEquals(1, summaries().size)
    }

    // --- helpers -------------------------------------------------------------

    private fun walk() = History.Walk(
        who = "Sam", startedAt = 1792530000000L, elapsed = 1200.0,
        distance = 2000.0, calories = 100.0, climbM = 10.0,
        avgSpeed = 6.0, maxSpeed = 7.0, maxIncline = 5.0,
        avgPulse = 120, maxPulse = 140, plan = "",
    )

    /** The CSV the recorder writes during the walk. */
    private fun csvFor(id: String) {
        File(dir, "$id${WalkFiles.CSV_SUFFIX}")
            .writeText(WalkFiles.CSV_HEADER + "\n0,6.0,5.9,0.0,0,0,0\n")
    }

    private fun summaries(): List<File> =
        dir.listFiles { f -> f.name.endsWith(WalkFiles.SUMMARY_SUFFIX) }
            ?.sortedBy { it.name } ?: emptyList()

    private fun ratingIn(file: File): Int? {
        val json = JSONObject(file.readText())
        if (!json.has("rpe")) return null
        return json.getInt("rpe")
    }
}
