package jp.halz.levelwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTest {

    private val lock = Lock(
        id = "a-b-c",
        name = "玄関",
        press = ButtonPress(540, 1200, 1_800L),
        bleAddress = "AA:BB:CC:DD:EE:FF",
    )

    @Test
    fun `survives a round trip through preferences`() {
        assertEquals(lock, Lock.fromJson(JSONObject(lock.toJson().toString())))
    }

    @Test
    fun `a lock without a recording or a device keeps its nulls`() {
        val fresh = Lock.create("裏口")
        val parsed = Lock.fromJson(JSONObject(fresh.toJson().toString()))
        assertEquals(fresh, parsed)
        assertNull(parsed?.press)
        assertNull(parsed?.bleAddress)
        assertFalse(fresh.isReady)
    }

    @Test
    fun `a lock is ready once its button is recorded`() {
        assertTrue(lock.isReady)
        assertFalse(lock.copy(press = null).isReady)
    }

    @Test
    fun `an entry without an id is dropped rather than shown unnamed`() {
        assertNull(Lock.fromJson(JSONObject("""{"name":"玄関"}""")))
    }

    @Test
    fun `each new lock gets its own id`() {
        assertTrue(Lock.create("玄関").id != Lock.create("玄関").id)
    }
}
