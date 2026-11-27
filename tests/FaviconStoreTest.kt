package tests

import jp.kaleidpixel.pageloupe.FaviconStore

// JVM tests for FaviconStore's pure parts: the SHA-256 file
// naming and the stale-file decision behind prune (which
// files may be deleted). The Bitmap-touching functions are
// exercised on device. FaviconStore's class loads against
// the android.jar stubs on the runtime classpath; the pure
// functions never call them.
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

fun main() {
    val a = "https://a.example/"
    val b = "https://b.example/"
    val nameA = FaviconStore.fileNameFor(a)
    val nameB = FaviconStore.fileNameFor(b)

    check("file name is 64 hex chars + .png", nameA.length == 68 && nameA.endsWith(".png") &&
        nameA.dropLast(4).all { it in '0'..'9' || it in 'a'..'f' })
    check("file name is stable", FaviconStore.fileNameFor(a) == nameA)
    check("file names differ per URL", nameA != nameB)
    check(
        "file name does not spell out the URL",
        !nameA.contains("example") && !nameA.contains("http")
    )

    val existing = listOf(nameA, nameB, nameA + ".tmp", "notes.txt", "stray.png")
    check(
        "stale: unkept png and any tmp are stale, kept and foreign names are not",
        FaviconStore.staleFileNames(existing, listOf(a)) ==
            listOf(nameB, nameA + ".tmp", "stray.png")
    )
    check(
        "stale: all bookmark icons kept — temps and foreign png files are stale",
        FaviconStore.staleFileNames(existing, listOf(a, b)) ==
            listOf(nameA + ".tmp", "stray.png")
    )
    check(
        "stale: empty directory",
        FaviconStore.staleFileNames(emptyList(), listOf(a)).isEmpty()
    )

    if (failures > 0) {
        println("FaviconStoreTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("FaviconStoreTest: all $checks checks passed")
}
