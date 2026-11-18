package io.github.halz.macremote.desktop.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.desktop.AppState
import io.github.halz.macremote.desktop.session.Thumbnails
import java.util.UUID

/**
 * Add or edit a connection: the OS first (it decides protocol, port and the
 * setup hint), then the address and login in grouped fields, with the port
 * tucked away under "詳細設定".
 */
@Composable
fun ProfileEditScreen(
    app: AppState,
    profileId: String?,
    onDone: () -> Unit,
    onConnect: (Profile) -> Unit,
) {
    val existing = remember(profileId) { profileId?.let { app.profiles.find(it) } }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.VNC) }
    var port by remember { mutableStateOf((existing?.port ?: protocol.defaultPort).toString()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(profileId?.let { app.password(it) }.orEmpty()) }
    var advancedOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save(): Profile {
        val profile = Profile(
            id = profileId ?: UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            port = port.toIntOrNull() ?: protocol.defaultPort,
            username = username.trim(),
            protocol = protocol,
        )
        app.profiles.upsert(profile)
        app.secrets.savePassword(profile.id, password)
        return profile
    }

    if (confirmDelete && profileId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この接続先を削除しますか？") },
            text = { Text(name.ifEmpty { host }) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    app.sessions.close(profileId)
                    app.profiles.delete(profileId)
                    app.secrets.deletePassword(profileId)
                    Thumbnails.delete(profileId)
                    onDone()
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }

    val canSave = host.isNotBlank()

    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconTile("←", onClick = onDone)
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
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Protocol.entries.forEach { option ->
                        OsOption(option = option, selected = protocol == option, modifier = Modifier.weight(1f)) {
                            // Follow the default port unless the user typed a custom one.
                            if (port == protocol.defaultPort.toString()) port = option.defaultPort.toString()
                            protocol = option
                        }
                    }
                }

                FieldGroup("接続先") {
                    Field("名前", name, { name = it }, placeholder = if (protocol == Protocol.VNC) "MacBook Pro" else "自宅の PC")
                    HorizontalDivider(color = AppColors.Outline)
                    Field("ホスト（Tailscale 名 / 100.x.x.x）", host, { host = it.trim() }, placeholder = "my-pc.tailnet.ts.net")
                }

                FieldGroup("ログイン") {
                    Field(
                        when (protocol) {
                            Protocol.VNC -> "Mac のユーザ名（空欄なら VNC パスワード）"
                            Protocol.RDP -> "Windows のユーザ名（DOMAIN\\user も可）"
                        },
                        username, { username = it },
                    )
                    HorizontalDivider(color = AppColors.Outline)
                    Field("パスワード", password, { password = it }, secret = true)
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
                        Field("ポート", port, { port = it.filter(Char::isDigit).take(5) })
                    }
                }

                Text(
                    "ⓘ " + when (protocol) {
                        Protocol.VNC -> "Mac の「システム設定 → 一般 → 共有 → 画面共有」をオンにしてください。強制再起動・ファイル転送・ターミナル・Full HD 切替には「リモートログイン」も必要です。"
                        Protocol.RDP -> "Windows（Pro 以上）の「設定 → システム → リモート デスクトップ」をオンにしてください。接続には Windows 標準の「リモート デスクトップ接続」(mstsc) を使います。強制再起動・ファイル転送・ターミナルには「OpenSSH サーバー」が必要です。"
                    },
                    color = AppColors.Subtle,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            Box(
                Modifier
                    .height(48.dp)
                    .widthIn(min = 110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.SurfaceHigh)
                    .clickable(enabled = canSave) { save(); onDone() }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text("保存", color = if (canSave) AppColors.Text else AppColors.Subtle, fontWeight = FontWeight.SemiBold) }
            PrimaryButton("保存して接続", Modifier.widthIn(max = 400.dp).weight(1f), enabled = canSave) { onConnect(save()) }
        }
    }
}

@Composable
private fun OsOption(option: Protocol, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
        Text(if (option == Protocol.VNC) "🖥" else "⊞", fontSize = 24.sp, color = AppColors.Text)
        Text(if (option == Protocol.VNC) "Mac" else "Windows", color = AppColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            if (option == Protocol.VNC) "画面共有 · 5900" else "リモートデスクトップ · 3389",
            color = AppColors.Subtle,
            fontSize = 11.sp,
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
    secret: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = AppColors.Subtle, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = AppColors.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(AppColors.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Color(0xFF5B6577), fontSize = 15.sp)
                    inner()
                }
            },
        )
    }
}
