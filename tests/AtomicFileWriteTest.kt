package tests

import jp.kaleidpixel.pageloupe.AtomicFileWrite
import java.io.File
import java.io.IOException

// JVM tests for AtomicFileWrite: the temp-file + rename
// procedure behind the requests JSON export (PER-58). Pure
// java.io, so the failure paths run here even though the
// export's JSON producers (org.json / JsonWriter) cannot.
// Run via the runUnitTests Gradle task.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun freshDir(tag: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "pl-afw-$tag-${System.nanoTime()}")
    check("mkdir $tag", dir.mkdirs())
    return dir
}

fun main() {
    // Success: the content lands under the real name and no
    // temp remains.
    run {
        val dir = freshDir("ok")
        val target = File(dir, "out.json")
        AtomicFileWrite.write(target) { it.write("[1,2]") }
        check("success writes target", target.readText() == "[1,2]")
        check("success leaves no tmp", !File(dir, "out.json.tmp").exists())
        dir.deleteRecursively()
    }

    // Success over an existing target replaces it.
    run {
        val dir = freshDir("replace")
        val target = File(dir, "out.json")
        target.writeText("old")
        AtomicFileWrite.write(target) { it.write("new") }
        check("replace overwrites target", target.readText() == "new")
        check("replace leaves no tmp", !File(dir, "out.json.tmp").exists())
        dir.deleteRecursively()
    }

    // The block throws: the exception propagates, no target is
    // created, and the temp is gone — partial content never
    // appears under the real name.
    run {
        val dir = freshDir("throw")
        val target = File(dir, "out.json")
        var caught: Throwable? = null
        try {
            AtomicFileWrite.write(target) {
                it.write("partial")
                throw IOException("boom")
            }
        } catch (e: Throwable) {
            caught = e
        }
        check("throw propagates", (caught as? IOException)?.message == "boom")
        check("throw leaves no target", !target.exists())
        check("throw leaves no tmp", !File(dir, "out.json.tmp").exists())
        dir.deleteRecursively()
    }

    // The block throws with an existing target in place: the
    // old content survives untouched (the point of writing via
    // a temp at all).
    run {
        val dir = freshDir("keep")
        val target = File(dir, "out.json")
        target.writeText("previous")
        var caught: Throwable? = null
        try {
            AtomicFileWrite.write(target) {
                it.write("partial")
                throw IOException("boom")
            }
        } catch (e: Throwable) {
            caught = e
        }
        check("failure with target propagates", caught is IOException)
        check("existing target survives failure", target.readText() == "previous")
        check("failure with target leaves no tmp", !File(dir, "out.json.tmp").exists())
        dir.deleteRecursively()
    }

    // A stale temp from a killed write does not affect the
    // next write: it is overwritten and renamed away.
    run {
        val dir = freshDir("stale")
        val target = File(dir, "out.json")
        File(dir, "out.json.tmp").writeText("stale")
        AtomicFileWrite.write(target) { it.write("fresh") }
        check("stale tmp overwritten", target.readText() == "fresh")
        check("stale tmp gone after write", !File(dir, "out.json.tmp").exists())
        dir.deleteRecursively()
    }

    // The rename cannot go through (the target name is taken
    // by a directory): IOException, the temp is deleted, and
    // the directory is untouched. Relies on POSIX rename(2)
    // refusing to replace a directory with a file, which holds
    // on the Linux/Android targets this suite runs on.
    run {
        val dir = freshDir("rename")
        val target = File(dir, "out.json")
        check("mkdir target", target.mkdir())
        var caught: Throwable? = null
        try {
            AtomicFileWrite.write(target) { it.write("data") }
        } catch (e: Throwable) {
            caught = e
        }
        check("rename failure throws IOException", caught is IOException)
        check("rename failure leaves no tmp", !File(dir, "out.json.tmp").exists())
        check("rename failure keeps the directory", target.isDirectory)
        dir.deleteRecursively()
    }

    // The stream variant (SEN-16) writes binary content
    // through the same tmp + rename path.
    run {
        val dir = freshDir("stream")
        val target = File(dir, "img.bin")
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        AtomicFileWrite.writeStream(target) { it.write(bytes) }
        check(
            "stream write lands byte-identical",
            target.readBytes().contentEquals(bytes),
        )
        check("stream write leaves no tmp", !File(dir, "img.bin.tmp").exists())
        dir.deleteRecursively()
    }

    // A stream block that throws mid-write leaves the old
    // content in place and no temp behind.
    run {
        val dir = freshDir("streamfail")
        val target = File(dir, "img.bin")
        target.writeBytes(byteArrayOf(1, 2, 3))
        var caught: Throwable? = null
        try {
            AtomicFileWrite.writeStream(target) { out ->
                out.write(byteArrayOf(9, 9))
                throw IOException("compress failed")
            }
        } catch (e: Throwable) {
            caught = e
        }
        check("stream failure throws", caught is IOException)
        check(
            "stream failure keeps the old content",
            target.readBytes().contentEquals(byteArrayOf(1, 2, 3)),
        )
        check("stream failure leaves no tmp", !File(dir, "img.bin.tmp").exists())
        dir.deleteRecursively()
    }

    println("AtomicFileWriteTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
