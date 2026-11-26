package android.content;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
    public static volatile PendingResult last;
    public final PendingResult goAsync() { last = new PendingResult(); return last; }
    public static final class PendingResult {
        private final CountDownLatch finished = new CountDownLatch(1);
        public void finish() { finished.countDown(); }
        public boolean await() throws InterruptedException { return finished.await(5, TimeUnit.SECONDS); }
    }
}
