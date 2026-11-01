package com.gupta.airplayreceiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Status and the two settings. Opening it once after install also lets the boot receiver run. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private Button overlayButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ReceiverService.start(this);
        Prefs prefs = new Prefs(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(96, 96, 96, 96);
        col.setBackgroundColor(Color.rgb(18, 18, 24));

        TextView title = new TextView(this);
        title.setText(Prefs.NAME + " · AirPlay receiver");
        title.setTextSize(40);
        title.setTextColor(Color.WHITE);
        col.addView(title);

        TextView how = new TextView(this);
        how.setText("On the Mac: Control Center → Screen Mirroring → " + Prefs.NAME);
        how.setTextSize(24);
        how.setTextColor(Color.LTGRAY);
        how.setPadding(0, 24, 0, 48);
        col.addView(how);

        statusView = new TextView(this);
        statusView.setTextSize(22);
        statusView.setTextColor(Color.WHITE);
        statusView.setPadding(0, 0, 0, 48);
        col.addView(statusView);

        Switch pin = new Switch(this);
        pin.setText("Ask for a code on the Mac the first time it connects  ");
        pin.setTextSize(22);
        pin.setTextColor(Color.WHITE);
        pin.setChecked(prefs.requirePin());
        pin.setOnCheckedChangeListener((v, on) -> { prefs.setRequirePin(on); restart(); });
        col.addView(pin);

        Switch uhd = new Switch(this);
        uhd.setText("4K picture (H.265; needs an Apple silicon Mac)  ");
        uhd.setTextSize(22);
        uhd.setTextColor(Color.WHITE);
        uhd.setChecked(prefs.uhd());
        uhd.setOnCheckedChangeListener((v, on) -> { prefs.setUhd(on); restart(); });
        col.addView(uhd);

        overlayButton = new Button(this);
        overlayButton.setText("Allow opening the mirroring screen automatically");
        overlayButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        col.addView(overlayButton);

        setContentView(col);
    }

    private void restart() {
        startService(new Intent(this, ReceiverService.class).setAction(ReceiverService.ACTION_RESTART));
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String s = "Status: " + ReceiverService.status;
            if (!ReceiverService.address.isEmpty()) s += "\nAddress: " + ReceiverService.address + "  port " + ReceiverService.port;
            statusView.setText(s);
            overlayButton.setVisibility(Settings.canDrawOverlays(MainActivity.this) ? android.view.View.GONE : android.view.View.VISIBLE);
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); refresh.run(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
