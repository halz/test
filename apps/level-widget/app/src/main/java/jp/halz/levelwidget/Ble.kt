package jp.halz.levelwidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

/**
 * Tells the widget which locks are close enough to operate.
 *
 * Nothing here talks to a lock: it only watches for the Bluetooth advertisements of the device the
 * user associated with each lock. The scan is handed to the system with a PendingIntent so it runs
 * in the background against the hardware filter instead of keeping the app awake.
 */
object Ble {

    /** A lock counts as reachable while its device was advertising this recently. */
    const val FRESH_MS = 5 * 60 * 1000L

    val scanPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(scanPermission) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /**
     * Whether the lock can be operated right now: true / false when we know, null when we cannot
     * tell (no device associated, no permission, Bluetooth off). Unknown must not be shown as out
     * of range, or every lock would look unreachable before the feature is set up.
     */
    fun reachable(prefs: Prefs, lock: Lock, context: Context): Boolean? {
        val address = lock.bleAddress ?: return null
        if (!hasPermission(context) || !isEnabled(context)) return null
        return System.currentTimeMillis() - prefs.lastSeen(address) <= FRESH_MS
    }

    /** (Re)starts the background scan for every associated device. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val scanner = scanner(context) ?: return
        if (!hasPermission(context)) return
        val addresses = Prefs(context).locks.mapNotNull { it.bleAddress }.distinct()
        val pending = scanIntent(context)
        runCatching { scanner.stopScan(pending) }
        if (addresses.isEmpty()) return
        runCatching {
            val filters = addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            scanner.startScan(filters, settings, pending)
        }
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun scanner(context: Context): BluetoothLeScanner? =
        adapter(context)?.takeIf { it.isEnabled }?.bluetoothLeScanner

    private fun scanIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScanReceiver::class.java),
        // The system fills in the results, so the intent has to stay mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Receives the batches the system scan produces and notes which devices were advertising. */
    class ScanReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val results = intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
            ) ?: return
            val addresses = results.mapNotNull { it.device?.address }.distinct()
            if (addresses.isEmpty()) return

            // Advertisements arrive about once a second per device; only write and redraw when the
            // widget would actually change.
            val now = SystemClock.elapsedRealtime()
            if (now - lastHandled < THROTTLE_MS) return
            lastHandled = now

            Prefs(context).markSeen(addresses, System.currentTimeMillis())
            LockWidgetProvider.refresh(context)
        }

        private companion object {
            const val THROTTLE_MS = 30_000L

            @Volatile
            var lastHandled = 0L
        }
    }
}
