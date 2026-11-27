package jp.kaleidpixel.pageloupe

/** True for an http(s) link string (external VIEW intents, launch data, saved URLs). */
fun isWebLink(url: String?): Boolean =
    !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))

/**
 * The launch intent's link that a recreated activity should
 * still open, or null when there is nothing to open: the link
 * was already consumed (its digest matches the saved one), or
 * the intent carries no web link at all. Pure, so the restore
 * branch's decision is unit-tested.
 */
fun launchLinkToOpen(launchUrl: String?, storedDigest: String?): String? {
    if (!isWebLink(launchUrl)) return null
    return if (urlDigest(launchUrl!!) != storedDigest) launchUrl else null
}

/**
 * The launch intent's data string a start may act on, or null
 * when this start is a relaunch from Recents (audit
 * hardening): the system redelivers the ORIGINAL launch
 * intent there, link included, and that link was the user's
 * business in an earlier session, not a new instruction —
 * Chrome skips it the same way. Every onCreate branch takes
 * its launch link through here, because the digest guard in
 * [launchLinkToOpen] only covers recreations that carry saved
 * state; a fresh process has nothing to compare against.
 * Pure, so the decision is unit-tested.
 */
fun launchDataForStart(dataString: String?, launchedFromHistory: Boolean): String? =
    if (launchedFromHistory) null else dataString


/**
 * Fixed-length digest of a URL (SHA-256, hex). Used to remember
 * "this launch intent was already consumed" in the saved state
 * without storing the URL itself: the state Bundle has a size
 * budget, and an external app controls the URL's length.
 */
fun urlDigest(url: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
