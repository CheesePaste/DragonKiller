package ai.cp.mixin;

import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class EquipmentMixin {
    @Inject(method = "onSpawn", at = @At("TAIL"))
    private void giveEquipment(CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (player.getInventory().isEmpty()) {
            giveInitialEquipment(player);
        }
    }

    private static void giveInitialEquipment(ServerPlayerEntity player) {
        // Diamond sword
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        player.getInventory().setStack(0, sword);

        // Infinite blocks (end stone, 4 stacks)
        ItemStack blocks = new ItemStack(Items.END_STONE, 64);
        player.getInventory().setStack(1, blocks);

        // Water bucket
        ItemStack waterBucket = new ItemStack(Items.WATER_BUCKET);
        player.getInventory().setStack(2, waterBucket);

        // Shield
        ItemStack shield = new ItemStack(Items.SHIELD);
        player.getInventory().setStack(3, shield);

        // Diamond armor
        player.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        player.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
        player.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));

        // Extra blocks in remaining slots
        for (int i = 4; i < 9; i++) {
            player.getInventory().setStack(i, new ItemStack(Items.END_STONE, 64));
        }
    }
}
