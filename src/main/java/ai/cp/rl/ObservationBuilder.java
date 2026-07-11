package ai.cp.rl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public class ObservationBuilder {

    public static JsonObject build(ServerPlayerEntity player, ServerWorld world, int timeAlive,
                                    double damageDealt, double dragonDamageDealt, int swingCount, int hitCount) {
        JsonObject data = new JsonObject();
        data.add("player", buildPlayer(player));
        data.add("dragon", buildDragon(world));
        data.add("endermen", buildEndermen(player, world));
        data.add("inventory", buildInventory(player));
        data.add("terrain", buildTerrain(player, world));
        data.add("raytrace", buildRaytrace(player));
        data.add("stats", buildStats(timeAlive, damageDealt, dragonDamageDealt, swingCount, hitCount));
        data.addProperty("reduced", true);
        return data;
    }

    private static JsonObject buildPlayer(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        Vec3d pos = player.getPos();
        obj.add("pos", toArray(pos.x, pos.y, pos.z));
        obj.add("rotation", toArray(player.getYaw(), player.getPitch()));
        obj.addProperty("health", player.getHealth());
        Vec3d vel = player.getVelocity();
        obj.add("velocity", toArray(vel.x, vel.y, vel.z));
        obj.addProperty("on_ground", player.isOnGround());
        obj.addProperty("sprinting", player.isSprinting());
        BlockPos below = player.getBlockPos().down();
        obj.addProperty("block_below", player.getWorld().getBlockState(below).getBlock().getTranslationKey());
        return obj;
    }

    private static JsonObject buildDragon(ServerWorld world) {
        JsonObject obj = new JsonObject();
        EnderDragonEntity dragon = getDragon(world);
        if (dragon != null) {
            Vec3d pos = dragon.getPos();
            obj.add("pos", toArray(pos.x, pos.y, pos.z));
            Vec3d vel = dragon.getVelocity();
            obj.add("velocity", toArray(vel.x, vel.y, vel.z));
            Box bbox = dragon.getBoundingBox();
            obj.add("bbox", toArray(bbox.getXLength(), bbox.getYLength()));
            obj.addProperty("health", dragon.getHealth());
            obj.addProperty("phase", dragon.getPhaseManager().getCurrent().getType().toString());
            Vec3d target = new Vec3d(dragon.getX() + dragon.head.getRotationVector().x * 20,
                                     dragon.getY() + dragon.head.getRotationVector().y * 20,
                                     dragon.getZ() + dragon.head.getRotationVector().z * 20);
            obj.add("target", toArray(target.x, target.y, target.z));
            obj.add("looking_at", toArray(target.x, target.y, target.z));
        } else {
            obj.add("pos", toArray(0, 0, 0));
            obj.add("velocity", toArray(0, 0, 0));
            obj.add("bbox", toArray(0, 0));
            obj.addProperty("health", 0.0);
            obj.addProperty("phase", "none");
            obj.add("target", toArray(0, 0, 0));
            obj.add("looking_at", toArray(0, 0, 0));
        }
        return obj;
    }

    private static JsonArray buildEndermen(ServerPlayerEntity player, ServerWorld world) {
        JsonArray arr = new JsonArray();
        Box searchBox = player.getBoundingBox().expand(32.0);
        List<EndermanEntity> endermen = world.getEntitiesByClass(EndermanEntity.class, searchBox, e -> true);
        int max = Math.min(endermen.size(), 8);
        for (int i = 0; i < max; i++) {
            EndermanEntity enderman = endermen.get(i);
            JsonObject obj = new JsonObject();
            Vec3d pos = enderman.getPos();
            obj.add("pos", toArray(pos.x, pos.y, pos.z));
            Vec3d vel = enderman.getVelocity();
            obj.add("velocity", toArray(vel.x, vel.y, vel.z));
            Box bbox = enderman.getBoundingBox();
            obj.add("bbox", toArray(bbox.getXLength(), bbox.getYLength()));
            obj.addProperty("health", enderman.getHealth());
            obj.addProperty("angry", enderman.isAngry());
            obj.addProperty("player_in_sight", enderman.canSee(player));
            obj.addProperty("player_looking_at", isPlayerLookingAt(player, enderman));
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject buildInventory(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        JsonArray hotbar = new JsonArray();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            hotbar.add(stack.isEmpty() ? null : stack.getItem().getTranslationKey());
        }
        obj.add("hotbar", hotbar);
        obj.addProperty("selected_slot", inv.selectedSlot);
        JsonArray armor = new JsonArray();
        for (int i = 0; i < 4; i++) {
            ItemStack stack = inv.armor.get(i);
            armor.add(stack.isEmpty() ? null : stack.getItem().getTranslationKey());
        }
        obj.add("armor", armor);
        return obj;
    }

    private static JsonObject buildTerrain(ServerPlayerEntity player, ServerWorld world) {
        JsonObject obj = new JsonObject();
        int radius = 7;
        int size = radius * 2 + 1;
        JsonArray heightmap = new JsonArray();
        JsonArray surface = new JsonArray();
        BlockPos playerPos = player.getBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = new BlockPos(playerPos.getX() + dx, 0, playerPos.getZ() + dz);
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
                if (topY <= world.getBottomY()) {
                    heightmap.add(-100.0f);
                    surface.add(0);
                } else {
                    heightmap.add((float) topY);
                    int type = getSurfaceType(world, new BlockPos(pos.getX(), topY - 1, pos.getZ()));
                    surface.add(type);
                }
            }
        }
        obj.add("heightmap", heightmap);
        obj.add("surface", surface);
        obj.add("origin", toArray(playerPos.getX(), playerPos.getZ()));
        obj.addProperty("radius", radius);
        return obj;
    }

    private static int getSurfaceType(ServerWorld world, BlockPos pos) {
        if (pos.getY() < world.getBottomY()) return 0;
        String key = world.getBlockState(pos).getBlock().getTranslationKey();
        if (key.contains("end_stone")) return 1;
        if (key.contains("obsidian")) return 2;
        if (key.contains("water")) return 3;
        return 4;
    }

    private static JsonObject buildRaytrace(ServerPlayerEntity player) {
        JsonObject obj = new JsonObject();
        double reachDistance = 64.0;
        HitResult hit = player.raycast(reachDistance, 0.0F, false);
        if (hit.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            Entity entity = entityHit.getEntity();
            obj.addProperty("hit_entity", entity.getType().getTranslationKey());
            obj.add("hit_block", null);
            obj.addProperty("distance", entityHit.getPos().distanceTo(player.getEyePos()));
            Vec3d pos = entityHit.getPos();
            obj.add("pos", toArray(pos.x, pos.y, pos.z));
            obj.addProperty("entity_id", entity.getId());
            obj.add("block_pos", null);
            obj.add("block_side", null);
            obj.add("block_id", null);
        } else if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            obj.addProperty("hit_entity", (String) null);
            obj.addProperty("hit_block", player.getWorld().getBlockState(blockHit.getBlockPos()).getBlock().getTranslationKey());
            obj.addProperty("distance", blockHit.getPos().distanceTo(player.getEyePos()));
            BlockPos bp = blockHit.getBlockPos();
            obj.add("pos", toArray(blockHit.getPos().x, blockHit.getPos().y, blockHit.getPos().z));
            obj.add("entity_id", null);
            obj.add("block_pos", toArray(bp.getX(), bp.getY(), bp.getZ()));
            obj.addProperty("block_side", blockHit.getSide().asString());
            obj.addProperty("block_id", player.getWorld().getBlockState(bp).getBlock().getTranslationKey());
        } else {
            obj.addProperty("hit_entity", (String) null);
            obj.addProperty("hit_block", (String) null);
            obj.addProperty("distance", reachDistance);
            obj.add("pos", toArray(0, 0, 0));
            obj.add("entity_id", null);
            obj.add("block_pos", null);
            obj.add("block_side", null);
            obj.add("block_id", null);
        }
        return obj;
    }

    private static JsonObject buildStats(int timeAlive, double damageDealt, double dragonDamageDealt,
                                          int swingCount, int hitCount) {
        JsonObject obj = new JsonObject();
        obj.addProperty("time_alive", timeAlive);
        obj.addProperty("damage_dealt", damageDealt);
        obj.addProperty("damage_taken", 0.0);
        obj.addProperty("dragon_damage_dealt", dragonDamageDealt);
        obj.addProperty("swing_count", swingCount);
        obj.addProperty("hit_count", hitCount);
        return obj;
    }

    private static boolean isPlayerLookingAt(ServerPlayerEntity player, EndermanEntity enderman) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toEnderman = new Vec3d(enderman.getX() - player.getX(),
                                      enderman.getEyeY() - player.getEyeY(),
                                      enderman.getZ() - player.getZ());
        double dist = toEnderman.length();
        toEnderman = toEnderman.normalize();
        double dot = lookVec.dotProduct(toEnderman);
        return dot > 1.0 - 0.025 / dist && player.canSee(enderman);
    }

    public static EnderDragonEntity getDragon(ServerWorld world) {
        var dragons = world.getEntitiesByClass(EnderDragonEntity.class,
            new Box(-200, 0, -200, 200, 256, 200), e -> true);
        return dragons.isEmpty() ? null : dragons.get(0);
    }

    private static JsonArray toArray(double x, double y, double z) {
        JsonArray arr = new JsonArray();
        arr.add(x);
        arr.add(y);
        arr.add(z);
        return arr;
    }

    private static JsonArray toArray(double a, double b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }
}
