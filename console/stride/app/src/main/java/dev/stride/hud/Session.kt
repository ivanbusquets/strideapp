package dev.stride.hud

/**
 * Where the *app* thinks we are in a workout.
 *
 * Deliberately not the board's `WorkoutMode`. The board's state machine is
 * one-way — it will go Idle → Running but refuses Running → Idle — so a workout
 * that has plainly finished still reports `running` for hours afterwards. Home
 * Assistant then believes the treadmill has been in use since breakfast, and the
 * elapsed counter never returns to zero.
 *
 * The user pressing START and END is the fact we can actually trust, so that is
 * what drives the HUD, the timer, and everything published to HA. The board's
 * mode is kept only for the two things it alone knows: the belt interlock and
 * the safety key.
 */
object Session {
    const val WELCOME = 0    // "what are we doing today?"
    const val WARMUP = 1     // easing in at walking pace, skippable
    const val ACTIVE = 2     // the workout proper
    const val PAUSED = 3     // belt stopped, timer held
    const val COOLDOWN = 4   // easing back down, skippable
    const val SUMMARY = 5    // post-workout recap

    fun name(v: Int) = when (v) {
        WELCOME -> "welcome"; WARMUP -> "warmup"; ACTIVE -> "running"
        PAUSED -> "paused"; COOLDOWN -> "cooldown"; SUMMARY -> "summary"
        else -> "unknown"
    }

    /** States in which the belt is live and the clock is running. */
    fun isMoving(v: Int) = v == WARMUP || v == ACTIVE || v == COOLDOWN
}

