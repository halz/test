package io.github.halz.macremote.data

import kotlin.math.abs

/**
 * Switches the Mac's main display to Full HD over SSH. macOS Screen Sharing
 * cannot resize the desktop from the client side, so this drives the free
 * `displayplacer` tool (brew install displayplacer) instead. A lower desktop
 * resolution makes the Mac's UI larger on a phone and shrinks the stream.
 */
object MacDisplay {

    // Non-interactive SSH sessions don't load the shell profile that puts
    // Homebrew on PATH (Apple Silicon: /opt/homebrew, Intel: /usr/local).
    private const val PATH = "PATH=/opt/homebrew/bin:/usr/local/bin:\$PATH"

    /** Switches to Full HD; returns the displayplacer command that restores the previous setup. */
    suspend fun setFullHd(host: String, username: String, password: String): String {
        val list = SshExec.run(host, username, password, "$PATH displayplacer list")
        if (list.exitStatus == 127 || "not found" in list.stderr) {
            error("Mac に displayplacer がありません。Mac のターミナルで brew install displayplacer を実行してください")
        }
        if (list.exitStatus != 0) error(list.stderr.trim().ifBlank { "displayplacer list に失敗しました" })
        val restore = list.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("displayplacer \"") }
            ?: error("現在の画面構成を読み取れませんでした")
        val target = fullHdArguments(list.stdout) ?: error("Full HD に近い解像度が見つかりませんでした")
        val result = SshExec.run(host, username, password, "$PATH displayplacer \"$target\"")
        if (result.exitStatus != 0) error((result.stderr + result.stdout).trim().ifBlank { "解像度を変更できませんでした" })
        return restore
    }

    suspend fun restore(host: String, username: String, password: String, restoreCommand: String) {
        val result = SshExec.run(host, username, password, "$PATH $restoreCommand")
        if (result.exitStatus != 0) error((result.stderr + result.stdout).trim().ifBlank { "元に戻せませんでした" })
    }

    private val modePattern = Regex("""mode \d+: res:(\d+)x(\d+)(?: hz:(\d+))?(?: color_depth:(\d+))?( scaling:on)?""")

    /**
     * displayplacer arguments for the main display's mode closest to 1920x1080,
     * preferring an exact match and, among equals, a non-scaled one (a scaled
     * "looks like 1920x1080" mode streams at 3840x2160).
     */
    internal fun fullHdArguments(listOutput: String): String? {
        val block = listOutput.split("Persistent screen id:").drop(1)
            .firstOrNull { "main display" in it } ?: return null
        val id = block.lineSequence().first().trim()
        val best = modePattern.findAll(block).map { m ->
            Mode(
                width = m.groupValues[1].toInt(),
                height = m.groupValues[2].toInt(),
                hz = m.groupValues[3],
                colorDepth = m.groupValues[4],
                scaled = m.groupValues[5].isNotEmpty(),
            )
        }.minByOrNull { mode ->
            (abs(mode.width - 1920) + abs(mode.height - 1080)) * 2 +
                (if (mode.scaled) 1 else 0) - (mode.hz.toIntOrNull() ?: 0) / 1000.0
        } ?: return null
        return buildString {
            append("id:$id res:${best.width}x${best.height}")
            if (best.hz.isNotEmpty()) append(" hz:${best.hz}")
            if (best.colorDepth.isNotEmpty()) append(" color_depth:${best.colorDepth}")
            append(if (best.scaled) " scaling:on" else " scaling:off")
            append(" origin:(0,0) degree:0")
        }
    }

    private data class Mode(
        val width: Int,
        val height: Int,
        val hz: String,
        val colorDepth: String,
        val scaled: Boolean,
    )
}
