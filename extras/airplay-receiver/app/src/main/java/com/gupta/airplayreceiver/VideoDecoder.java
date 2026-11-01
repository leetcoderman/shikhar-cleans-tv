package com.gupta.airplayreceiver;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes the mirrored H.264/H.265 stream (Annex-B access units, parameter sets in front of each
 * key frame) with MediaCodec straight onto the mirroring activity's surface.
 *
 * AirPlay mirroring has no way to ask the sender for a new key frame, so until a decoder runs
 * every frame since the last key frame is kept and replayed when the surface appears.
 */
final class VideoDecoder {
    private static final String TAG = NativeReceiver.TAG;
    private static final int MAX_BACKLOG = 300;  // 10 s at 30 fps

    interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final class Frame {
        final byte[] data;
        final boolean h265;
        Frame(byte[] data, boolean h265) { this.data = data; this.h265 = h265; }
    }

    private static final Frame RESET = new Frame(new byte[0], false);

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(600);
    private final List<Frame> backlog = new ArrayList<>();
    private Thread thread;
    private volatile boolean running;

    // decoder-thread state
    private Surface surface;
    private MediaCodec codec;
    private boolean codecH265;
    private byte[] configKey;     // parameter sets the running codec was configured with
    private byte[] pendingConfig; // parameter sets of the latest key frame
    private boolean pendingH265;
    private long pts;
    private volatile SizeListener sizeListener;
    private int frames;

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "airplay-video");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException ignored) {}
            thread = null;
        }
        queue.clear();
    }

    void feed(byte[] data, boolean h265) {
        if (!queue.offer(new Frame(data, h265))) {
            Log.w(TAG, "video queue full, dropping frame");
        }
    }

    /** Forgets the stream (session ended); the next session starts with a new key frame. */
    void reset() {
        queue.clear();
        queue.offer(RESET);
    }

    void setSurface(Surface s) {
        queue.offer(new SurfaceChange(s));
    }

    void setSizeListener(SizeListener l) {
        sizeListener = l;
    }

    private static final class SurfaceChange {
        final Surface surface;
        SurfaceChange(Surface s) { surface = s; }
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            Object item;
            try {
                item = queue.poll(codec != null ? 5 : 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            try {
                if (item instanceof SurfaceChange) {
                    releaseCodec();
                    surface = ((SurfaceChange) item).surface;
                    if (surface != null) startCodecFromBacklog();
                } else if (item == RESET) {
                    releaseCodec();
                    backlog.clear();
                    pendingConfig = null;
                } else if (item instanceof Frame) {
                    handleFrame((Frame) item);
                }
                if (codec != null) drain(info);
            } catch (Exception e) {
                Log.e(TAG, "video decoder error", e);
                releaseCodec();
                if (surface != null) startCodecFromBacklog();
            }
        }
        releaseCodec();
        backlog.clear();
    }

    private void handleFrame(Frame f) {
        byte[] config = extractParameterSets(f.data, f.h265);
        if (config != null) {
            pendingConfig = config;
            pendingH265 = f.h265;
            backlog.clear();
            if (codec != null && (codecH265 != f.h265 || !Arrays.equals(configKey, config))) {
                Log.i(TAG, "stream parameters changed, restarting decoder");
                releaseCodec();
            }
        }
        if (codec == null) {
            if (pendingConfig == null) return;  // wait for the first key frame
            backlog.add(f);
            if (backlog.size() > MAX_BACKLOG) {
                backlog.clear();
                pendingConfig = null;           // too old to replay; wait for the next key frame
            }
            if (surface != null) startCodecFromBacklog();
            return;
        }
        queueInput(f.data);
    }

    private void startCodecFromBacklog() {
        if (pendingConfig == null || surface == null || backlog.isEmpty()) return;
        String mime = pendingH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, pendingH265 ? 3840 : 1920, pendingH265 ? 2160 : 1080);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
        int pps = pendingH265 ? -1 : findStartCode(pendingConfig, 4);
        if (pps > 0) {  // H.264: SPS in csd-0, PPS in csd-1
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(pendingConfig, 0, pps)));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(pendingConfig, pps, pendingConfig.length)));
        } else {        // H.265: VPS+SPS+PPS together in csd-0
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(pendingConfig));
        }
        MediaCodec c = null;
        try {
            c = MediaCodec.createDecoderByType(mime);
            MediaCodecInfo.CodecCapabilities caps = c.getCodecInfo().getCapabilitiesForType(mime);
            if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            c.configure(fmt, surface, null, 0);
            c.start();
        } catch (Exception e) {
            Log.e(TAG, "cannot start " + mime + " decoder", e);
            if (c != null) c.release();
            return;
        }
        codec = c;
        codecH265 = pendingH265;
        configKey = pendingConfig;
        Log.i(TAG, "decoder " + c.getName() + " started for " + mime + ", replaying " + backlog.size() + " frames");
        List<Frame> replay = new ArrayList<>(backlog);
        backlog.clear();
        for (Frame f : replay) queueInput(f.data);
    }

    private void queueInput(byte[] data) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int attempt = 0; attempt < 20; attempt++) {
            int idx = codec.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer buf = codec.getInputBuffer(idx);
                buf.clear();
                int len = Math.min(data.length, buf.capacity());
                if (len < data.length) Log.w(TAG, "frame of " + data.length + " bytes truncated");
                buf.put(data, 0, len);
                pts += 33_333;
                codec.queueInputBuffer(idx, 0, len, pts, 0);
                if (++frames % 900 == 1) Log.i(TAG, "video frames decoded so far: " + frames);
                return;
            }
            drain(info);  // make room
        }
        Log.w(TAG, "decoder input stalled, frame dropped");
    }

    private void drain(MediaCodec.BufferInfo info) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx >= 0) {
                codec.releaseOutputBuffer(idx, true);
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = codec.getOutputFormat();
                int w = out.getInteger(MediaFormat.KEY_WIDTH);
                int h = out.getInteger(MediaFormat.KEY_HEIGHT);
                if (out.containsKey("crop-right") && out.containsKey("crop-left")) {
                    w = out.getInteger("crop-right") - out.getInteger("crop-left") + 1;
                    h = out.getInteger("crop-bottom") - out.getInteger("crop-top") + 1;
                }
                Log.i(TAG, "video size " + w + "x" + h);
                SizeListener l = sizeListener;
                if (l != null) l.onVideoSize(w, h);
            } else {
                return;
            }
        }
    }

    private void releaseCodec() {
        if (codec == null) return;
        try { codec.stop(); } catch (Exception ignored) {}
        codec.release();
        codec = null;
        configKey = null;
    }

    /**
     * Returns the parameter-set NAL units (with start codes) at the start of a key frame access
     * unit: SPS+PPS for H.264, VPS+SPS+PPS for H.265. Null if the frame carries none.
     */
    static byte[] extractParameterSets(byte[] d, boolean h265) {
        int end = -1;
        boolean found = false;
        int i = 0;
        while (true) {
            int sc = findStartCode(d, i);
            if (sc < 0) break;
            int nal = sc + 4;
            if (nal >= d.length) break;
            boolean isParam;
            if (h265) {
                int type = (d[nal] >> 1) & 0x3f;
                isParam = type == 32 || type == 33 || type == 34;
            } else {
                int type = d[nal] & 0x1f;
                isParam = type == 7 || type == 8;
            }
            if (!isParam) {
                end = sc;
                break;
            }
            found = true;
            i = nal;
        }
        if (!found) return null;
        if (end < 0) end = d.length;
        return Arrays.copyOf(d, end);
    }

    private static int findStartCode(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) return i;
        }
        return -1;
    }
}
