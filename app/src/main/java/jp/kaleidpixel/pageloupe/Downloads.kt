package jp.kaleidpixel.pageloupe

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Page downloads, handed to the system DownloadManager after
 * the user confirms (MainActivity owns the confirmation dialog):
 * the download runs under the system's own service and UI
 * (progress notification, Downloads app), into the public
 * Downloads folder.
 *
 * The request carries the page's cookies for the URL and the
 * page's origin as Referer, both prepared by the caller under
 * PageFeaturePolicy's rules: the cookie comes from the store
 * matching the tab's profile and is attached for https URLs
 * only, and the Referer is origin-only. Nothing here decides
 * policy; this object only performs the hand-off.
 */
object Downloads {

    /** One confirmed download, everything the hand-off needs. */
    data class Ask(
        /** Normalized by PageFeaturePolicy.normalizeDownloadUrl. */
        val url: String,
        /** Sanitized by PageFeaturePolicy.sanitizeFileName. */
        val fileName: String,
        val mimeType: String?,
        val userAgent: String?,
        /** Origin-only Referer, or null to send none. */
        val refererOrigin: String?,
        val secret: Boolean,
        /**
         * Which frame the navigation that became this download
         * was for (DownloadInitiatorTracker, decided when the
         * download started). The confirmation dialog tells the
         * user when the navigation was for a subframe or the
         * frame could not be confirmed, because those downloads
         * carry no cookie (SEN-285).
         */
        val frame: PageFeaturePolicy.DownloadFrame,
        /**
         * PageFeaturePolicy.shouldAttachCookie's verdict for this
         * download, decided from the two URLs and the frame when
         * the download started. The cookie VALUE is still only
         * read if and when the user approves.
         */
        val cookieAllowed: Boolean,
        /**
         * Cookie header value, or null to send none. Filled in
         * only at approval time (the Ask is built cookie-less);
         * see MainActivity.onDownloadRequested.
         */
        val cookie: String? = null
    )

    /** The hand-off's outcome; the caller phrases it to the
     * user (SEN-21: this object performs, it does not speak). */
    sealed interface EnqueueResult {
        data class Started(val fileName: String) : EnqueueResult
        data object Failed : EnqueueResult
    }

    fun enqueue(ctx: Context, ask: Ask): EnqueueResult {
        try {
            val fileName = uniqueFileName(ask.fileName)
            val request = DownloadManager.Request(Uri.parse(ask.url))
                .setTitle(fileName)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, fileName
                )
            if (!ask.mimeType.isNullOrBlank()) request.setMimeType(ask.mimeType)
            if (!ask.userAgent.isNullOrBlank()) {
                request.addRequestHeader("User-Agent", ask.userAgent)
            }
            if (!ask.cookie.isNullOrBlank()) {
                request.addRequestHeader("Cookie", ask.cookie)
            }
            if (!ask.refererOrigin.isNullOrBlank()) {
                request.addRequestHeader("Referer", ask.refererOrigin)
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            // File names of secret-tab downloads stay out of the
            // debug log, like every other secret-tab datum.
            DebugLog.log(
                "download",
                "started: ${if (ask.secret) "<redacted>" else fileName}, secret=${ask.secret}"
            )
            return EnqueueResult.Started(fileName)
        } catch (e: Exception) {
            DebugLog.log(
                "download",
                "enqueue failed: ${e.javaClass.simpleName}, secret=${ask.secret}"
            )
            return EnqueueResult.Failed
        }
    }

    /**
     * DownloadManager refuses a destination that already exists,
     * so a name already present in the Downloads folder gets a
     * " (n)" suffix before its extension. The search is capped:
     * past 100 collisions a timestamp suffix is used instead of
     * stat-ing the folder forever on the UI thread. (Existence
     * and enqueue are not atomic — a name taken in between still
     * fails the enqueue, which the caller surfaces as the
     * failure toast.)
     */
    private fun uniqueFileName(name: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..100) {
            val candidate = "$base ($n)$ext"
            if (!File(dir, candidate).exists()) return candidate
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }
}
