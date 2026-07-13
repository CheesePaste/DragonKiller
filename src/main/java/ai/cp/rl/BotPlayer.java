package ai.cp.rl;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

public class BotPlayer extends ServerPlayerEntity {
    private static final UUID BOT_UUID = UUID.nameUUIDFromBytes("RLBot".getBytes());

    public BotPlayer(MinecraftServer server, ServerWorld world) {
        super(server, world, new GameProfile(BOT_UUID, "RLBot"));

        // Dummy connection that absorbs all outgoing packets
        ClientConnection conn = new ClientConnection(NetworkSide.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet) {}

            @Override
            public void send(Packet<?> packet, PacketCallbacks callbacks) {}

            @Override
            public boolean isOpen() { return true; }

            @Override
            public boolean isChannelAbsent() { return true; }
        };

        // Network handler that:
        //  - Absorbs all packets (no client)
        //  - Overrides tick() to ONLY run physics, skipping the position
        //    reset that ServerPlayNetworkHandler.tick() normally does
        //    (updatePositionAndAngles(lastTickX, lastTickY, lastTickZ, ...))
        // Note: this handler's tick() is only ever called via ServerNetworkIo
        // for real network connections. BotPlayer's dummy ClientConnection is
        // NOT registered with ServerNetworkIo, so this tick() is effectively
        // dead code in the current architecture. RLTickHandler calls
        // playerTick() separately for physics.
        this.networkHandler = new ServerPlayNetworkHandler(server, conn, this) {
            @Override
            public void sendPacket(Packet<?> packet) {}

            @Override
            public void sendPacket(Packet<?> packet, PacketCallbacks callbacks) {}

            @Override
            public void tick() {
                this.player.playerTick();
            }
        };
    }

    @Override
    public void sendMessage(Text message) {}

    @Override
    public void sendMessage(Text message, boolean actionBar) {}

    @Override
    public void sendMessageToClient(Text message, boolean overlay) {}

    @Override
    public boolean isDisconnected() { return false; }
}
