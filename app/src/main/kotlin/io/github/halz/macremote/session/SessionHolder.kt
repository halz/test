package io.github.halz.macremote.session

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class SessionState {
    data object Connecting : SessionState()
    data class Connected(val serverName: String) : SessionState()
    data class Failed(val message: String) : SessionState()
    data object Closed : SessionState()
}

/**
 * One live VNC session. Owns the RfbClient reader coroutine and the Bitmap
 * mirror. Lives in [SessionHolder] (application scope) so the TCP connection
 * survives Activity re-creation when the Fold is opened or closed.
 */
class VncSession(
    val profile: Profile,
    password: String,
    scope: CoroutineScope,
) {
    val client = RfbClient(profile.host, profile.port, profile.username, password)

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state

    /** Bumped after every applied framebuffer update; the canvas redraws on change. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame

    /** Latest text the Mac put on its clipboard (ServerCutText, Latin-1 only). */
    private val _serverClipboard = MutableStateFlow<String?>(null)
    val serverClipboard: StateFlow<String?> = _serverClipboard

    val framebufferBitmap = FramebufferBitmap(client.framebuffer)

    // Input events must reach the wire in the order the UI produced them;
    // a single consumer coroutine guarantees that without blocking the UI.
    private val inputQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val inputJob: Job = scope.launch(Dispatchers.IO) {
        for (operation in inputQueue) {
            runCatching { operation() }
        }
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

    /**
     * Pastes into the Mac. Latin-1 text goes via the VNC clipboard plus Cmd+V;
     * anything else (Japanese etc.) is typed out as Unicode key events, since
     * the RFB cut-text message is Latin-1 only.
     */
    fun paste(value: String) {
        if (value.isEmpty()) return
        val isLatin1 = value.all { it.code in 0..0xFF }
        if (isLatin1) {
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

    /** Downsampling factor for the display bitmap (1 = full resolution). */
    fun setDisplayDivisor(value: Int) {
        inputQueue.trySend {
            framebufferBitmap.setDivisor(value)
            _frame.value++
        }
    }

    private val job: Job = scope.launch(Dispatchers.IO) {
        // UNDISPATCHED: the subscription must exist before run() can emit
        // Connected, or a replay-1 flow could drop it behind a later event.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is RfbEvent.Connected -> {
                        framebufferBitmap.onResize()
                        _state.value = SessionState.Connected(event.serverName)
                    }
                    is RfbEvent.Resized -> {
                        framebufferBitmap.onResize()
                        _frame.value++
                    }
                    is RfbEvent.Updated -> {
                        framebufferBitmap.apply(event.rects)
                        _frame.value++
                    }
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
        client.close()
        job.cancel()
        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
        }
    }
}

/**
 * Application-scoped owner of all live sessions (one per profile, shown as
 * tabs). Screens re-bind to existing sessions after configuration changes
 * instead of reconnecting. A finished (failed/closed) session stays listed —
 * its tab shows the error until retried or closed.
 */
class SessionHolder(private val appScope: CoroutineScope) {

    private val _sessions = MutableStateFlow<List<VncSession>>(emptyList())
    val sessions: StateFlow<List<VncSession>> = _sessions

    fun start(profile: Profile, password: String): VncSession {
        _sessions.value.find { it.profile.id == profile.id }?.let { existing ->
            existing.close()
            _sessions.value = _sessions.value - existing
        }
        return VncSession(profile, password, appScope).also { _sessions.value = _sessions.value + it }
    }

    fun sessionFor(profileId: String): VncSession? =
        _sessions.value.find { it.profile.id == profileId }

    /** The live session for [profileId], or null if none/finished. */
    fun activeFor(profileId: String): VncSession? {
        val session = sessionFor(profileId) ?: return null
        return when (session.state.value) {
            is SessionState.Failed, SessionState.Closed -> null
            else -> session
        }
    }

    fun close(profileId: String) {
        sessionFor(profileId)?.let { session ->
            session.close()
            _sessions.value = _sessions.value - session
        }
    }
}
