package jp.halz.levelwidget

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Setup screen: enable the service, pick the Level app, and manage the registered locks. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_service).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_target).setOnClickListener { pickTargetApp() }
        findViewById<Button>(R.id.btn_add_lock).setOnClickListener { addLock() }
    }

    override fun onResume() {
        super.onResume()
        Ble.start(this)
        render()
    }

    // --- the fixed part of the screen -----------------------------------------------------

    private fun render() {
        findViewById<TextView>(R.id.tv_service).text = getString(
            if (LevelAccessibilityService.isRunning()) R.string.service_on else R.string.service_off
        )
        val target = prefs.targetPackage
        findViewById<TextView>(R.id.tv_target).text =
            if (target == null) getString(R.string.target_unset)
            else getString(R.string.target_set, labelOf(target), target)
        renderLocks()
    }

    private fun labelOf(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun pickTargetApp() {
        val apps = launchableApps()
        val labels = apps.map { "${it.loadLabel(packageManager)}\n${it.activityInfo.packageName}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                prefs.targetPackage = apps[which].activityInfo.packageName
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

    // --- the lock list ---------------------------------------------------------------------

    private fun renderLocks() {
        val container = findViewById<LinearLayout>(R.id.lock_list)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        prefs.locks.forEach { lock ->
            container.addView(lockCard(inflater, container, lock))
        }
    }

    private fun lockCard(inflater: LayoutInflater, parent: ViewGroup, lock: Lock): View {
        val card = inflater.inflate(R.layout.item_lock, parent, false)
        card.findViewById<TextView>(R.id.lock_name).text = lock.name
        card.findViewById<TextView>(R.id.lock_press).text = lock.press?.let {
            getString(R.string.button_set, it.describe(), LevelAccessibilityService.seconds(it.holdMillis))
        } ?: getString(R.string.button_unset)
        card.findViewById<TextView>(R.id.lock_ble).text = lock.bleAddress?.let {
            getString(R.string.ble_set, it)
        } ?: getString(R.string.ble_unset)

        card.findViewById<Button>(R.id.lock_rename).setOnClickListener { rename(lock) }
        card.findViewById<Button>(R.id.lock_learn).setOnClickListener { pick(lock) }
        card.findViewById<Button>(R.id.lock_ble_pick).setOnClickListener { pickDevice(lock) }
        card.findViewById<Button>(R.id.lock_test).setOnClickListener {
            LockRunner.run(this, lock)?.let { toast(it) }
        }
        card.findViewById<Button>(R.id.lock_delete).setOnClickListener { confirmDelete(lock) }
        return card
    }

    private fun addLock() {
        nameDialog(getString(R.string.lock_add), getString(R.string.lock_default_name)) { name ->
            prefs.save(Lock.create(name))
            render()
            LockWidgetProvider.refresh(this)
        }
    }

    private fun rename(lock: Lock) {
        nameDialog(getString(R.string.lock_rename), lock.name) { name ->
            prefs.save(lock.copy(name = name))
            render()
            LockWidgetProvider.refresh(this)
        }
    }

    private fun nameDialog(title: String, initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initial)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(lock: Lock) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lock_delete_confirm, lock.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.remove(lock.id)
                Ble.start(this)
                render()
                LockWidgetProvider.refresh(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Records the button by pointing at it: the Level app reports nothing to accessibility. */
    private fun pick(lock: Lock) {
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
        LevelAccessibilityService.startPicking(lock)
        startActivity(launch)
    }

    // --- associating a Bluetooth device ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun pickDevice(lock: Lock) {
        if (!Ble.hasPermission(this)) {
            requestPermissions(arrayOf(Ble.scanPermission), REQUEST_SCAN)
            return
        }
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            toast(getString(R.string.ble_off))
            return
        }

        val found = LinkedHashMap<String, String>()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ble_scanning)
            .setMessage(R.string.ble_scanning_hint)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device?.address ?: return
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: "?"
                found[address] = getString(R.string.ble_entry, name, address, result.rssi)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }

        handler.postDelayed({
            runCatching { scanner.stopScan(callback) }
            dialog.dismiss()
            showDevices(lock, found)
        }, SCAN_MILLIS)
    }

    private fun showDevices(lock: Lock, found: Map<String, String>) {
        if (isFinishing) return
        if (found.isEmpty()) {
            toast(getString(R.string.ble_none))
            return
        }
        val addresses = found.keys.toList()
        val labels = addresses.map { found.getValue(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.ble_pick)
            .setItems(labels.toTypedArray()) { _, which ->
                prefs.save(lock.copy(bleAddress = addresses[which]))
                Ble.start(this)
                render()
                LockWidgetProvider.refresh(this)
            }
            .setNeutralButton(R.string.ble_clear) { _, _ ->
                prefs.save(lock.copy(bleAddress = null))
                Ble.start(this)
                render()
                LockWidgetProvider.refresh(this)
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SCAN) {
            if (Ble.hasPermission(this)) toast(getString(R.string.ble_granted))
            else toast(getString(R.string.ble_denied))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_SCAN = 1
        const val SCAN_MILLIS = 8_000L
    }
}
