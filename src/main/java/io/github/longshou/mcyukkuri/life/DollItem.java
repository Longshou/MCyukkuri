package io.github.longshou.mcyukkuri.life;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** A doll in hand: right-clicked onto a floor, it is set there as its placed form ({@link PlacedDollEntity}), one per click. */
public final class DollItem extends Item {
    public DollItem(Item.Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel();
        var clicked = context.getClickedPos();
        var spot = level.getBlockState(clicked).canBeReplaced() && level.getFluidState(clicked).isEmpty()
                ? clicked : clicked.relative(context.getClickedFace());
        var below = spot.below();
        var floor = level.getBlockState(below).getCollisionShape(level, below);
        if (floor.isEmpty() || !level.getBlockState(spot).canBeReplaced() || !level.getFluidState(spot).isEmpty())
            return InteractionResult.FAIL;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var placed = PlacedDollEntity.place((ServerLevel) level, context.getItemInHand().copyWithCount(1),
                new Vec3(spot.getX() + .5, below.getY() + floor.max(Direction.Axis.Y), spot.getZ() + .5),
                context.getHorizontalDirection().getOpposite().toYRot());
        if (placed == null) return InteractionResult.FAIL;
        var player = context.getPlayer();
        if (player == null || !player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
