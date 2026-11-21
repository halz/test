package io.github.halz.macremote.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.data.RemoteOs
import io.github.halz.macremote.data.platform
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.session.Thumbnails
import io.github.halz.macremote.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Add or edit a connection: the OS first (it decides protocol, port and the
 * setup hint), then the address and login in grouped fields, with the port
 * tucked away under "詳細設定".
 */
@Composable
fun ProfileEditScreen(
    profileId: String?,
    repository: ProfileRepository,
    secretStore: SecretStore,
    onDone: () -> Unit,
    onConnect: (Profile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5900") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var os by remember { mutableStateOf(RemoteOs.MAC) }
    // Only Linux lets the user pick; Mac is always VNC and Windows RDP.
    var protocol by remember { mutableStateOf(Protocol.VNC) }
    var loaded by remember { mutableStateOf(profileId == null) }
    var advancedOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId != null) {
            repository.find(profileId)?.let { existing ->
                name = existing.name
                host = existing.host
                port = existing.port.toString()
                username = existing.username
                protocol = existing.protocol
                os = existing.platform
                password = secretStore.loadPassword(profileId).orEmpty()
            }
            loaded = true
        }
    }

    fun save(then: (Profile) -> Unit) {
        scope.launch {
            val profile = Profile(
                id = profileId ?: UUID.randomUUID().toString(),
                name = name.trim(),
                host = host.trim(),
                port = port.toIntOrNull() ?: protocol.defaultPort,
                username = username.trim(),
                protocol = protocol,
                os = os,
            )
            repository.upsert(profile)
            secretStore.savePassword(profile.id, password)
            then(profile)
        }
    }

    if (confirmDelete && profileId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この接続先を削除しますか？") },
            text = { Text(name.ifEmpty { host }) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.delete(profileId)
                        secretStore.deletePassword(profileId)
                        Thumbnails.delete(context, profileId)
                        onDone()
                    }
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }

    val canSave = loaded && host.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.SurfaceHigh).clickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = AppColors.Text) }
            Text(
                if (profileId == null) "接続先を追加" else "接続先を編集",
                color = AppColors.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (profileId != null) {
                TextButton(onClick = { confirmDelete = true }) { Text("削除", color = AppColors.Danger) }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fun selectProtocol(next: Protocol) {
                    // Follow the default port unless the user typed a custom one.
                    if (port == protocol.defaultPort.toString()) port = next.defaultPort.toString()
                    protocol = next
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteOs.entries.forEach { option ->
                        OsOption(
                            option = option,
                            selected = os == option,
                            modifier = Modifier.weight(1f),
                        ) {
                            os = option
                            selectProtocol(
                                when (option) {
                                    RemoteOs.MAC -> Protocol.VNC
                                    RemoteOs.WINDOWS -> Protocol.RDP
                                    // GNOME Remote Desktop and xrdp speak RDP.
                                    RemoteOs.LINUX -> Protocol.RDP
                                }
                            )
                        }
                    }
                }
                if (os == RemoteOs.LINUX) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Protocol.RDP to "RDP（GNOME / xrdp）",
                            Protocol.VNC to "VNC",
                        ).forEach { (option, label) ->
                            val on = protocol == option
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) AppColors.Accent.copy(alpha = 0.14f) else AppColors.Surface)
                                    .border(1.dp, if (on) AppColors.Accent else AppColors.Outline, RoundedCornerShape(12.dp))
                                    .clickable { selectProtocol(option) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$label · ${option.defaultPort}",
                                    color = if (on) AppColors.Text else AppColors.Subtle,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                FieldGroup("接続先") {
                    Field(
                        "名前", name, { name = it },
                        placeholder = when (os) {
                            RemoteOs.MAC -> "MacBook Pro"
                            RemoteOs.WINDOWS -> "自宅の PC"
                            RemoteOs.LINUX -> "Ubuntu サーバー"
                        },
                    )
                    HorizontalDivider(color = AppColors.Outline)
                    Field("ホスト（Tailscale 名 / 100.x.x.x）", host, { host = it.trim() }, placeholder = "my-pc.tailnet.ts.net", keyboardType = KeyboardType.Uri)
                }

                FieldGroup("ログイン") {
                    Field(
                        when (os) {
                            RemoteOs.MAC -> "Mac のユーザ名（空欄なら VNC パスワード）"
                            RemoteOs.WINDOWS -> "Windows のユーザ名（DOMAIN\\user も可）"
                            RemoteOs.LINUX ->
                                if (protocol == Protocol.RDP) "Linux のユーザ名" else "Linux のユーザ名（SSH 用。VNC はパスワードのみ）"
                        },
                        username, { username = it },
                    )
                    HorizontalDivider(color = AppColors.Outline)
                    Field("パスワード", password, { password = it }, keyboardType = KeyboardType.Password, secret = true)
                }

                FieldGroup(null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("詳細設定", color = AppColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(if (advancedOpen) "閉じる ▴" else "ポート $port ▾", color = AppColors.Subtle, fontSize = 13.sp)
                    }
                    if (advancedOpen) {
                        HorizontalDivider(color = AppColors.Outline)
                        Field("ポート", port, { port = it.filter(Char::isDigit).take(5) }, keyboardType = KeyboardType.Number)
                    }
                }

                Text(
                    "ⓘ " + when {
                        os == RemoteOs.LINUX && protocol == Protocol.RDP ->
                            "Ubuntu（GNOME）は「設定 → システム → リモートデスクトップ」をオンにします。その他のデスクトップは xrdp をインストールしてください。ターミナル・ファイル転送・強制再起動には openssh-server が必要です。"
                        os == RemoteOs.LINUX ->
                            "x11vnc や TigerVNC などの VNC サーバーを VNC パスワード付きで起動してください。ターミナル・ファイル転送・強制再起動には openssh-server が必要です。"
                        os == RemoteOs.MAC -> "Mac の「システム設定 → 一般 → 共有 → 画面共有」をオンにしてください。強制再起動・ファイル転送・Full HD 切替には「リモートログイン」も必要です。"
                        else -> "Windows（Pro 以上）の「設定 → システム → リモート デスクトップ」をオンにしてください。強制再起動・ファイル転送には「OpenSSH サーバー」が必要です。"
                    },
                    color = AppColors.Subtle,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            Box(
                Modifier
                    .height(52.dp)
                    .widthIn(min = 110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.SurfaceHigh)
                    .clickable(enabled = canSave) { save { onDone() } }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text("保存", color = if (canSave) AppColors.Text else AppColors.Subtle, fontWeight = FontWeight.SemiBold) }
            Box(
                Modifier
                    .height(52.dp)
                    .widthIn(max = 400.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (canSave) AppColors.Accent else AppColors.SurfaceHigh)
                    .clickable(enabled = canSave) { save(onConnect) },
                contentAlignment = Alignment.Center,
            ) { Text("保存して接続", color = if (canSave) Color.White else AppColors.Subtle, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

@Composable
private fun OsOption(option: RemoteOs, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) AppColors.Accent.copy(alpha = 0.14f) else AppColors.Surface)
            .border(1.5.dp, if (selected) AppColors.Accent else AppColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (option) {
                RemoteOs.MAC -> "🖥"
                RemoteOs.WINDOWS -> "⊞"
                RemoteOs.LINUX -> "🐧"
            },
            fontSize = 24.sp,
            color = AppColors.Text,
        )
        Text(option.label, color = AppColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            when (option) {
                RemoteOs.MAC -> "画面共有"
                RemoteOs.WINDOWS -> "リモートデスクトップ"
                RemoteOs.LINUX -> "RDP / VNC"
            },
            color = AppColors.Subtle,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun FieldGroup(title: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Outline, RoundedCornerShape(16.dp)),
    ) {
        if (title != null) {
            Text(title, color = AppColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(start = 14.dp, top = 10.dp))
        }
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = AppColors.Subtle, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = AppColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(AppColors.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Color(0xFF5B6577), fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}
