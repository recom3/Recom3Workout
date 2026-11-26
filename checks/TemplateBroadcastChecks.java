package com.recom3.workout;

import android.content.*;
import java.io.*;
import java.util.*;

/** Executes the production BroadcastReceiver and OBJECT submission using host-only Android boundaries. */
public final class TemplateBroadcastChecks {
    private static int passed;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static final class App extends Context {
        final File files;
        final List<Intent> receipts = Collections.synchronizedList(new ArrayList<Intent>());
        volatile String senderThread;
        App(File files) { this.files = files; }
        public File getFilesDir() { return files; }
        public void sendBroadcast(Intent intent) { senderThread = Thread.currentThread().getName(); receipts.add(intent); }
    }
    private static void deliver(App app, byte[] bytes) throws Exception {
        new TemplateReceiver().onReceive(app, new Intent(WorkoutWire.TEMPLATE).putExtra("message", bytes));
        check(BroadcastReceiver.last.await(), "asynchronous broadcast finishes within its test deadline");
    }
    public static void main(String[] args) throws Exception {
        File files = new File(args[0], "broadcast-" + UUID.randomUUID());
        if (!files.mkdirs()) throw new IOException("Cannot create test directory");
        App app = new App(files);
        byte[] frame = TemplateChecks.message(TemplateChecks.VECTOR, 123).encode();
        deliver(app, frame);
        check(app.receipts.size() == 1 && new TemplateStore(files).load().hash.equals(TemplateChecks.HASH),
                "production OBJECT broadcast receiver persists the routine before returning a receipt");
        Intent submitted = app.receipts.get(0);
        WorkoutWire receipt = WorkoutWire.decode(submitted.getByteArrayExtra("HUDConnectivityMessage"));
        check(submitted.getAction().equals("com.reconinstruments.mobilesdk.hudconnectivity.channel.object")
                && receipt.action.equals(WorkoutWire.TEMPLATE_STORED) && receipt.request == 123,
                "receipt reuses the stock OBJECT broadcast and original request key");
        check(app.senderThread.equals("WorkoutTemplateRx") && !Thread.currentThread().getName().equals(app.senderThread),
                "production receiver storage and receipt submission run off the calling HUD thread");
        check(TemplateReceiver.latest(app).hash.equals(TemplateChecks.HASH) && android.widget.Toast.shown == 1,
                "only stored routine becomes available to the HUD and triggers its brief notice");
        deliver(app, Arrays.copyOfRange(frame, 0, 7));
        check(app.receipts.size() == 1, "partial broadcast does not produce an early success receipt");
        deliver(app, Arrays.copyOfRange(frame, 7, frame.length));
        check(app.receipts.size() == 2 && android.widget.Toast.shown == 1,
                "fragmented retransmission confirms idempotently without repeating the notice");
        byte[] wrong = frame.clone(); wrong[wrong.length - 1] = 32;
        deliver(app, wrong);
        check(app.receipts.size() == 2, "invalid checksum through the real receiver cannot emit an ACK");
        File pending = new File(files, "workout-template/pending.template");
        if (!pending.mkdir()) throw new IOException("Cannot simulate write failure");
        deliver(app, TemplateChecks.message(TemplateChecks.VECTOR.replace("\"revision\":7", "\"revision\":8"), 124).encode());
        check(app.receipts.size() == 2 && new TemplateStore(files).load().revision == 7,
                "write failure through the real receiver retains the old routine and emits no ACK");
        check(pending.delete(), "test storage failure fixture is cleaned up");
        String manifest = new String(SyncFiles.read(new File(args[1], "app/src/main/AndroidManifest.xml"), 16384), "UTF-8");
        check(manifest.contains(".TemplateReceiver") && manifest.contains("com.recom3.workout.TEMPLATE"),
                "manifest registers the TEMPLATE receiver for delivery while the activity is closed");
        System.out.println(passed + " broadcast checks passed (host Android boundaries; not device validation)");
        System.exit(0);
    }
}
