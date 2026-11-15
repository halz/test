package io.github.halz.macremote.session

import androidx.compose.ui.graphics.Color
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import io.github.halz.macremote.data.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.net.ConnectException
import java.net.UnknownHostException

sealed class ShellState {
    data object Connecting : ShellState()
    data object Connected : ShellState()
    data class Failed(val message: String) : ShellState()
    data object Closed : ShellState()
}

/**
 * An interactive SSH shell (xterm-256color PTY) on the profile's computer —
 * "Remote Login" on the Mac, OpenSSH Server on Windows — shown through a
 * libvterm [emulator]. Network I/O stays on IO threads: the emulator's
 * keyboard and resize callbacks arrive on the main thread and are queued.
 */
class SshShell(
    val profile: Profile,
    private val password: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ShellState>(ShellState.Connecting)
    val state: StateFlow<ShellState> = _state

    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(0xFFE8ECF2),
        defaultBackground = Color(0xFF0E1116),
        onKeyboardInput = { bytes -> outbox.trySend(bytes) },
        onResize = { dims -> resize(dims) },
    )

    private val job: Job = scope.launch(Dispatchers.IO) { run() }

    private suspend fun run() {
        try {
            val session = JSch().getSession(profile.username, profile.host, SSH_PORT).also { session = it }
            session.setPassword(password.toByteArray(Charsets.UTF_8))
            // Host keys are not pinned: the transport is the Tailscale tunnel (see README).
            session.setConfig("StrictHostKeyChecking", "no")
            session.setServerAliveInterval(30_000)
            session.connect(CONNECT_TIMEOUT_MILLIS)

            val channel = (session.openChannel("shell") as ChannelShell).also { channel = it }
            channel.setPtyType("xterm-256color")
            val dims = emulator.dimensions
            channel.setPtySize(dims.columns, dims.rows, dims.widthPixels, dims.heightPixels)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MILLIS)
            _state.value = ShellState.Connected

            scope.launch(Dispatchers.IO) {
                runCatching {
                    for (bytes in outbox) {
                        output.write(bytes)
                        output.flush()
                    }
                }
            }

            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) emulator.writeInput(buffer, 0, read)
            }
            if (_state.value !is ShellState.Failed) _state.value = ShellState.Closed
        } catch (e: Exception) {
            if (_state.value == ShellState.Connecting) _state.value = ShellState.Failed(describe(e))
            else if (_state.value !is ShellState.Failed) _state.value = ShellState.Closed
        } finally {
            disconnect()
        }
    }

    private fun resize(dims: TerminalDimensions) {
        val channel = channel ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { channel.setPtySize(dims.columns, dims.rows, dims.widthPixels, dims.heightPixels) }
        }
    }

    fun close() {
        job.cancel()
        outbox.close()
        // Closing the session ends the blocking read on the IO thread.
        scope.launch(Dispatchers.IO) { disconnect() }
        if (_state.value !is ShellState.Failed) _state.value = ShellState.Closed
    }

    private fun disconnect() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }

    private fun describe(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                "ユーザ名かパスワードが違います"
            e.cause is ConnectException || message.contains("Connection refused", ignoreCase = true) ->
                "SSH（ポート 22）に接続できません。Mac は「リモートログイン」、Windows は「OpenSSH サーバー」を有効にしてください"
            e.cause is UnknownHostException -> "ホストが見つかりません: ${profile.host}"
            e is JSchException && message.contains("timeout", ignoreCase = true) ->
                "接続がタイムアウトしました（Tailscale の接続を確認してください）"
            else -> message.ifEmpty { e.javaClass.simpleName }
        }
    }

    private companion object {
        const val SSH_PORT = 22
        const val CONNECT_TIMEOUT_MILLIS = 15_000
    }
}

/** Live terminals, one per profile, kept outside the UI so fold/unfold keeps them. */
class ShellHolder(private val scope: CoroutineScope) {
    private val shells = mutableMapOf<String, SshShell>()

    fun open(profile: Profile, password: String): SshShell =
        shells[profile.id]?.takeIf { it.state.value == ShellState.Connecting || it.state.value == ShellState.Connected }
            ?: SshShell(profile, password, scope).also {
                shells.remove(profile.id)?.close()
                shells[profile.id] = it
            }

    fun get(profileId: String): SshShell? = shells[profileId]

    fun close(profileId: String) {
        shells.remove(profileId)?.close()
    }
}
