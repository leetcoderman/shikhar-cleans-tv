package com.gupta.dock;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * Gupta's TV dock: a glass bar at the bottom of the screen, over every app. On Home it stays open; inside an app it
 * shrinks to a small arrow at the bottom edge, opens on a tap and tucks away again HIDE_MS after the last touch.
 *
 * Back and Home are Android's global actions. The panel tools (inputs, annotation, screenshot, screen
 * recording, timer, spotlight, magnifier, curtain) are the RuiPin ones, opened through their public intents, and
 * brightness steps through RuiPin's brightness broadcasts. Eye protection and freeze screen are not here: they go
 * through RuiPin's system-only panel API.
 *
 * Remote: the iPhone bridge injects its keys, and injected keys never reach an accessibility key filter. So Home
 * (com.gathin.tvhome) broadcasts ACTION_FOCUS_BELOW when Down is pressed on its last row, and the dock then makes its
 * window focusable: the arrows move between buttons, select presses, Up or Back hand focus back to Home. Inside apps,
 * the bridge sends ACTION_FOCUS_DOCK when Back is held: the dock opens and takes focus the same way (or only opens, when
 * the bridge is in cursor mode and the cursor can click it); holding Back again hands focus back.
 */
public class DockService extends AccessibilityService {

    private static final String TAG = "GuptaDock";
    private static final String HOME_PKG = "com.gathin.tvhome";
    private static final String HOME_CLASS = "com.gathin.tvhome.MainActivity";
    private static final String ACTION_FOCUS_BELOW = "com.gathin.tvhome.action.FOCUS_BELOW";
    /** From the iPhone bridge (Back held); extra "cursor" is true while its cursor is on. */
    private static final String ACTION_FOCUS_DOCK = "com.gupta.dock.action.FOCUS_DOCK";
    private static final String TOOLS_PKG = "com.fqcx.rptools";
    private static final String AIRPLAY_PKG = "com.gupta.airplayreceiver";
    private static final String REMOTE_PKG = "com.corvofeng.fakeatv";
    private static final long HIDE_MS = 5000L;
    /** How long a notice (Clean RAM's result, failures) stays above the bar. */
    private static final long NOTICE_MS = 3500L;
    /** The remote gives focus back to Home after this long without a key, in case Home was pressed (we never see it). */
    private static final long REMOTE_IDLE_MS = 15000L;
    /** Volume buttons step by this much; the panel's media volume runs 0 to 100. */
    private static final int VOLUME_STEP = 5;
    private static final int GLASS = 0xA6101624;
    private static final int EDGE = 0x40FFFFFF;
    private static final int PRESSED = 0x33FFFFFF;

