package io.github.halz.macremote.ui.terminal

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.session.ShellHolder
import io.github.halz.macremote.session.ShellState
import io.github.halz.macremote.session.SshShell
import io.github.halz.macremote.ui.theme.AppColors
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * An SSH terminal on the profile's computer. Pinch to change the font size;
 * the key bar adds keys a phone keyboard lacks (Esc, Tab, Ctrl, arrows...).
 */
@Composable
fun TerminalScreen(
    profileId: String,
    repository: ProfileRepository,
    secretStore: SecretStore,
    shellHolder: ShellHolder,
    onBack: () -> Unit,
) {
    var shell by remember { mutableStateOf(shellHolder.get(profileId)) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var reconnect by remember { mutableIntStateOf(0) }

    LaunchedEffect(profileId, reconnect) {
        val existing = shellHolder.get(profileId)
        if (reconnect == 0 && existing != null &&
            (existing.state.value == ShellState.Connected || existing.state.value == ShellState.Connecting)
        ) {
            shell = existing
            return@LaunchedEffect
        }
        val profile = repository.find(profileId)
        when {
            profile == null -> setupError = "接続先が見つかりません"
            profile.username.isEmpty() -> setupError = "ターミナルには接続先のユーザ名が必要です（編集から設定してください）"
            else -> {
                if (reconnect > 0) shellHolder.close(profileId)
                shell = shellHolder.open(profile, secretStore.loadPassword(profileId).orEmpty())
            }
        }
    }

    fun exit() {
        shellHolder.close(profileId)
        onBack()
    }
    BackHandler { exit() }

    val current = shell
    val state = current?.state?.collectAsState()?.value

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.SurfaceHigh).clickable { exit() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = AppColors.Text) }
            Column(Modifier.weight(1f)) {
                Text(
                    current?.profile?.let { it.name.ifEmpty { it.host } } ?: "ターミナル",
                    color = AppColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                )
                Text(
                    when (state) {
                        ShellState.Connected -> "● SSH 接続中 · ${current?.profile?.username}@${current?.profile?.host}"
                        ShellState.Connecting -> "接続中…"
                        is ShellState.Failed, ShellState.Closed -> "切断"
                        null -> ""
                    },
                    color = if (state == ShellState.Connected) AppColors.Online else AppColors.Subtle,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                setupError != null -> Message(setupError!!, onRetry = null)
                current == null || state == ShellState.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state is ShellState.Failed -> Message(state.message) { reconnect++ }
                state == ShellState.Closed -> Message("接続が終了しました") { reconnect++ }
                else -> current?.let { TerminalPane(it) }
            }
        }
    }
}

@Composable
private fun TerminalPane(shell: SshShell) {
    val modifiers = remember(shell) { StickyModifiers() }
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    var keyboardShown by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = shell.emulator,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            typeface = Typeface.MONOSPACE,
            backgroundColor = AppColors.Background,
            foregroundColor = AppColors.Text,
            keyboardEnabled = true,
            showSoftKeyboard = keyboardShown,
            focusRequester = focusRequester,
            onImeVisibilityChanged = { keyboardShown = it },
            modifierManager = modifiers,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fun key(code: Int) {
                shell.emulator.dispatchKey(modifiers.mask(), code)
                modifiers.clearTransients()
            }
            fun char(c: Char) {
                shell.emulator.dispatchCharacter(modifiers.mask(), c.code)
                modifiers.clearTransients()
            }
            BarKey(if (keyboardShown) "⌨ ▼" else "⌨", selected = keyboardShown) { keyboardShown = !keyboardShown }
            BarKey("Esc") { key(VTermKey.ESCAPE) }
            BarKey("Tab") { key(VTermKey.TAB) }
            BarKey("Ctrl", selected = modifiers.ctrl == 1, locked = modifiers.ctrl == 2) { modifiers.ctrl = modifiers.next(modifiers.ctrl) }
            BarKey("Alt", selected = modifiers.alt == 1, locked = modifiers.alt == 2) { modifiers.alt = modifiers.next(modifiers.alt) }
            BarKey("←") { key(VTermKey.LEFT) }
            BarKey("↑") { key(VTermKey.UP) }
            BarKey("↓") { key(VTermKey.DOWN) }
            BarKey("→") { key(VTermKey.RIGHT) }
            listOf('|', '/', '-', '~', '_').forEach { c -> BarKey(c.toString()) { char(c) } }
            BarKey("Home") { key(VTermKey.HOME) }
            BarKey("End") { key(VTermKey.END) }
            BarKey("PgUp") { key(VTermKey.PAGEUP) }
            BarKey("PgDn") { key(VTermKey.PAGEDOWN) }
            BarKey("貼付") { clipboard.getText()?.text?.let { shell.emulator.pasteText(it) } }
        }
    }
}

/** Ctrl/Alt from the key bar: 0 off, 1 next key only, 2 locked. */
private class StickyModifiers : ModifierManager {
    var ctrl by mutableIntStateOf(0)
    var alt by mutableIntStateOf(0)

    fun next(value: Int) = (value + 1) % 3

    fun mask(): Int = (if (alt > 0) 2 else 0) or (if (ctrl > 0) 4 else 0)

    override fun isCtrlActive() = ctrl > 0
    override fun isAltActive() = alt > 0
    override fun isShiftActive() = false

    override fun clearTransients() {
        if (ctrl == 1) ctrl = 0
        if (alt == 1) alt = 0
    }
}

@Composable
private fun BarKey(label: String, selected: Boolean = false, locked: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (locked) AppColors.Accent else AppColors.SurfaceHigh)
            .border(1.5.dp, if (selected) AppColors.Accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .widthIn(min = 42.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = AppColors.Text, fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = AppColors.Text, fontSize = 15.sp)
        if (onRetry != null) Button(onClick = onRetry) { Text("再接続") }
    }
}
