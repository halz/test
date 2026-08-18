package io.github.halz.macremote.data

/** A command a multi-touch gesture can be bound to. */
enum class GestureAction(val label: String) {
    NONE("なし"),
    MISSION_CONTROL("Mission Control"),
    APP_EXPOSE("アプリケーション Exposé"),
    SPACE_LEFT("左のデスクトップへ"),
    SPACE_RIGHT("右のデスクトップへ"),
    SHOW_DESKTOP("デスクトップを表示"),
    APP_SWITCHER("アプリ切替 (⌘Tab)"),
    SPOTLIGHT("Spotlight (⌘Space)"),
    COPY("コピー (⌘C)"),
    PASTE_REMOTE("ペースト (⌘V)"),
    SCREENSHOT("範囲スクリーンショット (⌘⇧4)"),
    BACK("戻る (⌘[)"),
    FORWARD("進む (⌘])"),
    MINIMIZE("ウィンドウをしまう (⌘M)"),
    CLOSE_WINDOW("ウィンドウを閉じる (⌘W)"),
    RIGHT_CLICK("右クリック"),
    TOGGLE_KEYBOARD("キーボード表示切替"),
    TOGGLE_TRACKPAD("トラックパッドモード切替"),
    PASTE_FROM_ANDROID("Android から貼り付け"),
    FIT("全体表示"),
    FILL("フィル表示"),
}

/**
 * A recognizable multi-touch gesture. Defaults mirror the macOS trackpad, so
 * the app feels like the Mac it is driving out of the box.
 */
enum class GestureTrigger(val label: String, val default: GestureAction) {
    TWO_TAP("2本指タップ", GestureAction.RIGHT_CLICK),
    THREE_UP("3本指 上スワイプ", GestureAction.MISSION_CONTROL),
    THREE_DOWN("3本指 下スワイプ", GestureAction.APP_EXPOSE),
    // Swiping left moves to the space on the right, as on the Mac.
    THREE_LEFT("3本指 左スワイプ", GestureAction.SPACE_RIGHT),
    THREE_RIGHT("3本指 右スワイプ", GestureAction.SPACE_LEFT),
    THREE_TAP("3本指タップ", GestureAction.TOGGLE_KEYBOARD),
    FOUR_UP("4本指 上スワイプ", GestureAction.NONE),
    FOUR_DOWN("4本指 下スワイプ", GestureAction.SHOW_DESKTOP),
    FOUR_LEFT("4本指 左スワイプ", GestureAction.NONE),
    FOUR_RIGHT("4本指 右スワイプ", GestureAction.NONE),
    FOUR_TAP("4本指タップ", GestureAction.SPOTLIGHT),
}
