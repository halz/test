package io.github.halz.macremote.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.desktop.data.ProfileStore
import io.github.halz.macremote.desktop.data.SecretStore
import io.github.halz.macremote.desktop.data.SettingsStore
import io.github.halz.macremote.desktop.session.RdpLauncher
import io.github.halz.macremote.desktop.session.SessionHolder
import io.github.halz.macremote.desktop.ui.AppColors
import io.github.halz.macremote.desktop.ui.FilesWindow
import io.github.halz.macremote.desktop.ui.MacRemoteTheme
import io.github.halz.macremote.desktop.ui.ProfileEditScreen
import io.github.halz.macremote.desktop.ui.ProfileListScreen
import io.github.halz.macremote.desktop.ui.SessionScreen
import io.github.halz.macremote.desktop.ui.TerminalWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.imageio.ImageIO

sealed class Screen {
    data object Profiles : Screen()
    data class Edit(val profileId: String?) : Screen()
    data class Session(val profileId: String) : Screen()
}

/** Everything the windows share; lives as long as the process. */
class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profiles = ProfileStore()
    val secrets = SecretStore()
    val settings = SettingsStore()
    val sessions = SessionHolder(scope)

    var screen by mutableStateOf<Screen>(Screen.Profiles)

    /** Profiles with an open terminal / file-transfer window. */
    val terminals = mutableStateListOf<Profile>()
    val fileWindows = mutableStateListOf<Profile>()

    /** Short notices shown as a snackbar in the main window. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun notify(message: String) {
        messages.tryEmit(message)
    }

    fun password(profileId: String): String = secrets.loadPassword(profileId).orEmpty()

    /** Mac: opens (or returns to) its tab. Windows: hands over to mstsc. */
    fun connect(profile: Profile) {
        when (profile.protocol) {
            Protocol.VNC -> screen = Screen.Session(profile.id)
            Protocol.RDP -> scope.launch(Dispatchers.IO) {
                runCatching { RdpLauncher.launch(profile, password(profile.id)) }
                    .onSuccess { notify("リモートデスクトップ接続 (mstsc) を起動しました") }
                    .onFailure { notify("起動できませんでした: ${it.message}") }
            }
        }
    }

    fun openTerminal(profile: Profile) {
        when {
            profile.username.isEmpty() -> notify("ターミナルにはユーザ名入りの接続先が必要です")
            terminals.none { it.id == profile.id } -> terminals.add(profile)
        }
    }

    fun openFiles(profile: Profile) {
        when {
            profile.username.isEmpty() -> notify("ファイル転送にはユーザ名入りの接続先が必要です")
            fileWindows.none { it.id == profile.id } -> fileWindows.add(profile)
        }
    }
}

private val appIcon = BitmapPainter(
    ImageIO.read(AppState::class.java.getResource("/icon.png")).toComposeImageBitmap()
)

fun main() = application {
    val app = remember { AppState() }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    val current = app.screen
    val title = when (current) {
        is Screen.Session -> app.profiles.find(current.profileId)?.let { "${it.name.ifEmpty { it.host }} — Mac Remote" }
        else -> null
    } ?: "Mac Remote"

    Window(
        onCloseRequest = {
            app.sessions.closeAll()
            exitApplication()
        },
        title = title,
        icon = appIcon,
        state = windowState,
    ) {
        MacRemoteTheme {
            MainContent(app, windowState, window)
        }
    }

    app.terminals.forEach { profile ->
        key(profile.id) {
            TerminalWindow(profile, app.password(profile.id), onClose = { app.terminals.remove(profile) })
        }
    }
    app.fileWindows.forEach { profile ->
        key(profile.id) {
            FilesWindow(profile, app.password(profile.id), onClose = { app.fileWindows.remove(profile) })
        }
    }
}

@Composable
private fun MainContent(app: AppState, windowState: WindowState, window: java.awt.Frame) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        app.messages.collect { snackbar.showSnackbar(it) }
    }
    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        when (val screen = app.screen) {
            Screen.Profiles -> ProfileListScreen(app, window)
            is Screen.Edit -> ProfileEditScreen(
                app = app,
                profileId = screen.profileId,
                onDone = { app.screen = Screen.Profiles },
                onConnect = { profile -> app.screen = Screen.Profiles; app.connect(profile) },
            )
            is Screen.Session -> SessionScreen(
                app = app,
                profileId = screen.profileId,
                windowState = windowState,
                onExit = { app.screen = Screen.Profiles },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
