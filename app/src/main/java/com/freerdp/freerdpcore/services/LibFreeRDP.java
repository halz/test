package com.freerdp.freerdpcore.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Java face of FreeRDP's Android JNI layer (libfreerdp-android.so,
 * built by tools/build-freerdp-android.sh). The native code binds to this
 * exact class name and calls back into the static On* methods below by name
 * and signature, so both must match the pinned FreeRDP revision. Callbacks
 * arrive on FreeRDP's session thread and are routed to a per-instance
 * {@link Listener}; the ones this app does not use are accepted and ignored.
 */
public final class LibFreeRDP {

    public interface Listener {
        void onConnectionSuccess();

        void onConnectionFailure(String reason);

        void onDisconnected();

        void onGraphicsResize(int width, int height);

        void onGraphicsUpdate(int x, int y, int width, int height);

        void onRemoteClipboardChanged(String data);
    }

    private static final Map<Long, Listener> listeners = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private LibFreeRDP() {
    }

    /**
     * Loads the native library; throws if this build was made without it.
     * FreeRDP's log is routed to {@link #logFile} so connection errors can be
     * shown in the app; the variables must be set before the first log call,
     * which happens while the library loads.
     */
    public static synchronized void load(Context context) {
        if (!loaded) {
            File log = logFile(context);
            if (log.length() > 1024 * 1024) log.delete();
            try {
                Os.setenv("WLOG_APPENDER", "FILE", true);
                Os.setenv("WLOG_LEVEL", "INFO", true);
                Os.setenv("WLOG_PREFIX", "[%lv] %fn: ", true);
                Os.setenv("WLOG_FILEAPPENDER_OUTPUT_FILE_PATH", log.getParent(), true);
                Os.setenv("WLOG_FILEAPPENDER_OUTPUT_FILE_NAME", log.getName(), true);
            } catch (ErrnoException ignored) {
                // Logging falls back to logcat.
            }
            // Each library is loaded explicitly, dependencies first: WinPR's
            // JNI_OnLoad stores the JavaVM its UTF-8/UTF-16 conversion calls
            // back into, and it only runs for libraries loaded from Java.
            System.loadLibrary("winpr3");
            System.loadLibrary("freerdp3");
            System.loadLibrary("freerdp-client3");
            System.loadLibrary("freerdp-android");
            loaded = true;
        }
    }

    public static File logFile(Context context) {
        return new File(context.getFilesDir(), "freerdp.log");
    }

    public static long newInstance(Context context, Listener listener) {
        long inst = freerdp_new(context);
        if (inst != 0) listeners.put(inst, listener);
        return inst;
    }

    public static void freeInstance(long inst) {
        listeners.remove(inst);
        freerdp_free(inst);
    }

    public static boolean parseArguments(long inst, String[] args) {
        return freerdp_parse_arguments(inst, args);
    }

    public static boolean connect(long inst) {
        return freerdp_connect(inst);
    }

    public static boolean disconnect(long inst) {
        return freerdp_disconnect(inst);
    }

    public static String lastError(long inst) {
        return freerdp_get_last_error_string(inst);
    }

    public static boolean updateGraphics(long inst, Bitmap bitmap, int x, int y, int width, int height) {
        return freerdp_update_graphics(inst, bitmap, x, y, width, height);
    }

    public static boolean sendCursorEvent(long inst, int x, int y, int flags) {
        return freerdp_send_cursor_event(inst, x, y, flags);
    }

    /** {@code virtualKey} is a Windows VK_* code; the native side maps it to a scancode. */
    public static boolean sendKeyEvent(long inst, int virtualKey, boolean down) {
        return freerdp_send_key_event(inst, virtualKey, down);
    }

    /** {@code utf16Unit} is one UTF-16 code unit (surrogate pairs are sent as two). */
    public static boolean sendUnicodeKeyEvent(long inst, int utf16Unit, boolean down) {
        return freerdp_send_unicodekey_event(inst, utf16Unit, down);
    }

