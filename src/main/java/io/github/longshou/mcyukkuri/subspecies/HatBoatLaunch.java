package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.GripRim;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.State;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Stateless custody lookup and retrieval operations on the same physical hat and accessory token. */
final class HatBoatLaunch {
    private HatBoatLaunch() {}

    /** The hull this owner rides or has deployed; a pure read of her {@code HatCustody}. */
    static HatBoatEntity find(YukkuriEntity y) {
        if (y.getVehicle() instanceof HatBoatEntity boat && boat.ownsPhysicalHat(y)) return boat;
        return owned(y);
    }

    /** The hull her custody names while the hat is a hull (deployed or adrift), if it is loaded; no scan, no side effect. */
    static HatBoatEntity owned(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel server)) return null;
        UUID id = y.life().custody().boat();
        return id != null && server.getEntity(id) instanceof HatBoatEntity boat && boat.ownsPhysicalHat(y) ? boat : null;
    }

    static boolean retrieveAfloat(HatBoatEntity boat, YukkuriEntity child, HatBoatEntity home) {
        return retrieveAfloat(boat, child, home, false);
    }

    static boolean retrieveAfloat(HatBoatEntity boat, YukkuriEntity child, HatBoatEntity home, boolean reachFallback) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || !(boat.isAfloat() || boat.state()==State.BEACHED)
                || boat.isVehicle() || boat.hasIncoming() || !child.getUUID().equals(boat.ownerYukkuri())
                || !WaterIncidents.settledAboard(child,home) || !home.young(child)
                || retrievalGrip(boat, child)==null) return false;
        return retireAfloat(boat, child);
    }
    /** A captain's accepted braid contact returns the same physical hat to its owner, never a remote replacement. */
    static boolean retrieveForChild(HatBoatEntity boat, YukkuriEntity child, HatBoatEntity home, YukkuriEntity captain) {
        if (boat.level().isClientSide || !(boat.isAfloat() || boat.state() == State.BEACHED) || boat.isVehicle() || boat.hasIncoming()
                || !WaterIncidents.settledAboard(captain,home) || !WaterIncidents.settledAboard(child,home)
                || !home.young(child) || !boat.ownsPhysicalHat(child) || !captain.aquaticLife().familyLife().reclaimHandoff(boat)) return false;
        return retireAfloat(boat, child);
    }
    private static boolean retireAfloat(HatBoatEntity boat, YukkuriEntity child) {
        var token=boat.launchToken();int cargo=boat.cargo().count();boolean paddle=boat.cargo().paddle();
        if (!child.life().returnAccessory(boat.ownerYukkuri(), boat.launchToken(), null)) return false;
        child.aquaticLife().firstVoyage().retainHat(boat.launchToken(),boat.cargo().save());
        boat.cargo().load(new CompoundTag());boat.syncCargo(boat.cargo().save());boat.launchToken(null);
        boat.state(State.RESCUED); boat.helm().stop("retired");
        GameplayLog.event(child,"headwear","reclaim handoff hat={} token={} owner={} cargo={} -> {} paddle={} -> {} accessory={}",
                boat.getUUID(),token,child.getUUID(),cargo,boat.cargo().count(),paddle,boat.cargo().paddle(),child.life().hasAccessory());
        return true;
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

    static boolean retrieve(HatBoatEntity boat, YukkuriEntity owner) {
        if (boat.level().isClientSide || !boat.catches() || !owner.getUUID().equals(boat.ownerYukkuri()) || owner.distanceToSqr(boat) > 16) return false;
        if (boat.hasIncoming()||!boat.getPassengers().isEmpty()||owner.family().crew().busy()) return false;
        if (!owner.life().returnAccessory(boat.ownerYukkuri(), boat.launchToken(), null)) return false;
        boat.clearCrew();
        boat.state(State.RESCUED); return true;
    }

    static boolean board(HatBoatEntity boat, YukkuriEntity owner) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || !owner.getUUID().equals(boat.ownerYukkuri()) || owner.distanceToSqr(boat) > 4
                || !boat.isAfloat() || !owner.life().matchesAccessory(boat.ownerYukkuri(), boat.launchToken())) return false;
        if (!owner.startRiding(boat, true)) return false;
        owner.life().boardCustody(boat.getUUID(), boat.ownerYukkuri(), boat.launchToken());
        owner.setYRot(boat.getYRot()); owner.yBodyRot=boat.getYRot(); owner.setYHeadRot(boat.getYRot()); return true;
    }
}
