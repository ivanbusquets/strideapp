package dev.stride.hud

import java.io.File

/**
 * The walk that has just ended, held until the summary screen is left.
 *
 * **Why this is a separate object.** The console asks how hard the walk felt
 * on the summary screen, so the summary file cannot be written at the moment
 * the belt stops: it has to wait for the person, and it must be written once
 * with what they tapped. That is a small piece of state with one rule — the
 * first exit wins — and keeping it here rather than inside [WalkLog] lets the
 * rule be tested without a console.
 *
 * **One summary, one send.** [hold] takes the walk, [rate] may change the
 * rating as often as the person changes their mind, and [close] hands the walk
 * to exactly one caller. Every later [close] finds nothing, so DONE followed
 * by sleep (or the reverse) cannot write two files or start two sends.
 *
 * **No summary without a CSV.** A summary is only half of a pair, and a
 * summary with no CSV can never be sent; writing one would leave an orphan on
 * the console. [hold] therefore refuses a walk whose CSV is not on disk, which
 * is the one case where the recorder could not open the file.
 */
internal class PendingSummary(private val dir: File) {

    private val lock = Any()
    private var id: String? = null
    private var walk: History.Walk? = null
    private var rpe: Int? = null

    /**
     * Hold this walk's summary until the screen is left.
     *
     * Returns false when a walk is already held, or when its CSV is not on the
     * console — in both cases nothing is written.
     */
    fun hold(id: String, walk: History.Walk): Boolean = synchronized(lock) {
        if (this.id != null) return false
        if (!File(dir, "$id${WalkFiles.CSV_SUFFIX}").isFile) return false
        this.id = id
        this.walk = walk
        this.rpe = null
        true
    }

    /**
     * Remember a rating. Out-of-range and null both mean "not given", which is
     * absent from the file rather than a zero — see [History.Walk.rpe].
     *
     * Does nothing once the summary has been closed: the file that went is a
     * snapshot, and history.json is the record of what was tapped afterwards.
     */
    fun rate(rpe: Int?) {
        synchronized(lock) {
            if (id == null) return
            this.rpe = rpe?.takeIf { it in 1..10 }
        }
    }

    /**
     * Write the one summary file and give up the held walk.
     *
     * The held walk is cleared before the file is written, so two threads that
     * arrive together cannot both write it: the winner writes, the loser sees
     * [Close.Nothing]. A write that fails is reported as [Close.Failed] and
     * leaves the CSV unpaired, which is the same shape as an interrupted walk.
     */
    fun close(): Close {
        val id: String
        val walk: History.Walk
        val rpe: Int?
        synchronized(lock) {
            id = this.id ?: return Close.Nothing
            walk = this.walk ?: return Close.Nothing
            rpe = this.rpe
            this.id = null
            this.walk = null
            this.rpe = null
        }
        return try {
            dir.mkdirs()
            File(dir, "$id${WalkFiles.SUMMARY_SUFFIX}")
                .writeText(walk.copy(rpe = rpe).json().toString(2))
            Close.Written(id, rpe)
        } catch (_: Exception) {
            Close.Failed
        }
    }

    sealed class Close {
        /** The summary was written; this is the one caller that may send it. */
        class Written(val id: String, val rpe: Int?) : Close()

        /** Nothing was held: another exit got there first, or there is no walk. */
        object Nothing : Close()

        /** The walk was held but its file could not be written. */
        object Failed : Close()
    }
}
