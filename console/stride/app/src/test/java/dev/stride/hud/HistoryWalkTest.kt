package dev.stride.hud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The walk record's `rpe` field, as it is written to and read from
 * `history.json`.
 *
 * **What is worth testing here.** `History` itself needs a device — a files
 * directory to write into — so this covers the half that does not: the two
 * functions that turn a walk into JSON and back. Between them they are the
 * whole of what "persisted" means for the rating, and each of the three rules
 * is easy to break silently:
 *
 *  * a rating of 1-10 comes back the same number it went in as;
 *  * an unrated walk writes **no `rpe` key at all** — 0 would read as a
 *    genuine bottom-of-the-scale rating to every later reader;
 *  * a file written before this field existed still loads, as unrated.
 *
 * The round trip goes through actual JSON text, not just the object, because
 * `history.json` is a string on a disk and that is where a type or a null
 * could change shape.
 */
class HistoryWalkTest {

    /** A walk whose numbers survive `json()` unchanged, so equality means something. */
    private fun walk(rpe: Int? = null) = History.Walk(
        who = "Sam",
        startedAt = 1792530000000L,
        elapsed = 1200.0,
        distance = 2000.0,
        calories = 150.0,
        climbM = 30.0,
        avgSpeed = 6.0,
        maxSpeed = 7.2,
        maxIncline = 6.0,
        avgPulse = 120,
        maxPulse = 148,
        plan = "rolling",
        rpe = rpe,
    )

    /** Exactly what [History] does: an array on disk, parsed back one by one. */
    private fun roundTrip(w: History.Walk): History.Walk {
        val text = JSONArray().put(w.json()).toString()
        return History.Walk.from(JSONArray(text)[0] as JSONObject)
    }

    // --- rated ---------------------------------------------------------------

    @Test
    fun `a rated walk keeps its rating through the file`() {
        val before = walk(rpe = 7)
        val after = roundTrip(before)
        assertEquals(7, after.rpe)
        assertEquals(before, after)
    }

    @Test
    fun `both ends of the scale are kept`() {
        assertEquals(1, roundTrip(walk(rpe = 1)).rpe)
        assertEquals(10, roundTrip(walk(rpe = 10)).rpe)
    }

    // --- unrated -------------------------------------------------------------

    @Test
    fun `an unrated walk writes no rpe key`() {
        val json = walk().json()
        assertFalse("unrated walks must not carry an rpe key", json.has("rpe"))
    }

    @Test
    fun `an unrated walk reloads as unrated`() {
        assertNull(roundTrip(walk()).rpe)
    }

    @Test
    fun `unrated is never stored as zero`() {
        val json = walk().json()
        assertFalse(json.has("rpe"))
        assertNull(roundTrip(walk()).rpe)
        // And nothing anywhere in the record says 0 for effort.
        assertFalse(json.toString().contains("\"rpe\":0"))
    }

    // --- legacy --------------------------------------------------------------

    @Test
    fun `a record written before the field existed loads as unrated`() {
        // Verbatim shape of a history.json entry from before REQ-11/13.
        val legacy = JSONObject(
            """{"who":"Sam","startedAt":1792530000000,"elapsed":1200,""" +
            """"distance":2000,"calories":150,"climbM":30,"avgSpeed":6.0,""" +
            """"maxSpeed":7.2,"maxIncline":6.0,"avgPulse":120,"maxPulse":148,""" +
            """"plan":"rolling"}"""
        )
        val w = History.Walk.from(legacy)
        assertNull(w.rpe)
        // Nothing else was disturbed by the new field.
        assertEquals("Sam", w.who)
        assertEquals(1200.0, w.elapsed, 0.0)
        assertEquals(2000.0, w.distance, 0.0)
        assertEquals("rolling", w.plan)
        assertEquals(walk(), w)
    }

    @Test
    fun `an explicit null loads as unrated`() {
        val json = walk(rpe = 5).json().put("rpe", JSONObject.NULL)
        assertNull(History.Walk.from(json).rpe)
    }

    // --- bad input -----------------------------------------------------------

    @Test
    fun `out-of-range ratings are never written`() {
        for (bad in listOf(0, -1, 11, 99)) {
            val json = walk(rpe = bad).json()
            assertFalse("rpe=$bad must not be written", json.has("rpe"))
        }
    }

    @Test
    fun `out-of-range ratings on disk read as unrated`() {
        for (bad in listOf(0, -1, 11, 99)) {
            val json = walk(rpe = 5).json().put("rpe", bad)
            assertNull("stored rpe=$bad must read as unrated", History.Walk.from(json).rpe)
        }
    }

    @Test
    fun `a non-numeric rating reads as unrated rather than throwing`() {
        val json = walk(rpe = 5).json().put("rpe", "hard")
        assertNull(History.Walk.from(json).rpe)
    }

    @Test
    fun `clean accepts exactly the scale`() {
        assertNull(History.Walk.clean(null))
        assertNull(History.Walk.clean(0))
        assertNull(History.Walk.clean(11))
        assertEquals(1, History.Walk.clean(1))
        assertEquals(10, History.Walk.clean(10))
    }

    // --- the rest of the record survives -------------------------------------

    @Test
    fun `adding a rating changes nothing else in the record`() {
        val key = walk().json()
        val rated = walk(rpe = 4).json()
        assertEquals(4, rated.getInt("rpe"))
        rated.remove("rpe")
        // JSONObject does not promise key order, so compare field by field.
        assertEquals(key.length(), rated.length())
        for (name in key.keys()) {
            assertEquals("$name changed", key.get(name).toString(), rated.get(name).toString())
        }
    }
}
