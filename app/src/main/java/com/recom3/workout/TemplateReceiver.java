package com.recom3.workout;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.*;

/** Stock OBJECT broadcasts wake the app; validation, fsync/readback and ACK run off the HUD thread. */
public final class TemplateReceiver extends BroadcastReceiver {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), new ThreadFactory() {
                public Thread newThread(Runnable task) { return new Thread(task, "WorkoutTemplateRx"); }
            });
    private static final WorkoutWire.Decoder DECODER = new WorkoutWire.Decoder();
    private static TemplateService service;
    private static String announcedHash = "";
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static synchronized TemplateService service(Context context) {
        if (service == null) service = new TemplateService(new TemplateStore(context.getFilesDir()), LocalProfiles.open(context));
        return service;
    }
    static void allowReplacement(Context context) {
        service(context).allowReplacement(SystemClock.elapsedRealtime());
    }
    static WorkoutTemplate latest(Context context) { return service(context).latest(); }
    @Override public void onReceive(Context context, Intent intent) {
        if (!WorkoutWire.TEMPLATE.equals(intent.getAction()) && !WorkoutWire.ROUTINE_V2.equals(intent.getAction())) return;
        final byte[] bytes;
        try { bytes = intent.getByteArrayExtra("message"); }
        catch (RuntimeException invalid) { return; }
        if (bytes == null || bytes.length == 0 || bytes.length > WorkoutWire.MAX_FRAME) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        try {
            WORKER.execute(new Runnable() {
                public void run() {
                    try {
                        for (WorkoutWire frame : DECODER.append(bytes, SystemClock.elapsedRealtime())) {
                            try {
                                WorkoutWire receipt = service(app).receive(frame, SystemClock.elapsedRealtime());
                                ReconWorkoutTransport.sendObject(app, receipt);
                                if (!announcedHash.equals(receipt.info)) {
                                    announcedHash = receipt.info;
                                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                                        public void run() { Toast.makeText(app, "New routine saved. Review it in Workout.", Toast.LENGTH_SHORT).show(); }
                                    });
                                }
                                Log.i("WorkoutTemplate", "Routine stored for the next workout; receipt submitted");
                            } catch (IOException invalid) { Log.w("WorkoutTemplate", "Template rejected or storage unavailable; no receipt"); }
                            catch (SecurityException unavailable) { Log.w("WorkoutTemplate", "Template receipt unavailable"); }
                        }
                    } catch (IOException invalid) { Log.w("WorkoutTemplate", "Invalid OBJECT frame; no receipt"); }
                    finally { pending.finish(); }
                }
            });
        } catch (RejectedExecutionException busy) { pending.finish(); Log.w("WorkoutTemplate", "Receiver busy; retry from ICE3"); }
    }
}
