package tests

import jp.kaleidpixel.pageloupe.relayChunks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// JVM tests for relayChunks (PER-64): the DevTools proxy's
// relay loop, extracted so the ordering contract is pinned
// here — forward (write + flush) first, observe after, and a
// chunk whose forwarding failed is never observed. Run via
// the runUnitTests Gradle task.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun pattern(size: Int): ByteArray =
    ByteArray(size) { (it % 251).toByte() }

/** OutputStream that records each write/flush as an event. */
private class RecordingOutput(
    private val events: MutableList<String>,
    private val failOnWrite: Boolean = false,
    private val failOnFlush: Boolean = false
) : OutputStream() {
    val bytes = ByteArrayOutputStream()
    override fun write(b: Int) {
        bytes.write(b)
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (failOnWrite) throw IOException("write failed")
        events.add("W$len")
        bytes.write(b, off, len)
    }
    override fun flush() {
        if (failOnFlush) throw IOException("flush failed")
        events.add("L")
    }
}

fun main() {
    // No observer: bytes are copied unchanged.
    run {
        val data = pattern(20_000)
        val out = ByteArrayOutputStream()
        relayChunks(ByteArrayInputStream(data), out)
        check("copies bytes unchanged", out.toByteArray().contentEquals(data))
    }

    // Order: every chunk is written and flushed before it is
    // observed, and the observer sees exactly the input bytes.
    run {
        val data = pattern(70_000) // 32768 + 32768 + 4464 at the PER-7 chunk size
        val events = mutableListOf<String>()
        val out = RecordingOutput(events)
        val observed = ByteArrayOutputStream()
        relayChunks(ByteArrayInputStream(data), out) { b, off, n ->
            events.add("F$n")
            observed.write(b, off, n)
        }
        check(
            "write/flush precede observe per chunk",
            events == listOf(
                "W32768", "L", "F32768",
                "W32768", "L", "F32768",
                "W4464", "L", "F4464"
            )
        )
        check("observed bytes equal input", observed.toByteArray().contentEquals(data))
        check("forwarded bytes equal input", out.bytes.toByteArray().contentEquals(data))
    }

    // A failed write: the chunk is never observed, and the
    // failure ends the loop quietly (no exception escapes).
    run {
        val events = mutableListOf<String>()
        val out = RecordingOutput(events, failOnWrite = true)
        var observedCalls = 0
        relayChunks(ByteArrayInputStream(pattern(100)), out) { _, _, _ -> observedCalls++ }
        check("write failure: nothing observed", observedCalls == 0)
        check("write failure: no events", events.isEmpty())
    }

    // A failed flush: same — the chunk does not count as
    // forwarded, so it is not observed.
    run {
        val events = mutableListOf<String>()
        val out = RecordingOutput(events, failOnFlush = true)
        var observedCalls = 0
        relayChunks(ByteArrayInputStream(pattern(100)), out) { _, _, _ -> observedCalls++ }
        check("flush failure: nothing observed", observedCalls == 0)
        check("flush failure: write happened", events == listOf("W100"))
    }

    // A read failure mid-stream: chunks already forwarded stay
    // forwarded and observed; the loop then ends quietly.
    run {
        val data = pattern(100)
        val events = mutableListOf<String>()
        val out = RecordingOutput(events)
        var observedCalls = 0
        val input = object : InputStream() {
            private var first = true
            override fun read(): Int = throw IOException("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) throw IOException("read failed")
                first = false
                System.arraycopy(data, 0, b, off, data.size)
                return data.size
            }
        }
        relayChunks(input, out) { _, _, _ -> observedCalls++ }
        check("read failure: first chunk observed", observedCalls == 1)
        check("read failure: first chunk forwarded", out.bytes.toByteArray().contentEquals(data))
    }

    // An observer that throws: the chunk was already forwarded
    // (that is the documented contract — the observer must
    // contain its own failures, as WsMessageTap.feed does),
    // and the exception ends the loop quietly.
    run {
        val data = pattern(20_000)
        val events = mutableListOf<String>()
        val out = RecordingOutput(events)
        relayChunks(ByteArrayInputStream(data), out) { _, _, _ ->
            throw RuntimeException("observer failed")
        }
        check(
            "observer failure: first chunk forwarded first",
            events == listOf("W20000", "L") &&
                out.bytes.toByteArray().contentEquals(data)
        )
    }

    // Empty input: nothing forwarded, nothing observed.
    run {
        val events = mutableListOf<String>()
        val out = RecordingOutput(events)
        var observedCalls = 0
        relayChunks(ByteArrayInputStream(ByteArray(0)), out) { _, _, _ -> observedCalls++ }
        check("empty input: no events", events.isEmpty())
        check("empty input: nothing observed", observedCalls == 0)
    }

    if (failures > 0) {
        println("RelayChunks: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RelayChunks: $checks checks, 0 failures")
}
