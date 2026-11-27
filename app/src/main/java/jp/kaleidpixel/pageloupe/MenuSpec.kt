package jp.kaleidpixel.pageloupe

/**
 * The browser menu's structure (owner spec, 2026-11-22): the
 * scrollable item list and the fixed icon-only footer, in display
 * order. Rendering and behavior live in MainActivity; this file
 * only pins WHAT appears, so a JVM test can hold the structure
 * against the spec while the features behind the items land one
 * by one.
 *
 * The entry types carry the wiring contract. An Action or
 * Checkbox entry's id is an enum whose every value MainActivity
 * handles in an exhaustive `when`, and icons resolve the same
 * way: adding an entry here without wiring it up there is a
 * compile error, not a silently dead row. Promoting a feature
 * from Placeholder to Action is a type change here plus the new
 * branch the compiler then demands there. A Placeholder entry
 * has nothing to wire yet: it is listed (so the menu's final
 * shape is already visible) but is not clickable -- tapping it
 * does nothing at all. The PlaceholderId values double as the
 * promotion checklist for the features still to come.
 *
 * Four entries have state-dependent rendering. The first two
 * were decided by the owner on 2026-11-22: the DevTools item's
 * label below is its closed-state name and the renderer shows
 * "DevTools を閉じる" while the panel is open, and the
 * settings item opens a provisional settings page that holds
 * the debug-log toggle (the menu itself no longer carries that
 * toggle, and its gate must always have a UI path). The third
 * is the console-export item (2026-11-23): it is greyed out
 * while no recorded log files exist, because its page would
 * have nothing to export. The fourth is the translate item
 * (2026-11-27): its label below is the untranslated name and
 * the renderer shows "原文を表示" while the current page is
 * translated, mirroring the DevTools pattern.
 *
 * Items that used to be in the menu but are not in this spec
 * (new tab, reload, about) keep their features; they are simply
 * not menu entries anymore.
 */
object MenuSpec {

    enum class Icon {
        HISTORY, DOWNLOAD, BOOKMARK, SEARCH, LANGUAGES, MONITOR,
        CODE, MONITOR_SMARTPHONE, FILE_TEXT, FILE_UP, SHARE_2, SETTINGS, POWER,
        ARROW_LEFT, HOME, ARROW_RIGHT, BOOKMARK_PLUS, BOOKMARK_MINUS,
    }

    enum class ActionId { HISTORY, BOOKMARKS, FIND, TRANSLATE, DEVTOOLS, CONSOLE_EXPORT_PAGE, SETTINGS, EXIT }
    enum class CheckboxId { DESKTOP, REMOTE_DEBUG, CONSOLE_RECORD }
    enum class PlaceholderId { DOWNLOADS }
    enum class FooterId { BACK, HOME, FORWARD, BOOKMARK_TOGGLE, SHARE }

    sealed interface ListEntry
    data class Action(val id: ActionId, val label: String, val icon: Icon) : ListEntry
    data class Checkbox(val id: CheckboxId, val label: String, val icon: Icon) : ListEntry
    data class Placeholder(val id: PlaceholderId, val label: String, val icon: Icon) : ListEntry
    object Separator : ListEntry

    /** The scrollable list, top to bottom. */
    val ITEMS: List<ListEntry> = listOf(
        Action(ActionId.HISTORY, "閲覧履歴", Icon.HISTORY),
        Placeholder(PlaceholderId.DOWNLOADS, "ダウンロード", Icon.DOWNLOAD),
        Action(ActionId.BOOKMARKS, "ブックマーク", Icon.BOOKMARK),
        Separator,
        Action(ActionId.FIND, "ページ内検索", Icon.SEARCH),
        Action(ActionId.TRANSLATE, "翻訳", Icon.LANGUAGES),
        Checkbox(CheckboxId.DESKTOP, "デスクトップサイト", Icon.MONITOR),
        Separator,
        Action(ActionId.DEVTOOLS, "DevTools を開く", Icon.CODE),
        Checkbox(CheckboxId.REMOTE_DEBUG, "リモートデバッグ", Icon.MONITOR_SMARTPHONE),
        Checkbox(CheckboxId.CONSOLE_RECORD, "コンソールログの記録", Icon.FILE_TEXT),
        Action(ActionId.CONSOLE_EXPORT_PAGE, "コンソールログ書き出し", Icon.FILE_UP),
        Separator,
        Action(ActionId.SETTINGS, "設定", Icon.SETTINGS),
        Action(ActionId.EXIT, "終了", Icon.POWER),
    )

    /** The DevTools item's label while the panel is open (its
     * ITEMS label is the closed-state name). Owner decision,
     * 2026-11-22. */
    const val DEVTOOLS_CLOSE_LABEL = "DevTools を閉じる"

    /** The translate item's label while the current page is
     * translated (its ITEMS label is the untranslated name).
     * Owner decision, 2026-11-27. */
    const val TRANSLATE_RESTORE_LABEL = "原文を表示"

    /** The translate item's label while a translation run is
     * working: the item cancels the run. Added in review
     * (v0.41.0) so a working run always has a visible stop. */
    const val TRANSLATE_CANCEL_LABEL = "翻訳を中止"

    data class FooterItem(val id: FooterId, val label: String, val icon: Icon)

    /**
     * The footer row, left to right: icon-only buttons pinned
     * below the list (the list scrolls, the footer does not).
     * The bookmark button's icon is the not-bookmarked form; it
     * switches to BOOKMARK_MINUS while the current page is
     * bookmarked (the renderer owns that swap).
     */
    val FOOTER: List<FooterItem> = listOf(
        FooterItem(FooterId.BACK, "戻る", Icon.ARROW_LEFT),
        FooterItem(FooterId.HOME, "ホーム", Icon.HOME),
        FooterItem(FooterId.FORWARD, "進む", Icon.ARROW_RIGHT),
        FooterItem(FooterId.BOOKMARK_TOGGLE, "ブックマークに追加", Icon.BOOKMARK_PLUS),
        FooterItem(FooterId.SHARE, "共有", Icon.SHARE_2),
    )
}
