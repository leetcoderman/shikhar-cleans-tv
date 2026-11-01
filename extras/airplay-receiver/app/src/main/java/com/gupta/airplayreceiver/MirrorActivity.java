package com.gupta.airplayreceiver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Full-screen view of the Mac's screen. Finishes (back to the previous app) when mirroring stops. */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback, VideoDecoder.SizeListener {
    private static final String EXTRA_PIN = "pin";
    private static WeakReference<MirrorActivity> current = new WeakReference<>(null);

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView message;
    private int videoW = 16, videoH = 9;
    private boolean closing;

    static void open(Context c, String pin) {
        Intent i = new Intent(c, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (pin != null) i.putExtra(EXTRA_PIN, pin);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Log.e(NativeReceiver.TAG, "cannot open the mirroring screen", e);
        }
    }

    static void closeIfOpen() {
        MirrorActivity a = current.get();
        if (a != null && !a.isFinishing()) {
            a.closing = true;
            a.finish();
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<>(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> layoutSurface());

        surfaceView.getHolder().addCallback(this);
        ReceiverService.video.setSizeListener(this);
        showIntent(getIntent());
        hideSystemBars();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showIntent(intent);
    }

    private void showIntent(Intent i) {
        String pin = i.getStringExtra(EXTRA_PIN);
        if (pin != null) {
            message.setTextSize(64);
            message.setText("Enter this code on your Mac\n\n" + pin);
            message.setVisibility(View.VISIBLE);
        } else {
            message.setTextSize(28);
            message.setText("Connecting to " + (ReceiverService.clientName != null ? ReceiverService.clientName : "the Mac") + "…");
            message.setVisibility(View.VISIBLE);
        }
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onVideoSize(int w, int h) {
        runOnUiThread(() -> {
            videoW = w;
            videoH = h;
            message.setVisibility(View.GONE);
            layoutSurface();
        });
    }

    /** Letterboxes the video into the panel keeping the Mac's aspect ratio. */
    private void layoutSurface() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        int w = W, h = (int) ((long) W * videoH / videoW);
        if (h > H) { h = H; w = (int) ((long) H * videoW / videoH); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        ReceiverService.video.setSurface(h.getSurface());
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        ReceiverService.video.setSurface(null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            stopByUser();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        stopByUser();
    }

    private void stopByUser() {
        closing = true;
        if (ReceiverService.isMirroring()) NativeReceiver.nativeDisconnect();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // AirPlay cannot ask the Mac for a fresh key frame, so the picture could not come back after
        // leaving this screen: end the session instead of leaving a broken one running.
        if (!closing && !isChangingConfigurations()) {
            if (ReceiverService.isMirroring()) NativeReceiver.nativeDisconnect();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        ReceiverService.video.setSizeListener(null);
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}
