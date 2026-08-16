package io.github.halz.macremote

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
