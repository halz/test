package io.github.halz.macremote

import android.app.Application
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.data.profileDataStore
import io.github.halz.macremote.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Manual DI: three singletons, no framework needed at this size. */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob())
    val profileRepository by lazy { ProfileRepository(profileDataStore) }
    val secretStore by lazy { SecretStore(profileDataStore) }
    val sessionHolder by lazy { SessionHolder(appScope) }
}
