package io.github.halz.macremote.ui.profiles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.ProfileTransfer
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved connections. GridCells.Adaptive gives one column on the Fold's narrow
 * cover screen and two on the near-square inner screen without explicit breakpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    repository: ProfileRepository,
    secretStore: SecretStore,
    sessionHolder: SessionHolder,
    onConnect: (Profile) -> Unit,
    onEdit: (String?) -> Unit,
    onOpenGestureSettings: () -> Unit,
) {
    val profiles by repository.profiles.collectAsState(initial = emptyList())
    val sessions by sessionHolder.sessions.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val content = ProfileTransfer.export(repository, secretStore)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                        ?: error("書き込み先を開けませんでした")
                }
            }.onSuccess { toast("エクスポートしました (パスワードを含みます。取り扱いに注意)") }
                .onFailure { toast("エクスポート失敗: ${it.message}") }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("ファイルを開けませんでした")
                }
                ProfileTransfer.import(content, repository, secretStore)
            }.onSuccess { count -> toast("${count}件の接続先をインポートしました") }
                .onFailure { toast("インポート失敗: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接続先") },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("設定") },
                                onClick = {
                                    menuOpen = false
                                    onOpenGestureSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("設定をエクスポート") },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("macremote-profiles.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("設定をインポート") },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) { Text("＋") }
        },
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("接続先がまだありません", style = MaterialTheme.typography.titleMedium)
                Text(
                    "＋ から Mac の Tailscale 名 (例: my-mac.tailnet.ts.net) を登録してください",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val connected = sessions.any {
                        it.profile.id == profile.id && it.state.value is SessionState.Connected
                    }
                    ProfileCard(
                        profile,
                        connected = connected,
                        onConnect = { onConnect(profile) },
                        onEdit = { onEdit(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    connected: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(onClick = onConnect) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                profile.name.ifEmpty { profile.host } + if (connected) "　● 接続中" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (connected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(
                "${profile.host}:${profile.port}" +
                    if (profile.username.isNotEmpty()) "（${profile.username}）" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) { Text("編集") }
        }
    }
}
