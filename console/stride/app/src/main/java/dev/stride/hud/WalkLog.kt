package dev.stride.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Two files per walk, written on the console and sent off it.
 *
 * **What this is for.** A walk the console records is a walk nobody else can
 * see. `history.json` keeps the summary — who, how far, how fast — and that
 * is the whole of it: one line per workout, on the machine, readable by
 * whoever can open the file. This adds the two things a summary cannot give
 * anybody:
 *
 *  * a **CSV of every second of the walk**, so the shape of it survives —
 *    where the climb was, when the pace fell off, what the heart rate was
 *    doing at the time. Nothing on the console records per-second data
 *    today, and the ring buffer that used to hold it is long gone.
 *
 *  * a **send**, so those files leave the treadmill and reach something that
 *    can do something with them.
 *
 * **The CSV is written as the walk goes, not afterwards.** A row a second,
 * appended and flushed, so a console that loses power in the middle of a
 * workout costs the last row and nothing else. A file written at the end
 * would be a file that exists or does not, and the treadmill is a machine in a
 * hallway that gets switched off at the wall.
 *
 * **The send is a plain HTTP POST of both files together**, as
 * `multipart/form-data`, with the walk id as a form field so the receiving
 * side can tell that the two files belong to one walk. The JDK's own
 * `HttpURLConnection` does it: this app is deliberately AndroidX-free and
 * carries one dependency, and a file-upload library would be a bigger step
 * than the feature deserves.
 *
 * **Nothing is sent twice, and nothing is lost.** Both files stay on the
 * console until the destination has accepted them, and a send that could not
 * reach the destination is tried again at the end of the next walk, oldest
 * first. A console that cannot reach the listener therefore accumulates
 * files rather than losing walks, and says so in the log rather than failing
 * quietly.
 *
 * **The summary is written when the walk ends, and sent when the summary
 * screen is left.** Those are two different moments and the gap between them
 * is the point: the person is asked how hard the walk felt on that screen, and
 * a file written at the moment of leaving would carry the rating only if they
 * had already answered — and would not exist at all if the console were
 * switched off at the wall while the summary was up. So `endWalk` writes the
 * summary there and then, unrated if nobody has tapped yet, and `finishSummary`
 * rewrites it with whatever was tapped and sends it once, on the way out (see
 * [rate]). A console that is left sitting on the summary sends nothing until
 * it sleeps or the next walk begins, which is the cost of the thing it buys.
 *
 * **Which walks get files at all** is [History]'s rule and not this class's:
 * a guest's walk and a walk under 300 m or three minutes are not recorded
 * locally, and a walk that is not recorded locally produces no files. See
 * [History.recordable].
 */
class WalkLog(private val context: Context, private val settings: Settings) {

    /** Guards the recording state. The poll thread and the WebView thread
     *  both reach it — the walk ends from a button press, and the recorder
     *  runs from the poll loop. */
    private val lock = Any()

    /** Guards the sending, so two walks ending at once cannot both send the
     *  same files. */
    private val sendLock = Any()

    /** Where the files live: the console's own storage, beside history.json. */
    private val dir = File(context.filesDir, DIR)

    /**
     * The walk being written, or null when there is none.
     *
     * Non-null means a CSV is open on the console for that walk. The summary
     * is written when the walk ends, and the pair is only then a candidate for
     * sending — so a walk cut short by a power cut leaves a CSV with no
     * summary, which is not a pair and is never sent.
     */
    private var currentId: String? = null
    private var writer: BufferedWriter? = null
    private var lastRowAt = 0L

    /**
     * The walk whose summary is written and waiting for the summary screen to
     * be left: its id, the walk itself, and the rating tapped on that screen.
     *
     * Held in memory rather than on disk because it lives for as long as the
     * screen does. The rating is re-applied when the file is rewritten, so the
     * file and the eventual send carry the last number tapped rather than the
     * first.
     */
    private var pendingId: String? = null
    private var pendingWalk: History.Walk? = null
    private var pendingRpe: Int? = null

    /**
     * True once this walk has been turned away for having no profile.
     *
     * Without it the recorder would ask again on every poll, and log the
     * same refusal five times a second for forty minutes.
     */
    private var turnedAway = false

    /** The last thing that went wrong, for the settings screen. */
    @Volatile var lastError: String = ""

    // --- the walk ------------------------------------------------------------

