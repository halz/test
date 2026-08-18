package io.github.halz.macremote.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.AppSettings
import io.github.halz.macremote.data.GestureAction
import io.github.halz.macremote.data.GestureTrigger
import kotlinx.coroutines.launch

/** Binds each multi-touch gesture to the command it should send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gestures by settings.gestures.collectAsState(initial = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ジェスチャ設定") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
                actions = {
                    TextButton(onClick = { scope.launch { settings.resetGestures() } }) { Text("既定に戻す") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            item(key = "header") {
                Text(
                    "画面を 2〜4 本指で操作したときに Mac へ送るコマンドを選べます。" +
                        "1 本指の操作 (タップ・ドラッグ・長押し) と 2 本指のスクロール／ズームは固定です。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
            }
            items(GestureTrigger.entries, key = { it.name }) { trigger ->
                GestureRow(
                    trigger = trigger,
                    action = gestures[trigger] ?: trigger.default,
                    onSelect = { scope.launch { settings.setGesture(trigger, it) } },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun GestureRow(
    trigger: GestureTrigger,
    action: GestureAction,
    onSelect: (GestureAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(trigger.label, style = MaterialTheme.typography.bodyLarge)
            Text(action.label, style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            GestureAction.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == action) "✓ ${option.label}" else option.label) },
                    onClick = {
                        menuOpen = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
