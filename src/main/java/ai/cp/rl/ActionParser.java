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
import net.minecraft.util.math.Vec3d;

public class ActionParser {
    private static int freezeCounter;
    private static int stickyAttackCounter;
    private static boolean stickyAttackActive;
    private static boolean observationSent;

    // Continuous movement state — persists until next action changes it
    private static boolean moveForward;
    private static boolean moveBackward;
    private static boolean sprinting;

    private static final double MOVE_SPEED = 0.2;
    private static final float TURN_SPEED = 15.0F;

    public static void reset() {
        freezeCounter = 0;
        stickyAttackCounter = 0;
        stickyAttackActive = false;
        observationSent = true;
        moveForward = false;
        moveBackward = false;
        sprinting = false;
    }

    public static void execute(int actionIndex, ServerPlayerEntity player, ServerWorld world) {
        moveForward = false;
        moveBackward = false;
        sprinting = false;

        switch (actionIndex) {
            case 0: break; // noop
            case 1: moveForward = true; break;
            case 2: moveBackward = true; break;
            case 3: player.setYaw(player.getYaw() - TURN_SPEED); break;
            case 4: player.setYaw(player.getYaw() + TURN_SPEED); break;
            case 5: player.setPitch(MathHelper.clamp(player.getPitch() - TURN_SPEED, -90.0F, 90.0F)); break;
            case 6: player.setPitch(MathHelper.clamp(player.getPitch() + TURN_SPEED, -90.0F, 90.0F)); break;
            case 7:
                performAttack(player, world);
                stickyAttackActive = true;
                stickyAttackCounter = RLConfig.STICKY_ATTACK;
                break;
            case 8: sprinting = true; break;
        }

        freezeCounter = RLConfig.ACTION_REPEAT;
        observationSent = false;
    }

    public static void tickExecute(ServerPlayerEntity player, ServerWorld world) {
        // Always hold the sword in slot 0
        player.getInventory().selectedSlot = 0;

        if (moveForward) {
            applyForwardVelocity(player, 1);
        } else if (moveBackward) {
            applyForwardVelocity(player, -1);
        }

        player.setSprinting(sprinting);

        if (stickyAttackActive && stickyAttackCounter > 0) {
            performAttack(player, world);
            stickyAttackCounter--;
            if (stickyAttackCounter == 0) {
                stickyAttackActive = false;
            }
        }

        if (freezeCounter > 0) {
            freezeCounter--;
        }
    }

    public static boolean needsNewAction() {
        return freezeCounter <= 0 && !stickyAttackActive && !observationSent;
    }

    public static void markObservationSent() {
        observationSent = true;
    }

    public static void addFreezeTicks(int ticks) {
        if (freezeCounter < ticks) {
            freezeCounter = ticks;
        }
    }

    private static void applyForwardVelocity(ServerPlayerEntity player, int direction) {
        float yawRad = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double dx = -MathHelper.sin(yawRad) * MOVE_SPEED * direction;
        double dz = MathHelper.cos(yawRad) * MOVE_SPEED * direction;
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

    public static boolean isStickyAttackActive() {
        return stickyAttackActive;
    }
}
