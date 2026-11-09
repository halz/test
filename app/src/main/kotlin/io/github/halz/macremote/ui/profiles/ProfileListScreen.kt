package io.github.halz.macremote.ui.profiles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.ProfileTransfer
import io.github.halz.macremote.data.Protocol
import io.github.halz.macremote.data.RemoteReboot
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.session.RemoteSession
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.session.SessionState
import io.github.halz.macremote.session.Thumbnails
import io.github.halz.macremote.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Saved connections. On the Fold's cover screen: a searchable grid of cards
 * with the last remote screen as thumbnail; long-press opens an action sheet.
 * On the wide inner screen: list on the left, details of the selected
 * connection on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    repository: ProfileRepository,
    secretStore: SecretStore,
    sessionHolder: SessionHolder,
    onConnect: (Profile) -> Unit,
    onEdit: (String?) -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenGestureSettings: () -> Unit,
) {
    val profiles by repository.profiles.collectAsState(initial = emptyList())
    val sessions by sessionHolder.sessions.collectAsState()
    val thumbnailVersion by sessionHolder.thumbnailVersion.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<Protocol?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetTarget by remember { mutableStateOf<Profile?>(null) }
    var rebootTarget by remember { mutableStateOf<Profile?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    fun sessionOf(profile: Profile): RemoteSession? = sessions.find { it.profile.id == profile.id }

    val visible = profiles.filter { profile ->
        (filter == null || profile.protocol == filter) &&
            (query.isBlank() || listOf(profile.name, profile.host).any { it.contains(query.trim(), ignoreCase = true) })
    }

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

    val actions = ProfileActions(
        connect = onConnect,
        edit = { onEdit(it.id) },
        files = { onOpenFiles(it.id) },
        duplicate = { profile ->
            scope.launch {
                val id = UUID.randomUUID().toString()
                repository.upsert(profile.copy(id = id, name = "${profile.name.ifEmpty { profile.host }} のコピー"))
                secretStore.savePassword(id, secretStore.loadPassword(profile.id).orEmpty())
                toast("複製しました")
            }
        },
        reboot = { profile ->
            if (profile.username.isEmpty()) toast("再起動にはユーザ名入りの接続先が必要です") else rebootTarget = profile
        },
        disconnect = { sessionHolder.close(it.id) },
    )

    rebootTarget?.let { target ->
        RebootDialog(
            target = target,
            onDismiss = { rebootTarget = null },
            onConfirm = {
                rebootTarget = null
                scope.launch {
                    runCatching {
                        RemoteReboot.reboot(target, secretStore.loadPassword(target.id).orEmpty())
                    }.onSuccess {
                        // The computer is going down; drop the live session cleanly.
                        sessionHolder.close(target.id)
                        toast("再起動コマンドを送信しました")
                    }.onFailure { toast("再起動失敗: ${it.message}") }
                }
            },
        )
    }

    sheetTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Surface,
        ) {
            ActionSheetContent(
                profile = target,
                session = sessionOf(target),
                thumbnailVersion = thumbnailVersion,
                actions = actions,
                onDone = { sheetTarget = null },
            )
        }
    }

    val header: @Composable () -> Unit = {
        ListHeader(
            onAdd = { onEdit(null) },
            onSettings = onOpenGestureSettings,
            onExport = { exportLauncher.launch("macremote-profiles.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        SearchBox(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        FilterTabs(filter, { filter = it }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.Background)) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            val selected = visible.find { it.id == selectedId } ?: visible.firstOrNull()
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .background(AppColors.Surface)
                        .statusBarsPadding(),
                ) {
                    header()
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        Protocol.entries.forEach { protocol ->
                            val group = visible.filter { it.protocol == protocol }
                            if (group.isEmpty()) return@forEach
                            item(key = "header-$protocol") {
                                Text(
                                    if (protocol == Protocol.VNC) "MAC" else "WINDOWS",
                                    color = AppColors.Subtle,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
                                )
                            }
                            items(group, key = { it.id }) { profile ->
                                ProfileRow(
                                    profile = profile,
                                    session = sessionOf(profile),
                                    thumbnailVersion = thumbnailVersion,
                                    selected = profile.id == selected?.id,
                                    onClick = { selectedId = profile.id },
                                    onLongClick = { sheetTarget = profile },
                                )
                            }
                        }
                    }
                }
                if (selected != null) {
                    DetailPane(selected, sessionOf(selected), thumbnailVersion, actions, Modifier.weight(1f))
                } else {
                    EmptyState(Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                header()
                if (visible.isEmpty()) {
                    EmptyState(Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { profile ->
                            ProfileCard(
                                profile = profile,
                                session = sessionOf(profile),
                                thumbnailVersion = thumbnailVersion,
                                onClick = { onConnect(profile) },
                                onLongClick = { sheetTarget = profile },
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .size(60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppColors.Accent)
                    .clickable { onEdit(null) },
                contentAlignment = Alignment.Center,
            ) { Text("＋", color = Color.White, fontSize = 28.sp) }
        }
    }
}

/** What can be done to a connection, shared by the sheet and the detail pane. */
private class ProfileActions(
    val connect: (Profile) -> Unit,
    val edit: (Profile) -> Unit,
    val files: (Profile) -> Unit,
    val duplicate: (Profile) -> Unit,
    val reboot: (Profile) -> Unit,
    val disconnect: (Profile) -> Unit,
)

@Composable
private fun ListHeader(onAdd: () -> Unit, onSettings: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
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
        IconTile("⚙", onClick = onSettings)
        IconTile("＋", onClick = onAdd)
    }
}

@Composable
private fun IconTile(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = AppColors.Text, fontSize = 17.sp) }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = AppColors.Text, fontSize = 15.sp),
        cursorBrush = SolidColor(AppColors.Accent),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.SurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔍", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("名前・ホストで検索", color = AppColors.Subtle, fontSize = 15.sp)
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
            .fillMaxWidth()
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
                    .padding(vertical = 8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
    profile: Profile,
    session: RemoteSession?,
    thumbnailVersion: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            ProfileThumbnail(profile, session, thumbnailVersion, Modifier.fillMaxSize())
            Badge(if (profile.protocol == Protocol.VNC) "Mac" else "⊞ Windows", Modifier.align(Alignment.TopStart).padding(8.dp))
            if (session.isConnected()) {
                Badge("● 接続中", Modifier.align(Alignment.TopEnd).padding(8.dp), background = AppColors.Online, color = Color(0xFF053D1E))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(profile.displayName(), color = AppColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            Text(subtitle(profile, session), color = AppColors.Subtle, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRow(
    profile: Profile,
    session: RemoteSession?,
    thumbnailVersion: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AppColors.SurfaceHigh else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileThumbnail(profile, session, thumbnailVersion, Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName(), color = AppColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(subtitle(profile, session), color = AppColors.Subtle, fontSize = 11.sp, maxLines = 1)
        }
        if (session.isConnected()) Box(Modifier.size(8.dp).clip(CircleShape).background(AppColors.Online))
    }
}

@Composable
private fun ActionSheetContent(
    profile: Profile,
    session: RemoteSession?,
    thumbnailVersion: Int,
    actions: ProfileActions,
    onDone: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProfileThumbnail(profile, session, thumbnailVersion, Modifier.size(width = 88.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)))
            Column {
                Text(profile.displayName(), color = AppColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(connectionLine(profile), color = AppColors.Subtle, fontSize = 12.sp)
            }
        }
        PrimaryButton(if (session.isConnected()) "● 画面に戻る" else "接続") { onDone(); actions.connect(profile) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile("✎", "編集", Modifier.weight(1f)) { onDone(); actions.edit(profile) }
            ActionTile("⎘", "ファイル", Modifier.weight(1f)) { onDone(); actions.files(profile) }
            ActionTile("⧉", "複製", Modifier.weight(1f)) { onDone(); actions.duplicate(profile) }
            ActionTile("⟳", "再起動", Modifier.weight(1f), danger = true) { onDone(); actions.reboot(profile) }
        }
        if (session != null) {
            ActionTile("⏏", "切断", Modifier.fillMaxWidth()) { onDone(); actions.disconnect(profile) }
        }
    }
}

@Composable
private fun DetailPane(
    profile: Profile,
    session: RemoteSession?,
    thumbnailVersion: Int,
    actions: ProfileActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lastUsed = remember(profile.id, thumbnailVersion) { Thumbnails.lastUsed(context, profile.id) }
    Column(
        modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ProfileThumbnail(
            profile, session, thumbnailVersion,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, AppColors.Outline, RoundedCornerShape(20.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.displayName(), color = AppColors.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Text(connectionLine(profile), color = AppColors.Subtle, fontSize = 14.sp)
            }
            PrimaryButton(if (session.isConnected()) "● 画面に戻る" else "接続", Modifier.width(200.dp)) { actions.connect(profile) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip("種類", if (profile.protocol == Protocol.VNC) "Mac（画面共有）" else "Windows（RDP）")
            InfoChip("状態", if (session.isConnected()) "接続中" else lastUsed?.let { relativeTime(it) + "に使用" } ?: "未使用")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("✎", "編集", Modifier.weight(1f)) { actions.edit(profile) }
            ActionTile("⎘", "ファイル転送", Modifier.weight(1f)) { actions.files(profile) }
            ActionTile("⧉", "複製", Modifier.weight(1f)) { actions.duplicate(profile) }
            ActionTile("⟳", "強制再起動", Modifier.weight(1f), danger = true) { actions.reboot(profile) }
            if (session != null) ActionTile("⏏", "切断", Modifier.weight(1f)) { actions.disconnect(profile) }
        }
    }
}

/**
 * The connection's picture: the live screen while connected, otherwise the
 * screen saved when it was last left, otherwise an OS-colored placeholder.
 */
@Composable
private fun ProfileThumbnail(profile: Profile, session: RemoteSession?, thumbnailVersion: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saved by produceState<ImageBitmap?>(null, profile.id, thumbnailVersion) {
        value = withContext(Dispatchers.IO) { Thumbnails.load(context, profile.id)?.asImageBitmap() }
    }
    val placeholder = Brush.linearGradient(
        if (profile.protocol == Protocol.VNC) listOf(Color(0xFF3B2A6B), Color(0xFFB04A7A), Color(0xFFF0A35E))
        else listOf(Color(0xFF0B3A75), Color(0xFF1767C9), Color(0xFF5FB3FF))
    )
    val live = session.isConnected()
    if (live && session != null) {
        val frame by session.frame.collectAsState()
        Canvas(modifier.background(Color.Black)) {
            frame // redraw on every framebuffer update
            drawCropped(session.display.bitmap.asImageBitmap())
        }
    } else {
        val image = saved
        Box(modifier.background(placeholder), contentAlignment = Alignment.Center) {
            if (image != null) {
                Canvas(Modifier.fillMaxSize()) { drawCropped(image) }
            } else {
                Text(
                    if (profile.protocol == Protocol.VNC) "Mac" else "⊞",
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
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

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun ActionTile(icon: String, label: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, color = if (danger) AppColors.Danger else AppColors.Text, fontSize = 20.sp)
        Text(label, color = if (danger) AppColors.Danger else AppColors.Subtle, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.SurfaceHigh).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = AppColors.Subtle, fontSize = 13.sp)
        Text(value, color = AppColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("接続先がありません", color = AppColors.Text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "＋ から Mac や Windows の Tailscale 名（例: my-pc.tailnet.ts.net）を登録してください",
            color = AppColors.Subtle,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RebootDialog(target: Profile, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.displayName()} を強制再起動") },
        text = {
            Text(
                "今すぐ再起動します。保存していない作業は失われます。\n\n" +
                    when (target.protocol) {
                        Protocol.VNC -> "Mac 側で「リモートログイン」が有効で、"
                        Protocol.RDP -> "Windows 側で「OpenSSH サーバー」が有効で、"
                    } + "このアカウントが管理者である必要があります。"
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("再起動する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun Profile.displayName() = name.ifEmpty { host }

/** Observed, so cards update when a connection comes up or drops. */
@Composable
private fun RemoteSession?.isConnected(): Boolean = this?.state?.collectAsState()?.value is SessionState.Connected

private fun connectionLine(profile: Profile): String =
    listOfNotNull(
        if (profile.protocol == Protocol.VNC) "Mac" else "Windows",
        "${profile.host}:${profile.port}",
        profile.username.ifEmpty { null },
    ).joinToString(" · ")

@Composable
private fun subtitle(profile: Profile, session: RemoteSession?): String {
    val context = LocalContext.current
    if (session.isConnected()) return profile.host
    val lastUsed = Thumbnails.lastUsed(context, profile.id) ?: return profile.host
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
