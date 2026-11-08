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
     * The crashing thread's backtrace from the native tombstone, which
     * Android 12+ hands over as a protobuf (system/core/debuggerd/proto/
     * tombstone.proto): signal, abort message, cause, then one line per frame.
     */
    private fun nativeTrace(exit: ApplicationExitInfo): String? {
        val bytes = try {
            exit.traceInputStream?.use { it.readBytes() } ?: return null
        } catch (e: Exception) {
            return null
        }
        return try {
            formatTombstone(Proto(bytes).fields())
        } catch (e: Exception) {
            null
        }
    }

    private fun formatTombstone(top: List<Proto.Field>): String {
        val tid = top.firstOrNull { it.number == 6 }?.varint
        val lines = mutableListOf<String>()
        top.firstOrNull { it.number == 10 }?.message?.let { signal ->
            lines += "signal ${signal.string(2).orEmpty()} ${signal.string(4).orEmpty()} fault=0x${(signal.varint(9) ?: 0).toString(16)}"
        }
        top.firstOrNull { it.number == 14 }?.text?.let { lines += "abort: $it" }
        top.filter { it.number == 15 }.forEach { cause -> cause.message?.string(1)?.let { lines += "cause: $it" } }
        val thread = top.filter { it.number == 16 }
            .mapNotNull { entry -> entry.message?.fields()?.firstOrNull { it.number == 2 }?.message }
            .firstOrNull { it.varint(1) == tid }
        if (thread != null) {
            lines += "thread ${thread.string(2).orEmpty()} (tid $tid)"
            thread.fields().filter { it.number == 4 }.mapNotNull { it.message }.forEachIndexed { i, frame ->
                val file = frame.string(6).orEmpty().substringAfterLast('/')
                val function = frame.string(4)?.let { "$it+${frame.varint(5) ?: 0}" } ?: "?"
                lines += "#%02d pc %08x %s %s".format(i, frame.varint(1) ?: 0, file, function)
            }
        }
        return lines.joinToString("\n")
    }

    /** Just enough protobuf decoding to walk the tombstone. */
    private class Proto(private val data: ByteArray, private val from: Int = 0, private val to: Int = data.size) {
        class Field(val number: Int, val varint: Long?, private val bytes: ByteArray?, private val start: Int, private val end: Int) {
            val message: Proto? get() = bytes?.let { Proto(it, start, end) }
            val text: String? get() = bytes?.let { String(it, start, end - start) }
        }

        fun fields(): List<Field> {
            val result = mutableListOf<Field>()
            var pos = from
            fun readVarint(): Long {
                var shift = 0
                var value = 0L
                while (true) {
                    val b = data[pos++].toInt()
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                }
            }
            while (pos < to) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> result += Field(number, readVarint(), null, 0, 0)
                    1 -> pos += 8
                    2 -> {
                        val length = readVarint().toInt()
                        result += Field(number, null, data, pos, pos + length)
                        pos += length
                    }
                    5 -> pos += 4
                    else -> return result
                }
            }
            return result
        }

        fun varint(number: Int): Long? = fields().firstOrNull { it.number == number }?.varint
        fun string(number: Int): String? = fields().firstOrNull { it.number == number }?.text
    }

    private fun stackFile(context: Context) = File(context.filesDir, "crash.txt")
}