/** One frame of everything both the HUD and Home Assistant need. */
data class Snapshot(
    /**
     * The pace the console is holding, km/h — the setpoint, not a measurement.
     *
     * This is the number on the dial, and it is the commanded speed on purpose.
     * `ActualKph` never populates on this board (see MainActivity.beltSpeed), so
     * the only alternative is an estimate built from a whole-metre odometer, and
     * showing that estimate is what made the readout wander. On 17 August 2026 a
     * steady walk rendered nineteen different speeds in thirty-six seconds while
     * the deck was tilting — 7.9, 7.7, 7.6, 7.5, 7.4, 7.5, 7.6, 7.7, 7.6 … — and
     * pressing − at 7.6 landed on 7.4 because the drift was larger than the step.
     *
     * A belt is a closed loop that reaches what it is told within a second or
     * two, so the setpoint is both the honest answer and the stable one, and it
     * makes the buttons exact by construction: 7.6 − 0.1 is 7.5, always. The
     * estimate is still computed, and still allowed to disagree — it is carried
     * as [beltKph] and watched, rather than being put on the dial.
     */
    val speed: Double,
    /**
     * What the odometer says the belt is actually doing, km/h.
     *
     * Diagnosis only: nothing draws this. It exists so a belt that cannot reach
     * the commanded pace is still detectable — see [slipping] — now that the
     * dial no longer shows it.
     *
     * Published to Home Assistant since 8 September 2026, so the recorder keeps
     * a trace of it. Reconstructing that walk's belt speed afterwards meant
     * differencing the whole-metre distance sensor, because this — the number
     * the console had already worked out — was going nowhere.
     */
    val beltKph: Double = 0.0,
    /**
     * The board's own `RPM` field, raw and unscaled.
     *
     * Read on the chance that it is a *different* sensor from the one behind
     * `DISTANCE`. If it is, the two disagreeing is the only signal this console
     * could ever have for a belt slipping over the drive roller: the odometer
     * counts the roller, so it and [beltKph] both sit upstream of that slip and
     * neither can see it. See [slipping] for why that matters.
     *
     * May well read a flat zero, as `ACTUAL_KPH` does on this board. It is
     * recorded rather than trusted, and nothing is built on it until a walk has
     * shown what it does.
     */
    val rpm: Double = 0.0,
    val incline: Double,
    val targetSpeed: Double,
    val targetIncline: Double,
    val distance: Double,
    val elapsed: Double,
    val calories: Double,
    val pulse: Int,
    val fan: Int,
    val session: Int,
    val workout: String,
    val dmk: Boolean,
    /** "warmup", "resuming", "cooldown", or empty when no ramp is in flight. */
    val ramping: String,
    /** Seconds left in a timed phase (warm-up / cool-down); 0 otherwise. */
    val phaseLeft: Double,
    /** The board's own WorkoutMode, carried purely for diagnosis. 8 = safety key out. */
    val boardMode: Int,
    val avgSpeed: Double,
    val maxSpeed: Double,
    val avgIncline: Double,
    val maxIncline: Double,
    /** Averaged over the frames that had a reading, not over the walk — a
     *  strap that connected late or dropped out must not drag the number
     *  towards zero. Both are 0 when nothing ever read. */
    val avgPulse: Int = 0,
    val maxPulse: Int = 0,
    /**
     * The *walker's* maximum heart rate, or 0 when they have not given an age.
     *
     * Not [maxPulse], which is the highest beat seen during this walk. This one
     * is a property of the person and the reason zones can be spoken about at
     * all — see Settings.Person.age. Zero is the ordinary case and means the
     * coach falls back to comparing against the session's own average.
     */
    val hrMax: Int = 0,
    /**
     * Seconds spent in each heart-rate zone, indexed 1-5; index 0 is everything
     * below zone 1 and is counted but rarely interesting.
     *
     * Empty when [hrMax] is 0 — without a maximum there are no zones, and a
     * console that invented some would be dressing a guess as physiology. The
     * summary falls back to average and peak, which need nobody's age.
     */
    val zoneSecs: List<Int> = emptyList(),
    /**
     * What this walk did that previous walks did not — see History.
     *
     * Computed once, when the belt stops, against this walker's own recorded
     * sessions. Empty on the way round; empty too for a first walk, which has
     * nothing to beat and should not be told it set five records.
     */
    val achievements: List<String> = emptyList(),
    /** Who tapped their name on the welcome screen. */
    val who: String = "",
    /**
     * The walker's stable profile id, or empty for a guest.
     *
     * Sent to Home Assistant alongside [who] so the coach can tell whose walk
     * this is without matching on a display name. The prompt keys every personal
     * figure — weight, blood pressure, sleep — off this, and attaches none of
     * them when it is empty. A guest on the belt gets coaching about the walk
     * and nothing about anybody's body. See Settings.Person.id.
     */
    val whoId: String = "",
    /** Guided walk, or empty on a casual one. */
    val plan: String = "",
    val segment: Int = 0,
    val segments: Int = 0,
    val segmentLabel: String = "",
    val segmentLeft: Double = 0.0,
    /** True when [segmentLeft] is metres rather than seconds — a route is
     *  driven by distance, so "how much of this stretch is left" is a distance
     *  too. See MainActivity.routeTick. */
    val segmentLeftIsDistance: Boolean = false,
    /** The segment after this one, so the coach can warn before it arrives. */
    val nextLabel: String = "",
    val nextIncline: Double = 0.0,
    /** Total plan length in seconds — one lap on a circuit. */
    val planTotalSec: Double = 0.0,
    /** Total ascent of the plan in metres, when it is known — a route carries
     *  its own, measured from the walk it came from. Zero for templates, whose
     *  climb depends on how fast you take them. */
    val planClimbM: Double = 0.0,
    /** The steepest gradient anywhere in the plan. */
    val planPeakIncline: Double = 0.0,
    /** The coach's closing line, ready before the summary appears. */
    val summaryLine: String = "",
    /** Plan time wrapped into the current lap. Equals elapsed unless looping. */
    val planElapsed: Double = 0.0,
    /** Which lap of an open-ended circuit; 1 for an ordinary walk. */
    val planLap: Int = 1,
    /** True when the route is a circuit with no end but STOP. */
    val planLoops: Boolean = false,
    /** Pace the plan is suggesting, km/h. 0 when it has no opinion. */
    val suggestPace: Double = 0.0,
    /** False once a manual incline press has taken the deck back for this segment. */
    val inclineAuto: Boolean = false,
    /** False while the board is refusing frames. The numbers here are then the
     *  last good ones rather than current, and the page can say so instead of
     *  presenting stale values as live. The console keeps drawing either way:
     *  repainting only after a good frame froze the HUD mid-render on
     *  2026-08-07 and made every button look dead. */
    val boardOk: Boolean = true,
    /** True while the board is refusing everything pending VerifySecurity. A
     *  different thing from [boardOk] being false, and worth saying differently:
     *  one is "the board is not answering", the other is "the board wants us to
     *  authenticate and we are doing it". */
    val boardLocked: Boolean = false,
    /** HR Target mode: the bpm being aimed for, 0 when the mode is not active. */
    val hrTargetBpm: Int = 0,
    /** HR Target mode's speed bounds, km/h. Zero when not active. */
    val hrTargetMinKph: Double = 0.0,
    val hrTargetMaxKph: Double = 0.0,
    /** HR Target mode's suggested speed, km/h — a suggestion only, exactly like
    *  [suggestPace] for a guided walk. Nothing here ever sets the belt speed;
    *  see HrTarget.kt and SAFETY.md. */
    val hrSuggestKph: Double = 0.0,
) {
    val mode: String get() = Session.name(session)

    /**
     * True when the ground says the belt is well short of what was asked for.
     *
     * The dial shows the setpoint, which is right almost always and wrong in
     * exactly one case: a belt that physically cannot reach the pace. This is
     * how that case still gets noticed. Deliberately a wide band — the estimate
     * behind it is coarse, and a threshold tight enough to catch a small
     * shortfall would fire on the estimator's own noise.
     *
     * **What this cannot catch.** It compares the setpoint against [beltKph],
     * and [beltKph] comes off the odometer, which counts the *drive roller*.
     * Both numbers therefore sit on the same side of a belt that is slipping
     * over that roller: the motor holds its speed, the odometer reports it
     * faithfully, and the belt surface under the walker sags and then catches
     * up without either number moving. On 8 September 2026 exactly that was
     * felt at the top of the last climb, and a second-by-second reconstruction
     * of the whole descent showed the roller never left 10.0 km/h. This flag
     * would not have fired, and did not.
     *
     * So it is a motor-cannot-reach-pace detector, not a slip detector, and the
     * name is a promise it only half keeps. [rpm] is the candidate for the
     * other half. Until that proves out, the honest record of a slip is the
     * walker saying so.
     */
    val slipping: Boolean
        get() = Session.isMoving(session) && speed > 0.5 && beltKph > 0.0 &&
                speed - beltKph > 0.8

    /**
     * JSON string escaping, for the fields that carry text somebody else wrote.
     *
     * [summaryLine] and the coach's lines come from a language model, plan and
     * segment labels come from a route file, and names are typed in by hand.
     * Any of them may contain a quote or a backslash, and one of those dropped
     * raw into this object produces JSON the HUD cannot parse — a blank screen
     * for the rest of the walk, from one apostrophe.
     */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    fun toJson(): String = buildString {
        append("{")
        append("\"speed\":${"%.1f".format(speed)},")
        append("\"beltKph\":${"%.1f".format(beltKph)},")
        append("\"rpm\":${"%.0f".format(rpm)},")
        append("\"slipping\":$slipping,")
        append("\"incline\":${"%.1f".format(incline)},")
        append("\"targetSpeed\":${"%.1f".format(targetSpeed)},")
        append("\"targetIncline\":${"%.1f".format(targetIncline)},")
        append("\"distance\":${"%.0f".format(distance)},")
        append("\"elapsed\":${"%.0f".format(elapsed)},")
        append("\"calories\":${"%.0f".format(calories)},")
        append("\"pulse\":$pulse,")
        append("\"fan\":$fan,")
        append("\"workout\":\"${esc(workout)}\",")
        append("\"dmk\":$dmk,")
        append("\"ramping\":\"${esc(ramping)}\",")
        append("\"phaseLeft\":${"%.0f".format(phaseLeft)},")
        append("\"boardMode\":$boardMode,")
        append("\"boardOk\":$boardOk,")
        append("\"boardLocked\":$boardLocked,")
        append("\"avgSpeed\":${"%.1f".format(avgSpeed)},")
        append("\"maxSpeed\":${"%.1f".format(maxSpeed)},")
        append("\"avgIncline\":${"%.1f".format(avgIncline)},")
        append("\"maxIncline\":${"%.1f".format(maxIncline)},")
        append("\"avgPulse\":$avgPulse,")
        append("\"maxPulse\":$maxPulse,")
        append("\"hrMax\":$hrMax,")
        append("\"zoneSecs\":${zoneSecs.joinToString(",", "[", "]")},")
        append("\"achievements\":${
            achievements.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
        },")
        append("\"who\":\"${esc(who)}\",")
        append("\"whoId\":\"${esc(whoId)}\",")
        append("\"plan\":\"${esc(plan)}\",")
        append("\"segment\":$segment,")
        append("\"segments\":$segments,")
        append("\"segmentLabel\":\"${esc(segmentLabel)}\",")
        append("\"segmentLeft\":${"%.0f".format(segmentLeft)},")
        append("\"segmentLeftIsDistance\":$segmentLeftIsDistance,")
        append("\"nextLabel\":\"${esc(nextLabel)}\",")
        append("\"nextIncline\":${"%.1f".format(nextIncline)},")
        append("\"planTotalSec\":${"%.0f".format(planTotalSec)},")
        append("\"planClimbM\":${"%.0f".format(planClimbM)},")
        append("\"planPeakIncline\":${"%.1f".format(planPeakIncline)},")
        append("\"summaryLine\":\"${esc(summaryLine)}\",")
        append("\"planElapsed\":${"%.0f".format(planElapsed)},")
        append("\"planLap\":$planLap,")
        append("\"planLoops\":$planLoops,")
        append("\"suggestPace\":${"%.1f".format(suggestPace)},")
        append("\"hrTargetBpm\":$hrTargetBpm,")
        append("\"hrTargetMinKph\":${"%.1f".format(hrTargetMinKph)},")
        append("\"hrTargetMaxKph\":${"%.1f".format(hrTargetMaxKph)},")
        append("\"hrSuggestKph\":${"%.1f".format(hrSuggestKph)},")
        append("\"inclineAuto\":$inclineAuto,")
        append("\"mode\":\"$mode\"")
        append("}")
    }
}
