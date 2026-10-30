package dev.stride.hud

/**
 * The one rule for the effort rating asked for on the summary screen.
 *
 * **The selector is shown for every walk, but only some walks are kept.** A
 * guest's walk and a walk under 300 m or three minutes still reach the summary
 * screen, so the 1-10 selector is drawn and taps without complaint. What must
 * not happen is the number being remembered: those walks leave no history
 * entry, no summary file and no send, and a rating is part of the walk. If it
 * were allowed to stand on its own it would be an orphan — a number about a
 * walk nothing else knows about, in `history.json`, a summary file, a POST, or
 * the retained MQTT state.
 *
 * The rule is [History.recordable], the same one [History.record] and
 * `WalkLog.endWalk` use, so the four can never disagree about which walks
 * exist. **Null is "not given", never 0** — see [History.Walk.rpe].
 */
internal object SummaryRating {

    /**
     * Whether a rating tapped for this walk may be remembered at all.
     *
     * False for a guest and for a walk too short or too brief to keep. The
     * selector still works on screen; the console simply drops the value.
     */
    fun allowed(walk: History.Walk, hasProfile: Boolean): Boolean =
        History.recordable(walk, hasProfile)

    /**
     * A rating that means something, or null.
     *
     * 1-10 is a rating; anything else is "not given". Kept here so the
     * selector's number and the walk record's field cannot drift apart.
     */
    fun clean(rpe: Int?): Int? = rpe?.takeIf { it in 1..10 }
}
