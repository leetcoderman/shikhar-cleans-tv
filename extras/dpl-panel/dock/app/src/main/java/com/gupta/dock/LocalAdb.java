package com.gupta.dock;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Just enough of the ADB wire protocol to open one shell command on this panel's own adbd (127.0.0.1:5555), which
 * accepts connections without a key. If adbd asks for a key, this gives up and returns false. adbd listens on
 * localhost only (AdbGuard); this connects over loopback, so that doesn't matter here.
 */
final class LocalAdb {
    private static final String TAG = "GuptaDock";
    private static final int CNXN = 0x4e584e43;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;
    private static final int AUTH = 0x48545541;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    private LocalAdb() { }

    /** Runs {@code command} through {@code shell:} and returns once adbd has accepted it. */
    static boolean shell(String command) {
        return run(command, null);
    }

    /** Runs {@code command} and returns what it printed once it ends, or null if adbd can't be reached. */
    static String output(String command) {
        StringBuilder text = new StringBuilder();
        return run(command, text) ? text.toString() : null;
    }

    private static boolean run(String command, StringBuilder output) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, CNXN, VERSION, MAX_DATA, "host::\0");
            int reply = readCommand(in, null);
            if (reply == AUTH) {
                Log.w(TAG, "local adb wants a key; restart not possible");
                return false;
            }
            if (reply != CNXN) return false;
            send(out, OPEN, 1, 0, "shell:" + command + "\0");
            if (readCommand(in, null) != OKAY) return false;
            if (output == null) return true;
            // The command's output comes as WRTE messages, each acknowledged, until adbd closes the stream.
            while (true) {
                ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                int message = readCommand(in, output, header);
                if (message == CLSE) return true;
                if (message == WRTE) send(out, OKAY, 1, header.getInt(4), "");
            }
        } catch (IOException e) {
            Log.w(TAG, "local adb: " + e);
            return false;
        }
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, String data) throws IOException {
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        int sum = 0;
        for (byte b : payload) sum += b & 0xFF;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(sum).putInt(~command);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static int readCommand(DataInputStream in, StringBuilder text) throws IOException {
        return readCommand(in, text, ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN));
    }

    /**
     * Reads one message into {@code header} and returns its command. A WRTE's payload is added to {@code text} when
     * given; other payloads are skipped.
     */
    private static int readCommand(DataInputStream in, StringBuilder text, ByteBuffer header) throws IOException {
        in.readFully(header.array());
        int command = header.getInt(0);
        int length = header.getInt(12);
        if (command == WRTE && text != null) {
            byte[] payload = new byte[length];
            in.readFully(payload);
            text.append(new String(payload, StandardCharsets.UTF_8));
        } else {
            skip(in, length);
        }
        return command;
    }

    private static void skip(InputStream in, int length) throws IOException {
        byte[] buf = new byte[Math.min(Math.max(length, 1), 4096)];
        while (length > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, length));
            if (n < 0) throw new IOException("adb closed");
            length -= n;
        }
    }
}
