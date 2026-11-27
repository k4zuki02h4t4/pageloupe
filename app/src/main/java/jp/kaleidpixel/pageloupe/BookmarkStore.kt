package jp.kaleidpixel.pageloupe

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One bookmark: a page the user explicitly saved. */
data class BookmarkEntry(
    val id: Long,
    val title: String,
    val url: String,
    val folderId: Long,
    val createdAtMillis: Long,
    val hasFavicon: Boolean,
)

/**
 * One bookmark folder. Folders nest: [parentId] is another
 * folder's id, or [BookmarkStore.ROOT_ID] at the top level.
 */
data class BookmarkFolder(
    val id: Long,
    val name: String,
    val parentId: Long,
    val createdAtMillis: Long,
)

/** How the bookmark page orders folders and bookmarks alike. */
enum class BookmarkSort {
    TITLE,
    NEWEST,
    OLDEST,
    ;

    companion object {
        fun fromName(name: String?): BookmarkSort =
            entries.firstOrNull { it.name == name } ?: TITLE
    }
}

/** Outcome of [BookmarkStore.addBookmark]. The failure
 * cases are kept apart because the UI reports them
 * differently — folding them into one "invalid" made the
 * dialog blame the URL for a full store. */
sealed interface AddBookmarkResult {
    /** A new entry was stored. */
    data class Added(val entry: BookmarkEntry) : AddBookmarkResult

    /** The URL was already bookmarked; nothing changed and
     * the existing entry is returned. */
    data class Duplicate(val entry: BookmarkEntry) : AddBookmarkResult

    /** The URL is not a storable web link. */
    data object InvalidUrl : AddBookmarkResult

    /** The target folder does not exist. */
    data object NoSuchFolder : AddBookmarkResult

    /** The store already holds [BookmarkStore.MAX_BOOKMARKS]. */
    data object LimitReached : AddBookmarkResult
}

/** Outcome of [BookmarkStore.updateBookmark]. */
sealed interface UpdateBookmarkResult {
    /** The edit was applied. [previousUrl] is the entry's
     * URL before the edit (its favicon file is keyed by it). */
    data class Updated(val entry: BookmarkEntry, val previousUrl: String) : UpdateBookmarkResult

    /** Another entry already holds the new URL. */
    data object DuplicateUrl : UpdateBookmarkResult

    /** The URL is not a storable web link. */
    data object InvalidUrl : UpdateBookmarkResult

    /** The entry does not exist (any more). */
    data object NotFound : UpdateBookmarkResult

    /** The target folder does not exist. */
    data object NoSuchFolder : UpdateBookmarkResult
}

/**
 * Normalize a URL typed into the bookmark edit dialog: a
 * bare host ("example.com", "example.com/path",
 * "example.com:8080") gains the https scheme; anything
 * already carrying a scheme is left alone for the store's
 * web-link check to judge (so "mailto:a@b.com" is rejected
 * as-is, never dressed up as an https URL). A scheme here
 * is a letter followed by letters/digits/+/- and a colon,
 * with no dot before the colon — a dot means the colon
 * belongs to a host:port pair instead. Pure.
 */
fun normalizeBookmarkUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t.contains("://")) return t
    val colon = t.indexOf(':')
    if (colon > 0 && t[0].isLetter() &&
        t.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' }
    ) {
        return t
    }
    if (!t.contains(' ') && t.contains('.')) return "https://$t"
    return t
}

/**
 * The bookmarks: user-saved pages organized in a folder tree,
 * backed by a JSON document the activity persists.
 *
 * Rules this class owns:
 *
 * - A URL is bookmarked at most once, wherever it sits: the
 *   menu footer's toggle state is keyed by URL, so a second
 *   copy would have no honest rendering. Adding an existing
 *   URL reports the existing entry; editing an entry onto
 *   another entry's URL is refused. Callers get the outcome
 *   as a result type and never re-check the rules themselves.
 * - Folders nest without a depth limit, but a folder can
 *   never be moved into itself or one of its descendants.
 * - Deleting a folder keeps its contents: child bookmarks
 *   and subfolders move up to the deleted folder's parent.
 * - Changing an entry's URL drops its favicon flag: the
 *   stored icon belongs to the old URL (favicon files are
 *   keyed by URL) and the new one fills in on a later visit.
 * - Titles, URLs and folder names are page- or user-supplied,
 *   so they are capped here the same way the history caps
 *   its fields, and only web links are stored. The store is
 *   capped at [MAX_BOOKMARKS] entries.
 *
 * Pure JVM (org.json aside) so the rules are unit-testable.
 * Not thread-safe: every call site runs on the UI thread, and
 * the activity serializes persistence snapshots itself (the
 * HistoryStore arrangement).
 */
