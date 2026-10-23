package dev.stride.hud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The walk log's file formats.
 *
 * **What is worth testing here.** Two things, and they are the two that can
 * be got wrong without anybody noticing:
 *
 *  * **the CSV row**, because it is a format other programs read. A comma
 *    in the wrong place is a file that opens but is wrong, and nobody finds
 *    out for a week.
 *
 *  * **the multipart body**, because the console and the listener are two
 *    implementations of one format on opposite sides of a network, and
 *    nothing else in the repo holds them together. The listener's half is
 *    checked by `tools/walk_listener_test.py`; this is the console's.
 *
 * Everything else in [WalkLog] needs a device — a file system it can write
 * to, a socket it can reach — and is exercised by walking on the machine.
 */
class WalkFilesTest {

    // --- the CSV -------------------------------------------------------------

    @Test
    fun `the header names every column the console samples`() {
        assertEquals(
            "elapsed_s,speed_kph,belt_kph,incline_pct,distance_m,calories_kcal,pulse_bpm",
            WalkFiles.CSV_HEADER
        )
    }

    @Test
    fun `a row has one value per column`() {
        val snap = Snapshot(
            speed = 6.0, incline = 3.0, targetSpeed = 6.0, targetIncline = 3.0,
            distance = 1200.0, elapsed = 742.0, calories = 95.0, pulse = 131,
            fan = 0, session = Session.ACTIVE, workout = "workout", dmk = false,
            ramping = "", phaseLeft = 0.0, boardMode = 2,
            avgSpeed = 5.8, maxSpeed = 6.5, avgIncline = 1.5, maxIncline = 4.0,
        )
        val row = WalkFiles.csvRow(snap)
        val cells = row.split(",")
        assertEquals(WalkFiles.CSV_HEADER.split(",").size, cells.size)
        assertEquals("742", cells[0])       // elapsed
        assertEquals("6.0", cells[1])        // speed
        assertEquals("0.0", cells[2])        // belt speed
        assertEquals("3.0", cells[3])        // incline
        assertEquals("1200", cells[4])       // distance
        assertEquals("95", cells[5])         // calories
        assertEquals("131", cells[6])        // pulse
    }

