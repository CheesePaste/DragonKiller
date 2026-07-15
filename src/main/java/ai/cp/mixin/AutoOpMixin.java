package ai.cp.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.authlib.GameProfile;

@Mixin(PlayerManager.class)
public abstract class AutoOpMixin {

    @Shadow
    public abstract void addToOperators(GameProfile profile);

    @Shadow
    public abstract void sendCommandTree(ServerPlayerEntity player);

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
        // Automatically make any joining player an OP, unless they are the bot player
        if (!player.getGameProfile().getName().equals("RLBot")) {
            addToOperators(player.getGameProfile());
            sendCommandTree(player);
            // Send feedback message
            player.sendMessage(net.minecraft.text.Text.literal("§a[AutoOP] You have been automatically granted OP privileges. Feel free to use spectator commands!"), false);
        }
    }
}
