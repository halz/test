package jp.halz.levelwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Setup screen: enable the service, pick the Level app, teach it the two buttons. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_service).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_target).setOnClickListener { pickTargetApp() }
        findViewById<Button>(R.id.btn_learn_lock).setOnClickListener { learn(LockAction.LOCK) }
        findViewById<Button>(R.id.btn_learn_unlock).setOnClickListener { learn(LockAction.UNLOCK) }
        findViewById<Button>(R.id.btn_test_lock).setOnClickListener { test(LockAction.LOCK) }
        findViewById<Button>(R.id.btn_test_unlock).setOnClickListener { test(LockAction.UNLOCK) }
        findViewById<Button>(R.id.btn_pick_lock).setOnClickListener { pick(LockAction.LOCK) }
        findViewById<Button>(R.id.btn_pick_unlock).setOnClickListener { pick(LockAction.UNLOCK) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        findViewById<TextView>(R.id.tv_service).text = getString(
            if (LevelAccessibilityService.isRunning()) R.string.service_on else R.string.service_off
        )
        val target = prefs.targetPackage
        findViewById<TextView>(R.id.tv_target).text =
            if (target == null) getString(R.string.target_unset)
            else getString(R.string.target_set, labelOf(target), target)
        findViewById<TextView>(R.id.tv_lock).text = describe(LockAction.LOCK)
        findViewById<TextView>(R.id.tv_unlock).text = describe(LockAction.UNLOCK)
    }

    private fun describe(action: LockAction): String {
        val matcher = prefs.matcher(action) ?: return getString(R.string.button_unset)
        val how = when {
            !matcher.longPress -> getString(R.string.press_tap)
            matcher.holdMillis > 0 ->
                getString(R.string.press_long_seconds, LevelAccessibilityService.seconds(matcher.holdMillis))
            else -> getString(R.string.press_long)
        }
        return getString(R.string.button_set, matcher.describe(), how)
    }

    private fun labelOf(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun pickTargetApp() {
        val apps = launchableApps()
        val labels = apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = apps[which].activityInfo.packageName
                if (chosen != prefs.targetPackage) {
                    prefs.targetPackage = chosen
                    // The recorded buttons belong to the previous app.
                    LockAction.values().forEach { prefs.setMatcher(it, null) }
                }
                render()
            }
            .show()
    }

    private fun launchableApps(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun learn(action: LockAction) {
        val launch = openTarget() ?: return
        prefs.learning = action
        toast(getString(R.string.learn_prompt, getString(action.labelRes)))
        startActivity(launch)
    }

    /** For buttons the Level app draws itself and never reports: point at them on screen. */
    private fun pick(action: LockAction) {
        val launch = openTarget() ?: return
        prefs.learning = null
        LevelAccessibilityService.startPicking(action)
        startActivity(launch)
    }

    private fun openTarget(): Intent? {
        if (!LevelAccessibilityService.isRunning()) {
            toast(getString(R.string.status_service_off))
            return null
        }
        val target = prefs.targetPackage
        if (target == null) {
            toast(getString(R.string.target_unset))
            return null
        }
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) toast(getString(R.string.status_app_missing))
        return launch
    }

    private fun test(action: LockAction) {
        LockRunner.run(this, action)?.let { toast(it) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
