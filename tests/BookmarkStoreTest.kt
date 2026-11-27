package tests

import jp.kaleidpixel.pageloupe.AddBookmarkResult
import jp.kaleidpixel.pageloupe.BookmarkEntry
import jp.kaleidpixel.pageloupe.BookmarkSort
import jp.kaleidpixel.pageloupe.BookmarkStore
import jp.kaleidpixel.pageloupe.UpdateBookmarkResult
import jp.kaleidpixel.pageloupe.normalizeBookmarkUrl

// JVM tests for the bookmark store's rules: URL uniqueness
// (as result types), the folder tree (nesting, cycle-safe
// moves, reparenting deletes), ordering, search, counts and
// flattening, URL normalization, and the tolerant JSON round
// trip (including parents that appear after their children
// in the file, and cycles). Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun added(r: AddBookmarkResult): BookmarkEntry? =
    (r as? AddBookmarkResult.Added)?.entry

fun main() {
    val store = BookmarkStore()
    val root = BookmarkStore.ROOT_ID

    // Adding and URL uniqueness, reported as results.
    val a = added(store.addBookmark("Alpha", "https://a.example/", root, 100L, false))
    val b = added(store.addBookmark("Beta", "https://b.example/", root, 200L, true))
    check("adds succeed", a != null && b != null)
    val dup = store.addBookmark("Alpha again", "https://a.example/", root, 300L, false)
    check(
        "duplicate URL reports the existing entry",
        dup is AddBookmarkResult.Duplicate && dup.entry.id == a?.id && store.bookmarkCount == 2
    )
    check(
        "non-web URL invalid",
        store.addBookmark("x", "javascript:alert(1)", root, 1L, false) is AddBookmarkResult.InvalidUrl
    )
    check(
        "missing folder invalid",
        store.addBookmark("x", "https://x.example/", 999L, 1L, false) is AddBookmarkResult.NoSuchFolder
    )
    check("findByUrl", store.findByUrl("https://b.example/")?.id == b?.id)

    // Folders and nesting.
    val work = store.addFolder("Work", root, 100L)
    val dev = store.addFolder("Dev", work!!.id, 110L)
    val android = store.addFolder("Android", dev!!.id, 120L)
    check("nested folders created", work != null && dev != null && android != null)
    check("blank folder name rejected", store.addFolder("  ", root, 1L) == null)
    check(
        "folder path is root-to-leaf",
        store.folderPath(android!!.id).map { it.name } == listOf("Work", "Dev", "Android")
    )
    val inDev = added(store.addBookmark("InDev", "https://dev.example/", dev.id, 400L, false))
    check("bookmark in nested folder", inDev?.folderId == dev.id)

    // Counts and flattening.
    val counts = store.childCounts()
    check(
        "child counts",
        counts[root] == 3 && counts[work.id] == 1 && counts[dev.id] == 2 &&
            counts[android.id] == null
    )
    check(
        "flatten is depth-first with depths",
        store.flattenFolders(null).map { it.first.name to it.second } ==
            listOf("Work" to 0, "Dev" to 1, "Android" to 2)
    )
    check(
        "flatten excludes a subtree",
        store.flattenFolders(work.id).isEmpty() &&
            store.flattenFolders(dev.id).map { it.first.name } == listOf("Work")
    )

    // Moves: cycle guards.
    check("move into own descendant refused", !store.moveFolder(work.id, android.id))
    check("move into itself refused", !store.moveFolder(work.id, work.id))
    // canMoveFolder mirrors moveFolder's guard (the drag
    // hover asks before any drop happens).
    check("canMove into descendant refused", !store.canMoveFolder(work.id, android.id))
    check("canMove into itself refused", !store.canMoveFolder(work.id, work.id))
    check("canMove to root ok", store.canMoveFolder(work.id, root))
    check("canMove missing folder refused", !store.canMoveFolder(999999L, root))
    check("canMove to missing target refused", !store.canMoveFolder(work.id, 999999L))
    check("move to root ok", store.moveFolder(dev.id, root))
    check(
        "path follows the move",
        store.folderPath(android.id).map { it.name } == listOf("Dev", "Android")
    )
    check("move back ok", store.moveFolder(dev.id, work.id))

    // Deleting a folder reparents its contents to its parent.
    check("removeFolder ok", store.removeFolder(dev.id))
    check(
        "subfolder moved up",
        store.folder(android.id)?.parentId == work.id
    )
    check(
        "bookmark moved up",
        store.bookmark(inDev!!.id)?.folderId == work.id
    )

    // Deleting a root-level folder reparents to the root.
    val s4 = BookmarkStore()
    val top = s4.addFolder("Top", root, 1L)!!
    val inner = added(s4.addBookmark("i", "https://i.example/", top.id, 1L, false))
    check("remove root-level folder ok", s4.removeFolder(top.id))
    check("contents land at root", s4.bookmark(inner!!.id)?.folderId == root)

    // Bookmark moves (drag-and-drop uses these): a pure
    // folder change that leaves title and URL alone.
    val s5 = BookmarkStore()
    val f5 = s5.addFolder("F5", root, 1L)!!
    val bm5 = added(s5.addBookmark("Keep", "https://keep.example/", root, 1L, true))!!
    check("moveBookmark into folder ok", s5.moveBookmark(bm5.id, f5.id))
    check(
        "moveBookmark changed only the folder",
        s5.bookmark(bm5.id)?.folderId == f5.id &&
            s5.bookmark(bm5.id)?.title == "Keep" &&
            s5.bookmark(bm5.id)?.url == "https://keep.example/" &&
            s5.bookmark(bm5.id)?.hasFavicon == true,
    )
    check("moveBookmark same folder is a no-op success", s5.moveBookmark(bm5.id, f5.id))
    check("moveBookmark back to root ok", s5.moveBookmark(bm5.id, root))
    check("moveBookmark missing bookmark refused", !s5.moveBookmark(999999L, f5.id))
    check("moveBookmark missing folder refused", !s5.moveBookmark(bm5.id, 999999L))
    // canMoveBookmark mirrors moveBookmark's guard (the drag
    // hover asks before any drop happens).
    check("canMoveBookmark into folder ok", s5.canMoveBookmark(bm5.id, f5.id))
    check("canMoveBookmark to root ok", s5.canMoveBookmark(bm5.id, root))
    check("canMoveBookmark missing bookmark refused", !s5.canMoveBookmark(999999L, f5.id))
    check("canMoveBookmark missing folder refused", !s5.canMoveBookmark(bm5.id, 999999L))
    check(
        "findByUrl still resolves after moves",
        s5.findByUrl("https://keep.example/")?.id == bm5.id,
    )

    // Edits: URL collisions are refused, and a URL change
    // drops the favicon flag (the icon belongs to the old URL).
    check(
        "edit onto another URL refused as duplicate",
        store.updateBookmark(a!!.id, "Alpha", "https://b.example/", root) is
            UpdateBookmarkResult.DuplicateUrl
    )
    val favEntry = added(store.addBookmark("Fav", "https://fav.example/", root, 500L, true))
    val upd = store.updateBookmark(favEntry!!.id, "Fav", "https://fav2.example/", root)
    check(
        "URL change drops the favicon flag and reports the old URL",
        upd is UpdateBookmarkResult.Updated && upd.previousUrl == "https://fav.example/" &&
            upd.entry.hasFavicon == false &&
            store.findByUrl("https://fav.example/") == null &&
            store.findByUrl("https://fav2.example/")?.id == favEntry.id
    )
    check(
        "plain edit ok",
        store.updateBookmark(a.id, "Alpha 2", "https://a2.example/", work.id) is
            UpdateBookmarkResult.Updated
    )
    check(
        "edited values stick",
        store.findByUrl("https://a2.example/")?.title == "Alpha 2" &&
            store.findByUrl("https://a.example/") == null
    )
    check(
        "edit of a missing entry is NotFound",
        store.updateBookmark(424242L, "t", "https://t.example/", root) is
            UpdateBookmarkResult.NotFound
    )
    check(
        "edit with a bad URL is InvalidUrl",
        store.updateBookmark(a.id, "t", "javascript:x", root) is
            UpdateBookmarkResult.InvalidUrl
    )

    // Ordering: title is the default, case-insensitive, with
    // an empty title falling back to the URL.
    val s2 = BookmarkStore()
    added(s2.addBookmark("banana", "https://1.example/", root, 100L, false))
    added(s2.addBookmark("Apple", "https://2.example/", root, 300L, false))
    added(s2.addBookmark("", "https://3.example/", root, 200L, false))
    check(
        "title order",
        s2.bookmarksIn(root, BookmarkSort.TITLE).map { it.url } ==
            listOf("https://2.example/", "https://1.example/", "https://3.example/")
    )
    check(
        "newest order",
        s2.bookmarksIn(root, BookmarkSort.NEWEST).map { it.url } ==
            listOf("https://2.example/", "https://3.example/", "https://1.example/")
    )
    check(
        "oldest order",
        s2.bookmarksIn(root, BookmarkSort.OLDEST).map { it.url } ==
            listOf("https://1.example/", "https://3.example/", "https://2.example/")
    )

    // Search spans folders and matches title or URL.
    check(
        "search matches across folders",
        store.search("dev.example", BookmarkSort.TITLE).map { it.id } == listOf(inDev.id)
    )
    check(
        "search is case-insensitive",
        store.search("ALPHA", BookmarkSort.TITLE).size == 1
    )
    check(
        "blank search returns all",
        store.search(" ", BookmarkSort.TITLE).size == store.bookmarkCount
    )

    // Typed-URL normalization for the edit dialog.
    check("bare host gains https", normalizeBookmarkUrl("example.com") == "https://example.com")
    check(
        "bare host with path gains https",
        normalizeBookmarkUrl(" example.com/a ") == "https://example.com/a"
    )
    check(
        "scheme kept",
        normalizeBookmarkUrl("http://example.com") == "http://example.com"
    )
    check("spaces are not a host", normalizeBookmarkUrl("exa mple.com") == "exa mple.com")
    check("no dot is not a host", normalizeBookmarkUrl("localhost") == "localhost")
    check(
        "host:port gains https",
        normalizeBookmarkUrl("example.com:8080") == "https://example.com:8080"
    )
    check(
        "non-web scheme is left for the store to reject",
        normalizeBookmarkUrl("mailto:a@b.com") == "mailto:a@b.com"
    )
    check(
        "javascript scheme is not dressed up",
        normalizeBookmarkUrl("javascript:document.cookie") == "javascript:document.cookie"
    )

    // JSON round trip preserves the tree and the entries.
    val restored = BookmarkStore.fromJson(store.toJson())
    check("round trip counts", restored.bookmarkCount == store.bookmarkCount &&
        restored.folderCount == store.folderCount)
    check(
        "round trip path",
        restored.folderPath(android.id).map { it.name } == listOf("Work", "Android")
    )
    check(
        "round trip favicon flag",
        restored.findByUrl("https://b.example/")?.hasFavicon == true
    )
    val afterRestore = added(restored.addBookmark("New", "https://new.example/", root, 500L, false))
    check(
        "ids keep increasing after restore",
        afterRestore != null && afterRestore.id > android.id
    )

    // A parent that appears AFTER its child in the file (the
    // shape a move produces: creation order is kept on write)
    // still attaches — the parse is two passes.
    val moved = BookmarkStore()
    val early = moved.addFolder("Early", root, 1L)!!
    val late = moved.addFolder("Late", root, 2L)!!
    check("setup move", moved.moveFolder(early.id, late.id))
    val movedBack = BookmarkStore.fromJson(moved.toJson())
    check(
        "parent later in file still attaches",
        movedBack.folder(early.id)?.parentId == late.id &&
            movedBack.folderPath(early.id).map { it.name } == listOf("Late", "Early")
    )

    // Cycles and self-parents in a damaged file land at the
    // root instead of looping, and the result stays acyclic.
    val cyclic = BookmarkStore.fromJson(
        """{"v":1,"next":9,"folders":[""" +
            """{"i":1,"n":"A","p":2,"at":1},{"i":2,"n":"B","p":1,"at":2},""" +
            """{"i":3,"n":"C","p":3,"at":3}],"bookmarks":[]}""",
    )
    check(
        "cycle breaks deterministically (later link in file order loses)",
        cyclic.folder(1)?.parentId == 2L && cyclic.folder(2)?.parentId == root
    )
    check("self-parent lands at root", cyclic.folder(3)?.parentId == root)
    check(
        "cyclic file still yields finite paths",
        cyclic.folderPath(1).size <= 2 && cyclic.folderPath(2).size <= 2
    )

    // A stored next that undershoots the real ids is repaired.
    val undershot = BookmarkStore.fromJson(
        """{"v":1,"next":2,"folders":[],"bookmarks":[""" +
            """{"i":40,"t":"x","u":"https://x.example/","f":0,"at":1,"fav":0}]}""",
    )
    val fresh = added(undershot.addBookmark("y", "https://y.example/", root, 2L, false))
    check("next id repaired past the max id", fresh != null && fresh.id > 40L)

    // Tolerant parsing: dangling references land at the root,
    // duplicate URLs keep the first row, corrupt input is an
    // empty store.
    val messy = BookmarkStore.fromJson(
        """{"v":1,"next":50,"folders":[{"i":5,"n":"F","p":77,"at":1}],""" +
            """"bookmarks":[{"i":6,"t":"x","u":"https://x.example/","f":99,"at":1,"fav":0},""" +
            """{"i":7,"t":"y","u":"https://x.example/","f":0,"at":2,"fav":0}]}""",
    )
    check("dangling folder lands at root", messy.folder(5)?.parentId == root)
    check("dangling bookmark folder lands at root", messy.bookmark(6)?.folderId == root)
    check("duplicate URL keeps first", messy.bookmarkCount == 1 && messy.bookmark(7) == null)
    check("corrupt input is empty", BookmarkStore.fromJson("{oops").isEmpty())

    // Favicon flag flips are stored.
    store.setHasFavicon(b!!.id, false)
    check("favicon flag update", store.findByUrl("https://b.example/")?.hasFavicon == false)

    // Caps: adds report LimitReached, folder creation stops,
    // and parsing truncates at the caps.
    val capped = BookmarkStore()
    var lastAdd: AddBookmarkResult? = null
    for (i in 1..BookmarkStore.MAX_BOOKMARKS) {
        lastAdd = capped.addBookmark("t$i", "https://h$i.example/", root, i.toLong(), false)
    }
    check("adds succeed up to the cap", lastAdd is AddBookmarkResult.Added)
    check(
        "add past the cap is LimitReached",
        capped.addBookmark("over", "https://over.example/", root, 1L, false) is
            AddBookmarkResult.LimitReached
    )
    val cappedFolders = BookmarkStore()
    var lastFolder: jp.kaleidpixel.pageloupe.BookmarkFolder? = null
    for (i in 1..BookmarkStore.MAX_FOLDERS) {
        lastFolder = cappedFolders.addFolder("f$i", root, i.toLong())
    }
    check("folders succeed up to the cap", lastFolder != null)
    check(
        "folder past the cap is refused",
        cappedFolders.addFolder("over", root, 1L) == null
    )
    val bigJson = StringBuilder("""{"v":1,"next":1,"folders":[""")
    for (i in 1..(BookmarkStore.MAX_FOLDERS + 5)) {
        if (i > 1) bigJson.append(',')
        bigJson.append("""{"i":$i,"n":"f$i","p":0,"at":$i}""")
    }
    bigJson.append("""],"bookmarks":[""")
    for (i in 1..(BookmarkStore.MAX_BOOKMARKS + 5)) {
        if (i > 1) bigJson.append(',')
        bigJson.append("""{"i":${10000 + i},"t":"t","u":"https://h$i.example/","f":0,"at":1,"fav":0}""")
    }
    bigJson.append("]}")
    val parsedCapped = BookmarkStore.fromJson(bigJson.toString())
    check(
        "parse truncates folders at the cap",
        parsedCapped.folderCount == BookmarkStore.MAX_FOLDERS
    )
    check(
        "parse truncates bookmarks at the cap",
        parsedCapped.bookmarkCount == BookmarkStore.MAX_BOOKMARKS
    )

    // Clear empties both maps and the URL index.
    val s3 = BookmarkStore()
    s3.addFolder("F", root, 1L)
    added(s3.addBookmark("t", "https://t.example/", root, 1L, false))
    s3.clear()
    check("clear empties", s3.isEmpty() && s3.findByUrl("https://t.example/") == null)

    if (failures > 0) {
        println("BookmarkStoreTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("BookmarkStoreTest: all $checks checks passed")
}
