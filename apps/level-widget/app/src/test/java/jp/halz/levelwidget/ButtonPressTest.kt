package jp.halz.levelwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonPressTest {

    @Test
    fun `survives a round trip through preferences`() {
        val press = ButtonPress(540, 1200, 1_800L)
        assertEquals(press, ButtonPress.fromJson(JSONObject(press.toJson().toString())))
    }

    @Test
    fun `a hold shorter or longer than a person can make is clamped`() {
        assertEquals(ButtonPress.MIN_HOLD_MS, ButtonPress.of(1, 2, 0L).holdMillis)
        assertEquals(ButtonPress.MAX_HOLD_MS, ButtonPress.of(1, 2, 60_000L).holdMillis)
        assertEquals(900L, ButtonPress.of(1, 2, 900L).holdMillis)
    }

    @Test
    fun `a recording without a position is not a recording`() {
        assertNull(ButtonPress.fromJson(null))
        assertNull(ButtonPress.fromJson(JSONObject()))
        assertNull(ButtonPress.fromJson(JSONObject("""{"x":10}""")))
    }

    @Test
    fun `describe names the position`() {
        assertEquals("(540, 1200)", ButtonPress(540, 1200, 1_000L).describe())
    }
}
