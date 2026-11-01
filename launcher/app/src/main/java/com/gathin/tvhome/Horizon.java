package com.gathin.tvhome;

import java.util.Random;

/**
 * The hills of the optional horizon backdrop (config.xml, horizon): three ridge lines in front of the
 * time-of-day sky, their colours under that sky, how far into the night it is, where the stars and the
 * lights in the nearest hill sit, and how far each hill moves when focus moves.
 *
 * Pure maths like {@link Sky}: no Android types, so it is tested on the JVM. HorizonView draws it.
 */
final class Horizon {

    /** Ridge layers, back to front. */
    static final int LAYERS = 3;
    /** Where each ridge rests, as a fraction of the screen height from the top. */
    private static final float[] BASE = {0.65f, 0.75f, 0.86f};
    /** How far each ridge rises above or dips below its base, as a fraction of the screen height. */
    private static final float[] AMPLITUDE = {0.10f, 0.078f, 0.056f};
    /** Phase of each ridge, so the three lines never run parallel. */
    private static final float[] SEED = {1.3f, 4.1f, 2.2f};
    /** How far each hill moves sideways, in dp, as focus goes from the centre to an edge: nearer hills move more. */
    private static final float[] TRAVEL_DP = {6f, 14f, 26f};
    /** Up and down, the hills move this share of their sideways travel. */
    static final float VERTICAL_SHARE = 0.4f;

    private Horizon() {
    }

    /**
     * The ridge's height at {@code x} (0 = left edge, 1 = right edge, and beyond them for the margin the
     * parallax moves into), as a fraction of the screen height from the top. Three sines of falling size:
     * a long swell, hills on it, and a little roughness on those.
     */
    static float ridge(int layer, float x) {
        float s = SEED[layer];
        double wave = 0.55 * Math.sin(x * 3.84 + s)
                + 0.30 * Math.sin(x * 10.56 + s * 2.3)
                + 0.15 * Math.sin(x * 25.92 + s * 3.7);
        return (float) (BASE[layer] - AMPLITUDE[layer] * wave);
    }

    /** The highest the ridge can reach, as a fraction of the screen height: where its layer starts. */
    static float top(int layer) {
        return BASE[layer] - AMPLITUDE[layer];
    }

    /** Colour of a hill under this sky: the far one takes the light of the horizon, the nearest is almost black. */
    static int color(int layer, Sky.Palette sky) {
        switch (layer) {
            case 0:
                return Sky.lerpColor(Sky.lerpColor(sky.bottom, sky.mid, 0.4f), 0xFF000000, 0.5f);
            case 1:
                return Sky.lerpColor(sky.mid, 0xFF000000, 0.72f);
            default:
                return Sky.lerpColor(sky.top, 0xFF000000, 0.82f);
        }
    }

    /**
     * How far into the night it is at {@code minute}, 0 (day) to 1 (full night), on a day whose sun rises at
     * {@code sunrise} and sets at {@code sunset} (minutes of the day; out of order or out of range, the
     * reference day of {@link Sky}). Stars and lights come out from ten minutes after sunset, fully lit an
     * hour and a half later, and fade over the hour before sunrise.
     */
    static float night(int minute, int sunrise, int sunset) {
        if (sunrise <= 0 || sunset >= 1440 || sunrise >= sunset) {
            sunrise = Sky.REF_SUNRISE;
            sunset = Sky.REF_SUNSET;
        }
        int m = Math.max(0, Math.min(1440, minute));
        if (m >= sunset + 100 || m <= sunrise - 70) return 1f;
        if (m > sunset + 10) return (m - sunset - 10) / 90f;
        if (m < sunrise - 10) return (sunrise - 10 - m) / 60f;
        return 0f;
    }

    /** Sideways offset of a hill in dp for focus at {@code focusX} (0 = left edge, 1 = right edge), against the focus. */
    static float offsetX(int layer, float focusX) {
        return -(clamp(focusX) * 2f - 1f) * TRAVEL_DP[layer];
    }

    /** Vertical offset of a hill in dp for focus at {@code focusY} (0 = top, 1 = bottom), against the focus. */
    static float offsetY(int layer, float focusY) {
        return -(clamp(focusY) * 2f - 1f) * TRAVEL_DP[layer] * VERTICAL_SHARE;
    }

    /** The furthest a hill moves sideways, in dp: the margin its picture needs on each side. */
    static float travelDp(int layer) {
        return TRAVEL_DP[layer];
    }

    /**
     * {@code count} stars as {x, y, size} triples: x and y as fractions of the screen, y in the upper 62%
     * (the far hill covers the rest), size 0 (most), 1 or 2 (few). The same seed gives the same sky.
     */
    static float[] stars(int count, long seed) {
        Random random = new Random(seed);
        float[] out = new float[count * 3];
        for (int i = 0; i < count; i++) {
            out[i * 3] = random.nextFloat();
            out[i * 3 + 1] = random.nextFloat() * 0.62f;
            float r = random.nextFloat();
            out[i * 3 + 2] = r < 0.6f ? 0 : r < 0.9f ? 1 : 2;
        }
        return out;
    }

    /**
     * {@code count} window lights on the nearest hill as {x, y} pairs, fractions of the screen: always below
     * its ridge line, so they sit on the hill and never in the sky.
     */
    static float[] lights(int count, long seed) {
        Random random = new Random(seed);
        float[] out = new float[count * 2];
        for (int i = 0; i < count; i++) {
            float x = -0.02f + random.nextFloat() * 1.04f;
            out[i * 2] = x;
            out[i * 2 + 1] = ridge(LAYERS - 1, x) + 0.012f + random.nextFloat() * 0.06f;
        }
        return out;
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }
}
