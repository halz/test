package io.github.halz.macremote.data

/**
 * Force-restarts the remote computer over SSH, the same transport the SFTP
 * browser uses — it works even when the screen-sharing side is frozen.
 * Requires an administrator account: on the Mac the login password is fed to
 * `sudo -S`; on Windows, OpenSSH Server runs administrators elevated.
 */
object RemoteReboot {

    suspend fun reboot(profile: Profile, password: String) {
        val result = when (profile.protocol) {
            Protocol.VNC -> SshExec.run(
                profile.host, profile.username, password,
                command = "sudo -S -p '' shutdown -r now",
                stdin = password + "\n",
            )
            Protocol.RDP -> SshExec.run(
                profile.host, profile.username, password,
                command = "shutdown /r /f /t 0",
            )
        }
        // shutdown tears sshd down, so the exit status may never arrive (-1);
        // only a real non-zero status is a failure.
        if (result.exitStatus > 0) {
            val detail = (result.stderr + result.stdout).trim()
            val lower = detail.lowercase()
            error(
                when {
                    "try again" in lower || "sorry" in lower ->
                        "sudo に失敗しました。このアカウントが Mac の管理者か確認してください"
                    "access is denied" in lower || "アクセスが拒否" in detail ->
                        "権限がありません。このアカウントが Windows の管理者か確認してください"
                    else -> detail.ifBlank { "exit ${result.exitStatus}" }
                }
            )
        }
    }
}
