package com.recom3.workout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/** Existing Recon HUDConnectivityMessage envelope (20-byte header + UTF-8 fields). */
final class WorkoutWire {
    static final String SESSION = "com.recom3.workout.SESSION";
    static final String STORED = "com.recom3.workout.SESSION_STORED";
    static final int MAX_FRAME = 25600;
    final int request;
    final String sender, action, info;
    final byte[] data;
    WorkoutWire(int request, String sender, String action, String info, byte[] data) {
        this.request = request; this.sender = sender; this.action = action; this.info = info; this.data = data;
    }
    byte[] encode() throws IOException {
        byte[] s = sender.getBytes("UTF-8"), a = action.getBytes("UTF-8"), i = info.getBytes("UTF-8");
        int length = 20 + s.length + a.length + i.length + data.length;
        if (length > MAX_FRAME) throw new IOException("Recon OBJECT exceeds 25 KiB");
        ByteBuffer bytes = ByteBuffer.allocate(length);
        bytes.putInt(length).putInt(request);
        put(bytes, s); put(bytes, a); put(bytes, i); bytes.put(data);
        return bytes.array();
    }
    private static void put(ByteBuffer buffer, byte[] bytes) { buffer.putInt(bytes.length).put(bytes); }
    private static String string(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 4) throw new IOException("Truncated Recon field");
        int length = buffer.getInt();
        if (length < 0 || length > 1024 || length > buffer.remaining()) throw new IOException("Invalid Recon field");
        byte[] bytes = new byte[length]; buffer.get(bytes); return new String(bytes, "UTF-8");
    }
    static WorkoutWire decode(byte[] data) throws IOException {
        if (data == null || data.length < 20 || data.length > MAX_FRAME) throw new IOException("Invalid Recon frame");
        ByteBuffer bytes = ByteBuffer.wrap(data);
        if (bytes.getInt() != data.length) throw new IOException("Recon length mismatch");
        int request = bytes.getInt();
        String sender = string(bytes), action = string(bytes), info = string(bytes);
        byte[] body = new byte[bytes.remaining()]; bytes.get(body);
        return new WorkoutWire(request, sender, action, info, body);
    }
    boolean confirms(SyncQueue.Entry entry, int expectedRequest) throws IOException {
        return request == expectedRequest && STORED.equals(action) && entry.hash.equals(info)
                && entry.id.equals(new String(data, "UTF-8"));
    }
    static String sha256(byte[] bytes) throws IOException {
        try {
            StringBuilder text = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                int value = b & 255;
                if (value < 16) text.append('0');
                text.append(Integer.toHexString(value));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
    }
}
