package io.github.halz.macremote.rfb

/** Server spoke something we do not understand. */
class RfbProtocolException(message: String) : Exception(message)

/** Authentication failed; [reason] is the server-supplied text when available (RFB 3.8). */
class RfbAuthException(val reason: String) : Exception(reason)
