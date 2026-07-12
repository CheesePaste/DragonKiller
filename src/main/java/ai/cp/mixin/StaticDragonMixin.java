package ai.cp.mixin;

import ai.cp.config.RLConfig;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.util.math.BlockPos;
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
    private int initDelay = 0;
    @Unique
    private int settleTicks = 0;

    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void cancelDragonAI(CallbackInfo ci) {
        if (RLConfig.IS_PHASE_2) return; // Phase 2: let dragon AI run freely

        EnderDragonEntity dragon = (EnderDragonEntity) (Object) this;
        if (!dragonInitialized) {
            BlockPos fightOrigin = dragon.getFightOrigin();
            BlockPos topPos = dragon.getWorld().getTopPosition(
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                EndPortalFeature.offsetOrigin(fightOrigin)
            );

            // Wait for terrain/podium to load (height should be > 30 in a loaded End)
            if (topPos.getY() < 30) {
                if (++initDelay < 100) {
                    ci.cancel(); // Freeze and wait for terrain load + podium gen
                    return;
                }
                // Fallback: scan the block column manually
                BlockPos.Mutable scanPos = new BlockPos.Mutable(0, 127, 0);
                for (int y = 127; y >= 0; y--) {
                    scanPos.setY(y);
                    if (!dragon.getWorld().getBlockState(scanPos).isAir()) {
                        topPos = scanPos.toImmutable();
                        break;
                    }
                }
            }

            // Perch on top of the highest block (+1 to sit ON TOP not inside)
            dragon.setPosition(topPos.getX() + 0.5, topPos.getY() + 1.0, topPos.getZ() + 0.5);
            // Set sitting phase so client renders perched pose
            dragon.getPhaseManager().setPhase(PhaseType.SITTING_SCANNING);
            dragonInitialized = true;
            settleTicks = 30; // Let body parts settle into stable sitting pose
            return; // Let tickMovement() run to position body parts
        }

        // Let body parts settle for ~30 ticks to reach stable sitting pose
        if (settleTicks > 0) {
            settleTicks--;
            return; // Don't cancel — let tickMovement() run for body part positioning
        }

        // Still update boss bar and fight state before freezing AI
        if (this.fight != null) {
            this.fight.updateFight(dragon);
        }
        ci.cancel(); // Freeze dragon after settled
    }
}
