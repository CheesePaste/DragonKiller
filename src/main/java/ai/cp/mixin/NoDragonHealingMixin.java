package ai.cp.mixin;

import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonEntity.class)
public class NoDragonHealingMixin {
    @Inject(method = "tickWithEndCrystals", at = @At("HEAD"), cancellable = true)
    private void cancelCrystalInteraction(CallbackInfo ci) {
        ci.cancel();
    }
}
