package ai.cp.mixin;

import ai.cp.DragonKiller;
import ai.cp.config.RLConfig;
import ai.cp.config.TickRateHelper;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dynamically adjusts the server tick rate based on MSPT (smoothed tick duration).
 * Ticks as fast as the CPU can handle, keeping ~30% headroom to avoid overload.
 * Phase 2: locked at 20 TPS (50ms) for consistent vanilla behavior.
 */
@Mixin(MinecraftServer.class)
public class TickRateMixin {

    @Shadow private float tickTime;  // Smoothed MSPT in milliseconds

    @Unique
    private long currentIntervalMs = 2;  // Start at 500 TPS

    @Unique
    private boolean adaptiveEnabled = true; // Enable adaptive tick rate for all phases

    @Unique
    private int tickCounter;

    /**
     * Replace all 50L literals in runServer() with the dynamic interval.
     * Affects: lag detection, catchup, timeReference, nextTickTimestamp.
     */
    @ModifyConstant(method = "runServer", constant = @Constant(longValue = 50L))
    private long dynamicTickRate(long original) {
        return currentIntervalMs;
    }

    /**
     * After every tick, adjust interval based on smoothed MSPT.
     * Target: tick takes ~70% of interval (30% headroom for spikes).
     * Clamped to [2ms = 500 TPS, 50ms = 20 TPS].
     * Smooths changes to max +/-1ms per tick to prevent oscillation.
     */
    @Inject(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;endTickMetrics()V",
            shift = At.Shift.AFTER))
    private void onPostTick(CallbackInfo ci) {
        // Check for command-forced TPS lock
        long forced = TickRateHelper.getForcedIntervalMs();
        if (forced > 0 && forced != currentIntervalMs) {
            currentIntervalMs = forced;
            TickRateHelper.update(currentIntervalMs, tickTime);
            return;
        }

        if (adaptiveEnabled) {
            if (tickTime <= 0.0f) return;

            long targetMs = (long) Math.ceil(tickTime * 1.43f);

            if (targetMs < 2) targetMs = 2;
            if (targetMs > 50) targetMs = 50;

            long diff = targetMs - currentIntervalMs;
            if (diff > 1) diff = 1;
            if (diff < -1) diff = -1;
            currentIntervalMs += diff;

            // Publish to helper for /tps command
            TickRateHelper.update(currentIntervalMs, tickTime);
        }

        tickCounter++;
        if (tickCounter % 200 == 0) {
            int tps = (int)(1000 / currentIntervalMs);
            DragonKiller.LOGGER.info("[RATE] {} TPS (interval={}ms, mspt={}ms)",
                tps, currentIntervalMs, String.format("%.1f", tickTime));
        }
    }
}
