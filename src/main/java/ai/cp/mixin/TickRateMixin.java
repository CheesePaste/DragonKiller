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
 * Phase 1: dynamically adjusts server tick rate based on MSPT.
 * Phase 2: locked at 20 TPS (50ms) — high TPS causes RL training instability.
 */
@Mixin(MinecraftServer.class)
public class TickRateMixin {

	@Shadow private float tickTime;  // Smoothed MSPT in milliseconds

	@Unique
	private long currentIntervalMs = 2;  // Start at 500 TPS

	@Unique
	private boolean adaptiveEnabled = true;

	@Unique
	private int tickCounter;

	@Unique
	private long lastTickMs = 0;

	/**
	 * Phase 2: return 50 (vanilla 20 TPS) so the server loop uses normal timing.
	 * Phase 1: return the adaptive interval for dynamic tick rate.
	 */
	@ModifyConstant(method = "runServer", constant = @Constant(longValue = 50L))
	private long dynamicTickRate(long original) {
		if (RLConfig.IS_PHASE_2) {
			return 50L;
		}
		return currentIntervalMs;
	}

	/**
	 * Phase 2: enforce hard 50ms sleep after each tick to guarantee 20 TPS.
	 * Phase 1: adaptive tick rate with timing headroom check.
	 */
	@Inject(method = "runServer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/MinecraftServer;endTickMetrics()V",
			shift = At.Shift.AFTER))
	private void onPostTick(CallbackInfo ci) {
		if (RLConfig.IS_PHASE_2) {
			// Hard 20 TPS enforcement: sleep until 50ms since last tick
			long now = System.currentTimeMillis();
			if (lastTickMs != 0) {
				long elapsed = now - lastTickMs;
				if (elapsed < 50) {
					try {
						Thread.sleep(50 - elapsed);
					} catch (InterruptedException ignored) {}
				}
			}
			lastTickMs = System.currentTimeMillis();
		} else {
			// Phase 1: adaptive tick rate logic
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

				TickRateHelper.update(currentIntervalMs, tickTime);
			}
		}

		tickCounter++;
		if (tickCounter % 200 == 0) {
			int tps = (int)(1000 / (RLConfig.IS_PHASE_2 ? 50L : currentIntervalMs));
			DragonKiller.LOGGER.info("[RATE] {} TPS (interval={}ms, mspt={}ms)",
				tps, RLConfig.IS_PHASE_2 ? 50L : currentIntervalMs, String.format("%.1f", tickTime));
		}
	}
}
