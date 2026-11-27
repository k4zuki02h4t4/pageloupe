package jp.kaleidpixel.pageloupe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * On-disk persistence for bookmark favicons.
 *
 * One small PNG per bookmarked URL, named by the URL's
 * SHA-256 (the ThumbnailStore arrangement: stable across
 * restarts, and the directory listing does not spell out the
 * URLs). Favicons are public site icons, but the set of files
 * still reveals which sites are bookmarked, so the directory
 * is excluded from cloud backup and device transfer
 * (data_extraction_rules.xml) like the history.
 *
 * Writes are atomic (temp file + rename, the ThumbnailStore
 * arrangement), so a reader never sees a half-written icon
 * and a killed write leaves at worst a temp file for [prune]
 * to collect.
 *
 * All functions do blocking I/O; call them off the UI thread.
 */
object FaviconStore {

    /** Directory (under filesDir) holding the favicon files. */
    const val DIR_NAME = "favicons"

    private const val EXT = ".png"

    /** Stored icons are scaled down to at most this many px
     * on their longer side (display is 22dp). */
    private const val MAX_PX = 64

    /** Decode cap for stored files: far above the written
     *  [MAX_PX] (format or density changes stay possible), far
     *  below anything that could pressure memory. */
    private const val MAX_DECODE_PX = 512

    private const val HEX = "0123456789abcdef"

    /** The file name for [url]'s favicon. Pure. */
    fun fileNameFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2 + EXT.length)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        sb.append(EXT)
        return sb.toString()
    }

    fun fileFor(dir: File, url: String): File = File(dir, fileNameFor(url))

    /**
     * A copy of [bitmap] scaled down to [MAX_PX] on its
     * longer side (never upscaled). Always a fresh bitmap the
     * caller owns: WebView icon bitmaps may be recycled under
     * the caller, and the in-memory cache and the disk file
     * must agree on size, so both are fed from this copy.
     */
    fun scaledCopy(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_PX) return Bitmap.createBitmap(bitmap)
        val ratio = MAX_PX.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * Save [bitmap] as [url]'s favicon, atomically. Throws
     * on failure (the ThumbnailStore arrangement): the caller
     * logs the failure kind and confirms the store's favicon
     * flag only when this returns, so a failed write stays
     * retryable on a later visit. The temp file is removed
     * before the throw.
     */
    fun save(dir: File, url: String, bitmap: Bitmap) {
        dir.mkdirs()
        val target = fileFor(dir, url)
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
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

    /** The stored favicon for [url], or null when none (or
     * the file does not decode — the caller treats both as
     * "no icon" and [prune] collects the file). */
    fun load(dir: File, url: String): Bitmap? {
        val file = fileFor(dir, url)
        if (!file.isFile) return null
        return runCatching {
            // Bounds first (audit hardening): the files are the
            // app's own (written at 64px), but a replaced or
            // corrupt file must not decode into an unbounded
            // bitmap on the UI path.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth !in 1..MAX_DECODE_PX ||
                bounds.outHeight !in 1..MAX_DECODE_PX
            ) {
                return@runCatching null
            }
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    fun delete(dir: File, url: String) {
        runCatching { fileFor(dir, url).delete() }
    }

    /** Remove every stored favicon (the bookmark page's
     * delete-all path). */
    fun clearAll(dir: File) {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Of the [existing] file names in the directory, the ones
     * [keepUrls] no longer cover: icons whose URL is not
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

    /** Delete every stored favicon whose URL is not in
     * [keepUrls] (startup housekeeping for orphaned files). */
    fun prune(dir: File, keepUrls: Collection<String>) {
        val names = dir.list() ?: return
        for (name in staleFileNames(names.asList(), keepUrls)) {
            File(dir, name).delete()
        }
    }
}
