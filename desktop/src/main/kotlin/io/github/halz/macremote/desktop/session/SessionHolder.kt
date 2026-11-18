package io.github.halz.macremote.desktop.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.desktop.data.AppDirs
import io.github.halz.macremote.rfb.fb.Framebuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import javax.imageio.ImageIO

/**
 * All live VNC sessions (one per profile, shown as tabs). A finished
 * (failed/closed) session stays listed — its tab shows the error until it is
 * retried or closed.
 */
class SessionHolder(private val scope: CoroutineScope) {

    private val _sessions = MutableStateFlow<List<VncSession>>(emptyList())
    val sessions: StateFlow<List<VncSession>> = _sessions

    private val _thumbnailVersion = MutableStateFlow(0)
    /** Bumped whenever a saved thumbnail changes, so the list reloads it. */
    val thumbnailVersion: StateFlow<Int> = _thumbnailVersion

    private fun snapshot(session: VncSession) {
        if (session.state.value !is SessionState.Connected) return
        val framebuffer = session.client.framebuffer
        val id = session.profile.id
        scope.launch(Dispatchers.IO) {
            runCatching { Thumbnails.save(id, framebuffer) }
            _thumbnailVersion.update { it + 1 }
        }
    }

    fun start(profile: Profile, password: String): VncSession {
        sessionFor(profile.id)?.let { existing ->
            snapshot(existing)
            existing.close()
            _sessions.value = _sessions.value - existing
        }
        return VncSession(profile, password, scope).also { _sessions.value = _sessions.value + it }
    }

    fun sessionFor(profileId: String): VncSession? = _sessions.value.find { it.profile.id == profileId }

    fun close(profileId: String) {
        sessionFor(profileId)?.let { session ->
            snapshot(session)
            session.close()
            _sessions.value = _sessions.value - session
        }
    }

    /** Saves thumbnails synchronously: called while the app is exiting. */
    fun closeAll() {
        _sessions.value.forEach { session ->
            if (session.state.value is SessionState.Connected) {
                runCatching { Thumbnails.save(session.profile.id, session.client.framebuffer) }
            }
            session.close()
        }
        _sessions.value = emptyList()
    }
}

/**
 * The last remote screen of each connection, kept as a small JPEG so the
 * connection list can show it. The file's time doubles as "last used".
 */
object Thumbnails {
    private const val WIDTH = 640

    private fun file(profileId: String) = File(File(AppDirs.root, "thumbnails"), "$profileId.jpg")

    fun save(profileId: String, framebuffer: Framebuffer) {
        val width = framebuffer.width
        val height = framebuffer.height
        val pixels = framebuffer.pixels
        if (width <= 1 || height <= 1 || pixels.size != width * height) return
        val full = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        System.arraycopy(pixels, 0, (full.raster.dataBuffer as DataBufferInt).data, 0, pixels.size)
        val scaledHeight = (height.toLong() * WIDTH / width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(WIDTH, scaledHeight, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(full, 0, 0, WIDTH, scaledHeight, null)
            dispose()
        }
        val target = file(profileId)
        target.parentFile.mkdirs()
        ImageIO.write(scaled, "jpg", target)
    }

    fun load(profileId: String): ImageBitmap? =
        file(profileId).takeIf { it.exists() }?.let { f ->
            runCatching { ImageIO.read(f)?.toComposeImageBitmap() }.getOrNull()
        }

    /** When the connection was last left, or null if never. */
    fun lastUsed(profileId: String): Long? = file(profileId).takeIf { it.exists() }?.lastModified()

    fun delete(profileId: String) {
        file(profileId).delete()
    }
}
