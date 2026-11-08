package io.github.halz.macremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.halz.macremote.ui.navigation.AppNavGraph
import io.github.halz.macremote.ui.theme.MacRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The keep-alive foreground service works without this, but its
        // "connected" notification is only visible when granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val app = application as App
        val crash = if (savedInstanceState == null) CrashReport.pending(this) else null
        setContent {
            MacRemoteTheme {
                AppNavGraph(
                    profileRepository = app.profileRepository,
                    secretStore = app.secretStore,
                    settings = app.appSettings,
                    sessionHolder = app.sessionHolder,
                )
                var report by remember { mutableStateOf(crash) }
                report?.let { CrashDialog(it, onDismiss = { report = null }) }
            }
        }
    }
}

@Composable
private fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("前回クラッシュしました") },
        text = {
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)); onDismiss() }) { Text("コピーして閉じる") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
