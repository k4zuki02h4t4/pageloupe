package tests

import jp.kaleidpixel.pageloupe.MenuSpec

// JVM tests for the browser menu's structure (owner spec,
// 2026-11-22): the item list's order, labels, entry types and
// separators, and the fixed footer's buttons. Behavior lives in
// MainActivity and is covered on device; the renderer's dispatch
// on the MenuSpec entry types and id enums is exhaustive, so an
// unwired entry is a compile error rather than a dead row. This
// suite pins WHAT the menu shows so later feature work cannot
// silently reorder, rename or reclassify it.
// Run via tests/run_tests.sh.

private var failures = 0
private var checks = 0

private fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) {
        failures++
        println("FAIL: $name")
    }
}

private fun tag(entry: MenuSpec.ListEntry): String = when (entry) {
    is MenuSpec.Action -> "A"
    is MenuSpec.Checkbox -> "C"
    is MenuSpec.Placeholder -> "P"
    MenuSpec.Separator -> "|"
}

private fun labelOf(entry: MenuSpec.ListEntry): String = when (entry) {
    is MenuSpec.Action -> entry.label
    is MenuSpec.Checkbox -> entry.label
    is MenuSpec.Placeholder -> entry.label
    MenuSpec.Separator -> ""
}

fun main() {
    // The list, top to bottom, exactly as specified: A = action,
    // C = checkbox, P = placeholder, | = separator.
    check(
        "list structure",
        MenuSpec.ITEMS.map { tag(it) } == listOf(
            "A", "P", "A", "|",
            "A", "A", "C", "|",
            "A", "C", "C", "A", "|",
            "A", "A",
        )
    )
    check(
        "list labels",
        MenuSpec.ITEMS.map { labelOf(it) } == listOf(
            "閲覧履歴", "ダウンロード", "ブックマーク", "",
            "ページ内検索", "翻訳", "デスクトップサイト", "",
            "DevTools を開く", "リモートデバッグ", "コンソールログの記録", "コンソールログ書き出し", "",
            "設定", "終了",
        )
    )
    check(
        "action items are the working features",
        MenuSpec.ITEMS.filterIsInstance<MenuSpec.Action>().map { it.id } ==
            listOf(
                MenuSpec.ActionId.HISTORY, MenuSpec.ActionId.BOOKMARKS, MenuSpec.ActionId.FIND,
                MenuSpec.ActionId.TRANSLATE,
                MenuSpec.ActionId.DEVTOOLS, MenuSpec.ActionId.CONSOLE_EXPORT_PAGE,
                MenuSpec.ActionId.SETTINGS, MenuSpec.ActionId.EXIT,
            )
    )
    check(
        "checkbox items are the three ON/OFF settings",
        MenuSpec.ITEMS.filterIsInstance<MenuSpec.Checkbox>().map { it.id } ==
            listOf(
                MenuSpec.CheckboxId.DESKTOP, MenuSpec.CheckboxId.REMOTE_DEBUG,
                MenuSpec.CheckboxId.CONSOLE_RECORD,
            )
    )
    check(
        "placeholders are the unimplemented features",
        MenuSpec.ITEMS.filterIsInstance<MenuSpec.Placeholder>().map { it.id } ==
            listOf(
                MenuSpec.PlaceholderId.DOWNLOADS,
            )
    )
    check(
        "every listed item has a label",
        MenuSpec.ITEMS.filter { it !is MenuSpec.Separator }.all { labelOf(it).isNotEmpty() }
    )

    check(
        "devtools close label",
        MenuSpec.DEVTOOLS_CLOSE_LABEL == "DevTools を閉じる"
    )

    check(
        "translate restore label",
        MenuSpec.TRANSLATE_RESTORE_LABEL == "原文を表示"
    )

    check(
        "translate cancel label",
        MenuSpec.TRANSLATE_CANCEL_LABEL == "翻訳を中止"
    )

    // The footer, left to right.
    check(
        "footer order",
        MenuSpec.FOOTER.map { it.id } ==
            listOf(
                MenuSpec.FooterId.BACK, MenuSpec.FooterId.HOME, MenuSpec.FooterId.FORWARD,
                MenuSpec.FooterId.BOOKMARK_TOGGLE, MenuSpec.FooterId.SHARE,
            )
    )
    check(
        "footer bookmark button starts in the add (plus) form",
        MenuSpec.FOOTER.first { it.id == MenuSpec.FooterId.BOOKMARK_TOGGLE }.icon ==
            MenuSpec.Icon.BOOKMARK_PLUS
    )
    check(
        "footer share button uses the share-2 icon",
        MenuSpec.FOOTER.first { it.id == MenuSpec.FooterId.SHARE }.icon ==
            MenuSpec.Icon.SHARE_2
    )
    check(
        "every footer button has a description label",
        MenuSpec.FOOTER.all { it.label.isNotEmpty() }
    )

    if (failures > 0) {
        println("MenuSpecTest: $failures of $checks checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("MenuSpecTest: all $checks checks passed")
}
