package dev.stride.hud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Every walk this console has recorded, per walker, kept on the console.
 *
 * **Why this is on the treadmill and not in Home Assistant.** The summary screen
 * is the one place a walk gets looked at, and it is looked at while standing on
 * the belt, three seconds after stopping. Anything it needs has to be there with
 * no broker, no phone and no network — the same rule the rest of the console
 * follows. Home Assistant still gets every session over MQTT and still keeps the
 * long-term picture; this is the copy that makes "most climb you have done"
 * answerable when the wifi is out.
 *
 * It is also, incidentally, the first workout log the console has ever kept. Up
 * to 17 August 2026 the only thing it persisted was the route cache, so a walk
 * that was not published to HA left no trace anywhere once the ring buffer
 * rolled — which is exactly what happened to the first thirty-five minutes of
 * that day's session.
 *
 * **Guests are not recorded.** A walk with no profile has nobody to compare it
 * to and nobody who has agreed to be remembered, so it is summarised and then
 * forgotten. See [record].
 */
class History(context: Context) {

    private val file = File(context.filesDir, "history.json")

    /**
     * Newest first, so the reads that matter — "the last few walks" — are the
     * front of the list, and the trim is a single drop from the tail.
     */
    @Volatile
    private var walks: List<Walk> = emptyList()

    /**
     * One finished walk, reduced to the figures a later walk can be measured
     * against.
     *
     * Deliberately not a full trace. A per-second recording of five hundred
     * walks is a database, and the console has a summary screen to draw, not a
     * dataset to serve. What is here is what an achievement can be built from.
     */
    data class Walk(
        val who: String,
        val startedAt: Long,
        val elapsed: Double,
        val distance: Double,
        val calories: Double,
        val climbM: Double,
        val avgSpeed: Double,
        val maxSpeed: Double,
        val maxIncline: Double,
        val avgPulse: Int,
        val maxPulse: Int,
        val plan: String,
        /**
         * How hard the walk felt, 1-10, or **null when nobody said**.
         *
         * **Null is a value here and 0 is not.** The rating is optional, and a
         * walk nobody rated has to stay distinguishable from a walk rated at
         * the bottom of the scale — see `json()`, which leaves the field out
         * entirely rather than writing a zero that every later reader would
         * have to know to discount.
         */
        val rpe: Int? = null,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("who", who)
            .put("startedAt", startedAt)
            .put("elapsed", Math.round(elapsed))
            .put("distance", Math.round(distance))
            .put("calories", Math.round(calories))
            .put("climbM", Math.round(climbM))
            .put("avgSpeed", round1(avgSpeed))
            .put("maxSpeed", round1(maxSpeed))
            .put("maxIncline", round1(maxIncline))
            .put("avgPulse", avgPulse)
            .put("maxPulse", maxPulse)
            .put("plan", plan)
            // Left out rather than written as 0: an unrated walk is absent,
            // and a reader that sees nothing knows not to treat it as a 1.
            .apply { rpe?.let { put("rpe", it) } }

        companion object {
            private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

            fun from(o: JSONObject) = Walk(
                who = o.optString("who"),
                startedAt = o.optLong("startedAt"),
                elapsed = o.optDouble("elapsed", 0.0),
                distance = o.optDouble("distance", 0.0),
                calories = o.optDouble("calories", 0.0),
                climbM = o.optDouble("climbM", 0.0),
                avgSpeed = o.optDouble("avgSpeed", 0.0),
                maxSpeed = o.optDouble("maxSpeed", 0.0),
                maxIncline = o.optDouble("maxIncline", 0.0),
                avgPulse = o.optInt("avgPulse", 0),
                maxPulse = o.optInt("maxPulse", 0),
                plan = o.optString("plan"),
                // Absent, null, or out of range is "not rated" — never 0,
                // and never something that would read as a genuine 1.
                rpe = if (o.has("rpe") && !o.isNull("rpe")) {
                    o.optInt("rpe").takeIf { it in 1..10 }
                } else null,
            )
        }
    }

