package io.github.halz.macremote.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File
import java.util.Vector
import kotlin.concurrent.thread

private data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * SFTP file browser for one profile, in its own window: click a folder
 * to open it, a file to download it; "アップロード" sends PC files to the
 * folder shown. Host keys are not pinned — the transport is the Tailscale tunnel.
 */
@Composable
fun FilesWindow(profile: Profile, password: String, onClose: () -> Unit) {
    Window(
        onCloseRequest = onClose,
        title = "ファイル転送 — ${profile.displayName()}",
        state = rememberWindowState(size = DpSize(760.dp, 640.dp)),
    ) {
        MacRemoteTheme {
            val scope = rememberCoroutineScope()
            var error by remember { mutableStateOf<String?>(null) }
            var status by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(true) }
            var currentPath by remember { mutableStateOf("") }
            var entries by remember { mutableStateOf(listOf<RemoteEntry>()) }
            val connection = remember { SftpConnection() }

            fun joinPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

            suspend fun open(path: String) {
                entries = connection.list(path)
                currentPath = path
            }

            fun perform(label: String, block: suspend () -> String) {
                scope.launch {
                    busy = true
                    status = label
                    status = runCatching { block() }.getOrElse { "失敗: ${it.message}" }
                    busy = false
                }
            }

            LaunchedEffect(Unit) {
                try {
                    withContext(Dispatchers.IO) { connection.connect(profile, password) }
                    open(connection.home())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val hint = when (profile.protocol) {
                        Protocol.VNC -> "Mac の「システム設定 → 一般 → 共有 → リモートログイン」がオンか確認してください"
                        Protocol.RDP -> "Windows の「設定 → システム → オプション機能」で OpenSSH サーバーを追加し、サービス sshd を開始しているか確認してください"
                    }
                    error = "接続できませんでした: ${e.message}\n\n$hint"
                }
                busy = false
            }
            DisposableEffect(Unit) { onDispose { connection.close() } }

            fun download(entry: RemoteEntry) {
                val dialog = FileDialog(window, "保存先を選択", FileDialog.SAVE).apply {
                    file = entry.name
                    isVisible = true
                }
                val name = dialog.file ?: return
                val target = File(dialog.directory, name)
                perform("${entry.name} をダウンロード中…") {
                    connection.download(joinPath(currentPath, entry.name), target)
                    "${target.name} を保存しました"
                }
            }

            fun upload() {
                val dialog = FileDialog(window, "送信するファイルを選択", FileDialog.LOAD).apply {
                    isMultipleMode = true
                    isVisible = true
                }
                val files = dialog.files.toList()
                if (files.isEmpty()) return
                val dir = currentPath
                perform("${files.size}件を送信中…") {
                    files.forEach { connection.upload(it, joinPath(dir, it.name)) }
                    open(dir)
                    "${files.size}件を送信しました"
                }
            }

            Column(Modifier.fillMaxSize().background(AppColors.Background)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconTile("↑") {
                        val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
                        if (currentPath != "/") perform("開いています…") { open(parent); "" }
                    }
                    Text(currentPath, color = AppColors.Text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (connection.isOpen) AppColors.Accent else AppColors.SurfaceHigh)
                            .clickable(enabled = connection.isOpen && !busy) { upload() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) { Text("アップロード", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                HorizontalDivider(color = AppColors.Outline)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val message = error
                    when {
                        message != null -> Text(message, color = AppColors.Text, modifier = Modifier.align(Alignment.Center).padding(32.dp))
                        currentPath.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(entries, key = { it.name }) { entry ->
                                EntryRow(entry) {
                                    if (busy) return@EntryRow
                                    if (entry.isDirectory) {
                                        perform("開いています…") { open(joinPath(currentPath, entry.name)); "" }
                                    } else {
                                        download(entry)
                                    }
                                }
                            }
                        }
                    }
                }
                status?.takeIf { it.isNotEmpty() }?.let {
                    HorizontalDivider(color = AppColors.Outline)
                    Text(it, color = AppColors.Subtle, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.name, color = AppColors.Text, fontSize = 14.sp, maxLines = 1)
        if (!entry.isDirectory) Text(formatSize(entry.size), color = AppColors.Subtle, fontSize = 12.sp)
    }
}

/** One SFTP session. ChannelSftp is not thread-safe, so every operation is serialized. */
private class SftpConnection {
    private val mutex = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelSftp? = null

    val isOpen: Boolean get() = channel != null

    fun connect(profile: Profile, password: String) {
        val s = JSch().getSession(profile.username, profile.host, 22)
        try {
            s.setPassword(password.toByteArray(Charsets.UTF_8))
            s.setConfig("StrictHostKeyChecking", "no")
            s.timeout = 15_000
            s.connect()
            val c = s.openChannel("sftp") as ChannelSftp
            c.connect()
            session = s
            channel = c
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            throw e
        }
    }

    private suspend fun <T> withChannel(block: (ChannelSftp) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block(channel ?: error("接続されていません")) }
    }

    suspend fun home(): String = withChannel { it.home }

    suspend fun list(path: String): List<RemoteEntry> = withChannel { c ->
        @Suppress("UNCHECKED_CAST")
        (c.ls(path) as Vector<ChannelSftp.LsEntry>)
            .filter { it.filename != "." && it.filename != ".." }
            .map { RemoteEntry(it.filename, it.attrs.isDir, it.attrs.size) }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun download(remotePath: String, target: File) = withChannel { c ->
        target.outputStream().use { out -> c.get(remotePath).use { it.copyTo(out) } }
    }

    suspend fun upload(source: File, remotePath: String) = withChannel { c ->
        source.inputStream().use { c.put(it, remotePath) }
    }

    fun close() {
        val c = channel
        val s = session
        channel = null
        session = null
        thread {
            runCatching { c?.disconnect() }
            runCatching { s?.disconnect() }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
