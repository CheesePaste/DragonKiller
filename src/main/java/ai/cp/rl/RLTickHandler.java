package ai.cp.rl;

import ai.cp.DragonKiller;
import ai.cp.rl.network.Protocol;
import ai.cp.rl.network.SocketServer;
import com.google.gson.JsonObject;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

public class RLTickHandler {
    private static final SocketServer socketServer = new SocketServer();
    private static final EpisodeManager episodeManager = new EpisodeManager();
    private static final RewardCalculator rewardCalc = new RewardCalculator();
    private static ServerPlayerEntity currentPlayer;
    private static ServerWorld endWorld;
    private static boolean initialized;

    // Stats tracking
    private static int swingCount;
    private static int hitCount;
    private static double dragonDamageDealt;
    private static double prevDragonHealth;

    public static void onServerTick(MinecraftServer server) {
        if (!initialized) {
            init(server);
        }

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

        // Run episode logic
        if (episodeManager.getState() == EpisodeManager.State.RUNNING && currentPlayer != null) {
            episodeTick();
        }
    }

    private static void init(MinecraftServer server) {
        endWorld = server.getWorld(World.END);
        socketServer.start();
        initialized = true;
        DragonKiller.LOGGER.info("RLTickHandler initialized");
    }

    private static void handleMessage(JsonObject msg) {
        String type = Protocol.getType(msg);
        switch (type) {
            case Protocol.TYPE_RESET -> handleReset();
            case Protocol.TYPE_STEP -> handleStep(msg);
            case Protocol.TYPE_CLOSE -> {
                socketServer.closeClient();
                episodeManager.endEpisode("client_disconnected");
            }
        }
    }

    private static void handleReset() {
        // Find a player in the end world
        if (endWorld == null) return;
        var players = endWorld.getPlayers();
        if (players.isEmpty()) {
            DragonKiller.LOGGER.warn("No players in end world for reset");
            return;
        }
        currentPlayer = (ServerPlayerEntity) players.get(0);
        episodeManager.resetPlayer(currentPlayer);
        episodeManager.startEpisode();

        ActionParser.reset();
        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        rewardCalc.reset(dragonHealth, currentPlayer.getHealth());
        resetStats(dragonHealth);

        // Send initial observation
        JsonObject obs = ObservationBuilder.build(currentPlayer, endWorld, 0, 0, 0, 0, 0);
        String message = Protocol.createObsMessage(obs, 0.0, false);
        socketServer.send(message);
    }

    private static void handleStep(JsonObject msg) {
        if (episodeManager.getState() != EpisodeManager.State.RUNNING) return;
        if (currentPlayer == null) return;

        int actionIndex = Protocol.getAction(msg);
        ActionParser.execute(actionIndex, currentPlayer, endWorld);
    }

    private static void episodeTick() {
        episodeManager.tick();
        ActionParser.tickExecute(currentPlayer, endWorld);

        if (ActionParser.needsNewAction()) {
            sendObservation();
        }
    }

    private static void sendObservation() {
        if (currentPlayer == null || endWorld == null) return;

        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double playerHealth = currentPlayer.getHealth();

        // Check if any enderman is angry nearby
        boolean endermanAngry = endWorld.getEntitiesByClass(
            net.minecraft.entity.mob.EndermanEntity.class,
            currentPlayer.getBoundingBox().expand(32.0),
            e -> e.isAngry()
        ).size() > 0;

        // Compute reward
        double denseReward = rewardCalc.computeDense(dragonHealth, playerHealth, endermanAngry, hitCount, swingCount);
        double totalReward = denseReward;

        // Check for dragon health delta event (sparse reward)
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0 && dragon != null && !dragon.isDead()) {
            totalReward += rewardCalc.onDragonHurt();
        }
        prevDragonHealth = dragonHealth;

        episodeManager.addReward(totalReward);

        // Build observation
        JsonObject obs = ObservationBuilder.build(currentPlayer, endWorld,
            episodeManager.getTickCount(), 0, dragonDamageDealt, swingCount, hitCount);

        // Check done
        EpisodeManager.DoneInfo doneInfo = episodeManager.checkDone(currentPlayer, endWorld);

        if (doneInfo.done()) {
            if (doneInfo.reason().equals("dragon_killed")) {
                totalReward += rewardCalc.onDragonDeath();
            } else if (doneInfo.reason().equals("player_died")) {
                totalReward += rewardCalc.onPlayerDeath();
            }
            episodeManager.addReward(rewardCalc.onDragonDeath());
        }

        String message = Protocol.createObsMessage(obs, totalReward, doneInfo.done());
        socketServer.send(message);

        if (doneInfo.done()) {
            episodeManager.endEpisode(doneInfo.reason());
            // Auto-reset: send a reset request to Python
        }
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
