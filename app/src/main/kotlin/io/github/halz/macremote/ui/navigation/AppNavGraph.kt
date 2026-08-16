package io.github.halz.macremote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.halz.macremote.data.AppSettings
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.session.SessionHolder
import io.github.halz.macremote.ui.profiles.ProfileEditScreen
import io.github.halz.macremote.ui.profiles.ProfileListScreen
import io.github.halz.macremote.ui.session.SessionScreen

@Composable
fun AppNavGraph(
    profileRepository: ProfileRepository,
    secretStore: SecretStore,
    settings: AppSettings,
    sessionHolder: SessionHolder,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "profiles") {
        composable("profiles") {
            ProfileListScreen(
                repository = profileRepository,
                onConnect = { profile -> navController.navigate("session/${profile.id}") },
                onEdit = { id -> navController.navigate(if (id == null) "edit" else "edit?id=$id") },
            )
        }
        composable("edit?id={id}") { backStackEntry ->
            ProfileEditScreen(
                profileId = backStackEntry.arguments?.getString("id"),
                repository = profileRepository,
                secretStore = secretStore,
                onDone = { navController.popBackStack() },
            )
        }
        composable("edit") {
            ProfileEditScreen(
                profileId = null,
                repository = profileRepository,
                secretStore = secretStore,
                onDone = { navController.popBackStack() },
            )
        }
        composable("session/{profileId}") { backStackEntry ->
            SessionScreen(
                profileId = backStackEntry.arguments?.getString("profileId").orEmpty(),
                repository = profileRepository,
                secretStore = secretStore,
                settings = settings,
                sessionHolder = sessionHolder,
                onExit = { navController.popBackStack() },
            )
        }
    }
}
