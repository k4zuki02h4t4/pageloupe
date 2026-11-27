package jp.kaleidpixel.pageloupe

import java.io.ByteArrayOutputStream

/**
 * Observes one WebSocket stream (upstream -> client) and delivers
 * complete text messages to [onTextMessage]. Observation only: the
 * relay never depends on this class, and any failure inside it
 * (including OutOfMemoryError) stops the tap instead of breaking
 * the stream.
 *
 * Parsing runs over a single offset buffer: bytes are appended at
 * [end], frames are consumed by advancing [start], and the consumed
 * prefix is compacted away only when it grows large or space runs
 * out. (Copying the whole pending buffer on every feed would make
 * a message of N bytes cost roughly N^2 / 16KiB of copying; the
 * offset buffer keeps it linear.)
 *
 * Messages are observed up to [MAX_MESSAGE_BYTES], both as received
 * and after inflation. A message that grows past the cap is dropped
 * whole: its remaining frames are consumed and skipped, so the
 * parser stays in sync and the next message is observed normally.
 *
 * Clear-data generations: [clearDiagnostics] bumps a generation
 * counter, and a message is delivered only while the generation
 * of its FIRST bytes is still current. The generations satisfy
 * msgEpoch ≤ feedStartEpoch ≤ diagEpoch at all times (each is a
 * snapshot of a later one), so the single comparison against the
 * message's own generation decides delivery. The check runs at
 * the delivery site and once more, under the samples lock, when
 * a diagnostic sample is taken (samples hold inflated plaintext,
 * up to 3 x 120 chars, and messages routinely span several
 * feeds). The residuals of this design are listed on
 * [clearDiagnostics].
 */
