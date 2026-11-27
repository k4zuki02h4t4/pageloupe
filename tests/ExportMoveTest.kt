package tests

import jp.kaleidpixel.pageloupe.ExportMove
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

// JVM tests for the console-export move loop, with a fake
// destination: the classification rules (a local failure is
// never the destination's loss, access loss stops the move),
// the partial-copy cleanup, and the outcome counts. The SAF
// wiring behind the real Target is covered on device.
// Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private class FakeTarget : ExportMove.Target {
    val created = mutableListOf<String>()
    val copied = mutableMapOf<String, String>()
    val discarded = mutableListOf<Any>()
    var createError: (String) -> Throwable? = { null }
    var copyError: (String) -> Throwable? = { null }
    var discardResult = true

    override fun create(name: String, mimeType: String): Any {
        createError(name)?.let { throw it }
        created.add(name)
        return name
    }

    override fun copy(handle: Any, input: InputStream) {
        val name = handle as String
        copyError(name)?.let { throw it }
        copied[name] = input.readBytes().toString(Charsets.UTF_8)
    }

    override fun discard(handle: Any): Boolean {
        discarded.add(handle)
        return discardResult
    }
}

private fun recording(dir: File, name: String, text: String = "x"): File {
    val f = File(dir, name)
    f.writeText(text)
    return f
}

