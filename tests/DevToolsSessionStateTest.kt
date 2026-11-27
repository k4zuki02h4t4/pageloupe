package tests

import jp.kaleidpixel.pageloupe.DevToolsSessionState

/**
 * JVM unit tests for the DevTools session-state holder
 * (DevToolsSessionState.kt, SEN-67). The contracts pinned here:
 *
 * - The load pair (frontendLoaded / frontendFailedUrl) clears
 *   together, only through resetLoadState(): if a future load
 *   path cleared only one of them, the stats ticker could
 *   overwrite a load error with "recording" (SEN-50).
 * - lastTargetSecret is the opposite contract: resetLoadState()
 *   must NOT lower it, because late console messages from a
 *   closed secret frontend still need redaction (SEC-10). It is
 *   raised by noteSessionOpened and re-derived only by
 *   notePageFinished for a live target's real load — the
 *   about:blank unload on close must not lower it (stage-6
 *   fix3), and a finish for the failed URL must not mark the
 *   frontend loaded.
 *
 * The wiring (open/close call resetLoadState, the callbacks
 * call the note* methods) stays in MainActivity and is covered
 * by on-device checks; what is pinned here is every decision
 * those calls delegate to the holder.
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

private const val FRONTEND = "https://frontend.example/"

fun main() {
    val fresh = DevToolsSessionState()
    check("initial: frontend not loaded", !fresh.frontendLoaded)
    check("initial: no failed URL", fresh.frontendFailedUrl == null)
    check("initial: last target not secret", !fresh.lastTargetSecret)

    val loaded = DevToolsSessionState()
    loaded.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check("a real finish marks the frontend loaded", loaded.frontendLoaded)
    loaded.noteLoadFailed(FRONTEND)
    loaded.resetLoadState()
    check("resetLoadState clears frontendLoaded", !loaded.frontendLoaded)
    check("resetLoadState clears frontendFailedUrl", loaded.frontendFailedUrl == null)

    val sticky = DevToolsSessionState()
    sticky.noteSessionOpened(targetSecret = true)
    sticky.noteLoadFailed(FRONTEND)
    sticky.resetLoadState()
    check(
        "resetLoadState keeps the sticky secret flag raised",
        sticky.lastTargetSecret
    )
    check(
        "resetLoadState still clears the failed URL when the flag is raised",
        sticky.frontendFailedUrl == null
    )

    val opened = DevToolsSessionState()
    opened.noteSessionOpened(targetSecret = false)
    check("opening on a normal target does not raise the flag", !opened.lastTargetSecret)
    opened.noteSessionOpened(targetSecret = true)
    check("opening on a secret target raises the flag", opened.lastTargetSecret)
    opened.noteSessionOpened(targetSecret = false)
    check("opening again never lowers the flag", opened.lastTargetSecret)

    val failed = DevToolsSessionState()
    failed.noteLoadFailed(FRONTEND)
    failed.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "the failed load's own finish does not mark it loaded",
        !failed.frontendLoaded
    )
    failed.notePageFinished(
        "https://frontend.example/other",
        targetPresent = true,
        targetSecret = false
    )
    check("a different URL's finish marks the frontend loaded", failed.frontendLoaded)

    val blanked = DevToolsSessionState()
    blanked.noteSessionOpened(targetSecret = true)
    blanked.notePageFinished("about:blank", targetPresent = false, targetSecret = false)
    check("the about:blank unload does not lower the flag", blanked.lastTargetSecret)
    check("the about:blank unload does not mark loaded", !blanked.frontendLoaded)
    blanked.notePageFinished(FRONTEND, targetPresent = false, targetSecret = false)
    check(
        "a finish with no live target does not lower the flag",
        blanked.lastTargetSecret
    )

    val rederived = DevToolsSessionState()
    rederived.noteSessionOpened(targetSecret = true)
    rederived.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "a live normal target's finish re-derives the flag down",
        !rederived.lastTargetSecret
    )
    rederived.notePageFinished(FRONTEND, targetPresent = true, targetSecret = true)
    check(
        "a live secret target's finish re-derives the flag up",
        rederived.lastTargetSecret
    )

    val retried = DevToolsSessionState()
    retried.noteLoadFailed(FRONTEND)
    retried.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check("before the reset the failed URL stays blocked", !retried.frontendLoaded)
    retried.resetLoadState()
    retried.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "after resetLoadState the same URL's finish marks it loaded (SEN-61)",
        retried.frontendLoaded
    )

    val nullFinish = DevToolsSessionState()
    nullFinish.noteSessionOpened(targetSecret = true)
    nullFinish.notePageFinished(null, targetPresent = true, targetSecret = false)
    check("a null-URL finish does not lower the flag", nullFinish.lastTargetSecret)
    check("a null-URL finish does not mark loaded", !nullFinish.frontendLoaded)

    val failedFinish = DevToolsSessionState()
    failedFinish.noteSessionOpened(targetSecret = true)
    failedFinish.noteLoadFailed(FRONTEND)
    failedFinish.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "the failed load's finish still re-derives the flag for a live target",
        !failedFinish.lastTargetSecret
    )

    val blankWithTarget = DevToolsSessionState()
    blankWithTarget.noteSessionOpened(targetSecret = true)
    blankWithTarget.notePageFinished(
        "about:blank",
        targetPresent = true,
        targetSecret = false
    )
    check(
        "an about:blank finish WITH a live target still keeps the flag (SEC-10)",
        blankWithTarget.lastTargetSecret
    )
    check(
        "an about:blank finish with a live target does not mark loaded",
        !blankWithTarget.frontendLoaded
    )

    val nullFailure = DevToolsSessionState()
    nullFailure.noteLoadFailed(FRONTEND)
    nullFailure.noteLoadFailed(null)
    check(
        "a null failure report keeps the recorded failed URL",
        nullFailure.frontendFailedUrl == FRONTEND
    )

    // HTTP errors (SEC-53): a 4xx/5xx main-frame response is
    // recorded as the failed load, so its finish cannot mark
    // the frontend loaded; 1xx-3xx are not failures.
    val http404 = DevToolsSessionState()
    check(
        "an HTTP 404 counts as a failure to surface",
        http404.noteHttpError(FRONTEND, 404)
    )
    check(
        "an HTTP 404 records the failed URL",
        http404.frontendFailedUrl == FRONTEND
    )
    http404.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "the finish of an HTTP-failed load does not mark loaded",
        !http404.frontendLoaded
    )
    val http500 = DevToolsSessionState()
    check(
        "an HTTP 500 counts as a failure",
        http500.noteHttpError(FRONTEND, 500) && http500.frontendFailedUrl == FRONTEND
    )
    val http399 = DevToolsSessionState()
    check(
        "an HTTP 399 is not a failure",
        !http399.noteHttpError(FRONTEND, 399) && http399.frontendFailedUrl == null
    )
    val httpOk = DevToolsSessionState()
    check(
        "an HTTP 200 is not a failure",
        !httpOk.noteHttpError(FRONTEND, 200) && httpOk.frontendFailedUrl == null
    )
    httpOk.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "a load with only a 200 finishes as loaded",
        httpOk.frontendLoaded
    )
    val httpNullUrl = DevToolsSessionState()
    check(
        "an HTTP error with no URL is surfaced but records nothing",
        httpNullUrl.noteHttpError(null, 503) && httpNullUrl.frontendFailedUrl == null
    )
    // Consequence of the null rule above, pinned deliberately:
    // with nothing recorded, a finish is not recognized as the
    // failed load and marks loaded — the same accepted shape
    // as the network-error path (see noteHttpError's doc;
    // request.url is non-null in practice).
    httpNullUrl.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "after an unrecorded HTTP error, a finish marks loaded (accepted residual)",
        httpNullUrl.frontendLoaded
    )
    val http400 = DevToolsSessionState()
    check(
        "an HTTP 400 is the first failing status",
        http400.noteHttpError(FRONTEND, 400) && http400.frontendFailedUrl == FRONTEND
    )
    val httpReset = DevToolsSessionState()
    httpReset.noteHttpError(FRONTEND, 404)
    httpReset.resetLoadState()
    check(
        "a reset clears an HTTP-recorded failure",
        httpReset.frontendFailedUrl == null && !httpReset.frontendLoaded
    )
    val httpOther = DevToolsSessionState()
    httpOther.noteHttpError(FRONTEND, 404)
    httpOther.notePageFinished(
        "https://example.invalid/other",
        targetPresent = true,
        targetSecret = false
    )
    check(
        "a finish for a different URL after an HTTP error marks loaded",
        httpOther.frontendLoaded
    )
    val httpSecret = DevToolsSessionState()
    httpSecret.noteSessionOpened(targetSecret = true)
    httpSecret.noteHttpError(FRONTEND, 404)
    httpSecret.notePageFinished(FRONTEND, targetPresent = true, targetSecret = false)
    check(
        "an HTTP-failed finish still re-derives the secret flag from the target",
        !httpSecret.lastTargetSecret && !httpSecret.frontendLoaded
    )

    println("DevToolsSessionStateTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
