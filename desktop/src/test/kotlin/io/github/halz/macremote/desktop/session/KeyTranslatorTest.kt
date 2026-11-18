package io.github.halz.macremote.desktop.session

import io.github.halz.macremote.rfb.keysym.Keysyms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.KeyEvent

class KeyTranslatorTest {
    private val sent = mutableListOf<Pair<Int, Boolean>>()
    private val translator = KeyTranslator { keysym, down -> sent += keysym to down }
    private val source = object : Component() {}

    private fun key(id: Int, code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, location: Int = KeyEvent.KEY_LOCATION_STANDARD) =
        translator.onKey(KeyEvent(source, id, 0L, 0, code, char, location))

    private fun press(code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, location: Int = KeyEvent.KEY_LOCATION_STANDARD) =
        key(KeyEvent.KEY_PRESSED, code, char, location)

    private fun release(code: Int, location: Int = KeyEvent.KEY_LOCATION_STANDARD) =
        key(KeyEvent.KEY_RELEASED, code, location = location)

    @Test
    fun `Ctrl+C becomes Command+c by default`() {
        press(KeyEvent.VK_CONTROL, location = KeyEvent.KEY_LOCATION_LEFT)
        press(KeyEvent.VK_C, '\u0003') // AWT reports a control code while Ctrl is held
        release(KeyEvent.VK_C)
        release(KeyEvent.VK_CONTROL, location = KeyEvent.KEY_LOCATION_LEFT)
        assertEquals(
            listOf(Keysyms.SUPER_L to true, 'c'.code to true, 'c'.code to false, Keysyms.SUPER_L to false),
            sent,
        )
    }

    @Test
    fun `Mac layout mapping makes Alt the Command key`() {
        translator.ctrlAsCommand = false
        press(KeyEvent.VK_ALT, location = KeyEvent.KEY_LOCATION_LEFT)
        press(KeyEvent.VK_CONTROL, location = KeyEvent.KEY_LOCATION_LEFT)
        press(KeyEvent.VK_WINDOWS, location = KeyEvent.KEY_LOCATION_LEFT)
        assertEquals(listOf(Keysyms.SUPER_L to true, Keysyms.CONTROL_L to true, Keysyms.ALT_L to true), sent)
    }

    @Test
    fun `release sends the keysym of the press even if the character changed`() {
        press(KeyEvent.VK_SHIFT, location = KeyEvent.KEY_LOCATION_LEFT)
        press(KeyEvent.VK_1, '!')
        release(KeyEvent.VK_SHIFT, location = KeyEvent.KEY_LOCATION_LEFT)
        release(KeyEvent.VK_1)
        assertEquals(
            listOf(Keysyms.SHIFT_L to true, '!'.code to true, Keysyms.SHIFT_L to false, '!'.code to false),
            sent,
        )
    }

    @Test
    fun `special keys map to X11 keysyms`() {
        press(KeyEvent.VK_ENTER, '\n')
        press(KeyEvent.VK_F5)
        press(KeyEvent.VK_LEFT)
        assertEquals(listOf(Keysyms.RETURN to true, 0xFFC2 to true, Keysyms.LEFT to true), sent)
    }

    @Test
    fun `keys without a keysym are left to the local UI`() {
        assertEquals(false, press(KeyEvent.VK_UNDEFINED))
        assertEquals(false, release(KeyEvent.VK_UNDEFINED))
        assertEquals(emptyList<Pair<Int, Boolean>>(), sent)
    }

    @Test
    fun `releaseAll lifts every held key`() {
        press(KeyEvent.VK_CONTROL, location = KeyEvent.KEY_LOCATION_LEFT)
        press(KeyEvent.VK_A, '\u0001')
        sent.clear()
        translator.releaseAll()
        assertEquals(setOf(Keysyms.SUPER_L to false, 'a'.code to false), sent.toSet())
    }
}