fun main() {
    val dir = java.nio.file.Files.createTempDirectory("export-move-test").toFile()
    try {
        // Everything moves: contents arrive, originals go.
        run {
            val a = recording(dir, "devtools-console-a.json", "A")
            val b = recording(dir, "devtools-console-a.log", "B")
            val target = FakeTarget()
            val out = ExportMove.run(listOf(a.name, b.name), dir, { target })
            check("all moved count", out.moved == 2 && out.failed == 0 && out.skipped == 0)
            check("all moved names", out.movedNames == listOf(a.name, b.name))
            check("contents copied", target.copied == mapOf(a.name to "A", b.name to "B"))
            check("originals deleted", !a.exists() && !b.exists())
            check("no invalid flag", !out.folderInvalid && out.discardFailures == 0)
            check("clean finish is not aborted", !out.aborted)
        }

        // Missing and non-recording names are skipped, not failed.
        run {
            val keep = recording(dir, "devtools-console-b.json")
            File(dir, "notes.txt").writeText("n")
            val target = FakeTarget()
            val out = ExportMove.run(
                listOf("devtools-console-gone.json", "notes.txt", keep.name), dir, { target }
            )
            check("skips counted", out.skipped == 2 && out.moved == 1 && out.failed == 0)
        }

        // A local open failure is a plain failure: the
        // destination is never touched for that file and the
        // move does not turn folder-invalid.
        run {
            val bad = recording(dir, "devtools-console-c.json")
            val good = recording(dir, "devtools-console-d.json")
            val target = FakeTarget()
            val out = ExportMove.run(
                listOf(bad.name, good.name), dir, { target },
                openSource = { f ->
                    if (f.name == bad.name) throw FileNotFoundException("gone")
                    f.inputStream()
                }
            )
            check(
                "source failure is plain",
                out.failed == 1 && out.moved == 1 && !out.folderInvalid
            )
            check("source failure created nothing", target.created == listOf(good.name))
            check("source failure keeps original", bad.exists())
        }

        // Access loss at the destination stops the move: what
        // already moved stays moved, the rest is not attempted.
        run {
            val f1 = recording(dir, "devtools-console-e.json")
            val f2 = recording(dir, "devtools-console-f.json")
            val f3 = recording(dir, "devtools-console-g.json")
            val target = FakeTarget()
            target.createError = { name ->
                if (name == f2.name) SecurityException("revoked") else null
            }
            val out = ExportMove.run(listOf(f1.name, f2.name, f3.name), dir, { target })
            check(
                "access loss stops the move",
                out.folderInvalid && out.moved == 1 && out.failed == 1
            )
            check("access loss moved names", out.movedNames == listOf(f1.name))
            check("access loss skips the rest", target.created == listOf(f1.name) && f3.exists())
            check("access loss break is not aborted", !out.aborted)
        }

        // A transient destination failure does not stop the
        // move, and its partial copy is discarded.
        run {
            val f1 = recording(dir, "devtools-console-h.json")
            val f2 = recording(dir, "devtools-console-i.json")
            val target = FakeTarget()
            target.copyError = { name ->
                if (name == f1.name) IOException("provider busy") else null
            }
            val out = ExportMove.run(listOf(f1.name, f2.name), dir, { target })
            check(
                "transient failure continues",
                !out.folderInvalid && out.failed == 1 && out.moved == 1
            )
            check("partial copy discarded", target.discarded == listOf<Any>(f1.name))
            check("transient failure is not aborted", !out.aborted)
            check("failed original remains", f1.exists())
        }

        // A failed discard is counted, not hidden.
        run {
            val f1 = recording(dir, "devtools-console-j.json")
            val target = FakeTarget()
            target.copyError = { IOException("write died") }
            target.discardResult = false
            val out = ExportMove.run(listOf(f1.name), dir, { target })
            check("discard failure counted", out.discardFailures == 1 && out.failed == 1)
        }

        // An Error propagates after the partial copy is
        // discarded: the loop does not swallow it.
        run {
            val f1 = recording(dir, "devtools-console-k.json")
            val target = FakeTarget()
            target.copyError = { AssertionError("boom") }
            var propagated = false
            try {
                ExportMove.run(listOf(f1.name), dir, { target })
            } catch (e: AssertionError) {
                propagated = true
            }
            check("error propagates", propagated)
            check("error still discards", target.discarded == listOf<Any>(f1.name))
        }

        // Progress made before an Error stays visible in the
        // caller-kept Progress: the caller can report what
        // actually moved even though the Outcome is lost with
        // the Error.
        run {
            val f1 = recording(dir, "devtools-console-l.json")
            val f2 = recording(dir, "devtools-console-m.json")
            val target = FakeTarget()
            target.copyError = { name ->
                if (name == f2.name) AssertionError("boom") else null
            }
            val progress = ExportMove.Progress()
            var propagated = false
            try {
                ExportMove.run(listOf(f1.name, f2.name), dir, { target }, progress = progress)
            } catch (e: AssertionError) {
                propagated = true
            }
            check("error after a move propagates", propagated)
            check(
                "progress survives the error",
                progress.moved == 1 && progress.movedNames == listOf(f1.name)
            )
            check("error marks the move aborted", progress.aborted)
        }

        // A discard failure recorded before a later Error is
        // in the Progress too: the leftover warning survives
        // the exceptional ending.
        run {
            val f1 = recording(dir, "devtools-console-o.json")
            val f2 = recording(dir, "devtools-console-p.json")
            val target = FakeTarget()
            target.copyError = { name ->
                if (name == f1.name) IOException("write died") else null
            }
            // The Error strikes at create, before any document
            // exists: no discard is attempted for that file, so
            // the one counted discard failure is the first
            // file's, recorded before the Error.
            target.createError = { name ->
                if (name == f2.name) AssertionError("boom") else null
            }
            target.discardResult = false
            val progress = ExportMove.Progress()
            var propagated = false
            try {
                ExportMove.run(listOf(f1.name, f2.name), dir, { target }, progress = progress)
            } catch (e: AssertionError) {
                propagated = true
            }
            check("discard-then-error propagates", propagated)
            check(
                "discard failure survives the error",
                progress.failed == 1 && progress.discardFailures == 1
            )
        }

        // The access-loss verdict is recorded before the
        // failure callback runs: a callback that throws cannot
        // lose it.
        run {
            val f1 = recording(dir, "devtools-console-q.json")
            val target = FakeTarget()
            target.createError = { SecurityException("revoked") }
            val progress = ExportMove.Progress()
            var propagated = false
            try {
                ExportMove.run(
                    listOf(f1.name), dir, { target }, progress = progress,
                    onFailure = { _, _ -> throw RuntimeException("callback broke") }
                )
            } catch (e: RuntimeException) {
                propagated = true
            }
            check("throwing callback propagates", propagated)
            check(
                "verdict survives the callback",
                progress.folderInvalid && progress.failed == 1
            )
            check("callback throw marks aborted too", progress.aborted)
        }

        // A discard that throws (a Target breaking its
        // contract) counts as a failed discard; the original
        // failure is still the one reported.
        run {
            val f1 = recording(dir, "devtools-console-n.json")
            val target = object : ExportMove.Target {
                override fun create(name: String, mimeType: String): Any = name
                override fun copy(handle: Any, input: InputStream) {
                    throw IOException("write died")
                }
                override fun discard(handle: Any): Boolean {
                    throw RuntimeException("discard broke")
                }
            }
            val out = ExportMove.run(listOf(f1.name), dir, { target })
            check(
                "throwing discard is counted",
                out.failed == 1 && out.discardFailures == 1 && !out.folderInvalid
            )
        }
        // Resolution failure by access loss: the folder is
        // invalid, nothing counts as failed, absent names are
        // skipped, and no document is ever created.
        run {
            val f1 = recording(dir, "devtools-console-r.json")
            var resolveFailures = 0
            val out = ExportMove.run(
                listOf(f1.name, "devtools-console-gone2.json"), dir,
                resolveTarget = { throw SecurityException("revoked") },
                onResolveFailure = { resolveFailures++ }
            )
            check(
                "resolve access loss",
                out.folderInvalid && out.failed == 0 && out.skipped == 1 && out.moved == 0
            )
            check("resolve access loss left the source", f1.exists())
            check("resolve failure hook not called on access loss", resolveFailures == 0)
            check("resolve access loss is not aborted", !out.aborted)
        }

        // Resolution failure, transient: every present file
        // fails at once; the absent ones are skipped.
        run {
            val f1 = recording(dir, "devtools-console-s.json")
            var resolveFailures = 0
            val out = ExportMove.run(
                listOf(f1.name, "devtools-console-gone3.json"), dir,
                resolveTarget = { throw IOException("provider down") },
                onResolveFailure = { resolveFailures++ }
            )
            check(
                "resolve transient failure",
                !out.folderInvalid && out.failed == 1 && out.skipped == 1 && !out.aborted
            )
            check("resolve failure hook called once", resolveFailures == 1)
        }

        // A throwing onResolveFailure must not lose the count
        // recorded before it (the same rule as the loop's own
        // callbacks): the failure stands, the move is aborted.
        run {
            val f1 = recording(dir, "devtools-console-u.json")
            val progress = ExportMove.Progress()
            var propagated = false
            try {
                ExportMove.run(
                    listOf(f1.name), dir,
                    resolveTarget = { throw IOException("provider down") },
                    progress = progress,
                    onResolveFailure = { throw RuntimeException("log blew up") }
                )
            } catch (e: RuntimeException) {
                propagated = true
            }
            check(
                "resolve callback throw keeps the count",
                propagated && progress.failed == 1 && progress.aborted
            )
        }

        // Nothing present: the resolver is never called.
        run {
            var resolved = false
            val out = ExportMove.run(
                listOf("devtools-console-gone4.json"), dir,
                resolveTarget = { resolved = true; FakeTarget() }
            )
            check("nothing present skips resolve", !resolved && out.skipped == 1)
        }

        // A resolver Error propagates and marks the move
        // aborted, like the loop's own Errors.
        run {
            val f1 = recording(dir, "devtools-console-t.json")
            val progress = ExportMove.Progress()
            var propagated = false
            try {
                ExportMove.run(
                    listOf(f1.name), dir,
                    resolveTarget = { throw AssertionError("boom") },
                    progress = progress
                )
            } catch (e: AssertionError) {
                propagated = true
            }
            check("resolve error propagates", propagated && progress.aborted)
        }
    } finally {
        dir.deleteRecursively()
    }

    if (failures > 0) {
        println("ExportMoveTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ExportMoveTest: $checks checks passed")
}
