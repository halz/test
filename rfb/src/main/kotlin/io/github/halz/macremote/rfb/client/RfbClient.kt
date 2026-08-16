package io.github.halz.macremote.rfb.client

import io.github.halz.macremote.rfb.RfbProtocolException
import io.github.halz.macremote.rfb.encoding.CopyRectDecoder
import io.github.halz.macremote.rfb.encoding.Decoder
import io.github.halz.macremote.rfb.encoding.Encodings
import io.github.halz.macremote.rfb.encoding.RawDecoder
import io.github.halz.macremote.rfb.encoding.ZlibDecoder
import io.github.halz.macremote.rfb.fb.Framebuffer
import io.github.halz.macremote.rfb.fb.Rect
import io.github.halz.macremote.rfb.keysym.Keysyms
import io.github.halz.macremote.rfb.messages.ClientMessages
import io.github.halz.macremote.rfb.protocol.InitMessages
import io.github.halz.macremote.rfb.protocol.PixelFormat
import io.github.halz.macremote.rfb.protocol.ProtocolVersion
import io.github.halz.macremote.rfb.protocol.SecurityNegotiation
import io.github.halz.macremote.rfb.transport.RfbSocket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class RfbEvent {
    data class Connected(val width: Int, val height: Int, val serverName: String) : RfbEvent()
    data class Resized(val width: Int, val height: Int) : RfbEvent()
    data class Updated(val rects: List<Rect>) : RfbEvent()
    data object Bell : RfbEvent()
    data class ServerCutText(val text: String) : RfbEvent()
}

/**
 * A single RFB session. Call [run] from a Dispatchers.IO coroutine: it
 * connects, authenticates, then processes server messages until the coroutine
 * is cancelled, the server disconnects (normal return), or an error is thrown.
 *
 * The input senders (sendPointer/sendKey/...) are safe to call from any
 * coroutine once [RfbEvent.Connected] has been emitted.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) {
    val framebuffer = Framebuffer(1, 1)

    private val _events = MutableSharedFlow<RfbEvent>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<RfbEvent> = _events

    private var socket: RfbSocket? = null
    private val writeMutex = Mutex()

    private val decoders: Map<Int, Decoder> = mapOf(
        Encodings.RAW to RawDecoder(),
        Encodings.COPY_RECT to CopyRectDecoder(),
        Encodings.ZLIB to ZlibDecoder(),
    )

    suspend fun run() {
        val sock = RfbSocket.connect(host, port)
        socket = sock
        try {
            val minor = ProtocolVersion.handshake(sock)
            SecurityNegotiation.authenticate(sock, minor, username, password)
            InitMessages.clientInit(sock)
            val serverInit = InitMessages.readServerInit(sock)
            framebuffer.resize(serverInit.width, serverInit.height)

            writeMutex.withLock {
                ClientMessages.setPixelFormat(sock, PixelFormat.CLIENT)
                ClientMessages.setEncodings(
                    sock,
                    listOf(Encodings.ZLIB, Encodings.COPY_RECT, Encodings.RAW, Encodings.PSEUDO_DESKTOP_SIZE),
                )
                ClientMessages.framebufferUpdateRequest(sock, false, 0, 0, serverInit.width, serverInit.height)
            }
            _events.emit(RfbEvent.Connected(serverInit.width, serverInit.height, serverInit.name))

            while (true) {
                currentCoroutineContext().ensureActive()
                when (val messageType = sock.readU8()) {
                    0 -> handleFramebufferUpdate(sock)
                    1 -> { // SetColourMapEntries: we request true colour, but consume it anyway
                        sock.skip(1)
                        sock.skip(2)
                        val count = sock.readU16()
                        sock.skip(count * 6)
                    }
                    2 -> _events.emit(RfbEvent.Bell)
                    3 -> {
                        sock.skip(3)
                        val length = sock.readU32().toInt()
                        val text = String(sock.readBytes(length), Charsets.ISO_8859_1)
                        _events.emit(RfbEvent.ServerCutText(text))
                    }
                    else -> throw RfbProtocolException("unknown server message type $messageType")
                }
            }
        } finally {
            socket = null
            sock.close()
        }
    }

    private suspend fun handleFramebufferUpdate(sock: RfbSocket) {
        sock.skip(1)
        val rectCount = sock.readU16()
        val updated = ArrayList<Rect>(rectCount)
        var resized = false
        for (i in 0 until rectCount) {
            val x = sock.readU16()
            val y = sock.readU16()
            val w = sock.readU16()
            val h = sock.readU16()
            val encoding = sock.readS32()
            when (encoding) {
                Encodings.PSEUDO_DESKTOP_SIZE -> {
                    framebuffer.resize(w, h)
                    resized = true
                    _events.emit(RfbEvent.Resized(w, h))
                }
                else -> {
                    val decoder = decoders[encoding]
                        ?: throw RfbProtocolException("server sent unrequested encoding $encoding")
                    val rect = Rect(x, y, w, h)
                    decoder.decode(sock, rect, framebuffer)
                    updated.add(rect)
                }
            }
        }
        if (updated.isNotEmpty()) {
            _events.emit(RfbEvent.Updated(updated))
        }
        // Keep exactly one update request in flight. After a resize, request a
        // full (non-incremental) frame since the old contents are gone.
        writeMutex.withLock {
            ClientMessages.framebufferUpdateRequest(sock, !resized, 0, 0, framebuffer.width, framebuffer.height)
        }
    }

    suspend fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        val sock = socket ?: return
        writeMutex.withLock {
            ClientMessages.pointerEvent(sock, buttonMask, x.coerceIn(0, framebuffer.width - 1), y.coerceIn(0, framebuffer.height - 1))
        }
    }

    suspend fun sendKey(keysym: Int, down: Boolean) {
        val sock = socket ?: return
        writeMutex.withLock { ClientMessages.keyEvent(sock, down, keysym) }
    }

    /** Types a string by sending down/up key events per code point. */
    suspend fun sendText(text: String) {
        val sock = socket ?: return
        writeMutex.withLock {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val keysym = Keysyms.forCodePoint(codePoint)
                ClientMessages.keyEvent(sock, true, keysym)
                ClientMessages.keyEvent(sock, false, keysym)
                i += Character.charCount(codePoint)
            }
        }
    }

    suspend fun sendCutText(text: String) {
        val sock = socket ?: return
        writeMutex.withLock { ClientMessages.clientCutText(sock, text) }
    }

    /** Unblocks a reader stuck in a blocking read; run() then exits with an IOException. */
    fun close() {
        socket?.close()
    }
}