    init {
        walks = try {
            if (file.exists()) parse(file.readText()) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "history unreadable (${e.message}) — starting empty")
            emptyList()
        }
        Log.i(TAG, "loaded ${walks.size} recorded walk(s)")
    }

    private fun parse(text: String): List<Walk> {
        val arr = JSONArray(text.trim().ifBlank { "[]" })
        val out = ArrayList<Walk>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(Walk.from(it)) }
        }
        return out
    }

    /**
     * Add a finished walk and work out what was notable about it.
     *
     * The comparison happens **before** the walk is stored, because a walk
     * compared against a list that already contains it beats nothing and every
     * session would come back with no achievements at all.
     *
     * Returns the achievement lines, newest walk already accounted for.
     */
    @Synchronized
    fun record(walk: Walk, hasProfile: Boolean): List<String> {
        if (!recordable(walk, hasProfile)) {
            Log.i(TAG, if (!hasProfile)
                "guest walk — summarised but not recorded"
            else
                "walk too short to record (${"%.0f".format(walk.distance)} m)")
            return emptyList()
        }
        val previous = walks.filter { it.who == walk.who }
        val earned = achievements(walk, previous)

        walks = (listOf(walk) + walks).take(KEEP)
        save()
        Log.i(TAG, "recorded walk for ${walk.who} " +
                "(${previous.size + 1} total), ${earned.size} achievement(s)")
        return earned
    }

    /**
     * What this walk did that the ones before it did not.
     *
     * Rules, in order of how much they are worth saying out loud. Capped at
     * [MAX_SHOWN] because a summary listing six records is a summary nobody
     * reads, and because a first big walk after a quiet month genuinely does
     * beat everything at once.
     *
     * A walker with fewer than [MIN_FOR_RECORDS] previous walks gets no
     * record-breaking lines. Everything is a personal best when there is one
     * walk to compare against, and being told so is worth nothing — so early
     * sessions get the consistency lines instead, which are true from the start.
     */
    private fun achievements(walk: Walk, previous: List<Walk>): List<String> {
        val out = ArrayList<String>()
        val n = previous.size

        if (n >= MIN_FOR_RECORDS) {
            if (walk.distance > previous.maxOf { it.distance })
                out.add("Furthest you have gone")
            if (walk.climbM > previous.maxOf { it.climbM } && walk.climbM >= MIN_CLIMB_M)
                out.add("Most climb in one workout")
            if (walk.elapsed > previous.maxOf { it.elapsed })
                out.add("Longest time on the belt")
            if (walk.maxIncline > previous.maxOf { it.maxIncline } && walk.maxIncline > 0)
                out.add("Steepest you have taken it")
            if (walk.avgSpeed > previous.maxOf { it.avgSpeed })
                out.add("Fastest average pace")

            // Worth more than any single record: the same work at a lower heart
            // rate is the one number here that means fitness rather than effort.
            // Guarded hard — it needs a strap on both walks, a comparable pace,
            // and a gap big enough not to be a warm day.
            val comparable = previous.filter {
                it.avgPulse > 0 && Math.abs(it.avgSpeed - walk.avgSpeed) < 0.4
            }
            if (walk.avgPulse > 0 && comparable.size >= 3) {
                val was = comparable.take(5).map { it.avgPulse }.average()
                if (was - walk.avgPulse >= HR_IMPROVEMENT_BPM) {
                    out.add("Same pace, ${Math.round(was - walk.avgPulse)} bpm lower")
                }
            }
        }

        // Cadence, which is true from the very first workout and is the thing
        // most worth reinforcing early on.
        //
        // Both lines are arithmetic on dates, so both are skipped when this
        // workout has none — see MainActivity.sessionStartWall. A console that
        // powered up minutes before the belt did cannot say what day it is, and
        // "3 days in a row" computed from a clock reading 2010 is not a smaller
        // version of the truth, it is a different number.
        if (walk.startedAt > 0L) {
            val week = walksSince(walk.who, walk.startedAt - WEEK_MS) + 1
            if (week >= 3) out.add("$week workouts this week")
            val streak = dayStreak(walk.who, walk.startedAt)
            if (streak >= 3) out.add("$streak days in a row")
        }

        return out.take(MAX_SHOWN)
    }

    private fun walksSince(who: String, since: Long) =
        walks.count { it.who == who && it.startedAt >= since }

    /**
     * Consecutive days ending today, counting the walk just finished.
     *
     * Calendar days rather than 24-hour blocks: a walk at 7 am and the next at
     * 9 pm the following day is two days in a row to anybody who walks, whatever
     * the thirty-eight hours between them say.
     */
    private fun dayStreak(who: String, endingAt: Long): Int {
        // Undated workouts carry no day, so they cannot extend or break a
        // streak — see MainActivity.sessionStartWall.
        val days = walks.filter { it.who == who && it.startedAt > 0L }
                        .map { dayOf(it.startedAt) }.toHashSet()
        var streak = 1
        var day = dayOf(endingAt)
        while (days.contains(day - 1)) { streak++; day-- }
        return streak
    }

    private fun dayOf(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 86_400_000L
    }

    /**
     * Put a rating on a walk that is already recorded, and return the walk as
     * it now stands.
     *
     * The rating is given *after* the walk was stored — the summary screen is
     * where it is asked for, and the walk was recorded the moment the belt
     * stopped so the summary could show what it beat. So this finds the entry
     * again by its own contents rather than by a new id: there is exactly one
     * walk with this person, this start time and this length, and it is the one
     * on screen.
     *
     * A walk that was never recorded — a guest's, or one too short — is not in
     * the list, and comes straight back with the rating on it. Nothing is
     * stored, which is the same rule that kept it out in the first place.
     *
     * @param rpe 1-10, or null to clear. **Null, not 0** — see [Walk.rpe].
     */
    @Synchronized
    fun rate(walk: Walk, rpe: Int?): Walk {
        val rating = rpe?.takeIf { it in 1..10 }
        val updated = walk.copy(rpe = rating)
        val i = walks.indexOfFirst { it == walk }
        if (i < 0) return updated
        walks = walks.toMutableList().also { it[i] = updated }
        save()
        Log.i(TAG, "rated the walk for ${walk.who} " +
                if (rating != null) "$rating/10" else "— cleared")
        return updated
    }

    /** The most recent walks for one person, newest first. */
    @Synchronized
    fun recent(who: String, limit: Int): List<Walk> =
        walks.filter { it.who == who }.take(limit)

    private fun save() {
        val arr = JSONArray()
        for (w in walks) arr.put(w.json())
        try {
            // Written whole and moved into place: a console losing power
            // mid-write is an ordinary event here, and a half-written history
            // would be thrown away wholesale on the next boot.
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not save history (${e.message})")
        }
    }

    companion object {
        private const val TAG = "StrideHistory"

        /**
         * Whether this walk is one the console keeps.
         *
         * **The rule, in one place, so that anything else which writes a walk
         * down asks the same question.** A guest's walk is summarised and
         * forgotten; a walk under [MIN_DISTANCE_M] or [MIN_ELAPSED_S] is an
         * interruption rather than a session. Neither is in `history.json`,
         * and neither should leave the console in a file either — see
         * WalkLog.
         *
         * The two conditions [record] checks, exactly as it checks them.
         * They are pulled out here rather than written twice so the two can
         * never disagree about which walks exist.
         *
         * @param hasProfile whether this walker is somebody the console
         *        knows. **A guest is a walker the console has no profile
         *        for** — the welcome screen offers "Guest" alongside the
         *        household, and that name arrives here as an ordinary string,
         *        so the only way to tell a guest from somebody is whether the
         *        people list has them.
         */
        fun recordable(walk: Walk, hasProfile: Boolean): Boolean =
            hasProfile &&
            walk.distance >= MIN_DISTANCE_M &&
            walk.elapsed >= MIN_ELAPSED_S

        /**
         * How many walks to keep.
         *
         * Two years of daily walking, and about 120 KB of JSON. The trim exists
         * so the file cannot grow without limit on a console that never gets
         * looked at, not because anything here is expensive.
         */
        const val KEEP = 750

        /** Shorter than this is an interruption, not a session — see Coach.SUMMARY_MIN_MS. */
        const val MIN_DISTANCE_M = 300.0
        const val MIN_ELAPSED_S = 180.0

        /** Below this a "most climb" record is measuring a flat walk's rounding. */
        const val MIN_CLIMB_M = 10.0

        /** Records need something to be a record against. */
        const val MIN_FOR_RECORDS = 3

        /** A drop this size at the same pace is a real change, not a warm room. */
        const val HR_IMPROVEMENT_BPM = 4.0

        const val MAX_SHOWN = 3
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
