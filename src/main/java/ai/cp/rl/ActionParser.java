package ai.cp.rl;

import ai.cp.config.RLConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ActionParser {
    private static int freezeCounter;
    private static int stickyAttackCounter;
    private static boolean stickyAttackActive;

    // Continuous movement state — persists until next action changes it
    private static boolean moveForward;
    private static boolean moveBackward;
    private static boolean strafeLeft;
    private static boolean strafeRight;
    private static boolean sprinting;

    private static final double MOVE_SPEED = 0.2;
    private static final float TURN_SPEED = 15.0F;

    public static void reset() {
        freezeCounter = 0;
        stickyAttackCounter = 0;
        stickyAttackActive = false;
        moveForward = false;
        moveBackward = false;
        strafeLeft = false;
        strafeRight = false;
        sprinting = false;
    }

    /**
     * Called once when a new action is received from the RL client.
     * Sets continuous movement flags and fires one-shot actions (jump, turn, use, etc).
     */
    public static void execute(int actionIndex, ServerPlayerEntity player, ServerWorld world) {
        // Reset movement flags to start fresh each action
        moveForward = false;
        moveBackward = false;
        strafeLeft = false;
        strafeRight = false;
        sprinting = false;

        switch (actionIndex) {
            case 0: break; // noop — stops all movement

            // Movement — continuous (apply every tick via tickExecute)
            case 1: moveForward = true; break;
            case 2: moveBackward = true; break;
            case 3: strafeLeft = true; break;
            case 4: strafeRight = true; break;

            // Looking — one-shot (fire once on receipt)
            case 5: player.setYaw(player.getYaw() - TURN_SPEED); break;
            case 6: player.setYaw(player.getYaw() + TURN_SPEED); break;
            case 7: player.setPitch(MathHelper.clamp(player.getPitch() - TURN_SPEED, -90.0F, 90.0F)); break;
            case 8: player.setPitch(MathHelper.clamp(player.getPitch() + TURN_SPEED, -90.0F, 90.0F)); break;

            // Attack — one-shot + sticky follow-through
            case 9:
                performAttack(player, world);
                stickyAttackActive = true;
                stickyAttackCounter = RLConfig.STICKY_ATTACK;
                break;

            // Use item — one-shot
            case 10: useHeldItem(player, world); break;

            // Jump/Sneak/Sprint — one-shot
            case 11: player.jump(); break;
            case 12: player.setSneaking(!player.isSneaking()); break;
            case 13: sprinting = true; break;

            // Combined: movement + attack (movement continuous, attack one-shot + sticky)
            case 14:
                moveForward = true;
                performAttack(player, world);
                stickyAttackActive = true;
                stickyAttackCounter = RLConfig.STICKY_ATTACK;
                break;
            case 15:
                strafeLeft = true;
                performAttack(player, world);
                stickyAttackActive = true;
                stickyAttackCounter = RLConfig.STICKY_ATTACK;
                break;
            case 16:
                strafeRight = true;
                performAttack(player, world);
                stickyAttackActive = true;
                stickyAttackCounter = RLConfig.STICKY_ATTACK;
                break;

            // Slot selection — one-shot
            case 17: player.getInventory().selectedSlot = 0; break;
            case 18: player.getInventory().selectedSlot = 1; break;
            case 19: player.getInventory().selectedSlot = 2; break;
            case 20: player.getInventory().selectedSlot = 3; break;
        }

        freezeCounter = RLConfig.ACTION_REPEAT;
    }

    /**
     * Called EVERY server tick. Applies continuous movement and sticky attack.
     */
    public static void tickExecute(ServerPlayerEntity player, ServerWorld world) {
        // Apply continuous movement every tick (SET velocity, preserve Y for gravity)
        if (moveForward) {
            applyForwardVelocity(player, 1);
        } else if (moveBackward) {
            applyForwardVelocity(player, -1);
        }
        if (strafeLeft) {
            applyStrafeVelocity(player, 1);
        } else if (strafeRight) {
            applyStrafeVelocity(player, -1);
        }

        // Sprinting state
        player.setSprinting(sprinting);

        // Sticky attack — fires each tick during sticky window
        if (stickyAttackActive && stickyAttackCounter > 0) {
            performAttack(player, world);
            stickyAttackCounter--;
            if (stickyAttackCounter == 0) {
                stickyAttackActive = false;
            }
        }

        // Decrement freeze counter
        if (freezeCounter > 0) {
            freezeCounter--;
        }
    }

    public static boolean needsNewAction() {
        return freezeCounter <= 0 && !stickyAttackActive;
    }

    /** Set a minimum freeze duration. Used after reset to prevent observation flood. */
    public static void addFreezeTicks(int ticks) {
        if (freezeCounter < ticks) {
            freezeCounter = ticks;
        }
    }

    private static void applyForwardVelocity(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = -MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
        // Preserve vertical velocity (gravity) while setting horizontal
        player.setVelocity(dx, player.getVelocity().y, dz);
        player.velocityModified = true;
    }

    private static void applyStrafeVelocity(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        player.setVelocity(dx, player.getVelocity().y, dz);
        player.velocityModified = true;
    }

    private static void performAttack(ServerPlayerEntity player, ServerWorld world) {
        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null) return;

        Entity target = findClosestDragonPart(player, dragon);
        if (target != null) {
            player.attack(target);
        }
    }

    private static Entity findClosestDragonPart(ServerPlayerEntity player, EnderDragonEntity dragon) {
        double reachDistance = 6.0;
        EnderDragonPart head = dragon.head;
        if (head != null && head.squaredDistanceTo(player) < reachDistance * reachDistance) {
            Vec3d from = player.getCameraPosVec(0.0F);
            Vec3d to = head.getBoundingBox().getCenter();
            HitResult hit = player.getWorld().raycast(
                new net.minecraft.world.RaycastContext(from, to,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.ENTITY) {
                return ((EntityHitResult) hit).getEntity();
            }
            return head;
        }
        return null;
    }

    private static void useHeldItem(ServerPlayerEntity player, ServerWorld world) {
        var stack = player.getMainHandStack();
        if (stack.getItem() == Items.WATER_BUCKET
            || stack.getItem() == Items.END_STONE
            || stack.getItem() == Items.DIAMOND_SWORD) {
            player.swingHand(Hand.MAIN_HAND);
        } else if (stack.getItem() == Items.SHIELD) {
            player.setCurrentHand(Hand.OFF_HAND);
        } else {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    public static boolean isStickyAttackActive() {
        return stickyAttackActive;
    }
}
