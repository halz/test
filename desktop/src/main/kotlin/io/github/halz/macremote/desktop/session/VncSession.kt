package io.github.halz.macremote.desktop.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.rfb.RfbAuthException
import io.github.halz.macremote.rfb.client.RfbClient
import io.github.halz.macremote.rfb.client.RfbEvent
import io.github.halz.macremote.rfb.keysym.Keysyms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class SessionState {
    data object Connecting : SessionState()
    data class Connected(val serverName: String) : SessionState()
    data class Failed(val message: String) : SessionState()
    data object Closed : SessionState()
}

/**
 * One live VNC session: the RfbClient reader coroutine plus the latest
 * rendered frame. Input uses X11 keysyms and RFB pointer button masks.
 */
class VncSession(
    val profile: Profile,
    password: String,
    scope: CoroutineScope,
) {
    val client = RfbClient(profile.host, profile.port, profile.username, password)

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state

    /** The remote screen, re-rendered after updates (at most ~60 times a second). */
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image

    /** Latest text the Mac put on its clipboard (ServerCutText is Latin-1 only). */
    private val _serverClipboard = MutableStateFlow<String?>(null)
    val serverClipboard: StateFlow<String?> = _serverClipboard

    // Input events must reach the wire in the order the UI produced them.
    private val inputQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val inputJob: Job = scope.launch(Dispatchers.IO) {
        for (operation in inputQueue) {
            runCatching { operation() }
        }
    }

    // Updates are coalesced: the renderer converts whatever the framebuffer
    // holds when it gets to run, so a burst of rects costs one frame.
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private var pixelBytes = ByteArray(0)
    private val renderJob: Job = scope.launch(Dispatchers.Default) {
        for (signal in dirty) {
            render()?.let { _image.value = it }
            delay(FRAME_INTERVAL_MILLIS)
        }
    }

    /**
     * Framebuffer ints are 0xFFRRGGBB; written little-endian they are the
     * B,G,R,A bytes of Skia's N32 format. The reader may write the
     * framebuffer meanwhile — a momentarily torn frame is the usual trade-off.
     */
    private fun render(): ImageBitmap? {
        val fb = client.framebuffer
        val width = fb.width
        val height = fb.height
        val pixels = fb.pixels
        if (width <= 1 || height <= 1 || pixels.size != width * height) return null
        if (pixelBytes.size != pixels.size * 4) pixelBytes = ByteArray(pixels.size * 4)
        ByteBuffer.wrap(pixelBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, pixelBytes, width * 4).toComposeImageBitmap()
    }

    fun pointer(buttonMask: Int, x: Int, y: Int) {
        inputQueue.trySend { client.sendPointer(buttonMask, x, y) }
    }

    fun key(keysym: Int, down: Boolean) {
        inputQueue.trySend { client.sendKey(keysym, down) }
    }

    fun keyPress(keysym: Int, modifiers: List<Int> = emptyList()) {
        inputQueue.trySend {
            modifiers.forEach { client.sendKey(it, true) }
            client.sendKey(keysym, true)
            client.sendKey(keysym, false)
            modifiers.asReversed().forEach { client.sendKey(it, false) }
        }
    }

    fun text(value: String) {
        inputQueue.trySend { client.sendText(value) }
    }

    /** Puts Latin-1 text on the Mac's clipboard (the RFB cut-text message can carry nothing else). */
    fun sendClipboard(value: String) {
        if (value.isEmpty() || !value.all { it.code in 0..0xFF }) return
        inputQueue.trySend { client.sendCutText(value) }
    }

    /**
     * Pastes into the Mac. Latin-1 text goes via the VNC clipboard plus Cmd+V;
     * anything else (Japanese etc.) is typed out as Unicode key events.
     */
    fun paste(value: String) {
        if (value.isEmpty()) return
        if (value.all { it.code in 0..0xFF }) {
            inputQueue.trySend {
                client.sendCutText(value)
                client.sendKey(Keysyms.SUPER_L, true)
                client.sendKey('v'.code, true)
                client.sendKey('v'.code, false)
                client.sendKey(Keysyms.SUPER_L, false)
            }
        } else {
            text(value)
        }
    }

    private val job: Job = scope.launch(Dispatchers.IO) {
        // UNDISPATCHED: the subscription must exist before run() can emit
        // Connected, or a replay-1 flow could drop it behind a later event.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is RfbEvent.Connected -> {
                        _state.value = SessionState.Connected(event.serverName)
                        dirty.trySend(Unit)
                    }
                    is RfbEvent.Resized, is RfbEvent.Updated -> dirty.trySend(Unit)
                    is RfbEvent.ServerCutText -> _serverClipboard.value = event.text
                    is RfbEvent.Bell -> Unit
                }
            }
        }
        try {
            client.run()
            _state.value = SessionState.Closed
        } catch (e: RfbAuthException) {
            _state.value = SessionState.Failed("認証に失敗しました: ${e.reason}")
        } catch (e: Exception) {
            _state.value = when (_state.value) {
                is SessionState.Connected -> SessionState.Closed
                else -> SessionState.Failed(e.message ?: "接続できませんでした")
            }
        } finally {
            collector.cancel()
        }
    }

    fun close() {
        inputQueue.close()
        inputJob.cancel()
        dirty.close()
        renderJob.cancel()
        client.close()
        job.cancel()
        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MILLIS = 16L
    }
}
