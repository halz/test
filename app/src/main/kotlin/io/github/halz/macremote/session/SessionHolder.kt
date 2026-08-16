package io.github.halz.macremote.session

import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.rfb.RfbAuthException
import io.github.halz.macremote.rfb.client.RfbClient
import io.github.halz.macremote.rfb.client.RfbEvent
import kotlinx.coroutines.CoroutineScope
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

    private val job: Job = scope.launch(Dispatchers.IO) {
        val collector = launch {
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
                    is RfbEvent.Bell, is RfbEvent.ServerCutText -> Unit
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
 * Application-scoped owner of the current session. Screens re-bind to the
 * existing session after configuration changes instead of reconnecting.
 */
class SessionHolder(private val appScope: CoroutineScope) {

    var current: VncSession? = null
        private set

    fun start(profile: Profile, password: String): VncSession {
        current?.close()
        return VncSession(profile, password, appScope).also { current = it }
    }

    /** The live session for [profileId], or null if none/finished. */
    fun activeFor(profileId: String): VncSession? {
        val session = current ?: return null
        if (session.profile.id != profileId) return null
        return when (session.state.value) {
            is SessionState.Failed, SessionState.Closed -> null
            else -> session
        }
    }

    fun close() {
        current?.close()
        current = null
    }
}
