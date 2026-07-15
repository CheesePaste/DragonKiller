package ai.cp.mixin;

import ai.cp.config.RLConfig;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.feature.EndPortalFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonEntity.class)
public class StaticDragonMixin {
    @Shadow private EnderDragonFight fight;

    @Unique
    private boolean dragonInitialized = false;
    @Unique
    private BlockPos perchPos = null;

    // ── MELEE_ONLY mode: mostly grounded with brief airborne windows ──
    @Unique
    private static final int MELEE_PERCH_TICKS = 600;     // 30 seconds grounded (melee window)
    @Unique
    private static final int MELEE_AIRBORNE_TICKS = 200;   // 10 seconds airborne (ranged practice)
    @Unique
    private static final double FLY_HEIGHT = 85.0;          // Y position when airborne
    @Unique
    private int cycleTimer = 0;
    @Unique
    private boolean airborne = false;

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void lockDragonPosition(CallbackInfo ci) {
        // MIXED mode: let dragon AI run freely (normal vanilla behavior)
        if (RLConfig.COMBAT_MODE == RLConfig.CombatMode.MIXED) return;

        EnderDragonEntity dragon = (EnderDragonEntity) (Object) this;
        if (!dragonInitialized) {
            BlockPos fightOrigin = dragon.getFightOrigin();
            BlockPos topPos = dragon.getWorld().getTopPosition(
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                EndPortalFeature.offsetOrigin(fightOrigin)
            );

            // Scan the block column manually if height is not loaded
            if (topPos.getY() < 30) {
                BlockPos.Mutable scanPos = new BlockPos.Mutable(0, 127, 0);
                for (int y = 127; y >= 0; y--) {
                    scanPos.setY(y);
                    if (!dragon.getWorld().getBlockState(scanPos).isAir()) {
                        topPos = scanPos.toImmutable();
                        break;
                    }
                }
            }

            perchPos = topPos;
            dragonInitialized = true;
        }

        if (RLConfig.COMBAT_MODE == RLConfig.CombatMode.RANGED_ONLY) {
            // Always airborne for target practice
            dragon.setPosition(0.5, FLY_HEIGHT, 0.5);
            dragon.setVelocity(Vec3d.ZERO);
            dragon.velocityModified = true;
            return;
        }

        // MELEE_ONLY: mostly grounded, with periodic brief airborne windows
        cycleTimer++;
        if (!airborne) {
            // Currently perched — melee phase
            if (cycleTimer >= MELEE_PERCH_TICKS) {
                airborne = true;
                cycleTimer = 0;
            }
        } else {
            // Currently airborne — brief ranged window
            if (cycleTimer >= MELEE_AIRBORNE_TICKS) {
                airborne = false;
                cycleTimer = 0;
            }
        }

        if (airborne) {
            dragon.setPosition(0.5, FLY_HEIGHT, 0.5);
        } else if (perchPos != null) {
            dragon.setPosition(perchPos.getX() + 0.5, perchPos.getY() + 1.0, perchPos.getZ() + 0.5);
        }
        dragon.setVelocity(Vec3d.ZERO);
        dragon.velocityModified = true;
    }
}
