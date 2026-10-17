package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Entry points for the idea that a home is a recognised area (project design). A home is a roofed, dry cavity with a way in
 * that walking animals can use and room for a nest and a store: a natural cave, a hollow in a mound or slope, or one the animals
 * scraped themselves ({@link Burrowing}). The registered form is a {@link ShelterBlockEntity} core in the doorway cell; its
 * data is the home record:
 * <ul>
 *   <li>doorway: the core cell, one step inside the mouth ({@link ShelterBlockEntity#getBlockPos()}); the mouth is {@link ShelterBlockEntity#entrance()};
 *   <li>sleeping cells deep inside ({@link ShelterBlockEntity#beds()}, deepest first), never the doorway or the store cell;
 *   <li>the store cell ({@link ShelterBlockEntity#pileCell()}) holding the forage pile, in a side cell that does not cut the way in;
 *   <li>the family nest: the parents' cells and the young that share a parent's cell, plus one extra cell for every three young
 *       ({@link ShelterBlockEntity#sharedBeds()}); grown children later get a cell and a small nest of their own.
 * </ul>
 * A wild grown individual without such a home is a vagrant ({@link io.github.longshou.mcyukkuri.settlement.HabitatLife.Residence}).
 */
public final class Homes {
    /** The home this individual belongs to, or null (vagrants, dependants without one, pets, house yukkuri, ...). */
    public static ShelterBlockEntity of(YukkuriEntity entity) {
        var home = entity.habitat().shelter();
        return home != null && home.owns(entity.getUUID()) ? home : null;
    }
    public static HabitatLife.Residence residence(YukkuriEntity entity) { return entity.habitat().residence(); }
    public static boolean vagrant(YukkuriEntity entity) { return entity.habitat().vagrant(); }

    /** The cell this individual sleeps in (family nest cell for the young and for parents, own cell once it has a small nest), or null. */
    public static BlockPos nestCell(YukkuriEntity entity) {
        var home = of(entity);
        return home == null ? null : home.bed(entity.getUUID());
    }
    /** All cells of the nest this individual sleeps in: the whole family nest, or the single cell of a personal nest. */
    public static List<BlockPos> nestCells(YukkuriEntity entity) {
        var home = of(entity);
        if (home == null) return List.of();
        var bed = home.bed(entity.getUUID());
        if (bed == null) return List.of();
        return home.privateNest(entity.getUUID()) ? List.of(bed) : home.sharedBeds();
    }
    /**
     * The nest identification for birth care: true when {@code pos} is one of the cells of the nest this individual belongs to.
     * A mother is "at her nest" when she stands on one of these cells; a parent that has left it (no home, wrong home, or
     * a cell of another family) does not recognise the place.
     */
    public static boolean isOwnNest(YukkuriEntity entity, BlockPos pos) { return nestCells(entity).contains(pos); }
    /**
     * Where a land mother gives birth: the cell of her own family nest, if her home is usable and has room for the young
     * ({@link ShelterBlockEntity#suitableForYoung}); null otherwise (the family code then falls back to her hat boat, or to
     * no nest). It never creates or moves a nest.
     */
    public static BlockPos birthNest(YukkuriEntity mother, int young) {
        var home = of(mother);
        if (home == null || !home.usable(mother) || home.privateNest(mother.getUUID()) || !home.suitableForYoung(young)) return null;
        return home.bed(mother.getUUID());
    }

    /** True if a home could be registered with its doorway at {@code doorway} and its mouth in direction {@code facing}. */
    public static boolean canRecognise(ServerLevel level, BlockPos doorway, Direction facing) {
        return ShelterPlacement.canPlace(level, new ShelterPlacement.Plan(doorway, ShelterBlock.Kind.BURROW, facing));
    }
    /** Register the cavity as a home (place its core and compute its layout); null if it does not qualify. Terrain is never changed. */
    public static ShelterBlockEntity recognise(ServerLevel level, BlockPos doorway, Direction facing) {
        var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(doorway, ShelterBlock.Kind.BURROW, facing));
        return placed == null ? null : placed.shelter();
    }
    private Homes() {}
}
