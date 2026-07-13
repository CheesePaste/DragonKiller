package ai.cp.rl;

import ai.cp.config.RLConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import com.google.gson.JsonArray;

public class ActionParser {
    private static int freezeCounter;
    private static boolean observationSent;

    // Continuous movement state
    private static boolean moveForward;
    private static boolean moveBackward;
    private static boolean moveLeft;
    private static boolean moveRight;
    private static boolean sprinting;
    private static boolean jumping;

    // Swing/hit tracking per action cycle
    private static int swingCount;
    private static boolean attackHappenedThisCycle;
    private static boolean wasFullCharge;
    private static boolean wasAirborne;
    private static boolean wasHeadshot;
    private static int lastHitType; // 0=none/miss, 1=body, 2=head
    private static boolean attackChosen;
    private static int totalAttackAttempts;


    /** Base walk speed (vanilla ≈0.215 blocks/tick = 4.317 blocks/sec). */
    private static final double WALK_SPEED = 0.215;
    /** Sprint multiplier (vanilla: +30%). */
    private static final double SPRINT_MULTIPLIER = 1.3;
    /** Backward speed fraction of forward (vanilla: ~0.6). */
    private static final double BACKWARD_MULTIPLIER = 0.6;
    /** Strafe speed fraction of forward (vanilla: ~0.85). */
    private static final double STRAFE_MULTIPLIER = 0.85;
    private static final float TURN_SPEED = 5.0F;

    public static void reset() {
        freezeCounter = 0;
        observationSent = true;
        moveForward = false;
        moveBackward = false;
        moveLeft = false;
        moveRight = false;
        sprinting = false;
        jumping = false;
        swingCount = 0;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;
        attackChosen = false;
        totalAttackAttempts = 0;
    }

    public static void execute(JsonArray actionArray, ServerPlayerEntity player, ServerWorld world) {
        moveForward = false;
        moveBackward = false;
        moveLeft = false;
        moveRight = false;
        sprinting = false;
        jumping = false;
        attackHappenedThisCycle = false;
        wasFullCharge = false;
        wasAirborne = false;
        wasHeadshot = false;
        lastHitType = 0;
        attackChosen = false;

        if (actionArray == null || actionArray.size() < 6) {
            // Default reset if invalid
            freezeCounter = RLConfig.ACTION_REPEAT;
            observationSent = false;
            return;
        }

        float aForward = actionArray.get(0).getAsFloat();
        float aStrafe = actionArray.get(1).getAsFloat();
        float aYaw = actionArray.get(2).getAsFloat();
        float aPitch = actionArray.get(3).getAsFloat();
        float aAttack = actionArray.get(4).getAsFloat();
        float aJump = actionArray.get(5).getAsFloat();

        // 1. Forward / Backward & Sprint
        if (aForward >= 0.6f) {
            moveForward = true;
            sprinting = true;
        } else if (aForward >= 0.2f) {
            moveForward = true;
            sprinting = false;
        } else if (aForward <= -0.2f) {
            moveBackward = true;
            sprinting = false;
        }

        // 2. Strafe Left / Right
        if (aStrafe >= 0.2f) {
            moveRight = true;
        } else if (aStrafe <= -0.2f) {
            moveLeft = true;
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

        freezeCounter = RLConfig.ACTION_REPEAT;
        observationSent = false;
    }



    public static void tickExecute(ServerPlayerEntity player, ServerWorld world) {
        // Always hold the sword in slot 0
        player.getInventory().selectedSlot = 0;

        applyMovement(player);

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
    public static float getCooldownProgress(ServerPlayerEntity player) {
        return player.getAttackCooldownProgress(0.5f);
    }

    private static void applyMovement(ServerPlayerEntity player) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double forward = 0, strafe = 0;

        if (moveForward) forward = 1.0;
        else if (moveBackward) forward = -1.0;

        if (moveLeft) strafe = 1.0;
        else if (moveRight) strafe = -1.0;

        if (forward == 0 && strafe == 0) return;

        // Normalize input vector so diagonal isn't faster than cardinal
        double inputLen = Math.sqrt(forward * forward + strafe * strafe);
        forward /= inputLen;
        strafe /= inputLen;

        // Apply speed penalties AFTER normalization (so they aren't normalized away)
        if (forward < 0) forward *= BACKWARD_MULTIPLIER;  // backward: 60% of forward
        strafe *= STRAFE_MULTIPLIER;                       // strafe: 85% of forward

        double speed = WALK_SPEED;
        if (sprinting) speed *= SPRINT_MULTIPLIER;

        // Compute velocity from yaw
        double vx = forward * -MathHelper.sin(yawRad) + strafe * MathHelper.cos(yawRad);
        double vz = forward * MathHelper.cos(yawRad) + strafe * MathHelper.sin(yawRad);

        // Scale to desired speed
        vx *= speed;
        vz *= speed;

        player.setVelocity(vx, player.getVelocity().y, vz);
        player.velocityModified = true;
    }

    private static void performAttack(ServerPlayerEntity player, ServerWorld world) {
        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null) return;

        swingCount++;

        // Vanilla raycast: what is the player actually looking at?
        double reach = 3.0;
        HitResult hit = player.raycast(reach, 0.0F, false);

        boolean hitDragon = false;
        boolean headshot = false;

        if (hit.getType() == HitResult.Type.ENTITY) {
            Entity entityHit = ((EntityHitResult) hit).getEntity();
            if (entityHit == dragon || entityHit instanceof EnderDragonPart) {
                hitDragon = true;
                headshot = (entityHit == dragon.head);
                player.attack(entityHit);
            }
        }

        attackHappenedThisCycle = hitDragon;
        wasFullCharge = (player.getAttackCooldownProgress(0.5f) >= 0.99f);
        wasAirborne = !player.isOnGround() && player.getVelocity().y < 0;
        wasHeadshot = headshot;
        lastHitType = headshot ? 2 : (hitDragon ? 1 : 0);
    }

}
