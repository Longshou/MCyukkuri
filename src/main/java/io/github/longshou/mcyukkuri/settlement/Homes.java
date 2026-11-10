package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import net.neoforged.neoforge.event.EventHooks;

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
    /** Loaded doorway cores in the closed box, in betweenClosed order (x fastest). No chunks are requested. */
    public static List<ShelterBlockEntity> loadedWithin(ServerLevel level, BlockPos minInclusive, BlockPos maxInclusive) {
        return LoadedShelters.within(level, minInclusive, maxInclusive);
    }
    /** Successful placements register immediately, without waiting for the block entity's onLoad callback. */
    public static void registerLoaded(ServerLevel level, ShelterBlockEntity core) { LoadedShelters.register(level, core); }
    /** The home this individual belongs to, or null (vagrants, dependants without one, pets, house yukkuri, ...). */
    public static ShelterBlockEntity of(YukkuriEntity entity) {
        var home = entity.habitat().shelter();
        return home != null && home.owns(entity.getUUID()) ? home : null;
    }
    public static HabitatLife.Residence residence(YukkuriEntity entity) { return entity.habitat().residence(); }
    public static boolean vagrant(YukkuriEntity entity) { return entity.habitat().vagrant(); }

    static int migrationHouseholds(List<YukkuriEntity> members, SettlementData.Group group) {
        var households = new HashSet<UUID>();
        for (var member : members) if (Migration.joins(member, group)) {
            var id = member.getUUID();
            var partner = member.family().partner();
            households.add(partner != null && partner.compareTo(id) < 0 ? partner : id);
        }
        return households.size();
    }

    /** The site needs enough separate places for families to begin ordinary home seeking. */
    static boolean nestable(ServerLevel level, BlockPos at, SettlementData.Site site, int households, YukkuriEntity owner) {
        int free = 0;
        for (var core : loadedWithin(level, at.offset(-24, -3, -24), at.offset(24, 3, 24)))
            if (core.residentIds().isEmpty() && core.sound() && core.moisture() < 85 && core.dirt() < 90) free++;
        if (site != null) for (var slot : site.plan().slots()) {
            if (!slot.dwelling() || slot.kind() == SiteSlot.Kind.NURSERY || level.getBlockEntity(slot.anchor()) != null) continue;
            if (NaturalShelter.enclosed(level, slot.anchor()) && NaturalShelter.stand(level, slot.door())) free++;
        }
        if (free >= households) return true;
        if (!EventHooks.canEntityGrief(level, owner)) return false;
        var cuts = new HashSet<BlockPos>();
        for (var mouth : Burrowing.faces(level, at, 6)) {
            for (var direction : Direction.Plane.HORIZONTAL) {
                if (!Burrowing.soft(level, mouth.relative(direction))) continue;
                var plan = Burrowing.plan(level, mouth, direction, Burrowing.MIN_CELLS);
                if (plan == null || plan.cells().stream().anyMatch(cuts::contains)) continue;
                cuts.addAll(plan.cells());
                if (++free >= households) return true;
            }
        }
        int needed = households - free;
        int plants = 0, footprints = 0;
        for (var pos : BlockPos.betweenClosed(at.offset(-6, -1, -6), at.offset(6, 1, 6))) {
            SearchBudget.probe(level);
            if (level.hasChunkAt(pos) && !PlantBedding.material(level.getBlockState(pos)).isEmpty()) plants++;
            if (footprints < needed && ((pos.getX() | pos.getZ()) & 1) == 0 && ShelterPlacement.surfaceFootprint(level, pos)) footprints++;
            if (footprints >= needed && plants >= needed) return true;
        }
        return false;
    }

    /**
     * Pets and other kept mothers sleep on a care-prop pet bed (one block, {@code CareBlock.Kind.NEST}). Giving birth needs a bigger bed:
     * the mother's own bed plus one more free pet bed set beside it (centre within two blocks, so the overhanging bolsters stay apart) for
     * every three young (the same one-cell-per-three rule as the wild family nest, {@link ShelterBlockEntity#extraCells}). Mothers without
     * such a bed (wild, house yukkuri, factory stock) are not affected.
     */
    public static boolean petBedFits(YukkuriEntity mother, int young) {
        if (!mother.pet().isPet() || mother.life().home() == null || mother.life().naturalHome()) return true;
        var level = mother.level();
        var at = mother.life().home().pos();
        if (!level.hasChunkAt(at) || !(level.getBlockEntity(at) instanceof CareBlockEntity bed)
                || bed.kind() != CareBlock.Kind.NEST) return true;
        if(bed.largeNest())return young<=4;
        int need = ShelterBlockEntity.extraCells(young), have = 0;
        for (var next : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 0, 2))) {
            if (!next.equals(at) && level.hasChunkAt(next) && level.getBlockEntity(next) instanceof CareBlockEntity other
                    && other.kind() == CareBlock.Kind.NEST
                    && other.canClaim(mother)) have++;
        }
        return have >= need;
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
