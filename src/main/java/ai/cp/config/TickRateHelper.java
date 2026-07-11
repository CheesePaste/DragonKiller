package ai.cp.config;

/** Thread-safe container for live tick rate data, written by TickRateMixin, read by /tps command. */
public class TickRateHelper {
    private static volatile int currentTps = 20;
    private static volatile float currentMspt = 50.0f;
    private static volatile long currentIntervalMs = 50;

    public static void update(long intervalMs, float mspt) {
        currentIntervalMs = intervalMs;
        currentMspt = mspt;
        currentTps = intervalMs > 0 ? (int)(1000 / intervalMs) : 0;
    }

    public static int getTps() { return currentTps; }
    public static float getMspt() { return currentMspt; }
    public static long getIntervalMs() { return currentIntervalMs; }
}
