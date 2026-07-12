package ai.cp.rl;

import ai.cp.DragonKiller;
import ai.cp.config.RLConfig;
import ai.cp.mixin.EnderDragonFightAccessor;
import ai.cp.mixin.PlayerManagerAccessor;
import ai.cp.rl.network.Protocol;
import ai.cp.rl.network.SocketServer;
import com.google.gson.JsonObject;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameRules;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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

    // Stats tracking
    private static int swingCount;
    private static int hitCount;
    private static double dragonDamageDealt;
    private static double prevDragonHealth;
    private static int invincibilityTicks;

    private static void broadcastActionBar(String msg) {
        if (server == null) return;
        Text text = Text.literal(msg);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player != botPlayer) {
                player.sendMessage(text, true);  // action bar
            }
        }
    }

    private static void broadcastChat(String msg) {
        if (server == null) return;
        Text text = Text.literal(msg);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player != botPlayer) {
                player.sendMessage(text, false); // chat
            }
        }
    }

    public static void onServerTick(MinecraftServer srv) {
        if (!initialized) {
            server = srv;
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

        // Ensure real players are in spectator mode
        ensureRealPlayersSpectate();

        // Bot physics is NOT ticked by ServerWorld — we must call playerTick explicitly.
        // Order: first apply action velocity (from episodeTick), then tick physics,
        // so velocity takes effect immediately (no 1-tick delay).
        if (botPlayer != null) {
            // Run episode logic (applies movement/jump velocity via ActionParser.tickExecute)
            if (episodeManager.getState() == EpisodeManager.State.RUNNING) {
                episodeTick();

                // Safety net: force survival mode every tick to override MC death→spectator
                if (botPlayer.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                    botPlayer.changeGameMode(GameMode.SURVIVAL);
                }
            }

            // Tick physics AFTER action execution so jump/velocity applies same tick
            botPlayer.playerTick();
        }

        // Drain send queue (non-blocking, best-effort per tick)
        socketServer.drainSendQueue();
    }

    private static void init(MinecraftServer srv) {
        endWorld = srv.getWorld(World.END);
        socketServer.start();

        // Disable natural health regeneration (prevents double-penalty on health)
        srv.getGameRules().get(GameRules.NATURAL_REGENERATION).set(false, srv);

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
        if (botPlayer != null) {
            PlayerManager pm = srv.getPlayerManager();
            PlayerManagerAccessor accessor = (PlayerManagerAccessor) pm;
            accessor.getPlayers().remove(botPlayer);
            accessor.getPlayerMap().remove(botPlayer.getUuid());
            if (!botPlayer.isRemoved()) {
                botPlayer.discard();
            }
            pm.sendToAll(new net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket(
                java.util.Collections.singletonList(botPlayer.getUuid())
            ));
        }

        botPlayer = new BotPlayer(srv, endWorld);

        // Register with PlayerManager so the server tracks the bot
        PlayerManager pm = srv.getPlayerManager();
        PlayerManagerAccessor accessor = (PlayerManagerAccessor) pm;
        accessor.getPlayers().add(botPlayer);
        accessor.getPlayerMap().put(botPlayer.getUuid(), botPlayer);

        // Force survival mode BEFORE broadcasting the player
        botPlayer.changeGameMode(GameMode.SURVIVAL);

        // Tell all connected clients about this fake player so they render its skin/body
        pm.sendToAll(new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(
            java.util.EnumSet.of(
                net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.ADD_PLAYER,
                net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_LISTED,
                net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_GAME_MODE
            ),
            java.util.Collections.singletonList(botPlayer)
        ));

        // Give equipment before world tracking so clients receive it via spawn packet
        giveBotEquipment();

        // Register with world for chunk loading and entity tracking
        endWorld.onPlayerConnected(botPlayer);

        // Register with boss bar manager
        srv.getBossBarManager().onPlayerConnect(botPlayer);

        // Give the bot a unique spawn angle for each creation
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 0.0F, 0.0F);

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

    private static void clearDragonBreath() {
        if (endWorld == null) return;
        // Remove all lingering breath clouds
        var clouds = endWorld.getEntitiesByClass(AreaEffectCloudEntity.class,
            new Box(-200, 0, -200, 200, 256, 200), c -> true);
        for (var cloud : clouds) cloud.discard();
        // Remove all fireballs
        var fireballs = endWorld.getEntitiesByClass(DragonFireballEntity.class,
            new Box(-200, 0, -200, 200, 256, 200), fb -> true);
        for (var fb : fireballs) fb.discard();
    }

    private static void handleReset() {
        // Always discard old bot and create fresh one (avoid state leakage from death)
        if (botPlayer != null && !botPlayer.isRemoved()) {
            botPlayer.discard();
        }
        clearDragonBreath();
        createBotPlayer(server);
        if (botPlayer == null) return;

        // Force survival mode
        botPlayer.changeGameMode(GameMode.SURVIVAL);


        // Phase 2: kill existing dragon and spawn a fresh one, linked to EnderDragonFight
        if (RLConfig.IS_PHASE_2) {
            EnderDragonEntity oldDragon = ObservationBuilder.getDragon(endWorld);
            if (oldDragon != null && !oldDragon.isRemoved()) {
                oldDragon.discard();
            }

            EnderDragonEntity newDragon = EntityType.ENDER_DRAGON.create(endWorld);
            if (newDragon != null) {
                newDragon.getPhaseManager().setPhase(PhaseType.HOLDING_PATTERN);
                newDragon.setPosition(0.0, 128.0, 0.0);
                endWorld.spawnEntity(newDragon);

                // Sync fight manager so it tracks this dragon immediately
                EnderDragonFight fight = endWorld.getEnderDragonFight();
                if (fight != null) {
                    newDragon.setFight(fight);
                    newDragon.setFightOrigin(BlockPos.ORIGIN);
                    ((EnderDragonFightAccessor) fight).setDragonUuid(newDragon.getUuid());
                }
                DragonKiller.LOGGER.info("[RESET] Phase 2 dragon spawned at (0, 128, 0) in HOLDING_PATTERN");
            }
        }

        // 5 seconds of invulnerability to survive loading / initial breath
        invincibilityTicks = 100;
        botPlayer.setInvulnerable(true);

        // Teleport to spawn
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 0.0F, 0.0F);

        episodeManager.resetPlayer(botPlayer);

        // Re-teleport after equipment (clear any state)
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 0.0F, 0.0F);

        episodeManager.startEpisode();

        ActionParser.reset();
        ActionParser.addFreezeTicks(RLConfig.ACTION_REPEAT); // Prevent immediate obs flood
        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double centerDistance = botPlayer.getPos().distanceTo(new Vec3d(0.0, 64.0, 0.0));
        rewardCalc.reset(dragonHealth, botPlayer.getHealth(), centerDistance);
        resetStats(dragonHealth);

        DragonKiller.LOGGER.info("[RESET] Episode {} started, bot at ({},{},{}) hp={} dragonHp={}",
            episodeManager.getEpisodeCount() + 1,
            String.format("%.1f", botPlayer.getX()),
            String.format("%.1f", botPlayer.getY()),
            String.format("%.1f", botPlayer.getZ()),
            String.format("%.1f", botPlayer.getHealth()),
            String.format("%.1f", dragonHealth));

        broadcastActionBar(String.format(
            "§a[EP%d] §fBot spawned  §cDragon §f❤%.0f",
            episodeManager.getEpisodeCount() + 1, dragonHealth));
        broadcastChat(String.format("§a[EP%d] Bot spawned at (%.1f, %.1f, %.1f) §cDragon ❤%.0f",
            episodeManager.getEpisodeCount() + 1,
            botPlayer.getX(), botPlayer.getY(), botPlayer.getZ(), dragonHealth));

        // Send initial observation
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld, 0.0f);
        String message = Protocol.createObsMessage(obs, 0.0, false);
        socketServer.send(message);
    }

    private static void giveBotEquipment() {
        // Diamond sword
        botPlayer.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));

        // Diamond armor
        botPlayer.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        botPlayer.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
        botPlayer.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
        botPlayer.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
    }

    private static void handleStep(JsonObject msg) {
        if (episodeManager.getState() != EpisodeManager.State.RUNNING) {
            DragonKiller.LOGGER.warn("[STEP] Ignored — episode state is {} (not RUNNING)", episodeManager.getState());
            return;
        }
        if (botPlayer == null) return;

        int actionIndex = Protocol.getAction(msg);
        ActionParser.execute(actionIndex, botPlayer, endWorld);
    }

    private static void episodeTick() {
        episodeManager.tick();
        ActionParser.tickExecute(botPlayer, endWorld);

        // Invincibility countdown
        if (invincibilityTicks > 0) {
            invincibilityTicks--;
            if (invincibilityTicks <= 0) {
                botPlayer.setInvulnerable(false);
            }
        }


        // If player died, immediately send observation (don't wait for freeze to expire)
        if (botPlayer.isDead() && episodeManager.getState() == EpisodeManager.State.RUNNING) {
            sendObservation();
            return;
        }

        if (ActionParser.needsNewAction()) {
            sendObservation();
        }
    }

    private static void sendObservation() {
        if (botPlayer == null || endWorld == null) return;

        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double playerHealth = botPlayer.getHealth();

        // Sync if dragon healed (e.g., StaticDragonMixin re-init)
        if (dragon != null && dragonHealth > prevDragonHealth + 10) {
            rewardCalc.syncMaxHealth(dragonHealth);
            prevDragonHealth = dragonHealth;
        }

        // Calculate facing-dragon flag and distance
        boolean facingDragon = false;
        double dragonDistance = 100.0;
        if (dragon != null && !dragon.isDead()) {
            Vec3d playerPos = botPlayer.getEyePos();
            Vec3d dragonPos = dragon.getPos();
            Vec3d toDragon = dragonPos.subtract(playerPos);
            double dx = toDragon.x;
            double dy = toDragon.y;
            double dz = toDragon.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            dragonDistance = playerPos.distanceTo(dragonPos);

            float yawToDragon = (float) MathHelper.atan2(-dx, dz) * MathHelper.DEGREES_PER_RADIAN;
            float pitchToDragon = -(float) MathHelper.atan2(dy, horizDist) * MathHelper.DEGREES_PER_RADIAN;
            float yawDelta = yawToDragon - botPlayer.getYaw();
            while (yawDelta > 180F) yawDelta -= 360F;
            while (yawDelta < -180F) yawDelta += 360F;
            float pitchDelta = pitchToDragon - botPlayer.getPitch();
            while (pitchDelta > 180F) pitchDelta -= 360F;
            while (pitchDelta < -180F) pitchDelta += 360F;

            facingDragon = Math.abs(yawDelta) < 45 && Math.abs(pitchDelta) < 30;
        }

        // Check if over void
        boolean isOverVoid = botPlayer.getBlockPos().getY() < 0;

        // Sync swing count from ActionParser
        swingCount = ActionParser.getSwingCount();

        // Distance from center of End island (strategic position for dragon landing)
        Vec3d center = new Vec3d(0.0, 64.0, 0.0);
        double centerDistance = botPlayer.getPos().distanceTo(center);

        // Compute dense reward
        boolean critHit = ActionParser.didAttackThisCycle() && ActionParser.wasAirborne();
        boolean isDragonSitting = false;
        if (dragon != null) {
            int phase = dragon.getPhaseManager().getCurrent().getType().getTypeId();
            isDragonSitting = phase == 3 || phase == 5 || phase == 6 || phase == 7;
        }
        double denseReward = rewardCalc.computeDense(
            dragonHealth, playerHealth,
            hitCount, swingCount, dragonDistance, centerDistance,
            botPlayer.isSprinting(), facingDragon, isOverVoid,
            critHit, isDragonSitting);
        double totalReward = denseReward;

        // Dragon health delta — apply damage/hit tracking + sparse reward
        double dragonDelta = prevDragonHealth - dragonHealth;
        if (dragonDelta > 0 && dragon != null && !dragon.isDead()) {
            dragonDamageDealt += dragonDelta;
            hitCount++;

            double hitReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
            broadcastActionBar(String.format(
                "§cDragon §f❤%.0f §7(-%.1f) §e+%.1f", dragonHealth, dragonDelta, hitReward));
            broadcastChat(String.format("§cDragon ❤%.0f §7(-%.1f) §eReward: +%.1f",
                dragonHealth, dragonDelta, hitReward));
        }
        prevDragonHealth = dragonHealth;

        episodeManager.addReward(totalReward);

        // Build observation (6 params — our version)
        float cooldownProgress = ActionParser.getCooldownProgress();
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld, cooldownProgress);

        // Check done
        EpisodeManager.DoneInfo doneInfo = episodeManager.checkDone(botPlayer, endWorld);

        if (doneInfo.done()) {
            double endReward = 0.0;
            if (doneInfo.reason().equals("dragon_killed")) {
                endReward = rewardCalc.onDragonDeath();
                DragonKiller.LOGGER.info("[DEATH] Dragon killed! +{} reward", endReward);
            } else if (doneInfo.reason().equals("player_died")) {
                endReward = rewardCalc.onPlayerDeath();
                DragonKiller.LOGGER.info("[DEATH] Bot died! hp={} reason=player_died reward={}",
                    botPlayer.getHealth(), endReward);
            }
            totalReward += endReward;
            episodeManager.addReward(endReward);
            String epColor = doneInfo.reason().equals("dragon_killed") ? "§a" : "§c";
            broadcastActionBar(String.format(
                "§6[EP%d] %s%s §7| §eReward: %+.1f",
                episodeManager.getEpisodeCount() + 1, epColor, doneInfo.reason(), endReward));
            broadcastChat(String.format("§6[EP%d] §f%s §7| §eReward: %+.1f §7| §fTotal: %.1f §7| §fTicks: %d",
                episodeManager.getEpisodeCount() + 1, doneInfo.reason(), endReward,
                episodeManager.getTotalReward(), episodeManager.getTickCount()));
            DragonKiller.LOGGER.info("[EPISODE] Done reason={} totalReward={} ticks={}",
                doneInfo.reason(), String.format("%.2f", episodeManager.getTotalReward()), episodeManager.getTickCount());
        }

        String message = Protocol.createObsMessage(obs, totalReward, doneInfo.done());
        socketServer.send(message);
        ActionParser.markObservationSent();

        if (doneInfo.done()) {
            clearDragonBreath();
            episodeManager.endEpisode(doneInfo.reason());
            DragonKiller.LOGGER.info("[EPISODE] Episode {} ended: {} ({} ticks, total reward: {})",
                episodeManager.getEpisodeCount(), doneInfo.reason(),
                episodeManager.getTickCount(), String.format("%.2f", episodeManager.getTotalReward()));
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
