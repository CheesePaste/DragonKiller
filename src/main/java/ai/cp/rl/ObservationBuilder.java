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

    public static JsonObject build(ServerPlayerEntity player, ServerWorld world, int timeAlive,
                                    double dragonDamageDealt, int hitCount, float attackCooldown) {
        JsonObject data = new JsonObject();
        data.add("player", buildPlayer(player));
        data.add("dragon_relative", buildDragonRelative(player, world));
        data.add("terrain", buildTerrain(player, world));
        data.add("inventory", buildInventory(player));
        data.add("raytrace", buildRaytrace(player, world));
        data.add("breath", buildBreath(player, world));
        data.add("stats", buildStats(timeAlive, dragonDamageDealt, hitCount, attackCooldown));
        return data;
    }

    private static JsonObject buildPlayer(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("health", player.getHealth());
        obj.addProperty("on_ground", player.isOnGround());
        obj.addProperty("sprinting", player.isSprinting());
        Vec3d vel = player.getVelocity();
        JsonArray v = new JsonArray();
        v.add(vel.x); v.add(vel.y); v.add(vel.z);
        obj.add("velocity", v);
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

            obj.addProperty("yaw", yawToDragon);
            obj.addProperty("pitch", pitchToDragon);
            obj.addProperty("yaw_delta", yawDelta);
            obj.addProperty("pitch_delta", pitchDelta);
            obj.addProperty("distance", distance);
            obj.addProperty("in_view", inView);
            obj.addProperty("health", dragon.getHealth());
            obj.addProperty("alive", true);

            // Dragon AI phase (Phase 2: 0=HOLDING_PATTERN … 10=HOVER)
            obj.addProperty("phase", dragon.getPhaseManager().getCurrent().getType().getTypeId());

            // Dragon velocity
            Vec3d dragonVel = dragon.getVelocity();
            JsonArray dv = new JsonArray();
            dv.add(dragonVel.x); dv.add(dragonVel.y); dv.add(dragonVel.z);
            obj.add("velocity", dv);
        } else {
            obj.addProperty("yaw", 0.0);
            obj.addProperty("pitch", 0.0);
            obj.addProperty("yaw_delta", 0.0);
            obj.addProperty("pitch_delta", 0.0);
            obj.addProperty("distance", 100.0);
            obj.addProperty("in_view", false);
            obj.addProperty("health", 0.0);
            obj.addProperty("alive", false);
            obj.addProperty("phase", 0);
            JsonArray dv = new JsonArray();
            dv.add(0.0); dv.add(0.0); dv.add(0.0);
            obj.add("velocity", dv);
        }
        return obj;
    }

    private static JsonObject buildTerrain(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        BlockPos playerPos = player.getBlockPos();
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, playerPos.getX(), playerPos.getZ());
        boolean overVoid = topY <= world.getBottomY();
        double groundDist = overVoid ? 100.0 : playerPos.getY() - topY;
        obj.addProperty("ground_distance", groundDist);
        obj.addProperty("is_over_void", overVoid);
        return obj;
    }

    private static JsonObject buildInventory(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        boolean hasSword = player.getInventory().getStack(0).getTranslationKey().contains("diamond_sword");
        boolean hasArmor = !player.getInventory().armor.get(3).isEmpty();
        obj.addProperty("has_sword", hasSword);
        obj.addProperty("has_armor", hasArmor);
        return obj;
    }

    private static JsonObject buildRaytrace(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        double reach = 64.0;
        HitResult hit = player.raycast(reach, 0.0F, false);
        boolean dragonInCrosshair = false;
        int hitType = 0; // 0=none, 1=block, 2=dragon
        double distance = reach;

        if (hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            distance = entity.getPos().distanceTo(player.getEyePos());
            if (entity instanceof EnderDragonEntity || entity instanceof EnderDragonPart) {
                dragonInCrosshair = true;
                hitType = 2;
            } else {
                hitType = 2; // non-dragon entity (unlikely in the End)
            }
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            hitType = 1;
            distance = hit.getPos().distanceTo(player.getEyePos());
        }

        obj.addProperty("dragon_in_crosshair", dragonInCrosshair);
        obj.addProperty("distance", distance);
        obj.addProperty("hit_type", hitType);
        return obj;
    }

    public static JsonObject buildBreath(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        double nearestBreath = 64.0;
        boolean breathWarning = false;

        Box searchBox = player.getBoundingBox().expand(32.0, 16.0, 32.0);

        // Scan for lingering dragon breath clouds
        var clouds = world.getEntitiesByClass(AreaEffectCloudEntity.class, searchBox, c -> true);
        for (AreaEffectCloudEntity cloud : clouds) {
            double dist = cloud.distanceTo(player);
            if (dist < nearestBreath) {
                nearestBreath = dist;
            }
            if (dist < 12.0) {
                breathWarning = true;
            }
        }

        // Scan for incoming dragon fireballs
        var fireballs = world.getEntitiesByClass(DragonFireballEntity.class, searchBox, fb -> true);
        for (DragonFireballEntity fb : fireballs) {
            double dist = fb.distanceTo(player);
            if (dist < nearestBreath) {
                nearestBreath = dist;
            }
            if (dist < 12.0) {
                breathWarning = true;
            }
        }

        obj.addProperty("nearest_breath", Math.min(nearestBreath / 64.0, 1.0));
        obj.addProperty("breath_warning", breathWarning);
        return obj;
    }

    private static JsonObject buildStats(int timeAlive, double dragonDamageDealt, int hitCount, float attackCooldown) {
        JsonObject obj = new JsonObject();
        obj.addProperty("time_alive", timeAlive);
        obj.addProperty("dragon_damage_dealt", dragonDamageDealt);
        obj.addProperty("hit_count", hitCount);
        obj.addProperty("attack_cooldown", attackCooldown);
        return obj;
    }

    public static EnderDragonEntity getDragon(ServerWorld world) {
        var dragons = world.getEntitiesByClass(EnderDragonEntity.class,
            new Box(-200, 0, -200, 200, 256, 200), e -> true);
        return dragons.isEmpty() ? null : dragons.get(0);
    }
}