    /**
     * A walk is beginning: open its CSV.
     *
     * **Called at the start of the walk, and again when the console zeroes
     * the session totals.** The second call is the warm-up fold: when the
     * console decides that the warm-up was not part of the workout — see
     * `Settings.resetAfterWarmup` — the recorded walk starts there, so the
     * CSV and the summary describe the same stretch of time. A CSV that
     * included the warm-up and a summary that did not would be two files
     * about one walk that disagreed about how far it was — and the
     * distance column would go backwards at the fold, because the totals
     * are zeroed there.
     *
     * The same file is rewritten rather than a second one started, so a walk
     * is always exactly one pair of files however many times this is called.
     *
     * @param who the walker. A guest's walk is not written at all: a guest
     *        has agreed to nothing, and this walk is not one the console
     *        keeps — see [History.recordable].
     */
    fun beginWalk(who: String) {
        if (!settings.walkLogOn()) return
        // **A walk beginning is also a way off the last walk's summary.** The
        // previous walk is over by definition, so a summary still waiting to be
        // sent is let go here rather than held behind this one — see
        // [finishSummary]. It usually has nothing to do: leaving the summary
        // for the welcome screen is the ordinary exit, and it gets there first.
        finishSummary()
        synchronized(lock) {
            turnedAway = false
            if (!hasProfile(who)) {
                // Left for the tick to notice, so the refusal is logged
                // once rather than on every poll.
                return
            }
            openCsv()
        }
    }

