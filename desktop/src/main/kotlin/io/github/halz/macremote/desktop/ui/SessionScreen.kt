package io.github.halz.macremote.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.halz.macremote.data.MacDisplay
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.desktop.AppState
import io.github.halz.macremote.desktop.session.KeyTranslator
import io.github.halz.macremote.desktop.session.SessionState
import io.github.halz.macremote.desktop.session.VncSession
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.rfb.messages.PointerButtons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** The remote-screen view with one tab per live Mac session. */
@Composable
fun SessionScreen(
    app: AppState,
    profileId: String,
    windowState: WindowState,
    onExit: () -> Unit,
) {
    val sessions by app.sessions.sessions.collectAsState()
    var currentId by remember { mutableStateOf(profileId) }
    var startFailed by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }
    // Set when the user disconnects the last tab, so the effect below does not
    // resurrect the session that was just closed.
    var exiting by remember { mutableStateOf(false) }

    // Opening a tab from the list, or switching to a dead tab, is an intent to (re)connect.
    LaunchedEffect(currentId, retryToken) {
        if (exiting) return@LaunchedEffect
        startFailed = null
        val existing = app.sessions.sessionFor(currentId)
        val finished = existing != null &&
            (existing.state.value is SessionState.Failed || existing.state.value == SessionState.Closed)
        if (existing == null || finished) {
            val profile = app.profiles.find(currentId)
            if (profile == null) {
                startFailed = "接続先が見つかりません"
                return@LaunchedEffect
            }
            app.sessions.start(profile, app.password(currentId))
        }
    }

    fun closeTab(id: String) {
        app.sessions.close(id)
        if (id == currentId) {
            val remaining = app.sessions.sessions.value.firstOrNull()
            if (remaining != null) {
                currentId = remaining.profile.id
            } else {
                exiting = true
                windowState.placement = WindowPlacement.Floating
                onExit()
            }
        }
    }

    val currentSession = sessions.find { it.profile.id == currentId }
    val state = currentSession?.state?.collectAsState()?.value
    var chromeVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            startFailed != null -> ErrorPane(startFailed!!, onRetry = null, onExit = onExit)
            currentSession == null || state == SessionState.Connecting ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            state is SessionState.Failed -> ErrorPane(
                state.message,
                onRetry = { app.sessions.close(currentId); retryToken++ },
                onExit = { closeTab(currentId) },
            )
            state == SessionState.Closed -> ErrorPane(
                "切断されました",
                onRetry = { app.sessions.close(currentId); retryToken++ },
                onExit = { closeTab(currentId) },
            )
            state is SessionState.Connected -> ConnectedContent(
                app = app,
                session = currentSession,
                windowState = windowState,
                chromeVisible = chromeVisible,
                onChromeVisibleChange = { chromeVisible = it },
                onBackToList = onExit,
                onDisconnect = { closeTab(currentId) },
            )
        }

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
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (connected) 68.dp else 8.dp),
            )
        }
    }
}

