package tests

import jp.kaleidpixel.pageloupe.SecretProfilePolicy

/**
 * JVM unit tests for the secret-tab profile decisions
 * (SecretProfilePolicy.kt): which profile a new tab's WebView is
 * assigned, and when the secret profile is wiped. The androidx.webkit
 * calls behind the decisions (SecretProfile.kt) need a real WebView
 * implementation and are exercised on device.
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
    // Profile assignment: only secret tabs move, and only where
    // multi-profile is supported. Everything else stays on the
    // default profile (null).
    check(
        "secret + supported gets the secret profile",
        SecretProfilePolicy.profileNameFor(secret = true, multiProfileSupported = true) ==
            SecretProfilePolicy.SECRET_PROFILE_NAME
    )
    check(
        "secret + unsupported stays on the default profile",
        SecretProfilePolicy.profileNameFor(secret = true, multiProfileSupported = false) == null
    )
    check(
        "normal + supported stays on the default profile",
        SecretProfilePolicy.profileNameFor(secret = false, multiProfileSupported = true) == null
    )
    check(
        "normal + unsupported stays on the default profile",
        SecretProfilePolicy.profileNameFor(secret = false, multiProfileSupported = false) == null
    )
    check(
        "secret profile name is not the default profile name",
        SecretProfilePolicy.SECRET_PROFILE_NAME != "Default" &&
            SecretProfilePolicy.SECRET_PROFILE_NAME.isNotBlank(),
        SecretProfilePolicy.SECRET_PROFILE_NAME
    )

    // Wipe decision after a close: the profile exists only for
    // live secret tabs, and only a secret close can trigger it.
    check(
        "secret close, none remain: wipe",
        SecretProfilePolicy.shouldWipeAfterSecretClose(closedWasSecret = true, remainingSecretTabs = 0)
    )
    check(
        "secret close, one remains: keep",
        !SecretProfilePolicy.shouldWipeAfterSecretClose(closedWasSecret = true, remainingSecretTabs = 1)
    )
    check(
        "secret close, several remain: keep",
        !SecretProfilePolicy.shouldWipeAfterSecretClose(closedWasSecret = true, remainingSecretTabs = 5)
    )
    check(
        "normal close, none remain: keep",
        !SecretProfilePolicy.shouldWipeAfterSecretClose(closedWasSecret = false, remainingSecretTabs = 0)
    )
    check(
        "normal close, some remain: keep",
        !SecretProfilePolicy.shouldWipeAfterSecretClose(closedWasSecret = false, remainingSecretTabs = 3)
    )

    println("SecretProfilePolicyTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
