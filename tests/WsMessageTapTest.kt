import jp.kaleidpixel.pageloupe.WsMessageTap
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * JVM unit tests for WsMessageTap (the WebSocket observation tap in
 * DevToolsProxy.kt). Run with tests/run_tests.sh. The fixtures are
 * built here: plain frames in every length encoding, fragmentation,
 * masking, and permessage-deflate with context takeover produced by
 * one shared Deflater, mirroring what Chromium sends.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("ok   $name")
    } else {
        failures++
        println("FAIL $name $detail")
    }
}

private fun frame(
    opcode: Int,
    fin: Boolean,
    rsv1: Boolean,
    payload: ByteArray,
    maskKey: ByteArray? = null
): ByteArray {
    val out = ByteArrayOutputStream()
    var b0 = opcode and 0x0F
    if (fin) b0 = b0 or 0x80
    if (rsv1) b0 = b0 or 0x40
    out.write(b0)
    val maskBit = if (maskKey != null) 0x80 else 0
    when {
        payload.size < 126 -> out.write(maskBit or payload.size)
        payload.size <= 0xFFFF -> {
            out.write(maskBit or 126)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
        }
        else -> {
            out.write(maskBit or 127)
            var i = 7
            while (i >= 0) {
                out.write((payload.size.toLong() ushr (8 * i)).toInt() and 0xFF)
                i--
            }
        }
    }
    if (maskKey != null) {
        out.write(maskKey)
        for (i in payload.indices) {
            out.write(payload[i].toInt() xor maskKey[i and 3].toInt())
        }
    } else {
        out.write(payload)
    }
    return out.toByteArray()
}

private fun textFrame(text: String, fin: Boolean = true, rsv1: Boolean = false): ByteArray =
    frame(1, fin, rsv1, text.toByteArray(Charsets.UTF_8))

private class Collector {
    val messages = mutableListOf<String>()
    val tap = WsMessageTap { messages.add(it) }

    fun feedAll(bytes: ByteArray, chunk: Int = Int.MAX_VALUE) {
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            tap.feed(bytes, i, n)
            i += n
        }
    }
}

/** Compress one message the way permessage-deflate with context takeover sends it. */
private fun deflateMessage(d: Deflater, text: String): ByteArray {
    d.setInput(text.toByteArray(Charsets.UTF_8))
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    while (true) {
        val n = d.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
        if (n > 0) out.write(chunk, 0, n)
        if (n < chunk.size && d.needsInput()) break
    }
    var bytes = out.toByteArray()
    // The sender strips the empty stored-block trailer (00 00 FF FF).
    if (bytes.size >= 4 &&
        bytes[bytes.size - 4] == 0.toByte() && bytes[bytes.size - 3] == 0.toByte() &&
        bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xFF.toByte()
    ) {
        bytes = bytes.copyOf(bytes.size - 4)
    }
    return bytes
}

