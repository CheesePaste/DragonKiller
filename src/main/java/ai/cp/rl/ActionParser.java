package ai.cp.rl;

import ai.cp.config.RLConfig;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.particle.ParticleTypes;
import com.google.gson.JsonArray;

public class ActionParser {
    private static int freezeCounter;
    private static boolean observationSent;

    // Continuous movement state
    private static float targetMoveX;
    private static float targetMoveZ;
    private static boolean sprinting;
    private static boolean jumping;

    // Attack cooldown (diamond sword: 1.6 speed → 12 ticks)
    private static int attackCooldown;
    private static final int ATTACK_COOLDOWN_TICKS = 12;

    // Swing/hit tracking per action cycle
    private static int swingCount;
    private static boolean attackHappenedThisCycle;
    private static boolean wasFullCharge;
    private static boolean wasAirborne;
    private static boolean wasHeadshot;
    private static int lastHitType; // 0=none, 1=body, 2=head
    private static boolean attackChosen; // whether action 7 was selected this cycle
    private static int totalAttackAttempts; // per-episode count of action 7 selections

    // Ranged weapon state
    private static int rangedCooldown;
    private static final int RANGED_COOLDOWN_TICKS = 30; // 1.5 seconds
    private static final double RANGED_DAMAGE = 5.0;
    private static boolean rangedMissThisCycle;
    private static boolean rangedHitThisCycle;

    // Shield state
    private static boolean shieldActive;


    /** Base walk speed (vanilla ≈0.215 blocks/tick = 4.317 blocks/sec). */
    private static final double WALK_SPEED = 0.215;
    /** Sprint multiplier (vanilla: +30%). */
    private static final double SPRINT_MULTIPLIER = 1.3;
    /** Backward speed fraction of forward (vanilla: ~0.7). */
    private static final double BACKWARD_MULTIPLIER = 0.7;
    /** Strafe speed fraction of forward (vanilla: ~0.85). */
    private static final double STRAFE_MULTIPLIER = 0.85;
    private static final float TURN_SPEED = 5.0F;

    public static void reset() {
        freezeCounter = 0;
        observationSent = true;
        targetMoveX = 0;
        targetMoveZ = 0;
        sprinting = false;
        jumping = false;
        attackCooldown = 0;
        swingCount = 0;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;
        attackChosen = false;
        totalAttackAttempts = 0;
        rangedCooldown = 0;
        rangedMissThisCycle = false;
        rangedHitThisCycle = false;
        shieldActive = false;
    }

    public static void execute(JsonArray actionArray, ServerPlayerEntity player, ServerWorld world) {
        targetMoveX = 0;
        targetMoveZ = 0;
        sprinting = false;
        jumping = false;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;
        attackChosen = false;
        rangedMissThisCycle = false;
        rangedHitThisCycle = false;

        if (actionArray == null || actionArray.size() < 7) {
            // Default reset if invalid
            freezeCounter = RLConfig.ACTION_REPEAT;
            observationSent = false;
            return;
        }

        float aMoveX = actionArray.get(0).getAsFloat();
        float aMoveZ = actionArray.get(1).getAsFloat();
        float aYaw = actionArray.get(2).getAsFloat();
        float aPitch = actionArray.get(3).getAsFloat();
        float aAttack = actionArray.get(4).getAsFloat();
        float aJump = actionArray.get(5).getAsFloat();
        float aShoot = actionArray.get(6).getAsFloat();

        // 1 & 2. Absolute World Movement (X and Z)
        targetMoveX = aMoveX;
        targetMoveZ = aMoveZ;

        // Auto-sprint if magnitude > 0.8
        if (Math.sqrt(aMoveX * aMoveX + aMoveZ * aMoveZ) > 0.8f) {
            sprinting = true;
        }

        // 3. Yaw (Continuous)
        if (Math.abs(aYaw) > 0.01f) {
            player.setYaw(player.getYaw() + aYaw * RLConfig.MAX_TURN_SPEED);
        }

        // 4. Pitch (Continuous)
        if (Math.abs(aPitch) > 0.01f) {
            player.setPitch(MathHelper.clamp(player.getPitch() + aPitch * RLConfig.MAX_TURN_SPEED, -90.0F, 90.0F));
        }

        // 5. Attack
        if (aAttack > 0.0f) {
            attackChosen = true;
            totalAttackAttempts++;
            performAttack(player, world);
        }

        // 6. Jump
        if (aJump > 0.0f) {
            jumping = true;
        }

        // 7. Shoot (Crossbow)
        if (aShoot > 0.0f) {
            performRangedAttack(player, world);
        }

        // 8. Shield
        float aShield = actionArray.size() > 7 ? actionArray.get(7).getAsFloat() : 0.0f;
        shieldActive = (aShield > 0.0f);

        freezeCounter = RLConfig.ACTION_REPEAT;
        observationSent = false;
    }



