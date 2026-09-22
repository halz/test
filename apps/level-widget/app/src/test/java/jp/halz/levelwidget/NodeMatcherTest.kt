package jp.halz.levelwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeMatcherTest {

    private val recorded = NodeMatcher(
        viewId = "com.level.home:id/lock_button",
        contentDescription = "Lock",
        text = "Locked",
        className = "android.widget.ImageButton",
        tapX = 540,
        tapY = 1200,
        longPress = true,
    )

    @Test
    fun `view id wins over the other identifiers`() {
        assertEquals(3, recorded.score("com.level.home:id/lock_button", null, null))
        assertEquals(2, recorded.score("other", "Lock", null))
        assertEquals(1, recorded.score("other", "other", "Locked"))
    }

    @Test
    fun `nothing in common is not a match`() {
        assertEquals(-1, recorded.score("other", "other", "other"))
        assertEquals(-1, recorded.score(null, null, null))
    }

    @Test
    fun `a recording without identifiers never matches by null`() {
        val boundsOnly = NodeMatcher(null, null, null, null, 100, 200, false)
        assertEquals(-1, boundsOnly.score(null, null, null))
        assertEquals(-1, boundsOnly.score("id", "desc", "text"))
    }

    @Test
    fun `survives a round trip through preferences`() {
        assertEquals(recorded, NodeMatcher.fromJson(recorded.toJson()))
    }

    @Test
    fun `a recording with only coordinates keeps its nulls`() {
        val boundsOnly = NodeMatcher(null, null, null, null, 100, 200, false)
        assertEquals(boundsOnly, NodeMatcher.fromJson(boundsOnly.toJson()))
    }

    @Test
    fun `how the button was pressed survives the round trip`() {
        assertEquals(true, NodeMatcher.fromJson(recorded.toJson())?.longPress)
        val tapped = recorded.copy(longPress = false)
        assertEquals(false, NodeMatcher.fromJson(tapped.toJson())?.longPress)
    }

    @Test
    fun `a recording made before long press support counts as a tap`() {
        val old = """{"viewId":"com.level.home:id/lock_button","tapX":540,"tapY":1200}"""
        assertEquals(false, NodeMatcher.fromJson(old)?.longPress)
    }

    @Test
    fun `unreadable preferences are ignored`() {
        assertNull(NodeMatcher.fromJson(null))
        assertNull(NodeMatcher.fromJson(""))
        assertNull(NodeMatcher.fromJson("not json"))
    }

    @Test
    fun `describe prefers the most human readable identifier`() {
        assertEquals("Lock", recorded.describe())
        assertEquals("lock_button", recorded.copy(contentDescription = null, text = null).describe())
        assertEquals("(100, 200)", NodeMatcher(null, null, null, null, 100, 200, false).describe())
    }
}