    /**
     * One tick of the recorder: start a file if a walk has begun, and append
     * a row if a second has passed.
     *
     * Called from the poll loop, so it runs at the poll rate and decides for
     * itself when a second has gone by. **One row per second while the belt
     * is live** — which is [Session.isMoving], the console's own answer to
     * "is the belt turning and the clock running". A pause is not a row: the
     * clock is held while the belt is stopped, so a row then would be a
     * duplicate of the last one.
     *
     * A walk with no profile is turned away before anything is written. A
     * guest has agreed to nothing, and a guest's walk does not belong in a
     * file on the console any more than it belongs in `history.json`.
     */
    fun tick(snap: Snapshot) {
        if (!settings.walkLogOn()) return
        synchronized(lock) {
            if (!Session.isMoving(snap.session)) return
            if (writer == null) {
                if (turnedAway) return
                if (!hasProfile(snap.who)) {
                    turnedAway = true
                    Log.i(TAG, "walk log: ${snap.who.ifBlank { "this walk" }} has no profile — not recorded")
                    return
                }
                // The walk log was turned on part-way through this walk, so
                // beginWalk() was never called for it. Start the file now, and
                // the walk is recorded from here on.
                openCsv()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastRowAt < ROW_EVERY_MS) return
            lastRowAt = now
            try {
                writer?.write(WalkFiles.csvRow(snap))
                writer?.write("\n")
                // Flushed every row, because the point of writing as the walk
                // goes is that a power cut costs the last row and not the
                // last few seconds of buffer.
                writer?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "walk log: cannot write the row (${e.message})")
                closeWriter()
            }
        }
    }

    /**
     * Open the CSV for the current walk, truncating whatever was there.
     *
     * **Truncating rather than appending** is what makes the warm-up fold
     * work: the totals are zeroed there, so the walk starts again, and a
     * file that kept the warm-up's rows would carry metres that the summary
     * does not. A walk is always exactly one pair of files.
     */
    private fun openCsv() {
        val id = currentId ?: newWalkId().also { currentId = it }
        try {
            dir.mkdirs()
            val out = BufferedWriter(
                OutputStreamWriter(
                    FileOutputStream(File(dir, "$id${WalkFiles.CSV_SUFFIX}"), false),
                    Charsets.UTF_8
                )
            )
            // The header goes down before anything else, so a file with no
            // rows in it is still a readable file rather than a fragment.
            out.write(WalkFiles.CSV_HEADER)
            out.write("\n")
            out.flush()
            writer = out
            lastRowAt = 0L
            Log.i(TAG, "walk log: recording $id")
        } catch (e: Exception) {
            Log.w(TAG, "walk log: cannot start the CSV (${e.message})")
            closeWriter()
        }
    }

    // --- at the end of the walk ----------------------------------------------

    /**
     * The walk has ended: write the summary, or not. **Do not send yet.**
     *
     * The summary carries exactly what `history.json` carries for the same
     * walk — the same fields, produced by the same code — so the two cannot
     * disagree. The CSV and the summary are named with the same walk id, which
     * is how a receiver pairs them up.
     *
     * The file goes down *now* rather than when the summary screen is left,
     * because a console switched off at the wall with the summary on screen
     * must still leave a summary behind. It is written unrated — nobody has
     * been asked yet — and rewritten by [finishSummary] with whatever was
     * tapped on the way out. The send waits for that same moment: see the
     * class comment for why the two were pulled apart.
     *
     * A walk that is not recorded locally produces no files at all, and the
     * CSV that was being written for it is thrown away: a stroll that stopped
     * after two minutes is not a walk anybody asked for.
     */
    fun endWalk(walk: History.Walk, hasProfile: Boolean) {
        val id: String?
        synchronized(lock) {
            closeWriter()
            id = currentId
            currentId = null
            turnedAway = false
        }
        if (id != null) {
            if (History.recordable(walk, hasProfile)) {
                val unrated = walk.copy(rpe = null)
                if (writeSummary(id, unrated)) {
                    synchronized(lock) {
                        pendingId = id
                        pendingWalk = unrated
                        pendingRpe = null
                    }
                    Log.i(TAG, "walk log: wrote the summary for $id — " +
                            "the send waits for the summary screen to be left")
                }
            } else {
                File(dir, "$id${WalkFiles.CSV_SUFFIX}").delete()
                Log.i(TAG, "walk log: walk not worth keeping — no files for $id")
            }
        }
    }

    /**
     * Remember the rating tapped on the summary screen.
     *
     * Nothing is written here: the file on the console is rewritten once, by
     * [finishSummary], with the last number tapped rather than the first. That
     * is also what stops a person changing their mind from costing a second
     * request.
     *
     * Once [finishSummary] has run — the console slept, or the summary was left
     * — there is nothing pending and this does nothing, so a rating tapped by
     * somebody who woke the panel on the summary afterwards reaches
     * history.json (see [History.rate]) but not the copy already sent. That is
     * the one send per walk the request asks for; the file is a snapshot of
     * what was on screen when it went, and history is the record of what was
     * tapped.
     */
    fun rate(rpe: Int?) {
        synchronized(lock) {
            if (pendingId == null) return
            pendingRpe = rpe?.takeIf { it in 1..10 }
        }
    }

    /**
     * The summary screen has been left — or the console has gone to sleep, or
     * the next walk has begun. Rewrite the summary with the rating and send.
     *
     * Called from every one of those exits ([MainActivity.home],
     * [MainActivity.Bridge.dim] and [beginWalk]), because each of them can be
     * the last thing that happens before the console is switched off, and the
     * one thing this must never do is hold a finished walk for a screen nobody
     * is standing in front of. The first call wins and the rest find nothing
     * pending, so the pair can only be sent once from here.
     */
    fun finishSummary() {
        val id: String?
        val walk: History.Walk?
        val rpe: Int?
        synchronized(lock) {
            id = pendingId
            walk = pendingWalk
            rpe = pendingRpe
            pendingId = null
            pendingWalk = null
            pendingRpe = null
        }
        if (id == null || walk == null) return
        val rated = walk.copy(rpe = rpe)
        if (!writeSummary(id, rated)) {
            // No summary means no pair, and an unpaired CSV is never sent.
            // The CSV stays on the console; nothing else can be done with it.
            Log.w(TAG, "walk log: no summary for $id — that walk is not sent")
            return
        }
        Log.i(TAG, "walk log: summary for $id closed" +
                if (rpe != null) " at $rpe/10" else " unrated")
        // On its own thread: the person is on their way off the summary, and a
        // network timeout is somebody else's problem to wait for. Same
        // reasoning as the MQTT retry thread.
        Thread({ flush() }, "walk-log-send").start()
    }

    /** Write one walk's summary file. Returns false when it could not be. */
    private fun writeSummary(id: String, walk: History.Walk): Boolean = try {
        dir.mkdirs()
        File(dir, "$id${WalkFiles.SUMMARY_SUFFIX}")
            .writeText(walk.json().toString(2))
        true
    } catch (e: Exception) {
        Log.w(TAG, "walk log: cannot write the summary (${e.message})")
        false
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "walk log: cannot close the CSV (${e.message})")
        }
        writer = null
    }

    // --- sending --------------------------------------------------------------

    /**
     * Send everything that is waiting, oldest first, and delete what the
     * destination accepted.
     *
     * **Oldest first, and it stops at the first failure.** A destination that
     * is not answering would fail every walk the same way, and trying the
     * rest of the queue against it is a way of making a broken listener look
     * busy. The queue keeps its order instead, and the oldest walk goes
     * first the moment the destination comes back.
     *
     * A pair that cannot even be read — a truncated file, say — is skipped
     * rather than allowed to block every walk behind it. It stays on the
     * console, because the rule is that files are removed only once their
     * send has succeeded.
     */
    fun flush() {
        synchronized(sendLock) {
            if (!settings.walkLogOn()) return
            val url = settings.walkLogUrl().trim()
            if (url.isBlank()) {
                lastError = "no destination is set"
                return
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                lastError = "the destination is not an http address"
                Log.w(TAG, "walk log: $lastError — $url")
                return
            }
            try {
                dir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "walk log: cannot use ${dir.absolutePath} (${e.message})")
                return
            }

            val pairs = WalkFiles.pairsIn(dir)
            if (pairs.isEmpty()) {
                lastError = ""
                return
            }
            val already = sentIds()
            for (pair in pairs) {
                if (already.contains(pair.id)) {
                    // Sent before the console lost track of it. The files go
                    // now rather than being sent a second time.
                    pair.csv.delete()
                    pair.summary.delete()
                    continue
                }
                val csvBytes = readOrNull(pair.csv)
                val summaryBytes = readOrNull(pair.summary)
                if (csvBytes == null || summaryBytes == null) {
                    Log.w(TAG, "walk log: cannot read the files for ${pair.id} — skipping")
                    continue
                }
                val who = whoOf(summaryBytes)
                // **Stops at the first failure.** The walks behind it keep
                // their place and are tried at the end of the next walk —
                // see WalkFiles.toSend, which is where that rule lives and
                // where it is tested.
                if (!post(url, pair.id, who, csvBytes, summaryBytes)) break
                markSent(pair.id)
                pair.csv.delete()
                pair.summary.delete()
                lastError = ""
                Log.i(TAG, "walk log: sent ${pair.id}")
            }
        }
    }

    /**
     * One POST of both files, as one request.
     *
     * The walk id goes as a form field as well as in both filenames, so the
     * receiver can pair the two files without having to parse either name.
     * `who` goes too: it is in the summary as well, but a receiver that
     * wants to route by person should not have to open a file to find out
     * whose walk this was.
     */
    private fun post(url: String, id: String, who: String,
                     csv: ByteArray, summary: ByteArray): Boolean {
        val boundary = "----STRIDE" + UUID.randomUUID().toString().replace("-", "")
        val body = WalkFiles.multipart(
            boundary,
            listOf(
                WalkFiles.Field("walk_id", id),
                WalkFiles.Field("who", who),
            ),
            listOf(
                WalkFiles.FilePart("csv", "$id${WalkFiles.CSV_SUFFIX}", "text/csv", csv),
                WalkFiles.FilePart("summary", "$id${WalkFiles.SUMMARY_SUFFIX}",
                                   "application/json", summary),
            )
        )
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Content-Length", body.size.toString())
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) return true
            lastError = "the destination answered $code"
            Log.w(TAG, "walk log: $lastError for $id — the files stay on the console")
            return false
        } catch (e: Exception) {
            lastError = e.message?.takeIf { it.isNotBlank() } ?: "the destination could not be reached"
            Log.w(TAG, "walk log: $lastError — $id stays on the console")
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // --- what the settings screen shows ---------------------------------------

    /** What the settings screen says about the log: see [MainActivity.Bridge]. */
    fun status(): JSONObject = JSONObject()
        .put("enabled", settings.walkLogOn())
        .put("url", settings.walkLogUrl())
        .put("pending", WalkFiles.pairsIn(dir).size)
        .put("recording", currentId ?: "")
        .put("error", lastError)

    // --- bookkeeping -----------------------------------------------------------

    /**
     * Whether this walker is somebody the console knows.
     *
     * A name that is not in the people list is a guest, whoever picked it —
     * and a guest's walk is not one the console keeps. This is the same
     * question [Settings.coachedFor] and [Settings.publishFor] ask, and it
     * gets the same answer: unknown walkers get neither.
     */
    private fun hasProfile(who: String): Boolean = settings.person(who) != null

    /**
     * A walk id: when it started, and enough of a random suffix to stay
     * unique.
     *
     * The clock is not trusted for this on its own. This board boots at 2010
     * and only finds the real time once the network is up, which can be ten
     * minutes into a walk — so two walks on two different days can start at
     * the same millisecond, and a walk id that collided with one already sent
     * would pair the wrong files together on the far side. The suffix makes
     * that impossible; the timestamp in front of it keeps the ids in order,
     * which is what "oldest first" is built on.
     */
    private fun newWalkId(): String {
        val stamp = System.currentTimeMillis()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        return "walk-$stamp-$suffix"
    }

    /** The walk ids the destination has already accepted. */
    private fun sentIds(): Set<String> {
        val f = File(dir, SENT_LOG)
        if (!f.exists()) return emptySet()
        return try {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "walk log: cannot read the sent log (${e.message})")
            emptySet()
        }
    }

    /**
     * Record that a walk has been sent, so it is never sent again.
     *
     * The files are deleted as well, and that is what usually stops the
     * second send. This is the belt to that pair of braces: a console that
     * loses power between the destination accepting a walk and the console
     * deleting the files would otherwise send that walk a second time at the
     * end of the next workout. The receiver is told the walk id, so it can
     * ignore a repeat as well.
     */
    private fun markSent(id: String) {
        try {
            dir.mkdirs()
            val f = File(dir, SENT_LOG)
            val lines = f.readLines().filter { it.isNotBlank() }.toMutableList()
            lines.add(id)
            // Trimmed from the front. This is a safety net against one
            // specific crash, not a record of anything, and it is the only
            // file here that would otherwise grow for ever.
            val kept = if (lines.size > SENT_LOG_KEEP) lines.takeLast(SENT_LOG_KEEP) else lines
            f.writeText(kept.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "walk log: cannot update the sent log (${e.message})")
        }
    }

    private fun readOrNull(f: File): ByteArray? = try {
        f.readBytes()
    } catch (e: Exception) {
        Log.w(TAG, "walk log: cannot read ${f.name} (${e.message})")
        null
    }

    /** The walker's name, out of the summary that is being sent. */
    private fun whoOf(summary: ByteArray): String = try {
        JSONObject(String(summary, Charsets.UTF_8)).optString("who")
    } catch (e: Exception) {
        ""
    }

    companion object {
        private const val TAG = "StrideWalkLog"

        /** Where the files go, under the console's own storage. */
        private const val DIR = "walks"

        /** The list of walks the destination has already accepted. */
        private const val SENT_LOG = "sent.log"

        /** How many ids that list keeps. */
        private const val SENT_LOG_KEEP = 500

        /** One row a second, which is the rate the console already samples at. */
        private const val ROW_EVERY_MS = 1000L

        /**
         * A home network listener is a box in a hallway, not a bank. A
         * connection that hangs is a listener that is switched off, and the
         * treadmill should say so and carry on rather than hold a thread
         * open waiting for it.
         */
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}