    public static void tickExecute(ServerPlayerEntity player, ServerWorld world) {
        // Always hold the sword in slot 0
        player.getInventory().selectedSlot = 0;

        // Decrement attack cooldown
        if (attackCooldown > 0) {
            attackCooldown--;
        }

        // Decrement ranged cooldown
        if (rangedCooldown > 0) {
            rangedCooldown--;
        }

        applyMovement(player);

        if (jumping) {
            if (player.isOnGround()) {
                player.jump();
            }
            jumping = false;
        }

        player.setSprinting(sprinting);

        // Active shield holding
        if (shieldActive) {
            if (!player.isUsingItem()) {
                player.setCurrentHand(net.minecraft.util.Hand.OFF_HAND);
            }
        } else {
            if (player.isUsingItem() && player.getActiveHand() == net.minecraft.util.Hand.OFF_HAND) {
                player.stopUsingItem();
            }
        }

        if (freezeCounter > 0) {
            freezeCounter--;
        }
    }

    public static boolean needsNewAction() {
        return freezeCounter <= 0 && !observationSent;
    }

    public static boolean isWaitingForAction() {
        return freezeCounter <= 0 && observationSent;
    }

    public static void markObservationSent() {
        observationSent = true;
    }

    public static void addFreezeTicks(int ticks) {
        if (freezeCounter < ticks) {
            freezeCounter = ticks;
        }
    }

    public static int getSwingCount() {
        return swingCount;
    }

    /** Whether the most recent attack swing was at full charge (cooldown == 0). */
    public static boolean wasFullCharge() {
        return wasFullCharge;
    }

    /** Whether the most recent attack swing was while airborne (potential crit). */
    public static boolean wasAirborne() {
        return wasAirborne;
    }

    /** Whether the attack action (index 7) was chosen this cycle (regardless of hit). */
    public static boolean wasAttackChosen() {
        return attackChosen;
    }

    /** Per-episode count of attack action selections (includes misses). */
    public static int getTotalAttackAttempts() {
        return totalAttackAttempts;
    }

    /** Whether any attack was performed in the current action cycle. */
    public static boolean didAttackThisCycle() {
        return attackHappenedThisCycle;
    }

    /** Whether the most recent attack was a headshot (hit dragon.head). */
    public static boolean wasHeadshot() {
        return wasHeadshot;
    }

    /** Last attack result: 0=none/miss, 1=body hit, 2=headshot. */
    public static int getLastHitType() {
        return lastHitType;
    }

    /** Normalized cooldown [0, 1] for observation. 1.0 = fully charged. */
    public static float getCooldownProgress() {
        return 1.0f - (float) attackCooldown / ATTACK_COOLDOWN_TICKS;
    }

    private static void applyMovement(ServerPlayerEntity player) {
        if (Math.abs(targetMoveX) < 0.05f && Math.abs(targetMoveZ) < 0.05f) {
            return; // Deadzone
        }

        double vx = targetMoveX;
        double vz = targetMoveZ;

        double inputLen = Math.sqrt(vx * vx + vz * vz);
        if (inputLen > 1.0) {
            vx /= inputLen;
            vz /= inputLen;
        }

        double speed = WALK_SPEED;
        if (sprinting) speed *= SPRINT_MULTIPLIER;

        // Rotate player-local input into world-space velocity.
        // Convention: vz = forward(+) / backward(-), vx = strafe-right(+) / strafe-left(-)
        // Minecraft yaw: 0=South(+Z), 90=West(-X), 180=North(-Z), -90=East(+X)
        // Forward world vector: (-sin(yaw), 0, cos(yaw))
        // Right   world vector: ( cos(yaw), 0, sin(yaw))
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double worldX = -Math.sin(yawRad) * vz + Math.cos(yawRad) * vx;
        double worldZ =  Math.cos(yawRad) * vz + Math.sin(yawRad) * vx;

        Vec3d v = player.getVelocity();
        double horizontalSpeedSq = v.x * v.x + v.z * v.z;

        // Two-layer knockback protection:
        // 1) Momentum check: if horizontal speed is large, the player is being launched (e.g. dragon wing).
        //    Do NOT override with setVelocity — only apply gentle steering via addVelocity.
        // 2) Time check: within 10 ticks of last damage, also avoid overriding (safety net).
        //    10 ticks is long enough for the player to clear the dragon's 8-block wing hitbox.
        int epTick = RLTickHandler.getEpisodeManager().getTickCount();
        int lastDamageTick = RLTickHandler.getLastDamageEpisodeTick();
        boolean recentlyHurt = (epTick - lastDamageTick) <= 10;
        boolean beingLaunched = horizontalSpeedSq > 0.5 * 0.5; // >0.5 blocks/tick = strong knockback

        if (beingLaunched || recentlyHurt) {
            // Apply gentle steering force instead of overriding velocity.
            // This lets the knockback naturally push the player away while allowing
            // minor course corrections (like dodging toward safety).
            double steerForce = 0.02; // similar to vanilla air-control
            player.addVelocity(worldX * steerForce, 0, worldZ * steerForce);
            player.velocityModified = true;
        } else {
            player.setVelocity(worldX * speed, v.y, worldZ * speed);
            player.velocityModified = true;
        }
    }

