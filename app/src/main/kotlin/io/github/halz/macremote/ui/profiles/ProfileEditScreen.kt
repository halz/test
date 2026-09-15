package io.github.halz.macremote.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: String?,
    repository: ProfileRepository,
    secretStore: SecretStore,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5900") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(profileId == null) }

    LaunchedEffect(profileId) {
        if (profileId != null) {
            repository.find(profileId)?.let { existing ->
                name = existing.name
                host = existing.host
                port = existing.port.toString()
                username = existing.username
                password = secretStore.loadPassword(profileId).orEmpty()
            }
            loaded = true
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (profileId == null) "接続先を追加" else "接続先を編集") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fieldModifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            OutlinedTextField(name, { name = it }, fieldModifier, label = { Text("名前") }, singleLine = true)
            OutlinedTextField(
                host, { host = it }, fieldModifier,
                label = { Text("ホスト (Tailscale名 / 100.x.x.x)") }, singleLine = true,
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit).take(5) }, fieldModifier,
                label = { Text("ポート") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                username, { username = it }, fieldModifier,
                label = { Text("ユーザ名 (Macログインで入る場合)") }, singleLine = true,
            )
            OutlinedTextField(
                password, { password = it }, fieldModifier,
                label = { Text("パスワード") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDone) { Text("キャンセル") }
                if (profileId != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            repository.delete(profileId)
                            secretStore.deletePassword(profileId)
                            onDone()
                        }
                    }) { Text("削除") }
                }
                Button(
                    enabled = loaded && host.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val id = profileId ?: UUID.randomUUID().toString()
                            repository.upsert(
                                Profile(
                                    id = id,
                                    name = name.trim(),
                                    host = host.trim(),
                                    port = port.toIntOrNull() ?: 5900,
                                    username = username.trim(),
                                )
                            )
                            secretStore.savePassword(id, password)
                            onDone()
                        }
                    },
                ) { Text("保存") }
            }
        }
    }
}
