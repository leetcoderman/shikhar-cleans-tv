package com.gupta.airplayreceiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes AirPlay audio (AAC-ELD for screen mirroring, ALAC or AAC-LC for audio-only AirPlay) with MediaCodec and plays it on the
 * panel's current media output (speakers, HDMI/ARC or a connected Bluetooth device).
 */
final class AudioPlayer {
    private static final String TAG = NativeReceiver.TAG;
    // AudioSpecificConfig for AirPlay audio: 44.1 kHz stereo; ELD with 480-sample frames, LC with 1024.
    private static final byte[] ASC_AAC_ELD = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
    private static final byte[] ASC_AAC_LC = {0x12, 0x10};
    // ALAC magic cookie ('alac' atom: size, tag, version, then ALACSpecificConfig) for AirPlay's
    // fixed stream: 352 samples/frame, 16 bit, 2 channels, 44.1 kHz (UxPlay uses the same values).
    private static final byte[] ALAC_COOKIE = {
            0, 0, 0, 36, 'a', 'l', 'a', 'c', 0, 0, 0, 0,
            0, 0, 0x01, 0x60,           // frameLength 352
            0, 16, 40, 10, 14, 2,       // compatibleVersion, bitDepth, pb, mb, kb, numChannels
            0, (byte) 0xFF,             // maxRun 255
            0, 0, 0, 0, 0, 0, 0, 0,     // maxFrameBytes, avgBitRate (unknown)
            0, 0, (byte) 0xAC, 0x44};   // sampleRate 44100
    private static final int MAX_QUEUED = 40;   // about 0.4 s of ELD; older frames are dropped to bound latency
    private static final int MAX_QUEUED_ALAC = 150;  // about 1.2 s: audio-only senders send in bursts

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private Thread thread;
    private volatile boolean running;
    private volatile int ct;
    private volatile int spf = 480;
    private volatile float gain = 1f;
    private boolean warnedPcm;

    synchronized void start(int contentType, int samplesPerFrame) {
        if (running && ct == contentType) return;
        stop();
        ct = contentType;
        spf = samplesPerFrame > 0 ? samplesPerFrame : (ct == 2 ? 352 : ct == 4 ? 1024 : 480);
        if (ct != 8 && ct != 4 && ct != 2) {
            if (!warnedPcm) Log.w(TAG, "audio type ct=" + ct + " (PCM) is not supported; sound will be silent");
            warnedPcm = true;
            return;
        }
        running = true;
        thread = new Thread(this::loop, "airplay-audio");
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

    void feed(byte[] data) {
        if (!running) return;
        queue.offer(data);
        int max = ct == 2 ? MAX_QUEUED_ALAC : MAX_QUEUED;
        while (queue.size() > max) queue.poll();
    }

    void flush() {
        queue.clear();
    }

    /** AirPlay volume: -30..0 dB, -144 = mute. Mapped onto the stream gain, panel volume untouched. */
    void setVolume(float db) {
        float g;
        if (db <= -30f) g = 0f;
        else if (db >= 0f) g = 1f;
        else g = (float) Math.pow(10, db / 20.0);
        gain = g;
    }

    private void loop() {
        MediaCodec codec = null;
        AudioTrack track = null;
        float appliedGain = -1f;
        try {
            String mime = ct == 2 ? "audio/alac" : MediaFormat.MIMETYPE_AUDIO_AAC;
            MediaFormat fmt = MediaFormat.createAudioFormat(mime, 44100, 2);
            if (ct == 2) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(ALAC_COOKIE));
            } else {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(ct == 8 ? ASC_AAC_ELD : ASC_AAC_LC));
                fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            Log.i(TAG, "audio decoder " + codec.getName() + " started, ct=" + ct + " spf=" + spf);
            long ptsStep = spf * 1_000_000L / 44100;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long pts = 0;
            while (running) {
                byte[] frame = queue.poll(20, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    int idx = codec.dequeueInputBuffer(20_000);
                    if (idx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(idx);
                        in.clear();
                        in.put(frame, 0, Math.min(frame.length, in.capacity()));
                        codec.queueInputBuffer(idx, 0, Math.min(frame.length, in.capacity()), pts, 0);
                        pts += ptsStep;
                    }
                }
                while (true) {
                    int out = codec.dequeueOutputBuffer(info, 0);
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        if (track != null) track.release();
                        track = createTrack(of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        appliedGain = -1f;
                    } else if (out >= 0) {
                        if (track == null) track = createTrack(44100, 2);
                        if (appliedGain != gain) {
                            appliedGain = gain;
                            track.setVolume(appliedGain);
                        }
                        ByteBuffer pcm = codec.getOutputBuffer(out);
                        if (info.size > 0 && pcm != null) {
                            pcm.position(info.offset);
                            pcm.limit(info.offset + info.size);
                            track.write(pcm, info.size, AudioTrack.WRITE_BLOCKING);
                        }
                        codec.releaseOutputBuffer(out, false);
                    } else {
                        break;
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.e(TAG, "audio error", e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                track.release();
            }
            running = false;
        }
    }

    private static AudioTrack createTrack(int rate, int channels) {
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        t.play();
        Log.i(TAG, "audio output " + rate + " Hz, " + channels + " ch");
        return t;
    }
}
