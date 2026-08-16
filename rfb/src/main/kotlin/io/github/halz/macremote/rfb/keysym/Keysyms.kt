package io.github.halz.macremote.rfb.keysym

/**
 * X11 keysyms as used by the RFB KeyEvent message.
 *
 * Latin-1 printable characters map to their code point; other Unicode
 * characters use the 0x01000000 + codepoint convention, which macOS Screen
 * Sharing understands for most text (best-effort for CJK input — see README).
 */
object Keysyms {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val DELETE = 0xFFFF
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    /** macOS Command key. */
    const val SUPER_L = 0xFFEB
    /** macOS Option key. */
    const val ALT_L = 0xFFE9

    private const val UNICODE_OFFSET = 0x01000000

    /** Keysym for a Unicode code point. */
    fun forCodePoint(codePoint: Int): Int = when (codePoint) {
        in 0x20..0x7E, in 0xA0..0xFF -> codePoint
        '\n'.code, '\r'.code -> RETURN
        '\t'.code -> TAB
        '\b'.code -> BACKSPACE
        else -> UNICODE_OFFSET + codePoint
    }
}
