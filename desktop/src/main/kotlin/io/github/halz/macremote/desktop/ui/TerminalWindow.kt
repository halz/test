package io.github.halz.macremote.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.desktop.session.SshTerminal
import io.github.halz.macremote.desktop.session.SshTtyConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

private sealed class TerminalState {
    data object Connecting : TerminalState()
    data object Connected : TerminalState()
    data class Failed(val message: String) : TerminalState()
}

/** An SSH terminal for one profile, in its own window. */
@Composable
fun TerminalWindow(profile: Profile, password: String, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "ターミナル — ${profile.displayName()}",
        state = rememberWindowState(size = DpSize(960.dp, 620.dp)),
    ) {
        MacRemoteTheme {
            var state by remember { mutableStateOf<TerminalState>(TerminalState.Connecting) }
            val widget = remember { SshTerminal.widget() }
            // Owns the connection until the window closes; a connect that
            // finishes after that is closed straight away.
            val holder = remember { ConnectorHolder() }

            LaunchedEffect(Unit) {
                state = try {
                    val connector = withContext(Dispatchers.IO) {
                        SshTerminal.connect(profile, password).also { holder.set(it) }
                    }
                    widget.setTtyConnector(connector)
                    widget.start()
                    TerminalState.Connected
                } catch (e: Exception) {
                    TerminalState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    holder.dispose()
                    thread { runCatching { widget.close() } }
                }
            }

            Box(Modifier.fillMaxSize().background(AppColors.Background)) {
                when (val s = state) {
                    TerminalState.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is TerminalState.Failed -> Text(
                        "接続できませんでした: ${s.message}",
                        color = AppColors.Text,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                    TerminalState.Connected -> {
                        SwingPanel(factory = { widget }, modifier = Modifier.fillMaxSize())
                        LaunchedEffect(Unit) { widget.requestFocusInWindow() }
                    }
                }
            }
        }
    }
}

private class ConnectorHolder {
    private var connector: SshTtyConnector? = null
    private var disposed = false

    @Synchronized
    fun set(value: SshTtyConnector) {
        if (disposed) value.close() else connector = value
    }

    @Synchronized
    fun dispose() {
        disposed = true
        connector?.let { c -> thread { c.close() } }
        connector = null
    }
}
