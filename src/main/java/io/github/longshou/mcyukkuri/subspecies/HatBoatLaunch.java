package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.GripRim;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Stateless launch and retrieval operations on the same physical hat and accessory token. */
final class HatBoatLaunch {
    private HatBoatLaunch() {}

    static HatBoatEntity find(YukkuriEntity y) {
        if (y.getVehicle() instanceof HatBoatEntity boat && boat.ownsPhysicalHat(y)) {
            y.life().restoreCustody(boat.getUUID(), boat.ownerYukkuri(), boat.launchToken());
            return boat;
        }
        return owned(y);
    }

    static HatBoatEntity owned(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel server)) return null;
        if (y.life().accessoryCustodyEnded()) return null;
        UUID id = y.life().custodyBoat();
        if (id == null) id = y.subspecies().boatId();
        if (id != null && server.getEntity(id) instanceof HatBoatEntity boat && boat.ownsPhysicalHat(y)) {
            y.life().confirmCustody(boat.getUUID(), boat.ownerYukkuri(), boat.launchToken());
            return boat;
        }
        // Old CRADLE/MOORED saves did not record the boat UUID on the owner. Adopt only a nearby exact owner+token match.
        if (y.life().custodyBoat() != null) return null;
        for (var boat : server.getEntitiesOfClass(HatBoatEntity.class, y.getBoundingBox().inflate(8), candidate -> candidate.ownsPhysicalHat(y))) {
            y.life().confirmCustody(boat.getUUID(), boat.ownerYukkuri(), boat.launchToken());
            return boat;
        }
        return null;
    }

    static void abortDeployment(HatBoatEntity boat, YukkuriEntity owner) {
        if (boat.level().isClientSide || boat.isRemoved()) return;
        for (var rider : List.copyOf(boat.getPassengers())) rider.stopRiding();
        boat.clearCrew(); boat.hullMotion.delayedDrops.clear();
        if (owner != null && owner.getUUID().equals(boat.ownerYukkuri()) && boat.launchToken() != null) {
            boat.cargo().unload(owner, boat.launchToken());
            owner.life().returnAccessory(boat.ownerYukkuri(), boat.launchToken(), null);
            owner.aquaticLife().landed(); owner.subspecies().boatId(null);
        } else boat.cargo().spill();
        boat.launchToken(null); boat.state(State.RESCUED); boat.stopPaddling();
    }

    static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water) {
        return createFor(owner, water, false);
    }

    /**
     * Spawn-only water creation: the owner begins aboard the real newly created hull. This deliberately bypasses the
     * shore-launch pose, but keeps the same accessory token/custody transaction and rolls it back if boarding fails.
     */
    static HatBoatEntity createAfloatAndBoard(YukkuriEntity owner, BlockPos water) {
        if (!(owner.level() instanceof ServerLevel level)) return null;
        String path = "afloat";
        if (owner.getVariant() != YukkuriVariant.MARISA || owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA)
            return refused(owner, path, water, "not_aquatic_marisa");
        if (!owner.aquaticLife().firstVoyage().mayLaunch()) return refused(owner, path, water, "first_voyage_ineligible");
        if (owner.subspecies().boatId() != null) return refused(owner, path, water, "existing_boat");
        if (!owner.isAlive()) return refused(owner, path, water, "not_alive");
        if (owner.isPassenger()) return refused(owner, path, water, "passenger");
        if (owner.isVehicle()) return refused(owner, path, water, "carrying_passengers");
        if (!owner.life().hasAccessory()) return refused(owner, path, water, "no_accessory");
        if (owner.operations().active()) return refused(owner, path, water, "operation_active");
        if (owner.distanceToSqr(Vec3.atCenterOf(water)) > 14*14) return refused(owner, path, water, "water_out_of_range");
        if (!level.hasChunkAt(water)) return refused(owner, path, water, "water_unloaded");
        if (!HatBoatRoute.calmSurface(level, water)) return refused(owner, path, water, "water_not_calm");
        var boat=ModEntities.HAT_BOAT.get().create(level);
        if(boat==null)return refused(owner, path, water, "entity_creation_failed");
        boat.syncHullScale(HatVessel.hatScale(owner));boat.setPos(HatBoatRoute.surface(level,water));boat.setYRot(owner.yBodyRot);
        // The spawn owner can already occupy this water surface; exclude that body but retain every other obstruction.
        if(!hullClear(level,owner,boat,owner,path,water))return refused(owner, path, water, "hull_collision");
        boat.syncOwner(Optional.of(owner.getUUID()));boat.syncAccessoryIdentity(owner);boat.syncHullScale(HatVessel.hatScale(owner));
        if(!level.addFreshEntity(boat))return refused(owner, path, water, "entity_add_failed");
        boolean boarded=false;
        String failure = "accessory_transfer_failed";
        try {
            var retainedToken=owner.aquaticLife().firstVoyage().retainedHatToken();
            boat.launchToken(owner.life().stowAccessory(retainedToken,boat.getUUID()));
            if (boat.launchToken()!=null) failure = "boarding_refused";
            boarded=boat.launchToken()!=null&&owner.startRiding(boat,true);
        }
        finally {
            if(!boarded){
                var token=boat.launchToken();boat.launchToken(null);
                try { if(token!=null)owner.life().returnAccessory(owner.getUUID(),token,null); }
                finally { boat.discard(); }
            }
        }
        if(!boarded)return refused(owner, path, water, failure);
        owner.subspecies().boatId(boat.getUUID());owner.setPersistenceRequired();owner.getNavigation().stop();
        owner.setYRot(boat.getYRot());owner.yBodyRot=boat.getYRot();owner.setYHeadRot(boat.getYRot());
        var seat=boat.toWorld(boat.ownerSeat(owner));owner.moveTo(seat.x,seat.y,seat.z,boat.getYRot(),owner.getXRot());
        owner.aquaticLife().firstVoyage().restoreHatCargo(boat);owner.aquaticLife().equip(boat);boat.heldTicks=60;
        GameplayLog.event(owner, "aquatic", "afloat hull created and owner boarded boat={} water={} food={} paddle={}",
                boat.getUUID(), water, boat.cargo().count(), boat.cargo().paddle());
        return boat;
    }

    static boolean afloatSpawnClear(YukkuriEntity owner,BlockPos water) {
        if(!HatBoatRoute.calmSurface(owner.level(),water))return false;
        var dimensions=ModEntities.HAT_BOAT.get().getDimensions().scale(HatVessel.hatScale(owner)/HatVessel.AQUATIC_SCALE);
        return owner.level().noCollision(owner,dimensions.makeBoundingBox(HatBoatRoute.surface(owner.level(),water)));
    }

    /** Read-only candidate check using the same hat-off placement and hull size as an animated shore launch. */
    static boolean bankLaunchClear(YukkuriEntity owner,BlockPos bank,BlockPos water) {
        var feet=Vec3.atBottomCenterOf(bank);var surface=HatBoatRoute.surface(owner.level(),water);
        var delta=surface.subtract(feet);
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        var placement=HatVessel.placement(true,HatVessel.Role.SELF);
        var offset=placement.offset().scale(owner.growthScale()*owner.getScale()/16).yRot((float)Math.toRadians(180-yaw));
        var at=new Vec3(feet.x+offset.x,surface.y,feet.z+offset.z);
        if(!HatBoatRoute.calmSurface(owner.level(),BlockPos.containing(at.x,water.getY(),at.z)))return false;
        var dimensions=ModEntities.HAT_BOAT.get().getDimensions().scale(HatVessel.hatScale(owner)/HatVessel.AQUATIC_SCALE);
        return owner.level().noCollision(owner,dimensions.makeBoundingBox(at));
    }

    static HatBoatEntity launchFromBank(YukkuriEntity owner, BlockPos water) {
        if (owner.level().isClientSide) return null;
        if (!HatBoatRoute.calmSurface(owner.level(), water)) return refused(owner, "bank", water, "water_not_calm");
        var delta=HatBoatRoute.surface(owner.level(),water).subtract(owner.position());
        float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
        owner.setYRot(yaw); owner.yBodyRot=yaw; owner.setYHeadRot(yaw);
        return createFor(owner, water, true);
    }

    static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated) {
        return createFor(owner,water,animated,null);
    }

    static HatBoatEntity launchFromFather(YukkuriEntity child, HatBoatEntity home, BlockPos water) {
        if (child.getVehicle()!=home || !home.protects(child) || !home.onVoyageBerth(child) || home.voyageBerth(child)==null
                || !(home.ownerYukkuri().equals(child.family().father()) || home.ownerYukkuri().equals(child.family().mother()))
                || !child.aquaticLife().firstVoyage().launchingFrom(home)) return null;
        return createFor(child,water,true,home);
    }

    static HatBoatEntity createFor(YukkuriEntity owner, BlockPos water, boolean animated, HatBoatEntity home) {
        // Only the aquatic Marisa sails her hat; land Marisa never take to the water (user correction 2026-10-14).
        if (!(owner.level() instanceof ServerLevel level)) return null;
        String path = home == null ? "bank" : "parent_hat";
        if (owner.getVariant() != YukkuriVariant.MARISA || owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA)
            return refused(owner, path, water, "not_aquatic_marisa");
        if (!owner.aquaticLife().firstVoyage().mayLaunch()) return refused(owner, path, water, "first_voyage_ineligible");
        if (owner.subspecies().boatId() != null) return refused(owner, path, water, "existing_boat");
        if (!owner.isAlive()) return refused(owner, path, water, "not_alive");
        if (owner.isPassenger() && home==null) return refused(owner, path, water, "passenger");
        if (owner.isVehicle()) return refused(owner, path, water, "carrying_passengers");
        if (!owner.life().hasAccessory()) return refused(owner, path, water, "no_accessory");
        if (owner.isInWaterOrBubble()) return refused(owner, path, water, "owner_in_water");
        if (home==null && !level.getFluidState(owner.blockPosition()).isEmpty()) return refused(owner, path, water, "feet_not_dry");
        if (owner.operations().active()) return refused(owner, path, water, "operation_active");
        if (owner.distanceToSqr(Vec3.atCenterOf(water)) > 9) return refused(owner, path, water, "water_out_of_range");
        if (!level.hasChunkAt(water)) return refused(owner, path, water, "water_unloaded");
        var fluid = level.getFluidState(water);
        if (!HatBoatRoute.calmSurface(level, water)) return refused(owner, path, water, "water_not_calm");
        var boat = ModEntities.HAT_BOAT.get().create(level);
        if (boat == null) return refused(owner, path, water, "entity_creation_failed");
        boat.syncHullScale(HatVessel.hatScale(owner));
        boat.setPos(water.getX()+.5, water.getY()+fluid.getHeight(level, water)+.025, water.getZ()+.5);
        var away=boat.position().subtract(owner.position()).multiply(1,0,1).normalize().scale(.10);
        boat.setPos(boat.position().add(away));
        float heading=owner.yBodyRot;
        if (animated) {
            // Meet the authored hat-off clip exactly in X/Z and orientation before the owner's hop starts.
            var placement=HatVessel.placement(true,HatVessel.Role.SELF);
            var offset=placement.offset().scale(owner.growthScale()*owner.getScale()/16).yRot((float)Math.toRadians(180-owner.yBodyRot));
            boat.setPos(owner.getX()+offset.x,boat.getY(),owner.getZ()+offset.z);
            if (!HatBoatRoute.calmSurface(level,BlockPos.containing(boat.getX(),water.getY(),boat.getZ()))) return refused(owner, path, water, "hat_offset_not_calm");
            heading+=placement.yaw();
        }
        if (!hullClear(level,owner,boat,boat,path,water)) return refused(owner, path, water, "hull_collision");
        boat.syncOwner(Optional.of(owner.getUUID())); boat.syncAccessoryIdentity(owner);boat.syncHullScale(HatVessel.hatScale(owner)); boat.setYRot(heading);
        if (!level.addFreshEntity(boat)) return refused(owner, path, water, "entity_add_failed");
        var retainedToken=owner.aquaticLife().firstVoyage().retainedHatToken();
        boat.launchToken(owner.life().stowAccessory(retainedToken, boat.getUUID()));
        if (boat.launchToken() == null || !animated && !owner.startRiding(boat, true)) {
            String reason = boat.launchToken() == null ? "accessory_transfer_failed" : "boarding_refused";
            if (boat.launchToken() != null) owner.life().returnAccessory(owner.getUUID(), boat.launchToken(), null);
            boat.discard(); return refused(owner, path, water, reason);
        }
        owner.subspecies().boatId(boat.getUUID()); owner.setPersistenceRequired(); owner.getNavigation().stop();
        owner.aquaticLife().firstVoyage().restoreHatCargo(boat);
        owner.aquaticLife().equip(boat); boat.heldTicks = 60;
        if (home != null) {
            // During the real hat-off launch the child still rides the parent and can hand over provisions.
            // Restored or carried provisions are used first; a parent never loses its final serving.
            if(boat.cargo().count()==0&&home.protects(owner)&&owner.aquaticLife().firstVoyage().launchingFrom(home))
                home.cargo().transferProvisionsTo(boat.cargo(),owner,2);
            // False can mean that the child has only started approaching the parent's rim.
            // BOARD owns retries and cancellation; the same physical hat must remain deployed meanwhile.
            owner.family().transferHat(home,boat);
        } else if (animated) owner.family().embark(boat);
        GameplayLog.event(owner, "aquatic", "hull deployed path={} boat={} water={} ownerAboard={} food={} paddle={}",
                path, boat.getUUID(), water, owner.getVehicle()==boat, boat.cargo().count(), boat.cargo().paddle());
        return boat;
    }

    /** The 1.21.1 CollisionGetter.noCollision sequence, retaining the first actual rejecting result.
     *  Do not rerun collision queries after failure just to diagnose a different world snapshot. */
    private static boolean hullClear(ServerLevel level,YukkuriEntity owner,HatBoatEntity boat,Entity context,String path,BlockPos water){
        var box=boat.getBoundingBox();
        for(var shape:level.getBlockCollisions(context,box))if(!shape.isEmpty()){
            GameplayLog.limited(owner,"aquatic","launch.collision."+path,200,
                    "hull collision path={} kind=block water={} hull={} firstShape={} context={}",
                    path,water,box,shape.bounds(),context.getUUID());
            return false;
        }
        var entities=level.getEntityCollisions(context,box);
        if(!entities.isEmpty()){
            GameplayLog.limited(owner,"aquatic","launch.collision."+path,200,
                    "hull collision path={} kind=entity water={} hull={} shapes={} context={}",
                    path,water,box,entities.size(),context.getUUID());
            return false;
        }
        var border=level.getWorldBorder();
        var shape=border.isInsideCloseToBorder(context,box)?border.getCollisionShape():null;
        if(shape!=null&&Shapes.joinIsNotEmpty(shape,Shapes.create(box),BooleanOp.AND)){
            GameplayLog.limited(owner,"aquatic","launch.collision."+path,200,
                    "hull collision path={} kind=world_border water={} hull={} context={}",path,water,box,context.getUUID());
            return false;
        }
        return true;
    }

    private static HatBoatEntity refused(YukkuriEntity owner, String path, BlockPos water, String reason) {
        GameplayLog.limited(owner, "aquatic", "launch."+path+"."+reason, 200,
                "hat launch refused path={} reason={} water={} distanceSquared={} boatId={} accessory={} operation={}",
                path, reason, water, owner.distanceToSqr(Vec3.atCenterOf(water)), owner.subspecies().boatId(),
                owner.life().hasAccessory(), owner.operations().active());
        return null;
    }

    static boolean retrieveAfloat(HatBoatEntity boat, YukkuriEntity child, HatBoatEntity home) {
        return retrieveAfloat(boat, child, home, false);
    }

    static boolean retrieveAfloat(HatBoatEntity boat, YukkuriEntity child, HatBoatEntity home, boolean reachFallback) {
        if (boat.level().isClientSide || boat.capsizing() || !(boat.isAfloat() || boat.state()==State.CAPSIZED || boat.state()==State.BEACHED)
                || boat.isVehicle() || boat.hasIncoming() || !child.getUUID().equals(boat.ownerYukkuri())
                || !home.protects(child) || !home.young(child)
                || retrievalGrip(boat, child)==null && !(reachFallback && boat.ownsPhysicalHat(child) && boat.distanceToSqr(child)<=25)
                || !child.life().returnAccessory(boat.ownerYukkuri(),boat.launchToken(),null)) return false;
        child.aquaticLife().firstVoyage().retainHat(boat.launchToken(),boat.cargo().save());
        boat.cargo().load(new CompoundTag());boat.syncCargo(boat.cargo().save());boat.launchToken(null);
        child.subspecies().boatId(null);
        boat.state(State.RESCUED); boat.stopPaddling(); boat.clearTowTicks(); return true;
    }

    static Vec3 retrievalGrip(HatBoatEntity boat, YukkuriEntity actor){
        if(actor==null||!boat.ownsPhysicalHat(actor)||boat.isVehicle()||boat.hasIncoming()||boat.capsizing())return null;
        Vec3 nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(var local:GripRim.points(boat.mode())){var at=boat.tiltedWorld(local);double d=actor.getBoundingBox().getCenter().distanceToSqr(at);if(d<distance){distance=d;nearest=at;}}
        return boat.reachableGrip(actor,nearest);
    }

    static Vec3 retrievalApproach(HatBoatEntity boat, HatBoatEntity parentHat,YukkuriEntity child){
        if(parentHat==null||parentHat==boat||child==null||!boat.ownsPhysicalHat(child)||!parentHat.protects(child)
                ||!parentHat.young(child)||boat.isVehicle()||boat.hasIncoming()||boat.capsizing()||boat.selfRescueHeld())return null;
        var toward=parentHat.position().subtract(boat.position()).multiply(1,0,1).normalize();
        if(toward.lengthSqr()<.01)toward=child.position().subtract(boat.position()).multiply(1,0,1).normalize();
        if(toward.lengthSqr()<.01)return null;
        double theirs=Math.max(boat.brimReachToward(toward),boxReach(boat, toward));
        double ours=Math.max(parentHat.brimReachToward(toward.scale(-1)),parentHat.boxReach(toward.scale(-1)));
        var at=boat.position().add(toward.scale(theirs+ours+.035));
        return new Vec3(at.x,parentHat.getY(),at.z);
    }

    static double boxReach(HatBoatEntity boat, Vec3 toward){
        double x=boat.getBoundingBox().getXsize()*.5,z=boat.getBoundingBox().getZsize()*.5;
        return Math.min(Math.abs(toward.x)<1e-8?Double.POSITIVE_INFINITY:x/Math.abs(toward.x),
                Math.abs(toward.z)<1e-8?Double.POSITIVE_INFINITY:z/Math.abs(toward.z));
    }

    static HatBoatEntity setDown(YukkuriEntity owner, Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) {
        if (!(owner.level() instanceof ServerLevel level) || owner.getVariant() != YukkuriVariant.MARISA || !owner.isAlive()
                || owner.isPassenger() || !owner.life().hasAccessory() || owner.subspecies().boatId() != null) return null;
        var spot = BlockPos.containing(at);
        if (!level.hasChunkAt(spot)) return null;
        double y;
        if (mode.afloat()) {
            // The water surface below the bank she stands on.
            BlockPos surface = null;
            for (int down = 0; down < 3 && surface == null; down++)
                if (level.getFluidState(spot.below(down)).is(FluidTags.WATER) && level.getFluidState(spot.below(down).above()).isEmpty()) surface = spot.below(down);
            if (surface == null) return null;
            y = surface.getY()+level.getFluidState(surface).getHeight(level, surface)+.025;
        } else {
            if (!level.getFluidState(spot).isEmpty() || !level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP)) return null;
            y = spot.getY();
        }
        var hat = ModEntities.HAT_BOAT.get().create(level);
        if (hat == null) return null;
        hat.setPos(at.x, y, at.z); hat.setYRot(yaw); hat.launchRole(role); hat.moorAt(hat.position());
        hat.syncOwner(Optional.of(owner.getUUID())); hat.syncAccessoryIdentity(owner);hat.syncHullScale(HatVessel.hatScale(owner));
        hat.state(mode.afloat() ? State.MOORED : State.CRADLE);
        if (!level.addFreshEntity(hat)) return null;
        hat.launchToken(owner.life().stowAccessory(null, hat.getUUID()));
        if (hat.launchToken() == null) { hat.discard(); return null; }
        return hat;
    }

    static boolean retrieve(HatBoatEntity boat, YukkuriEntity owner) {
        if (boat.level().isClientSide || !boat.catches() || !owner.getUUID().equals(boat.ownerYukkuri()) || owner.distanceToSqr(boat) > 16) return false;
        if (boat.hasIncoming()||!boat.getPassengers().isEmpty()||owner.family().cradlePickup().active()) return false;
        if (!owner.life().returnAccessory(boat.ownerYukkuri(), boat.launchToken(), null)) return false;
        boat.clearCrew();
        boat.state(State.RESCUED); return true;
    }

    static boolean board(HatBoatEntity boat, YukkuriEntity owner) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || !owner.getUUID().equals(boat.ownerYukkuri()) || owner.distanceToSqr(boat) > 4
                || !boat.isAfloat() || !owner.life().matchesAccessory(boat.ownerYukkuri(), boat.launchToken())) return false;
        if (!owner.startRiding(boat, true)) return false;
        owner.life().restoreCustody(boat.getUUID(), boat.ownerYukkuri(), boat.launchToken());
        owner.setYRot(boat.getYRot()); owner.yBodyRot=boat.getYRot(); owner.setYHeadRot(boat.getYRot()); return true;
    }
}
