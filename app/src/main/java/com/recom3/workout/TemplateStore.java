package com.recom3.workout;

import java.io.*;

/** A single local routine context, durable before ACK; no remote account identity is assumed. */
class TemplateStore {
    private static final int MAGIC = 0x57335431;
    private final File file;
    TemplateStore(File files) { file = new File(files, "workout-template/active.template"); }

    WorkoutTemplate load() throws IOException {
        synchronized (TemplateStore.class) {
            recover();
            return file.exists() ? read(file) : null;
        }
    }
    WorkoutTemplate accept(WorkoutTemplate next, boolean replaceContext) throws IOException {
        synchronized (TemplateStore.class) {
            WorkoutTemplate old = load();
            if (old != null) {
                if (old.id.equals(next.id) && old.revision == next.revision && old.hash.equals(next.hash)) return old;
                if (!replaceContext && (!old.id.equals(next.id) || next.revision < old.revision
                        || next.revision == old.revision)) throw new IOException("template_context_or_revision_conflict");
            }
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("template_storage_unavailable");
            File pending = new File(parent, "pending.template");
            File backup = new File(file.getPath() + ".bak");
            // Only one process/serialized receiver writes this slot. An interrupted pending file is never read.
            write(pending, next);
            if (!read(pending).hash.equals(next.hash)) throw new IOException("template_readback_failed");
            if (file.exists() && !file.renameTo(backup)) throw new IOException("template_backup_failed");
            try {
                if (!pending.renameTo(file)) throw new IOException("template_replace_failed");
                verifyStored(next);
                if (backup.exists() && !backup.delete()) throw new IOException("template_commit_failed");
            } catch (IOException failure) {
                if (file.exists() && !file.delete()) throw new IOException("template_rollback_failed", failure);
                if (backup.exists() && !backup.renameTo(file)) throw new IOException("template_rollback_failed", failure);
                throw failure;
            }
            return next;
        }
    }
    void verifyStored(WorkoutTemplate expected) throws IOException {
        WorkoutTemplate stored = read(file);
        if (!stored.hash.equals(expected.hash) || !stored.id.equals(expected.id) || stored.revision != expected.revision)
            throw new IOException("template_readback_failed");
    }
    private void recover() throws IOException {
        File backup = new File(file.getPath() + ".bak");
        if (!backup.exists()) return;
        read(backup); // Never erase a current record in favor of an unverified backup.
        if (file.exists() && !file.delete()) throw new IOException("template_recovery_failed");
        if (!backup.renameTo(file)) throw new IOException("template_recovery_failed");
    }
    private static void write(File pending, WorkoutTemplate template) throws IOException {
        byte[] payload = template.bytes();
        FileOutputStream stream = new FileOutputStream(pending);
        try {
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(MAGIC); out.writeUTF(template.hash); out.writeInt(payload.length); out.write(payload);
            out.flush(); stream.getFD().sync();
        } finally { stream.close(); }
    }
    private static WorkoutTemplate read(File source) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(SyncFiles.read(source, 16600)));
        try {
            if (input.readInt() != MAGIC) throw new IOException("invalid_template_record");
            String hash = input.readUTF();
            int length = input.readInt();
            if (length < 1 || length > WorkoutTemplate.MAX_WIRE || length != input.available())
                throw new IOException("invalid_template_record");
            byte[] bytes = new byte[length]; input.readFully(bytes);
            if (!hash.equals(WorkoutWire.sha256(bytes))) throw new IOException("invalid_template_record");
            return WorkoutTemplate.parse(bytes);
        } finally { input.close(); }
    }
}
