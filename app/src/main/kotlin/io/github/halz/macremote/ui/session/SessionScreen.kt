package io.github.halz.macremote.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import io.github.halz.macremote.session.VncSession
import androidx.compose.ui.unit.IntSize

/**
 * The remote-screen view. The session itself lives in [SessionHolder]
 * (application scope), so folding/unfolding the device re-creates this
 * composable but re-binds to the same TCP connection.
 */
@Composable
fun SessionScreen(
    profileId: String,
    repository: ProfileRepository,
    secretStore: SecretStore,
    sessionHolder: SessionHolder,
    onExit: () -> Unit,
) {
    var session by remember { mutableStateOf(sessionHolder.activeFor(profileId)) }
    var startFailed by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }

    LaunchedEffect(profileId, retryToken) {
        if (sessionHolder.activeFor(profileId) == null) {
            val profile = repository.find(profileId)
            if (profile == null) {
                startFailed = "接続先が見つかりません"
                return@LaunchedEffect
            }
            val password = secretStore.loadPassword(profileId).orEmpty()
            session = sessionHolder.start(profile, password)
        } else {
            session = sessionHolder.activeFor(profileId)
        }
    }

    val currentSession = session
    val state = currentSession?.state?.collectAsState()?.value

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            startFailed != null -> ErrorPane(startFailed!!, onRetry = null, onExit = onExit)
            currentSession == null || state == SessionState.Connecting -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state is SessionState.Failed -> ErrorPane(
                state.message,
                onRetry = { retryToken++ },
                onExit = { sessionHolder.close(); onExit() },
            )
            state == SessionState.Closed -> ErrorPane(
                "切断されました",
                onRetry = { retryToken++ },
                onExit = { sessionHolder.close(); onExit() },
            )
            state is SessionState.Connected -> ConnectedContent(
                session = currentSession,
                onDisconnect = { sessionHolder.close(); onExit() },
            )
        }
    }
}

@Composable
private fun ConnectedContent(session: VncSession, onDisconnect: () -> Unit) {
    val transform = remember(session) { CanvasTransform() }
    var keyboardActive by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    // One-shot modifiers applied to the next key or typed character.
    var modifiers by remember { mutableStateOf(setOf<Int>()) }
    var lastPointerRemote by remember { mutableStateOf<Offset?>(null) }

    val frameState = session.frame.collectAsState()
    val fb = session.client.framebuffer

    // Handle DesktopSize changes outside the draw phase: snapshot state must
    // not be written while drawing.
    LaunchedEffect(session) {
        session.frame.collect {
            val remoteSize = IntSize(fb.width, fb.height)
            if (transform.remoteSize != remoteSize) {
                transform.remoteSize = remoteSize
                transform.fit()
            }
        }
    }

    fun sendPointer(mask: Int, remote: Offset) {
        lastPointerRemote = remote
        session.pointer(mask, remote.x.toInt(), remote.y.toInt())
    }

    fun consumeModifiers(): List<Int> {
        val current = modifiers.toList()
        modifiers = emptySet()
        return current
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    transform.viewSize = size
                    transform.remoteSize = IntSize(fb.width, fb.height)
                    if (transform.userZoomed) transform.clampOffset() else transform.fit()
                }
                .vncGestures(transform) { mask, remote -> sendPointer(mask, remote) },
        ) {
            frameState.value // snapshot read: redraw whenever a framebuffer update lands
            withTransform({
                translate(transform.offsetX, transform.offsetY)
                scale(transform.scale, transform.scale, pivot = Offset.Zero)
            }) {
                drawImage(session.framebufferBitmap.bitmap.asImageBitmap())
                // macOS often doesn't paint the cursor into the framebuffer, so
                // mark the last pointer position locally.
                lastPointerRemote?.let { p ->
                    drawCircle(
                        color = Color(0x99FFFFFF),
                        radius = 12f / transform.scale,
                        center = p,
                        style = Stroke(width = 2.5f / transform.scale),
                    )
                }
            }
        }

        KeyInputBridge(
            active = keyboardActive,
            onText = { text ->
                val mods = consumeModifiers()
                if (mods.isEmpty()) session.text(text)
                else text.codePoints().forEach { cp -> session.keyPress(Keysyms.forCodePoint(cp), mods) }
            },
            onKeysym = { keysym -> session.keyPress(keysym, consumeModifiers()) },
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (toolbarVisible) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDisconnect) { Text("切断") }
                        TextButton(onClick = { keyboardActive = !keyboardActive }) {
                            Text(if (keyboardActive) "⌨✓" else "⌨")
                        }
                        TextButton(onClick = { transform.fit() }) { Text("全体") }
                        ModifierChip("⌘", Keysyms.SUPER_L, modifiers) { modifiers = it }
                        ModifierChip("⌃", Keysyms.CONTROL_L, modifiers) { modifiers = it }
                        ModifierChip("⌥", Keysyms.ALT_L, modifiers) { modifiers = it }
                        ModifierChip("⇧", Keysyms.SHIFT_L, modifiers) { modifiers = it }
                        TextButton(onClick = { session.keyPress(Keysyms.ESCAPE, consumeModifiers()) }) { Text("Esc") }
                        TextButton(onClick = { session.keyPress(Keysyms.TAB, consumeModifiers()) }) { Text("Tab") }
                        TextButton(onClick = { session.keyPress(Keysyms.LEFT, consumeModifiers()) }) { Text("←") }
                        TextButton(onClick = { session.keyPress(Keysyms.UP, consumeModifiers()) }) { Text("↑") }
                        TextButton(onClick = { session.keyPress(Keysyms.DOWN, consumeModifiers()) }) { Text("↓") }
                        TextButton(onClick = { session.keyPress(Keysyms.RIGHT, consumeModifiers()) }) { Text("→") }
                    }
                }
            }
            TextButton(onClick = { toolbarVisible = !toolbarVisible }) {
                Text(if (toolbarVisible) "▲" else "▼", color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ModifierChip(
    label: String,
    keysym: Int,
    active: Set<Int>,
    onChange: (Set<Int>) -> Unit,
) {
    FilterChip(
        selected = keysym in active,
        onClick = { onChange(if (keysym in active) active - keysym else active + keysym) },
        label = { Text(label) },
    )
}

@Composable
private fun ErrorPane(message: String, onRetry: (() -> Unit)?, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onRetry != null) Button(onClick = onRetry) { Text("再接続") }
            TextButton(onClick = onExit) { Text("一覧へ戻る") }
        }
    }
}
