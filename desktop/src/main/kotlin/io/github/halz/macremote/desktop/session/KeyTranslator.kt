package io.github.halz.macremote.desktop.session

import io.github.halz.macremote.rfb.keysym.Keysyms
import java.awt.event.KeyEvent

/**
 * Turns the PC keyboard's AWT key events into X11 keysym down/up events for
 * the Mac. Each release sends exactly the keysym its press sent, so a key
 * whose character changes while held (Shift released first, say) never sticks.
 */
class KeyTranslator(private val send: (keysym: Int, down: Boolean) -> Unit) {

    /** Ctrl → ⌘ Command, Alt → ⌥ Option, Win → ⌃ Control; false: Ctrl → ⌃, Win → ⌥, Alt → ⌘ (Mac keyboard layout). */
    @Volatile
    var ctrlAsCommand: Boolean = true

    private val pressed = HashMap<Int, Int>()

    /** Returns true when the event was sent to the Mac (and should not reach the local UI). */
    fun onKey(event: KeyEvent): Boolean = when (event.id) {
        KeyEvent.KEY_PRESSED -> {
            val keysym = keysymFor(event.keyCode, event.keyLocation, event.keyChar)
            if (keysym != null) {
                pressed[keyId(event)] = keysym
                send(keysym, true)
                true
            } else {
                false
            }
        }
        KeyEvent.KEY_RELEASED -> {
            val keysym = pressed.remove(keyId(event))
            if (keysym != null) send(keysym, false)
            keysym != null
        }
        else -> false
    }

    /** Left and right Shift/Ctrl share a key code; the location tells them apart. */
    private fun keyId(event: KeyEvent) = event.keyCode * 8 + event.keyLocation

    /** Whether a key that sent [keysym] is held down right now. */
    fun isHeld(keysym: Int): Boolean = keysym in pressed.values

    /** Releases everything still held, e.g. when the window loses focus mid-shortcut. */
    fun releaseAll() {
        pressed.values.forEach { send(it, false) }
        pressed.clear()
    }

    internal fun keysymFor(keyCode: Int, location: Int, keyChar: Char): Int? {
        special(keyCode, location)?.let { return it }
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar.code >= 0x20 && keyChar.code != 0x7F) {
            return Keysyms.forCodePoint(keyChar.code)
        }
        // With Ctrl held the AWT character is a control code; use the key itself.
        return when (keyCode) {
            in KeyEvent.VK_A..KeyEvent.VK_Z -> 'a'.code + (keyCode - KeyEvent.VK_A)
            in KeyEvent.VK_0..KeyEvent.VK_9 -> '0'.code + (keyCode - KeyEvent.VK_0)
            else -> PUNCTUATION[keyCode]?.code
        }
    }

    private fun special(keyCode: Int, location: Int): Int? {
        val right = location == KeyEvent.KEY_LOCATION_RIGHT
        return when (keyCode) {
            KeyEvent.VK_CONTROL -> if (ctrlAsCommand) Keysyms.SUPER_L else if (right) CONTROL_R else Keysyms.CONTROL_L
            KeyEvent.VK_ALT -> if (ctrlAsCommand) Keysyms.ALT_L else Keysyms.SUPER_L
            KeyEvent.VK_WINDOWS, KeyEvent.VK_META -> if (ctrlAsCommand) Keysyms.CONTROL_L else Keysyms.ALT_L
            KeyEvent.VK_SHIFT -> if (right) SHIFT_R else Keysyms.SHIFT_L
            KeyEvent.VK_ENTER -> Keysyms.RETURN
            KeyEvent.VK_BACK_SPACE -> Keysyms.BACKSPACE
            KeyEvent.VK_TAB -> Keysyms.TAB
            KeyEvent.VK_ESCAPE -> Keysyms.ESCAPE
            KeyEvent.VK_DELETE -> Keysyms.DELETE
            KeyEvent.VK_INSERT -> INSERT
            KeyEvent.VK_HOME -> Keysyms.HOME
            KeyEvent.VK_END -> Keysyms.END
            KeyEvent.VK_PAGE_UP -> Keysyms.PAGE_UP
            KeyEvent.VK_PAGE_DOWN -> Keysyms.PAGE_DOWN
            KeyEvent.VK_LEFT -> Keysyms.LEFT
            KeyEvent.VK_UP -> Keysyms.UP
            KeyEvent.VK_RIGHT -> Keysyms.RIGHT
            KeyEvent.VK_DOWN -> Keysyms.DOWN
            KeyEvent.VK_CAPS_LOCK -> CAPS_LOCK
            KeyEvent.VK_CONTEXT_MENU -> MENU
            in KeyEvent.VK_F1..KeyEvent.VK_F12 -> F1 + (keyCode - KeyEvent.VK_F1)
            else -> null
        }
    }

    private companion object {
        const val SHIFT_R = 0xFFE2
        const val CONTROL_R = 0xFFE4
        const val INSERT = 0xFF63
        const val MENU = 0xFF67
        const val CAPS_LOCK = 0xFFE5
        const val F1 = 0xFFBE

        /** US-layout characters of the punctuation keys, for Ctrl/Cmd shortcuts. */
        val PUNCTUATION = mapOf(
            KeyEvent.VK_MINUS to '-', KeyEvent.VK_EQUALS to '=', KeyEvent.VK_OPEN_BRACKET to '[',
            KeyEvent.VK_CLOSE_BRACKET to ']', KeyEvent.VK_BACK_SLASH to '\\', KeyEvent.VK_SEMICOLON to ';',
            KeyEvent.VK_QUOTE to '\'', KeyEvent.VK_COMMA to ',', KeyEvent.VK_PERIOD to '.',
            KeyEvent.VK_SLASH to '/', KeyEvent.VK_BACK_QUOTE to '`', KeyEvent.VK_SPACE to ' ',
        )
    }
}
