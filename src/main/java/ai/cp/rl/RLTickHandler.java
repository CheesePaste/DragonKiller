package ai.cp.rl;

import ai.cp.DragonKiller;
import ai.cp.mixin.PlayerManagerAccessor;
import ai.cp.rl.network.Protocol;
import ai.cp.rl.network.SocketServer;
import com.google.gson.JsonObject;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public class RLTickHandler {
    private static final SocketServer socketServer = new SocketServer();
    private static final EpisodeManager episodeManager = new EpisodeManager();
    private static final RewardCalculator rewardCalc = new RewardCalculator();
    private static MinecraftServer server;
    private static BotPlayer botPlayer;
    private static ServerWorld endWorld;
    private static boolean initialized;

    // Watchdog: auto-reset if no TYPE_STEP received for this many ticks
    private static final int WATCHDOG_TIMEOUT = 300; // 15 seconds
    private static int ticksSinceLastStep;

    // Stats tracking
    private static int swingCount;
    private static int hitCount;
    private static double dragonDamageDealt;
    private static double prevDragonHealth;

    // Debug counters
    private static int totalTickCount;
    private static int lastLogTick;

    public static void onServerTick(MinecraftServer srv) {
        if (!initialized) {
            server = srv;
            init(server);
        }

        totalTickCount++;

        // Always try to accept client
        if (!socketServer.isConnected()) {
            socketServer.acceptClient();
        }

        // Check for reset or step messages
        if (socketServer.isConnected()) {
            JsonObject msg = socketServer.tryReceive();
            if (msg != null) {
                handleMessage(msg);
            }
        }

        // Ensure real players are in spectator mode
        ensureRealPlayersSpectate();

        // Tick bot physics every server tick
        if (botPlayer != null) {
            botPlayer.playerTick();
        }

        // Run episode logic
        if (episodeManager.getState() == EpisodeManager.State.RUNNING && botPlayer != null) {
            episodeTick();
        }

        // Watchdog: auto-reset if no step received for too long
        if (initialized && socketServer.isConnected() && botPlayer != null) {
            if (episodeManager.getState() == EpisodeManager.State.RUNNING) {
                ticksSinceLastStep++;
                if (ticksSinceLastStep > WATCHDOG_TIMEOUT) {
                    DragonKiller.LOGGER.warn("[WATCHDOG] No step for {} ticks ({}s), force-ending episode (state=RUNNING)",
                        ticksSinceLastStep, ticksSinceLastStep / 20);
                    forceEndEpisode("watchdog_timeout");
                }
            } else if (episodeManager.getState() == EpisodeManager.State.DONE) {
                ticksSinceLastStep++;
                if (ticksSinceLastStep > WATCHDOG_TIMEOUT) {
                    DragonKiller.LOGGER.warn("[WATCHDOG] Stuck in DONE state for {} ticks, auto-resetting",
                        ticksSinceLastStep);
                    // Client should have sent reset — force it
                    forceReset();
                }
            }
        }

        // Periodic debug log (every 5 seconds)
        if (totalTickCount - lastLogTick >= 100) {
            lastLogTick = totalTickCount;
            logDebugState();
        }
    }

    private static void logDebugState() {
        if (botPlayer == null) return;
        DragonKiller.LOGGER.info("[DEBUG] tick={} state={} epTick={} hp={:.1f} pos=({:.1f},{:.1f},{:.1f}) yaw={:.1f} connected={} msgAge={}",
            totalTickCount,
            episodeManager.getState(),
            episodeManager.getTickCount(),
            botPlayer.getHealth(),
            botPlayer.getX(), botPlayer.getY(), botPlayer.getZ(),
            botPlayer.getYaw(),
            socketServer.isConnected(),
            ticksSinceLastStep);
    }

    private static void init(MinecraftServer srv) {
        endWorld = srv.getWorld(World.END);
        socketServer.start();

        // Create and register the bot player
        createBotPlayer(srv);

        initialized = true;
        DragonKiller.LOGGER.info("RLTickHandler initialized with BotPlayer");
    }

    private static void createBotPlayer(MinecraftServer srv) {
        if (endWorld == null) {
            DragonKiller.LOGGER.error("Cannot create bot: End world is null");
            return;
        }

        // Remove old bot if it exists
        if (botPlayer != null && !botPlayer.isRemoved()) {
            botPlayer.discard();
        }

        botPlayer = new BotPlayer(srv, endWorld);

        // Register with PlayerManager so the server tracks the bot
        PlayerManager pm = srv.getPlayerManager();
        PlayerManagerAccessor accessor = (PlayerManagerAccessor) pm;
        accessor.getPlayers().add(botPlayer);
        accessor.getPlayerMap().put(botPlayer.getUuid(), botPlayer);

        // Register with world for chunk loading and entity tracking
        endWorld.onPlayerConnected(botPlayer);

        // Register with boss bar manager
        srv.getBossBarManager().onPlayerConnect(botPlayer);

        // Give the bot a unique spawn angle for each creation
        botPlayer.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);

        DragonKiller.LOGGER.info("BotPlayer created: {} (UUID: {})", botPlayer.getName().getString(), botPlayer.getUuid());
    }

    private static void ensureRealPlayersSpectate() {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player != botPlayer && player.interactionManager.getGameMode() != GameMode.SPECTATOR) {
                player.changeGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private static void handleMessage(JsonObject msg) {
        String type = Protocol.getType(msg);
        DragonKiller.LOGGER.info("[MSG] Received type={}", type);
        switch (type) {
            case Protocol.TYPE_RESET -> handleReset();
            case Protocol.TYPE_STEP -> handleStep(msg);
            case Protocol.TYPE_CLOSE -> {
                DragonKiller.LOGGER.info("[MSG] Client requested close");
                socketServer.closeClient();
                episodeManager.endEpisode("client_disconnected");
            }
            default -> DragonKiller.LOGGER.warn("[MSG] Unknown message type: {}", type);
        }
    }

    private static void handleReset() {
        // Reset watchdog immediately
        ticksSinceLastStep = 0;

        if (botPlayer == null || botPlayer.isRemoved() || botPlayer.isDead()) {
            if (botPlayer != null && !botPlayer.isRemoved()) {
                botPlayer.discard();
            }
            createBotPlayer(server);
        }
        if (botPlayer == null) return;

        // Force survival mode (dead players may be in spectator)
        botPlayer.changeGameMode(GameMode.SURVIVAL);

        // Teleport to spawn
        botPlayer.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);

        episodeManager.resetPlayer(botPlayer);

        // Give equipment
        giveBotEquipment();

        // Re-teleport after equipment (clear any state)
        botPlayer.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);

        episodeManager.startEpisode();

        ActionParser.reset();
        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double dragonDistance = dragon != null ? botPlayer.distanceTo(dragon) : 100.0;
        rewardCalc.reset(dragonHealth, botPlayer.getHealth(), dragonDistance);
        resetStats(dragonHealth);

        DragonKiller.LOGGER.info("[RESET] Episode {} started, bot at ({:.1f},{:.1f},{:.1f}) hp={:.1f} dragonHp={:.1f}",
            episodeManager.getEpisodeCount() + 1,
            botPlayer.getX(), botPlayer.getY(), botPlayer.getZ(),
            botPlayer.getHealth(), dragonHealth);

        // Send initial observation
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld, 0, 0, 0, 0, 0);
        String message = Protocol.createObsMessage(obs, 0.0, false);
        socketServer.send(message);
    }

    private static void giveBotEquipment() {
        // Diamond sword
        botPlayer.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));

        // Infinite blocks
        botPlayer.getInventory().setStack(1, new ItemStack(Items.END_STONE, 64));

        // Water bucket
        botPlayer.getInventory().setStack(2, new ItemStack(Items.WATER_BUCKET));

        // Shield
        botPlayer.getInventory().setStack(3, new ItemStack(Items.SHIELD));

        // Diamond armor
        botPlayer.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        botPlayer.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
        botPlayer.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
        botPlayer.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));

        // Extra blocks
        for (int i = 4; i < 9; i++) {
            botPlayer.getInventory().setStack(i, new ItemStack(Items.END_STONE, 64));
        }
    }

    private static void handleStep(JsonObject msg) {
        ticksSinceLastStep = 0;

        if (episodeManager.getState() != EpisodeManager.State.RUNNING) {
            DragonKiller.LOGGER.warn("[STEP] Ignored — episode state is {} (not RUNNING)", episodeManager.getState());
            return;
        }
        if (botPlayer == null) return;

        int actionIndex = Protocol.getAction(msg);
        DragonKiller.LOGGER.info("[STEP] action={} epTick={} pos=({:.1f},{:.1f},{:.1f}) hp={:.1f}",
            actionIndex, episodeManager.getTickCount(),
            botPlayer.getX(), botPlayer.getY(), botPlayer.getZ(),
            botPlayer.getHealth());
        ActionParser.execute(actionIndex, botPlayer, endWorld);
    }

    private static void episodeTick() {
        episodeManager.tick();
        ActionParser.tickExecute(botPlayer, endWorld);

        if (ActionParser.needsNewAction()) {
            sendObservation();
        }
    }

    private static void sendObservation() {
        if (botPlayer == null || endWorld == null) return;

        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double playerHealth = botPlayer.getHealth();

        // Check if any enderman is angry nearby
        boolean endermanAngry = endWorld.getEntitiesByClass(
            net.minecraft.entity.mob.EndermanEntity.class,
            botPlayer.getBoundingBox().expand(32.0),
            e -> e.isAngry()
        ).size() > 0;

        double dragonDistance = dragon != null ? botPlayer.distanceTo(dragon) : 100.0;

        // Compute dense reward
        double denseReward = rewardCalc.computeDense(
            dragonHealth, playerHealth, endermanAngry,
            hitCount, swingCount, dragonDistance,
            botPlayer.isSprinting());
        double totalReward = denseReward;

        // Dragon health delta (sparse reward)
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0 && dragon != null && !dragon.isDead()) {
            totalReward += rewardCalc.onDragonHurt();
        }
        prevDragonHealth = dragonHealth;

        episodeManager.addReward(totalReward);

        // Build observation
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld,
            episodeManager.getTickCount(), 0, dragonDamageDealt, swingCount, hitCount);

        // Check done
        EpisodeManager.DoneInfo doneInfo = episodeManager.checkDone(botPlayer, endWorld);

        if (doneInfo.done()) {
            double endReward = 0.0;
            if (doneInfo.reason().equals("dragon_killed")) {
                endReward = rewardCalc.onDragonDeath();
            } else if (doneInfo.reason().equals("player_died")) {
                endReward = rewardCalc.onPlayerDeath();
            }
            totalReward += endReward;
            episodeManager.addReward(endReward);
            DragonKiller.LOGGER.info("[EPISODE] Done reason={} totalReward={:.2f} ticks={}",
                doneInfo.reason(), episodeManager.getTotalReward(), episodeManager.getTickCount());
        }

        String message = Protocol.createObsMessage(obs, totalReward, doneInfo.done());
        socketServer.send(message);

        if (doneInfo.done()) {
            episodeManager.endEpisode(doneInfo.reason());
            DragonKiller.LOGGER.info("[EPISODE] Episode {} ended: {} ({} ticks, total reward: {:.2f})",
                episodeManager.getEpisodeCount(), doneInfo.reason(),
                episodeManager.getTickCount(), episodeManager.getTotalReward());
        }
    }

    private static void forceEndEpisode(String reason) {
        if (episodeManager.getState() != EpisodeManager.State.RUNNING) return;
        episodeManager.endEpisode(reason);
        if (botPlayer != null && endWorld != null) {
            var obs = ObservationBuilder.build(botPlayer, endWorld,
                episodeManager.getTickCount(), 0, dragonDamageDealt, swingCount, hitCount);
            String msg = Protocol.createObsMessage(obs, 0.0, true);
            socketServer.send(msg);
        }
        DragonKiller.LOGGER.info("[WATCHDOG] Force-ended episode: {}", reason);
    }

    private static void forceReset() {
        if (botPlayer != null) {
            // Check if bot is in a bad state
            if (botPlayer.isRemoved() || botPlayer.isDead()) {
                if (botPlayer.isDead() && !botPlayer.isRemoved()) {
                    botPlayer.discard();
                }
                createBotPlayer(server);
            }
        } else {
            createBotPlayer(server);
        }

        if (botPlayer == null || endWorld == null) return;

        botPlayer.changeGameMode(GameMode.SURVIVAL);
        botPlayer.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);
        episodeManager.resetPlayer(botPlayer);
        giveBotEquipment();
        botPlayer.teleport(endWorld, 30.5, 70.0, 30.5, 0.0F, 0.0F);
        episodeManager.startEpisode();
        ActionParser.reset();

        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double dragonDistance = dragon != null ? botPlayer.distanceTo(dragon) : 100.0;
        rewardCalc.reset(dragonHealth, botPlayer.getHealth(), dragonDistance);
        resetStats(dragonHealth);

        ticksSinceLastStep = 0;

        DragonKiller.LOGGER.info("[WATCHDOG] Force-reset episode {}", episodeManager.getEpisodeCount() + 1);

        var obs = ObservationBuilder.build(botPlayer, endWorld, 0, 0, 0, 0, 0);
        socketServer.send(Protocol.createObsMessage(obs, 0.0, false));
    }

    private static void resetStats(double dragonHealth) {
        swingCount = 0;
        hitCount = 0;
        dragonDamageDealt = 0;
        prevDragonHealth = dragonHealth;
    }

    public static SocketServer getSocketServer() { return socketServer; }
    public static EpisodeManager getEpisodeManager() { return episodeManager; }
}