    // Icon tiles, in the spirit of the Mac's dock: one colour per job, lighter at the top.
    private static final int GRAPHITE = 0xFF636366;
    private static final int INDIGO = 0xFF5856D6;
    private static final int ORANGE = 0xFFFF8A00;
    private static final int TEAL = 0xFF14A8B8;
    private static final int PINK = 0xFFFF2D55;
    private static final int YELLOW = 0xFFFFB800;
    private static final int BLUE = 0xFF0A7AFF;
    private static final int PURPLE = 0xFFA550E0;
    private static final int ANDROID_GREEN = 0xFF2DB872;
    private static final int RED = 0xFFFF3B30;
    private static final int CRIMSON = 0xFFC2185B;
    private static final int DARK_RED = 0xFFA51C1C;
    /** Clean RAM: slate, so it doesn't read as a second Screenshot (teal). */
    private static final int SLATE = 0xFF4F6D8F;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoHide = this::collapse;
    private final Runnable toolCheck = this::checkTools;
    private final Runnable frontCheck = this::checkFront;
    private final Runnable remoteIdle = this::leaveRemote;
    private final Runnable noticeEnd = () -> {
        if ("notice".equals(this.popupHolder.getTag())) closePopup();
    };
    private final BroadcastReceiver focusBelow = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (onHome && root != null && root.getVisibility() == View.VISIBLE && bar.getVisibility() == View.VISIBLE) {
                enterRemote();
            }
        }
    };
    private final BroadcastReceiver focusDock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Not while a RuiPin tool has the screen: its controls are where the dock would be.
            if (root == null || root.getVisibility() != View.VISIBLE) return;
            if (remote) {
                leaveRemote();
                return;
            }
            expand();
            if (!intent.getBooleanExtra("cursor", false)) enterRemote();
        }
    };
    /**
     * AdbGuard (network ADB off, More tools → Mac ADB to open it for 15 min). Off: its Mac ADB tile failed to reopen
     * ADB on the first panel, and the way back in then is a full power cycle (dock/README.md, "ADB lockdown").
     */
    private static final boolean ADB_GUARD = false;
    private AdbGuard adbGuard;
    private long toolPendingUntil;
    private int frontTries;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout root;
    private LinearLayout bar;
    private FrameLayout popupHolder;
    private View handle;
    private View toolsButton;
    private View powerButton;
    private ImageView muteIcon;
    private boolean remote;
    private boolean onHome = true;
    /** The app in front now, so a screen change inside it (YouTube does this often) doesn't fold the dock. */
    private String frontPkg = HOME_PKG;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windows = getSystemService(WindowManager.class);
        root = new LinearLayout(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    leaveRemote();
                    return false;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) poke();
                return super.dispatchTouchEvent(e);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                return remoteKey(e) || super.dispatchKeyEvent(e);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        popupHolder = new FrameLayout(this);
        popupHolder.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.bottomMargin = dp(10);
        root.addView(popupHolder, pl);

        bar = buildBar();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.bottomMargin = dp(14);
        root.addView(bar, bl);

        handle = buildHandle();
        handle.setVisibility(View.GONE);
        root.addView(handle, new LinearLayout.LayoutParams(dp(84), dp(26)));

        // Not focusable until the remote comes down from Home; touches outside always go to the app below.
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        windows.addView(root, params);
        registerReceiver(focusBelow, new IntentFilter(ACTION_FOCUS_BELOW));
        registerReceiver(focusDock, new IntentFilter(ACTION_FOCUS_DOCK));
        // Every boot: ADB back to localhost only (AdbGuard).
        if (ADB_GUARD) {
            adbGuard = new AdbGuard(handler, this::adbChanged);
            adbGuard.lock(false);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (root == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // A closing window is still listed when this arrives; look once the windows settle.
            handler.removeCallbacks(toolCheck);
            handler.postDelayed(toolCheck, 300);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && onHome
                && HOME_PKG.contentEquals(String.valueOf(event.getPackageName()))
                && HOME_CLASS.contentEquals(String.valueOf(event.getClassName()))) {
            // Home shown again (the remote's Home key, say) while already there: hand the remote back to it.
            leaveRemote();
            closePopup();
        }
        // Which app is in front is read from the window list, not from the event: the events can't be trusted for
        // it (an app's first event is often its starting window or a fragment, dialog or bottom sheet rather than
        // an Activity, and a late event from Home can land after the app's own).
        frontTries = 0;
        handler.removeCallbacks(frontCheck);
        handler.postDelayed(frontCheck, 150);
    }

    /**
     * Opens the dock when Home comes to the front and folds it when any other app does. The front app is the
     * topmost application window; the dock's own window, Android's and System UI's dialogs and RuiPin's tool screens
     * (checkTools) are looked through. A screen change inside the same app leaves the dock alone.
     */
    private void checkFront() {
        String keyboard = currentKeyboard();
        String pkg = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo node = w.getRoot();
            if (node == null || node.getPackageName() == null) {
                // Its content isn't ready yet; look again shortly.
                if (frontTries++ < 5) handler.postDelayed(frontCheck, 300);
                return;
            }
            String p = node.getPackageName().toString();
            if (p.equals(getPackageName()) || p.equals("android") || p.equals("com.android.systemui")
                    || p.equals(TOOLS_PKG) || p.equals(keyboard)) continue;
            pkg = p;
            break;
        }
        if (pkg == null) return;
        if (pkg.equals(HOME_PKG)) {
            if (onHome) return;
            frontPkg = HOME_PKG;
            onHome = true;
            leaveRemote();
            closePopup();
            expand();
        } else if (onHome || !pkg.equals(frontPkg)) {
            frontPkg = pkg;
            onHome = false;
            leaveRemote();
            closePopup();
            collapse();
        }
    }

    /**
     * RuiPin's tools (crop, curtain, annotation, magnifier...) keep their own controls at the bottom centre, under
     * the arrow, so the dock steps aside while one of their screens is up. Most are see-through and report
     * themselves as a plain FrameLayout, so the window list is the only reliable sign.
     */
    private void checkTools() {
        boolean tool = false;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (isToolScreen(w)) {
                tool = true;
                break;
            }
        }
        // Screenshot hides the dock before the crop tool shows up; don't bring it back in between.
        if (!tool && SystemClock.uptimeMillis() < toolPendingUntil) {
            handler.postDelayed(toolCheck, 300);
            return;
        }
        if (tool) leaveRemote();
        root.setVisibility(tool ? View.GONE : View.VISIBLE);
    }

    /** A RuiPin tool's screen: an app screen, or a system window that has focus or covers most of the screen. */
    private boolean isToolScreen(AccessibilityWindowInfo w) {
        int type = w.getType();
        if (type != AccessibilityWindowInfo.TYPE_APPLICATION && type != AccessibilityWindowInfo.TYPE_SYSTEM) return false;
        AccessibilityNodeInfo node = w.getRoot();
        if (node == null || node.getPackageName() == null || !TOOLS_PKG.contentEquals(node.getPackageName())) return false;
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION || w.isFocused()) return true;
        // The floating timer and the side bars are small, unfocused system windows; they stay with the dock.
        Rect r = new Rect();
        w.getBoundsInScreen(r);
        return r.width() * 4 >= getResources().getDisplayMetrics().widthPixels * 3;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoHide);
        handler.removeCallbacks(toolCheck);
        handler.removeCallbacks(frontCheck);
        handler.removeCallbacks(remoteIdle);
        handler.removeCallbacks(noticeEnd);
        if (adbGuard != null) adbGuard.stop();
        if (root != null) {
            unregisterReceiver(focusBelow);
            unregisterReceiver(focusDock);
        }
        if (root != null && windows != null) windows.removeViewImmediate(root);
        root = null;
        super.onDestroy();
    }

    // ---- states ----

    private void expand() {
        bar.setVisibility(View.VISIBLE);
        handle.setVisibility(View.GONE);
        poke();
    }

    /** Back to the arrow, unless Home is in front, where the dock stays open. */
    private void collapse() {
        if (onHome) return;
        handler.removeCallbacks(autoHide);
        closePopup();
        bar.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
    }

    /** A touch on the dock: inside an app, wait HIDE_MS again before tucking it away (not while the remote is in it). */
    private void poke() {
        handler.removeCallbacks(autoHide);
        if (!onHome && !remote && bar.getVisibility() == View.VISIBLE) handler.postDelayed(autoHide, HIDE_MS);
    }

    // ---- the remote ----

    private void enterRemote() {
        if (remote) return;
        remote = true;
        handler.removeCallbacks(autoHide);
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windows.updateViewLayout(root, params);
        root.post(() -> {
            View first = firstButton();
            if (!remote || first == null) return;
            // Android parks focus on the first button even while the window can't take keys, so requestFocus may
            // change nothing; draw the ring by hand.
            first.requestFocus();
            highlight(root.findFocus(), true);
        });
        handler.postDelayed(remoteIdle, REMOTE_IDLE_MS);
    }

    /** Gives focus back to the app below (Home keeps the tile it had); inside an app the bar folds HIDE_MS later. */
    private void leaveRemote() {
        if (!remote) return;
        remote = false;
        handler.removeCallbacks(remoteIdle);
        View f = root.findFocus();
        if (f != null) {
            highlight(f, false);
            f.clearFocus();
        }
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windows.updateViewLayout(root, params);
        poke();
    }

    /** Keys while the dock has focus. Arrows move focus on their own; these are the exceptions. */
    private boolean remoteKey(KeyEvent e) {
        if (!remote) return false;
        handler.removeCallbacks(remoteIdle);
        handler.postDelayed(remoteIdle, REMOTE_IDLE_MS);
        View focused = root.findFocus();
        boolean inBar = focused != null && focused.getParent() == bar;
        boolean popupOpen = popupHolder.getVisibility() == View.VISIBLE;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) {
                    if (popupOpen) {
                        View opener = "power".equals(popupHolder.getTag()) ? powerButton : toolsButton;
                        closePopup();
                        if (opener != null) opener.requestFocus();
                    } else {
                        leaveRemote();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                // Up from the bar goes back to the tiles, unless a popup is open above it.
                if (inBar && !popupOpen) {
                    if (down) leaveRemote();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Nothing below the bar.
                return inBar;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Held select keeps stepping volume and brightness.
                if (down && e.getRepeatCount() > 0 && focused != null
                        && Boolean.TRUE.equals(focused.getTag(R.id.repeats))) {
                    focused.performClick();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private View firstButton() {
        for (int k = 0; k < bar.getChildCount(); k++) {
            if (bar.getChildAt(k).isFocusable()) return bar.getChildAt(k);
        }
        return null;
    }

    // ---- the bar ----

    private LinearLayout buildBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(glass(dp(26)));
        row.setPadding(dp(10), dp(6), dp(10), dp(6));

        row.addView(button(R.drawable.ic_back, R.string.back, GRAPHITE, () -> performGlobalAction(GLOBAL_ACTION_BACK)));
        row.addView(button(R.drawable.ic_home, R.string.home, BLUE, () -> performGlobalAction(GLOBAL_ACTION_HOME)));
        row.addView(button(R.drawable.ic_close, R.string.close_app, DARK_RED, this::closeApp));
        row.addView(divider());
        row.addView(button(R.drawable.ic_input, R.string.inputs, INDIGO,
                () -> open(new Intent("android.intent.action.RPSource"))));
        row.addView(button(R.drawable.ic_annotate, R.string.annotate, ORANGE,
                () -> open(new Intent("android.intent.action.RPAnnotation"))));
        row.addView(button(R.drawable.ic_screenshot, R.string.screenshot, TEAL, this::screenshot));
        row.addView(divider());
        // Volume and brightness sit in the bar itself: down, an icon, up. The speaker icon mutes.
        row.addView(volumeStepper(R.drawable.ic_minus, R.string.quieter, -VOLUME_STEP));
        row.addView(muteButton());
        row.addView(volumeStepper(R.drawable.ic_plus, R.string.louder, VOLUME_STEP));
        row.addView(divider());
        row.addView(stepper(R.drawable.ic_minus, R.string.dimmer, "action.com.rp.brightness.down"));
        FrameLayout sun = iconTile(R.drawable.ic_brightness, YELLOW);
        sun.setContentDescription(getString(R.string.brightness));
        row.addView(sun, new LinearLayout.LayoutParams(dp(56), dp(56)));
        row.addView(stepper(R.drawable.ic_plus, R.string.brighter, "action.com.rp.brightness.up"));
        row.addView(divider());
        toolsButton = button(R.drawable.ic_tools, R.string.tools, PURPLE, () -> togglePopup(buildTools()));
        row.addView(toolsButton);
        // Android's own settings (the panel's RuiPin settings are a different app, reached from Home's top bar).
        row.addView(button(R.drawable.ic_android, R.string.settings, ANDROID_GREEN, this::androidSettings));
        row.addView(button(R.drawable.ic_memory, R.string.clean_ram, SLATE, this::cleanRam));
        powerButton = button(R.drawable.ic_power, R.string.power, RED, () -> togglePopup(buildPower()));
        row.addView(powerButton);
        return row;
    }

    private View buildHandle() {
        FrameLayout tab = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GLASS);
        bg.setStroke(dp(1), EDGE);
        float r = dp(13);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        tab.setBackground(bg);
        tab.setAlpha(0.7f);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron_up);
        arrow.setContentDescription(getString(R.string.show_dock));
        tab.addView(arrow, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        tab.setOnClickListener(v -> expand());
        return tab;
    }

    // ---- popups above the bar ----

    private void togglePopup(View content) {
        if (popupHolder.getVisibility() == View.VISIBLE && popupHolder.getTag() == content.getTag()) {
            closePopup();
            return;
        }
        popupHolder.removeAllViews();
        popupHolder.addView(content);
        popupHolder.setTag(content.getTag());
        popupHolder.setVisibility(View.VISIBLE);
    }

    private void closePopup() {
        if (popupHolder == null) return;
        popupHolder.removeAllViews();
        popupHolder.setTag(null);
        popupHolder.setVisibility(View.GONE);
    }

    /** The speaker icon: shows whether media is muted, and mutes or unmutes on a tap. */
    private View muteButton() {
        AudioManager audio = getSystemService(AudioManager.class);
        FrameLayout tile = iconTile(R.drawable.ic_volume, PINK);
        muteIcon = (ImageView) tile.getChildAt(0);
        refreshMute();
        return pressable(tile, getString(R.string.mute), () -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            refreshMute();
        }, dp(56));
    }

    private void refreshMute() {
        AudioManager audio = getSystemService(AudioManager.class);
        boolean silent = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        muteIcon.setImageResource(silent ? R.drawable.ic_volume_off : R.drawable.ic_volume);
    }

    /** Media volume up or down by {@code delta} (of 100), unmuting first; hold to repeat. */
    private View volumeStepper(int icon, int label, int delta) {
        AudioManager audio = getSystemService(AudioManager.class);
        return repeater(icon, label, () -> {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, now + delta)),
                    AudioManager.FLAG_SHOW_UI);
            refreshMute();
        });
    }

    /**
     * Dimmer and Brighter step the backlight through RuiPin's brightness broadcasts; RuiPin shows its own slider at the
     * top of the screen. Holding a button keeps stepping.
     */
    private View stepper(int icon, int label, String action) {
        return repeater(icon, label, () -> sendBroadcast(new Intent(action)));
    }

    /** A plain −/+ glyph that runs {@code step} on a tap and keeps running it while held. */
    private View repeater(int icon, int label, Runnable step) {
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                step.run();
                poke();
                handler.postDelayed(this, 150);
            }
        };
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setTag(R.id.repeats, true);
        return pressable(b, getString(label), step, dp(44), repeat);
    }

    private View buildTools() {
        GridLayout grid = new GridLayout(this);
        grid.setTag("tools");
        grid.setColumnCount(3);
        grid.setBackground(glass(dp(20)));
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        grid.addView(tile(R.drawable.ic_record, R.string.screen_record, RED,
                () -> open(new Intent("android.intent.action.RPScreenRecord"))));
        // RuiPin's TimerService is not exported; its timer screen is.
        grid.addView(tile(R.drawable.ic_timer, R.string.timer, ORANGE,
                () -> open(new Intent("android.intent.action.timer"))));
        grid.addView(tile(R.drawable.ic_spotlight, R.string.spotlight, YELLOW,
                () -> open(new Intent("android.intent.action.RPSpotlight"))));
        grid.addView(tile(R.drawable.ic_magnifier, R.string.magnifier, BLUE,
                () -> open(new Intent("android.intent.action.RPMagnifier"))));
        grid.addView(tile(R.drawable.ic_curtain, R.string.curtain, CRIMSON,
                () -> open(new Intent("android.intent.action.RPCurtain"))));
        grid.addView(tile(R.drawable.ic_bluetooth, R.string.bluetooth, BLUE,
                () -> open(new Intent("com.rp.settings.BLUETOOTH_SETTINGS"))));
        // Gupta's TV AirPlay receiver and the iPhone remote bridge: their settings screens (both keep running anyway).
        grid.addView(tile(R.drawable.ic_airplay, R.string.airplay, INDIGO, () -> openApp(AIRPLAY_PKG)));
        grid.addView(tile(R.drawable.ic_remote, R.string.iphone_remote, GRAPHITE, () -> openApp(REMOTE_PKG)));
        // ADB is localhost-only; this lets the Mac in for a while (AdbGuard).
        if (ADB_GUARD) grid.addView(tile(R.drawable.ic_laptop, R.string.mac_adb, SLATE, this::macAdb));
        return grid;
    }

    /** Power off goes to RuiPin's own power-off screen (15 s countdown, cancel, or screen-off only). */
    private View buildPower() {
        GridLayout grid = new GridLayout(this);
        grid.setTag("power");
        grid.setColumnCount(2);
        grid.setBackground(glass(dp(20)));
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        grid.addView(tile(R.drawable.ic_power, R.string.power_off, RED,
                () -> open(new Intent("android.intent.action.ShutDown"))));
        grid.addView(tile(R.drawable.ic_restart, R.string.restart, ORANGE, this::restart));
        return grid;
    }

    // ---- actions ----

    /** Opens ADB to the network for AdbGuard.OPEN_MS, or locks it again right away if it is open. */
    private void macAdb() {
        closePopup();
        if (adbGuard.isOpen()) adbGuard.lock(true);
        else adbGuard.open();
    }

    private void adbChanged(boolean open, boolean ok) {
        if (root == null) return;
        if (!ok) notice(getString(R.string.mac_adb_failed));
        else if (open) notice(getString(R.string.mac_adb_open,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(adbGuard.openUntilWallClock()))));
        else notice(getString(R.string.mac_adb_locked));
    }

    /**
     * Android's reboot needs a system permission, and RuiPin only restarts from its own (now hidden) side menu. The
     * panel's ADB on 127.0.0.1:5555 runs unauthenticated, so the dock asks it for {@code shell:reboot}.
     */
    private void restart() {
        closePopup();
        leaveRemote();
        new Thread(() -> {
            if (!LocalAdb.shell("reboot")) {
                handler.post(() -> notice(getString(R.string.restart_failed)));
            }
        }, "dock-restart").start();
    }

    /**
     * Kills cached and empty background processes ({@code am kill-all}, through the panel's local ADB like Restart),
     * then says how much memory that freed. Running apps and services (Home, the iPhone bridge, AirPlay, the dock,
     * the keyboard) are not cached and stay up; nothing is force-stopped.
     */
    private void cleanRam() {
        closePopup();
        leaveRemote();
        long before = availMem();
        new Thread(() -> {
            if (!LocalAdb.shell("am kill-all")) {
                handler.post(() -> notice(getString(R.string.ram_clean_failed)));
                return;
            }
            // shell() returns once adbd takes the command; give the kills a moment before measuring.
            handler.postDelayed(() -> {
                long after = availMem();
                long freedMb = Math.max(0, after - before) / (1024 * 1024);
                double freeGb = after / (1024.0 * 1024 * 1024);
                notice(getString(R.string.ram_cleaned, freedMb, freeGb));
            }, 1500);
        }, "dock-clean-ram").start();
    }

    /**
     * A short message in a glass pill above the bar, for NOTICE_MS. A toast would sit behind the dock: Android 11
     * ignores a text toast's gravity, so it always lands at the bottom centre.
     */
    private void notice(CharSequence text) {
        TextView t = label(text.toString());
        t.setBackground(glass(dp(18)));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setTag("notice");
        popupHolder.removeAllViews();
        popupHolder.addView(t);
        popupHolder.setTag("notice");
        popupHolder.setVisibility(View.VISIBLE);
        handler.removeCallbacks(noticeEnd);
        handler.postDelayed(noticeEnd, NOTICE_MS);
    }

    private long availMem() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        return info.availMem;
    }

    private boolean open(Intent intent) {
        closePopup();
        leaveRemote();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "can't open " + intent, e);
            notice(getString(R.string.not_available));
            return false;
        }
    }

    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) open(i);
        else notice(getString(R.string.not_available));
    }

    /**
     * RuiPin's crop tool freezes the screen as it opens, so the dock leaves first. It comes back when the tool
     * closes (checkTools).
     */
    private void screenshot() {
        leaveRemote();
        root.setVisibility(View.GONE);
        toolPendingUntil = SystemClock.uptimeMillis() + 2000;
        handler.postDelayed(() -> {
            if (!open(new Intent("android.intent.action.RPCut"))) {
                toolPendingUntil = 0;
                root.setVisibility(View.VISIBLE);
            }
        }, 250);
    }

    /**
     * Stops the app in front completely (as Settings' Force stop does) and goes Home. Android only lets the system do
     * that, so it goes through the panel's local ADB like Restart. Apps the TV runs on are never closed.
     */
    private void closeApp() {
        String pkg = frontPkg;
        if (onHome || pkg == null) {
            notice(getString(R.string.nothing_to_close));
            return;
        }
        if (keepsTvRunning(pkg) || !pkg.matches("[A-Za-z0-9_.]+")) {
            notice(getString(R.string.cant_close));
            return;
        }
        closePopup();
        leaveRemote();
        performGlobalAction(GLOBAL_ACTION_HOME);
        new Thread(() -> {
            if (!LocalAdb.shell("am force-stop " + pkg)) {
                handler.post(() -> notice(getString(R.string.close_failed)));
            }
        }, "dock-close").start();
    }

    /** Home, the iPhone remote, the dock, AirPlay, the keyboard, and RuiPin's and Amlogic's system apps. */
    private boolean keepsTvRunning(String pkg) {
        return pkg.equals(HOME_PKG) || pkg.equals(REMOTE_PKG) || pkg.equals(AIRPLAY_PKG)
                || pkg.equals(getPackageName()) || pkg.equals(currentKeyboard())
                || pkg.equals("android") || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.rp.") || pkg.startsWith("com.fqcx.") || pkg.startsWith("com.droidlogic")
                || pkg.startsWith("com.amlogic.");
    }

    private void androidSettings() {
        Intent i = new Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings");
        if (getPackageManager().resolveActivity(i, 0) == null) i.setPackage(null);
        open(i);
    }

    private String currentKeyboard() {
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime == null ? "" : ime.split("/")[0];
    }

    // ---- small views ----

    private View button(int icon, int label, int color, Runnable action) {
        return pressable(iconTile(icon, color), getString(label), action, dp(56));
    }

    /** A rounded, softly graded colour square with a white glyph, centred in a 56dp cell. */
    private FrameLayout iconTile(int icon, int color) {
        FrameLayout cell = new FrameLayout(this);
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        glyph.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighter(color), color});
        g.setCornerRadius(dp(11));
        g.setStroke(dp(1), 0x26FFFFFF);
        glyph.setBackground(g);
        glyph.setElevation(dp(2));
        cell.addView(glyph, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        return cell;
    }

    private View tile(int icon, int label, int color, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.addView(iconTile(icon, color), new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView text = label(getString(label));
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        t.addView(text);
        View v = pressable(t, getString(label), action, 0);
        v.setLayoutParams(new GridLayout.LayoutParams(new ViewGroup.LayoutParams(dp(108), dp(96))));
        return v;
    }

    private View pressable(View v, CharSequence description, Runnable action, int size) {
        return pressable(v, description, action, size, null);
    }

    /**
     * Touch and remote feedback for a dock control. {@code hold}, when given, starts 400 ms into a touch and is
     * cancelled on release.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View pressable(View v, CharSequence description, Runnable action, int size, Runnable hold) {
        v.setContentDescription(description);
        v.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0);
        v.setBackground(bg);
        v.setOnTouchListener((view, e) -> {
            int m = e.getActionMasked();
            if (m == MotionEvent.ACTION_DOWN) {
                bg.setColor(PRESSED);
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                if (hold != null) handler.postDelayed(hold, 400);
            } else if (m == MotionEvent.ACTION_UP || m == MotionEvent.ACTION_CANCEL) {
                if (hold != null) handler.removeCallbacks(hold);
                bg.setColor(0);
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
        v.setOnFocusChangeListener((view, focused) -> highlight(view, focused && remote));
        v.setOnClickListener(view -> action.run());
        if (size > 0) v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    /** The remote's highlight: a white ring and a slight lift. */
    private void highlight(View v, boolean on) {
        if (v == null || !(v.getBackground() instanceof GradientDrawable)) return;
        GradientDrawable bg = (GradientDrawable) v.getBackground();
        bg.setColor(on ? PRESSED : 0);
        bg.setStroke(on ? dp(2) : 0, 0xFFFFFFFF);
        v.animate().scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f).setDuration(120).start();
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(32));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        d.setLayoutParams(lp);
        return d;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        return t;
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(GLASS);
        g.setStroke(dp(1), EDGE);
        g.setCornerRadius(radius);
        return g;
    }

    /** The top of an icon tile's gradient: the colour mixed a quarter of the way to white. */
    private static int lighter(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        r += (255 - r) / 4;
        g += (255 - g) / 4;
        b += (255 - b) / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }
}
