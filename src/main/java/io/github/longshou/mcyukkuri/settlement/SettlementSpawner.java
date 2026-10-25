package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.LoggerFactory;

/** Places physical shelters on dry free ground without replacing solid blocks or loading remote chunks. */
public final class SettlementSpawner {
    public enum Preset { WANDERER, FAMILY, FOREST, FOREST_LED, BANDITS }
    public static final TagKey<Biome> FORESTS = TagKey.create(Registries.BIOME, MCYukkuri.id("forest_settlements"));
    private static final YukkuriVariant[] COMMON = {YukkuriVariant.REIMU, YukkuriVariant.MARISA, YukkuriVariant.CHEN, YukkuriVariant.ALICE, YukkuriVariant.MYON};
    public static void discover(YukkuriEntity seed) {
        ServerLevel level = (ServerLevel)seed.level();
        if (!YukkuriConfig.NATURAL_SPAWNS.get() || !YukkuriConfig.COMMUNITIES.get() || seed.profile().houseBody()) return;
        if (!SettlementData.get(level).discover(seed.blockPosition())) return;
        int roll = seed.getRandom().nextInt(100);
        Preset preset = Preset.WANDERER;
        if (level.getBiome(seed.blockPosition()).is(FORESTS) && roll < YukkuriConfig.FOREST_CHANCE.get())
            preset = seed.getRandom().nextInt(100) < YukkuriConfig.LEADER_CHANCE.get() ? Preset.FOREST_LED : Preset.FOREST;
        else if (roll >= YukkuriConfig.FOREST_CHANCE.get() && roll < YukkuriConfig.FOREST_CHANCE.get() + YukkuriConfig.FAMILY_CHANCE.get())
            preset = Preset.FAMILY;
        else if (roll >= YukkuriConfig.FOREST_CHANCE.get() + YukkuriConfig.FAMILY_CHANCE.get()
                && roll < Math.min(100, YukkuriConfig.FOREST_CHANCE.get() + YukkuriConfig.FAMILY_CHANCE.get() + YukkuriConfig.BANDIT_CHANCE.get()))
            preset = Preset.BANDITS;
        // A planned site waiting for its community within 48 blocks is populated by the site claimer instead.
        if ((preset == Preset.FOREST || preset == Preset.FOREST_LED) && SiteLife.vacantNear(level, seed.blockPosition(), 48) != null) preset = Preset.WANDERER;
        spawn(level, seed.blockPosition(), preset, seed);
    }
    public static List<YukkuriEntity> spawn(ServerLevel level, BlockPos origin, Preset preset) {
        return spawn(level, origin, preset, null);
    }
    /**
     * Minimal water-colony entry used by the playground: an existing aquatic individual remains the real founder;
     * the rest of the family, its forest-style leader office and the bank-side common pile are ordinary settlement data.
     */
    public static List<YukkuriEntity> spawnAquaticColony(ServerLevel level, BlockPos bank, YukkuriEntity seed) {
        if (seed == null || seed.level() != level || !seed.isAlive() || !seed.profile().aquatic()) return fail("the founder must be a living aquatic yukkuri");
        if (seed.settlement().group() != null) return fail("the aquatic founder already belongs to a settlement");
        if (seed.distanceToSqr(Vec3.atBottomCenterOf(bank)) > 20 * 20) return fail("the founder must be within twenty blocks of the bank anchor");
        return spawn(level, bank, Preset.FAMILY, seed, null,
                new SettlementComposition(List.of(new SettlementComposition.Household(2, 1)), true), true);
    }
    /**
     * Populates a planned site (settlement/site): a leader in the longhouse if the composition has one, every household in
     * its own burrow, the first stock on the granary piles. Fails without side effects when the site cannot take the group.
     */
    public static List<YukkuriEntity> spawnAtSite(ServerLevel level, SettlementData.Site site, SettlementComposition composition) {
        return spawnAtSite(level, site, composition, false);
    }
    /**
     * @param forced true for an explicit administrator or exhibit placement: the density cap meant for natural generation
     *               (people within 32 blocks, so neighbouring plots would crowd each other out) does not apply
     */
    public static List<YukkuriEntity> spawnAtSite(ServerLevel level, SettlementData.Site site, SettlementComposition composition, boolean forced) {
        var plaza = site.first(SiteSlot.Kind.PLAZA);
        if (plaza == null || !site.vacant()) return fail("the site has no plaza or is already occupied");
        return spawn(level, plaza.anchor(), composition.leader() ? Preset.FOREST_LED : Preset.FOREST, null, site, composition, forced);
    }
    private static List<YukkuriEntity> spawn(ServerLevel level, BlockPos origin, Preset preset, YukkuriEntity seed) {
        return spawn(level, origin, preset, seed, null, null, false);
    }
    private static List<YukkuriEntity> spawn(ServerLevel level, BlockPos origin, Preset preset, YukkuriEntity seed,
                                             SettlementData.Site site, SettlementComposition fixed, boolean forced) {
        if (preset == Preset.BANDITS) return spawnBandits(level, origin, seed);
        boolean forest = preset == Preset.FOREST || preset == Preset.FOREST_LED;
        FAILURE.remove();
        var composition = fixed != null ? fixed : SettlementComposition.sample(preset, level.random);
        int count = preset == Preset.WANDERER ? 1 : composition.size();
        int cap = forest ? Math.min(YukkuriConfig.COMMUNITY_SPAWN_CAP.get(), YukkuriConfig.COMMUNITY_POPULATION_CAP.get())
                : Math.min(YukkuriConfig.LOCAL_SPAWN_CAP.get(), FamilyConfig.POPULATION_CAP.get());
        if (!level.hasChunkAt(origin) || !forced && FamilyData.get(level).population(level,
                origin) + count - (seed == null ? 0 : 1) > cap) return fail("population cap "+cap+" within 32 blocks reached");
        YukkuriEntity probe = ModEntities.REIMU.get().create(level);
        boolean aquaticSettlement = seed != null && seed.profile().aquatic() && !forest;
        if (probe == null || !aquaticSettlement && site == null && !probe.life().dryStand(origin)) return fail("the plaza "+origin.toShortString()+" is not a dry standing place");
        if (aquaticSettlement && !nearbyCalmWater(level, origin)) return fail("the water household needs a calm surface within fourteen blocks of "+origin.toShortString());
        BlockPos waterCommons = aquaticSettlement && composition.leader() ? waterCommons(level, probe, origin) : null;
        if (aquaticSettlement && composition.leader() && waterCommons == null)
            return fail("the water household needs a dry bank cell for its common store near "+origin.toShortString());
        boolean seedLeader = seed != null && composition.leader() && (seed.getVariant() == YukkuriVariant.PATCHOULI || aquaticSettlement);
        List<Integer> units = new ArrayList<>();
        if (seedLeader) units.add(1);
        composition.households().forEach(h -> units.add(h.size()));
        if ((composition.leader() && !seedLeader) || preset == Preset.WANDERER) units.add(1);
        // Cardboard encounters belong to existing human villages: discarded human goods, not yukkuri construction.
        var kind=preset==Preset.WANDERER&&level.isVillage(origin)?ShelterBlock.Kind.CARDBOARD:ShelterBlock.Kind.BURROW;
        var plans=aquaticSettlement ? List.<ShelterPlacement.Plan>of()
                : site!=null?SiteLife.allocate(level,site,units,composition,seedLeader):plans(probe,origin,units,forest?24:12,kind);
        if(!aquaticSettlement&&plans.size()!=units.size()){trace("No complete set of sites: "+preset+" "+units);return List.of();}
        List<BlockPos> sites=new ArrayList<>();List<Integer> rooms=new ArrayList<>();
        for(int room=0;room<plans.size();room++) {
            var beds=plans.get(room).beds(level);
            for(int i=0;i<units.get(room);i++){sites.add(beds.get(i));rooms.add(room);}
        }
        List<YukkuriEntity> members = new ArrayList<>();
        List<List<YukkuriEntity>> households = new ArrayList<>();
        // An aquatic founder is the first parent of the real household as well as its leader.  Do not create a
        // leader-shaped extra adult beside a disconnected generated family.
        if (seedLeader && !aquaticSettlement) members.add(seed);
        for (var household : composition.households()) {
            List<YukkuriEntity> family = new ArrayList<>();
            boolean aquaticFamily = aquaticSettlement || members.isEmpty() && seed != null && seed.profile().aquatic();
            // Most two-parent households are a Reimu mother and a Marisa father [original 4/5]; the rest stay mixed.
            boolean couple = household.parents() == 2 && level.random.nextInt(5) != 0;
            for (int i = 0; i < household.size(); i++) {
                YukkuriVariant variant = aquaticFamily ? YukkuriVariant.MARISA : i < household.parents() ? couple ? (i == 0 ? YukkuriVariant.REIMU
                        : family.getFirst().getVariant() == YukkuriVariant.MARISA ? YukkuriVariant.REIMU : YukkuriVariant.MARISA) : COMMON[level.random.nextInt(COMMON.length)]
                        : family.get(level.random.nextInt(household.parents())).getVariant();
                YukkuriEntity member = members.isEmpty() && seed != null ? seed : ModEntities.forVariant(variant).create(level);
                if (member == null) return List.of();
                if (aquaticFamily && member != seed && i < household.parents())
                    member.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,
                            SubspeciesLife.Congenital.NORMAL);
                if (aquaticFamily && member.subspecies().kind() == SubspeciesLife.Kind.AQUATIC_MARISA)
                    member.aquaticLife().requestNaturalAfloatSpawn(); // an aquatic household lives on the water, not on the bank
                family.add(member); members.add(member);
            }
            households.add(family);
        }
        if ((composition.leader() && !seedLeader) || preset == Preset.WANDERER) {
            YukkuriEntity member = preset == Preset.WANDERER && seed != null ? seed
                    : ModEntities.forVariant(composition.leader() ? YukkuriVariant.PATCHOULI : COMMON[level.random.nextInt(COMMON.length)]).create(level);
            if (member == null) return List.of();
            members.add(member);
        }
        List<ShelterPlacement.Placed> homes=new ArrayList<>();
        for(var plan:plans){var placed=ShelterPlacement.place(level,plan);if(placed==null){trace("Site changed during placement: "+plan);homes.forEach(home->home.rollback(level));
                return List.of();}homes.add(placed);}
        List<YukkuriEntity> added = new ArrayList<>();
        var seedPartner=seed==null?null:FamilyData.get(level).snapshotPartner(seed.getUUID());
        var seedSnapshot=new CompoundTag();if(seed!=null)seed.saveWithoutId(seedSnapshot);
        var aquaticStarts = aquaticSettlement ? aquaticStarts(level, probe, origin, members.size() - (seed == null ? 0 : 1)) : List.<BlockPos>of();
        if (aquaticSettlement && aquaticStarts.size() != members.size() - (seed == null ? 0 : 1))
            return fail("the water household needs " + (members.size() - (seed == null ? 0 : 1)) + " separate dry launch places near " + origin.toShortString());
        int aquaticStart = 0;
        for (int i = 0; i < members.size(); i++) {
            YukkuriEntity member = members.get(i);
            if (member == seed) continue;
            BlockPos start = aquaticSettlement ? aquaticStarts.get(aquaticStart++) : sites.get(i);
            member.moveTo(Vec3.atBottomCenterOf(start)); member.setOnGround(true);
            member.tickCount = level.random.nextInt(200); // spread every "tickCount % N" job over the colony
            if (!level.noCollision(member) || !level.addFreshEntity(member)) {
                trace("Member collision at "+start);
                rollback(level,added,homes,seed,seedSnapshot,seedPartner);return List.of();
            }
            added.add(member);
        }
        if(preset==Preset.WANDERER) {
            if (aquaticSettlement) { boardAquaticMembers(members); return List.copyOf(members); }
            homes.getFirst().shelter().initializeEncounter(1);
            if(!members.getFirst().habitat().choose(homes.getFirst().shelter())){rollback(level,added,homes,seed,seedSnapshot,seedPartner);return List.of();}

            return List.copyOf(members);
        }
        var leader = composition.leader() ? members.get(seedLeader ? 0 : members.size() - 1).getUUID() : null;
        var group = new SettlementData.Group(UUID.randomUUID(), forest || aquaticSettlement && composition.leader() ? SettlementData.Kind.FOREST : SettlementData.Kind.FAMILY,
                origin, leader);
        if (site != null) group.siteId = site.id;
        if (waterCommons != null) group.waterCommons = waterCommons;
        SettlementData.get(level).add(group);
        for (int i = 0; i < members.size(); i++) {
            YukkuriEntity member = members.get(i);
            member.settlement().join(group);

            FamilyData.get(level).register(member);
        }
        for (int i = 0; i < households.size(); i++) {
            var family = households.get(i); var plan = composition.households().get(i);
            YukkuriEntity first = family.getFirst(), second = plan.parents() == 2 ? family.get(1) : null;
            // Generated children take the first parent as mother: a Reimu with a Marisa is always the mother.
            if (second != null && first.getVariant() == YukkuriVariant.MARISA && second.getVariant() == YukkuriVariant.REIMU) { var swap = first; first = second; second = swap; }
            if (second != null && !first.family().introduce(second)) {
                rollback(level,added,homes,seed,seedSnapshot,seedPartner);SettlementData.get(level).remove(group.id);return List.of();
            }
            for (int j = plan.parents(); j < family.size(); j++) {
                family.get(j).family().generatedChild(first, second);
                if (first.profile().aquatic())
                    SubspeciesLife.onBirth(family.get(j), first, second);
                FamilyData.get(level).register(family.get(j));
            }
        }
        for(int i=0;i<homes.size();i++)homes.get(i).shelter().initializeEncounter(units.get(i));
        // Generated members are wild-born, not vanilla natural spawns: give them the wild cognition draw (headwear.Recognition).
        for(int i=0;i<members.size();i++) {
            if (aquaticSettlement) continue;
            if(!members.get(i).habitat().choose(homes.get(rooms.get(i)).shelter())) {
                trace("Claim failed: member "+i+" site "+homes.get(rooms.get(i)).shelter().getBlockPos()+" capacity "+homes.get(rooms.get(i)).shelter().capacity());
                rollback(level,added,homes,seed,seedSnapshot,seedPartner);
                SettlementData.get(level).remove(group.id);return List.of();
            }
        }
        if(site!=null)SiteLife.found(level,site,group,members.size());
        else if (waterCommons != null) initializeWaterCommons(level, group, members.size());
        if (aquaticSettlement) boardAquaticMembers(members);
        return List.copyOf(members);
    }
    /** Once the household's ages and kin are set, every aquatic member joins the world aboard her own hull in this same tick. */
    private static void boardAquaticMembers(List<YukkuriEntity> members) {
        for (var member : members) if (member.isAlive()) member.aquaticLife().attemptAfloatSpawn();
    }
    /** A water group's public store is a normal forage pile on an actual dry bank, seeded only at colony creation. */
    private static void initializeWaterCommons(ServerLevel level, SettlementData.Group group, int population) {
        var at = group.waterCommons;
        if (at == null || !level.setBlock(at, ModBlocks.FORAGE_PILE.get().defaultBlockState(), 3)
                || !(level.getBlockEntity(at) instanceof ForagePileBlockEntity pile)) return;
        pile.setApproach(dryApproach(level, at));
        pile.returnFood(new ItemStack(Items.SHORT_GRASS, Math.min(16, Math.max(4, population * 2))));
        SettlementData.get(level).setDirty();
    }
    private static BlockPos waterCommons(ServerLevel level, YukkuriEntity probe, BlockPos origin) {
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-5, -2, -5), origin.offset(5, 2, 5)))
            if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && probe.life().dryStand(pos) && nearbyCalmWater(level, pos)) candidates.add(pos.immutable());
        return candidates.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(origin))).orElse(null);
    }
    /** Spawn only on separate real dry cells; each aquatic member launches its ordinary hull afterwards. */
    private static List<BlockPos> aquaticStarts(ServerLevel level, YukkuriEntity probe, BlockPos origin, int needed) {
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -2, -6), origin.offset(6, 2, 6)))
            if (level.hasChunkAt(pos) && probe.life().dryStand(pos) && nearbyCalmWater(level, pos)) candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        var starts = new ArrayList<BlockPos>();
        for (var candidate : candidates) {
            if (starts.stream().anyMatch(chosen -> chosen.distSqr(candidate) < 4)) continue;
            starts.add(candidate);
            if (starts.size() == needed) break;
        }
        return starts;
    }
    private static Direction dryApproach(ServerLevel level, BlockPos pile) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var stand = pile.relative(side);
            if (level.hasChunkAt(stand) && level.getFluidState(stand).isEmpty()
                    && level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) return side;
        }
        return Direction.SOUTH;
    }
    /** Water families keep the requested dry bank as their meeting and common-store anchor, but launch only onto a real calm surface. */
    private static boolean nearbyCalmWater(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-14, -3, -14), origin.offset(14, 3, 14)))
            if (io.github.longshou.mcyukkuri.subspecies.HatBoatRoute.calmSurface(level, pos)) return true;
        return false;
    }
    /** A rare band of 3-4 adult common yukkuri with no shelter and no family records; only some groups behave this way, wanderers in general do not. */
    private static List<YukkuriEntity> spawnBandits(ServerLevel level, BlockPos origin, YukkuriEntity seed) {
        int count = 3 + level.random.nextInt(2);
        int cap = Math.min(YukkuriConfig.LOCAL_SPAWN_CAP.get(), FamilyConfig.POPULATION_CAP.get());
        if (!level.hasChunkAt(origin) || FamilyData.get(level).population(level, origin) + count - (seed == null ? 0 : 1) > cap) return List.of();
        if (seed != null && !Arrays.asList(COMMON).contains(seed.getVariant())) return List.of();
        YukkuriEntity probe = ModEntities.REIMU.get().create(level);
        if (probe == null || !probe.life().dryStand(origin)) return List.of();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-5, -2, -5), origin.offset(5, 2, 5)))
            if (pos.distSqr(origin) <= 25 && probe.life().dryStand(pos)) candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        List<BlockPos> sites = new ArrayList<>();
        sites.add(origin.immutable());
        for (BlockPos pos : candidates) {
            if (sites.size() >= count) break;
            if (sites.stream().allMatch(site -> site.distSqr(pos) >= 4)) sites.add(pos);
        }
        if (sites.size() < count) return List.of();
        List<YukkuriEntity> members = new ArrayList<>();
        List<YukkuriEntity> added = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            YukkuriEntity member = i == 0 && seed != null ? seed : ModEntities.forVariant(COMMON[level.random.nextInt(COMMON.length)]).create(level);
            if (member == null) { added.forEach(YukkuriEntity::discard); return List.of(); }
            if (member != seed) {
                member.moveTo(Vec3.atBottomCenterOf(sites.get(i))); member.setOnGround(true);
            member.tickCount = level.random.nextInt(200); // spread every "tickCount % N" job over the colony
                if (!level.noCollision(member) || !level.addFreshEntity(member)) { added.forEach(YukkuriEntity::discard); return List.of(); }
                added.add(member);
            }
            members.add(member);
        }
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.BANDITS, origin, null);
        SettlementData.get(level).add(group);
        for (YukkuriEntity member : members) { member.settlement().join(group);  }
        return List.copyOf(members);
    }
    private static void rollback(ServerLevel level,List<YukkuriEntity> added,List<ShelterPlacement.Placed> homes,
                                 YukkuriEntity seed,CompoundTag snapshot,FamilyData.PartnerSnapshot seedPartner) {
        var allocated=added.stream().map(YukkuriEntity::getUUID).collect(Collectors.toSet());
        added.forEach(YukkuriEntity::discard);homes.forEach(home->home.rollback(level));
        FamilyData.get(level).rollbackEncounter(allocated,seedPartner);
        YukkuriWorldData.get(level).rollbackEncounter(allocated);
        if(seed!=null){seed.load(snapshot);var old=seed.habitat().shelter();if(old!=null)old.claim(seed);}
    }
    /** Reserve complete household footprints before changing blocks or adding members. */
    private static List<ShelterPlacement.Plan> plans(YukkuriEntity probe,BlockPos origin,List<Integer> units,int radius,ShelterBlock.Kind kind) {
        ServerLevel level=(ServerLevel)probe.level();
        var candidates=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(origin.offset(-radius,-3,-radius),origin.offset(radius,3,radius)))
            // A burrow centre must itself be an enclosed cavity cell: filtering once here spares every household
            // (and every facing) a full cavity survey of each flat meadow cell, the main cost of a colony start.
            if(pos.distSqr(origin)<=radius*radius&&(kind!=ShelterBlock.Kind.BURROW||NaturalShelter.enclosed(level,pos))&&probe.life().dryStand(pos))candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos->pos.distSqr(origin)));
        var result=new ArrayList<ShelterPlacement.Plan>();var occupied=new HashSet<BlockPos>();
        var failedPaths=new HashSet<List<BlockPos>>();int paths=0;
        for(int size:units) {
            if(size>kind.capacity)return List.of();
            ShelterPlacement.Plan selected=null;
            for(var center:candidates) {

                var footprint=ShelterPlacement.positions(center,kind);
                if(footprint.stream().anyMatch(occupied::contains))continue;
                if(result.stream().anyMatch(plan->Math.abs(center.getX()-plan.center().getX())<kind.radius*2+2
                        &&Math.abs(center.getZ()-plan.center().getZ())<kind.radius*2+2))continue;
                for(var facing:Direction.Plane.HORIZONTAL) {
                    var plan=new ShelterPlacement.Plan(center,kind,facing);
                    if(!ShelterPlacement.canPlace(level,plan))continue;
                    var beds=plan.beds(level);
                    if(beds.size()<size||beds.stream().anyMatch(occupied::contains))continue;
                    var sources=new ArrayList<BlockPos>();sources.add(origin);result.forEach(home->sources.add(home.entrance(level)));
                    sources.sort(Comparator.comparingDouble(pos->pos.distSqr(plan.entrance(level))));
                    int alternatives=0;
                    for(var from:sources) {
                        if(from.distSqr(plan.entrance(level))>16*16||failedPaths.contains(List.of(from,plan.entrance(level))))continue;
                        if(++alternatives>4)break;
                        probe.moveTo(Vec3.atBottomCenterOf(from));probe.setOnGround(true);
                        if(++paths>128){trace("Path budget after "+result.size()+" homes");return List.of();}
                        if(probe.life().reachable(plan.entrance(level),false)){selected=plan;break;}
                        failedPaths.add(List.of(from,plan.entrance(level)));
                    }
                    if(selected!=null)break;
                }
                if(selected!=null)break;
            }
            if(selected==null){trace("No site for household "+result.size()+" of size "+size+" after "+paths+" paths");return List.of();}
            result.add(selected);occupied.addAll(selected.beds(level));
            for(var bed:selected.beds(level))for(var dir:Direction.Plane.HORIZONTAL)occupied.add(bed.relative(dir));
        }
        return result;
    }
    private static final ThreadLocal<String> FAILURE = new ThreadLocal<>();
    /** Why the last spawn on this thread returned nobody (shown by the settlement command). */
    public static String lastFailure() { String s = FAILURE.get(); return s == null ? "unknown" : s; }
    static void failed(String reason) { trace(reason); }
    private static List<YukkuriEntity> fail(String reason) { trace(reason); return List.of(); }
    private static void trace(String message) {
        FAILURE.set(message);
        LoggerFactory.getLogger(SettlementSpawner.class).info("[settlement] {}", message);
    }
    private SettlementSpawner() {}
}
