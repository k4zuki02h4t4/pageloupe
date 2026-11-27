package jp.kaleidpixel.pageloupe

/**
 * The pure decisions behind the secret-tab profile split, kept
 * free of Android and androidx.webkit references so the JVM tests
 * can pin them (the framework calls themselves live in
 * [SecretProfile] and are verified on a real device).
 *
 * Background: Android WebView normally gives the whole app ONE
 * browsing profile, under which secret tabs would share cookies,
 * cache and storage with normal tabs. androidx.webkit's
 * multi-profile API (an approved exception to the
 * no-dependencies rule) lets a WebView run on a named profile
 * with data of its own — but only where the device's WebView
 * implementation supports it, which is a runtime property,
 * hence the [profileNameFor] gate.
 */
object SecretProfilePolicy {

    /** Name of the dedicated profile all secret tabs share. */
    const val SECRET_PROFILE_NAME = "pageloupe-secret"

    /**
     * The profile to assign to a freshly created WebView, or null
     * to keep it on the default profile: only secret tabs move,
     * and only where multi-profile is supported. On an unsupported
     * WebView a secret tab keeps sharing the default profile.
     */
    fun profileNameFor(secret: Boolean, multiProfileSupported: Boolean): String? =
        if (secret && multiProfileSupported) SECRET_PROFILE_NAME else null

    /**
     * After a tab-close operation, the secret profile's data is
     * wiped when what was closed was secret AND no secret tab
     * remains: the profile exists only to serve live secret tabs.
     * Closing normal tabs never touches it.
     */
    fun shouldWipeAfterSecretClose(closedWasSecret: Boolean, remainingSecretTabs: Int): Boolean =
        closedWasSecret && remainingSecretTabs <= 0
}
