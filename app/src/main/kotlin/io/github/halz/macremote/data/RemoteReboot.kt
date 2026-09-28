package io.github.halz.macremote.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Force-restarts the Mac over SSH ("Remote Login"), the same transport the
 * SFTP browser uses — it works even when the VNC side is frozen. Requires the
 * profile's account to be an administrator; the login password is fed to
 * `sudo -S` on stdin. Host keys are not pinned (Tailscale tunnel, see README).
 */
object RemoteReboot {

    suspend fun reboot(host: String, username: String, password: String): Unit =
        withContext(Dispatchers.IO) {
            val session = JSch().getSession(username, host, 22)
            try {
                session.setPassword(password.toByteArray(Charsets.UTF_8))
                session.setConfig("StrictHostKeyChecking", "no")
                session.timeout = 15_000
                session.connect()
                val channel = session.openChannel("exec") as ChannelExec
                val stderr = ByteArrayOutputStream()
                channel.setCommand("sudo -S -p '' shutdown -r now")
                val stdin = channel.outputStream
                channel.setErrStream(stderr)
                channel.connect()
                stdin.write((password + "\n").toByteArray(Charsets.UTF_8))
                stdin.flush()
                stdin.close()
                val deadline = System.currentTimeMillis() + 15_000
                while (!channel.isClosed && System.currentTimeMillis() < deadline) delay(100)
                val status = channel.exitStatus
                channel.disconnect()
                // shutdown tears sshd down, so the exit status may never arrive
                // (-1); only a real non-zero status is a failure.
                if (status > 0) {
                    val detail = stderr.toString(Charsets.UTF_8.name()).trim()
                    error(
                        if ("try again" in detail.lowercase() || "sorry" in detail.lowercase()) {
                            "sudo に失敗しました。このアカウントが Mac の管理者か確認してください"
                        } else {
                            detail.ifBlank { "exit $status" }
                        }
                    )
                }
            } finally {
                runCatching { session.disconnect() }
            }
        }
}
