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
import java.util.Locale

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
        findViewById<Button>(R.id.btn_hold).setOnClickListener { pickHold() }
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
        findViewById<TextView>(R.id.tv_hold).text = holdLabel(prefs.holdMillis)
    }

    private fun describe(action: LockAction): String {
        val matcher = prefs.matcher(action) ?: return getString(R.string.button_unset)
        val how = getString(if (matcher.longPress) R.string.press_long else R.string.press_tap)
        return getString(R.string.button_set, matcher.describe(), how)
    }

    private fun holdLabel(millis: Long): String =
        getString(R.string.hold_seconds, String.format(Locale.getDefault(), "%.1f", millis / 1000.0))

    private fun pickHold() {
        val labels = HOLD_CHOICES.map { holdLabel(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.hold_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                prefs.holdMillis = HOLD_CHOICES[which]
                render()
            }
            .show()
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
        if (!LevelAccessibilityService.isRunning()) {
            toast(getString(R.string.status_service_off))
            return
        }
        val target = prefs.targetPackage
        if (target == null) {
            toast(getString(R.string.target_unset))
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) {
            toast(getString(R.string.status_app_missing))
            return
        }
        prefs.learning = action
        toast(getString(R.string.learn_prompt, getString(action.labelRes)))
        startActivity(launch)
    }

    private fun test(action: LockAction) {
        LockRunner.run(this, action)?.let { toast(it) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val HOLD_CHOICES = longArrayOf(800L, Prefs.DEFAULT_HOLD_MILLIS, 2_500L, 4_000L)
    }
}
