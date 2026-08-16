package io.github.halz.macremote.ui.files

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Vector

private data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * SFTP file browser for the profile's Mac. Requires "Remote Login" (SSH)
 * enabled on the Mac and a profile with a username; authentication reuses the
 * profile's password. Host keys are not pinned — the expected transport is a
 * Tailscale tunnel (see README).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(
    profileId: String,
    repository: ProfileRepository,
    secretStore: SecretStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(listOf<RemoteEntry>()) }
    var sftp by remember { mutableStateOf<Pair<Session, ChannelSftp>?>(null) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    suspend fun refresh(channel: ChannelSftp, path: String) {
        val listed = withContext(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            (channel.ls(path) as Vector<ChannelSftp.LsEntry>)
                .filter { it.filename != "." && it.filename != ".." }
                .map { RemoteEntry(it.filename, it.attrs.isDir, it.attrs.size) }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        entries = listed
        currentPath = path
    }

    LaunchedEffect(profileId) {
        loading = true
        errorMessage = null
        runCatching {
            val profile = repository.find(profileId) ?: error("接続先が見つかりません")
            if (profile.username.isEmpty()) {
                error(
                    "ファイル転送には Mac のユーザ名入りプロファイルが必要です。" +
                        "接続先設定でユーザ名を入力してください"
                )
            }
            val password = secretStore.loadPassword(profileId).orEmpty()
            val connected = withContext(Dispatchers.IO) {
                val session = JSch().getSession(profile.username, profile.host, 22)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                session.timeout = 15_000
                session.connect()
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()
                session to channel
            }
            sftp = connected
            refresh(connected.second, withContext(Dispatchers.IO) { connected.second.home })
        }.onFailure {
            errorMessage = "接続できませんでした: ${it.message}\n\n" +
                "Mac の「システム設定 → 一般 → 共有 → リモートログイン」がオンか確認してください"
        }
        loading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            val connection = sftp
            Thread {
                runCatching {
                    connection?.second?.disconnect()
                    connection?.first?.disconnect()
                }
            }.start()
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val remotePath = pendingDownload
        pendingDownload = null
        if (uri == null || remotePath == null) return@rememberLauncherForActivityResult
        val channel = sftp?.second ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        channel.get(remotePath).use { input -> input.copyTo(out) }
                    } ?: error("保存先を開けませんでした")
                }
            }.onSuccess { toast("ダウンロードしました") }
                .onFailure { toast("ダウンロード失敗: ${it.message}") }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val channel = sftp?.second ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                } ?: "upload-${System.currentTimeMillis()}"
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        channel.put(input, "$currentPath/$name")
                    } ?: error("ファイルを開けませんでした")
                }
                refresh(channel, currentPath)
                name
            }.onSuccess { toast("$it を送信しました") }
                .onFailure { toast("送信失敗: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ファイル", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
        floatingActionButton = {
            if (sftp != null) {
                FloatingActionButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Text("↑") }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                errorMessage != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Text(
                        currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (currentPath != "/") {
                            item(key = "..") {
                                EntryRow(RemoteEntry("..", true, 0)) {
                                    scope.launch {
                                        val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
                                        sftp?.second?.let {
                                            runCatching { refresh(it, parent) }
                                                .onFailure { e -> toast("開けません: ${e.message}") }
                                        }
                                    }
                                }
                            }
                        }
                        items(entries, key = { it.name }) { entry ->
                            EntryRow(entry) {
                                val path = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
                                if (entry.isDirectory) {
                                    scope.launch {
                                        sftp?.second?.let {
                                            runCatching { refresh(it, path) }
                                                .onFailure { e -> toast("開けません: ${e.message}") }
                                        }
                                    }
                                } else {
                                    pendingDownload = path
                                    downloadLauncher.launch(entry.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (entry.isDirectory) "📁 " else "📄 ") + entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        if (!entry.isDirectory) {
            Text(formatSize(entry.size), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
