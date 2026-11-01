package com.gupta.airplayreceiver;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Settings, and the receiver's stable identity. */
final class Prefs {
    /** Shown on the Mac. Distinct from QuickShare ("Smart Interactive Tablet") and the remote bridge. */
    static final String NAME = "Gupta's TV";
    /** mDNS host label (Guptas-TV-Mirror.local); the remote bridge must use a different one. */
    static final String HOST_LABEL = "Guptas-TV-Mirror";

    static final String KEY_PIN = "require_pin";
    static final String KEY_4K = "uhd_hevc";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("receiver", Context.MODE_PRIVATE);
    }

    boolean requirePin() { return sp.getBoolean(KEY_PIN, false); }
    void setRequirePin(boolean v) { sp.edit().putBoolean(KEY_PIN, v).apply(); }

    boolean uhd() { return sp.getBoolean(KEY_4K, false); }
    void setUhd(boolean v) { sp.edit().putBoolean(KEY_4K, v).apply(); }

    /**
     * A random, locally administered MAC used as AirPlay device ID. Generated once, so the Mac
     * keeps recognising the receiver, and different from the panel's real adapters.
     */
    byte[] hwAddr() {
        String s = sp.getString("hw_addr", null);
        if (s == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02);
            s = format(b);
            sp.edit().putString("hw_addr", s).apply();
        }
        String[] parts = s.split(":");
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }

    static String format(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", b[i] & 0xff));
        }
        return sb.toString();
    }
}
