package ai.cp.rl;

import ai.cp.config.RLConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.player.PlayerInventory;
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
    private static int currentAction;
    private static boolean stickyAttackActive;

    // Movement speed per tick (when action_repeat=5, total step ≈ 1 block)
    private static final double MOVE_SPEED = 0.2;
    private static final float TURN_SPEED = 15.0F;

    public static void reset() {
        freezeCounter = 0;
        stickyAttackCounter = 0;
        currentAction = 0;
        stickyAttackActive = false;
    }

    public static void queueAction(int actionIndex) {
        currentAction = actionIndex;
        freezeCounter = RLConfig.ACTION_REPEAT;
        if (actionIndex == 9 || actionIndex == 14 || actionIndex == 15 || actionIndex == 16) {
            stickyAttackActive = true;
            stickyAttackCounter = RLConfig.STICKY_ATTACK;
        } else {
            stickyAttackActive = false;
        }
    }

    public static void tickExecute(ServerPlayerEntity player, ServerWorld world) {
        if (stickyAttackActive && stickyAttackCounter > 0) {
            performAttack(player, world);
            stickyAttackCounter--;
            if (stickyAttackCounter == 0) {
                stickyAttackActive = false;
            }
        }

        if (freezeCounter > 0) {
            freezeCounter--;
            return;
        }
    }

    public static boolean needsNewAction() {
        return freezeCounter <= 0 && !stickyAttackActive;
    }

    public static void execute(int actionIndex, ServerPlayerEntity player, ServerWorld world) {
        queueAction(actionIndex);

        switch (actionIndex) {
            case 0: break; // noop

            // Movement
            case 1: moveForward(player, 1); break;
            case 2: moveForward(player, -1); break;
            case 3: moveStrafe(player, 1); break;
            case 4: moveStrafe(player, -1); break;

            // Looking
            case 5: turnLeft(player); break;
            case 6: turnRight(player); break;
            case 7: turnUp(player); break;
            case 8: turnDown(player); break;

            // Attack (sticky starts on queue, first attack here)
            case 9: performAttack(player, world); break;

            // Use item
            case 10: useHeldItem(player, world); break;

            // Jump/Sneak/Sprint
            case 11: player.jump(); break;
            case 12: player.setSneaking(!player.isSneaking()); break;
            case 13: player.setSprinting(true); break;

            // Combined: forward + attack
            case 14: moveForward(player, 1); break;
            case 15: moveStrafe(player, 1); break;
            case 16: moveStrafe(player, -1); break;

            // Slot selection
            case 17: player.getInventory().selectedSlot = 0; break;
            case 18: player.getInventory().selectedSlot = 1; break;
            case 19: player.getInventory().selectedSlot = 2; break;
            case 20: player.getInventory().selectedSlot = 3; break;
        }
    }

    private static void moveForward(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = -MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
        player.setVelocity(player.getVelocity().add(dx, 0, dz));
        player.velocityModified = true;
    }

    private static void moveStrafe(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        player.setVelocity(player.getVelocity().add(dx, 0, dz));
        player.velocityModified = true;
    }

    private static void turnLeft(ServerPlayerEntity player) {
        player.setYaw(player.getYaw() - TURN_SPEED);
    }

    private static void turnRight(ServerPlayerEntity player) {
        player.setYaw(player.getYaw() + TURN_SPEED);
    }

    private static void turnUp(ServerPlayerEntity player) {
        player.setPitch(MathHelper.clamp(player.getPitch() - TURN_SPEED, -90.0F, 90.0F));
    }

    private static void turnDown(ServerPlayerEntity player) {
        player.setPitch(MathHelper.clamp(player.getPitch() + TURN_SPEED, -90.0F, 90.0F));
    }

    private static void performAttack(ServerPlayerEntity player, ServerWorld world) {
        EnderDragonEntity dragon = ObservationBuilder.getDragon(world);
        if (dragon == null) return;

        // Find the closest dragon part in front of the player
        Entity target = findClosestDragonPart(player, dragon);
        if (target != null) {
            player.attack(target);
        }
    }

    private static Entity findClosestDragonPart(ServerPlayerEntity player, EnderDragonEntity dragon) {
        double reachDistance = 6.0;
        // Try head first (most common target)
        EnderDragonPart head = dragon.head;
        if (head != null && head.squaredDistanceTo(player) < reachDistance * reachDistance) {
            // Check line of sight
            Vec3d from = player.getCameraPosVec(0.0F);
            Vec3d to = head.getBoundingBox().getCenter();
            HitResult hit = player.getWorld().raycast(
                new net.minecraft.world.RaycastContext(from, to,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.ENTITY) {
                return ((EntityHitResult) hit).getEntity();
            }
        }
        return head; // Fallback to head
    }

    private static void useHeldItem(ServerPlayerEntity player, ServerWorld world) {
        var stack = player.getMainHandStack();
        if (stack.getItem() == Items.WATER_BUCKET) {
            // Place water at feet
            var pos = player.getBlockPos();
            // Water placement is complex — for Phase 1, simulate by using item
            player.swingHand(Hand.MAIN_HAND);
        } else if (stack.getItem() == Items.SHIELD) {
            player.setCurrentHand(Hand.OFF_HAND);
        } else if (stack.getItem() == Items.END_STONE) {
            // Block placement — simplified for Phase 1
            player.swingHand(Hand.MAIN_HAND);
        } else {
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    public static boolean isFreezeActive() {
        return freezeCounter > 0;
    }
}
