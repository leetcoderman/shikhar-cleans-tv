package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the horizon's hills: where they sit, their colours, the night level and the parallax. */
public class HorizonTest {

    @Test
    public void eachRidgeStaysBetweenItsTopAndTheBottomOfTheScreen() {
        for (int layer = 0; layer < Horizon.LAYERS; layer++) {
            for (float x = -0.1f; x <= 1.1f; x += 0.005f) {
                float y = Horizon.ridge(layer, x);
                assertTrue(y >= Horizon.top(layer) - 1e-5f);
                assertTrue(y < 1f);
            }
        }
    }

    @Test
    public void nearerHillsSitLower() {
        assertTrue(Horizon.top(0) < Horizon.top(1));
        assertTrue(Horizon.top(1) < Horizon.top(2));
    }

    @Test
    public void nearerHillsAreDarker() {
        for (int minute : new int[]{120, 450, 780, 1140}) {
            Sky.Palette sky = Sky.at(minute);
            assertTrue(luminance(Horizon.color(0, sky)) >= luminance(Horizon.color(1, sky)));
            assertTrue(luminance(Horizon.color(1, sky)) >= luminance(Horizon.color(2, sky)));
            assertEquals(0xFF, Horizon.color(2, sky) >>> 24);
        }
    }

    @Test
    public void nightComesAfterSunsetAndGoesBeforeSunrise() {
        int rise = 380;
        int set = 1085;
        assertEquals(0f, Horizon.night(780, rise, set), 0f);
        assertEquals(0f, Horizon.night(set + 10, rise, set), 0f);
        assertEquals(0.5f, Horizon.night(set + 55, rise, set), 1e-4f);
        assertEquals(1f, Horizon.night(set + 100, rise, set), 0f);
        assertEquals(1f, Horizon.night(0, rise, set), 0f);
        assertEquals(1f, Horizon.night(rise - 70, rise, set), 0f);
        assertEquals(0.5f, Horizon.night(rise - 40, rise, set), 1e-4f);
        assertEquals(0f, Horizon.night(rise, rise, set), 0f);
    }

    @Test
    public void nightWithoutAPlaceFollowsTheReferenceDay() {
        assertEquals(Horizon.night(1300, Sky.REF_SUNRISE, Sky.REF_SUNSET), Horizon.night(1300, 0, 0), 0f);
        assertEquals(Horizon.night(400, Sky.REF_SUNRISE, Sky.REF_SUNSET), Horizon.night(400, 900, 600), 0f);
    }

    @Test
    public void hillsMoveAgainstTheFocusAndTheNearestMovesFurthest() {
        assertEquals(0f, Horizon.offsetX(2, 0.5f), 1e-6f);
        assertTrue(Horizon.offsetX(2, 1f) < 0f);
        assertTrue(Horizon.offsetX(2, 0f) > 0f);
        assertTrue(Math.abs(Horizon.offsetX(2, 1f)) > Math.abs(Horizon.offsetX(1, 1f)));
        assertTrue(Math.abs(Horizon.offsetX(1, 1f)) > Math.abs(Horizon.offsetX(0, 1f)));
        assertEquals(Horizon.travelDp(2), Math.abs(Horizon.offsetX(2, 5f)), 1e-6f);
        assertEquals(Horizon.travelDp(1) * Horizon.VERTICAL_SHARE, Math.abs(Horizon.offsetY(1, 1f)), 1e-6f);
    }

    @Test
    public void theSameSeedGivesTheSameSky() {
        assertArrayEquals(Horizon.stars(150, 7L), Horizon.stars(150, 7L), 0f);
        float[] stars = Horizon.stars(150, 7L);
        for (int i = 0; i < 150; i++) {
            assertTrue(stars[i * 3] >= 0f && stars[i * 3] < 1f);
            assertTrue(stars[i * 3 + 1] >= 0f && stars[i * 3 + 1] < 0.62f);
            assertTrue(stars[i * 3 + 2] == 0f || stars[i * 3 + 2] == 1f || stars[i * 3 + 2] == 2f);
        }
    }

    @Test
    public void lightsSitOnTheNearestHill() {
        float[] lights = Horizon.lights(28, 7L);
        for (int i = 0; i < lights.length; i += 2) {
            assertTrue(lights[i + 1] > Horizon.ridge(Horizon.LAYERS - 1, lights[i]));
            assertTrue(lights[i + 1] < 1f);
        }
    }

    private static int luminance(int color) {
        return ((color >> 16) & 0xFF) * 3 + ((color >> 8) & 0xFF) * 6 + (color & 0xFF);
    }
}
