package ai.cp.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public class WorldSimplifierMixin {
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void cancelEndEntities(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerWorld self = (ServerWorld) (Object) this;
        if (self.getRegistryKey() == World.END) {
            if (entity instanceof EndermanEntity) {
                cir.setReturnValue(false);
            }
        }
    }
}
