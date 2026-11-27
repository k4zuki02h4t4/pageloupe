package tests

import jp.kaleidpixel.pageloupe.DevToolsProfilePolicy
import jp.kaleidpixel.pageloupe.SecretProfilePolicy

/**
 * JVM unit tests for the profile names (SecretProfilePolicy,
 * DevToolsProfilePolicy). The names are the partitions: if the
 * DevTools profile's name ever collided with the secret
 * profile's, the frontend would share the secret partition —
 * the secret wipe would take the DevTools settings with it, and
 * the frontend's own state would be treated as secret data.
 * The bridges behind the names (SecretProfile, DevToolsProfile)
 * need a real WebView implementation and are exercised on
 * device.
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
        "devtools profile name is the pinned literal",
        DevToolsProfilePolicy.PROFILE_NAME == "pageloupe-devtools",
        DevToolsProfilePolicy.PROFILE_NAME
    )
    check(
        "secret profile name is the pinned literal",
        SecretProfilePolicy.SECRET_PROFILE_NAME == "pageloupe-secret",
        SecretProfilePolicy.SECRET_PROFILE_NAME
    )
    check(
        "the two profile names never collide",
        DevToolsProfilePolicy.PROFILE_NAME != SecretProfilePolicy.SECRET_PROFILE_NAME
    )
    check(
        "neither name is the default profile name",
        DevToolsProfilePolicy.PROFILE_NAME != "Default" &&
            SecretProfilePolicy.SECRET_PROFILE_NAME != "Default"
    )

    println("ProfileNamesTest: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
