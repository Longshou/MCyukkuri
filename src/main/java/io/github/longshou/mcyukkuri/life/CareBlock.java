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
public final class CareBlock extends Block implements EntityBlock {
    public enum Kind { BOWL, NEST, TOILET, MAT }
    private final Kind kind;
    private final String style;
    private final int capacity;
    private final float height;
    private static final JsonObject AUTHORED = readCollision();
    private static final VoxelShape NEST_SHAPE = authoredShape("yukkuri_nest");
    private static final VoxelShape LARGE_NEST_SHAPE = authoredShape("large_yukkuri_nest");
    private static final VoxelShape LARGE_WILD_SHAPE = largeWildShape();
    private static final double NEST_TOP = authoredTop("yukkuri_nest"), LARGE_NEST_TOP = authoredTop("large_yukkuri_nest");
    /** Collision shells generated from the visible pet nests (drafts/care-props/placed.py collision()). */
    private static JsonObject readCollision() {
        try (var stream = CareBlock.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/care_collision.json")) {
            if (stream == null) throw new IllegalStateException("Missing care_collision.json");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static double authoredTop(String name) { return AUTHORED.getAsJsonObject(name).get("top").getAsDouble(); }
    /** Equal-height boxes of the mesh's top surface, centred on the block; joined pairwise to keep the merge cheap. */
    private static VoxelShape authoredShape(String name) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (var raw : AUTHORED.getAsJsonObject(name).getAsJsonArray("boxes")) {
            var b = raw.getAsJsonArray();
            shapes.add(box(8 + b.get(0).getAsDouble(), 0, 8 + b.get(1).getAsDouble(), 8 + b.get(2).getAsDouble(), b.get(4).getAsDouble(), 8 + b.get(3).getAsDouble()));
        }
        while (shapes.size() > 1) {
            List<VoxelShape> next = new ArrayList<>();
            for (int i = 0; i < shapes.size(); i += 2) next.add(i + 1 < shapes.size() ? Shapes.or(shapes.get(i), shapes.get(i + 1)) : shapes.get(i));
            shapes = next;
        }
        return shapes.isEmpty() ? Shapes.empty() : shapes.getFirst().optimize();
    }
    /** Wild bedding: rounded 31-unit footprint with a 28.5-unit interior and a low southern entrance. */
    private static VoxelShape largeWildShape() {
        VoxelShape shape=Shapes.empty();
        double floor=.5, rim=2.6;
        for(double x=-15.5;x<15.5;x+=1){
            double sample=Math.abs(x+.5);
            double outer=Math.pow(Math.max(0,Math.pow(15.5,4)-Math.pow(sample,4)),.25);
            double inner=Math.pow(Math.max(0,Math.pow(14.25,4)-Math.pow(sample,4)),.25);
            shape=Shapes.or(shape,box(8+x,0,8-outer,9+x,floor,8+outer));
            if(inner==0)shape=Shapes.or(shape,box(8+x,0,8-outer,9+x,rim,8+outer));
            else {
                shape=Shapes.or(shape,box(8+x,0,8-outer,9+x,rim,8-inner));
                double south=sample<7?floor:sample<10?1.5:sample<12?2.3:rim;
                shape=Shapes.or(shape,box(8+x,0,8+inner,9+x,south,8+outer));
            }
        }
        return shape.optimize();
    }
    public CareBlock(Kind kind, Properties properties) { this(kind,properties,"",8,0); }
    public CareBlock(Kind kind, Properties properties,String style,int capacity,float height) { super(properties);this.kind=kind;this.style=style;this.capacity=capacity;this.height=height; }
    public Kind kind() { return kind; }
    public String style(){return style;}
    public int capacity(){return capacity;}
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CareBlockEntity(pos, state); }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        // A toilet is a low step with a standing surface, not an open cell at ground height.
        return kind != Kind.TOILET && super.isPathfindable(state, type);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if(style.startsWith("large_"))return box(-7.5,0,-7.5,23.5,style.equals("large_wild_nest")?3:LARGE_NEST_TOP,23.5);
        if(height>0)return box(0,0,0,16,height,16);
        return switch(kind){
            case BOWL -> box(.92,0,1.5572,15.08,2.22,14.4428);
            case NEST -> box(-2,0,-2,18,NEST_TOP,18);
            case TOILET -> box(1.975,0,3.175,14.025,2.35,12.825);
            case MAT -> box(0,0,0,16,.15,16);
        };
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if(style.startsWith("large_"))return style.equals("large_wild_nest")?LARGE_WILD_SHAPE:LARGE_NEST_SHAPE;
        return kind == Kind.NEST && height == 0 ? NEST_SHAPE : getShape(state,level,pos,context);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // Let notebook selection and the cleaning tool reach Item.useOn.
        if (stack.is(ModItems.FIELD_NOTEBOOK.get()) || stack.is(ModItems.WASH_CLOTH.get()))
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof DyeItem dye && CareStyles.styled(id()))
            return restyle(dye.getDyeColor(), stack, level, pos, player);
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
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CareBlockEntity care) {
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
