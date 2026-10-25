package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.pathfinder.PathComputationType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small survival facilities. Inventory and residence are owned by the block entity. */
public class CareBlock extends Block implements EntityBlock {
    public enum Kind { BOWL, NEST, TOILET, MAT }
    private final Kind kind;
    private final String style;
    private final int capacity;
    private final float height;
    private static final JsonObject AUTHORED = readCollision();
    private static final VoxelShape NEST_SHAPE = authoredShape("yukkuri_nest", 8);
    private static final double NEST_TOP = authoredTop("yukkuri_nest");
    /** How far the cushion nest's bolster reaches past its block on every side (placed.py NEST_ROLL outer radius 11.4). */
    private static final double NEST_OVERHANG = 3.4 / 16;
    /** Collision shells generated from the visible pet nests (drafts/care-props/placed.py collision()). */
    private static JsonObject readCollision() {
        try (var stream = CareBlock.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/care_collision.json")) {
            if (stream == null) throw new IllegalStateException("Missing care_collision.json");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    static double authoredTop(String name) { return AUTHORED.getAsJsonObject(name).get("top").getAsDouble(); }
    /** Equal-height boxes of the mesh's top surface around a footprint centre {@code centre} units from the block's corner
     *  (8 = block centre, 16 = shared corner of a 2x2 footprint); joined pairwise to keep the merge cheap. */
    static VoxelShape authoredShape(String name, double centre) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (var raw : AUTHORED.getAsJsonObject(name).getAsJsonArray("boxes")) {
            var b = raw.getAsJsonArray();
            shapes.add(box(centre + b.get(0).getAsDouble(), 0, centre + b.get(1).getAsDouble(), centre + b.get(2).getAsDouble(), b.get(4).getAsDouble(), centre + b.get(3).getAsDouble()));
        }
        while (shapes.size() > 1) {
            List<VoxelShape> next = new ArrayList<>();
            for (int i = 0; i < shapes.size(); i += 2) next.add(i + 1 < shapes.size() ? Shapes.or(shapes.get(i), shapes.get(i + 1)) : shapes.get(i));
            shapes = next;
        }
        return shapes.isEmpty() ? Shapes.empty() : shapes.getFirst().optimize();
    }
    public CareBlock(Kind kind, Properties properties) { this(kind,properties,"",8,0); }
    public CareBlock(Kind kind, Properties properties,String style,int capacity,float height) { super(properties);this.kind=kind;this.style=style;this.capacity=capacity;this.height=height; }
    public Kind kind() { return kind; }
    public String style(){return style;}
    public int capacity(){return capacity;}
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CareBlockEntity(pos, state); }
    /** The cell holding this facility's block entity (a 2x2 nest's helper cells answer for their owner). */
    protected BlockPos owner(BlockState state, BlockPos pos) { return pos; }
    /** The pet cushion nest's bolster overhangs its block: it is only placed where the four side cells hold nothing solid,
     *  so the soft roll never draws into a wall, another nest or another block (blocks set beside it later are the player's choice). */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (kind != Kind.NEST || height > 0) return super.getStateForPlacement(context);
        var level = context.getLevel(); var pos = context.getClickedPos();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var next = pos.relative(side);
            var shape = level.getBlockState(next).getCollisionShape(level, next);
            if (!shape.isEmpty() && (side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? shape.min(side.getAxis()) < NEST_OVERHANG : shape.max(side.getAxis()) > 1 - NEST_OVERHANG)) {
                if (level.isClientSide && context.getPlayer() != null)
                    context.getPlayer().displayClientMessage(Component.literal("软垫窝的软卷会伸出本格约 3 像素：四周侧边须留空"), true);
                return null;
            }
        }
        return super.getStateForPlacement(context);
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        // A toilet is a low step with a standing surface, not an open cell at ground height.
        return kind != Kind.TOILET && super.isPathfindable(state, type);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if(height>0)return box(0,0,0,16,height,16);
        return switch(kind){
            case BOWL -> box(.92,0,1.5572,15.08,2.22,14.4428);
            case NEST -> box(-3.5,0,-3.5,19.5,NEST_TOP,19.5);
            // The pet sand tray (placed.py, 14.4 x 12) seats adults up to 1.2x.
            case TOILET -> box(.77,0,2.21,15.23,2.35,13.79);
            case MAT -> box(0,0,0,16,.15,16);
        };
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.NEST && height == 0 ? NEST_SHAPE : getShape(state,level,pos,context);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // Let notebook selection and the cleaning tool reach Item.useOn.
        if (stack.is(ModItems.FIELD_NOTEBOOK.get()) || stack.is(ModItems.WASH_CLOTH.get()))
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof DyeItem dye && CareStyles.styled(id()))
            return restyle(dye.getDyeColor(), stack, level, owner(state, pos), player);
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    private String id(){return BuiltInRegistries.BLOCK.getKey(this).getPath();}
    /** A dye of a listed colour switches the placed good to that style and is used up (not in creative). */
    private ItemInteractionResult restyle(DyeColor dye, ItemStack stack, Level level, BlockPos pos, Player player) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof CareBlockEntity care)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        String block = id();
        var target = CareStyles.forDye(block, dye);
        if (target == null) {
            player.displayClientMessage(Component.literal("这种染料没有对应款式；可用：" + CareStyles.hint(block)), true);
            GameplayLog.limited(player, "interaction", "care_style_no_dye", 20, "care style refused; block={} at={} dye={} reason=no_style_for_dye",
                    block, pos.toShortString(), dye.getName());
            return ItemInteractionResult.CONSUME;
        }
        String before = care.pattern();
        if (target.id().equals(before)) {
            player.displayClientMessage(Component.literal("已经是「" + target.label() + "」款式"), true);
            GameplayLog.limited(player, "interaction", "care_style_same", 20, "care style refused; block={} at={} dye={} style={} reason=already_this_style",
                    block, pos.toShortString(), dye.getName(), target.id().isEmpty() ? "default" : target.id());
            return ItemInteractionResult.CONSUME;
        }
        care.setPattern(target.id());
        boolean consumed = !player.getAbilities().instabuild;
        if (consumed) stack.shrink(1);
        level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1, 1);
        player.displayClientMessage(Component.literal("换成「" + target.label() + "」款式"), true);
        GameplayLog.event(player, "interaction", "care style changed; block={} at={} dye={} style {} -> {} dye_consumed={}",
                block, pos.toShortString(), dye.getName(), before.isEmpty() ? "default" : before, target.id().isEmpty() ? "default" : target.id(), consumed);
        return ItemInteractionResult.SUCCESS;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if(kind==Kind.MAT)return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(owner(state, pos)) instanceof CareBlockEntity care) {
            if (kind == Kind.BOWL) {
                player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                        new ChestMenu(MenuType.GENERIC_9x1, id, inventory, care, 1), Component.translatable("block.mcyukkuri.food_bowl")));
            } else if (kind == Kind.NEST) {
                player.displayClientMessage(Component.literal((care.largeNest()?"大窝（成/亚成 2＋赤 4，子 2 单独） ":"普通窝（赤 4／子 2／成或亚成 1） ")+care.occupancy()), true);
            } else {
                player.displayClientMessage(Component.translatable("care.mcyukkuri.toilet.level", care.waste(), care.capacity()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof CareBlockEntity care) {
            Containers.dropContents(level, pos, care);
            if (care.waste() > 0) for(var waste:care.cleanItems())popResource(level,pos,waste);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
