package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 1/16-high exam-hall floor marker without collision. The examiner scans for these kinds instead of relying on template rotation. */
public final class ExamMarkBlock extends Block {
    public enum Kind implements StringRepresentable {
        ROOM("room"), PROMISE("promise"), ACTOR("actor"), GOLD("gold"), TRAINEE("trainee"), EXAMINER("examiner");
        private final String id;
        Kind(String id) { this.id = id; }
        @Override public String getSerializedName() { return id; }
    }
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 1, 16);
    public ExamMarkBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(KIND, Kind.ROOM)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(KIND); }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    /** Creative-mode builders cycle the marker kind by right-clicking it with an empty hand. */
    @Override protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos pos, Player p, BlockHitResult hit) {
        if (!p.getAbilities().instabuild || !p.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!l.isClientSide) l.setBlock(pos, s.cycle(KIND), 3);
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
}
