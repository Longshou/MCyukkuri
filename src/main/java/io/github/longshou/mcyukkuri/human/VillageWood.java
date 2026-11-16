package io.github.longshou.mcyukkuri.human;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * The wood a piece of village furniture is made of: the wood families of the five village palettes
 * (art/structures/human-world/palette.py). States saved before the property existed load as {@link #OAK}, the original look.
 */
public enum VillageWood implements StringRepresentable {
    OAK, SPRUCE, ACACIA, JUNGLE;

    public static final EnumProperty<VillageWood> WOOD = EnumProperty.create("wood", VillageWood.class);

    @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }

    /** The wood of a piece of the same block beside {@code pos}, so a piece set down next to others continues them; oak alone. */
    public static VillageWood beside(BlockGetter level, BlockPos pos, Block block) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var other = level.getBlockState(pos.relative(side));
            if (other.is(block)) return other.getValue(WOOD);
        }
        return OAK;
    }
}
