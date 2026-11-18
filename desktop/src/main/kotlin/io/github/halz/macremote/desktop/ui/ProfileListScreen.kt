package io.github.halz.macremote.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.data.RemoteReboot
import io.github.halz.macremote.desktop.AppState
import io.github.halz.macremote.desktop.Screen
import io.github.halz.macremote.desktop.data.ProfileTransfer
import io.github.halz.macremote.desktop.session.SessionState
import io.github.halz.macremote.desktop.session.Thumbnails
import io.github.halz.macremote.desktop.session.VncSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/**
 * Saved connections as a searchable grid of cards, each showing the last
 * remote screen. Click connects; right-click (or "⋯") opens the actions.
 */
@Composable
fun ProfileListScreen(app: AppState, window: Frame) {
    val profiles by app.profiles.profiles.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val thumbnailVersion by app.sessions.thumbnailVersion.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<Protocol?>(null) }
    var rebootTarget by remember { mutableStateOf<Profile?>(null) }

    fun sessionOf(profile: Profile): VncSession? = sessions.find { it.profile.id == profile.id }

    val visible = profiles.filter { profile ->
        (filter == null || profile.protocol == filter) &&
            (query.isBlank() || listOf(profile.name, profile.host).any { it.contains(query.trim(), ignoreCase = true) })
    }

    fun actions(profile: Profile): List<Pair<String, () -> Unit>> = buildList {
        val session = sessionOf(profile)
        add((if (session != null) "画面に戻る" else "接続") to { app.connect(profile) })
        add("編集" to { app.screen = Screen.Edit(profile.id) })
        add("ファイル転送" to { app.openFiles(profile) })
        add("ターミナル (SSH)" to { app.openTerminal(profile) })
        add("複製" to {
            val id = UUID.randomUUID().toString()
            app.profiles.upsert(profile.copy(id = id, name = "${profile.name.ifEmpty { profile.host }} のコピー"))
            app.secrets.savePassword(id, app.password(profile.id))
            app.notify("複製しました")
        })
        add("強制再起動…" to {
            if (profile.username.isEmpty()) app.notify("再起動にはユーザ名入りの接続先が必要です") else rebootTarget = profile
        })
        if (session != null) add("切断" to { app.sessions.close(profile.id) })
    }

    rebootTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { rebootTarget = null },
            title = { Text("${target.displayName()} を強制再起動しますか？") },
            text = {
                Text(
                    "SSH で再起動コマンドを送ります。保存していない作業は失われます。" +
                        (if (target.protocol == Protocol.VNC) "Mac の" else "Windows の") +
                        "管理者アカウントである必要があります。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    rebootTarget = null
                    app.scope.launch {
                        runCatching { RemoteReboot.reboot(target, app.password(target.id)) }
                            .onSuccess {
                                app.sessions.close(target.id)
                                app.notify("再起動コマンドを送信しました")
                            }
                            .onFailure { app.notify("再起動失敗: ${it.message}") }
                    }
                }) { Text("再起動する") }
            },
            dismissButton = { TextButton(onClick = { rebootTarget = null }) { Text("キャンセル") } },
        )
    }

    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        ListHeader(
            onAdd = { app.screen = Screen.Edit(null) },
            onExport = { exportProfiles(app, window) },
            onImport = { importProfiles(app, window) },
        )
        Row(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchBox(query, { query = it }, Modifier.widthIn(max = 420.dp).weight(1f))
            FilterTabs(filter, { filter = it }, Modifier.width(300.dp))
        }
        if (visible.isEmpty()) {
            EmptyState(Modifier.weight(1f).fillMaxWidth(), hasProfiles = profiles.isNotEmpty())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visible, key = { it.id }) { profile ->
                    val menu = actions(profile)
                    ContextMenuArea(items = { menu.map { (label, action) -> ContextMenuItem(label, action) } }) {
                        ProfileCard(
                            profile = profile,
                            session = sessionOf(profile),
                            thumbnailVersion = thumbnailVersion,
                            actions = menu,
                            onClick = { app.connect(profile) },
                        )
                    }
                }
            }
        }
    }
}

private fun exportProfiles(app: AppState, window: Frame) {
    val dialog = FileDialog(window, "設定をエクスポート", FileDialog.SAVE).apply {
        file = "macremote-profiles.json"
        isVisible = true
    }
    val name = dialog.file ?: return
    runCatching { File(dialog.directory, name).writeText(ProfileTransfer.export(app.profiles, app.secrets)) }
        .onSuccess { app.notify("エクスポートしました (パスワードを含みます。取り扱いに注意)") }
        .onFailure { app.notify("エクスポート失敗: ${it.message}") }
}

private fun importProfiles(app: AppState, window: Frame) {
    val dialog = FileDialog(window, "設定をインポート", FileDialog.LOAD).apply {
        file = "*.json"
        isVisible = true
    }
    val name = dialog.file ?: return
    runCatching { ProfileTransfer.import(File(dialog.directory, name).readText(), app.profiles, app.secrets) }
        .onSuccess { app.notify("${it}件の接続先をインポートしました") }
        .onFailure { app.notify("インポート失敗: ${it.message}") }
}

