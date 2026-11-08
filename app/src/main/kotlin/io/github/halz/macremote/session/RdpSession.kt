package io.github.halz.macremote.session

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.freerdpcore.services.LibFreeRDP
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.rfb.messages.PointerButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * A Windows Remote Desktop session through FreeRDP's JNI layer. The remote
 * desktop is requested at Full HD so it reads well when scaled onto a phone.
 * FreeRDP runs its own session thread; input calls only enqueue events, so
 * they are safe from the UI thread.
 */
class RdpSession(
    override val profile: Profile,
    password: String,
    context: Context,
    private val scope: CoroutineScope,
) : RemoteSession, LibFreeRDP.Listener {

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    override val state: StateFlow<SessionState> = _state

    private val _frame = MutableStateFlow(0L)
    override val frame: StateFlow<Long> = _frame

    private val _serverClipboard = MutableStateFlow<String?>(null)
    override val serverClipboard: StateFlow<String?> = _serverClipboard

    private val rdpDisplay = Display()
    override val display: RemoteDisplay get() = rdpDisplay

    private var inst = 0L
    private val freed = AtomicBoolean(false)
    private var lastButtons = 0

    init {
        try {
            LibFreeRDP.load()
            inst = LibFreeRDP.newInstance(context.applicationContext, this)
            check(inst != 0L) { "FreeRDP を初期化できませんでした" }
            check(LibFreeRDP.parseArguments(inst, arguments(password))) { "接続設定が不正です" }
            check(LibFreeRDP.connect(inst)) { "接続を開始できませんでした" }
        } catch (e: UnsatisfiedLinkError) {
            _state.value = SessionState.Failed("この APK には RDP ライブラリが含まれていません")
        } catch (e: Exception) {
            _state.value = SessionState.Failed(e.message ?: "接続できませんでした")
            release()
        }
    }

    private fun arguments(password: String): Array<String> {
        val (domain, user) = profile.username.split('\\', limit = 2)
            .let { if (it.size == 2) it[0] to it[1] else null to profile.username }
        return buildList {
            add("freerdp")
            add("/gdi:sw")
            add("/v:${profile.host}")
            add("/port:${profile.port}")
            if (user.isNotEmpty()) add("/u:$user")
            if (domain != null) add("/d:$domain")
            if (password.isNotEmpty()) add("/p:$password")
            add("/size:${DESKTOP_WIDTH}x$DESKTOP_HEIGHT")
            add("/bpp:32")
            add("/network:auto")
            add("/gfx")
            add("-wallpaper")
            add("/clipboard")
            add("/kbd:unicode:on")
            // Self-signed RDP certificates are the norm; the transport is the
            // Tailscale tunnel (see README), as with the SFTP host keys.
            add("/cert:ignore")
            add("/auto-reconnect")
        }.toTypedArray()
    }

    // ---- LibFreeRDP.Listener (called on FreeRDP's session thread) ----

    override fun onConnectionSuccess() {
        _state.value = SessionState.Connected(profile.host)
    }

    override fun onConnectionFailure(reason: String?) {
        _state.value = SessionState.Failed(reason?.takeIf { it.isNotBlank() } ?: "接続できませんでした")
        releaseLater()
    }

    override fun onDisconnected() {
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Closed
        releaseLater()
    }

    override fun onGraphicsResize(width: Int, height: Int) {
        rdpDisplay.resize(width, height)
        _frame.value++
    }

    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        // Runs inside FreeRDP's paint callback, while its buffer is consistent.
        if (LibFreeRDP.updateGraphics(inst, rdpDisplay.bitmap, x, y, width, height)) _frame.value++
    }

    override fun onRemoteClipboardChanged(data: String?) {
        _serverClipboard.value = data
    }

    // ---- Input ----

    override fun pointer(buttonMask: Int, x: Int, y: Int) {
        if (inst == 0L || freed.get()) return
        if (buttonMask and PointerButtons.WHEEL_UP != 0) {
            LibFreeRDP.sendCursorEvent(inst, x, y, PTR_FLAGS_WHEEL or WHEEL_STEP)
            return
        }
        if (buttonMask and PointerButtons.WHEEL_DOWN != 0) {
            // Rotation is a 9-bit two's-complement value: -120 = NEGATIVE | 0x88.
            LibFreeRDP.sendCursorEvent(inst, x, y, PTR_FLAGS_WHEEL or PTR_FLAGS_WHEEL_NEGATIVE or 0x0088)
            return
        }
        val buttons = buttonMask and (PointerButtons.LEFT or PointerButtons.RIGHT or PointerButtons.MIDDLE)
        val changed = buttons xor lastButtons
        lastButtons = buttons
        if (changed == 0) {
            LibFreeRDP.sendCursorEvent(inst, x, y, PTR_FLAGS_MOVE)
            return
        }
        for ((bit, flag) in BUTTON_FLAGS) {
            if (changed and bit == 0) continue
            val down = if (buttons and bit != 0) PTR_FLAGS_DOWN else 0
            LibFreeRDP.sendCursorEvent(inst, x, y, flag or down)
        }
    }

    override fun keyPress(keysym: Int, modifiers: List<Int>) {
        if (inst == 0L || freed.get()) return
        val mods = modifiers.mapNotNull { virtualKey(it) }
        mods.forEach { LibFreeRDP.sendKeyEvent(inst, it, true) }
        val vk = virtualKey(keysym)
        if (vk != null) {
            LibFreeRDP.sendKeyEvent(inst, vk, true)
            LibFreeRDP.sendKeyEvent(inst, vk, false)
        } else {
            unicode(keysym)
        }
        mods.asReversed().forEach { LibFreeRDP.sendKeyEvent(inst, it, false) }
    }

    override fun text(value: String) {
        if (inst == 0L || freed.get()) return
        for (ch in value) {
            when (ch) {
                '\n', '\r' -> keyPress(Keysyms.RETURN, emptyList())
                '\t' -> keyPress(Keysyms.TAB, emptyList())
                '\b' -> keyPress(Keysyms.BACKSPACE, emptyList())
                // RDP Unicode input takes UTF-16 code units, surrogates included.
                else -> {
                    LibFreeRDP.sendUnicodeKeyEvent(inst, ch.code, true)
                    LibFreeRDP.sendUnicodeKeyEvent(inst, ch.code, false)
                }
            }
        }
    }

    /** Puts the text on the Windows clipboard and presses Ctrl+V. */
    override fun paste(value: String) {
        if (value.isEmpty() || inst == 0L || freed.get()) return
        LibFreeRDP.sendClipboardData(inst, value)
        keyPress('v'.code, listOf(Keysyms.CONTROL_L))
    }

    /** The desktop is already Full HD; downsampling would only blur it. */
    override fun setDisplayDivisor(value: Int) = Unit

    /** The instance is freed once FreeRDP reports the disconnect (or failure). */
    override fun close() {
        if (inst != 0L && !freed.get()) LibFreeRDP.disconnect(inst)
        if (_state.value !is SessionState.Failed) _state.value = SessionState.Closed
    }

    private fun unicode(keysym: Int) {
        val codePoint = if (keysym >= UNICODE_KEYSYM_OFFSET) keysym - UNICODE_KEYSYM_OFFSET else keysym
        text(String(Character.toChars(codePoint)))
    }

    // The session thread delivers the disconnect callbacks and only then
    // winds down, so freeing must not happen inside them.
    private fun releaseLater() {
        scope.launch(Dispatchers.IO) {
            delay(1_000)
            release()
        }
    }

    private fun release() {
        if (inst != 0L && freed.compareAndSet(false, true)) LibFreeRDP.freeInstance(inst)
    }

    private class Display : RemoteDisplay {
        // hasAlpha=false: FreeRDP writes RGBX, whose X byte must not be read as alpha.
        @Volatile
        override var bitmap: Bitmap = newBitmap(DESKTOP_WIDTH, DESKTOP_HEIGHT)
            private set
        override val divisor: Int = 1
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        fun resize(width: Int, height: Int) {
            if (width != bitmap.width || height != bitmap.height) bitmap = newBitmap(width, height)
        }

        private fun newBitmap(width: Int, height: Int) =
            Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
                .apply { setHasAlpha(false) }
    }

    private companion object {
        const val DESKTOP_WIDTH = 1920
        const val DESKTOP_HEIGHT = 1080

        const val PTR_FLAGS_WHEEL = 0x0200
        const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100
        const val WHEEL_STEP = 0x0078
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        val BUTTON_FLAGS = listOf(
            PointerButtons.LEFT to 0x1000,
            PointerButtons.RIGHT to 0x2000,
            PointerButtons.MIDDLE to 0x4000,
        )

        const val UNICODE_KEYSYM_OFFSET = 0x01000000

        /** X11 keysym -> Windows virtual-key code, for keys RDP sends as scancodes. */
        fun virtualKey(keysym: Int): Int? = when (keysym) {
            Keysyms.BACKSPACE -> 0x08
            Keysyms.TAB -> 0x09
            Keysyms.RETURN -> 0x0D
            Keysyms.ESCAPE -> 0x1B
            Keysyms.PAGE_UP -> 0x21
            Keysyms.PAGE_DOWN -> 0x22
            Keysyms.END -> 0x23
            Keysyms.HOME -> 0x24
            Keysyms.LEFT -> 0x25
            Keysyms.UP -> 0x26
            Keysyms.RIGHT -> 0x27
            Keysyms.DOWN -> 0x28
            Keysyms.DELETE -> 0x2E
            Keysyms.F11 -> 0x7A
            Keysyms.SHIFT_L -> 0xA0
            Keysyms.CONTROL_L -> 0xA2
            Keysyms.ALT_L -> 0xA4
            Keysyms.SUPER_L -> 0x5B
            ' '.code -> 0x20
            in '0'.code..'9'.code -> keysym
            in 'a'.code..'z'.code -> keysym - 0x20
            in 'A'.code..'Z'.code -> keysym
            else -> null
        }
    }
}