    @Test
    fun `a row never contains a comma in a number`() {
        // The bug this guards against is `String.format` following the
        // device locale: a console set to a language that writes "6,5"
        // would put a comma inside a CSV field and every reader would
        // silently see the wrong number of columns.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val snap = Snapshot(
                speed = 6.5, incline = 0.0, targetSpeed = 6.5, targetIncline = 0.0,
                distance = 0.0, elapsed = 1.0, calories = 0.0, pulse = 0,
                fan = 0, session = Session.ACTIVE, workout = "workout", dmk = false,
                ramping = "", phaseLeft = 0.0, boardMode = 2,
                avgSpeed = 0.0, maxSpeed = 0.0, avgIncline = 0.0, maxIncline = 0.0,
            )
            val row = WalkFiles.csvRow(snap)
            assertEquals(7, row.split(",").size)
            assertTrue("the row carries a decimal comma: $row", row.contains("6.5"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // --- the multipart body --------------------------------------------------

    @Test
    fun `the body carries the fields, the files and the end`() {
        val body = WalkFiles.multipart(
            "----BOUNDARY",
            listOf(WalkFiles.Field("walk_id", "walk-1"), WalkFiles.Field("who", "Sam")),
            listOf(
                WalkFiles.FilePart("csv", "walk-1.csv", "text/csv", "a,b\n1,2\n".toByteArray()),
                WalkFiles.FilePart("summary", "walk-1.summary.json", "application/json",
                    """{"who":"Sam"}""".toByteArray()),
            )
        )
        val text = String(body, Charsets.UTF_8)

        assertTrue(text.startsWith("------BOUNDARY\r\n"))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"walk_id\""))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"who\""))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"csv\"; " +
                "filename=\"walk-1.csv\""))
        assertTrue(text.contains("Content-Type: text/csv"))
        assertTrue(text.contains("Content-Type: application/json"))
        assertTrue(text.endsWith("------BOUNDARY--\r\n"))
        // The files' own bytes are in there, unchanged.
        assertTrue(text.contains("a,b\n1,2"))
        assertTrue(text.contains("""{"who":"Sam"}"""))
    }

    @Test
    fun `the files come after the fields, so a parser reading in order gets both`() {
        val body = WalkFiles.multipart(
            "B",
            listOf(WalkFiles.Field("walk_id", "walk-1")),
            listOf(WalkFiles.FilePart("csv", "walk-1.csv", "text/csv", "1,2\n".toByteArray())),
        )
        val text = String(body, Charsets.UTF_8)
        val fieldAt = text.indexOf("name=\"walk_id\"")
        val fileAt = text.indexOf("name=\"csv\"")
        assertTrue("the field should come first", fieldAt in 0 until fileAt)
    }

    @Test
    fun `a name with a quote in it cannot break the header`() {
        // A field's *value* is the body, so a quote in the walker's name
        // is harmless there — it is the field's *name* that goes in a
        // header, and that is what has to be escaped.
        //
        // The walk id is generated, so it needs none of this; the test
        // here is about the mechanism, because a body that a name can
        // break is a body that breaks.
        val body = WalkFiles.multipart(
            "B",
            listOf(WalkFiles.Field("who", "Sam \"The Walker\"")),
            listOf(WalkFiles.FilePart("csv", "walk-1.csv", "text/csv", "1,2\n".toByteArray())),
        )
        val text = String(body, Charsets.UTF_8)
        // The name is the field's body, verbatim and whole.
        assertTrue("the name did not survive: $text", text.contains("Sam \"The Walker\""))
    }

    @Test
    fun `a field name with a quote in it cannot break the header`() {
        // A quoted string in a header ends at the next quote, so a name
        // carrying one would produce a header the receiving side reads as
        // something else entirely.
        val body = WalkFiles.multipart(
            "B",
            listOf(WalkFiles.Field("we\"ird", "value")),
            listOf(WalkFiles.FilePart("csv", "walk-1.csv", "text/csv", "1,2\n".toByteArray())),
        )
        val text = String(body, Charsets.UTF_8)
        // The header is two lines: the boundary and the disposition. A
        // quote that was not escaped would make it three, or leave the
        // rest of the line as rubbish.
        val lines = text.substringBefore("\r\n\r\n").split("\r\n")
        assertEquals("the header is not two lines: $lines", 2, lines.size)
        assertTrue("the quote was not escaped: $lines", lines[1].contains("name=\"we\\\"ird\""))
    }

    @Test
    fun `a field name with a newline in it cannot end the header early`() {
        // A newline in a header is an injection: it would end the header
        // and let whatever followed be read as a header of its own.
        val body = WalkFiles.multipart(
            "B",
            listOf(WalkFiles.Field("we\nird", "value")),
            listOf(WalkFiles.FilePart("csv", "walk-1.csv", "text/csv", "1,2\n".toByteArray())),
        )
        val text = String(body, Charsets.UTF_8)
        // Still two lines, and the newline is in the name as the two
        // characters backslash-n rather than as a line break.
        val lines = text.substringBefore("\r\n\r\n").split("\r\n")
        assertEquals("the header is not two lines: $lines", 2, lines.size)
        assertTrue("the newline was not escaped: $lines", lines[1].contains("name=\"we\\nird\""))
    }

    // --- which files are a pair ----------------------------------------------

    @Test
    fun `a walk is a csv and a summary that share an id`() {
        val dir = Files.createTempDirectory("walklog").toFile()
        try {
            write(dir, "walk-1.csv")
            write(dir, "walk-1.summary.json")
            write(dir, "walk-2.csv")
            write(dir, "walk-2.summary.json")

            val pairs = WalkFiles.pairsIn(dir)
            assertEquals(2, pairs.size)
            assertEquals("walk-1", pairs[0].id)
            assertEquals("walk-2", pairs[1].id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a csv with no summary is not a walk and is not sent`() {
        // A walk cut short by a power cut leaves a CSV and nothing else.
        // There is no summary to pair it with, and a half-walk is not a
        // walk, so it never goes.
        val dir = Files.createTempDirectory("walklog").toFile()
        try {
            write(dir, "walk-1.csv")
            assertTrue(WalkFiles.pairsIn(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the pairs come out oldest first`() {
        // The ids start with the millisecond the walk began, so the file
        // order is the walk order — and "oldest first" is what a queue of
        // unsent walks needs.
        val dir = Files.createTempDirectory("walklog").toFile()
        try {
            write(dir, "walk-1000-aaa.csv")
            write(dir, "walk-1000-aaa.summary.json")
            write(dir, "walk-2000-bbb.csv")
            write(dir, "walk-2000-bbb.summary.json")
            write(dir, "walk-3000-ccc.csv")
            write(dir, "walk-3000-ccc.summary.json")

            val ids = WalkFiles.pairsIn(dir).map { it.id }
            assertEquals(listOf("walk-1000-aaa", "walk-2000-bbb", "walk-3000-ccc"), ids)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the sent log is not mistaken for a walk`() {
        val dir = Files.createTempDirectory("walklog").toFile()
        try {
            write(dir, "sent.log")
            write(dir, "walk-1.csv")
            write(dir, "walk-1.summary.json")
            assertEquals(1, WalkFiles.pairsIn(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- the queue ------------------------------------------------------------

    @Test
    fun `a walk that was already sent is not sent again`() {
        // The console retries a send it could not deliver. If the first
        // attempt actually landed, the retry must not produce a second
        // copy — the sent log is what says so.
        val pairs = listOf(
            pair("walk-1"), pair("walk-2"), pair("walk-3"),
        )
        val toSend = WalkFiles.toSend(pairs, setOf("walk-2")) { true }
        assertEquals(listOf("walk-1", "walk-3"), toSend.map { it.id })
    }

    @Test
    fun `the queue goes oldest first`() {
        val pairs = listOf(pair("walk-1000"), pair("walk-2000"), pair("walk-3000"))
        val toSend = WalkFiles.toSend(pairs, emptySet()) { true }
        assertEquals(listOf("walk-1000", "walk-2000", "walk-3000"), toSend.map { it.id })
    }

    @Test
    fun `a walk whose files cannot be read is skipped, not sent`() {
        // A truncated file must not block every walk behind it, and it must
        // not be sent either: the rule is that files are removed only once
        // their send has succeeded.
        val pairs = listOf(pair("walk-1"), pair("walk-2"), pair("walk-3"))
        val toSend = WalkFiles.toSend(pairs, emptySet()) { it.id != "walk-2" }
        assertEquals(listOf("walk-1", "walk-3"), toSend.map { it.id })
    }

    @Test
    fun `nothing to send is not an error`() {
        assertTrue(WalkFiles.toSend(emptyList(), emptySet()) { true }.isEmpty())
    }

    // --- the summary ---------------------------------------------------------

    @Test
    fun `the summary file carries the same fields the history keeps`() {
        // The point of writing the summary from `History.Walk.json()` is
        // that the two cannot disagree. This is the check that it still
        // does.
        val walk = History.Walk(
            who = "Sam", startedAt = 1792530000000L, elapsed = 1200.0,
            distance = 2000.0, calories = 150.0, climbM = 30.0,
            avgSpeed = 6.0, maxSpeed = 7.2, maxIncline = 6.0,
            avgPulse = 120, maxPulse = 148, plan = "rolling",
        )
        val json = walk.json()
        val expected = listOf(
            "who", "startedAt", "elapsed", "distance", "calories", "climbM",
            "avgSpeed", "maxSpeed", "maxIncline", "avgPulse", "maxPulse", "plan",
        )
        val names = json.keys().asSequence().toList()
        assertEquals(expected.size, names.size)
        for (field in expected) {
            assertTrue("the summary is missing $field", names.contains(field))
        }
    }

    // --- how hard it felt ----------------------------------------------------

    @Test
    fun `an unrated walk leaves the effort out rather than writing a zero`() {
        // Optional means absent, not nought. A zero stored here would read as
        // a genuine bottom-of-the-scale rating to every later reader, and the
        // scale starts at 1 — see History.Walk.rpe.
        val json = walk(distance = 2000.0, elapsed = 1200.0).json()
        assertFalse("an unrated walk wrote an rpe field: $json", json.has("rpe"))
    }

    @Test
    fun `a rating is one integer between one and ten`() {
        val walk = walk(distance = 2000.0, elapsed = 1200.0).copy(rpe = 7)
        val json = walk.json()
        assertEquals(7, json.getInt("rpe"))
        // And it comes back off the file as the same number, which is what
        // history.json does on the next boot.
        assertEquals(7, History.Walk.from(json).rpe)
    }

    @Test
    fun `a rating out of range reads back as not given`() {
        // Nothing writes 0 or 11, and if anything ever does, it must not come
        // back as a rating — least of all 0, which is not on the scale.
        val zero = JSONObject().put("rpe", 0)
        val eleven = JSONObject().put("rpe", 11)
        val given = JSONObject().put("rpe", JSONObject.NULL)
        assertNull(History.Walk.from(zero).rpe)
        assertNull(History.Walk.from(eleven).rpe)
        assertNull(History.Walk.from(given).rpe)
    }

    // --- which walks are recorded --------------------------------------------

    @Test
    fun `a guest walk is not recorded`() {
        val walk = walk(distance = 2000.0, elapsed = 1200.0)
        assertFalse(History.recordable(walk, hasProfile = false))
    }

    @Test
    fun `a short walk is not recorded`() {
        // Under 300 m, or under three minutes: an interruption rather than
        // a session. The same rule the console's own history keeps.
        assertFalse(History.recordable(walk(distance = 299.0, elapsed = 1200.0), true))
        assertFalse(History.recordable(walk(distance = 2000.0, elapsed = 179.0), true))
    }

    @Test
    fun `a real walk is recorded`() {
        assertTrue(History.recordable(walk(distance = 300.0, elapsed = 180.0), true))
        assertTrue(History.recordable(walk(distance = 2000.0, elapsed = 1200.0), true))
    }

    // --- helpers -------------------------------------------------------------

    private fun walk(distance: Double, elapsed: Double) = History.Walk(
        who = "Sam", startedAt = 1792530000000L, elapsed = elapsed,
        distance = distance, calories = 100.0, climbM = 10.0,
        avgSpeed = 6.0, maxSpeed = 7.0, maxIncline = 5.0,
        avgPulse = 120, maxPulse = 140, plan = "",
    )

    private fun write(dir: File, name: String) {
        File(dir, name).writeText("x")
    }

    private fun pair(id: String): WalkFiles.Pair {
        val dir = Files.createTempDirectory("walklog").toFile()
        return WalkFiles.Pair(id, File(dir, "$id.csv"), File(dir, "$id.summary.json"))
    }
}