class WsMessageTap(
    /**
     * When non-null, only messages whose CDP method is in
     * this set are delivered (PER-5): the method name is
     * peeked from the raw bytes' prefix, so an uninteresting
     * message (a screencast frame, a dataReceived event)
     * never pays for a full UTF-8 String conversion and a
     * JSON parse downstream. Filtered-out messages are not
     * "dropped" — they were never the consumer's business,
     * like binary frames. Null delivers everything, which
     * is what the JVM tests exercise. (Declared before the
     * listener so the listener keeps the trailing-lambda
     * slot.)
     */
    private val methodFilter: Set<String>? = null,
    private val onTextMessage: (String) -> Unit
) {

    /** Diagnostics hook: total bytes fed into the parser.
     *  Also used by the JVM tests as a fault-injection point. */
    var onBytes: ((Int) -> Unit)? = null

    // --- Diagnostics (read after the session to see what really flowed) ---
    @Volatile var deliveredCount: Int = 0
        private set
    @Volatile var inflateTried: Int = 0
        private set
    @Volatile var inflateOk: Int = 0
        private set
    private val headBytes = mutableListOf<Byte>()

    /**
     * Set by [clearDiagnostics] under the headBytes lock: the
     * first-bytes capture is a start-of-stream diagnostic and
     * never refills after a clear.
     */
    private var headSealed = false
    private val samples = mutableListOf<String>()

    /** Copy of the diagnostic samples, taken under their lock. */
    fun samplesSnapshot(): List<String> = synchronized(samples) { samples.toList() }

    fun headHex(): String = synchronized(headBytes) {
        headBytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Whether a clear has sealed the first-bytes capture. */
    fun isHeadSealed(): Boolean = synchronized(headBytes) { headSealed }

    /**
     * Forget the diagnostic CONTENT (first bytes, first samples)
     * without touching the counters. Used by clear-data: pre-clear
     * fragments must not be written back by a later tap-debug
     * dump, while the loss counters and the failure flag describe
     * the LIVE tap and must stay readable for the stats line and
     * the export notes — so the tap itself is never discarded
     * here. The generation mechanism is in the class KDoc; this
     * bump happens under the samples lock, which is what makes
     * the increment safe. Residuals, stated plainly:
     * (1) A message whose delivery-site check passed just before
     * the clear can still reach the RECORDER (at most one in
     * flight on the relay thread; samples are closed by the
     * re-check under the lock). The caller runs this before
     * recorder.clear(), which bounds it to that one delivery.
     * (2) Only the current tap (lastTap) is cleared; a previous
     * connection's tap still draining is not covered.
     * (3) The boundary is the feed point: a chunk is
     * observed only after it has been forwarded, so a chunk
     * read before the clear but not yet forwarded — plus
     * whatever is still in the socket buffer — counts as
     * received after the clear. A write or flush blocked on a slow
     * panel widens that window; it cannot be sharpened.
     * (4) Over-retire, the safe direction: a message that merely
     * shares a feed with a retired one, whose first bytes
     * predate the clear even when most of it arrived after, or
     * that starts in a feed whose buffer still holds a partial
     * control frame from before the clear (the latch refreshes
     * only on an empty buffer), is retired too and counted
     * nowhere — at most the traffic around one clear is
     * affected, and recording resumes from the next message.
     * (5) The first-bytes capture ([noteFed]) is a start-of-
     * stream diagnostic: a clear seals it, and it never
     * refills. The seal and the capture share the headBytes
     * lock, so a feed racing the clear either lands before it
     * (and is wiped) or after it (and captures nothing).
     */
    fun clearDiagnostics() {
        synchronized(samples) { diagEpoch++; samples.clear() }
        synchronized(headBytes) { headBytes.clear(); headSealed = true }
    }

    /** Bumped by [clearDiagnostics]; see its KDoc. */
    @Volatile private var diagEpoch = 0

    /** The [diagEpoch] the message being assembled started under (feed lock only). */
    private var msgEpoch = 0

    /**
     * [diagEpoch] as the in-flight [feed] started (feed lock
     * only). A snapshot, not a second delivery condition: its
     * only job is feeding the [nextMsgEpoch] latch.
     */
    private var feedStartEpoch = 0

    /**
     * The epoch of the feed that delivered the FIRST bytes of
     * the next message (feed lock only); the generation rules
     * are in the class KDoc. Latched on an empty buffer with no
     * message in progress, and refreshed at every message end
     * to the completing feed.
     */
    private var nextMsgEpoch = 0

    private fun noteFed(data: ByteArray, off: Int, len: Int) {
        synchronized(headBytes) {
            if (headSealed) return
            var i = 0
            while (headBytes.size < 256 && i < len) {
                headBytes.add(data[off + i])
                i++
            }
        }
    }

    private fun noteDelivered(text: String) {
        deliveredCount++
        synchronized(samples) {
            // Re-checked under the lock (the bump and the list
            // clear share it): a clear landing between the
            // delivery site's check and this add still keeps
            // the sample out.
            if (samples.size < 3 && diagEpoch == msgEpoch) {
                // Stored raw: the one consumer (the tap-debug
                // dump) escapes with the shared rule
                // (DebugLog.escapeLine) when it writes.
                samples.add(text.take(120))
            }
        }
    }

    // --- Receive buffer (offsets into one array, no per-feed copies) ---
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    // --- Message assembly state ---
    private var msgBuf = ByteArrayOutputStream()
    private var inMessage = false
    private var msgOpcode = -1
    private var msgCompressed = false
    /** The current message passed the cap: drop it whole, deliver nothing. */
    private var msgDropped = false

    /**
     * Completed messages that were dropped whole (over the size
     * cap, or inflate-truncated) and therefore never reached the
     * recorder. Without this counter the exported log would
     * look complete even when it is not; the stats line and the
     * export header surface it.
     */
    @Volatile var droppedMessages: Int = 0

    /** Messages the method filter (PER-5) turned away:
     * counted separately (review round 2, PER-6) so the
     * diagnostics can still tell that traffic flowed even
     * though none of it was recorded. */
    @Volatile var filteredMessages: Int = 0
        private set

    /**
     * True once observation FAILED (corrupt stream, invalid
     * frame, inflate budget): the tap stopped mid-connection and
     * later messages were not recorded. A normal [close] at
     * connection end does NOT set this: conflating the two would
     * make the stats line and the export notes claim "tap
     * stopped" for every cleanly closed connection, e.g. in the
     * window between a reconnect's frontend load and its new
     * socket.
     * Note the wider internal state is [stopped] (failed OR
     * closed); this property is the narrow one — failure only.
     */
    val hasFailed: Boolean get() = failed

    // --- Skip state for a frame whose payload is not stored ---
    private var skipRemaining = 0L
    private var skipIsData = false
    private var skipFin = false

    @Volatile private var stopped = false
    @Volatile private var failed = false

    /**
     * Enter the failed state: observation stops for good. This
     * is the one place that sets the pair, so no failure path
     * can set [stopped] and forget [failed] (or the reverse).
     */
    private fun fail() {
        failed = true
        stopped = true
    }

    /**
     * The peer uses permessage-deflate WITH context takeover: one shared
     * raw-deflate stream spans the whole connection, so a single Inflater
     * must be kept alive across messages. (Verified against a captured
     * stream: a fresh Inflater per message fails with "invalid distance
     * too far back" on almost every message.)
     */
    private var sharedInflater: java.util.zip.Inflater? = null

    @Synchronized
    fun feed(data: ByteArray, off: Int, len: Int) {
        if (stopped || len <= 0) return
        feedStartEpoch = diagEpoch
        if (!inMessage && skipRemaining == 0L && end == start) {
            nextMsgEpoch = feedStartEpoch
        }
        try {
            onBytes?.invoke(len)
            noteFed(data, off, len)
            append(data, off, len)
            drain()
        } catch (t: Throwable) {
            // Observation must never take the relay down. Whatever went
            // wrong (a corrupt stream, an OOM on a huge message), the
            // tap stops here and the bytes flow on untapped.
            fail()
        }
        // However the tap became stopped (close path aside), its
        // buffers are dead weight for the rest of the connection.
        // The release itself allocates, so it is guarded too: an
        // OOM here must not escape the failure containment above
        // and take the relay down. A failed release is logged
        // (gated): the buffers then simply live until GC, but the
        // fact that the guard fired stays observable. The log
        // call needs no guard of its own: DebugLog.log never
        // throws, by its documented best-effort contract.
        if (stopped) {
            try { releaseBuffers() } catch (_: Throwable) {
                DebugLog.log("tap", "buffer release failed after stop")
            }
        }
    }

    /** Release the native inflater state. The tap is unusable afterwards. */
    @Synchronized
    fun close() {
        stopped = true
        try { sharedInflater?.end() } catch (_: Throwable) {}
        sharedInflater = null
        // Release the (possibly multi-MiB) buffers instead of holding
        // them until the next tap is created: the diagnostics counters
        // and samples above are all that is read after close. The
        // release allocates, so it is guarded like the rest of
        // teardown: a failure here must not skip the caller's own
        // cleanup. (The log call in the guard relies on
        // DebugLog.log's never-throws contract, as in feed.)
        try { releaseBuffers() } catch (_: Throwable) {
            DebugLog.log("tap", "buffer release failed at close")
        }
    }

    /** Drop the large buffers; diagnostics counters/samples stay. Callers hold the lock. */
    private fun releaseBuffers() {
        buf = ByteArray(INITIAL_CAPACITY)
        start = 0
        end = 0
        msgBuf = ByteArrayOutputStream()
    }

    private fun append(data: ByteArray, off: Int, len: Int) {
        if (end + len > buf.size) {
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            }
            if (end + len > buf.size) {
                var cap = buf.size * 2
                while (cap < end + len) cap *= 2
                buf = buf.copyOf(cap)
            }
        }
        System.arraycopy(data, off, buf, end, len)
        end += len
    }

    private fun compactIfNeeded() {
        if (start == end) {
            start = 0
            end = 0
            // PER-8: fully drained after growing for one
            // huge frame — hand the capacity back. The
            // threshold is SHRINK_AFTER_BYTES, not
            // INITIAL_CAPACITY (review round 2, PER-1):
            // the relay feeds up to 32KB chunks, so a
            // buffer that merely grew past 16KB to hold
            // one chunk was freed and re-grown on every
            // frame boundary during bulk transfers.
            if (buf.size > SHRINK_AFTER_BYTES) {
                buf = ByteArray(INITIAL_CAPACITY)
            }
        } else if (start >= COMPACT_THRESHOLD) {
            System.arraycopy(buf, start, buf, 0, end - start)
            end -= start
            start = 0
        }
    }

    private fun drain() {
        while (true) {
            if (stopped) return
            if (skipRemaining > 0) {
                val n = minOf(skipRemaining, (end - start).toLong())
                start += n.toInt()
                skipRemaining -= n
                if (skipRemaining > 0) {
                    compactIfNeeded()
                    return
                }
                finishSkip()
                continue
            }
            val avail = end - start
            if (avail < 2) {
                compactIfNeeded()
                return
            }
            val b0 = buf[start].toInt()
            val b1 = buf[start + 1].toInt()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var payloadLen = (b1 and 0x7F).toLong()
            var headerLen = 2
            when (payloadLen) {
                126L -> {
                    if (avail < 4) {
                        compactIfNeeded()
                        return
                    }
                    payloadLen = ((buf[start + 2].toInt() and 0xFF) shl 8 or
                        (buf[start + 3].toInt() and 0xFF)).toLong()
                    headerLen = 4
                }
                127L -> {
                    if (avail < 10) {
                        compactIfNeeded()
                        return
                    }
                    var v = 0L
                    for (i in 0 until 8) {
                        v = v shl 8 or (buf[start + 2 + i].toInt() and 0xFF).toLong()
                    }
                    payloadLen = v
                    headerLen = 10
                }
            }
            if (masked) headerLen += 4
            if (payloadLen < 0) {
                // An 8-byte length with the top bit set is not a valid
                // frame. Parsing cannot resynchronize; stop observing.
                fail()
                return
            }
            val isData = opcode == 0 || opcode == 1 || opcode == 2
            var store = false
            if (isData && !msgDropped) {
                val projected = (if (inMessage) msgBuf.size() else 0) + payloadLen
                if (projected <= MAX_MESSAGE_BYTES) {
                    store = true
                } else {
                    // This frame pushes the message over the cap: the
                    // whole message is dropped. What was buffered is
                    // discarded, and this frame's payload is skipped
                    // below like any unstored frame.
                    msgDropped = true
                    // Shrink here, not in endMessage (review
                    // round 2, PER-6): after this reset the
                    // assembled size reads 0, so the end-of-
                    // message shrink check never saw a dropped
                    // oversize message's grown buffer.
                    if (msgBuf.size() > SHRINK_AFTER_BYTES) {
                        msgBuf = java.io.ByteArrayOutputStream()
                    } else {
                        msgBuf.reset()
                    }
                }
            }
            if (!store) {
                if (avail < headerLen) {
                    compactIfNeeded()
                    return
                }
                start += headerLen
                skipRemaining = payloadLen
                skipIsData = isData
                skipFin = fin
                if (isData && !inMessage) {
                    inMessage = true
                    msgEpoch = nextMsgEpoch
                    msgOpcode = opcode
                    msgCompressed = b0 and 0x40 != 0
                }
                if (skipRemaining == 0L) finishSkip()
                continue
            }
            // Stored path: wait until the whole frame is buffered.
            if (avail < headerLen + payloadLen) {
                compactIfNeeded()
                return
            }
            val payloadStart = start + headerLen
            if (masked) {
                // Unmask in place; the buffer belongs to the tap.
                val maskStart = payloadStart - 4
                for (i in 0 until payloadLen.toInt()) {
                    buf[payloadStart + i] =
                        (buf[payloadStart + i].toInt() xor buf[maskStart + (i and 3)].toInt()).toByte()
                }
            }
            if (isData) {
                if (!inMessage) {
                    inMessage = true
                    msgEpoch = nextMsgEpoch
                    msgOpcode = opcode
                    msgCompressed = b0 and 0x40 != 0
                    msgBuf.reset()
                }
                msgBuf.write(buf, payloadStart, payloadLen.toInt())
                if (fin) endMessage()
            }
            // Control frames are consumed and ignored.
            start += headerLen + payloadLen.toInt()
        }
    }

    /** The payload of an unstored frame has been fully skipped. */
    private fun finishSkip() {
        if (skipIsData && skipFin) endMessage()
        skipIsData = false
        skipFin = false
    }

    private fun endMessage() {
        val assembledSize = msgBuf.size()
        if (msgDropped && msgOpcode == 1) droppedMessages++
        if (!msgDropped && msgOpcode == 1) {
            val rawBytes = msgBuf.toByteArray()
            var deliver: ByteArray? = rawBytes
            if (msgCompressed) {
                inflateTried++
                val (inflated, truncated) = inflateMessage(rawBytes)
                when {
                    truncated -> deliver = null
                    inflated != null -> {
                        inflateOk++
                        deliver = inflated
                    }
                    // Inflate failed: fall back to the raw bytes. The
                    // recorder ignores non-JSON either way.
                }
            }
            if (deliver != null) {
                // The method filter peeks at the raw bytes
                // before any String exists (PER-5).
                val wanted = methodFilter == null ||
                    peekMethod(deliver) in methodFilter
                if (!wanted) filteredMessages++
                // Delivered only while the epoch of the message's
                // FIRST bytes is still current (class KDoc).
                if (wanted && diagEpoch == msgEpoch) {
                    try {
                        val text = String(deliver, Charsets.UTF_8)
                        noteDelivered(text)
                        onTextMessage(text)
                    } catch (_: Throwable) {
                        // A failing listener must not kill the tap.
                    }
                }
            } else {
                // Assembled but undeliverable (inflate truncated at
                // the cap): also a lost message.
                droppedMessages++
            }
        }
        inMessage = false
        msgOpcode = -1
        msgCompressed = false
        msgDropped = false
        msgBuf.reset()
        // PER-8: a stream whose one huge message grew the
        // assembly buffer must not hold that capacity for
        // the rest of the connection; past the threshold,
        // trade the (already copied-out) buffer for a fresh
        // small one.
        if (assembledSize > SHRINK_AFTER_BYTES) {
            msgBuf = ByteArrayOutputStream()
        }
        // Whatever bytes are already buffered belong to the next
        // message and arrived in THIS feed (see nextMsgEpoch).
        nextMsgEpoch = feedStartEpoch
    }

    /**
     * The CDP method name of a JSON message, read from its
     * raw prefix without building a String (PER-5). CDP
     * places "method" within the first bytes of an event or
     * a request; a response ({"id":...,"result":...}) has
     * none in the window and returns null, as does anything
     * that is not CDP JSON.
     */
    private fun peekMethod(bytes: ByteArray): String? {
        val token = METHOD_TOKEN
        val window = minOf(bytes.size, PEEK_WINDOW_BYTES)
        var i = 0
        while (i + token.size < window) {
            if (bytes[i] == token[0]) {
                var j = 1
                while (j < token.size && bytes[i + j] == token[j]) j++
                if (j == token.size) {
                    val nameStart = i + token.size
                    var nameEnd = nameStart
                    while (nameEnd < window &&
                        bytes[nameEnd] != '"'.code.toByte()
                    ) {
                        nameEnd++
                    }
                    if (nameEnd >= window || nameEnd == nameStart) {
                        return null
                    }
                    return String(bytes, nameStart, nameEnd - nameStart, Charsets.US_ASCII)
                }
            }
            i++
        }
        return null
    }

    /**
     * Inflate one message with [inf], driving the inflater through ALL
     * of its input even past the output cap, so a context-takeover
     * stream stays in sync; output beyond the cap is discarded and
     * reported as truncated. Returns (bytes, truncated); bytes is null
     * on a data-format failure or when truncated.
     */
    private fun inflateBounded(
        inf: java.util.zip.Inflater,
        raw: ByteArray
    ): Pair<ByteArray?, Boolean> {
        return try {
            // Restore the empty stored block trailer the sender stripped.
            inf.setInput(raw + TRAILER)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var truncated = false
            var produced = 0L
            while (true) {
                val n = inf.inflate(chunk)
                if (n > 0) produced += n
                if (produced > MAX_INFLATE_PRODUCED_BYTES) {
                    // One message must not burn unbounded CPU on the
                    // relay thread (a near-1000:1 deflate stream could
                    // otherwise expand for gigabytes). The inflater is
                    // now mid-message, so its context is unusable:
                    // stop observing entirely; the relay is unaffected.
                    fail()
                    DebugLog.log("tap", "inflate produced-output budget exceeded; tap failed for this connection")
                    return null to true
                }
                if (n > 0 && !truncated) {
                    val room = MAX_MESSAGE_BYTES - out.size()
                    if (n <= room) {
                        out.write(chunk, 0, n)
                    } else {
                        if (room > 0) out.write(chunk, 0, room)
                        truncated = true
                    }
                }
                if (inf.finished() || inf.needsInput() || n == 0) break
            }
            (if (truncated) null else out.toByteArray()) to truncated
        } catch (_: Throwable) {
            null to false
        }
    }

    /** Decompress one message, keeping the shared inflater's history. */
    private fun inflateMessage(raw: ByteArray): Pair<ByteArray?, Boolean> {
        val shared = sharedInflater
            ?: java.util.zip.Inflater(true).also { sharedInflater = it }
        val (viaShared, truncatedShared) = inflateBounded(shared, raw)
        if (truncatedShared) return null to true
        if (viaShared != null && viaShared.isNotEmpty()) return viaShared to false
        // Context lost: salvage this message with a fresh inflater and
        // adopt it as the new shared one. The old inflater's native
        // state is released here, not left to finalization.
        val fresh = java.util.zip.Inflater(true)
        val (viaFresh, truncatedFresh) = inflateBounded(fresh, raw)
        try { sharedInflater?.end() } catch (_: Throwable) {}
        sharedInflater = fresh
        return (viaFresh?.takeIf { it.isNotEmpty() }) to truncatedFresh
    }

    companion object {
        /** Largest message (raw or inflated) the tap will hold for delivery. */
        const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
        /** Hard bound on total inflated output produced for one message, kept + discarded. */
        const val MAX_INFLATE_PRODUCED_BYTES = 32L * 1024 * 1024
        private const val INITIAL_CAPACITY = 16 * 1024
        private const val COMPACT_THRESHOLD = 64 * 1024
        /** Past this assembled size the assembly buffer is replaced, not kept (PER-8). */
        private const val SHRINK_AFTER_BYTES = 256 * 1024
        /** How far into a message the method peek looks (PER-5). */
        private const val PEEK_WINDOW_BYTES = 512
        private val METHOD_TOKEN = "\"method\":\"".toByteArray(Charsets.US_ASCII)
        private val TRAILER = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte())
    }
}
