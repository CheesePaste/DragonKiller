package ai.cp.mixin;

import ai.cp.rl.RLTickHandler;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public class ShieldBlockMixin {
    @Inject(method = "damageShield", at = @At("HEAD"))
    private void onDamageShield(float amount, CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (player.equals(RLTickHandler.getBotPlayer())) {
            RLTickHandler.onShieldBlock(amount);
        }
    }
}
