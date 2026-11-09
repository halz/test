package io.github.halz.macremote.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The last remote screen of each connection, kept as a small JPEG so the
 * connection list can show it. The file's time doubles as "last used".
 */
object Thumbnails {
    private const val WIDTH = 640

    fun file(context: Context, profileId: String) = File(File(context.filesDir, "thumbnails"), "$profileId.jpg")

    fun save(context: Context, profileId: String, source: Bitmap) {
        if (source.width <= 1 || source.height <= 1) return
        val height = (source.height.toLong() * WIDTH / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, WIDTH, height, true)
        val target = file(context, profileId)
        target.parentFile?.mkdirs()
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== source) scaled.recycle()
    }

    fun load(context: Context, profileId: String): Bitmap? =
        file(context, profileId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** When the connection was last left, or null if never. */
    fun lastUsed(context: Context, profileId: String): Long? =
        file(context, profileId).takeIf { it.exists() }?.lastModified()

    fun delete(context: Context, profileId: String) {
        file(context, profileId).delete()
    }
}
