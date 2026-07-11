package ai.cp.rl;

import ai.cp.DragonKiller;
import ai.cp.config.RLConfig;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

public class EpisodeManager {
    public enum State { IDLE, RUNNING, DONE }

    private State state = State.IDLE;
    private int tickCount;
    private double totalReward;
    private int episodeCount;
    private boolean dragonJustDied;
    private boolean playerJustDied;

    public void startEpisode() {
        state = State.RUNNING;
        tickCount = 0;
        totalReward = 0.0;
        dragonJustDied = false;
        playerJustDied = false;
        DragonKiller.LOGGER.info("Episode {} started", episodeCount + 1);
    }

    public void endEpisode(String reason) {
        state = State.DONE;
        episodeCount++;
        DragonKiller.LOGGER.info("Episode {} ended: {} ({} ticks, total reward: {})",
            episodeCount, reason, tickCount, totalReward);
    }

    public void tick() {
        if (state == State.RUNNING) {
            tickCount++;
        }
    }

    public void addReward(double reward) {
        totalReward += reward;
    }

    public DoneInfo checkDone(ServerPlayerEntity player, ServerWorld world) {
        if (state != State.RUNNING) return new DoneInfo(false, "");

        // Check player death
        if (player.isDead()) {
            return new DoneInfo(true, "player_died");
        }

        // Check dragon death
        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null || dragon.isDead()) {
            return new DoneInfo(true, "dragon_killed");
        }

        // Check timeout
        if (tickCount >= RLConfig.EPISODE_TIMEOUT) {
            return new DoneInfo(true, "timeout");
        }

        // Check if player left the end
        if (player.getWorld().getRegistryKey() != World.END) {
            return new DoneInfo(true, "left_end");
        }

        return new DoneInfo(false, "");
    }

    public void resetPlayer(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0F);
        player.clearStatusEffects();
        player.extinguish();
    }

    public State getState() { return state; }
    public int getTickCount() { return tickCount; }
    public double getTotalReward() { return totalReward; }
    public int getEpisodeCount() { return episodeCount; }

    public record DoneInfo(boolean done, String reason) {}
}
