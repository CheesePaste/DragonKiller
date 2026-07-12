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

public class ActionParser {
    private static int freezeCounter;
    private static boolean observationSent;

    // Continuous movement state
    private static boolean moveForward;
    private static boolean moveBackward;
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


    private static final double MOVE_SPEED = 0.2;
    private static final float TURN_SPEED = 5.0F;

    public static void reset() {
        freezeCounter = 0;
        observationSent = true;
        moveForward = false;
        moveBackward = false;
        sprinting = false;
        jumping = false;
        attackCooldown = 0;
        swingCount = 0;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;
    }

    public static void execute(int actionIndex, ServerPlayerEntity player, ServerWorld world) {
        moveForward = false;
        moveBackward = false;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;

        switch (actionIndex) {
            case 0: break; // noop
            case 1: moveForward = true; break;
            case 2: moveBackward = true; break;
            case 3: player.setYaw(player.getYaw() - TURN_SPEED); break;
            case 4: player.setYaw(player.getYaw() + TURN_SPEED); break;
            case 5: player.setPitch(MathHelper.clamp(player.getPitch() - TURN_SPEED, -90.0F, 90.0F)); break;
            case 6: player.setPitch(MathHelper.clamp(player.getPitch() + TURN_SPEED, -90.0F, 90.0F)); break;
            case 7: performAttack(player, world); break;
            case 8: sprinting = !sprinting; break;
            case 9: jumping = true; break;
        }

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

        if (moveForward) {
            applyForwardVelocity(player, 1);
        } else if (moveBackward) {
            applyForwardVelocity(player, -1);
        }

        if (jumping) {
            if (player.isOnGround()) {
                player.jump();
            }
            jumping = false;
        }

        player.setSprinting(sprinting);

        if (freezeCounter > 0) {
            freezeCounter--;
        }
    }

    public static boolean needsNewAction() {
        return freezeCounter <= 0 && !observationSent;
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

    private static void applyForwardVelocity(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = -MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
        player.setVelocity(dx, player.getVelocity().y, dz);
        player.velocityModified = true;
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

}
