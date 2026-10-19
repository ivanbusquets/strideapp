package dev.stride.hud

import android.os.SystemClock

/**
 * Suggests a speed to bring heart rate towards a target, for the HR Target
 * workout mode.
 *
 * Never sets the belt speed itself — see SAFETY.md: "STRIDE drives the
 * incline. It only ever suggests a speed." This produces a suggested km/h,
 * clamped to [minKph, maxKph], that the HUD offers as a tappable pill exactly
 * like a guided walk's pace hint (Stride.setSpeed on tap). Nothing here ever
 * changes targetKph.
 *
 * Deliberately tolerant rather than reactive: a heart rate wanders by a few
 * beats constantly, and a suggestion that chases every wobble would be a
 * pace that never settles. Two thresholds, the looser one needing to hold
 * longer — a small miss has to prove itself for half a minute, a big one
 * only needs ten seconds:
 *
 *   off by > SMALL_BPM for SMALL_HOLD_MS, or
 *   off by > LARGE_BPM for LARGE_HOLD_MS
 */
class HrTarget {

    companion object {
        const val SMALL_BPM = 4
        const val SMALL_HOLD_MS = 30_000L
        const val LARGE_BPM = 8
        const val LARGE_HOLD_MS = 10_000L

        /** How much to move the suggestion by, per nudge. Small and
         *  deliberate — a change a walker can feel and undo, not a
         *  calculated jump. */
        const val STEP_KPH = 0.3
    }

    private var offSince = 0L
    private var offDirection = 0        // +1 = HR too high (slow down), -1 = too low (speed up)
    @Volatile var suggestKph = 0.0
        private set

    fun reset() {
        offSince = 0L
        offDirection = 0
        suggestKph = 0.0
    }

    /**
     * One frame. Call every poll while the mode is active and genuinely at
     * pace (not mid-ramp). [currentKph] seeds the first suggestion when
     * nothing has been suggested yet.
     *
     * No reading (bpm <= 0) holds whatever was last suggested rather than
     * reacting to silence — a strap dropout is not a reason to change pace.
     */
    fun update(bpm: Int, targetBpm: Int, currentKph: Double, minKph: Double, maxKph: Double) {
        if (suggestKph <= 0.0) suggestKph = currentKph.coerceIn(minKph, maxKph)
        if (bpm <= 0 || targetBpm <= 0) { offSince = 0L; return }

        val diff = bpm - targetBpm          // positive: too hard, negative: too easy
        val now = SystemClock.elapsedRealtime()
        val direction = if (diff > 0) 1 else if (diff < 0) -1 else 0
        val absDiff = Math.abs(diff)

        if (direction == 0 || direction != offDirection) {
            offDirection = direction
            offSince = if (direction == 0) 0L else now
            return
        }

        val due = (absDiff > LARGE_BPM && now - offSince >= LARGE_HOLD_MS) ||
                  (absDiff > SMALL_BPM && now - offSince >= SMALL_HOLD_MS)
        if (!due) return

        // direction 1 = HR too high = slow down; -1 = too low = speed up.
        suggestKph = (suggestKph - direction * STEP_KPH).coerceIn(minKph, maxKph)
        offSince = now   // re-arm: the next nudge needs its own hold
    }
}