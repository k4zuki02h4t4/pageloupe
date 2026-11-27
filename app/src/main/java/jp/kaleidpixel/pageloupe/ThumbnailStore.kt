package jp.kaleidpixel.pageloupe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * On-disk persistence for tab-switcher thumbnails (SEN-157).
 *
 * Thumbnails used to live only in memory, so every restored
 * background tab — and every tab whose bitmap was dropped
 * under memory pressure — fell back to the placeholder
 * letter in the switcher. Captured thumbnails are now also
 * written here, one file per page URL, and read back when a
 * switcher card binds without a bitmap.
 *
 * The file name is the SHA-256 of the URL: stable across
 * restarts, free of characters a file system would reject,
 * and it does not spell out the URL in the directory
 * listing. Two tabs on the same URL share one file, which
 * is fine for a top-of-page snapshot.
 *
 * Only normal tabs are ever stored. A secret tab's page
 * image must not reach the disk at all, so callers skip
 * secret tabs before touching this store — neither a save
 * nor a lookup. The whole directory is excluded from cloud
 * backup and device transfer (data_extraction_rules.xml),
 * and [prune] keeps only the files of currently open tabs,
 * so a thumbnail's lifetime follows its tab's.
 *
 * All functions do blocking I/O or CPU work; call them off
 * the UI thread.
 */
object ThumbnailStore {

    /** Directory (under filesDir) holding the thumbnail files. */
    const val DIR_NAME = "thumbnails"

    private const val EXT = ".webp"
    private const val QUALITY = 85

    /** Decode cap for stored files: far above the written
     *  shape (360px wide), far below anything that could
     *  pressure memory. */
    private const val MAX_DECODE_PX = 4096

    /** The file name for [url]'s thumbnail. Pure. */
    fun fileNameFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2 + EXT.length)
        for (b in digest) sb.append("%02x".format(b.toInt() and 0xFF))
        sb.append(EXT)
        return sb.toString()
    }

    /**
     * Of the [existing] file names in the directory, the ones
     * [keepUrls] no longer cover: thumbnails whose URL is not
     * kept, and any temp file (a temp is never a keeper — it
     * is either renamed over its target by [save] or garbage
     * from a killed write). Names that are not this store's
     * are left alone. Pure.
     */
    fun staleFileNames(existing: Collection<String>, keepUrls: Collection<String>): List<String> {
        val keep = keepUrls.mapTo(HashSet()) { fileNameFor(it) }
        return existing.filter { name ->
            when {
                name.endsWith(EXT + ".tmp") -> true
                name.endsWith(EXT) -> name !in keep
                else -> false
            }
        }
    }

    /**
     * Encode [bitmap] as [url]'s thumbnail file, atomically:
     * write "<name>.tmp" next to the target, then rename over
     * it, so a reader never sees a half-written image. Throws
     * on failure; the temp is removed first.
     */
    fun save(dir: File, url: String, bitmap: Bitmap) {
        dir.mkdirs()
        val target = File(dir, fileNameFor(url))
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                val format = Bitmap.CompressFormat.WEBP_LOSSY
                if (!bitmap.compress(format, QUALITY, out)) {
                    throw IOException("compress failed: ${target.name}")
                }
            }
            if (!tmp.renameTo(target)) {
                throw IOException("rename failed: ${target.name}")
            }
        } catch (e: Throwable) {
            if (tmp.exists() && !tmp.delete()) {
                e.addSuppressed(IOException("temp not deleted: ${tmp.name}"))
            }
            throw e
        }
    }

    /**
     * The load outcome for a bounds probe ([outWidth] /
     * [outHeight] from an inJustDecodeBounds decode), or null
     * to go on and decode for real. Pure, so the
     * classification is unit-tested: an unreadable header
     * (non-positive dimensions) is Corrupt like any other
     * undecodable file, while dimensions beyond the written
     * shape are Oversized — deterministic, unlike a decode
     * failure, so the caller records a miss for them instead
     * of retrying on every bind.
     */
    fun boundsOutcome(outWidth: Int, outHeight: Int): ThumbLoad? = when {
        outWidth <= 0 || outHeight <= 0 -> ThumbLoad.Corrupt
        outWidth > MAX_DECODE_PX || outHeight > MAX_DECODE_PX -> ThumbLoad.Oversized
        else -> null
    }

    /**
     * Read [url]'s stored thumbnail. The outcomes are kept
     * apart because the caller treats them differently:
     * Absent and Oversized are stable facts worth remembering
     * (a miss is recorded; the next capture overwrites
     * either), while Corrupt and Failed are both retryable —
     * a decode that returns null is not proof of corruption,
     * since the decoder can also fail transiently under memory
     * pressure.
     */
    fun load(dir: File, url: String): ThumbLoad {
        val f = File(dir, fileNameFor(url))
        if (!f.isFile) return ThumbLoad.Absent
        return try {
            // Bounds first (audit hardening), as in
            // FaviconStore.load: a replaced or corrupt file must
            // not decode into an unbounded bitmap. The verdict
            // for the probed dimensions is boundsOutcome's.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            boundsOutcome(bounds.outWidth, bounds.outHeight)?.let { return it }
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) ThumbLoad.Ok(bmp) else ThumbLoad.Corrupt
        } catch (_: Throwable) {
            ThumbLoad.Failed
        }
    }

    /** Delete every stored thumbnail whose URL is not in [keepUrls]. */
    fun prune(dir: File, keepUrls: Collection<String>) {
        val names = dir.list() ?: return
        for (name in staleFileNames(names.asList(), keepUrls)) {
            File(dir, name).delete()
        }
    }
}

/** Outcome of a [ThumbnailStore.load] call. */
sealed interface ThumbLoad {
    /** A stored thumbnail was decoded. */
    data class Ok(val bitmap: Bitmap) : ThumbLoad

    /** No file is stored for the URL. */
    object Absent : ThumbLoad

    /** A file is stored but does not decode as an image: corrupt, or a transient decode failure. */
    object Corrupt : ThumbLoad

    /** A file is stored but its dimensions are outside what the writer produces: never decoded, and never retried. */
    object Oversized : ThumbLoad

    /** The read itself failed (an exception): retryable, nothing is corrupt. */
    object Failed : ThumbLoad
}
