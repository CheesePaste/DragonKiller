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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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
    private static int invincibilityTicks;

    // Shield tracking
    private static int epShieldBlocks;
    private static double epTotalShieldBlockReward;
    private static double tickShieldBlockReward;

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

    // Passive regen: 1 HP per 80 ticks (same as vanilla with full food, 0 saturation)
    private static int regenTimer;

    private static double epRewardDense;       // total from computeDense
    private static double epRewardBreath;      // breath penalty
    private static double epRewardPlayerDamage; // player damage penalty
    private static double epRewardDeath;       // death penalty / dragon kill bonus

    // Player game mode override: 0=spectator(default), 1=creative, 2=survival
    private static final Map<UUID, Integer> playerGameModeOverrides = new HashMap<>();
    private static final Set<UUID> autoSpectators = new HashSet<>();

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
            if (episodeManager.getState() == EpisodeManager.State.RUNNING && !botPlayer.isRemoved()) {
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

                // Auto-spectate logic
                if (target == GameMode.SPECTATOR && autoSpectators.contains(player.getUuid())) {
                    if (botPlayer != null && !botPlayer.isRemoved() && player.getCameraEntity() != botPlayer) {
                        player.setCameraEntity(botPlayer);
                    }
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
                if (ai.cp.config.RLConfig.COMBAT_MODE == ai.cp.config.RLConfig.CombatMode.RANGED_ONLY) {
                    newDragon.getPhaseManager().setPhase(PhaseType.HOVER);
                    newDragon.setPosition(0.0, 85.0, 0.0);
                    DragonKiller.LOGGER.info("[RESET] Phase 2 dragon spawned at (0, 85, 0) in HOVER for target practice");
                } else {
                    if (ai.cp.config.RLConfig.IS_PHASE_2) {
                        newDragon.getPhaseManager().setPhase(PhaseType.HOLDING_PATTERN);
                        newDragon.setPosition(0.0, 128.0, 0.0);
                        DragonKiller.LOGGER.info("[RESET] Phase 2 dragon spawned at (0, 128, 0) in HOLDING_PATTERN");
                    } else {
                        newDragon.getPhaseManager().setPhase(PhaseType.SITTING_SCANNING);
                        newDragon.setPosition(0.0, 85.0, 0.0);
                        DragonKiller.LOGGER.info("[RESET] Phase 1 dragon spawned in SITTING_SCANNING");
                    }
                }
                endWorld.spawnEntity(newDragon);

                // Sync fight manager so it tracks this dragon immediately
                EnderDragonFight fight = endWorld.getEnderDragonFight();
                if (fight != null) {
                    newDragon.setFight(fight);
                    newDragon.setFightOrigin(BlockPos.ORIGIN);
                    ((EnderDragonFightAccessor) fight).setDragonUuid(newDragon.getUuid());
                }
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
        rewardCalc.reset(dragonHealth, centerDistance);
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

        // Shield in offhand
        botPlayer.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));

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
            // PhaseType IDs: 3=LANDING, 4=SITTING_SCANNING, 5=SITTING_ATTACKING,
            // 6=SITTING_FLAMING, 7=TAKEOFF — all counts as "sitting" for reward multiplier
            isDragonSitting = phase == 3 || phase == 4 || phase == 5 || phase == 6 || phase == 7;
        }

        // Compute dragon delta before rewardCalc (it updates prev values internally)
        double dragonDelta = prevDragonHealth - dragonHealth;

        // Player damage penalty
        int epTick = episodeManager.getTickCount();
        double healthLost = prevPlayerHealth - playerHealth;
        double tickPlayerDmgPenalty = 0.0;
        if (healthLost > 0) {
            // Scale penalty by remaining health: (maxHP / currentHP)^2
            // 20 HP → 1x, 10 HP → 4x, 5 HP → 16x, 2 HP → 100x, 1 HP → 400x
            // This teaches the AI that taking damage at low HP is near-fatal.
            double maxHP = 20.0;
            double hpRatio = maxHP / Math.max(playerHealth, 0.5);
            double lowHpMultiplier = hpRatio * hpRatio;
            tickPlayerDmgPenalty = healthLost * RLConfig.REWARD_PLAYER_DAMAGE_PENALTY * lowHpMultiplier;
            epRewardPlayerDamage += tickPlayerDmgPenalty;
            epPlayerDamageTaken += healthLost;
            lastDamageEpisodeTick = epTick;

            broadcastActionBar(String.format(
                "§c⚠ AI Hurt §f-%.1f HP §7(§c%+.1f §7x%.0f§7) §f❤%.0f", healthLost, tickPlayerDmgPenalty, lowHpMultiplier, playerHealth));
            broadcastChat(String.format(
                "§c⚠ AI Hurt §f-%.1f HP §7| §cPenalty: %+.1f §7(x%.0f) §7| §f❤%.0f left", healthLost, tickPlayerDmgPenalty, lowHpMultiplier, playerHealth));
        }
        prevPlayerHealth = playerHealth;

        boolean didAttack = ActionParser.didAttackThisCycle() || ActionParser.wasRangedHit();

        // Build observation early so reward calc can read breath data
        float cooldownProgress = ActionParser.getCooldownProgress();
        JsonObject obs = ObservationBuilder.build(botPlayer, endWorld, cooldownProgress);

        double denseReward = rewardCalc.computeDense(
            dragonHealth, playerHealth, centerDistance,
            isOverVoid, isDragonSitting, didAttack);
        double totalReward = denseReward + tickPlayerDmgPenalty + tickShieldBlockReward;
        tickShieldBlockReward = 0.0;
        epRewardDense += denseReward;

        // Ranged miss penalty
        if (ActionParser.wasRangedMiss()) {
            totalReward += RLConfig.REWARD_RANGED_MISS;
            broadcastActionBar(String.format(
                "§7🏹 Ranged Miss §c%+.1f", RLConfig.REWARD_RANGED_MISS));
            broadcastChat(String.format(
                "§7🏹 Ranged Miss §7| §cPenalty: %+.1f", RLConfig.REWARD_RANGED_MISS));
        }

        // Breath penalty
        double nearestBreathDist = 64.0;
        try {
            JsonObject br = obs.getAsJsonObject("breath");
            if (br != null) {
                nearestBreathDist = br.get("nearest_breath").getAsDouble() * 64.0;
            }
        } catch (Exception ignored) {}
        if (nearestBreathDist < RLConfig.BREATH_PENALTY_RANGE) {
            double breathFactor = 1.0 - (nearestBreathDist / RLConfig.BREATH_PENALTY_RANGE);
            double breathReward = breathFactor * RLConfig.REWARD_BREATH_PENALTY;
            totalReward += breathReward;
            epRewardBreath += breathReward;
        }

        // Track total damage dealt + show action bar on hit
        if (dragonDelta > 0 && dragon != null && !dragon.isDead()) {
            dragonDamageDealt += dragonDelta;
            double dmgReward = dragonDelta * RLConfig.REWARD_DRAGON_DAMAGE;
            if (isDragonSitting) dmgReward *= RLConfig.REWARD_SITTING_MULTIPLIER;
            broadcastActionBar(String.format(
                "§cDragon §f❤%.0f §7(-%.1f) §e+%s", dragonHealth, dragonDelta,
                String.format("%.1f", dmgReward)));
            broadcastChat(String.format("§cDragon ❤%.0f §7(-%.1f) §eReward: +%s",
                dragonHealth, dragonDelta,
                String.format("%.1f", dmgReward)));
        }
        prevDragonHealth = dragonHealth;

        episodeManager.addReward(totalReward);

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
        if (br != null && br.get("breath_warning").getAsBoolean()) epBreathTicks++;
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
            tracker.addProperty("blocked_hits", 0);
            tracker.addProperty("clawback_count", 0);
            tracker.addProperty("kiting_reward", 0.0);
            tracker.addProperty("shield_blocks", epShieldBlocks);
            tracker.addProperty("shield_block_reward", Math.round(epTotalShieldBlockReward * 10.0) / 10.0);
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
            DragonKiller.LOGGER.info("[EP_REWARD] dense={} breath={} playerDmg={} death={} total={}",
                String.format("%.1f", epRewardDense),
                String.format("%.1f", epRewardBreath),
                String.format("%.1f", epRewardPlayerDamage),
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
        if (mode != 0) {
            autoSpectators.remove(playerUuid);
        }
        return switch (mode) {
            case 1 -> "§aCreative";
            case 2 -> "§eSurvival";
            default -> "§7Spectator";
        };
    }

    public static int getPlayerGameMode(UUID playerUuid) {
        return playerGameModeOverrides.getOrDefault(playerUuid, 0);
    }

    public static boolean toggleAutoSpectate(UUID playerUuid) {
        if (autoSpectators.contains(playerUuid)) {
            autoSpectators.remove(playerUuid);
            return false;
        } else {
            autoSpectators.add(playerUuid);
            return true;
        }
    }

    public static boolean isAutoSpectating(UUID playerUuid) {
        return autoSpectators.contains(playerUuid);
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
        epShieldBlocks = 0;
        epTotalShieldBlockReward = 0.0;
        tickShieldBlockReward = 0.0;
        epRewardDense = 0;
        epRewardBreath = 0;
        epRewardPlayerDamage = 0;
        epRewardDeath = 0;
    }

    public static SocketServer getSocketServer() { return socketServer; }
    public static EpisodeManager getEpisodeManager() { return episodeManager; }
    public static int getLastDamageEpisodeTick() { return lastDamageEpisodeTick; }
    public static BotPlayer getBotPlayer() { return botPlayer; }

    public static void onShieldBlock(float amount) {
        epShieldBlocks++;
        double reward = RLConfig.REWARD_SHIELD_BLOCK;
        tickShieldBlockReward += reward;
        epTotalShieldBlockReward += reward;

        broadcastActionBar(String.format(
            "§b🛡 Shield Block! §f%.1f dmg blocked §e+%.1f", amount, reward));
        broadcastChat(String.format(
            "§b🛡 Shield Block! §fBlocked %.1f dmg §7| §eReward: +%.1f §7(Total: %d blocks)",
            amount, reward, epShieldBlocks));
    }
}
