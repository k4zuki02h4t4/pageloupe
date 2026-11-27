package tests

import jp.kaleidpixel.pageloupe.PopupProbeRegistry

// JVM tests for the popup probe lifetime rules (SEN-286 /
// SEN-317): one pending probe per tab, supersede hands the
// old probe back, retire reports whether the probe was still
// registered (the only state in which a late callback may
// act), and expire hands the pending probe back exactly
// once. Run via the runUnitTests Gradle task.

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
    // Begin on an empty registry supersedes nothing.
    run {
        val r = PopupProbeRegistry<String>()
        check("begin on empty returns null", r.begin("a") == null)
        check("begun probe is pending", r.isPending("a"))
    }

    // A second begin supersedes the first: the old probe is
    // handed back and is no longer pending, so its late
    // callbacks can no longer act.
    run {
        val r = PopupProbeRegistry<String>()
        r.begin("a")
        check("begin supersedes", r.begin("b") == "a")
        check("superseded is not pending", !r.isPending("a"))
        check("successor is pending", r.isPending("b"))
        check("retire of superseded is false", !r.retire("a"))
    }

    // Retire works exactly once for the pending probe.
    run {
        val r = PopupProbeRegistry<String>()
        r.begin("a")
        check("retire of pending is true", r.retire("a"))
        check("second retire is false", !r.retire("a"))
        check("retired is not pending", !r.isPending("a"))
    }

    // Expire hands the pending probe back exactly once.
    run {
        val r = PopupProbeRegistry<String>()
        check("expire on empty is null", r.expire() == null)
        r.begin("a")
        check("expire returns the probe", r.expire() == "a")
        check("second expire is null", r.expire() == null)
        check("retire after expire is false", !r.retire("a"))
    }

    // After an expire, a fresh begin starts clean.
    run {
        val r = PopupProbeRegistry<String>()
        r.begin("a")
        r.expire()
        check("begin after expire supersedes nothing", r.begin("b") == null)
        check("new probe is pending", r.isPending("b"))
    }

    println("PopupProbeRegistryTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
