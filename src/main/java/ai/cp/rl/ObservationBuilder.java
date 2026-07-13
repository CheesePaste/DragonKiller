package ai.cp.rl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

public class ObservationBuilder {

    /** Reusable search box covering the entire End island. Avoids per-call allocation. */
    private static final Box END_SEARCH_BOX = new Box(-200, 0, -200, 200, 256, 200);

    public static JsonObject build(ServerPlayerEntity player, ServerWorld world, float attackCooldown) {
        JsonObject data = new JsonObject();
        data.add("player", buildPlayer(player));
        data.add("dragon_relative", buildDragonRelative(player, world));
        data.add("terrain", buildTerrain(player, world));
        data.add("raytrace", buildRaytrace(player, world));
        data.add("breath", buildBreath(player, world));
        data.add("stats", buildStats(attackCooldown));
        return data;
    }

    private static JsonObject buildPlayer(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("health", player.getHealth());
        obj.addProperty("on_ground", player.isOnGround());
        Vec3d vel = player.getVelocity();
        JsonArray v = new JsonArray();
        v.add(vel.x); v.add(vel.y); v.add(vel.z);
        obj.add("velocity", v);
        obj.addProperty("center_dx", MathHelper.clamp(player.getX() / 150.0, -1.0, 1.0));
        obj.addProperty("center_dz", MathHelper.clamp(player.getZ() / 150.0, -1.0, 1.0));
        return obj;
    }

