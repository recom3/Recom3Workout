package com.recom3.workout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/** Existing Recon HUDConnectivityMessage envelope (20-byte header + UTF-8 fields). */
final class WorkoutWire {
    static final String SESSION = "com.recom3.workout.SESSION";
    static final String STORED = "com.recom3.workout.SESSION_STORED";
    static final String TEMPLATE = "com.recom3.workout.TEMPLATE";
    static final String TEMPLATE_STORED = "com.recom3.workout.TEMPLATE_STORED";
    static final String ROUTINE_V2 = "com.recom3.workout.ROUTINE_V2";
    static final String ROUTINE_V2_STORED = "com.recom3.workout.ROUTINE_V2_STORED";
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
        byte[] bytes = new byte[length]; buffer.get(bytes); return TemplateJson.utf8(bytes);
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

    /** Bounded assembly for fragmented/coalesced OBJECT delivery; no new framing. */
    static final class Decoder {
        private byte[] pending = new byte[0];
        private long last;
        java.util.List<WorkoutWire> append(byte[] bytes, long nowMs) throws IOException {
            if (nowMs < last || nowMs - last > 5000) pending = new byte[0];
            last = nowMs;
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME
                    || pending.length + bytes.length > 2 * MAX_FRAME) {
                pending = new byte[0]; throw new IOException("Invalid OBJECT fragment");
            }
            byte[] all = new byte[pending.length + bytes.length];
            System.arraycopy(pending, 0, all, 0, pending.length);
            System.arraycopy(bytes, 0, all, pending.length, bytes.length);
            java.util.List<WorkoutWire> result = new java.util.ArrayList<WorkoutWire>();
            int offset = 0;
            try {
                while (all.length - offset >= 4) {
                    int length = ByteBuffer.wrap(all, offset, 4).getInt();
                    if (length < 20 || length > MAX_FRAME) throw new IOException("Invalid OBJECT length");
                    if (all.length - offset < length) break;
                    byte[] frame = new byte[length]; System.arraycopy(all, offset, frame, 0, length);
                    result.add(decode(frame)); offset += length;
                }
                pending = new byte[all.length - offset];
                System.arraycopy(all, offset, pending, 0, pending.length);
                return result;
            } catch (IOException invalid) { pending = new byte[0]; throw invalid; }
        }
    }
}