/**
 * The parts of the walk log that are just bytes and file names.
 *
 * Kept apart from [WalkLog] on purpose: this is the part with the fiddly
 * rules in it — how a row is formatted, how a multipart body is laid out,
 * which files are a pair — and none of that needs an Android device to
 * check. It is what the unit tests read.
 */
object WalkFiles {

    /**
     * The CSV's header row.
     *
     * The seven fields the console already samples once a second. `elapsed`
     * is the session clock, `speed` is the setpoint the console is holding
     * and `belt_kph` is what the odometer says it is actually doing — two
     * different numbers, and the gap between them is the whole point of
     * keeping the second one.
     */
    const val CSV_HEADER =
        "elapsed_s,speed_kph,belt_kph,incline_pct,distance_m,calories_kcal,pulse_bpm"

    /** One row of the CSV, for one second of the walk. */
    fun csvRow(snap: Snapshot): String = buildString {
        append(num(snap.elapsed, 0)); append(',')
        append(num(snap.speed, 1)); append(',')
        append(num(snap.beltKph, 1)); append(',')
        append(num(snap.incline, 1)); append(',')
        append(num(snap.distance, 0)); append(',')
        append(num(snap.calories, 0)); append(',')
        append(snap.pulse)
    }

    /**
     * A number, formatted the same way everywhere.
     *
     * **In American decimal, always.** `String.format` follows the device's
     * locale, and a console set to a language that writes `6,5` would put a
     * comma in the middle of a CSV field and quietly break every reader.
     * The rest of the console has that bug; a file other people have to
     * parse is not the place to reproduce it.
     */
    private fun num(v: Double, dp: Int): String =
        String.format(Locale.US, "%.${dp}f", v)