@Composable
private fun SessionTabs(
    sessions: List<VncSession>,
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
            val image by session.image.collectAsState()
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChromeBackground)
                    .border(1.dp, if (selected) AppColors.Accent else ChromeOutline, RoundedCornerShape(14.dp))
                    .clickable { onSelect(id) }
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray)) {
                    image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                }
                Column {
                    Text(session.profile.displayName(), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(
                        when (state) {
                            is SessionState.Connected -> if (selected) "● 表示中" else "● 接続中"
                            SessionState.Connecting -> "接続中…"
                            else -> "切断"
                        },
                        color = if (state is SessionState.Connected) AppColors.Online else AppColors.Subtle,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "✕",
                    color = AppColors.Subtle,
                    modifier = Modifier.clip(CircleShape).clickable { onCloseTab(id) }.padding(6.dp),
                )
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

/** Where the remote screen sits in the view: scale plus top-left offset, in pixels. */
private data class Placement(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * Fit: the whole screen, letterboxed. 1:1: real pixels; when the remote is
 * larger than the view, the view pans with the mouse so every edge is reachable.
 */
private fun placement(image: ImageBitmap, view: IntSize, fit: Boolean, mouse: Offset): Placement {
    val rw = image.width.toFloat()
    val rh = image.height.toFloat()
    val vw = view.width.toFloat()
    val vh = view.height.toFloat()
    if (fit) {
        val s = min(vw / rw, vh / rh)
        return Placement(s, (vw - rw * s) / 2, (vh - rh * s) / 2)
    }
    fun axis(remote: Float, viewLen: Float, pos: Float) =
        if (remote <= viewLen) (viewLen - remote) / 2 else -(pos / viewLen).coerceIn(0f, 1f) * (remote - viewLen)
    return Placement(1f, axis(rw, vw, mouse.x), axis(rh, vh, mouse.y))
}

@Composable
private fun ConnectedContent(
    app: AppState,
    session: VncSession,
    windowState: WindowState,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    onBackToList: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val settings by app.settings.settings.collectAsState()
    val imageState = session.image.collectAsState()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var mouse by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Clipboard sync, over SSH (see VncSession): the PC's clipboard goes to
    // the Mac when the window comes to the front and before every ⌘V; the
    // Mac's comes back after ⌘C/⌘X and when the window goes to the back.
    val clipboard = remember { ClipboardBridge() }
    val clipboardError: (String) -> Unit = remember(session) {
        { message -> app.notify("Mac とクリップボードを共有できません（「リモートログイン」が必要です）: $message") }
    }
    fun pushLocal() {
        clipboard.takeLocalChange()?.let { session.setMacClipboard(it, clipboardError) }
    }
    fun pullRemote(delayMillis: Long = 0) {
        scope.launch {
            delay(delayMillis)
            session.macClipboard(clipboardError)?.takeIf { it.isNotEmpty() }?.let { clipboard.setFromRemote(it) }
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(session, windowFocused) { if (windowFocused) pushLocal() else pullRemote() }
    // Mac → PC also covers servers that do send ServerCutText (not macOS).
    LaunchedEffect(session) {
        session.serverClipboard.collect { text -> if (!text.isNullOrEmpty()) clipboard.setFromRemote(text) }
    }

    val translator = remember(session) {
        lateinit var translator: KeyTranslator
        translator = KeyTranslator { keysym, down ->
            if (down && translator.isHeld(Keysyms.SUPER_L)) {
                when (keysym) {
                    'v'.code -> pushLocal()
                    // Give the Mac a moment to update its clipboard.
                    'c'.code, 'x'.code -> pullRemote(delayMillis = 400)
                }
            }
            session.key(keysym, down)
        }
        translator
    }
    SideEffect { translator.ctrlAsCommand = settings.ctrlAsCommand }
    var menuOpen by remember { mutableStateOf(false) }
    var textDialogOpen by remember { mutableStateOf(false) }
    val toolbarHover = remember { MutableInteractionSource() }
    val toolbarHovered by toolbarHover.collectIsHoveredAsState()
    val handleHover = remember { MutableInteractionSource() }
    val handleHovered by handleHover.collectIsHoveredAsState()
    val fullscreen = windowState.placement == WindowPlacement.Fullscreen

    LaunchedEffect(session) { focus.requestFocus() }

    // The toolbar hides itself a moment after the mouse leaves it; hovering
    // the handle at the top edge brings it back.
    LaunchedEffect(chromeVisible, toolbarHovered, menuOpen) {
        if (chromeVisible && !toolbarHovered && !menuOpen) {
            delay(CHROME_AUTO_HIDE_MILLIS)
            onChromeVisibleChange(false)
        }
    }
    LaunchedEffect(handleHovered) { if (handleHovered) onChromeVisibleChange(true) }

    fun pasteLocal() {
        val text = clipboard.read()
        if (text.isNullOrEmpty()) app.notify("PC のクリップボードに文字がありません") else session.paste(text, clipboardError)
        focus.requestFocus()
    }

    fun macDisplayAction(done: String, action: suspend (Profile, String) -> Unit) {
        val profile = session.profile
        if (profile.username.isEmpty()) {
            app.notify("解像度の変更には Mac のユーザ名入りの接続先が必要です")
            return
        }
        app.scope.launch {
            runCatching { action(profile, app.password(profile.id)) }
                .onSuccess { app.notify(done) }
                .onFailure { app.notify("解像度の変更に失敗: ${it.message}") }
        }
    }

    if (textDialogOpen) {
        TextSendDialog(
            onSend = { text -> textDialogOpen = false; session.text(text); focus.requestFocus() },
            onDismiss = { textDialogOpen = false; focus.requestFocus() },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .onPreviewKeyEvent { event ->
                    val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return@onPreviewKeyEvent false
                    translator.onKey(awt)
                }
                .onFocusChanged { if (!it.isFocused) translator.releaseAll() }
                .focusRequester(focus)
                .focusable()
                .pointerInput(session) {
                    var scrollY = 0f
                    var scrollX = 0f
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val image = imageState.value ?: continue
                            // The delegated states are read live: this block is not restarted on recomposition.
                            if (!settings.fitToWindow) mouse = change.position
                            val p = placement(image, viewSize, settings.fitToWindow, mouse)
                            val x = ((change.position.x - p.offsetX) / p.scale).toInt().coerceIn(0, image.width - 1)
                            val y = ((change.position.y - p.offsetY) / p.scale).toInt().coerceIn(0, image.height - 1)
                            var mask = 0
                            if (event.buttons.isPrimaryPressed) mask = mask or PointerButtons.LEFT
                            if (event.buttons.isTertiaryPressed) mask = mask or PointerButtons.MIDDLE
                            if (event.buttons.isSecondaryPressed) mask = mask or PointerButtons.RIGHT
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    // Wheel notches are ±1; touchpads send fractions, so accumulate.
                                    scrollY += change.scrollDelta.y
                                    scrollX += change.scrollDelta.x
                                    while (abs(scrollY) >= 1f) {
                                        val button = if (scrollY > 0) PointerButtons.WHEEL_DOWN else PointerButtons.WHEEL_UP
                                        session.pointer(mask or button, x, y)
                                        session.pointer(mask, x, y)
                                        scrollY -= sign(scrollY)
                                    }
                                    while (abs(scrollX) >= 1f) {
                                        val button = if (scrollX > 0) WHEEL_RIGHT else WHEEL_LEFT
                                        session.pointer(mask or button, x, y)
                                        session.pointer(mask, x, y)
                                        scrollX -= sign(scrollX)
                                    }
                                }
                                PointerEventType.Press -> {
                                    focus.requestFocus()
                                    session.pointer(mask, x, y)
                                }
                                else -> session.pointer(mask, x, y)
                            }
                            change.consume()
                        }
                    }
                },
        ) {
            val image = imageState.value ?: return@Canvas
            val p = placement(image, IntSize(size.width.toInt(), size.height.toInt()), settings.fitToWindow, mouse)
            drawImage(
                image,
                dstOffset = IntOffset(p.offsetX.roundToInt(), p.offsetY.roundToInt()),
                dstSize = IntSize((image.width * p.scale).roundToInt(), (image.height * p.scale).roundToInt()),
                filterQuality = if (p.scale == 1f) FilterQuality.None else FilterQuality.Medium,
            )
        }

        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .hoverable(toolbarHover)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ChromeBackground)
                    .border(1.dp, ChromeOutline, RoundedCornerShape(22.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarButton("▦", "一覧") { onBackToList() }
                ToolbarButton("⤢", if (settings.fitToWindow) "全体表示" else "等倍") {
                    app.settings.update { it.copy(fitToWindow = !it.fitToWindow) }
                    focus.requestFocus()
                }
                ToolbarButton("⛶", if (fullscreen) "全画面解除" else "全画面", selected = fullscreen) {
                    windowState.placement = if (fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    focus.requestFocus()
                }
                ToolbarButton("⎘", "貼り付け") { pasteLocal() }
                ToolbarButton("あ", "文字入力") { textDialogOpen = true }
                Box {
                    ToolbarButton("⋯", "その他", selected = menuOpen) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; focus.requestFocus() }) {
                        val choose: (() -> Unit) -> () -> Unit = { action -> { menuOpen = false; action(); focus.requestFocus() } }
                        MenuItem("入力ソース切替 (⌃Space)", choose { session.keyPress(' '.code, listOf(Keysyms.CONTROL_L)) })
                        MenuItem("Mission Control (⌃↑)", choose { session.keyPress(Keysyms.UP, listOf(Keysyms.CONTROL_L)) })
                        MenuItem("Spotlight (⌘Space)", choose { session.keyPress(' '.code, listOf(Keysyms.SUPER_L)) })
                        MenuItem("強制終了 (⌘⌥Esc)", choose { session.keyPress(Keysyms.ESCAPE, listOf(Keysyms.SUPER_L, Keysyms.ALT_L)) })
                        HorizontalDivider()
                        MenuItem(
                            if (settings.ctrlAsCommand) "✓ Ctrl を ⌘ として送る" else "　 Ctrl を ⌘ として送る（オフ: Alt が ⌘）",
                            choose { app.settings.update { it.copy(ctrlAsCommand = !it.ctrlAsCommand) } },
                        )
                        HorizontalDivider()
                        MenuItem("ファイル転送", choose { app.openFiles(session.profile) })
                        MenuItem("ターミナル (SSH)", choose { app.openTerminal(session.profile) })
                        HorizontalDivider()
                        MenuItem("Mac を Full HD に変更", choose {
                            macDisplayAction("Full HD に変更しました") { profile, password ->
                                val restore = MacDisplay.setFullHd(profile.host, profile.username, password)
                                // Keep the very first original: a second switch must not overwrite it.
                                app.settings.update {
                                    if (profile.id in it.displayRestore) it
                                    else it.copy(displayRestore = it.displayRestore + (profile.id to restore))
                                }
                            }
                        })
                        MenuItem("Mac の解像度を元に戻す", choose {
                            macDisplayAction("元の解像度に戻しました") { profile, password ->
                                val restore = app.settings.settings.value.displayRestore[profile.id]
                                    ?: error("元の解像度が記録されていません")
                                MacDisplay.restore(profile.host, profile.username, password, restore)
                                app.settings.update { it.copy(displayRestore = it.displayRestore - profile.id) }
                            }
                        })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("切断", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDisconnect() },
                        )
                    }
                }
            }
        } else {
            // A slim handle brings the toolbar back on hover or click.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 160.dp, height = 14.dp)
                    .hoverable(handleHover)
                    .clickable { onChromeVisibleChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 56.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
            }
        }
    }
}

