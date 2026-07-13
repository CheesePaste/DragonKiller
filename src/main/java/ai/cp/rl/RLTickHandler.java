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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RLTickHandler {
    private static final SocketServer socketServer = new SocketServer();
    private static final EpisodeManager episodeManager = new EpisodeManager();
    private static final RewardCalculator rewardCalc = new RewardCalculator();
    private static MinecraftServer server;
    private static BotPlayer botPlayer;
    private static ServerWorld endWorld;
    private static boolean initialized;

    // Stats tracking
    private static double dragonDamageDealt;
    private static double prevDragonHealth;
    private static double prevHitDist;       // previous observation's hit distance to dragon (for push detection)
    private static double prevSittingDist = 100.0; // previous distance to dragon when sitting (for approach reward)
    private static int invincibilityTicks;

    // Per-episode stats for TensorBoard
    private static double prevPlayerHealth;
    private static int epHeadshotCount;
    private static double epPlayerDamageTaken;
    private static int epBreathTicks;
    private static int epLowHpTicks;
    private static double epMinDragonDistance;
    private static int epBlockedHits;
    private static int epClawbackCount;

    // Anti-trade: damage proximity tracking
    private static int lastDamageEpisodeTick = -100;
    private static double retroactiveClawback = 0.0;
    private static final ArrayDeque<double[]> pendingAttackClawbacks = new ArrayDeque<>();

    // Passive regen: 1 HP per 80 ticks (same as vanilla with full food, 0 saturation)
    private static int regenTimer;

    private static double epRewardDense;       // total from computeDense
    private static double epRewardBreath;      // breath penalty
    private static double epRewardPush;        // collision push penalty
    private static double epRewardClawback;    // retroactive clawback
    private static double epRewardTradeZero;   // anti-trade zeroed damage reward
    private static double epRewardDeath;       // death penalty / dragon kill bonus
    private static double epRewardSurvive;     // survive tick reward

    // Player game mode override: 0=spectator(default), 1=creative, 2=survival
    private static final Map<UUID, Integer> playerGameModeOverrides = new HashMap<>();

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

        // Check for reset or step messages and BLOCK if needed (fixes 500 TPS async bug)
        while (socketServer.isConnected()) {
            boolean needsBlock = false;
            if (episodeManager.getState() != EpisodeManager.State.RUNNING) {
                needsBlock = true;
            } else if (ActionParser.isWaitingForAction()) {
                needsBlock = true;
            }

            if (!needsBlock) {
                break;
            }

            JsonObject msg = socketServer.tryReceive();
            if (msg != null) {
                handleMessage(msg);
            } else {
                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
            }
        }

        // Ensure real players are in spectator mode
        ensureRealPlayersSpectate();

        // BotPlayer.playerTick() drives physics — called explicitly here AFTER setting velocity.
        // BotPlayer.tick() is guarded to skip the ServerWorld entity loop (BotPlayer.java guard flag).
        if (botPlayer != null) {
            // Run episode logic (applies movement/jump velocity via ActionParser.tickExecute)
            if (episodeManager.getState() == EpisodeManager.State.RUNNING) {
                episodeTick();

                // Safety net: force survival mode every tick to override MC death→spectator
                if (botPlayer.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                    botPlayer.changeGameMode(GameMode.SURVIVAL);
                }

                // Tick physics AFTER action execution so jump/velocity applies same tick
                botPlayer.playerTick();
            }
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

        // Face the bot toward center (0, 0) so it doesn't need a 180° turn
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 180.0F, 0.0F);

        DragonKiller.LOGGER.info("BotPlayer created: {} (UUID: {})", botPlayer.getName().getString(), botPlayer.getUuid());
    }

    private static void ensureRealPlayersSpectate() {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player != botPlayer) {
                int mode = playerGameModeOverrides.getOrDefault(player.getUuid(), 0);
                GameMode target = switch (mode) {
                    case 1 -> GameMode.CREATIVE;
                    case 2 -> GameMode.SURVIVAL;
                    default -> GameMode.SPECTATOR;
                };
                if (player.interactionManager.getGameMode() != target) {
                    player.changeGameMode(target);
                }
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


        // Phase 2: kill ALL existing dragons and spawn a single fresh one
        if (RLConfig.IS_PHASE_2) {
            var allDragons = endWorld.getEntitiesByClass(EnderDragonEntity.class,
                new Box(-200, 0, -200, 200, 256, 200), e -> true);
            for (EnderDragonEntity oldDragon : allDragons) {
                if (oldDragon != null && !oldDragon.isRemoved()) {
                    oldDragon.discard();
                }
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

        // Teleport to spawn (facing center — yaw=180 = North toward (0,0))
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 180.0F, 0.0F);

        episodeManager.resetPlayer(botPlayer);

        // Re-teleport after equipment (clear any state)
        botPlayer.teleport(endWorld, 0.0, 65.0, 43.0, 180.0F, 0.0F);

        episodeManager.startEpisode();

        ActionParser.reset();
        ActionParser.addFreezeTicks(RLConfig.ACTION_REPEAT); // Prevent immediate obs flood
        EnderDragonEntity dragon = ObservationBuilder.getDragon(endWorld);
        double dragonHealth = dragon != null ? dragon.getHealth() : 0;
        double centerDistance = botPlayer.getPos().distanceTo(new Vec3d(0.0, 64.0, 0.0));
        rewardCalc.reset(dragonHealth);
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
        ActionParser.markObservationSent();
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

        com.google.gson.JsonArray actionArray = Protocol.getActionArray(msg);
        ActionParser.execute(actionArray, botPlayer, endWorld);
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

        // Passive health regen: 1 HP per 80 ticks
        regenTimer++;
        if (regenTimer >= 80) {
            regenTimer = 0;
            if (botPlayer.getHealth() < botPlayer.getMaxHealth()) {
                botPlayer.heal(1.0f);
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
        double dragonDistance = 100.0;
        if (dragon != null && !dragon.isDead()) {
            Vec3d playerPos = botPlayer.getEyePos();
            Vec3d dragonPos = dragon.getPos();
            dragonDistance = playerPos.distanceTo(dragonPos);
            epMinDragonDistance = Math.min(epMinDragonDistance, dragonDistance);
        }

        // Check if over void
        boolean isOverVoid = botPlayer.getBlockPos().getY() < 0;

        // Distance from center of End island (strategic position for dragon landing)
        Vec3d center = new Vec3d(0.0, 64.0, 0.0);
        double centerDistance = botPlayer.getPos().distanceTo(center);

        // Compute dense reward
        boolean isDragonSitting = false;
        if (dragon != null) {
            int phase = dragon.getPhaseManager().getCurrent().getType().getTypeId();
            isDragonSitting = phase == 3 || phase == 5 || phase == 6 || phase == 7;
        }

        // Compute dragon delta before rewardCalc (it updates prev values internally)
        double dragonDelta = prevDragonHealth - dragonHealth;

        // ── Anti-trade: zero damage reward if recently damaged ──
        int epTick = episodeManager.getTickCount();
        double healthLost = prevPlayerHealth - playerHealth;
        if (healthLost > 0) {
            lastDamageEpisodeTick = epTick;
            epPlayerDamageTaken += healthLost;
            // Retroactive clawback: attack within ANTI_TRADE_WINDOW of taking damage = trade
            while (!pendingAttackClawbacks.isEmpty()) {
                double[] entry = pendingAttackClawbacks.peekFirst();
                int atkTick = (int) entry[0];
                if (epTick - atkTick <= RLConfig.ANTI_TRADE_WINDOW_TICKS) {
                    retroactiveClawback += entry[1];
                    pendingAttackClawbacks.pollFirst();
                } else break;
            }
            // Clean entries older than ANTI_TRADE_WINDOW ticks
            while (!pendingAttackClawbacks.isEmpty()) {
                if (epTick - (int) pendingAttackClawbacks.peekFirst()[0] > RLConfig.ANTI_TRADE_WINDOW_TICKS)
                    pendingAttackClawbacks.pollFirst();
                else break;
            }
        }
        prevPlayerHealth = playerHealth;

        boolean didAttack = ActionParser.didAttackThisCycle();
        double hitDist = ObservationBuilder.getHitDistance(botPlayer, endWorld);

        // Build observation early so reward calc can read breath data
        float cooldownProgress = ActionParser.getCooldownProgress(botPlayer);
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld, cooldownProgress);

        double denseReward = rewardCalc.computeDense(
            dragonHealth, playerHealth, centerDistance,
            isOverVoid, isDragonSitting, didAttack);
        double totalReward = denseReward;
        epRewardDense += denseReward;

        // Survive tick reward (replaces time penalty)
        totalReward += RLConfig.REWARD_SURVIVE_TICK;
        epRewardSurvive += RLConfig.REWARD_SURVIVE_TICK;

        // Anti-trade: only zero/queue damage reward when bot actually attacked
        boolean hitZeroed = false;
        boolean hadClawback = false;
        if (didAttack && dragonDelta > 0) {
            if (epTick - lastDamageEpisodeTick <= RLConfig.ANTI_TRADE_WINDOW_TICKS) {
                double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
                if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
                totalReward -= dmgReward;
                epRewardTradeZero += dmgReward;
                hitZeroed = true;
                epBlockedHits++;
            } else {
                double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
                if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
                pendingAttackClawbacks.add(new double[]{epTick, dmgReward});
            }
        }

        // Apply retroactive clawback from earlier damage-trading hits
        if (retroactiveClawback != 0) {
            totalReward += retroactiveClawback;
            epRewardClawback += retroactiveClawback;
            hadClawback = true;
            epClawbackCount++;
            retroactiveClawback = 0;
        }

// Collision push penalty
        boolean wasPushed = prevHitDist < RLConfig.COLLISION_PENALTY_RANGE && hitDist > prevHitDist + 0.3;
        if (wasPushed) {
            totalReward += RLConfig.REWARD_COLLISION_PENALTY;
            epRewardPush += RLConfig.REWARD_COLLISION_PENALTY;
            DragonKiller.LOGGER.info("[PUSH] tick={} dist {}->{} penalty={}", epTick, String.format("%.2f", prevHitDist), String.format("%.2f", hitDist), String.format("%.1f", RLConfig.REWARD_COLLISION_PENALTY));
            broadcastActionBar(String.format("§7[PUSH] -%.1f", Math.abs(RLConfig.REWARD_COLLISION_PENALTY)));
        }

        // Sitting dragon rewards: approach/retreat + in-range bonus
        if (isDragonSitting && dragon != null && !dragon.isDead()) {
            double distDelta = prevSittingDist - dragonDistance; // positive = getting closer
            totalReward += distDelta * RLConfig.REWARD_SITTING_APPROACH;

            if (hitDist < RLConfig.SITTING_ATTACK_RANGE) {
                totalReward += RLConfig.REWARD_SITTING_RANGE_REWARD;
            }

            prevSittingDist = dragonDistance;
        } else {
            prevSittingDist = 100.0;
        }

        // Track total damage dealt + show action bar on hit
        if (dragonDelta > 0 && dragon != null && !dragon.isDead()) {
            dragonDamageDealt += dragonDelta;
            double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
            if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
            String tag = hitZeroed ? " §c[BLOCKED]" : (hadClawback ? " §6[CLAWBACK]" : "");
            broadcastActionBar(String.format(
                "§cDragon §f❤%.0f §7(-%.1f) §e%s%s", dragonHealth, dragonDelta,
                hitZeroed ? "+0.0" : String.format("+%.1f", dmgReward), tag));
            broadcastChat(String.format("§cDragon ❤%.0f §7(-%.1f) §eReward: %s%s",
                dragonHealth, dragonDelta,
                hitZeroed ? "+0.0" : String.format("+%.1f", dmgReward), tag));
        }
        prevDragonHealth = dragonHealth;

        episodeManager.addReward(totalReward);
        prevHitDist = hitDist;

        // ── Per-episode stats tracking ──────────────────────────────────────
        if (ActionParser.didAttackThisCycle()) {
            if (ActionParser.wasHeadshot()) epHeadshotCount++;
        }
        // Note: healthLost / epPlayerDamageTaken / prevPlayerHealth handled above

        if (dragon != null && !dragon.isDead()) {
            JsonObject dr = obs.getAsJsonObject("dragon_relative");
            JsonObject rt = obs.getAsJsonObject("raytrace");
        }
        JsonObject br = obs.getAsJsonObject("breath");
        if (br.get("breath_warning").getAsBoolean()) epBreathTicks++;
        if (playerHealth < 5.0) epLowHpTicks++;

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
            epRewardDeath += endReward;
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

            // Add tracker stats for TensorBoard
            JsonObject tracker = new JsonObject();
            tracker.addProperty("end_reason", doneInfo.reason());
            tracker.addProperty("attack_attempts", ActionParser.getTotalAttackAttempts());
            tracker.addProperty("hit_count", ActionParser.getSwingCount());
            tracker.addProperty("headshot_count", epHeadshotCount);
            tracker.addProperty("damage_dealt", Math.round(dragonDamageDealt * 10.0) / 10.0);
            tracker.addProperty("player_damage_taken", Math.round(epPlayerDamageTaken * 10.0) / 10.0);
            tracker.addProperty("breath_ticks", epBreathTicks);
            tracker.addProperty("low_hp_ticks", epLowHpTicks);
            tracker.addProperty("min_dragon_distance", Math.round(epMinDragonDistance * 10.0) / 10.0);
            tracker.addProperty("blocked_hits", epBlockedHits);
            tracker.addProperty("clawback_count", epClawbackCount);
            obs.add("tracker", tracker);
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
            DragonKiller.LOGGER.info("[EP_REWARD] survive={} dense={} breath={} push={} trade0={} claw={} death={} total={}",
                String.format("%.1f", epRewardSurvive),
                String.format("%.1f", epRewardDense),
                String.format("%.1f", epRewardBreath), String.format("%.1f", epRewardPush),
                String.format("%.1f", epRewardTradeZero), String.format("%.1f", epRewardClawback),
                String.format("%.1f", epRewardDeath), String.format("%.1f", episodeManager.getTotalReward()));
        }
    }

    /** Set a real player's game mode override. Accepts: spectator/creative/survival, or 0/1/2. */
    public static String setPlayerGameMode(UUID playerUuid, String modeName) {
        int mode = switch (modeName.toLowerCase()) {
            case "creative", "c", "1" -> 1;
            case "survival", "s", "2" -> 2;
            default -> 0;
        };
        playerGameModeOverrides.put(playerUuid, mode);
        return switch (mode) {
            case 1 -> "§aCreative";
            case 2 -> "§eSurvival";
            default -> "§7Spectator";
        };
    }

    public static int getPlayerGameMode(UUID playerUuid) {
        return playerGameModeOverrides.getOrDefault(playerUuid, 0);
    }

    private static void resetStats(double dragonHealth) {
        dragonDamageDealt = 0;
        prevDragonHealth = dragonHealth;
        prevPlayerHealth = botPlayer != null ? botPlayer.getHealth() : 20.0;
        epHeadshotCount = 0;
        epPlayerDamageTaken = 0;
        epBreathTicks = 0;
        epLowHpTicks = 0;
        epMinDragonDistance = 100.0;
        epBlockedHits = 0;
        epClawbackCount = 0;
        lastDamageEpisodeTick = -100;
        regenTimer = 0;
        retroactiveClawback = 0;
        prevHitDist = 100.0;
        prevSittingDist = 100.0;
        epRewardSurvive = 0;
        epRewardDense = 0;
        epRewardBreath = 0;
        epRewardPush = 0;
        epRewardClawback = 0;
        epRewardTradeZero = 0;
        epRewardDeath = 0;
        pendingAttackClawbacks.clear();
    }

    public static SocketServer getSocketServer() { return socketServer; }
    public static EpisodeManager getEpisodeManager() { return episodeManager; }
}