@Composable
private fun ListHeader(onAdd: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("接続先", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box {
            IconTile("⇅") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("設定をエクスポート") }, onClick = { menuOpen = false; onExport() })
                DropdownMenuItem(text = { Text("設定をインポート") }, onClick = { menuOpen = false; onImport() })
            }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Accent)
                .clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) { Text("＋ 接続先を追加", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = AppColors.Text, fontSize = 14.sp),
        cursorBrush = SolidColor(AppColors.Accent),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.SurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔍", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("名前・ホストで検索", color = AppColors.Subtle, fontSize = 14.sp)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Text("✕", color = AppColors.Subtle, modifier = Modifier.clickable { onChange("") })
                }
            }
        },
    )
}

@Composable
private fun FilterTabs(filter: Protocol?, onChange: (Protocol?) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Outline, RoundedCornerShape(12.dp))
            .padding(3.dp),
    ) {
        listOf(null to "すべて", Protocol.VNC to "Mac", Protocol.RDP to "Windows").forEach { (value, label) ->
            val on = filter == value
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) AppColors.SurfaceHigh else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) AppColors.Text else AppColors.Subtle,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    session: VncSession?,
    thumbnailVersion: Int,
    actions: List<Pair<String, () -> Unit>>,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val connected = session.isConnected()
    Column(
        Modifier
            .clip(shape)
            .background(if (hovered) AppColors.SurfaceHigh else AppColors.Surface)
            .border(1.dp, if (hovered) AppColors.Accent.copy(alpha = 0.6f) else AppColors.Outline, shape)
            .hoverable(hover)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            ProfileThumbnail(profile, session, thumbnailVersion, Modifier.fillMaxSize())
            Badge(if (profile.protocol == Protocol.VNC) "Mac" else "⊞ Windows", Modifier.align(Alignment.TopStart).padding(8.dp))
            if (connected) {
                Badge("● 接続中", Modifier.align(Alignment.TopEnd).padding(8.dp), background = AppColors.Online, color = Color(0xFF053D1E))
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.displayName(), color = AppColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(subtitle(profile, connected), color = AppColors.Subtle, fontSize = 12.sp, maxLines = 1)
            }
            Box {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", color = if (hovered || menuOpen) AppColors.Text else AppColors.Subtle, fontSize = 18.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, hasProfiles: Boolean) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (hasProfiles) "該当する接続先がありません" else "接続先がまだありません", color = AppColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (!hasProfiles) {
            Text(
                "右上の「＋ 接続先を追加」から Mac（画面共有）や Windows（リモートデスクトップ）を登録してください。",
                color = AppColors.Subtle,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The connection's picture: the live screen while connected, otherwise the
 * screen saved when it was last left, otherwise an OS-colored placeholder.
 */
@Composable
private fun ProfileThumbnail(profile: Profile, session: VncSession?, thumbnailVersion: Int, modifier: Modifier = Modifier) {
    val saved by produceState<ImageBitmap?>(null, profile.id, thumbnailVersion) {
        value = withContext(Dispatchers.IO) { Thumbnails.load(profile.id) }
    }
    val placeholder = Brush.linearGradient(
        if (profile.protocol == Protocol.VNC) listOf(Color(0xFF3B2A6B), Color(0xFFB04A7A), Color(0xFFF0A35E))
        else listOf(Color(0xFF0B3A75), Color(0xFF1767C9), Color(0xFF5FB3FF))
    )
    val live = if (session.isConnected()) session?.image?.collectAsState()?.value else null
    val image = live ?: saved
    Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
        if (image != null) {
            Canvas(Modifier.fillMaxSize()) { drawCropped(image) }
        } else {
            Text(
                if (profile.protocol == Protocol.VNC) "Mac" else "⊞",
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
        }
    }
}

/** Draws [image] to fill the area, cropping the overflowing axis. */
private fun DrawScope.drawCropped(image: ImageBitmap) {
    if (image.width <= 0 || image.height <= 0 || size.width <= 0f || size.height <= 0f) return
    val target = size.width / size.height
    val source = image.width.toFloat() / image.height
    val srcSize = if (source > target) {
        IntSize((image.height * target).toInt(), image.height)
    } else {
        IntSize(image.width, (image.width / target).toInt())
    }
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcSize.width) / 2, (image.height - srcSize.height) / 2),
        srcSize = srcSize,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, background: Color = Color(0x8C000000), color: Color = Color.White) {
    Text(
        text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

internal fun Profile.displayName() = name.ifEmpty { host }

/** Observed, so cards update when a connection comes up or drops. */
@Composable
internal fun VncSession?.isConnected(): Boolean = this?.state?.collectAsState()?.value is SessionState.Connected

private fun subtitle(profile: Profile, connected: Boolean): String {
    if (connected) return profile.host
    val lastUsed = Thumbnails.lastUsed(profile.id) ?: return profile.host
    return "${profile.host} · ${relativeTime(lastUsed)}"
}

private fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "たった今"
        minutes < 60 -> "${minutes}分前"
        minutes < 60 * 24 -> "${minutes / 60}時間前"
        else -> "${minutes / (60 * 24)}日前"
    }
}
