package dev.dronesmc;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class DroneItem extends Item {
    public DroneItem() {
        setRegistryName(DronesMC.ID, "drone"); setTranslationKey(DronesMC.ID + ".drone");
        setMaxStackSize(1); setCreativeTab(CreativeTabs.TRANSPORTATION);
    }
    @Override public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos,
            EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return EnumActionResult.SUCCESS;
        BlockPos target = pos.offset(facing);
        ItemStack stack = player.getHeldItem(hand);
        if (!player.canPlayerEdit(target, facing, stack) || !world.isBlockLoaded(target)) return EnumActionResult.FAIL;
        DroneEntity drone = new DroneEntity(world);
        drone.setPosition(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (stack.hasDisplayName()) drone.setCustomNameTag(stack.getDisplayName());
        if (!world.getCollisionBoxes(drone, drone.getEntityBoundingBox()).isEmpty()
                || world.containsAnyLiquid(drone.getEntityBoundingBox())
                || !world.checkNoEntityCollision(drone.getEntityBoundingBox())) return EnumActionResult.FAIL;
        if (!world.spawnEntity(drone)) return EnumActionResult.FAIL;
        if (!player.isCreative()) stack.shrink(1);
        return EnumActionResult.SUCCESS;
    }
}
