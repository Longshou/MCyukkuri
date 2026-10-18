package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.SiteLife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Delegated life state; persistence stays in the original YukkuriLife compound. */
final class LifeHome {
    private final YukkuriLife o;
    LifeHome(YukkuriLife o) { this.o = o; }

    private GlobalPos home;
    private boolean naturalHome;
    GlobalPos home() { return home; }

    boolean naturalHome() { return naturalHome; }

    boolean dryStand(BlockPos pos) {
        return o.level().hasChunkAt(pos) && o.level().getFluidState(pos).isEmpty() && o.level().getFluidState(pos.below()).isEmpty()
                && o.level().noCollision(o.entity(), new AABB(pos.getX()+.5-o.entity().getBbWidth()/2,
                        pos.getY(),pos.getZ()+.5-o.entity().getBbWidth()/2,pos.getX()+.5+o.entity().getBbWidth()/2,
                        pos.getY()+o.entity().getBbHeight(),pos.getZ()+.5+o.entity().getBbWidth()/2))
                && o.level().getBlockState(pos.below()).isFaceSturdy(o.level(), pos.below(), Direction.UP);
    }

    boolean restable(BlockPos pos) {
        if(covered(pos))return true;
        var home=o.entity().habitat().shelter();
        return home!=null&&home.owns(o.entity().getUUID())&&home.contains(pos)&&home.sound()&&!o.level().isRainingAt(pos);
    }