    private static JsonObject buildDragonRelative(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        EnderDragonEntity dragon = getDragon(world);
        if (dragon != null && !dragon.isDead()) {
            Vec3d playerPos = player.getEyePos();
            Vec3d dragonPos = dragon.getPos();
            Vec3d toDragon = dragonPos.subtract(playerPos);
            double dx = toDragon.x;
            double dy = toDragon.y;
            double dz = toDragon.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            double distance = playerPos.distanceTo(dragonPos);

            // Yaw that would face the dragon (Minecraft: 0=South, -90=East, 90=West, ±180=North)
            float yawToDragon = (float) MathHelper.atan2(-dx, dz) * MathHelper.DEGREES_PER_RADIAN;
            // Pitch: 0=horiz, negative=up, positive=down
            float pitchToDragon = -(float) MathHelper.atan2(dy, horizDist) * MathHelper.DEGREES_PER_RADIAN;

            // How far off the player's current orientation is
            float yawDelta = yawToDragon - player.getYaw();
            while (yawDelta > 180F) yawDelta -= 360F;
            while (yawDelta < -180F) yawDelta += 360F;

            float pitchDelta = pitchToDragon - player.getPitch();
            while (pitchDelta > 180F) pitchDelta -= 360F;
            while (pitchDelta < -180F) pitchDelta += 360F;

            boolean inView = Math.abs(yawDelta) < 45 && Math.abs(pitchDelta) < 30;

            obj.addProperty("in_view", inView);

            // Relative Cartesian coordinates
            obj.addProperty("dragon_dx", dx);
            obj.addProperty("dragon_dz", dz);

            // Vertical height difference (+ = dragon above player)
            obj.addProperty("dy", dy);

            // Dragon hurt time (invincibility frame 0-10)
            obj.addProperty("dragon_hurt_time", dragon.hurtTime / 10.0);

            // Min distance from player eye to any dragon part's bounding box
            obj.addProperty("hit_dist", minDistanceToDragon(playerPos, dragon));

            // Direction to the closest dragon part's hitbox center
            Vec3d hitCenter = findClosestPartCenter(playerPos, dragon);
            Vec3d toHit = hitCenter.subtract(playerPos);
            double hitDx = toHit.x, hitDy = toHit.y, hitDz = toHit.z;
            double hitHoriz = Math.sqrt(hitDx * hitDx + hitDz * hitDz);

            float yawToHit = (float) MathHelper.atan2(-hitDx, hitDz) * MathHelper.DEGREES_PER_RADIAN;
            float hitYawDelta = yawToHit - player.getYaw();
            while (hitYawDelta > 180F) hitYawDelta -= 360F;
            while (hitYawDelta < -180F) hitYawDelta += 360F;

            float pitchToHit = -(float) MathHelper.atan2(hitDy, hitHoriz) * MathHelper.DEGREES_PER_RADIAN;
            float hitPitchDelta = pitchToHit - player.getPitch();
            while (hitPitchDelta > 180F) hitPitchDelta -= 360F;
            while (hitPitchDelta < -180F) hitPitchDelta += 360F;

            obj.addProperty("hit_yaw_delta", hitYawDelta);
            obj.addProperty("hit_pitch_delta", hitPitchDelta);

            // Direction to the dragon's head specifically (high-value target)
            Vec3d headCenter = dragon.head.getBoundingBox().getCenter();
            Vec3d toHead = headCenter.subtract(playerPos);
            double headDx = toHead.x, headDy = toHead.y, headDz = toHead.z;
            double headHoriz = Math.sqrt(headDx * headDx + headDz * headDz);

            float yawToHead = (float) MathHelper.atan2(-headDx, headDz) * MathHelper.DEGREES_PER_RADIAN;
            float headYawDelta = yawToHead - player.getYaw();
            while (headYawDelta > 180F) headYawDelta -= 360F;
            while (headYawDelta < -180F) headYawDelta += 360F;

            float pitchToHead = -(float) MathHelper.atan2(headDy, headHoriz) * MathHelper.DEGREES_PER_RADIAN;
            float headPitchDelta = pitchToHead - player.getPitch();
            while (headPitchDelta > 180F) headPitchDelta -= 360F;
            while (headPitchDelta < -180F) headPitchDelta += 360F;

            obj.addProperty("head_yaw_delta", headYawDelta);
            obj.addProperty("head_pitch_delta", headPitchDelta);

            // Dragon AI phase (Phase 2: 0=HOLDING_PATTERN … 10=HOVER)
            obj.addProperty("phase", dragon.getPhaseManager().getCurrent().getType().getTypeId());

            // Relative dragon velocity (player-centric frame)
            Vec3d dragonVel = dragon.getVelocity();
            if (distance > 0.01) {
                Vec3d toDragonNorm = toDragon.multiply(1.0 / distance);
                double towardVel = dragonVel.dotProduct(toDragonNorm);
                // Horizontal perpendicular to player→dragon line (positive = right in player's view)
                double hMag = Math.sqrt(toDragonNorm.x * toDragonNorm.x + toDragonNorm.z * toDragonNorm.z);
                if (hMag > 0.001) {
                    double nx = toDragonNorm.x / hMag, nz = toDragonNorm.z / hMag;
                    double lateralVel = dragonVel.x * (-nz) + dragonVel.z * nx;
                    obj.addProperty("toward_vel", towardVel);
                    obj.addProperty("lateral_vel", lateralVel);
                    obj.addProperty("vertical_vel", dragonVel.y);
                } else {
                    obj.addProperty("toward_vel", towardVel);
                    obj.addProperty("lateral_vel", 0.0);
                    obj.addProperty("vertical_vel", dragonVel.y);
                }
            } else {
                obj.addProperty("toward_vel", dragonVel.length());
                obj.addProperty("lateral_vel", 0.0);
                obj.addProperty("vertical_vel", dragonVel.y);
            }
        } else {
            obj.addProperty("in_view", false);
            obj.addProperty("dragon_dx", 0.0);
            obj.addProperty("dragon_dz", 0.0);
            obj.addProperty("dy", 0.0);
            obj.addProperty("dragon_hurt_time", 0.0);
            obj.addProperty("hit_dist", 100.0);
            obj.addProperty("hit_yaw_delta", 0.0);
            obj.addProperty("hit_pitch_delta", 0.0);
            obj.addProperty("head_yaw_delta", 0.0);
            obj.addProperty("head_pitch_delta", 0.0);
            obj.addProperty("phase", 0);
            obj.addProperty("toward_vel", 0.0);
            obj.addProperty("lateral_vel", 0.0);
            obj.addProperty("vertical_vel", 0.0);
        }
        return obj;
    }

