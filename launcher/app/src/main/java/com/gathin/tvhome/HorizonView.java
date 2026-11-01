package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import java.util.Calendar;

/**
 * The optional horizon backdrop (config.xml, horizon): three hills in front of the time-of-day sky, stars and a
 * crescent moon at night, lights in the nearest hill, and a small parallax as focus moves across the tiles. Off
 * by default; it draws over SkyView, so a theme that turns it on also sets sky_intensity.
 *
 * Each hill is an ALPHA_8 mask drawn once at the screen's size and tinted with a paint colour, about 2 MB in all
 * at 1080p. After the first layout nothing is painted into a bitmap again: the minute tick only changes three
 * colours and how dark it is, and a parallax step only moves the masks.
 */
public class HorizonView extends View {

    /** Full-screen and faded with setAlpha: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static final int STARS = 150;
    private static final int LIGHTS = 28;
    private static final long SKY_SEED = 7L;
    /** Star sizes in dp, by Horizon.stars' size bucket, and their brightness at full night. */
    private static final float[] STAR_DP = {1.2f, 1.8f, 2.6f};
    private static final int[] STAR_ALPHA = {140, 190, 230};
    private static final int LIGHT_COLOR = 0xFFFFC37A;
    private static final int MOON_COLOR = 0xFFEDEBFF;
    /** The crescent sits above the Settings pill, right of the header. */
    private static final float MOON_X = 0.905f;
    private static final float MOON_Y = 0.055f;
    private static final float MOON_DP = 13f;
    private static final long GLIDE_MS = 700L;
    /**
     * home_name: the house stands on the near hill this far across the screen; its name is lettered at the top
     * centre, between the clock and the buttons, its baseline NAME_BASELINE_DP down.
     */
    private static final float HOUSE_X = 0.80f;
    private static final float NAME_BASELINE_DP = 92f;
    private static final float NAME_DP = 44f;
    /** Each window lights once the evening is this far in, left to right; after LATE_MINUTE only the last stays on. */
    private static final float[] WINDOW_AT = {0.12f, 0.3f, 0.5f};
    private static final int LATE_MINUTE = 23 * 60 + 30;
    private static final int EARLY_MINUTE = 5 * 60 + 30;
    private static final int WINDOW_COLOR = 0xFFFFC870;
    private static final int NAME_COLOR = 0xFFFFE4B8;

