package ai.cp.mixin;

import ai.cp.config.RLConfig;
import net.minecraft.world.gen.GeneratorOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GeneratorOptions.class)
public class FixedSeedMixin {
    @Inject(method = "getSeed", at = @At("RETURN"), cancellable = true)
    private void fixSeed(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(RLConfig.WORLD_SEED);
    }
}
