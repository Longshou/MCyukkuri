package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HeadTop;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.NestOccupancy;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.NestSupport;
import io.github.longshou.mcyukkuri.life.PetNestBlock;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A read-only preview of the family room. Only the selected arrangement is installed. */
final class ShopFamilyRoom extends PathNavigationRegion {
    private final ServerLevel server;
    private final ShopRegistry.Entry shop;
    private final Rotation rotation;
    private final BlockPos anchor;
    private final List<BlockPos> cells;
    private final Map<BlockPos,BlockState> planned = new LinkedHashMap<>();

    private ShopFamilyRoom(ServerLevel level, ShopRegistry.Entry shop, BlockPos anchor) {
        super(level, anchor.offset(-4, -1, -4), anchor.offset(4, 3, 4));
        this.server = level;
        this.shop = shop;
        this.anchor = anchor;
        this.rotation = ShopRegistry.authoredRotation(shop);
        this.cells = cells(anchor, rotation);
    }
    // Opposite corners give two ribboned juveniles a full diagonal of turning clearance.
    static List<BlockPos> cells(BlockPos anchor, Rotation rotation) {
        return List.of(anchor, anchor.offset(new BlockPos(1, 0, 1).rotate(rotation)),
                anchor.relative(rotation.rotate(Direction.SOUTH)), anchor.relative(rotation.rotate(Direction.EAST)));
    }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
    @Override public BlockState getBlockState(BlockPos pos) {
        var state = planned.get(pos);
        return state == null ? super.getBlockState(pos) : state;
    }

    static List<BlockPos> prepare(ServerLevel level, ShopRegistry.Entry shop, BlockPos anchor, List<YukkuriEntity> litter) {
        var room = new ShopFamilyRoom(level, shop, anchor);
        var bodies = litter.stream().map(y -> new ShopFamilyBed.Member(NestOccupancy.stage(y),
                new NestLayout.Place(0, 0, y.getBbWidth(), HeadTop.reach(y)))).toList();
        var choice = ShopFamilyBed.choose(bodies, layout -> room.preview(layout, litter));
        if (choice == null || !room.install(choice, litter.size())) {
            GameplayLog.world(level, "shop", "action=family_bed result=refused reason={} shop={} nest={} count={}",
                    choice == null ? "no_supported_layout" : "unavailable_bedding", shop.id, anchor, bodies.size());
            return List.of();
        }
        return choice == ShopFamilyBed.ADJACENT ? room.cells.subList(0, litter.size()) : List.of(anchor);
    }

    private List<ShopFamilyBed.Pad> preview(ShopFamilyBed layout, List<YukkuriEntity> litter) {
        planned.clear();
        for (var pos : cells) {
            var old = server.getBlockState(pos);
            if (old.is(ModBlocks.NEST.get()) || old.is(ModBlocks.LARGE_NEST.get()))
                planned.put(pos, Blocks.AIR.defaultBlockState());
        }
        boolean woven = layout == ShopFamilyBed.WOVEN;
        var beds = layout == ShopFamilyBed.ADJACENT ? cells.subList(0, litter.size()) : List.of(anchor);
        for (var pos : woven ? cells : beds) {
            if (!getBlockState(pos).isAir() || !server.getWorldBorder().isWithinBounds(pos) || !NestSupport.below(server, pos)) return List.of();
        }
        if (woven) {
            var state = ModBlocks.LARGE_NEST.get().shopPlacement(server, anchor, rotation);
            if (state == null) return List.of();
            var nw = LargeNestBlock.northWest(state, anchor);
            for (int i = 0; i < 4; i++)
                planned.put(nw.offset(i & 1, 0, i >> 1), LargeNestBlock.cellState(state, i));
        } else {
            for (var pos : beds) planned.put(pos, ModBlocks.NEST.get().defaultBlockState());
            for (var pos : beds) {
                var old = server.getBlockState(pos);
                // The two outside aisles lead to the food and water dishes. Keep the space between cushions clear.
                var facing = rotation.rotate(pos.equals(cells.get(1)) ? Direction.EAST : Direction.WEST);
                if (layout == ShopFamilyBed.CUSHION && old.is(ModBlocks.NEST.get())) facing = old.getValue(PetNestBlock.FACING);
                var fitted = PetNestBlock.fitted(getBlockState(pos), server, this, pos, facing);
                if (fitted == null) return List.of();
                planned.put(pos, fitted);
            }
        }
        for (var entry : planned.entrySet()) {
            var pos = entry.getKey();
            if (server.getBlockState(pos).getBlock() != entry.getValue().getBlock()
                    && !server.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty()) return List.of();
        }
        var pads = new ArrayList<ShopFamilyBed.Pad>();
        for (var pos : beds) {
            var state = getBlockState(pos);
            var bed = woven ? NestResting.LARGE : PetNestBlock.bed(state);
            var centre = woven ? Vec3.atLowerCornerOf(LargeNestBlock.northWest(state, pos)).add(1, 0, 1) : Vec3.atBottomCenterOf(pos);
            var containers = litter.stream().map(body -> BedContainer.planned(body, this, centre, bed)).toList();
            pads.add(new ShopFamilyBed.Pad(pos.getX(), pos.getZ(), bed, (i,p) -> containers.get(i).standsInside(p.x(), p.z())));
        }
        return pads;
    }

    private boolean install(ShopFamilyBed layout, int count) {
        var changes = planned.entrySet().stream().filter(e -> server.getBlockState(e.getKey()) != e.getValue()).toList();
        if (changes.isEmpty()) return true;
        int cushions = 0;
        ItemStack returned = ItemStack.EMPTY;
        for (var change : changes) {
            var old = server.getBlockState(change.getKey());
            if (old.is(change.getValue().getBlock())) continue;
            if (change.getValue().is(ModBlocks.NEST.get())) cushions++;
            if (old.is(ModBlocks.NEST.get()) || old.is(ModBlocks.LARGE_NEST.get()) && old.getValue(LargeNestBlock.PART) == 0) {
                if (returned.isEmpty()) returned = new ItemStack(old.getBlock());
                else returned.grow(1);
            }
        }
        var shelf = ShopStock.get(server);
        var cushion = ShopCatalog.find(shop.kind, "yukkuri_nest");
        if (shelf.stock(server, shop.id, cushion) < cushions) return false;
        if (!returned.isEmpty() && ShopTrade.drop(server, shop.id, Vec3.atBottomCenterOf(shop.dock().above()), returned) == null) return false;
        for (int i = 0; i < cushions; i++) shelf.take(server, shop.id, cushion);
        for (var change : changes) server.setBlock(change.getKey(), change.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        GameplayLog.world(server, "shop", "action=family_bed result=installed grade={} shop={} nest={} count={} shelf_cushions={} returned={}",
                layout.name().toLowerCase(Locale.ROOT), shop.id, anchor, count, cushions, returned);
        return true;
    }
}
