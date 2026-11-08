package io.github.halz.macremote.session

import android.graphics.Bitmap
import io.github.halz.macremote.data.Profile
import kotlinx.coroutines.flow.StateFlow

/** The remote screen as the canvas draws it. */
interface RemoteDisplay {
    val bitmap: Bitmap

    /** Bitmap pixels per server pixel are 1/[divisor]. */
    val divisor: Int

    /** Remote desktop size in server pixels. */
    val width: Int
    val height: Int
}

/**
 * One live connection, whatever the protocol. Input uses X11 keysyms and
 * RFB-style pointer button masks ([io.github.halz.macremote.rfb.messages.PointerButtons]);
 * each implementation translates them for its wire format.
 */
interface RemoteSession {
    val profile: Profile
    val state: StateFlow<SessionState>

    /** Bumped after every applied screen update; the canvas redraws on change. */
    val frame: StateFlow<Long>

    /** Latest text the remote put on its clipboard. */
    val serverClipboard: StateFlow<String?>

    val display: RemoteDisplay

    fun pointer(buttonMask: Int, x: Int, y: Int)

    fun keyPress(keysym: Int, modifiers: List<Int> = emptyList())

    fun text(value: String)

    fun paste(value: String)

    /** Downsampling factor for the display bitmap (1 = full resolution); may be ignored. */
    fun setDisplayDivisor(value: Int)

    fun close()
}
