package com.gupta.dock;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps the panel's ADB off the network. adbd here takes connections without a key and listens on every interface
 * (port 5555), so anyone on the Wi-Fi gets a shell. Setting {@code service.adb.listen_addrs} to
 * {@code tcp:localhost:5555} and restarting adbd makes it listen on loopback only: the dock's own Close app, Restart
 * and Clean RAM, and the iPhone bridge's key injector all connect from 127.0.0.1 and keep working.
 *
 * The property is not persistent, so it is set again whenever the dock starts (every boot); until then, for the first
 * half minute or so after a boot, 5555 is open as before. Setting it from the shell works only because this
 * firmware runs SELinux permissive.
 *
 * The Mac (theme pushes, adb root, installs) gets in through More tools → Mac ADB: that clears the property for
 * OPEN_MS, then the guard locks again. A restart of adbd drops every ADB connection; the bridge reconnects by itself.
 */
final class AdbGuard {
    private static final String TAG = "GuptaDock";
    static final String LOCAL_ONLY = "tcp:localhost:5555";
    /** How long Mac ADB leaves 5555 open to the network. */
    static final long OPEN_MS = 15 * 60 * 1000L;
    /** At boot adbd may not be up when the dock starts: try this often, this many times. */
    private static final long RETRY_MS = 5000L;
    private static final int TRIES = 36;

    interface Listener {
        void onAdbChanged(boolean open, boolean ok);
    }

    private final Handler handler;
    private final Listener listener;
    private final Runnable relock = () -> lock(true);
    private long openUntil;

    AdbGuard(Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    boolean isOpen() {
        return SystemClock.elapsedRealtime() < openUntil;
    }

    long openUntilWallClock() {
        return System.currentTimeMillis() + (openUntil - SystemClock.elapsedRealtime());
    }

    /** Listens on localhost only, unless Mac ADB is open; {@code tell} reports the result to the listener. */
    void lock(boolean tell) {
        openUntil = 0;
        handler.removeCallbacks(relock);
        new Thread(() -> {
            for (int i = 0; i < TRIES; i++) {
                if (isOpen()) return;   // Mac ADB was pressed meanwhile
                String sockets = LocalAdb.output("cat /proc/net/tcp /proc/net/tcp6");
                if (sockets != null && !listensOnNetwork(sockets)) {
                    Log.i(TAG, "adb: localhost only");
                    if (tell) handler.post(() -> listener.onAdbChanged(false, true));
                    return;
                }
                if (sockets != null) {
                    Log.i(TAG, "adb: listening on the network, restricting to localhost");
                    // adbd restarts and drops the second connection; the next pass checks the result.
                    if (LocalAdb.output("setprop service.adb.listen_addrs " + LOCAL_ONLY) != null) {
                        LocalAdb.output("setprop ctl.restart adbd");
                    }
                }
                SystemClock.sleep(RETRY_MS);
            }
            Log.w(TAG, "adb: couldn't restrict to localhost");
            if (tell) handler.post(() -> listener.onAdbChanged(false, false));
        }, "dock-adb-lock").start();
    }

    /** Opens 5555 to the network for OPEN_MS (adbd falls back to service.adb.tcp.port), then locks again. */
    void open() {
        openUntil = SystemClock.elapsedRealtime() + OPEN_MS;
        handler.removeCallbacks(relock);
        handler.postDelayed(relock, OPEN_MS);
        new Thread(() -> {
            boolean ok = LocalAdb.output("setprop service.adb.listen_addrs ''") != null;
            if (ok) LocalAdb.output("setprop ctl.restart adbd");
            Log.i(TAG, "adb: open to the network for Mac ADB, ok=" + ok);
            handler.post(() -> {
                if (!ok) {
                    openUntil = 0;
                    handler.removeCallbacks(relock);
                }
                listener.onAdbChanged(true, ok);
            });
        }, "dock-adb-open").start();
    }

    /** True if /proc/net/tcp{,6} shows port 5555 (15B3) listening (state 0A) on the any-address. */
    static boolean listensOnNetwork(String procNet) {
        for (String line : procNet.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4 || !"0A".equals(f[3]) || !f[1].endsWith(":15B3")) continue;
            if (f[1].substring(0, f[1].indexOf(':')).matches("0+")) return true;
        }
        return false;
    }

    void stop() {
        handler.removeCallbacks(relock);
    }
}
