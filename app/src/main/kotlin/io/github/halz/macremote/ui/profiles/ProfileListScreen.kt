package io.github.halz.macremote.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository

/**
 * Saved connections. GridCells.Adaptive gives one column on the Fold's narrow
 * cover screen and two on the near-square inner screen without explicit breakpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    repository: ProfileRepository,
    onConnect: (Profile) -> Unit,
    onEdit: (String?) -> Unit,
) {
    val profiles by repository.profiles.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("接続先") }) },
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
                    ProfileCard(profile, onConnect = { onConnect(profile) }, onEdit = { onEdit(profile.id) })
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, onConnect: () -> Unit, onEdit: () -> Unit) {
    Card(onClick = onConnect) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(profile.name.ifEmpty { profile.host }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${profile.host}:${profile.port}" +
                    if (profile.username.isNotEmpty()) "（${profile.username}）" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) { Text("編集") }
        }
    }
}