class BookmarkStore {

    private val folders = LinkedHashMap<Long, BookmarkFolder>()
    private val bookmarks = LinkedHashMap<Long, BookmarkEntry>()

    /** URL → entry id, kept in step with [bookmarks] so
     * [findByUrl] (called on menu renders and title/icon
     * callbacks) stays O(1) at any store size. */
    private val byUrl = HashMap<String, Long>()
    private var nextId = 1L

    val folderCount: Int get() = folders.size
    val bookmarkCount: Int get() = bookmarks.size

    fun isEmpty(): Boolean = bookmarks.isEmpty() && folders.isEmpty()

    fun folder(id: Long): BookmarkFolder? = folders[id]

    fun bookmark(id: Long): BookmarkEntry? = bookmarks[id]

    fun findByUrl(url: String): BookmarkEntry? =
        byUrl[url]?.let { bookmarks[it] }

    /** Folders directly inside [parentId], ordered by [sort]. */
    fun foldersIn(parentId: Long, sort: BookmarkSort): List<BookmarkFolder> =
        sortedFolders(folders.values.filter { it.parentId == parentId }, sort)

    /** Bookmarks directly inside [folderId], ordered by [sort]. */
    fun bookmarksIn(folderId: Long, sort: BookmarkSort): List<BookmarkEntry> =
        sortedBookmarks(bookmarks.values.filter { it.folderId == folderId }, sort)

