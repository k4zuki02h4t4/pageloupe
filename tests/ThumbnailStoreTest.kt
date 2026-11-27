package tests

import jp.kaleidpixel.pageloupe.ThumbLoad
import jp.kaleidpixel.pageloupe.ThumbnailStore
import java.io.File

// JVM tests for ThumbnailStore (SEN-157): the file naming
// and the prune selection. Pure functions plus plain
// java.io, so they run here; the bitmap encode/decode
// (save/load) is Android glue and is covered on-device.
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
    val dir = File(System.getProperty("java.io.tmpdir"), "pl-thumb-$tag-${System.nanoTime()}")
    check("mkdir $tag", dir.mkdirs())
    return dir
}

fun main() {
    // ---- fileNameFor ----
    val a = ThumbnailStore.fileNameFor("https://example.com/")
    check("name is 64 hex + .webp", a.length == 64 + 5 && a.endsWith(".webp") &&
        a.dropLast(5).all { it in '0'..'9' || it in 'a'..'f' })
    check("name is deterministic", a == ThumbnailStore.fileNameFor("https://example.com/"))
    check("different URL, different name",
        a != ThumbnailStore.fileNameFor("https://example.com/other"))
    check("scheme matters",
        a != ThumbnailStore.fileNameFor("http://example.com/"))

    // ---- staleFileNames ----
    val keep = listOf("https://example.com/", "https://github.com/")
    val keepA = ThumbnailStore.fileNameFor(keep[0])
    val keepB = ThumbnailStore.fileNameFor(keep[1])
    val dropped = ThumbnailStore.fileNameFor("https://old.example/")
    val existing = listOf(keepA, keepB, dropped, "$keepA.tmp", "notes.txt", "other.png")
    val stale = ThumbnailStore.staleFileNames(existing, keep)
    check("kept files survive", keepA !in stale && keepB !in stale)
    check("unkept thumbnail is stale", dropped in stale)
    check("temp file is always stale", "$keepA.tmp" in stale)
    check("foreign files are left alone", "notes.txt" !in stale && "other.png" !in stale)
    check("empty keep set drops every thumbnail",
        ThumbnailStore.staleFileNames(listOf(keepA, keepB), emptyList()).size == 2)
    check("nothing existing, nothing stale",
        ThumbnailStore.staleFileNames(emptyList(), keep).isEmpty())

    // ---- prune on a real directory ----
    val dir = freshDir("prune")
    for (name in existing) File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
    ThumbnailStore.prune(dir, keep)
    val after = dir.list()?.toList() ?: emptyList()
    check("prune keeps the kept thumbnails", keepA in after && keepB in after)
    check("prune deletes the unkept thumbnail", dropped !in after)
    check("prune deletes temp files", "$keepA.tmp" !in after)
    check("prune leaves foreign files", "notes.txt" in after && "other.png" in after)
    ThumbnailStore.prune(dir, emptyList())
    val afterAll = dir.list()?.toList() ?: emptyList()
    check("prune with empty keep set deletes all thumbnails",
        keepA !in afterAll && keepB !in afterAll && "notes.txt" in afterAll)
    dir.deleteRecursively()

    // Pruning a directory that does not exist is a no-op.
    val missing = File(System.getProperty("java.io.tmpdir"), "pl-thumb-missing-${System.nanoTime()}")
    ThumbnailStore.prune(missing, keep)
    check("prune of a missing directory is a no-op", !missing.exists())

    // ---- load outcomes ----
    // Only the Absent outcome is covered here: the decode
    // outcomes (Ok / Corrupt / Failed) need BitmapFactory,
    // which the JVM does not have; they are device-verified.
    val dir2 = freshDir("loadoutcomes")
    val url = "https://example.com/"
    check("load with no file is Absent", ThumbnailStore.load(dir2, url) == ThumbLoad.Absent)
    check("load in a missing directory is Absent",
        ThumbnailStore.load(missing, url) == ThumbLoad.Absent)
    dir2.deleteRecursively()

    // ---- boundsOutcome (the load bounds classification) ----
    check("unreadable header is Corrupt",
        ThumbnailStore.boundsOutcome(-1, -1) == ThumbLoad.Corrupt)
    check("zero height is Corrupt",
        ThumbnailStore.boundsOutcome(360, 0) == ThumbLoad.Corrupt)
    check("width beyond the cap is Oversized",
        ThumbnailStore.boundsOutcome(4097, 100) == ThumbLoad.Oversized)
    check("height beyond the cap is Oversized",
        ThumbnailStore.boundsOutcome(100, 4097) == ThumbLoad.Oversized)
    check("the written shape decodes",
        ThumbnailStore.boundsOutcome(360, 403) == null)
    check("exactly the cap decodes",
        ThumbnailStore.boundsOutcome(4096, 4096) == null)

    println("ThumbnailStoreTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
