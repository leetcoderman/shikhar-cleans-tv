package com.gupta.airplayreceiver;

import android.util.Log;

/** JNI front of libgtvairplay (UxPlay's AirPlay library). Callbacks arrive on native threads. */
public final class NativeReceiver {
    static final String TAG = "GuptaAirPlay";

    static {
        System.loadLibrary("gtvairplay");
    }

    /** Receives everything the native side reports. Methods are called on native threads. */
    public interface Listener {
        void onConnectionCount(int count);
        void onClientRequest(String name, String model, String deviceId);
        void onVideoFormat(boolean h265, int widthSource, int heightSource, int width, int height);
        void onVideoData(byte[] data, boolean h265, long ntpTime);
        void onVideoReset(int type);
        void onVideoFlush();
        void onAudioFormat(int ct, int samplesPerFrame);
        void onAudioData(byte[] data, int ct);
        void onAudioFlush();
        void onVolume(float db);
        void onPin(String pin);
        void onMirrorRunning(boolean running);
        void onSessionReset();
    }

    private static volatile Listener listener;

    private NativeReceiver() {}

    static void setListener(Listener l) {
        listener = l;
    }

    /**
     * Starts the AirPlay server and its mDNS advertisement.
     * @param pinMode 0 = open, 1 = PIN shown on screen on first pairing (UxPlay's -pin)
     * @return the TCP port in use, or a negative error code
     */
    static native int nativeStart(String name, byte[] hwAddr, String deviceId, String keyFile, int width,
                                  int height, int fps, int pinMode, int fixedPin, String hostLabel,
                                  String ipv4, boolean h265, boolean debug);

    static native void nativeStop();

    /** Drops the current client (as UxPlay does when the client stops sending feedback). */
    static native void nativeDisconnect();

    // ---- called from native code ----

    static void onLog(int level, String msg) {
        if (msg == null) return;
        msg = msg.trim();
        if (level <= 3) Log.e(TAG, msg);       // LOGGER_ERR and worse
        else if (level <= 5) Log.w(TAG, msg);  // LOGGER_WARNING, LOGGER_NOTICE
        else if (level == 6) Log.i(TAG, msg);
        else Log.d(TAG, msg);
    }

    static void onConnectionCount(int n) {
        Listener l = listener;
        if (l != null) l.onConnectionCount(n);
    }

    static void onClientRequest(String name, String model, String deviceId) {
        Listener l = listener;
        if (l != null) l.onClientRequest(name, model, deviceId);
    }

    static void onVideoFormat(boolean h265, int ws, int hs, int w, int h) {
        Listener l = listener;
        if (l != null) l.onVideoFormat(h265, ws, hs, w, h);
    }

    static void onVideoData(byte[] data, boolean h265, long ntp) {
        Listener l = listener;
        if (l != null) l.onVideoData(data, h265, ntp);
    }

    static void onVideoReset(int type) {
        Listener l = listener;
        if (l != null) l.onVideoReset(type);
    }

    static void onVideoFlush() {
        Listener l = listener;
        if (l != null) l.onVideoFlush();
    }

    static void onAudioFormat(int ct, int spf) {
        Listener l = listener;
        if (l != null) l.onAudioFormat(ct, spf);
    }

    static void onAudioData(byte[] data, int ct) {
        Listener l = listener;
        if (l != null) l.onAudioData(data, ct);
    }

    static void onAudioFlush() {
        Listener l = listener;
        if (l != null) l.onAudioFlush();
    }

    static void onVolume(float db) {
        Listener l = listener;
        if (l != null) l.onVolume(db);
    }

    static void onPin(String pin) {
        Listener l = listener;
        if (l != null) l.onPin(pin);
    }

    static void onMirrorRunning(boolean running) {
        Listener l = listener;
        if (l != null) l.onMirrorRunning(running);
    }

    static void onSessionReset() {
        Listener l = listener;
        if (l != null) l.onSessionReset();
    }
}
