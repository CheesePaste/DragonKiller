package ai.cp.config;

/** Thread-safe container for live tick rate data, written by TickRateMixin, read by /tps command. */
public class TickRateHelper {
    private static volatile int currentTps = 20;
    private static volatile float currentMspt = 50.0f;
    private static volatile long currentIntervalMs = 50;
    private static volatile long forcedIntervalMs = 0; // 0 = adaptive

    public static void update(long intervalMs, float mspt) {
        currentIntervalMs = intervalMs;
        currentMspt = mspt;
        currentTps = intervalMs > 0 ? (int)(1000 / intervalMs) : 0;
    }

    /** Lock TPS to a specific value. Pass 0 or negative for adaptive. */
    public static void setTargetTps(int tps) {
        if (tps <= 0) {
            forcedIntervalMs = 0;
        } else {
            forcedIntervalMs = Math.max(2, 1000 / tps); // cap at 500 TPS
        }
    }

    public static long getForcedIntervalMs() { return forcedIntervalMs; }
    public static int getTps() { return currentTps; }
    public static float getMspt() { return currentMspt; }
    public static long getIntervalMs() { return currentIntervalMs; }
}
