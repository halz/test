package io.github.halz.macremote.desktop.session

import io.github.halz.macremote.data.Profile
import io.github.halz.macremote.desktop.data.AppDirs
import java.io.File

/**
 * Windows targets open in the built-in Remote Desktop client (mstsc.exe).
 * The password goes to the Windows Credential Manager (cmdkey, the same
 * place mstsc's "remember me" uses) since .rdp files cannot carry one.
 */
object RdpLauncher {

    fun launch(profile: Profile, password: String) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            error("Windows への接続は Windows 版でのみ使えます（mstsc を起動します）")
        }
        val address = if (profile.port == 3389) profile.host else "${profile.host}:${profile.port}"
        if (profile.username.isNotEmpty() && password.isNotEmpty()) {
            val stored = ProcessBuilder(
                "cmdkey", "/generic:TERMSRV/${profile.host}", "/user:${profile.username}", "/pass:$password",
            ).redirectErrorStream(true).start()
            stored.inputStream.readAllBytes()
            if (stored.waitFor() != 0) error("資格情報を保存できませんでした (cmdkey)")
        }
        val file = File(File(AppDirs.root, "rdp").apply { mkdirs() }, "${profile.id}.rdp")
        file.writeText(rdpFile(address, profile.username), Charsets.UTF_8)
        ProcessBuilder("mstsc.exe", file.absolutePath).start()
    }

    /** Windowed, resizing with the window, clipboard shared. */
    internal fun rdpFile(address: String, username: String): String = buildString {
        appendLine("full address:s:$address")
        if (username.isNotEmpty()) appendLine("username:s:$username")
        appendLine("screen mode id:i:1")
        appendLine("desktopwidth:i:1920")
        appendLine("desktopheight:i:1080")
        appendLine("dynamic resolution:i:1")
        appendLine("smart sizing:i:1")
        appendLine("redirectclipboard:i:1")
        appendLine("prompt for credentials:i:0")
        appendLine("authentication level:i:2")
    }
}
