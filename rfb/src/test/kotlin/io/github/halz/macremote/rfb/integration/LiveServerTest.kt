package io.github.halz.macremote.rfb.integration

import io.github.halz.macremote.rfb.client.RfbEvent
import io.github.halz.macremote.rfb.client.RfbClient
import io.github.halz.macremote.rfb.messages.PointerButtons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * End-to-end protocol test against a real x11vnc server on a headless Xvfb
 * display. Runs only where those binaries exist (this project's CI container);
 * skips cleanly on developer machines that lack them.
 *
 * Covers: RFB 3.8 handshake, VNC password auth (type 2), true-colour
 * SetPixelFormat, a full framebuffer update decoded from a known solid colour,
 * and a PointerEvent verified via xdotool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveServerTest {

    private val display = ":99"
    private val port = 5905
    private val password = "testpass"
    private val width = 320
    private val height = 240
    // 0x3366cc; x11vnc reports colours exactly for a solid root.
    private val solid = 0xFF3366CC.toInt()

    private var xvfb: Process? = null
    private var vnc: Process? = null

    private fun binaryExists(name: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }

    @BeforeAll
    fun startServer() {
        val available = listOf("Xvfb", "x11vnc", "xsetroot").all { binaryExists(it) }
        assumeTrue(available, "Xvfb/x11vnc/xsetroot not installed; skipping live-server test")

        xvfb = ProcessBuilder("Xvfb", display, "-screen", "0", "${width}x${height}x24")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        Thread.sleep(1500)

        val passwdFile = File.createTempFile("vncpass", "").apply { deleteOnExit() }
        run("x11vnc", "-storepasswd", password, passwdFile.absolutePath)

        vnc = ProcessBuilder(
            "x11vnc", "-display", display, "-rfbauth", passwdFile.absolutePath,
            "-rfbport", port.toString(), "-forever", "-shared", "-noxdamage",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        Thread.sleep(2500)

        // Paint the root AFTER x11vnc has attached: Xvfb does not repaint the root
        // background into readable memory until a clear, so painting earlier leaves
        // x11vnc's initial capture black (verified empirically).
        run("xsetroot", "-solid", "#3366cc")
        Thread.sleep(1000)
    }

    @AfterAll
    fun stopServer() {
        vnc?.destroy()
        xvfb?.destroy()
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command)
            .apply { environment()["DISPLAY"] = display }
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor()
    }

    @Test
    fun `connects, authenticates and decodes a solid framebuffer`() = runBlocking {
        val client = RfbClient("127.0.0.1", port, username = "", password = password)
        val job = launch(Dispatchers.IO) { runCatching { client.run() } }
        try {
            val connected = withTimeout(10_000) {
                client.events.filterIsInstance<RfbEvent.Connected>().first()
            }
            assertEquals(width, connected.width)
            assertEquals(height, connected.height)

            // The solid colour may arrive in the first full update or a later
            // incremental one; poll updates until all sampled pixels match.
            val samples = listOf(10 to 10, 160 to 120, 300 to 200)
            val fb = client.framebuffer
            withTimeout(15_000) {
                client.events.filterIsInstance<RfbEvent.Updated>().first {
                    samples.all { (x, y) -> fb.pixels[y * fb.width + x] == solid }
                }
            }
            for ((x, y) in samples) {
                assertEquals(solid, fb.pixels[y * fb.width + x], "pixel at $x,$y")
            }
        } finally {
            client.close()
            job.cancel()
        }
    }

    @Test
    fun `pointer event moves the server cursor`() = runBlocking {
        assumeTrue(binaryExists("xdotool"), "xdotool not installed; skipping pointer test")
        val client = RfbClient("127.0.0.1", port, username = "", password = password)
        val job = launch(Dispatchers.IO) { runCatching { client.run() } }
        try {
            withTimeout(10_000) { client.events.filterIsInstance<RfbEvent.Connected>().first() }
            client.sendPointer(0, 200, 150)
            Thread.sleep(500)

            val output = ProcessBuilder("xdotool", "getmouselocation", "--shell")
                .apply { environment()["DISPLAY"] = display }
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            val x = Regex("""X=(\d+)""").find(output)?.groupValues?.get(1)?.toInt() ?: -1
            val y = Regex("""Y=(\d+)""").find(output)?.groupValues?.get(1)?.toInt() ?: -1
            // Allow small slop for server-side rounding.
            assertTrue(kotlin.math.abs(x - 200) <= 2, "cursor X was $x (output: $output)")
            assertTrue(kotlin.math.abs(y - 150) <= 2, "cursor Y was $y")
        } finally {
            client.close()
            job.cancel()
        }
    }
}
