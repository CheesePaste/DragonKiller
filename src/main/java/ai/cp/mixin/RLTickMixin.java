package ai.cp.mixin;

import ai.cp.rl.RLTickHandler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class RLTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onServerTick(CallbackInfo ci) {
        RLTickHandler.onServerTick((MinecraftServer) (Object) this);
    }
}