    BlockPos nearestDryCover() {
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8),o.entity().blockPosition().offset(8,2,8))) {
            if(!dryStand(pos)||!covered(pos))continue;
            var owner=ShelterBlock.shelter(o.level(),pos);
            if(owner!=null&&!owner.owns(o.entity().getUUID()))continue;
            double candidate=o.entity().distanceToSqr(Vec3.atBottomCenterOf(pos));
            if(candidate<distance&&o.reachable(pos,false)){best=pos.immutable();distance=candidate;}
        }
        return best;
    }

    boolean covered(BlockPos pos) {
        var shelter=ShelterBlock.shelter(o.level(),pos);
        if(shelter!=null)return shelter.rainSheltered(pos);
        // Skylight propagation can lag behind a removed roof; shelter follows actual blocks immediately.
        return o.level().hasChunkAt(pos) && o.level().getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY();
    }

    boolean rainOutside() {
        return o.level().isRaining() && o.level().isRainingAt(o.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,o.entity().blockPosition()));
    }

    BlockPos entrance(BlockPos nest) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = nest.relative(direction);
            if (dryStand(pos) && covered(pos)) return pos;
        }
        return null;
    }

    boolean assignHome(GlobalPos selected) {
        if (!selected.dimension().equals(o.level().dimension()) || !o.level().hasChunkAt(selected.pos())
                || o.entity().distanceToSqr(Vec3.atCenterOf(selected.pos())) > 256) return false;
        var wild=ShelterBlock.shelter(o.level(),selected.pos());
        if(wild!=null)return o.entity().habitat().choose(wild);
        if (!(o.level().getBlockEntity(selected.pos()) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.NEST) return false;
        if (o.level().getBlockState(selected.pos()).is(ModBlocks.PREDATOR_NEST.get()) && !o.homeProvider().acceptsPredatorNest()) return false;
        BlockPos stand = entrance(selected.pos());
        if (stand == null || !(o.reachable(stand, false) || o.homeProvider().canFlyTo(o.entity(), stand))
                || care.resident() != null && !care.resident().equals(o.entity().getUUID())) return false;
        releaseHome();
        if (!care.claim(o.entity().getUUID())) return false;
        o.entity().habitat().release();
        home = selected; naturalHome = false; o.deferHomeMaintenance(); o.entity().setPersistenceRequired(); return true;
    }

    boolean assignNaturalHome(BlockPos selected) {
        var shelter=ShelterBlock.shelter(o.level(),selected);
        if(shelter!=null&&!shelter.owns(o.entity().getUUID()))return false;
        if (o.entity().distanceToSqr(Vec3.atBottomCenterOf(selected)) > 256 || !dryStand(selected) || !(shelter!=null&&shelter.owns(o.entity().getUUID())&&shelter.sound()||covered(selected))
                || !o.reachable(selected, false)) return false;
        releaseHome(); home = GlobalPos.of(o.level().dimension(), selected.immutable()); naturalHome = true;
        o.deferHomeMaintenance(); o.entity().setPersistenceRequired(); return true;
    }

    void releaseHome() {
        if (home != null && !naturalHome && home.dimension().equals(o.level().dimension()) && o.level().hasChunkAt(home.pos())
                && o.level().getBlockEntity(home.pos()) instanceof CareBlockEntity care) care.release(o.entity().getUUID());
        home = null; naturalHome = false;
    }

    BlockPos homeStand() {
        if(o.entity().habitat().hasMemory())return o.entity().habitat().homeStand();
        var provider=o.homeProvider();
        if(provider.homeStand(o.entity())!=null)return provider.homeStand(o.entity());
        if (home == null || !home.dimension().equals(o.level().dimension()) || !o.level().hasChunkAt(home.pos())) return null;
        if (naturalHome) return ShelterBlock.shelter(o.level(),home.pos())==null
                && dryStand(home.pos()) && covered(home.pos()) ? home.pos() : null;
        if (o.level().getBlockEntity(home.pos()) instanceof CareBlockEntity care && o.entity().getUUID().equals(care.resident())) return entrance(home.pos());
        return null;
    }

    /** True only for budget deferral: the caller retries in 20 ticks instead of the ordinary 200. */
    boolean maintainHome() {
        if (o.homeProvider().homeStand(o.entity()) != null) return false;
        if(o.entity().habitat().hasMemory()) {
            if(o.entity().habitat().unavailable()||o.entity().habitat().homeStand()!=null)return false;
            o.entity().habitat().release();
        }
        if (home != null) {
            // Unloaded or other-dimension homes remain memories, not destroyed homes.
            if (!home.dimension().equals(o.level().dimension()) || !o.level().hasChunkAt(home.pos())) return false;
            if (homeStand() != null) return false;
            releaseHome();
        }
        var search=o.homeProvider().findHome(o.entity());
        if(search==HomeProvider.SearchResult.DEFERRED)return true;
        if(search==HomeProvider.SearchResult.FOUND)return false;
        BlockPos nest = nearestCare(CareBlock.Kind.NEST, false);
        if (nest != null && assignHome(GlobalPos.of(o.level().dimension(), nest))) return false;
        // A wild individual without a recognised home is a vagrant: any dry roof is only a refuge for the night, never an address.
        if (o.entity().habitat().wildLiver()) return false;
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8), o.entity().blockPosition().offset(8,2,8))) {
            if(ShelterBlock.shelter(o.level(),scan)!=null)continue;
            if (!dryStand(scan) || !covered(scan)) continue;
            double candidate = o.entity().distanceToSqr(Vec3.atBottomCenterOf(scan));
            if (candidate < distance && o.reachable(scan, false)) { best = scan.immutable(); distance = candidate; }
        }
        if (best != null) { home = GlobalPos.of(o.level().dimension(), best); naturalHome = true; }
        return false;
    }

    BlockPos nearestCare(CareBlock.Kind kind, boolean requireFood) {
        BlockPos best = null; double distance = Double.MAX_VALUE;
        for (BlockPos scan : BlockPos.betweenClosed(o.entity().blockPosition().offset(-8,-2,-8), o.entity().blockPosition().offset(8,2,8))) {
            if (!o.level().hasChunkAt(scan) || !(o.level().getBlockEntity(scan) instanceof CareBlockEntity care) || care.kind() != kind) continue;
            if (requireFood && !care.hasFood(o::acceptsFood) || kind == CareBlock.Kind.TOILET && care.waste() >= care.capacity()
                    || kind == CareBlock.Kind.NEST && care.resident() != null && !care.resident().equals(o.entity().getUUID())) continue;
            double candidate = o.entity().distanceToSqr(Vec3.atCenterOf(scan));
            if (candidate < distance) {
                boolean access = false;
                if (kind == CareBlock.Kind.TOILET) {
                    access = ToiletContact.facilitySeat(o.entity(), scan) != null && o.reachable(scan.above(), false);
                } else if (kind == CareBlock.Kind.NEST) {
                    BlockPos stand = entrance(scan); access = stand != null && o.reachable(stand,false);
                } else for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos stand = scan.relative(direction);
                    if (dryStand(stand) && o.reachable(stand,false)) { access=true; break; }
                }
                if (access) { best = scan.immutable(); distance = candidate; }
            }
        }
        return best == null && kind == CareBlock.Kind.TOILET ? siteToilet() : best;
    }

    BlockPos siteToilet() {
        var site = SiteLife.of(o.entity());
        if (site == null) return null;
        BlockPos best = null; double distance = 24 * 24;
        for (BlockPos at : SiteLife.latrines(site)) {
            if (!o.level().hasChunkAt(at) || !(o.level().getBlockEntity(at) instanceof CareBlockEntity care) || care.kind() != CareBlock.Kind.TOILET
                    || care.waste() >= care.capacity()) continue;
            double candidate = o.entity().distanceToSqr(Vec3.atCenterOf(at));
            if (candidate < distance && ToiletContact.facilitySeat(o.entity(), at) != null && o.reachable(at.above(), false)) { best = at.immutable(); distance = candidate; }
        }
        return best;
    }

    boolean seesWater(BlockPos water){
        var surface=new Vec3(water.getX()+.5,water.getY()+o.level().getFluidState(water).getHeight(o.level(),water)+.05,water.getZ()+.5);
        var hit=o.level().clip(new ClipContext(o.entity().getEyePosition(),surface,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,o.entity()));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(water);
    }

    BlockPos drinkStand(BlockPos water){
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var side:Direction.Plane.HORIZONTAL)for(int rise=0;rise<=1;rise++){
            var stand=water.relative(side).above(rise);double d=o.entity().distanceToSqr(Vec3.atBottomCenterOf(stand));
            if(d<distance&&dryStand(stand)&&o.reachable(stand,false)){best=stand;distance=d;}
        }
        return best;
    }

    boolean hasReachableDrink() { return nearestDrink() != null; }

    BlockPos nearestDrink(){
        BlockPos best=null;double distance=Double.MAX_VALUE;
        for(var water:BlockPos.betweenClosed(o.entity().blockPosition().offset(-6,-2,-6),o.entity().blockPosition().offset(6,1,6))){
            if(!o.level().hasChunkAt(water)||!o.level().getFluidState(water).is(FluidTags.WATER))continue;
            double d=o.entity().distanceToSqr(Vec3.atCenterOf(water));
            if(d<distance&&drinkStand(water)!=null){best=water.immutable();distance=d;}
        }
        return best==null?siteWater():best;
    }

    BlockPos siteWater(){
        var site=SiteLife.of(o.entity());
        if(site==null)return null;
        var water=SiteLife.water(site,o.entity().blockPosition());
        return water!=null&&water.distSqr(o.entity().blockPosition())<=32*32&&o.level().hasChunkAt(water)
                &&o.level().getFluidState(water).is(FluidTags.WATER)&&drinkStand(water)!=null?water.immutable():null;
    }
    void save(CompoundTag tag) { if (home != null) { tag.putString("HomeDimension", home.dimension().location().toString()); tag.putLong("HomePos",
            home.pos().asLong()); tag.putBoolean("NaturalHome", naturalHome); } }
    void load(CompoundTag tag) {
        home = null;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("HomeDimension"));
        if (dimension!=null && tag.contains("HomePos")) home=GlobalPos.of(ResourceKey.create(Registries.DIMENSION,dimension),BlockPos.of(tag.getLong("HomePos")));
        naturalHome=tag.getBoolean("NaturalHome");
    }

}
