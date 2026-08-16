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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.AppSettings
import io.github.halz.macremote.data.DisplayScale
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import io.github.halz.macremote.session.VncSession
import kotlinx.coroutines.launch
import kotlin.math.floor

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
    settings: AppSettings,
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
                settings = settings,
                onDisconnect = { sessionHolder.close(); onExit() },
            )
        }
    }
}

@Composable
private fun ConnectedContent(
    session: VncSession,
    settings: AppSettings,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val transform = remember(session) { CanvasTransform() }
    var keyboardActive by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var scaleMenuOpen by remember { mutableStateOf(false) }
    // One-shot modifiers applied to the next key or typed character.
    var modifiers by remember { mutableStateOf(setOf<Int>()) }

    val trackpad by settings.trackpadMode.collectAsState(initial = false)
    val displayScale by settings.displayScale.collectAsState(initial = DisplayScale.AUTO)

    val frameState = session.frame.collectAsState()
    val fb = session.client.framebuffer
    val clipboard = LocalClipboardManager.current

    // Cursor indicator / trackpad cursor, in SERVER coordinates.
    var cursorServer by remember(session) { mutableStateOf(Offset(fb.width / 2f, fb.height / 2f)) }
    var viewSizeState by remember { mutableStateOf(IntSize.Zero) }

    fun divisor(): Int = session.framebufferBitmap.divisor

    // Pick the downsampling factor for the screen currently in use. AUTO keys
    // off the live view size, so folding/unfolding re-optimizes automatically.
    LaunchedEffect(session, displayScale, viewSizeState) {
        val view = viewSizeState
        if (view == IntSize.Zero || fb.width == 0) return@LaunchedEffect
        val target = displayScale.divisor
            ?: floor(minOf(fb.width.toFloat() / view.width, fb.height.toFloat() / view.height))
                .toInt().coerceIn(1, 4)
        session.setDisplayDivisor(target)
    }

    // Handle DesktopSize/divisor changes outside the draw phase: snapshot
    // state must not be written while drawing.
    LaunchedEffect(session) {
        session.frame.collect {
            val bitmap = session.framebufferBitmap.bitmap
            val remoteSize = IntSize(bitmap.width, bitmap.height)
            if (transform.remoteSize != remoteSize) {
                transform.remoteSize = remoteSize
                transform.fit()
            }
        }
    }

    // Mac clipboard -> Android clipboard.
    LaunchedEffect(session) {
        session.serverClipboard.collect { text ->
            if (!text.isNullOrEmpty()) clipboard.setText(AnnotatedString(text))
        }
    }

    // Remembered per session only: isTrackpad() reads the live setting through
    // the snapshot-state delegate, so the object need not be re-created.
    val pointerTarget = remember(session) {
        object : PointerTarget {
            override fun isTrackpad() = trackpad

            override fun resolveDirect(viewPosition: Offset): Offset {
                val d = divisor()
                val bitmapPos = transform.toRemote(viewPosition)
                return Offset(bitmapPos.x * d, bitmapPos.y * d)
            }

            override fun cursor() = cursorServer

            override fun moveCursorBy(viewDelta: Offset): Offset {
                val d = divisor()
                val serverDelta = viewDelta * (d / transform.scale)
                val next = Offset(
                    (cursorServer.x + serverDelta.x).coerceIn(0f, (fb.width - 1).toFloat()),
                    (cursorServer.y + serverDelta.y).coerceIn(0f, (fb.height - 1).toFloat()),
                )
                cursorServer = next
                // Keep the cursor on screen: nudge the viewport when it nears an edge.
                val viewPos = Offset(
                    next.x / d * transform.scale + transform.offsetX,
                    next.y / d * transform.scale + transform.offsetY,
                )
                val margin = 48f
                val view = viewSizeState
                var dx = 0f
                var dy = 0f
                if (viewPos.x < margin) dx = margin - viewPos.x
                if (viewPos.x > view.width - margin) dx = view.width - margin - viewPos.x
                if (viewPos.y < margin) dy = margin - viewPos.y
                if (viewPos.y > view.height - margin) dy = view.height - margin - viewPos.y
                if (dx != 0f || dy != 0f) transform.panBy(Offset(dx, dy))
                return next
            }

            override fun send(buttonMask: Int, server: Offset) {
                cursorServer = server
                session.pointer(buttonMask, server.x.toInt(), server.y.toInt())
            }
        }
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
                    viewSizeState = size
                    transform.viewSize = size
                    val bitmap = session.framebufferBitmap.bitmap
                    transform.remoteSize = IntSize(bitmap.width, bitmap.height)
                    if (transform.userZoomed) transform.clampOffset() else transform.fit()
                }
                .vncGestures(transform, pointerTarget),
        ) {
            frameState.value // snapshot read: redraw whenever a framebuffer update lands
            withTransform({
                translate(transform.offsetX, transform.offsetY)
                scale(transform.scale, transform.scale, pivot = Offset.Zero)
            }) {
                drawImage(session.framebufferBitmap.bitmap.asImageBitmap())
                // macOS often doesn't paint the cursor into the framebuffer, so
                // mark the pointer position locally (ring; filled dot in trackpad mode).
                val d = session.framebufferBitmap.divisor
                val p = Offset(cursorServer.x / d, cursorServer.y / d)
                if (trackpad) {
                    drawCircle(color = Color(0xCC000000), radius = 5f / transform.scale, center = p)
                    drawCircle(color = Color(0xEEFFFFFF), radius = 3.5f / transform.scale, center = p)
                } else {
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
                        FilterChip(
                            selected = trackpad,
                            onClick = { scope.launch { settings.setTrackpadMode(!trackpad) } },
                            label = { Text("TP") },
                        )
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { session.paste(it) }
                        }) { Text("貼付") }
                        Box {
                            TextButton(onClick = { scaleMenuOpen = true }) { Text("解像度") }
                            DropdownMenu(expanded = scaleMenuOpen, onDismissRequest = { scaleMenuOpen = false }) {
                                DisplayScale.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (option == displayScale) "✓ ${option.label}" else option.label)
                                        },
                                        onClick = {
                                            scaleMenuOpen = false
                                            scope.launch { settings.setDisplayScale(option) }
                                        },
                                    )
                                }
                            }
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
