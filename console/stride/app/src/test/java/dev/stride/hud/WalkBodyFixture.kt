package dev.stride.hud

import org.junit.Test
import java.io.File

/**
 * Writes the console's real multipart body to a file, for the end-to-end
 * check in tools/walk_listener_test.py.
 *
 * Not a test of anything by itself — it is a fixture generator. It is
 * skipped unless STRIDE_WALK_BODY_OUT names a file to write.
 */
class WalkBodyFixture {

    @Test
    fun writeTheBody() {
        val out = System.getenv("STRIDE_WALK_BODY_OUT") ?: return
        val csv = "elapsed_s,speed_kph,belt_kph,incline_pct,distance_m,calories_kcal,pulse_bpm\n" +
                "0,6.0,5.9,0.0,0,0,0\n" +
                "1,6.0,5.9,1.0,2,0,120\n"
        val summary = """{"who":"Sam","startedAt":1792530000000,"elapsed":1200,""" +
                """"distance":2000,"calories":150,"climbM":30,"avgSpeed":6.0,""" +
                """"maxSpeed":7.2,"maxIncline":6.0,"avgPulse":120,"maxPulse":148,""" +
                """"plan":"rolling"}"""
        val body = WalkFiles.multipart(
            "----STRIDE0123456789abcdef",
            listOf(
                WalkFiles.Field("walk_id", "walk-1792530000000-abc123"),
                WalkFiles.Field("who", "Sam"),
            ),
            listOf(
                WalkFiles.FilePart("csv", "walk-1792530000000-abc123.csv",
                    "text/csv", csv.toByteArray()),
                WalkFiles.FilePart("summary", "walk-1792530000000-abc123.summary.json",
                    "application/json", summary.toByteArray()),
            )
        )
        File(out).writeBytes(body)
    }
}