fun main() {
    val cap = WsMessageTap.MAX_MESSAGE_BYTES

    // 1. A single text frame is delivered whole.
    Collector().apply {
        feedAll(textFrame("hello"))
        check("single frame", messages == listOf("hello"), "$messages")
    }

    // 2. The same frame fed byte by byte.
    Collector().apply {
        feedAll(textFrame("trickle"), chunk = 1)
        check("byte-by-byte feed", messages == listOf("trickle"), "$messages")
    }

    // 3. Two frames in one feed arrive in order.
    Collector().apply {
        feedAll(textFrame("one") + textFrame("two"))
        check("two frames one feed", messages == listOf("one", "two"), "$messages")
    }

    // 4. Fragmented message with an interleaved ping.
    Collector().apply {
        val bytes = frame(1, false, false, "Hel".toByteArray()) +
            frame(9, true, false, ByteArray(0)) +
            frame(0, true, false, "lo".toByteArray())
        feedAll(bytes, chunk = 3)
        check("fragmented + ping", messages == listOf("Hello"), "$messages")
    }

    // 5. A masked frame is unmasked.
    Collector().apply {
        val f = frame(1, true, false, "secret".toByteArray(), byteArrayOf(1, 2, 3, 4))
        feedAll(f)
        check("masked frame", messages == listOf("secret"), "$messages")
    }

    // 6. The 16-bit and 64-bit length encodings.
    Collector().apply {
        val mid = "m".repeat(300)
        val big = "b".repeat(70_000)
        feedAll(textFrame(mid))
        feedAll(textFrame(big), chunk = 8192)
        check("16-bit length", messages.getOrNull(0) == mid)
        check("64-bit length", messages.getOrNull(1) == big)
    }

    // 7. permessage-deflate with context takeover: two messages, one
    // shared Deflater, fed in odd-sized chunks.
    Collector().apply {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val m1 = """{"id":1,"method":"Runtime.enable"}"""
        val m2 = """{"id":2,"method":"Network.enable","params":{"maxTotalBufferSize":100}}"""
        feedAll(frame(1, true, true, deflateMessage(deflater, m1)), chunk = 5)
        feedAll(frame(1, true, true, deflateMessage(deflater, m2)), chunk = 997)
        deflater.end()
        check("deflate context takeover", messages == listOf(m1, m2), "$messages")
        check("inflate counters", tap.inflateTried == 2 && tap.inflateOk == 2,
            "tried=${tap.inflateTried} ok=${tap.inflateOk}")
    }

    // 8. A message past the cap is dropped whole; the next is fine.
    Collector().apply {
        val big = ByteArray(cap + 100) { 'x'.code.toByte() }
        feedAll(frame(1, true, false, big), chunk = 65536)
        check("oversized dropped", messages.isEmpty(), "size=${messages.size}")
        feedAll(textFrame("after"))
        check("resync after oversized", messages == listOf("after"), "$messages")
    }

    // 9. Oversized only in sum (two fragments, each under the cap).
    Collector().apply {
        val f1 = ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() }
        val f2 = ByteArray(4 * 1024 * 1024) { 'b'.code.toByte() }
        feedAll(frame(1, false, false, f1), chunk = 65536)
        feedAll(frame(0, true, false, f2), chunk = 65536)
        check("fragmented oversized dropped", messages.isEmpty(), "size=${messages.size}")
        feedAll(textFrame("next"))
        check("resync after fragmented oversized", messages == listOf("next"), "$messages")
    }

    // 10. Inflated output past the cap is dropped, and the deflate
    // context survives (the inflater is driven through all input), so
    // the next compressed message still decodes.
    Collector().apply {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val huge = "a".repeat(cap + 1024 * 1024)
        feedAll(frame(1, true, true, deflateMessage(deflater, huge)), chunk = 8192)
        check("inflate overflow dropped", messages.isEmpty(), "size=${messages.size}")
        check("inflate overflow counted as dropped", tap.droppedMessages == 1,
            "dropped=${tap.droppedMessages}")
        check("inflate overflow does not fail the tap", !tap.hasFailed)
        val m2 = """{"id":9,"result":{}}"""
        feedAll(frame(1, true, true, deflateMessage(deflater, m2)))
        deflater.end()
        check("context survives overflow", messages == listOf(m2), "$messages")
    }

    // 11. A frame declaring an astronomical length: feed returns
    // normally, nothing is delivered, nothing is thrown.
    Collector().apply {
        val header = byteArrayOf(0x81.toByte(), 127,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        feedAll(header + ByteArray(1000) { 0x41 })
        check("astronomical length tolerated", messages.isEmpty())
        tap.close()
        check("close after garbage ok", true)
    }

    // 12. A length with the top bit set is invalid: the tap stops
    // itself instead of throwing or looping.
    Collector().apply {
        val header = byteArrayOf(0x81.toByte(), 127,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        feedAll(header)
        feedAll(textFrame("unreachable"))
        check("invalid length stops delivery", messages.isEmpty(), "$messages")
        check("invalid length marks tap failed", tap.hasFailed)
    }

    // 13. An empty text message is delivered as an empty string.
    Collector().apply {
        feedAll(textFrame(""))
        check("empty message", messages == listOf(""), "$messages")
    }

    // 14. After close(), nothing is delivered.
    Collector().apply {
        tap.close()
        feedAll(textFrame("late"))
        check("closed tap is silent", messages.isEmpty(), "$messages")
        // A normal close is not a failure: the stats line and
        // export notes must not claim "tap stopped".
        check("close does not mark tap failed", !tap.hasFailed)
    }

    // 15. A 1 MiB message in 8 KiB chunks (the relay's read size):
    // delivered intact, exactly once.
    Collector().apply {
        val big = "0123456789abcdef".repeat(64 * 1024)
        feedAll(textFrame(big), chunk = 8192)
        check("1MiB in 8KiB chunks", messages == listOf(big),
            "count=${messages.size}")
    }

    // 16. A masked oversized frame is dropped whole; the next is fine.
    Collector().apply {
        val big = ByteArray(cap + 64) { 'y'.code.toByte() }
        val f = frame(1, true, false, big, byteArrayOf(9, 8, 7, 6))
        feedAll(f, chunk = 65536)
        check("masked oversized dropped", messages.isEmpty(), "size=${messages.size}")
        feedAll(textFrame("masked-after"))
        check("resync after masked oversized", messages == listOf("masked-after"), "$messages")
    }

    // 17. A control frame interleaved inside a fragmented oversized
    // message does not break the drop/resync.
    Collector().apply {
        val f1 = ByteArray(5 * 1024 * 1024) { 'c'.code.toByte() }
        val f2 = ByteArray(4 * 1024 * 1024) { 'd'.code.toByte() }
        feedAll(frame(1, false, false, f1), chunk = 65536)
        feedAll(frame(9, true, false, "ping".toByteArray()))
        feedAll(frame(0, true, false, f2), chunk = 65536)
        check("fragmented oversized with ping dropped", messages.isEmpty(), "size=${messages.size}")
        feedAll(textFrame("ping-after"))
        check("resync after ping-interleaved drop", messages == listOf("ping-after"), "$messages")
    }

    // 18. A message whose inflated output passes the produced-output
    // budget (32MiB) stops the tap entirely: the relay is safe, but
    // observation stops instead of burning unbounded CPU, and the
    // context is not silently reused afterwards.
    Collector().apply {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val huge = "a".repeat(40 * 1024 * 1024)
        feedAll(frame(1, true, true, deflateMessage(deflater, huge)), chunk = 8192)
        check("inflate budget drop", messages.isEmpty(), "size=${messages.size}")
        feedAll(textFrame("after-budget"))
        deflater.end()
        check("tap stopped after inflate budget", messages.isEmpty(), "$messages")
        check("inflate budget marks tap failed", tap.hasFailed)
    }

    // 19. Dropped messages are counted and the tap reports its
    // failure state: an oversized message bumps droppedMessages
    // by exactly one and the tap keeps working.
    Collector().apply {
        val big = ByteArray(cap + 64) { 'z'.code.toByte() }
        feedAll(frame(1, true, false, big), chunk = 65536)
        check("oversized counted as dropped", tap.droppedMessages == 1,
            "dropped=${tap.droppedMessages}")
        check("a drop does not fail the tap", !tap.hasFailed)
        feedAll(textFrame("still-alive"))
        check("delivered after counted drop", messages == listOf("still-alive"), "$messages")
        check("delivered message not counted", tap.droppedMessages == 1,
            "dropped=${tap.droppedMessages}")
    }

    // 20. clearDiagnostics forgets the diagnostic CONTENT only:
    // head bytes and samples are emptied, the
    // loss counters and delivered count survive, and post-clear
    // traffic is sampled again — but the first-bytes capture is
    // sealed by the clear and never refills (SEC-57).
    Collector().apply {
        feedAll(textFrame("pre-clear-one"))
        val big = ByteArray(cap + 64) { 'z'.code.toByte() }
        feedAll(frame(1, true, false, big), chunk = 65536)
        check("pre-clear diagnostics present",
            tap.headHex().isNotEmpty() && tap.samplesSnapshot().isNotEmpty())
        check("pre-clear counters", tap.droppedMessages == 1 && tap.deliveredCount == 1,
            "dropped=${tap.droppedMessages} delivered=${tap.deliveredCount}")
        check("head not sealed before the clear", !tap.isHeadSealed())
        tap.clearDiagnostics()
        check("clear empties head bytes", tap.headHex().isEmpty(), tap.headHex())
        check("clear seals the head capture", tap.isHeadSealed())
        check("clear empties samples", tap.samplesSnapshot().isEmpty(),
            "${tap.samplesSnapshot()}")
        check("clear keeps counters", tap.droppedMessages == 1 && tap.deliveredCount == 1,
            "dropped=${tap.droppedMessages} delivered=${tap.deliveredCount}")
        check("clear does not fail the tap", !tap.hasFailed)
        feedAll(textFrame("post-clear"))
        check("post-clear sample captured",
            tap.samplesSnapshot().any { it.contains("post-clear") },
            "${tap.samplesSnapshot()}")
        check("post-clear head stays empty (sealed by the clear)",
            tap.headHex().isEmpty(), tap.headHex())
    }

    // 21. A clear in the middle of one feed retires the rest of
    // that feed: messages received before the
    // clear but not yet delivered reach neither the listener nor
    // the samples. The next feed records normally again.
    run {
        val delivered = mutableListOf<String>()
        lateinit var tap: WsMessageTap
        tap = WsMessageTap { msg ->
            delivered.add(msg)
            if (delivered.size == 1) tap.clearDiagnostics()
        }
        val two = textFrame("first-msg") + textFrame("second-msg")
        tap.feed(two, 0, two.size)
        check("mid-feed clear keeps only the first delivery",
            delivered == listOf("first-msg"), "$delivered")
        check("mid-feed clear leaves no samples",
            tap.samplesSnapshot().isEmpty(), "${tap.samplesSnapshot()}")
        val third = textFrame("third-msg")
        tap.feed(third, 0, third.size)
        check("feed after clear delivers again",
            delivered == listOf("first-msg", "third-msg"), "$delivered")
        check("feed after clear samples again",
            tap.samplesSnapshot().any { it.contains("third-msg") },
            "${tap.samplesSnapshot()}")
    }

    // 22. The epoch is taken at message START: a fragmented
    // message whose first fragment arrived
    // before a clear and whose final fragment arrives after it is
    // retired whole, while a message started after the clear —
    // even fragmented across feeds — is delivered.
    Collector().apply {
        feedAll(frame(1, false, false, "frag-before-".toByteArray(Charsets.UTF_8)))
        tap.clearDiagnostics()
        feedAll(frame(0, true, false, "clear".toByteArray(Charsets.UTF_8)))
        check("cross-feed pre-clear message retired", messages.isEmpty(), "$messages")
        check("cross-feed retired message leaves no sample",
            tap.samplesSnapshot().isEmpty(), "${tap.samplesSnapshot()}")
        feedAll(frame(1, false, false, "frag-after-".toByteArray(Charsets.UTF_8)))
        feedAll(frame(0, true, false, "clear".toByteArray(Charsets.UTF_8)))
        check("cross-feed post-clear message delivered",
            messages == listOf("frag-after-clear"), "$messages")
    }

    // 23. A single UNfragmented frame split across feeds (the
    // normal CDP shape for large messages): the epoch is
    // latched by the first bytes, not by the feed
    // that completes the frame. First half before a clear, rest
    // after it: retired. The same split without a clear: delivered.
    Collector().apply {
        val big = textFrame("x".repeat(20000))
        val half = big.size / 2
        tap.feed(big, 0, half)
        tap.clearDiagnostics()
        tap.feed(big, half, big.size - half)
        check("split single frame across clear retired", messages.isEmpty(), "$messages")
        check("split retired frame leaves no sample",
            tap.samplesSnapshot().isEmpty(), "${tap.samplesSnapshot()}")
        // The first-bytes capture is sealed by the clear
        // (SEC-57): the post-clear half — the retired message's
        // CONTINUATION — must not refill it. (Before SEC-57 it
        // refilled with 'x' (0x78) bytes.)
        check("split frame continuation not recaptured in head bytes",
            tap.headHex().isEmpty(), tap.headHex())
        val big2 = textFrame("y".repeat(20000))
        tap.feed(big2, 0, half)
        tap.feed(big2, half, big2.size - half)
        check("split single frame without clear delivered",
            messages == listOf("y".repeat(20000)), "size=${messages.size}")
    }

    // 24. A clear landing DURING a feed (here from the onBytes
    // hook, on the second feed: it runs after that feed's epoch
    // was read but before its bytes are parsed) retires the
    // message that feed carries: its first bytes belong to the
    // pre-clear epoch. The capture is emptied and sealed by the
    // clear, so the same feed — a fragmented frame (first byte
    // 0x01, unlike the 0x81 of the pre-clear frame) — must not
    // refill it (SEC-57).
    Collector().apply {
        var feeds = 0
        tap.onBytes = {
            feeds++
            if (feeds == 2) tap.clearDiagnostics()
        }
        feedAll(textFrame("pre-clear"))
        check("pre-clear message delivered",
            messages == listOf("pre-clear"), "$messages")
        check("head filled before the clear",
            tap.headHex().startsWith("81"), tap.headHex())
        feedAll(frame(1, false, false, "mid-feed-".toByteArray(Charsets.UTF_8)))
        feedAll(frame(0, true, false, "clear".toByteArray(Charsets.UTF_8)))
        check("message spanning the mid-feed clear retired",
            messages == listOf("pre-clear"), "$messages")
        check("clear during feed keeps samples empty",
            tap.samplesSnapshot().isEmpty(), "${tap.samplesSnapshot()}")
        check("head not recaptured from the clearing feed",
            tap.headHex().isEmpty(), tap.headHex())
        feedAll(textFrame("after-clear"))
        check("message after mid-feed clear delivered",
            messages == listOf("pre-clear", "after-clear"), "$messages")
    }

    println("---")
    println("$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