    private final float density;
    private final Bitmap[] masks = new Bitmap[Horizon.LAYERS];
    private final float[] maskLeft = new float[Horizon.LAYERS];
    private final float[] maskTop = new float[Horizon.LAYERS];
    private final Paint[] hillPaint = new Paint[Horizon.LAYERS];
    private final Paint mistPaint = new Paint();
    private final Paint[] starPaint = new Paint[STAR_DP.length];
    private final float[][] starPoints = new float[STAR_DP.length][];
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] lightPoints;
    private final Paint moonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moon = new Path();
    private float moonX;
    private float moonY;
    private float mistTop;
    private float mistBottom;
    private int mistColor;

    /** home_name, or empty for no house and no name. */
    private String homeName = "";
    private final Path house = new Path();
    private final Paint housePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] windows = new RectF[WINDOW_AT.length];
    private final Paint[] windowPaint = new Paint[WINDOW_AT.length];
    private final Paint[] windowGlow = new Paint[WINDOW_AT.length];
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float nameX;
    private float nameY;

    private boolean on;
    private boolean glide = true;
    /** 0 (day) to 1 (full night): how bright the stars, the moon and the lights are. */
    private float night;
    private int lastMinute = -1;
    private float focusX = 0.5f;
    private float focusY = 0.5f;
    @Nullable
    private ValueAnimator glider;

    public HorizonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Horizon.LAYERS; i++) {
            // Filtered: the parallax moves a mask by fractions of a pixel.
            hillPaint[i] = new Paint(Paint.FILTER_BITMAP_FLAG);
        }
        for (int i = 0; i < STAR_DP.length; i++) {
            starPaint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            starPaint[i].setStrokeCap(Paint.Cap.ROUND);
            starPaint[i].setStrokeWidth(STAR_DP[i] * density);
        }
        lightPaint.setStrokeCap(Paint.Cap.ROUND);
        lightPaint.setStrokeWidth(2.4f * density);
        moonPaint.setColor(MOON_COLOR);
        for (int i = 0; i < WINDOW_AT.length; i++) {
            windows[i] = new RectF();
            windowPaint[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            windowGlow[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        namePaint.setTypeface(ResourcesCompat.getFont(context, R.font.outfit_light));
        namePaint.setTextSize(NAME_DP * density);
        namePaint.setLetterSpacing(0.04f);
        namePaint.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
    }

    /**
     * Turns the backdrop on or off (config.xml, horizon); {@code animate} false moves the hills without gliding. A
     * non-empty {@code homeName} adds the house on the near hill and the name at the top centre.
     */
    public void setUp(boolean on, boolean animate, String homeName) {
        this.on = on;
        this.homeName = homeName == null ? "" : homeName;
        glide = animate;
        setVisibility(on ? VISIBLE : GONE);
        if (on && getWidth() > 0) build(getWidth(), getHeight());
    }

    /** Picks up a new time of day; cheap and safe to call every minute. */
    public void update() {
        if (!on) return;
        render(false);
    }

    /**
     * Moves the hills for focus at ({@code x}, {@code y}), fractions of the screen from its top left: they slide
     * the other way, the nearest furthest.
     */
    public void follow(float x, float y) {
        if (!on) return;
        final float toX = Math.max(0f, Math.min(1f, x));
        final float toY = Math.max(0f, Math.min(1f, y));
        if (Math.abs(toX - focusX) < 0.002f && Math.abs(toY - focusY) < 0.002f) return;
        stop();
        if (!glide || getWidth() == 0) {
            focusX = toX;
            focusY = toY;
            invalidate();
            return;
        }
        final float fromX = focusX;
        final float fromY = focusY;
        glider = ValueAnimator.ofFloat(0f, 1f);
        glider.setDuration(GLIDE_MS);
        glider.setInterpolator(new DecelerateInterpolator(1.6f));
        glider.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            focusX = fromX + (toX - fromX) * t;
            focusY = fromY + (toY - fromY) * t;
            invalidate();
        });
        glider.start();
    }

    /** Stops a glide where it is, for when Home goes to the background. */
    public void stop() {
        if (glider != null) {
            glider.cancel();
            glider = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (on) render(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (on && w > 0 && h > 0) build(w, h);
    }

    /** The hill masks, the stars, the lights and the moon for a screen of w by h. */
    private void build(int w, int h) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path ridge = new Path();
        float step = 4f * density;
        for (int i = 0; i < Horizon.LAYERS; i++) {
            int margin = (int) Math.ceil(Horizon.travelDp(i) * density);
            int below = (int) Math.ceil(margin * Horizon.VERTICAL_SHARE);
            float top = Horizon.top(i) * h - 2f;
            int mw = w + 2 * margin;
            int mh = (int) Math.ceil(h + below - top);
            masks[i] = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8);
            ridge.rewind();
            ridge.moveTo(0f, mh);
            for (float px = 0f; px < mw + step; px += step) {
                float x = Math.min(px, mw);
                ridge.lineTo(x, Horizon.ridge(i, (x - margin) / w) * h - top);
            }
            ridge.lineTo(mw, mh);
            ridge.close();
            new Canvas(masks[i]).drawPath(ridge, fill);
            maskLeft[i] = -margin;
            maskTop[i] = top;
        }

        float[] stars = Horizon.stars(STARS, SKY_SEED);
        int[] counts = new int[STAR_DP.length];
        for (int i = 0; i < STARS; i++) counts[(int) stars[i * 3 + 2]]++;
        for (int b = 0; b < STAR_DP.length; b++) starPoints[b] = new float[counts[b] * 2];
        int[] filled = new int[STAR_DP.length];
        for (int i = 0; i < STARS; i++) {
            int b = (int) stars[i * 3 + 2];
            starPoints[b][filled[b]++] = stars[i * 3] * w;
            starPoints[b][filled[b]++] = stars[i * 3 + 1] * h;
        }

        float[] lights = Horizon.lights(LIGHTS, SKY_SEED);
        lightPoints = new float[lights.length];
        for (int i = 0; i < lights.length; i += 2) {
            lightPoints[i] = lights[i] * w;
            lightPoints[i + 1] = lights[i + 1] * h;
        }

        moonX = MOON_X * w;
        moonY = MOON_Y * h;
        float r = MOON_DP * density;
        Path bite = new Path();
        bite.addCircle(moonX + 0.55f * r, moonY - 0.4f * r, 0.92f * r, Path.Direction.CW);
        moon.rewind();
        moon.addCircle(moonX, moonY, r, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);

        // Mist where the middle hill meets the near one.
        mistTop = (Horizon.top(1) + 0.02f) * h;
        mistBottom = (Horizon.top(2) + 0.08f) * h;
        mistColor = 0;
        if (!homeName.isEmpty()) buildHouse(w, h);
        render(true);
    }

    /** A gabled house with a chimney on the near hill, three windows, and where the name goes. */
    private void buildHouse(int w, int h) {
        float u = density;
        float cx = HOUSE_X * w;
        float ground = Horizon.ridge(Horizon.LAYERS - 1, HOUSE_X) * h + 6f * u;
        float bw = 84f * u;
        float bh = 40f * u;
        float roof = 30f * u;
        float left = cx - bw / 2f;
        float top = ground - bh;
        house.rewind();
        house.addRect(left, top, left + bw, ground + 8f * u, Path.Direction.CW);
        Path gable = new Path();
        gable.moveTo(left - 8f * u, top + 1f);
        gable.lineTo(cx, top - roof);
        gable.lineTo(left + bw + 8f * u, top + 1f);
        gable.close();
        house.op(gable, Path.Op.UNION);
        Path chimney = new Path();
        chimney.addRect(left + bw * 0.68f, top - roof * 0.95f, left + bw * 0.68f + 9f * u, top - roof * 0.3f,
                Path.Direction.CW);
        house.op(chimney, Path.Op.UNION);
        // A round tree beside it, the same dark as the house.
        house.addCircle(left - 26f * u, ground - 30f * u, 17f * u, Path.Direction.CW);
        house.addCircle(left - 36f * u, ground - 20f * u, 13f * u, Path.Direction.CW);
        house.addRect(left - 29f * u, ground - 18f * u, left - 25f * u, ground + 8f * u, Path.Direction.CW);

        float ww = 13f * u;
        float wh = 15f * u;
        float wy = top + 12f * u;
        windows[0].set(left + 12f * u, wy, left + 12f * u + ww, wy + wh);
        windows[1].set(left + bw - 12f * u - ww, wy, left + bw - 12f * u, wy + wh);
        windows[2].set(cx - 8f * u, wy + 4f * u, cx + 8f * u, ground);
        for (int i = 0; i < windows.length; i++) {
            windowGlow[i].setShader(new RadialGradient(windows[i].centerX(), windows[i].centerY(), 34f * u,
                    new int[]{0x66FFB050, 0x00FFB050}, null, Shader.TileMode.CLAMP));
        }
        nameX = w / 2f;
        nameY = NAME_BASELINE_DP * density;
    }

    /** How lit window {@code i} is, 0 to 1: one by one as the evening comes, only the door late at night. */
    private float windowLight(int i, int minute) {
        boolean late = minute >= LATE_MINUTE || minute < EARLY_MINUTE;
        if (late && i != windows.length - 1) return 0f;
        return Math.max(0f, Math.min(1f, (night - WINDOW_AT[i]) / 0.12f));
    }

    private void render(boolean force) {
        if (masks[0] == null) return;
        Calendar now = Calendar.getInstance();
        int minute = Sky.minuteOfDay(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        if (!force && minute == lastMinute) return;
        lastMinute = minute;

        int[] sun = SkyView.sunTimes(getContext());
        Sky.Palette sky = sun == null ? Sky.at(minute) : Sky.at(minute, sun[0], sun[1]);
        night = sun == null ? Horizon.night(minute, Sky.REF_SUNRISE, Sky.REF_SUNSET)
                : Horizon.night(minute, sun[0], sun[1]);
        for (int i = 0; i < Horizon.LAYERS; i++) hillPaint[i].setColor(Horizon.color(i, sky));
        if (sky.bottom != mistColor) {
            mistColor = sky.bottom;
            int rgb = sky.bottom & 0x00FFFFFF;
            mistPaint.setShader(new LinearGradient(0f, mistTop, 0f, mistBottom,
                    new int[]{rgb, (40 << 24) | rgb, rgb}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        int a = Math.round(night * 255f);
        for (int b = 0; b < STAR_DP.length; b++) starPaint[b].setColor(((STAR_ALPHA[b] * a / 255) << 24) | 0xEBF0FF);
        lightPaint.setColor((Math.round(night * 204f) << 24) | (LIGHT_COLOR & 0x00FFFFFF));
        moonPaint.setAlpha(Math.round(night * 230f));
        if (!homeName.isEmpty()) {
            int hill = Horizon.color(Horizon.LAYERS - 1, sky);
            housePaint.setColor(blend(hill, 0xFF000000, 0.35f));
            for (int i = 0; i < windows.length; i++) {
                float lit = windowLight(i, minute);
                windowPaint[i].setColor(blend(blend(hill, 0xFF000000, 0.5f), WINDOW_COLOR, lit));
                windowGlow[i].setAlpha(Math.round(lit * 255f));
            }
            // Brightest at dusk and night, still readable by day.
            namePaint.setColor(((150 + Math.round(night * 90f)) << 24) | (NAME_COLOR & 0x00FFFFFF));
            namePaint.setShadowLayer(12f * density, 0f, 0f, (Math.round(night * 120f) << 24) | 0xFFA040);
        }
        haloPaint.setShader(new RadialGradient(moonX, moonY, MOON_DP * density * 4f,
                new int[]{(Math.round(night * 30f) << 24) | 0xC8CDFF, 0x00C8CDFF}, null, Shader.TileMode.CLAMP));
        invalidate();
    }

    /** {@code a} moved toward {@code b} by {@code t}, 0 to 1, channel by channel. */
    private static int blend(int a, int b, float t) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int ca = (a >> shift) & 0xFF;
            int cb = (b >> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (masks[0] == null) return;
        if (night > 0f) {
            for (int b = 0; b < STAR_DP.length; b++) canvas.drawPoints(starPoints[b], starPaint[b]);
            float r = MOON_DP * density * 4f;
            canvas.drawRect(moonX - r, moonY - r, moonX + r, moonY + r, haloPaint);
            canvas.drawPath(moon, moonPaint);
        }
        for (int i = 0; i < Horizon.LAYERS; i++) {
            float ox = Horizon.offsetX(i, focusX) * density;
            float oy = Horizon.offsetY(i, focusY) * density;
            canvas.drawBitmap(masks[i], maskLeft[i] + ox, maskTop[i] + oy, hillPaint[i]);
            if (i == 1) canvas.drawRect(0f, mistTop, getWidth(), mistBottom, mistPaint);
            if (i == Horizon.LAYERS - 1 && (night > 0f || !homeName.isEmpty())) {
                canvas.save();
                canvas.translate(ox, oy);
                if (night > 0f) canvas.drawPoints(lightPoints, lightPaint);
                if (!homeName.isEmpty()) {
                    canvas.drawPath(house, housePaint);
                    for (int w = 0; w < windows.length; w++) {
                        if (windowGlow[w].getAlpha() > 0) {
                            float r = 34f * density;
                            canvas.drawRect(windows[w].centerX() - r, windows[w].centerY() - r,
                                    windows[w].centerX() + r, windows[w].centerY() + r, windowGlow[w]);
                        }
                        canvas.drawRect(windows[w], windowPaint[w]);
                    }
                }
                canvas.restore();
            }
        }
        // Outside the parallax: the name stays put at the top while the hills move.
        if (!homeName.isEmpty()) canvas.drawText(homeName, nameX, nameY, namePaint);
    }
}
