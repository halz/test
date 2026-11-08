package io.github.halz.macremote.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Runs one command over SSH ("Remote Login" on the Mac, OpenSSH Server on
 * Windows) with the profile's credentials. Host keys are not pinned: the
 * expected transport is a Tailscale tunnel (see README).
 */
object SshExec {

    /** [exitStatus] is -1 when the command never reported one (e.g. sshd went down). */
    data class Result(val exitStatus: Int, val stdout: String, val stderr: String)

    suspend fun run(
        host: String,
        username: String,
        password: String,
        command: String,
        stdin: String? = null,
        timeoutMillis: Long = 15_000,
    ): Result = withContext(Dispatchers.IO) {
        val session = JSch().getSession(username, host, 22)
        try {
            session.setPassword(password.toByteArray(Charsets.UTF_8))
            session.setConfig("StrictHostKeyChecking", "no")
            session.timeout = 15_000
            session.connect()
            val channel = session.openChannel("exec") as ChannelExec
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.setCommand(command)
            // setOutputStream receives the remote stdout; getOutputStream
            // writes the remote stdin — two different streams.
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            val input = channel.getOutputStream()
            channel.connect()
            if (stdin != null) {
                input.write(stdin.toByteArray(Charsets.UTF_8))
                input.flush()
            }
            input.close()
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!channel.isClosed && System.currentTimeMillis() < deadline) delay(100)
            val status = channel.exitStatus
            channel.disconnect()
            Result(status, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
        } finally {
            runCatching { session.disconnect() }
        }
    }
}
