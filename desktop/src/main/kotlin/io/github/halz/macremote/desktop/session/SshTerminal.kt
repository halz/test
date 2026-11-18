package io.github.halz.macremote.desktop.session

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import io.github.halz.macremote.data.Profile
import java.awt.Font
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.UnknownHostException

/** An interactive SSH shell bridged into JediTerm. */
class SshTtyConnector(
    private val session: Session,
    private val channel: ChannelShell,
    private val title: String,
) : TtyConnector {
    private val reader = InputStreamReader(channel.inputStream, Charsets.UTF_8)
    private val output = channel.outputStream

    override fun read(buf: CharArray, offset: Int, length: Int): Int = reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun isConnected(): Boolean = channel.isConnected

    override fun resize(termSize: TermSize) {
        runCatching { channel.setPtySize(termSize.columns, termSize.rows, 0, 0) }
    }

    override fun waitFor(): Int {
        while (!channel.isClosed) Thread.sleep(100)
        return channel.exitStatus
    }

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = title

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

object SshTerminal {
    private const val SSH_PORT = 22
    private const val CONNECT_TIMEOUT_MILLIS = 15_000

    /** Blocking: opens an xterm-256color shell on the profile's computer. */
    fun connect(profile: Profile, password: String, columns: Int = 100, rows: Int = 30): SshTtyConnector {
        val session = JSch().getSession(profile.username, profile.host, SSH_PORT)
        try {
            session.setPassword(password.toByteArray(Charsets.UTF_8))
            // Host keys are not pinned: the transport is the Tailscale tunnel (see README).
            session.setConfig("StrictHostKeyChecking", "no")
            session.setServerAliveInterval(30_000)
            session.connect(CONNECT_TIMEOUT_MILLIS)
            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color")
            channel.setPtySize(columns, rows, 0, 0)
            val connector = SshTtyConnector(session, channel, profile.name.ifEmpty { profile.host })
            channel.connect(CONNECT_TIMEOUT_MILLIS)
            return connector
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw IllegalStateException(describe(e, profile), e)
        }
    }

    fun widget(): JediTermWidget = JediTermWidget(100, 30, DarkSettings())

    fun describe(e: Exception, profile: Profile): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", ignoreCase = true) || message.contains("Auth cancel", ignoreCase = true) ->
                "ユーザ名かパスワードが違います"
            e.cause is ConnectException || message.contains("Connection refused", ignoreCase = true) ->
                "SSH（ポート 22）に接続できません。Mac は「リモートログイン」、Windows は「OpenSSH サーバー」を有効にしてください"
            e.cause is UnknownHostException -> "ホストが見つかりません: ${profile.host}"
            e is JSchException && message.contains("timeout", ignoreCase = true) ->
                "接続がタイムアウトしました（Tailscale の接続を確認してください）"
            else -> message.ifEmpty { e.javaClass.simpleName }
        }
    }

    /** The app's dark palette; "Monospaced" so Japanese falls back to a CJK font. */
    private class DarkSettings : DefaultSettingsProvider() {
        private val foreground = TerminalColor.rgb(0xE8, 0xEC, 0xF2)
        private val background = TerminalColor.rgb(0x0E, 0x11, 0x16)

        override fun getDefaultForeground(): TerminalColor = foreground
        override fun getDefaultBackground(): TerminalColor = background

        // Deprecated, but JediTermWidget still builds its initial style from it.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getDefaultStyle(): TextStyle = TextStyle(foreground, background)

        override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, getTerminalFontSize().toInt())
        override fun getTerminalFontSize(): Float = 15f
    }
}