/** Types text into the Mac as Unicode key events — the way to send Japanese typed with the PC's IME. */
@Composable
private fun TextSendDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文字を送信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("入力した文字を Mac のカーソル位置に打ち込みます（日本語可）。", color = AppColors.Subtle)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { Button(onClick = { onSend(text) }, enabled = text.isNotEmpty()) { Text("送信") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** The PC's clipboard, remembering what was last exchanged so changes are not echoed back. */
private class ClipboardBridge {
    private val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    private var last: String? = null

    fun read(): String? = runCatching {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    fun setFromRemote(text: String) {
        last = text
        runCatching { clipboard.setContents(StringSelection(text), null) }
    }

    /** The local clipboard text if it changed since the last exchange (the first call always reports it). */
    fun takeLocalChange(): String? {
        val text = read() ?: return null
        if (text == last) return null
        last = text
        return text
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

/** An icon-and-caption button in the floating toolbar. */
@Composable
private fun ToolbarButton(icon: String, caption: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AppColors.Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .widthIn(min = 60.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(caption, color = if (selected) Color.White else AppColors.Subtle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: (() -> Unit)?, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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

private val ChromeBackground = Color(0xE6141820)
private val ChromeOutline = Color(0x33FFFFFF)
private const val CHROME_AUTO_HIDE_MILLIS = 2_500L

/** RFB buttons 6 and 7: horizontal scrolling. */
private const val WHEEL_LEFT = 32
private const val WHEEL_RIGHT = 64