    private static JsonObject buildTerrain(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        BlockPos playerPos = player.getBlockPos();
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, playerPos.getX(), playerPos.getZ());
        boolean overVoid = topY <= world.getBottomY();
        double groundDist = overVoid ? 100.0 : Math.max(0.0, playerPos.getY() - topY);
        obj.addProperty("ground_distance", groundDist);
        return obj;
    }

    private static JsonObject buildRaytrace(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        double reach = 64.0;
        HitResult hit = player.raycast(reach, 0.0F, false);
        boolean dragonInCrosshair = false;

        if (hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            dragonInCrosshair = entity instanceof EnderDragonEntity || entity instanceof EnderDragonPart;
        }

        obj.addProperty("dragon_in_crosshair", dragonInCrosshair);
        return obj;
    }

    public static JsonObject buildBreath(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        double nearestDist = 64.0;
        Vec3d nearestPos = null;
        boolean breathWarning = false;

        Box searchBox = player.getBoundingBox().expand(32.0, 16.0, 32.0);

        // 1. Actual breath clouds
        var clouds = world.getEntitiesByClass(AreaEffectCloudEntity.class, searchBox, c -> true);
        for (AreaEffectCloudEntity cloud : clouds) {
            double dist = cloud.distanceTo(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestPos = cloud.getPos();
            }
            if (dist < 12.0) {
                breathWarning = true;
            }
        }

        // 2. Predict where each fireball will land, treat as future cloud
        var fireballs = world.getEntitiesByClass(DragonFireballEntity.class, searchBox, fb -> true);
        for (DragonFireballEntity fb : fireballs) {
            Vec3d impact = predictImpact(fb, world);
            if (impact != null) {
                double dist = player.getPos().distanceTo(impact);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestPos = impact;
                }
                if (dist < 12.0) {
                    breathWarning = true;
                }
            }
        }

        // Direction to nearest threat
        double yawDelta = 0.0;
        if (nearestPos != null && nearestDist < 32.0) {
            Vec3d toThreat = nearestPos.subtract(player.getPos());
            float yawToThreat = (float) MathHelper.atan2(-toThreat.x, toThreat.z) * MathHelper.DEGREES_PER_RADIAN;
            float delta = yawToThreat - player.getYaw();
            while (delta > 180F) delta -= 360F;
            while (delta < -180F) delta += 360F;
            yawDelta = delta / 180.0;
        }

        obj.addProperty("nearest_breath", Math.min(nearestDist / 64.0, 1.0));
        obj.addProperty("breath_warning", breathWarning);
        obj.addProperty("breath_yaw_delta", yawDelta);
        return obj;
    }

    /** Predict where a fireball will hit the ground, treating it as a future breath cloud. */
    private static Vec3d predictImpact(DragonFireballEntity fb, ServerWorld world) {
        Vec3d pos = fb.getPos();
        Vec3d vel = fb.getVelocity();

        // Trace fireball trajectory linearly (fireballs have negligible gravity)
        for (int i = 0; i < 200; i++) {
            pos = pos.add(vel);
            if (pos.y < world.getBottomY()) return null; // lost to void
            BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
            if (world.getBlockState(bp).isSolid()) {
                return pos; // predicted impact point
            }
        }

        return null; // no obstruction in trace range
    }

    private static JsonObject buildStats(float attackCooldown) {
        JsonObject obj = new JsonObject();
        obj.addProperty("attack_cooldown", attackCooldown);
        obj.addProperty("ranged_cooldown", ActionParser.getRangedCooldownProgress());
        obj.addProperty("last_hit_type", ActionParser.getLastHitType());
        return obj;
    }

    /** Euclidean distance from eye to the closest point on a bounding box. */
    private static double distanceToBox(Vec3d eye, Box box) {
        double cx = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double cy = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double cz = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        double dx = eye.x - cx, dy = eye.y - cy, dz = eye.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Minimum distance from player eyes to any part of the dragon's collision body. */
    private static double minDistanceToDragon(Vec3d eyePos, EnderDragonEntity dragon) {
        double minDist = 64.0;
        for (EnderDragonPart part : dragon.getBodyParts()) {
            double d = distanceToBox(eyePos, part.getBoundingBox());
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    /** Center of the closest dragon part's bounding box. Used to compute direction toward the nearest hitbox. */
    private static Vec3d findClosestPartCenter(Vec3d eyePos, EnderDragonEntity dragon) {
        Vec3d closestCenter = dragon.getPos(); // Fallback
        double minDistSq = 9999.0;
        for (EnderDragonPart part : dragon.getBodyParts()) {
            Vec3d center = part.getBoundingBox().getCenter();
            double dSq = eyePos.squaredDistanceTo(center);
            if (dSq < minDistSq) {
                minDistSq = dSq;
                closestCenter = center;
            }
        }
        return closestCenter;
    }

    public static EnderDragonEntity getDragon(ServerWorld world) {
        var dragons = world.getEntitiesByClass(EnderDragonEntity.class,
            END_SEARCH_BOX, e -> true);
        return dragons.isEmpty() ? null : dragons.get(0);
    }

    /** Minimum distance from player eyes to any dragon part's bounding box. */
    public static double getHitDistance(ServerPlayerEntity player, ServerWorld world) {
        EnderDragonEntity dragon = getDragon(world);
        if (dragon == null || dragon.isRemoved()) return 100.0;
        return minDistanceToDragon(player.getEyePos(), dragon);
    }
}
