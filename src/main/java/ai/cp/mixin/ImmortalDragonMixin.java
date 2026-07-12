package ai.cp.mixin;

import ai.cp.config.RLConfig;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.boss.dragon.phase.PhaseManager;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnderDragonEntity.class)
public class ImmortalDragonMixin {
    @Shadow private PhaseManager phaseManager;

    @Inject(method = "damagePart", at = @At("TAIL"))
    private void preventDragonDeath(EnderDragonPart part, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (RLConfig.IS_PHASE_2) return; // Phase 2: dragon can die

        EnderDragonEntity self = (EnderDragonEntity) (Object) this;

        // Undo death state: heal and reset dead flag so the dragon stays alive
        if (self.isDead() || self.getHealth() <= 0.0F) {
            ((LivingEntityAccessor) self).setDead(false);
            self.setHealth(self.getMaxHealth());
        }
        if (phaseManager.getCurrent().getType() == PhaseType.DYING) {
            phaseManager.setPhase(PhaseType.SITTING_SCANNING);
        }
    }
}