    public static boolean sendClipboardData(long inst, String data) {
        return freerdp_send_clipboard_data(inst, data);
    }

    private static native long freerdp_new(Context context);

    private static native void freerdp_free(long inst);

    private static native boolean freerdp_parse_arguments(long inst, String[] args);

    private static native boolean freerdp_connect(long inst);

    private static native boolean freerdp_disconnect(long inst);

    private static native String freerdp_get_last_error_string(long inst);

    private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y,
                                                          int width, int height);

    private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);

    private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);

    private static native boolean freerdp_send_unicodekey_event(long inst, int keycode, boolean down);

    private static native boolean freerdp_send_clipboard_data(long inst, String data);

    // ---- Callbacks from native code. Names and signatures are fixed by the JNI layer. ----

    private static void OnConnectionSuccess(long inst) {
        Listener l = listeners.get(inst);
        if (l != null) l.onConnectionSuccess();
    }

    private static void OnConnectionFailure(long inst) {
        Listener l = listeners.get(inst);
        if (l != null) l.onConnectionFailure(freerdp_get_last_error_string(inst));
    }

    private static void OnPreConnect(long inst) {
    }

    private static void OnDisconnecting(long inst) {
    }

    private static void OnDisconnected(long inst) {
        Listener l = listeners.get(inst);
        if (l != null) l.onDisconnected();
    }

    private static void OnSettingsChanged(long inst, int width, int height, int bpp) {
        Listener l = listeners.get(inst);
        if (l != null) l.onGraphicsResize(width, height);
    }

    // Credentials are always passed on the command line; a prompt means they
    // were rejected, so give up rather than retry.
    private static boolean OnAuthenticate(long inst, StringBuilder username, StringBuilder domain,
                                          StringBuilder password) {
        return false;
    }

    private static boolean OnGatewayAuthenticate(long inst, StringBuilder username,
                                                 StringBuilder domain, StringBuilder password) {
        return false;
    }

    // Not reached with /cert:ignore; kept so the native lookup always resolves.
    private static int OnVerifyCertificateEx(long inst, String host, long port, String commonName,
                                             String subject, String issuer, String fingerprint,
                                             long flags) {
        return 2;
    }

    private static int OnVerifyChangedCertificateEx(long inst, String host, long port,
                                                    String commonName, String subject, String issuer,
                                                    String fingerprint, String oldSubject,
                                                    String oldIssuer, String oldFingerprint,
                                                    long flags) {
        return 2;
    }

    private static boolean OnExperimentalFeature(long inst, int feature) {
        return false;
    }

    private static void OnGraphicsUpdate(long inst, int x, int y, int width, int height) {
        Listener l = listeners.get(inst);
        if (l != null) l.onGraphicsUpdate(x, y, width, height);
    }

    private static void OnGraphicsResize(long inst, int width, int height, int bpp) {
        Listener l = listeners.get(inst);
        if (l != null) l.onGraphicsResize(width, height);
    }

    private static void OnRemoteClipboardChanged(long inst, String data) {
        Listener l = listeners.get(inst);
        if (l != null) l.onRemoteClipboardChanged(data);
    }

    private static void OnRemoteClipboardImageChanged(long inst, byte[] data) {
    }

    private static void OnPointerSet(long inst, int[] pixels, int width, int height, int hotX, int hotY) {
    }

    private static void OnPointerSetNull(long inst) {
    }

    private static void OnPointerSetDefault(long inst) {
    }

    private static void OnReconnecting(long inst) {
    }

    private static void OnRailWindowUpdate(long inst, long windowId, int width, int height, int[] pixels) {
    }

    private static void OnRailWindowMove(long inst, long windowId, int x, int y, int width, int height) {
    }

    private static void OnRailWindowHide(long inst, long windowId) {
    }

    private static void OnRailWindowDestroy(long inst, long windowId) {
    }

    private static void OnRailSessionEnd(long inst) {
    }

    private static void OnRailMonitoredDesktop(long inst, long[] windowIds, long activeWindowId) {
    }
}
