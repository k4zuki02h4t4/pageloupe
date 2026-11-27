package tests

import jp.kaleidpixel.pageloupe.TranslatePlan
import java.io.File

/**
 * JVM unit tests for the contract between PageTranslator's
 * embedded extraction script and the Kotlin side (SEN-8):
 * the script is a Kotlin raw string, so a "$" inside it
 * would become a string template and silently corrupt the
 * script (or fail the build in confusing ways), and its
 * per-node character cap must equal the plan's per-item
 * cap — the two sides were sized against each other
 * (TranslatePlan.MAX_ITEM_CHARS's doc names the script's
 * MAXNODECHARS). The test reads the source file: the
 * script only exists as source text, so source is the
 * artifact under contract. Run via the runUnitTests
 * Gradle task.
 */

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name $detail")
    }
}

private fun translatorSource(): File {
    val candidates = listOf(
        "app/src/main/java/jp/kaleidpixel/pageloupe/PageTranslator.kt",
        "src/main/java/jp/kaleidpixel/pageloupe/PageTranslator.kt",
        "../app/src/main/java/jp/kaleidpixel/pageloupe/PageTranslator.kt"
    )
    for (c in candidates) {
        val f = File(c)
        if (f.isFile) return f
    }
    error("PageTranslator.kt not found from ${File(".").absolutePath}")
}

fun main() {
    val source = translatorSource().readText()

    // The one raw-string script: from the extractScript
    // opening delimiter to its closing one.
    val openMarker = "extractScript(key: String): String = \"\"\""
    val open = source.indexOf(openMarker)
    check("extract script found", open >= 0)
    val bodyStart = open + openMarker.length
    val close = source.indexOf("\"\"\"", bodyStart)
    check("extract script terminates", close > bodyStart)
    val script = source.substring(bodyStart, close)

    check("script is substantial", script.length > 4000, "${script.length}")
    check(
        "script contains no dollar sign (raw-string template hazard)",
        !script.contains('$')
    )
    check(
        "script carries the state-key placeholder the Kotlin side replaces",
        script.contains("__PLT_STATE_KEY__")
    )

    val capMatch = Regex("""var MAXNODECHARS = (\d+);""").find(script)
    check("script declares MAXNODECHARS", capMatch != null)
    val scriptCap = capMatch?.groupValues?.get(1)?.toInt()
    check(
        "script MAXNODECHARS equals TranslatePlan.MAX_ITEM_CHARS",
        scriptCap == TranslatePlan.MAX_ITEM_CHARS,
        "script=$scriptCap plan=${TranslatePlan.MAX_ITEM_CHARS}"
    )

    println("TranslateScriptContractTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
