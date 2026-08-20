package io.github.halz.macremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            MacRemoteTheme {
                AppNavGraph(
                    profileRepository = app.profileRepository,
                    secretStore = app.secretStore,
                    settings = app.appSettings,
                    sessionHolder = app.sessionHolder,
                )
            }
        }
    }
}
