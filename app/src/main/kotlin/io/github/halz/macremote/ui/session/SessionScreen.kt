package io.github.halz.macremote.ui.session

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.AppSettings
import io.github.halz.macremote.data.DisplayScale
import io.github.halz.macremote.data.GestureAction
import io.github.halz.macremote.data.GestureTrigger
import io.github.halz.macremote.data.MacDisplay
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.rfb.messages.PointerButtons
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import io.github.halz.macremote.session.RemoteSession
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * The remote-screen view with one tab per live session. Sessions live in
 * [SessionHolder] (application scope), so folding/unfolding the device
 * re-creates this composable but re-binds to the same TCP connections.
 */
@Composable
fun SessionScreen(
    profileId: String,
    repository: ProfileRepository,
    secretStore: SecretStore,
    settings: AppSettings,
    sessionHolder: SessionHolder,
    onOpenFiles: (String) -> Unit,
    onExit: () -> Unit,
) {
    val sessions by sessionHolder.sessions.collectAsState()
    var currentId by rememberSaveable { mutableStateOf(profileId) }
    var startFailed by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }
    // Set when the user disconnects the last tab: the screen keeps composing
    // during the exit transition, and the reconnect effect below must not
    // resurrect the session that was just closed.
    var exiting by remember { mutableStateOf(false) }

    // (Re)connect when the tab has no session or only a finished one. Coming
    // from the profile list or switching to a dead tab is an intent to
    // connect; a session that fails right after still shows its error pane.
    // `sessions` is a key so that returning after the idle timeout closed
    // everything reconnects automatically instead of spinning forever.
    LaunchedEffect(currentId, retryToken, sessions) {
        if (exiting) return@LaunchedEffect
        startFailed = null
        val existing = sessionHolder.sessionFor(currentId)
        val finished = existing != null &&
            (existing.state.value is SessionState.Failed || existing.state.value == SessionState.Closed)
        if (existing == null || finished) {
            val profile = repository.find(currentId)
            if (profile == null) {
                startFailed = "接続先が見つかりません"
                return@LaunchedEffect
            }
            val password = secretStore.loadPassword(currentId).orEmpty()
            sessionHolder.start(profile, password)
        }
    }

    fun closeTab(id: String) {
        sessionHolder.close(id)
        if (id == currentId) {
            val remaining = sessionHolder.sessions.value.firstOrNull()
            if (remaining != null) {
                currentId = remaining.profile.id
            } else {
                exiting = true
                onExit()
            }
        }
    }

    val currentSession = sessions.find { it.profile.id == currentId }
    val state = currentSession?.state?.collectAsState()?.value

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Resolution changes go over SSH, which needs the Mac account (ARD login).
    fun macDisplayAction(
        profile: Profile,
        done: String,
        action: suspend (Profile, String) -> Unit,
    ): (() -> Unit)? {
        if (profile.protocol != Protocol.VNC || profile.username.isEmpty()) return null
        return {
            scope.launch {
                runCatching { action(profile, secretStore.loadPassword(profile.id).orEmpty()) }
                    .onSuccess { Toast.makeText(context, done, Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "解像度の変更に失敗: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            startFailed != null -> ErrorPane(startFailed!!, onRetry = null, onExit = onExit)
            currentSession == null || state == SessionState.Connecting -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state is SessionState.Failed -> ErrorPane(
                state.message,
                onRetry = { sessionHolder.close(currentId); retryToken++ },
                onExit = { closeTab(currentId) },
            )
            state == SessionState.Closed -> ErrorPane(
                "切断されました",
                onRetry = { sessionHolder.close(currentId); retryToken++ },
                onExit = { closeTab(currentId) },
            )
            state is SessionState.Connected -> ConnectedContent(
                session = currentSession,
                settings = settings,
                onOpenFiles = { onOpenFiles(currentId) },
                onBackToList = onExit,
                onDisconnect = { closeTab(currentId) },
                onMacFullHd = macDisplayAction(currentSession.profile, "Full HD に変更しました") { profile, password ->
                    val restore = MacDisplay.setFullHd(profile.host, profile.username, password)
                    // Keep the very first original: a second Full HD switch must not
                    // overwrite it with the Full HD setup itself.
                    if (settings.displayRestore(profile.id) == null) settings.setDisplayRestore(profile.id, restore)
                },
                onMacRestoreResolution = macDisplayAction(currentSession.profile, "元の解像度に戻しました") { profile, password ->
                    val restore = settings.displayRestore(profile.id) ?: error("元の解像度が記録されていません")
                    MacDisplay.restore(profile.host, profile.username, password, restore)
                    settings.setDisplayRestore(profile.id, null)
                },
            )
        }

        // Session tabs, shown only when there is something to switch between.
        if (sessions.size > 1 || (sessions.size == 1 && sessions[0].profile.id != currentId)) {
            SessionTabs(
                sessions = sessions,
                currentId = currentId,
                onSelect = { currentId = it },
                onCloseTab = { closeTab(it) },
                onAdd = onExit,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun SessionTabs(
    sessions: List<RemoteSession>,
    currentId: String,
    onSelect: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.navigationBarsPadding().padding(start = 8.dp, bottom = 56.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sessions.forEach { session ->
                val id = session.profile.id
                FilterChip(
                    selected = id == currentId,
                    onClick = { if (id == currentId) onCloseTab(id) else onSelect(id) },
                    label = { Text(if (id == currentId) "${session.profile.name.ifEmpty { session.profile.host }} ✕" else session.profile.name.ifEmpty { session.profile.host }) },
                )
            }
            TextButton(onClick = onAdd) { Text("＋") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedContent(
    session: RemoteSession,
    settings: AppSettings,
    onOpenFiles: () -> Unit,
    onBackToList: () -> Unit,
    onDisconnect: () -> Unit,
    onMacFullHd: (() -> Unit)?,
    onMacRestoreResolution: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val transform = remember(session) { CanvasTransform() }
    var keyboardActive by remember { mutableStateOf(false) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var scaleMenuOpen by remember { mutableStateOf(false) }
    // One-shot modifiers applied to the next key or typed character.
    var modifiers by remember { mutableStateOf(setOf<Int>()) }

    // Held as State (not plain values) so the remembered gesture callback
    // below reads live settings without being re-created on every change.
    val trackpadState = settings.trackpadMode.collectAsState(initial = false)
    val gesturesState = settings.gestures.collectAsState(initial = emptyMap())
    val trackpad by trackpadState
    val displayScale by settings.displayScale.collectAsState(initial = DisplayScale.AUTO)

    // The user can dismiss the IME with the system back/swipe, which never
    // goes through the toolbar button — follow the real visibility so the
    // button state can't stick at "shown".
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible) keyboardActive = false
    }

    val frameState = session.frame.collectAsState()
    val fb = session.display
    val clipboard = LocalClipboardManager.current

    // Cursor indicator / trackpad cursor, in SERVER coordinates.
    var cursorServer by remember(session) { mutableStateOf(Offset(fb.width / 2f, fb.height / 2f)) }
    var viewSizeState by remember { mutableStateOf(IntSize.Zero) }

    fun divisor(): Int = session.display.divisor

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
            val bitmap = session.display.bitmap
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

    // Stable across recompositions (everything it touches is snapshot state or
    // remembered), so multi-touch detection is never restarted mid-gesture.
    val onGesture: (GestureTrigger, Offset) -> Unit = remember(session, transform, pointerTarget) {
        { trigger: GestureTrigger, viewPosition: Offset ->
            val action = gesturesState.value[trigger] ?: trigger.default
            val shortcut = shortcutFor(action, windows = session.profile.protocol == Protocol.RDP)
            if (shortcut != null) {
                session.keyPress(shortcut.first, shortcut.second)
            } else when (action) {
                GestureAction.RIGHT_CLICK -> {
                    val p = if (trackpadState.value) pointerTarget.cursor()
                    else pointerTarget.resolveDirect(viewPosition)
                    pointerTarget.send(0, p)
                    pointerTarget.send(PointerButtons.RIGHT, p)
                    pointerTarget.send(0, p)
                }
                GestureAction.TOGGLE_KEYBOARD -> { keyboardActive = !keyboardActive }
                GestureAction.TOGGLE_TRACKPAD -> {
                    scope.launch { settings.setTrackpadMode(!trackpadState.value) }
                }
                GestureAction.PASTE_FROM_ANDROID -> { clipboard.getText()?.text?.let { session.paste(it) } }
                GestureAction.FIT -> transform.fit()
                GestureAction.FILL -> transform.fill()
                else -> Unit
            }
        }
    }

    // imePadding shrinks the canvas above the soft keyboard; onSizeChanged then
    // re-fits, so the remote screen stays fully visible while typing.
    Box(Modifier.fillMaxSize().imePadding()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewSizeState = size
                    transform.viewSize = size
                    val bitmap = session.display.bitmap
                    transform.remoteSize = IntSize(bitmap.width, bitmap.height)
                    if (transform.userZoomed) transform.clampOffset() else transform.fit()
                }
                .vncGestures(transform, pointerTarget, onGesture),
        ) {
            frameState.value // snapshot read: redraw whenever a framebuffer update lands
            withTransform({
                translate(transform.offsetX, transform.offsetY)
                scale(transform.scale, transform.scale, pivot = Offset.Zero)
            }) {
                drawImage(session.display.bitmap.asImageBitmap())
                // macOS often doesn't paint the cursor into the framebuffer, so
                // mark the pointer position locally (ring; filled dot in trackpad mode).
                val d = session.display.divisor
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
                        // Back to the list with the connection kept alive, so
                        // another Mac can be opened as a second tab.
                        TextButton(onClick = onBackToList) { Text("一覧") }
                        FilterChip(
                            selected = trackpad,
                            onClick = { scope.launch { settings.setTrackpadMode(!trackpad) } },
                            label = { Text("TP") },
                        )
                        TextButton(onClick = {
                            clipboard.getText()?.text?.let { session.paste(it) }
                        }) { Text("貼付") }
                        TextButton(onClick = onOpenFiles) { Text("ファイル") }
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
                                if (onMacFullHd != null && onMacRestoreResolution != null) {
                                    DropdownMenuItem(
                                        text = { Text("Mac を Full HD に変更") },
                                        onClick = { scaleMenuOpen = false; onMacFullHd() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Mac の解像度を元に戻す") },
                                        onClick = { scaleMenuOpen = false; onMacRestoreResolution() },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { transform.fit() }) { Text("全体") }
                        TextButton(onClick = { transform.fill() }) { Text("フィル") }
                    }
                }
            }
            TextButton(onClick = { toolbarVisible = !toolbarVisible }) {
                Text(if (toolbarVisible) "▲" else "▼", color = Color.White.copy(alpha = 0.6f))
            }
        }

        // Modifier/navigation keys along the bottom edge, above the IME when open.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { keyboardActive = !keyboardActive }) {
                    Text(if (keyboardActive) "⌨✓" else "⌨")
                }
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

/**
 * The keyboard shortcut a gesture command sends, or null for commands that
 * are not shortcuts. Windows gets the closest native equivalent of each
 * macOS action (Task View for Mission Control, Ctrl in place of Command).
 */
private fun shortcutFor(action: GestureAction, windows: Boolean): Pair<Int, List<Int>>? {
    val command = Keysyms.SUPER_L // Command on the Mac, the Windows key on Windows
    val ctrl = Keysyms.CONTROL_L
    val alt = Keysyms.ALT_L
    val shift = Keysyms.SHIFT_L
    return if (windows) {
        when (action) {
            GestureAction.MISSION_CONTROL -> Keysyms.TAB to listOf(command)
            GestureAction.APP_EXPOSE -> Keysyms.TAB to listOf(ctrl, alt)
            GestureAction.SPACE_LEFT -> Keysyms.LEFT to listOf(ctrl, command)
            GestureAction.SPACE_RIGHT -> Keysyms.RIGHT to listOf(ctrl, command)
            GestureAction.SHOW_DESKTOP -> 'd'.code to listOf(command)
            GestureAction.APP_SWITCHER -> Keysyms.TAB to listOf(alt)
            GestureAction.SPOTLIGHT -> 's'.code to listOf(command)
            GestureAction.COPY -> 'c'.code to listOf(ctrl)
            GestureAction.PASTE_REMOTE -> 'v'.code to listOf(ctrl)
            GestureAction.SCREENSHOT -> 's'.code to listOf(command, shift)
            GestureAction.BACK -> Keysyms.LEFT to listOf(alt)
            GestureAction.FORWARD -> Keysyms.RIGHT to listOf(alt)
            GestureAction.MINIMIZE -> Keysyms.DOWN to listOf(command)
            GestureAction.CLOSE_WINDOW -> 'w'.code to listOf(ctrl)
            else -> null
        }
    } else {
        when (action) {
            GestureAction.MISSION_CONTROL -> Keysyms.UP to listOf(ctrl)
            GestureAction.APP_EXPOSE -> Keysyms.DOWN to listOf(ctrl)
            GestureAction.SPACE_LEFT -> Keysyms.LEFT to listOf(ctrl)
            GestureAction.SPACE_RIGHT -> Keysyms.RIGHT to listOf(ctrl)
            GestureAction.SHOW_DESKTOP -> Keysyms.F11 to emptyList()
            GestureAction.APP_SWITCHER -> Keysyms.TAB to listOf(command)
            GestureAction.SPOTLIGHT -> ' '.code to listOf(command)
            GestureAction.COPY -> 'c'.code to listOf(command)
            GestureAction.PASTE_REMOTE -> 'v'.code to listOf(command)
            GestureAction.SCREENSHOT -> '4'.code to listOf(command, shift)
            GestureAction.BACK -> '['.code to listOf(command)
            GestureAction.FORWARD -> ']'.code to listOf(command)
            GestureAction.MINIMIZE -> 'm'.code to listOf(command)
            GestureAction.CLOSE_WINDOW -> 'w'.code to listOf(command)
            else -> null
        }
    }
}
