package com.gupta.airplayreceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;

/**
 * Foreground service that keeps the AirPlay receiver advertised on the LAN, starts at boot, and
 * opens {@link MirrorActivity} when a Mac starts mirroring.
 */
public class ReceiverService extends Service implements NativeReceiver.Listener {
    private static final String TAG = NativeReceiver.TAG;
    private static final String CHANNEL = "receiver";
    static final String ACTION_RESTART = "com.gupta.airplayreceiver.RESTART";

    static final VideoDecoder video = new VideoDecoder();
    static final AudioPlayer audio = new AudioPlayer();

    static volatile String status = "Starting…";
    static volatile String address = "";
    static volatile int port;
    static volatile String clientName;
    private static volatile boolean mirroring;

    private HandlerThread worker;
    private Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager cm;
    private String startedIp;
    private static final long RECHECK_MS = 30_000;
    /** Safety net in case a network change is missed: re-checks the address every 30 s. */
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            ensureStarted();
            handler.postDelayed(this, RECHECK_MS);
        }
    };

    static void start(Context c) {
        c.startForegroundService(new Intent(c, ReceiverService.class));
    }

    static boolean isMirroring() {
        return mirroring;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        startForeground(1, buildNotification());

        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("airplay-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        worker = new HandlerThread("airplay-control");
        worker.start();
        handler = new Handler(worker.getLooper());
        NativeReceiver.setListener(this);
        video.start();

        cm = getSystemService(ConnectivityManager.class);
        // Every network, not just the default one: the panel's Ethernet can get an address at boot and
        // lose it later while Wi-Fi stays the default, and the advertised address must follow.
        NetworkRequest all = new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.registerNetworkCallback(all, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { handler.postDelayed(ReceiverService.this::ensureStarted, 1500); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { handler.postDelayed(ReceiverService.this::ensureStarted, 1500); }
            @Override public void onLost(Network n) { handler.postDelayed(ReceiverService.this::ensureStarted, 1500); }
        });
        handler.post(this::ensureStarted);
        handler.postDelayed(recheck, RECHECK_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            handler.post(() -> { stopNative(); ensureStarted(); });
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(Prefs.NAME + " AirPlay receiver")
                .setContentText("Ready for Screen Mirroring from a Mac")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** Starts the server on the current LAN address, or restarts it if the address changed. */
    private void ensureStarted() {
        String ip = pickIpv4();
        if (ip == null) {
            if (startedIp != null) stopNative();
            status = "Waiting for the network";
            return;
        }
        if (ip.equals(startedIp)) return;
        if (startedIp != null && mirroring) return;  // don't cut a running session; re-checked later
        if (startedIp != null) stopNative();
        Prefs p = new Prefs(this);
        boolean uhd = p.uhd();
        byte[] hw = p.hwAddr();
        String keyFile = new File(getFilesDir(), "pairing_key.pem").getAbsolutePath();
        int r = NativeReceiver.nativeStart(Prefs.NAME, hw, Prefs.format(hw), keyFile,
                uhd ? 3840 : 1920, uhd ? 2160 : 1080, 30, p.requirePin() ? 1 : 0, 0,
                Prefs.HOST_LABEL, ip, uhd, false);
        if (r > 0) {
            startedIp = ip;
            port = r;
            address = ip;
            status = "Ready";
            Log.i(TAG, "advertising \"" + Prefs.NAME + "\" at " + ip + ":" + r);
        } else {
            status = "Could not start (error " + r + ")";
            Log.e(TAG, "nativeStart failed: " + r);
            handler.postDelayed(this::ensureStarted, 10_000);
        }
    }

    private void stopNative() {
        NativeReceiver.nativeStop();
        startedIp = null;
        endMirroring();
    }

    /**
     * Prefers the default network (the home LAN), then Ethernet, then Wi-Fi, then any other network
     * with a private IPv4 address.
     */
    private String pickIpv4() {
        String best = null;
        int bestRank = 99;
        Network active = cm.getActiveNetwork();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (caps == null || lp == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            int rank = (n.equals(active) ? 0 : 10)
                    + (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 0
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 2);
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && la.getAddress().isSiteLocalAddress() && rank < bestRank) {
                    best = la.getAddress().getHostAddress();
                    bestRank = rank;
                }
            }
        }
        return best;
    }

    @Override
    public void onDestroy() {
        NativeReceiver.setListener(null);
        NativeReceiver.nativeStop();
        video.stop();
        audio.stop();
        if (multicastLock != null) multicastLock.release();
        worker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- mirroring session ----

    private void beginMirroring() {
        if (mirroring) return;
        mirroring = true;
        status = "Mirroring" + (clientName != null ? " from " + clientName : "");
        main.post(() -> MirrorActivity.open(this, null));
    }

    private void endMirroring() {
        boolean was = mirroring;
        mirroring = false;
        video.reset();
        audio.stop();
        if (startedIp != null) status = "Ready";
        main.post(MirrorActivity::closeIfOpen);
        if (was) Log.i(TAG, "mirroring ended");
    }

    // ---- NativeReceiver.Listener (native threads) ----

    @Override public void onConnectionCount(int count) {
        if (count == 0) endMirroring();
    }

    @Override public void onClientRequest(String name, String model, String deviceId) {
        clientName = name;
    }

    @Override public void onVideoFormat(boolean h265, int ws, int hs, int w, int h) {
        Log.i(TAG, "video format " + (h265 ? "H.265 " : "H.264 ") + ws + "x" + hs + " -> " + w + "x" + h);
        beginMirroring();
    }

    @Override public void onVideoData(byte[] data, boolean h265, long ntp) {
        if (!mirroring) beginMirroring();
        video.feed(data, h265);
    }

    @Override public void onVideoReset(int type) {
        endMirroring();
    }

    @Override public void onVideoFlush() {}

    @Override public void onAudioFormat(int ct, int spf) {
        audio.start(ct, spf);
    }

    @Override public void onAudioData(byte[] data, int ct) {
        audio.feed(data);
    }

    @Override public void onAudioFlush() {
        audio.flush();
    }

    @Override public void onVolume(float db) {
        audio.setVolume(db);
    }

    @Override public void onPin(String pin) {
        main.post(() -> MirrorActivity.open(this, pin));
    }

    @Override public void onMirrorRunning(boolean running) {
        if (running) beginMirroring(); else endMirroring();
    }

    @Override public void onSessionReset() {
        endMirroring();
    }
}