    /**
     * Direct child counts (folders + bookmarks) per parent
     * folder id, in one pass — the bookmark page's folder
     * rows show these, and counting must not sort or rescan
     * per row.
     */
    fun childCounts(): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        for (f in folders.values) counts[f.parentId] = (counts[f.parentId] ?: 0) + 1
        for (b in bookmarks.values) counts[b.folderId] = (counts[b.folderId] ?: 0) + 1
        return counts
    }

    /**
     * The whole folder tree flattened depth-first (title
     * order within each level) as folder-to-depth pairs, for
     * the folder picker. [excludeSubtreeOf] hides that folder
     * and its descendants (a folder cannot be moved into its
     * own subtree).
     */
    fun flattenFolders(excludeSubtreeOf: Long?): List<Pair<BookmarkFolder, Int>> {
        // One pass to index children by parent (each level
        // title-sorted), then a walk over the index: the cost
        // stays linear in the folder count instead of
        // rescanning every folder per level.
        val children = HashMap<Long, List<BookmarkFolder>>()
        val byParent = folders.values.groupBy { it.parentId }
        for ((parentId, kids) in byParent) {
            children[parentId] = sortedFolders(kids, BookmarkSort.TITLE)
        }
        val excluded = if (excludeSubtreeOf != null) {
            // The excluded folder's whole subtree, collected
            // over the same index.
            val set = HashSet<Long>()
            val stack = ArrayDeque<Long>()
            stack.add(excludeSubtreeOf)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                if (!set.add(cur)) continue
                for (kid in children[cur].orEmpty()) stack.add(kid.id)
            }
            set
        } else {
            emptySet()
        }
        val out = ArrayList<Pair<BookmarkFolder, Int>>()
        fun walk(parentId: Long, depth: Int) {
            for (f in children[parentId].orEmpty()) {
                if (f.id in excluded) continue
                out.add(f to depth)
                walk(f.id, depth + 1)
            }
        }
        walk(ROOT_ID, 0)
        return out
    }

    /** Every bookmark, in storage order (no sort applied):
     * for callers that only need to scan the set. */
    fun allBookmarks(): List<BookmarkEntry> = bookmarks.values.toList()

    /**
     * Bookmarks whose title or URL contains [query]
     * (case-insensitive), across every folder, ordered by
     * [sort]. A blank query returns every bookmark.
     */
    fun search(query: String, sort: BookmarkSort): List<BookmarkEntry> {
        val q = query.trim()
        val matched = if (q.isEmpty()) {
            bookmarks.values
        } else {
            bookmarks.values.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.url.contains(q, ignoreCase = true)
            }
        }
        return sortedBookmarks(matched, sort)
    }

    /** The chain of folders from the top level down to and
     * including [folderId] (empty for the root itself). A
     * dangling or cyclic chain stops at the break rather than
     * looping: the store keeps chains acyclic, this is only a
     * guard for hand-damaged state. */
    fun folderPath(folderId: Long): List<BookmarkFolder> {
        val chain = ArrayList<BookmarkFolder>()
        val seen = HashSet<Long>()
        var cur = folderId
        while (cur != ROOT_ID && seen.add(cur)) {
            val f = folders[cur] ?: break
            chain.add(f)
            cur = f.parentId
        }
        return chain.reversed()
    }

    /** Whether [candidateId] is [folderId] itself or one of
     * its descendants. */
    fun isSelfOrDescendant(folderId: Long, candidateId: Long): Boolean {
        val seen = HashSet<Long>()
        var cur = candidateId
        while (cur != ROOT_ID && seen.add(cur)) {
            if (cur == folderId) return true
            cur = folders[cur]?.parentId ?: return false
        }
        return false
    }

    /**
     * Add a bookmark for [url] in [folderId]; the outcome
     * (added / already there / not storable) is the result,
     * so callers never re-derive the store's rules.
     */
    fun addBookmark(
        title: String,
        url: String,
        folderId: Long,
        createdAtMillis: Long,
        hasFavicon: Boolean,
    ): AddBookmarkResult {
        if (!isStorableUrl(url)) return AddBookmarkResult.InvalidUrl
        if (folderId != ROOT_ID && folderId !in folders) return AddBookmarkResult.NoSuchFolder
        findByUrl(url)?.let { return AddBookmarkResult.Duplicate(it) }
        if (bookmarks.size >= MAX_BOOKMARKS) return AddBookmarkResult.LimitReached
        val entry = BookmarkEntry(
            id = nextId++,
            title = title.take(MAX_TITLE_LENGTH),
            url = url,
            folderId = folderId,
            createdAtMillis = createdAtMillis,
            hasFavicon = hasFavicon,
        )
        bookmarks[entry.id] = entry
        byUrl[entry.url] = entry.id
        return AddBookmarkResult.Added(entry)
    }

    /**
     * Apply an edit; the outcome is the result. A URL change
     * also clears the favicon flag (see the class doc): the
     * caller deletes the old URL's icon file, addressed by
     * [UpdateBookmarkResult.Updated.previousUrl].
     */
    fun updateBookmark(
        id: Long,
        title: String,
        url: String,
        folderId: Long,
    ): UpdateBookmarkResult {
        val current = bookmarks[id] ?: return UpdateBookmarkResult.NotFound
        if (!isStorableUrl(url)) return UpdateBookmarkResult.InvalidUrl
        if (folderId != ROOT_ID && folderId !in folders) return UpdateBookmarkResult.NoSuchFolder
        val holder = findByUrl(url)
        if (holder != null && holder.id != id) return UpdateBookmarkResult.DuplicateUrl
        val updated = current.copy(
            title = title.take(MAX_TITLE_LENGTH),
            url = url,
            folderId = folderId,
            hasFavicon = if (url != current.url) false else current.hasFavicon,
        )
        bookmarks[id] = updated
        if (url != current.url) {
            byUrl.remove(current.url)
            byUrl[url] = id
        }
        return UpdateBookmarkResult.Updated(updated, current.url)
    }

    /** Remove one bookmark; returns it (its favicon file is
     * the caller's to clean up), or null when absent. */
    fun removeBookmark(id: Long): BookmarkEntry? {
        val removed = bookmarks.remove(id) ?: return null
        byUrl.remove(removed.url)
        return removed
    }

    fun setHasFavicon(id: Long, value: Boolean) {
        val current = bookmarks[id] ?: return
        if (current.hasFavicon != value) {
            bookmarks[id] = current.copy(hasFavicon = value)
        }
    }

    /** Create a folder inside [parentId]. Null when the name
     * is blank, the parent does not exist, or the store is
     * at its folder cap. */
    fun addFolder(name: String, parentId: Long, createdAtMillis: Long): BookmarkFolder? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (folders.size >= MAX_FOLDERS) return null
        if (parentId != ROOT_ID && parentId !in folders) return null
        val folder = BookmarkFolder(nextId++, trimmed.take(MAX_FOLDER_NAME_LENGTH), parentId, createdAtMillis)
        folders[folder.id] = folder
        return folder
    }

    fun renameFolder(id: Long, name: String): Boolean {
        val current = folders[id] ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        folders[id] = current.copy(name = trimmed.take(MAX_FOLDER_NAME_LENGTH))
        return true
    }

    /**
     * Whether [moveFolder] would accept moving folder [id]
     * under [newParentId]: both must exist (root allowed as
     * the target) and the target must not be the folder
     * itself or one of its descendants. The drag-and-drop UI
     * asks this on hover, before any drop happens.
     */
    fun canMoveFolder(id: Long, newParentId: Long): Boolean {
        if (id !in folders) return false
        if (newParentId != ROOT_ID && newParentId !in folders) return false
        return newParentId != id && !isSelfOrDescendant(id, newParentId)
    }

    /**
     * Move a folder under [newParentId] (root allowed).
     * Refused when [canMoveFolder] is false.
     */
    fun moveFolder(id: Long, newParentId: Long): Boolean {
        val current = folders[id] ?: return false
        if (!canMoveFolder(id, newParentId)) return false
        if (current.parentId == newParentId) return true
        folders[id] = current.copy(parentId = newParentId)
        return true
    }

    /**
     * Whether [moveBookmark] would accept moving bookmark
     * [id] into [newFolderId]: both must exist (root allowed
     * as the target). The drag-and-drop UI asks this on
     * hover, before any drop happens.
     */
    fun canMoveBookmark(id: Long, newFolderId: Long): Boolean {
        if (id !in bookmarks) return false
        return newFolderId == ROOT_ID || newFolderId in folders
    }

    /**
     * Move a bookmark into folder [newFolderId] (root
     * allowed). A pure move: the title and URL are untouched
     * and not re-validated. False when the bookmark or the
     * target folder does not exist; true (a no-op) when the
     * bookmark is already there.
     */
    fun moveBookmark(id: Long, newFolderId: Long): Boolean {
        val current = bookmarks[id] ?: return false
        if (!canMoveBookmark(id, newFolderId)) return false
        if (current.folderId == newFolderId) return true
        bookmarks[id] = current.copy(folderId = newFolderId)
        return true
    }

    /**
     * Delete a folder, moving everything directly inside it
     * (bookmarks and subfolders) up to the folder's parent.
     */
    fun removeFolder(id: Long): Boolean {
        val current = folders[id] ?: return false
        folders.remove(id)
        for (f in folders.values.filter { it.parentId == id }) {
            folders[f.id] = f.copy(parentId = current.parentId)
        }
        for (b in bookmarks.values.filter { it.folderId == id }) {
            bookmarks[b.id] = b.copy(folderId = current.parentId)
        }
        return true
    }

    fun clear() {
        folders.clear()
        bookmarks.clear()
        byUrl.clear()
    }

    /** Serialize this store for persistence. */
    fun toJson(): String {
        val folderArr = JSONArray()
        for (f in folders.values) {
            folderArr.put(
                JSONObject()
                    .put("i", f.id)
                    .put("n", f.name)
                    .put("p", f.parentId)
                    .put("at", f.createdAtMillis),
            )
        }
        val bookmarkArr = JSONArray()
        for (b in bookmarks.values) {
            bookmarkArr.put(
                JSONObject()
                    .put("i", b.id)
                    .put("t", b.title)
                    .put("u", b.url)
                    .put("f", b.folderId)
                    .put("at", b.createdAtMillis)
                    .put("fav", if (b.hasFavicon) 1 else 0),
            )
        }
        return JSONObject()
            .put("v", FORMAT_VERSION)
            .put("next", nextId)
            .put("folders", folderArr)
            .put("bookmarks", bookmarkArr)
            .toString()
    }

    companion object {
        /** The id of the implicit top level (not a real folder). */
        const val ROOT_ID = 0L

        /** The store's cap, on the history's scale. */
        const val MAX_BOOKMARKS = 5000

        /** The folder cap: far above any hand-built tree,
         * it exists so a damaged or hostile file cannot make
         * the parse (or the picker's flattening) unbounded. */
        const val MAX_FOLDERS = 1000

        /** URLs longer than this are not stored (cf. HistoryStore). */
        const val MAX_URL_LENGTH = 2048

        /** Titles are stored truncated to this length. */
        const val MAX_TITLE_LENGTH = 512
        const val MAX_FOLDER_NAME_LENGTH = 128
        private const val FORMAT_VERSION = 1

        private fun isStorableUrl(url: String): Boolean =
            url.length <= MAX_URL_LENGTH && isWebLink(url)

        // Sorting decorates each row with its key once, so a
        // sort costs one lowercase per row, not one per
        // comparison.
        private fun sortedBookmarks(
            list: Collection<BookmarkEntry>,
            sort: BookmarkSort,
        ): List<BookmarkEntry> = when (sort) {
            BookmarkSort.TITLE -> list
                .map { it to it.title.ifEmpty { it.url }.lowercase(Locale.ROOT) }
                .sortedWith(
                    compareBy<Pair<BookmarkEntry, String>> { it.second }
                        .thenByDescending { it.first.createdAtMillis }
                        .thenBy { it.first.id },
                )
                .map { it.first }

            BookmarkSort.NEWEST -> list.sortedWith(
                compareByDescending<BookmarkEntry> { it.createdAtMillis }
                    .thenByDescending { it.id },
            )

            BookmarkSort.OLDEST -> list.sortedWith(
                compareBy<BookmarkEntry> { it.createdAtMillis }.thenBy { it.id },
            )
        }

        private fun sortedFolders(
            list: Collection<BookmarkFolder>,
            sort: BookmarkSort,
        ): List<BookmarkFolder> = when (sort) {
            BookmarkSort.TITLE -> list
                .map { it to it.name.lowercase(Locale.ROOT) }
                .sortedWith(
                    compareBy<Pair<BookmarkFolder, String>> { it.second }
                        .thenByDescending { it.first.createdAtMillis }
                        .thenBy { it.first.id },
                )
                .map { it.first }

            BookmarkSort.NEWEST -> list.sortedWith(
                compareByDescending<BookmarkFolder> { it.createdAtMillis }
                    .thenByDescending { it.id },
            )

            BookmarkSort.OLDEST -> list.sortedWith(
                compareBy<BookmarkFolder> { it.createdAtMillis }.thenBy { it.id },
            )
        }

        /**
         * Parse a document written by [toJson]. Tolerant by
         * design (the HistoryStore arrangement): a corrupt
         * file yields an empty store, malformed rows are
         * skipped, and values are re-validated on the way in.
         *
         * Folders are read in two passes — every folder first,
         * parent links second — because the file's row order
         * is creation order, and a folder created early can
         * legitimately sit under one created later (a move, or
         * a delete reparenting it). A parent that is missing,
         * or whose chain loops back on itself, lands the
         * folder at the root. A bookmark whose folder is
         * missing lands at the root; a duplicate URL keeps the
         * first row.
         */
        fun fromJson(json: String): BookmarkStore =
            parseDocument(json) ?: BookmarkStore()

        /**
         * Parse a document written by [toJson], or null when
         * the document is corrupt AT THE ROOT: not a JSON
         * object, a format version this build does not write,
         * or no bookmarks array. Row-level damage is not
         * corruption (see [fromJson]). The caller evacuates
         * a root-corrupt file rather than letting the next
         * save overwrite it unseen (SEN-2).
         */
        fun parseDocument(json: String): BookmarkStore? {
            // Exception-only on purpose (review round 3,
            // PER-2): runCatching would also catch an
            // OutOfMemoryError and report a huge but valid
            // file as corrupt, evacuating it. An Error
            // propagates to the loader, which treats it as a
            // read failure (writes blocked, file untouched).
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (root.optInt("v", -1) != FORMAT_VERSION) return null
            if (root.optJSONArray("bookmarks") == null) return null
            val store = BookmarkStore()
            var maxId = 0L
            val folderArr = root.optJSONArray("folders")
            if (folderArr != null) {
                // Pass 1: accept the folders themselves.
                // LinkedHashMap: pass 2 attaches in file
                // order, so a cycle breaks at a deterministic
                // folder (the later link in file order).
                val rawParents = LinkedHashMap<Long, Long>()
                for (i in 0 until folderArr.length()) {
                    if (store.folders.size >= MAX_FOLDERS) break
                    val obj = folderArr.optJSONObject(i) ?: continue
                    val id = obj.optLong("i", 0L)
                    val name = obj.optString("n", "").trim()
                    if (id <= ROOT_ID || name.isEmpty() || id in store.folders) continue
                    store.folders[id] = BookmarkFolder(
                        id,
                        name.take(MAX_FOLDER_NAME_LENGTH),
                        ROOT_ID,
                        obj.optLong("at", 0L),
                    )
                    rawParents[id] = obj.optLong("p", ROOT_ID)
                    if (id > maxId) maxId = id
                }
                // Pass 2: attach parents whose chain reaches
                // the root without looping; the rest stay at
                // the root.
                for ((id, rawParent) in rawParents) {
                    if (rawParent == ROOT_ID || rawParent !in store.folders) continue
                    if (!chainReachesRoot(store.folders, id, rawParent)) continue
                    val f = store.folders[id] ?: continue
                    store.folders[id] = f.copy(parentId = rawParent)
                }
            }
            val bookmarkArr = root.optJSONArray("bookmarks")
            if (bookmarkArr != null) {
                for (i in 0 until bookmarkArr.length()) {
                    if (store.bookmarks.size >= MAX_BOOKMARKS) break
                    val obj = bookmarkArr.optJSONObject(i) ?: continue
                    val id = obj.optLong("i", 0L)
                    val url = obj.optString("u", "")
                    if (id <= ROOT_ID || id in store.bookmarks) continue
                    if (!isStorableUrl(url)) continue
                    if (url in store.byUrl) continue
                    var folderId = obj.optLong("f", ROOT_ID)
                    if (folderId != ROOT_ID && folderId !in store.folders) folderId = ROOT_ID
                    store.bookmarks[id] = BookmarkEntry(
                        id,
                        obj.optString("t", "").take(MAX_TITLE_LENGTH),
                        url,
                        folderId,
                        obj.optLong("at", 0L),
                        obj.optInt("fav", 0) == 1,
                    )
                    store.byUrl[url] = id
                    if (id > maxId) maxId = id
                }
            }
            val storedNext = root.optLong("next", 0L)
            store.nextId = maxOf(storedNext, maxId + 1, 1L)
            return store
        }

        /**
         * Whether giving folder [id] the parent [parent]
         * keeps the attached graph acyclic, judging by the
         * parents attached SO FAR plus this candidate link:
         * a not-yet-attached parent currently reads as a
         * root child, so the walk ends there. That is sound
         * because attachments happen one at a time and each
         * is checked against the graph the previous ones
         * left: whichever link would close a cycle is the
         * one being checked when the cycle becomes visible,
         * and it is refused (the folder stays at the root).
         */
        private fun chainReachesRoot(
            folders: Map<Long, BookmarkFolder>,
            id: Long,
            parent: Long,
        ): Boolean {
            val seen = HashSet<Long>()
            seen.add(id)
            var cur = parent
            while (cur != ROOT_ID) {
                if (!seen.add(cur)) return false
                cur = folders[cur]?.parentId ?: return false
            }
            return true
        }
    }
}
