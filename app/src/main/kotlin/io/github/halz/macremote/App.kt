package io.github.halz.macremote

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.halz.macremote.data.AppSettings
import io.github.halz.macremote.data.ProfileRepository
import io.github.halz.macremote.data.SecretStore
import io.github.halz.macremote.data.profileDataStore
import io.github.halz.macremote.session.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI: three singletons, no framework needed at this size. */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob())
    val profileRepository by lazy { ProfileRepository(profileDataStore) }
    val secretStore by lazy { SecretStore(profileDataStore) }
    val appSettings by lazy { AppSettings(profileDataStore) }
    val sessionHolder by lazy { SessionHolder(appScope, this) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            appSettings.idleTimeout.collect { sessionHolder.idleTimeoutMinutes = it.minutes }
        }
        // Feed background/foreground transitions to the idle watchdog. During
        // a configuration change the count briefly hits zero; the watchdog is
        // cancelled again milliseconds later, so that is harmless.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) sessionHolder.onAppForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) sessionHolder.onAppBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