    private static void performAttack(ServerPlayerEntity player, ServerWorld world) {
        // Respect attack cooldown — no spamming
        if (attackCooldown > 0) return;

        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null) return;

        Entity target = findClosestDragonPart(player, dragon);
        if (target != null) {
            attackHappenedThisCycle = true;
            wasFullCharge = (player.getAttackCooldownProgress(0.5f) >= 0.99f);
            wasAirborne = !player.isOnGround() && player.getVelocity().y < 0;
            wasHeadshot = (target == dragon.head);
            lastHitType = wasHeadshot ? 2 : 1;

            player.attack(target);
            attackCooldown = ATTACK_COOLDOWN_TICKS;
            swingCount++;
        }
    }

    private static Entity findClosestDragonPart(ServerPlayerEntity player, EnderDragonEntity dragon) {
        // Vanilla player attack: raycast from camera along look direction, max 3 blocks
        Vec3d from = player.getCameraPosVec(0.0F);
        Vec3d lookVec = player.getRotationVec(0.0F);
        double maxDist = 3.0;
        Vec3d to = from.add(lookVec.x * maxDist, lookVec.y * maxDist, lookVec.z * maxDist);

        // Block raycast — entity must be in front of any block (vanilla compares block vs entity distance)
        BlockHitResult blockHit = player.getWorld().raycast(
            new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        double blockDistSq = blockHit.getType() == HitResult.Type.MISS
            ? Double.MAX_VALUE : from.squaredDistanceTo(blockHit.getPos());

        // Entity raycast — check all 8 dragon parts (head, neck, body, 3×tail, 2×wing)
        Entity closest = null;
        double closestDistSq = maxDist * maxDist;

        for (EnderDragonPart part : dragon.getBodyParts()) {
            Optional<Vec3d> hitPoint = part.getBoundingBox().raycast(from, to);
            if (hitPoint.isPresent()) {
                double distSq = from.squaredDistanceTo(hitPoint.get());
                if (distSq < closestDistSq && distSq < blockDistSq) {
                    closestDistSq = distSq;
                    closest = part;
                }
            }
        }

        return closest;
    }

    private static void performRangedAttack(ServerPlayerEntity player, ServerWorld world) {
        if (RLConfig.COMBAT_MODE == RLConfig.CombatMode.MELEE_ONLY) return;
        if (rangedCooldown > 0) return;

        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null) return;

        Vec3d eyePos = player.getCameraPosVec(0.0F);
        Vec3d lookVec = player.getRotationVec(0.0F);

        // Simulation parameters (Crossbow initial speed is 3.0 blocks/tick)
        double initialSpeed = 3.0;
        Vec3d velocity = lookVec.multiply(initialSpeed);
        Vec3d arrowPos = eyePos;
        Vec3d dragonVel = dragon.getVelocity();

        boolean hit = false;
        Entity hitPart = null;
        Vec3d hitPos = null;

        // Simulate up to 60 ticks of flight (approx 180 blocks distance)
        for (int tick = 0; tick < 60; tick++) {
            // Apply drag (0.99) and gravity (0.05)
            velocity = velocity.multiply(0.99);
            velocity = new Vec3d(velocity.x, velocity.y - 0.05, velocity.z);
            arrowPos = arrowPos.add(velocity);

            // Estimate dragon's part positions in the future (current + velocity * tick)
            Vec3d offset = dragonVel.multiply(tick);

            for (EnderDragonPart part : dragon.getBodyParts()) {
                Box futureBox = part.getBoundingBox().offset(offset);

                // Check if the simulated arrow point is inside the shifted bounding box
                if (futureBox.contains(arrowPos)) {
                    hit = true;
                    hitPart = part;
                    hitPos = arrowPos;
                    break;
                }
            }

            if (hit) break;

            // Stop if below void height
            if (arrowPos.y < world.getBottomY()) break;
        }

        if (hit && hitPart != null) {
            // Apply damage instantly
            hitPart.damage(world.getDamageSources().playerAttack(player), (float) RANGED_DAMAGE);
            rangedHitThisCycle = true;

            // Play effects
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1.0F, 1.0F);
            spawnShootParticles(world, eyePos, hitPos);
        } else {
            // Miss
            rangedMissThisCycle = true;
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 1.0F, 1.0F);
            spawnShootParticles(world, eyePos, arrowPos);
        }

        rangedCooldown = RANGED_COOLDOWN_TICKS;
    }

    private static void spawnShootParticles(ServerWorld world, Vec3d start, Vec3d end) {
        Vec3d dir = end.subtract(start);
        double dist = dir.length();
        int count = (int) (dist * 2); // 2 particles per block
        for (int i = 0; i < count; i++) {
            double ratio = (double) i / count;
            Vec3d point = start.add(dir.multiply(ratio));
            world.spawnParticles(ParticleTypes.CRIT, point.x, point.y, point.z,
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public static float getRangedCooldownProgress() {
        return (float) rangedCooldown / RANGED_COOLDOWN_TICKS;
    }

    public static boolean wasRangedMiss() {
        return rangedMissThisCycle;
    }

    public static boolean wasRangedHit() {
        return rangedHitThisCycle;
    }

}
