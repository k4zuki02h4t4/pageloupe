package tests

import jp.kaleidpixel.pageloupe.DevToolsProfilePolicy
import jp.kaleidpixel.pageloupe.ProfileSupport

/**
 * JVM unit tests for the DevTools profile decisions
 * (DevToolsProfilePolicy.kt): how a multi-profile probe answer
 * steers an apply attempt. The point of pinning this here is
 * the UNKNOWN case: a probe that could not tell must count as
 * a failure to surface, never as the accepted "unsupported"
 * degradation. The bridge behind the decisions
 * (DevToolsProfile.kt) needs a real WebView implementation and
 * is exercised on device.
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

fun main() {
    check(
        "supported probe: proceed",
        DevToolsProfilePolicy.probeCourse(ProfileSupport.SupportState.SUPPORTED) ==
            DevToolsProfilePolicy.ProbeCourse.PROCEED
    )
    check(
        "unsupported probe: the accepted degradation",
        DevToolsProfilePolicy.probeCourse(ProfileSupport.SupportState.UNSUPPORTED) ==
            DevToolsProfilePolicy.ProbeCourse.UNSUPPORTED
    )
    check(
        "failed probe: counts as failure, not as unsupported",
        DevToolsProfilePolicy.probeCourse(ProfileSupport.SupportState.UNKNOWN) ==
            DevToolsProfilePolicy.ProbeCourse.FAILED
    )
    check(
        "profile name is not blank",
        DevToolsProfilePolicy.PROFILE_NAME.isNotBlank(),
        DevToolsProfilePolicy.PROFILE_NAME
    )

    println("DevToolsProfilePolicyTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
