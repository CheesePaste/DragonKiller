package ai.cp.mixin;

import ai.cp.config.RLConfig;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonEntity.class)
public class StaticDragonMixin {
    @Shadow private EnderDragonFight fight;

    @Unique
    private boolean dragonInitialized = false;

    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void cancelDragonAI(CallbackInfo ci) {
        if (RLConfig.IS_PHASE_2) return; // Phase 2: let dragon AI run freely

        EnderDragonEntity dragon = (EnderDragonEntity) (Object) this;
        if (!dragonInitialized) {
            // Teleport to perch above exit portal
            dragon.setPosition(0.5, 66.0, 0.5);
            // Set sitting phase so client renders perched pose
            dragon.getPhaseManager().setPhase(PhaseType.SITTING_SCANNING);
            dragonInitialized = true;
            return; // Let first tick run to position body parts correctly
        }
        // Still update boss bar and fight state before freezing AI
        if (this.fight != null) {
            this.fight.updateFight(dragon);
        }
        ci.cancel(); // Freeze dragon after initialization
    }
}