    /** One form field of a multipart body. */
    class Field(val name: String, val value: String)

    /** One file part of a multipart body. */
    class FilePart(val name: String, val filename: String, val type: String, val bytes: ByteArray)

    /**
     * A multipart/form-data body: the fields, then the files, then the end.
     *
     * Written out by hand rather than by a library, because this app carries
     * one dependency and that one is a MQTT client. It is the shape every
     * multipart body has, and it is about twenty lines.
     */
    fun multipart(boundary: String, fields: List<Field>, files: List<FilePart>): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        for (f in fields) {
            ascii("--$boundary\r\n")
            ascii("Content-Disposition: form-data; name=\"${quote(f.name)}\"\r\n\r\n")
            out.write(f.value.toByteArray(Charsets.UTF_8))
            ascii("\r\n")
        }
        for (f in files) {
            ascii("--$boundary\r\n")
            ascii("Content-Disposition: form-data; name=\"${quote(f.name)}\"; " +
                  "filename=\"${quote(f.filename)}\"\r\n")
            ascii("Content-Type: ${f.type}\r\n\r\n")
            out.write(f.bytes)
            ascii("\r\n")
        }
        ascii("--$boundary--\r\n")
        return out.toByteArray()
    }

    /**
     * A name safe to put inside a header's quoted string.
     *
     * **The walker's name is typed in by hand**, and somebody's name can
     * contain a quote — which would end the quoted string early and produce
     * a header the receiving side parses as something else. A backslash and
     * a newline are the other two that matter: the first escapes the next
     * character, the second ends the header.
     *
     * The walk id is generated and needs none of this, but the name does,
     * and a body that a name can break is a body that breaks.
     */
    private fun quote(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }

    /** A walk's two files, waiting to be sent. */
    class Pair(val id: String, val csv: File, val summary: File)

    /**
     * Which of the queued walks to try, and in what order.
     *
     * **The queue's rules, with no network and no device in them.** A
     * destination that is not answering would fail every walk the same
     * way, so the queue is walked oldest first and **stops at the first
     * failure** — the rest keep their place rather than being thrown at a
     * broken listener one after another. A walk already known to have been
     * accepted is not in the list at all: it is never sent twice.
     *
     * A walk whose files cannot be read is skipped rather than allowed to
     * block every walk behind it, but it is *not* sent — so it stays on
     * the console, which is the rule: files are removed only once their
     * send has succeeded.
     *
     * @param pairs the queued walks, oldest first
     * @param alreadySent the ids the destination has accepted before
     * @param readable whether a walk's two files can actually be read
     * @return the walks to send, in order
     */
    fun toSend(
        pairs: List<Pair>,
        alreadySent: Set<String>,
        readable: (Pair) -> Boolean,
    ): List<Pair> {
        val out = ArrayList<Pair>()
        for (pair in pairs) {
            if (alreadySent.contains(pair.id)) continue
            if (!readable(pair)) continue
            out.add(pair)
        }
        return out
    }

    /**
     * The pairs of files on the console, oldest first.
     *
     * A walk is a *pair*: a CSV and a summary that share an id. A CSV with
     * no summary is a walk that was cut short, and is not sent — there is
     * nothing to say about it, and a half-walk is not a walk.
     *
     * The order is the id order, and an id starts with the millisecond the
     * walk began, so oldest first is just the file order.
     */
    fun pairsIn(dir: File): List<Pair> {
        val files = dir.listFiles() ?: return emptyList()
        val csvs = HashMap<String, File>()
        val summaries = HashMap<String, File>()
        for (f in files) {
            val n = f.name
            when {
                n.endsWith(CSV_SUFFIX) -> csvs[n.removeSuffix(CSV_SUFFIX)] = f
                n.endsWith(SUMMARY_SUFFIX) -> summaries[n.removeSuffix(SUMMARY_SUFFIX)] = f
            }
        }
        val out = ArrayList<Pair>()
        for ((id, csv) in csvs) {
            val summary = summaries[id] ?: continue
            out.add(Pair(id, csv, summary))
        }
        out.sortBy { it.id }
        return out
    }

    const val CSV_SUFFIX = ".csv"
    const val SUMMARY_SUFFIX = ".summary.json"
}
