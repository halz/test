package jp.halz.levelwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

/** Tapping a lock on the widget opens this: hold the ring to actually run the lock. */
class HoldActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hold)

        val lock = Prefs(this).lock(intent.getStringExtra(EXTRA_LOCK_ID))
        if (lock == null || !lock.isReady) {
            Toast.makeText(this, R.string.status_not_configured, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        findViewById<TextView>(R.id.hold_name).text = lock.name
        val hint = findViewById<TextView>(R.id.hold_hint)
        val ring = findViewById<HoldRing>(R.id.hold_ring)
        ring.onHoldStart = { hint.setText(R.string.hold_holding) }
        ring.onHoldCancel = { hint.setText(R.string.hold_prompt) }
        ring.onHoldComplete = {
            val failure = LockRunner.run(this, lock)
            if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_LOCK_ID = "lock_id"

        fun intent(context: Context, lock: Lock): Intent =
            Intent(context, HoldActivity::class.java)
                .putExtra(EXTRA_LOCK_ID, lock.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
