package ai.cp.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class EndSpawnMixin {
    @Inject(method = "onSpawn", at = @At("HEAD"))
    private void teleportToEnd(CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld endWorld = server.getWorld(World.END);
        if (endWorld == null) return;

        if (player.getWorld().getRegistryKey() != World.END) {
            player.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);
        }
    }
}
