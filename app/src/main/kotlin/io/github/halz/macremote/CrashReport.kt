package io.github.halz.macremote

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects what is known about the previous run's crash so it can be shown
 * (and copied) on the next launch: the Java stack trace, the system's exit
 * record for native crashes, and the tail of FreeRDP's log.
 */
object CrashReport {
    private const val PREFS = "crash_report"
    private const val KEY_SEEN = "seen_timestamp"
    private const val LOG_LINES = 40
    private const val TRACE_STRINGS = 400
    private val BUILD_ID = Regex("[0-9a-f]{16,}")

    fun install(context: Context) {
        val file = stackFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText("${thread.name}\n${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report for a crash not yet shown, or null. Marks it as shown. */
    fun pending(context: Context): String? {
        val stack = stackFile(context)
        val javaTrace = stack.takeIf { it.exists() }?.readText()
        stack.delete()

        var exitSummary: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val exit = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()
            if (exit != null && exit.timestamp > prefs.getLong(KEY_SEEN, 0) &&
                (exit.reason == ApplicationExitInfo.REASON_CRASH ||
                    exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
            ) {
                prefs.edit().putLong(KEY_SEEN, exit.timestamp).apply()
                val kind = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "native" else "java"
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
                exitSummary = "$time crash ($kind): ${exit.description.orEmpty()}"
                if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    nativeTrace(exit)?.let { exitSummary += "\n$it" }
                }
            }
        }
        if (javaTrace == null && exitSummary == null) return null

        val log = LibFreeRDP.logFile(context).takeIf { it.exists() }
            ?.readLines()?.takeLast(LOG_LINES)?.joinToString("\n")
        return listOfNotNull(
            exitSummary,
            javaTrace,
            log?.let { "--- FreeRDP log ---\n$it" },
        ).joinToString("\n\n")
    }

    /**
     * The readable text of the native tombstone (Android 12+ hands it over as
     * a protobuf): abort message, signal, and the frames' library and
     * function names, in the order the record lists them.
     */
    private fun nativeTrace(exit: ApplicationExitInfo): String? {
        val bytes = try {
            exit.traceInputStream?.use { it.readBytes() } ?: return null
        } catch (e: Exception) {
            return null
        }
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                current.append(c.toChar())
            } else {
                val text = current.toString()
                // Build ids are long hex runs and only add noise.
                if (text.length >= 4 && strings.lastOrNull() != text && !BUILD_ID.matches(text)) strings += text
                current.clear()
            }
            if (strings.size >= TRACE_STRINGS) break
        }
        return strings.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun stackFile(context: Context) = File(context.filesDir, "crash.txt")
}
