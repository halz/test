package io.github.halz.macremote.ui.session

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
import io.github.halz.macremote.session.RemoteSession
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // The floating toolbar and the tab strip under it show and hide together.
    var chromeVisible by remember { mutableStateOf(true) }

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
                chromeVisible = chromeVisible,
                onChromeVisibleChange = { chromeVisible = it },
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

        // Session tabs under the toolbar, shown only when there is something
        // to switch between. Outside a live session they stay visible.
        val connected = state is SessionState.Connected
        if ((chromeVisible || !connected) &&
            (sessions.size > 1 || (sessions.size == 1 && sessions[0].profile.id != currentId))
        ) {
            SessionTabs(
                sessions = sessions,
                currentId = currentId,
                onSelect = { currentId = it; chromeVisible = true },
                onCloseTab = { closeTab(it) },
                onAdd = onExit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (connected) 72.dp else 8.dp),
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
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.forEach { session ->
            val id = session.profile.id
            val selected = id == currentId
            val state by session.state.collectAsState()
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChromeBackground)
                    .border(1.dp, if (selected) Accent else ChromeOutline, RoundedCornerShape(14.dp))
                    .clickable { onSelect(id) }
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionThumbnail(session, Modifier.size(width = 52.dp, height = 32.dp))
                Column {
                    Text(
                        session.profile.name.ifEmpty { session.profile.host },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    Text(
                        when (state) {
                            is SessionState.Connected -> if (selected) "● 表示中" else "● 接続中"
                            SessionState.Connecting -> "接続中…"
                            else -> "切断"
                        },
                        color = if (state is SessionState.Connected) Online else ChromeSubtle,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (selected) {
                    Text(
                        "✕",
                        color = ChromeSubtle,
                        modifier = Modifier.clip(CircleShape).clickable { onCloseTab(id) }.padding(6.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ChromeBackground)
                .clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) { Text("＋", color = Color.White) }
    }
}

/** A live miniature of a session's remote screen. */
@Composable
private fun SessionThumbnail(session: RemoteSession, modifier: Modifier = Modifier) {
    val frame by session.frame.collectAsState()
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Color.DarkGray)) {
        frame // redraw on every framebuffer update
        val bitmap = session.display.bitmap
        drawImage(
            bitmap.asImageBitmap(),
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedContent(
    session: RemoteSession,
    settings: AppSettings,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    onOpenFiles: () -> Unit,
    onBackToList: () -> Unit,
    onDisconnect: () -> Unit,
    onMacFullHd: (() -> Unit)?,
    onMacRestoreResolution: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val transform = remember(session) { CanvasTransform() }
    var keyboardActive by remember { mutableStateOf(false) }
    var keyBarOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    // Bumped by every toolbar interaction; restarts the auto-hide countdown.
    var chromeTouch by remember { mutableStateOf(0) }
    // Modifiers: one tap applies to the next key only, a second tap locks
    // the modifier until it is tapped again.
    var modifiers by remember { mutableStateOf(setOf<Int>()) }
    var lockedModifiers by remember { mutableStateOf(setOf<Int>()) }
    val windows = session.profile.protocol == Protocol.RDP

    LaunchedEffect(chromeVisible, chromeTouch, moreMenuOpen) {
        if (chromeVisible && !moreMenuOpen) {
            delay(CHROME_AUTO_HIDE_MILLIS)
            onChromeVisibleChange(false)
        }
    }

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
        val current = (lockedModifiers + modifiers).toList()
        modifiers = emptySet()
        return current
    }

    fun tapModifier(keysym: Int) {
        when (keysym) {
            in lockedModifiers -> lockedModifiers = lockedModifiers - keysym
            in modifiers -> {
                modifiers = modifiers - keysym
                lockedModifiers = lockedModifiers + keysym
            }
            else -> modifiers = modifiers + keysym
        }
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

        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ChromeBackground)
                    .border(1.dp, ChromeOutline, RoundedCornerShape(22.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back to the list with the connection kept alive.
                ToolbarButton("▦", "一覧") { onBackToList() }
                ToolbarButton(if (trackpad) "◎" else "☝", if (trackpad) "トラックパッド" else "タッチ", selected = trackpad) {
                    chromeTouch++
                    scope.launch { settings.setTrackpadMode(!trackpad) }
                }
                ToolbarButton(if (windows) "Ctrl" else "⌘", "キー", selected = keyBarOpen) {
                    chromeTouch++
                    keyBarOpen = !keyBarOpen
                }
                ToolbarButton("⤢", if (transform.userZoomed) "全体表示" else "拡大") {
                    chromeTouch++
                    if (transform.userZoomed) transform.fit() else transform.fill()
                }
                Box {
                    ToolbarButton("⋯", "その他", selected = moreMenuOpen) { moreMenuOpen = true }
                    DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false; chromeTouch++ }) {
                        DropdownMenuItem(
                            text = { Text("貼り付け（端末のクリップボード）") },
                            onClick = {
                                moreMenuOpen = false
                                clipboard.getText()?.text?.let { session.paste(it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("ファイル転送") },
                            onClick = { moreMenuOpen = false; onOpenFiles() },
                        )
                        HorizontalDivider()
                        Text(
                            "表示の解像度",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        DisplayScale.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == displayScale) "✓ ${option.label}" else "　 ${option.label}") },
                                onClick = {
                                    moreMenuOpen = false
                                    scope.launch { settings.setDisplayScale(option) }
                                },
                            )
                        }
                        if (onMacFullHd != null && onMacRestoreResolution != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Mac を Full HD に変更") },
                                onClick = { moreMenuOpen = false; onMacFullHd() },
                            )
                            DropdownMenuItem(
                                text = { Text("Mac の解像度を元に戻す") },
                                onClick = { moreMenuOpen = false; onMacRestoreResolution() },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("切断", color = MaterialTheme.colorScheme.error) },
                            onClick = { moreMenuOpen = false; onDisconnect() },
                        )
                    }
                }
            }
        } else {
            // A slim handle brings the toolbar back.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .size(width = 120.dp, height = 28.dp)
                    .clickable { onChromeVisibleChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.55f)),
                )
            }
        }

        // Keys along the bottom edge (above the soft keyboard when it is
        // open). Collapsed, only a round keyboard button remains.
        if (keyBarOpen || keyboardActive) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ChromeBackground)
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyButton(if (keyboardActive) "⌨ ▼" else "⌨", selected = keyboardActive) {
                    keyboardActive = !keyboardActive
                }
                val modifierKeys = if (windows) {
                    listOf("Ctrl" to Keysyms.CONTROL_L, "Alt" to Keysyms.ALT_L, "⊞" to Keysyms.SUPER_L, "⇧" to Keysyms.SHIFT_L)
                } else {
                    listOf("⌘" to Keysyms.SUPER_L, "⌥" to Keysyms.ALT_L, "⌃" to Keysyms.CONTROL_L, "⇧" to Keysyms.SHIFT_L)
                }
                modifierKeys.forEach { (label, keysym) ->
                    KeyButton(
                        label,
                        selected = keysym in modifiers,
                        locked = keysym in lockedModifiers,
                    ) { tapModifier(keysym) }
                }
                listOf(
                    "Esc" to Keysyms.ESCAPE, "Tab" to Keysyms.TAB,
                    "←" to Keysyms.LEFT, "↑" to Keysyms.UP, "↓" to Keysyms.DOWN, "→" to Keysyms.RIGHT,
                    "Del" to Keysyms.DELETE, "Home" to Keysyms.HOME, "End" to Keysyms.END,
                    "PgUp" to Keysyms.PAGE_UP, "PgDn" to Keysyms.PAGE_DOWN,
                ).forEach { (label, keysym) ->
                    KeyButton(label) { session.keyPress(keysym, consumeModifiers()) }
                }
                KeyButton("貼付") { clipboard.getText()?.text?.let { session.paste(it) } }
                if (!keyboardActive) KeyButton("✕") { keyBarOpen = false }
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(ChromeBackground)
                    .border(1.dp, ChromeOutline, CircleShape)
                    .clickable { keyboardActive = true },
                contentAlignment = Alignment.Center,
            ) { Text("⌨", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/** An icon-and-caption button in the floating toolbar. */
@Composable
private fun ToolbarButton(icon: String, caption: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .widthIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(caption, color = if (selected) Color.White else ChromeSubtle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** A key in the bottom key bar; [selected] is one-shot, [locked] held. */
@Composable
private fun KeyButton(label: String, selected: Boolean = false, locked: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (locked) Accent else KeyBackground)
            .border(1.5.dp, if (selected) Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .widthIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private val ChromeBackground = Color(0xE6141820)
private val ChromeOutline = Color(0x33FFFFFF)
private val ChromeSubtle = Color(0xFF8B95A7)
private val KeyBackground = Color(0xFF2A303C)
private val Accent = Color(0xFF4F8CFF)
private val Online = Color(0xFF38D27A)
private const val CHROME_AUTO_HIDE_MILLIS = 4_000L

@Composable
private fun ErrorPane(message: String, onRetry: (() -> Unit)?, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // An RDP failure may carry FreeRDP's log lines after a blank line.
        val title = message.substringBefore("\n\n")
        val detail = message.substringAfter("\n\n", "")
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotEmpty()) {
            SelectionContainer {
                Text(detail, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
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
