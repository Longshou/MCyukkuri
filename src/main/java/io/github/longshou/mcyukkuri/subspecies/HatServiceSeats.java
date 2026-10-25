package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatCarry.CarrySearch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Temporary care, toilet and practice reservations on the vessel's shared seat ledger. */
final class HatServiceSeats {
    private final HatBoatEntity boat;
    /** A care action may temporarily move a young and the seated owner, then restores the normal layout. */
    record ServiceSeat(double[] original, String originalRegion, double[] service, Vec3 ownerService, boolean rearCare, boolean toiletReady) {}
    record ReturnRetry(long geometryStamp,long at) {}
    final Map<UUID,ServiceSeat> serviceSeats = new HashMap<>();
    final Map<UUID,ServiceSeat> returningSeats = new HashMap<>();
    final Map<UUID,ReturnRetry> returnRetries = new HashMap<>();
    /** The owner also uses a supported rear brim seat for ordinary on-water hygiene. */
    boolean ownerToiletSeat;
    String carePlanDebug="not planned";

    HatServiceSeats(HatBoatEntity boat) { this.boat = boat; }

    String carePlanDebug(){return carePlanDebug+"; carry="+boat.carry.carryPlanDebug
            +"; returning="+returningSeats.size()+" service="+serviceSeats.size()
            +" phase="+(boat.carry.carryTrip==null?"none":boat.carry.carryTrip.phase);}

    boolean prepareCareSeat(YukkuriEntity child, YukkuriEntity parent) {
        return prepareServiceSeat(child, parent, false);
    }

    boolean prepareRearCareSeat(YukkuriEntity child, YukkuriEntity parent) {
        return prepareServiceSeat(child, parent, true);
    }

    boolean prepareServiceSeat(YukkuriEntity child, YukkuriEntity parent, boolean rearCare) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || child == null || parent == null || parent != boat.owner() || child.getVehicle() != boat
                || parent.getVehicle() != boat || !boat.careRecipient(child) || !boat.protects(parent) || !boat.protects(child)
                || !boat.isAfloat() || boat.flooding != 0 || boat.hasIncoming() || ownerToiletSeat || boat.departure.departureYielding!=null ||
                        child.getUUID().equals(boat.departure.voyageYoung)) return false;
        var held = serviceSeats.get(child.getUUID());
        if (held != null && held.ownerService() != null) {
            if (held.rearCare() != rearCare) return false;
            // Unfolding may require a real withdrawal; restore the reserved care station afterwards.
            if (boat.carry.carryTrip == null && !boat.atSeat(parent, held.ownerService())) {
                if (!boat.moveOwnerSeat(parent, held.ownerService(), false)) {
                    releaseCareSeat(child.getUUID());
                    return false;
                }
                boat.syncSeats();
            }
            return true;
        }
        if (boat.carry.carryTrip != null) return false;
        if (serviceSeats.keySet().stream().anyMatch(id -> !id.equals(child.getUUID()))) return false;
        if (held == null) held = returningSeats.get(child.getUUID());
        // Feeding takes precedence over a child's voluntary movement.  Keep that movement's original
        // berth, but replace its destination from the child's current in-hat position.
        var original = held != null ? held.original() : boat.seatState.residentSeat(child.getUUID());
        if (original == null) return false;
        ServiceSeat best = serviceSeatFor(child, parent, original, rearCare);
        if (best == null) { if(boat.cargo().innerCount()>0)boat.cargo().moveAside(boat.cargo().count()); return false; }
        if (!moveCarePair(child, parent, best.service(), best.ownerService())) {
            carePlanDebug+=" commit blocked";if(boat.cargo().innerCount()>0)boat.cargo().moveAside(boat.cargo().count());return false;
        }
        serviceSeats.put(child.getUUID(), best);
        returningSeats.remove(child.getUUID());
        child.childhood().stopHatPractice();
        boat.syncSeats();
        return true;
    }

    boolean prepareOwnerToiletSeat(YukkuriEntity owner) {
        if (owner != boat.owner()) return false;
        if (ownerToiletSeat) return true;
        if (!serviceSeats.isEmpty() || boat.ownerSeatBusy()) return false;
        Vec3 brim = boat.rearBrimPlace(owner, boat.taken());
        if (brim == null) {carePlanDebug="toilet: no supported outer brim";return false;}
        if(!boat.moveOwnerSeat(owner, brim, false))return false;
        ownerToiletSeat = true;
        boat.syncSeats();
        return true;
    }

    boolean careSeatReady(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        var parent = boat.owner();
        return service != null && service.ownerService() != null && parent != null
                && boat.carry.carryTrip == null
                && boat.atSeat(child, new Vec3(service.service()[0], service.service()[1], service.service()[2]))
                && boat.atSeat(parent, service.ownerService());
    }

    boolean rearCareSeat(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.rearCare();
    }

    boolean prepareToiletSeat(YukkuriEntity child, YukkuriEntity parent) {
        if (boat.level().isClientSide || child == null || parent == null || parent != boat.owner() || child.getVehicle() != boat
                || parent.getVehicle() != boat || !boat.protects(child) || !boat.protects(parent) || !boat.isAfloat()) return false;
        var service = serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() == null || !service.rearCare()) return false;
        if (service.toiletReady()) return true;
        if (!careSeatReady(child)) return false;
        Vec3 brim = boat.frontEdgePlace(child, boat.takenExcept(child.getUUID()), true);
        if (brim == null) {carePlanDebug="toilet: no supported outer brim";return false;}
        var destination = new double[]{brim.x, brim.y, brim.z, boat.radiusOf(child)};
        Vec3 escort=boat.escortPlace(parent,child,brim);
        if(escort==null){carePlanDebug="toilet: no escort position";return false;}
        if (!moveCarePair(child, parent, destination, escort)) {carePlanDebug="toilet: transfer blocked to "+brim+" escort="+escort;return false;}
        serviceSeats.put(child.getUUID(), new ServiceSeat(service.original(), service.originalRegion(), destination, escort, true, true));
        boat.syncSeats();
        return true;
    }

    boolean prepareToiletSeat(YukkuriEntity child) {
        if (boat.level().isClientSide || child == null || child.getVehicle() != boat
                || !boat.protects(child) || !boat.isAfloat() || boat.flooding != 0 || boat.hasIncoming()) return false;
        if (child.getUUID().equals(boat.ownerYukkuri())) return prepareOwnerToiletSeat(child);
        var service = serviceSeats.get(child.getUUID());
        if (service != null && service.toiletReady()) return true;
        if (service != null && service.ownerService() != null) return false;
        if (serviceSeats.keySet().stream().anyMatch(id -> !id.equals(child.getUUID())) || !returningSeats.isEmpty() || boat.ownerSeatBusy()) return false;
        var original = service != null ? service.original() : boat.seatState.residentSeat(child.getUUID());
        if (original == null) return false;
        Vec3 brim = boat.frontEdgePlace(child, boat.takenExcept(child.getUUID()), true);
        if (brim == null) return false;
        var destination = new double[]{brim.x, brim.y, brim.z, boat.radiusOf(child)};
        if (!boat.moveYoungSeat(child, destination, false)) return false;
        serviceSeats.put(child.getUUID(), new ServiceSeat(original.clone(), service == null ? boat.region() : service.originalRegion(), destination, null, false, true));
        boat.syncSeats();
        return true;
    }

    boolean toiletSeatReady(YukkuriEntity child) {
        if (child != null && child.getUUID().equals(boat.ownerYukkuri())) { var seat=boat.seatState.ownerServiceSeat(); return ownerToiletSeat && seat != null && boat.atSeat(child, seat); }
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return boat.carry.carryTrip == null && service != null && service.toiletReady()
                && boat.atSeat(child, new Vec3(service.service()[0], service.service()[1], service.service()[2]))
                && (service.ownerService() == null || boat.owner() != null && boat.atSeat(boat.owner(), service.ownerService()));
    }

    void faceToiletOutward(YukkuriEntity child) {
        if (child == null) return;
        boolean owner = child.getUUID().equals(boat.ownerYukkuri());
        var local = owner ? boat.seatState.ownerServiceSeat() : boat.seatOf(child); if (local == null) return;
        Vec3 inside = boat.toWorld(local.add(0, 0, owner ? -1 : 1)); Vec3 delta = inside.subtract(child.position());
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        SurfaceApproach.face(child,yaw);
    }

    Vec3 toiletDropPosition(YukkuriEntity child) {
        if (!toiletSeatReady(child)) return null;
        boolean owner = child.getUUID().equals(boat.ownerYukkuri());
        var local = owner ? boat.seatState.ownerServiceSeat() : boat.seatOf(child); if (local == null) return null;
        Vec3 at = boat.toWorld(local), outside = boat.toWorld(local.add(0, 0, owner ? 1 : -1)).subtract(at).normalize();
        Vec3 raw = at.add(outside.scale(child.getBbWidth() * .55 + .06));
        // A water block hidden under the brim does not make this a valid drop.  The rear point must
        // already be beyond the actual hat outline, then have an unobstructed short fall into water.
        Vec3 rawLocal = boat.toLocal(raw);
        if (Double.isFinite(HatVessel.surface(boat.mode(), rawLocal.x, rawLocal.z))) return null;
        BlockPos below = BlockPos.containing(raw.add(0, -.04, 0));
        for (int down = 0; down <= 2; down++) {
            BlockPos atBelow = below.below(down);
            if (boat.level().getFluidState(atBelow).is(FluidTags.WATER)) return raw.add(0, .04, 0);
            if (!boat.level().getBlockState(atBelow).isAir()) return null;
        }
        return null;
    }

    boolean toiletAssistanceValidated(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.toiletReady() && service.rearCare();
    }

    boolean releaseToiletSeat(YukkuriEntity child) {
        if (child != null && child.getUUID().equals(boat.ownerYukkuri())) {
            if (!ownerToiletSeat) return false;
            ownerToiletSeat = false;
            boat.moveOwnerSeat(child, boat.ownerSeat(child), false);
            boat.syncSeats();
            return true;
        }
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || !service.toiletReady()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    void releaseCareSeat(UUID childId) {
        var service = serviceSeats.remove(childId);
        if (service != null) returningSeats.putIfAbsent(childId, service);
        returnCareSeats();
        boat.syncSeats();
    }

    void returnCareSeats() {
        if (boat.carry.carryTrip != null) return;
        for (var entry : List.copyOf(returningSeats.entrySet())) {
            if (!(boat.level() instanceof ServerLevel server) || !(server.getEntity(entry.getKey()) instanceof YukkuriEntity child)
                    || child.getVehicle() != boat) { returningSeats.remove(entry.getKey()); continue; }
            if(serviceSeats.containsKey(entry.getKey()))continue;
            var service = entry.getValue();
            var original=new Vec3(service.original()[0],service.original()[1],service.original()[2]);
            double[] destination = service.originalRegion().equals(boat.region())&&HatVessel.fits(boat.mode(),original,boat.radiusOf(child),
                    boat.layoutTaken(child.getUUID()),HatVessel.bodySpace(child,boat.hullScale(),boat.unit()))
                    ?new double[]{original.x,original.y,original.z,boat.radiusOf(child)}:boat.normalSeat(child,child.getUUID());
            if (destination == null) {
                if(boat.changingSeat(child))continue;
                boat.cramped.add(child.getUUID());returningSeats.remove(entry.getKey());
                var at=boat.toLocal(child.position());boat.seatState.putResident(child.getUUID(),new double[]{at.x,at.y,at.z,boat.radiusOf(child)});
                continue;
            }
            Vec3 target = new Vec3(destination[0],destination[1],destination[2]);
            var parent = boat.owner();
            var path = boat.walkingRoute(child,target);
            if (!child.childhood().canJump() && (HatBoatEntity.hasStep(path) || Math.abs(boat.toLocal(child.position()).y-target.y)>.01) && parent != null && parent.getVehicle() == boat) {
                if (boat.beginReturnCarry(child,parent,target)) break;
                continue;
            }
            if (boat.toLocal(child.position()).distanceToSqr(target)>1e-6
                    &&boat.toLocal(child.position()).distanceToSqr(target)<=.01) {
                if (!boat.changingSeat(child)&&!boat.movePreparationCrawl(child,target)) continue;
            } else if (!boat.moveYoungSeat(child, destination, false)) continue;
            if (!boat.changingSeat(child)&&boat.toLocal(child.position()).distanceToSqr(target)<1e-6) returningSeats.remove(entry.getKey());
        }
        if (boat.carry.carryTrip == null && serviceSeats.isEmpty() && !ownerToiletSeat&&boat.level().getGameTime()>=boat.boardingEdgeUntil) {
            var parent = boat.owner();
            if (parent != null && parent.getVehicle() == boat&&!parent.family().selfClimbActive()) boat.moveOwnerSeat(parent, boat.ownerSeat(parent), false);
        }
        boat.syncSeats();
    }

    boolean beginPracticeMove(YukkuriEntity child, boolean hop) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || boat.cargo().handling() || boat.cargo().tier()>=3 || child == null ||
                child.getVehicle() != boat || !boat.young(child) || !boat.protects(child)
                || !boat.isAfloat() || boat.flooding != 0 || boat.hasIncoming() || boat.departure.departureYielding!=null || child.getUUID().equals(boat.departure.voyageYoung)
                || !serviceSeats.isEmpty() || !returningSeats.isEmpty() || boat.carry.carryTrip!=null || boat.changingSeat(child) || boat.ownerSeatBusy()) return false;
        var original = boat.seatState.residentSeat(child.getUUID());
        if (original == null) return false;
        double r = boat.radiusOf(child); Vec3 from = boat.toLocal(child.position()); Vec3 chosen = null;
        for (int i = 0; i < 8 && chosen == null; i++) {
            double angle = i * Math.PI / 4.0;
            Vec3 prefer = from.add(Math.cos(angle) * Math.max(.6, r * 2.5), 0, Math.sin(angle) * Math.max(.6, r * 2.5));
            Vec3 candidate = HatVessel.place(boat.mode(), r, boat.region(), prefer, boat.layoutTaken(child.getUUID()), from,Double.NaN,HatVessel.bodySpace(child,boat.hullScale(),boat.unit()));
            if (candidate != null && candidate.distanceToSqr(from) > .16 && Math.abs(candidate.y-from.y) < 1e-4
                    && HatVessel.footprint(boat.mode(), candidate.x, candidate.z, r) == candidate.y) chosen = candidate;
        }
        if (chosen == null) return false;
        var destination = new double[]{chosen.x, chosen.y, chosen.z, r};
        if (!boat.moveYoungSeat(child, destination, hop)) return false;
        var movement=boat.seatState.move(child.getUUID());
        if(movement==null||movement.arc()!=hop)return false;
        serviceSeats.put(child.getUUID(), new ServiceSeat(original.clone(), boat.region(), destination.clone(), null, false, false));
        boat.syncSeats();
        return true;
    }

    boolean practisingMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        return service != null && service.ownerService() == null && !service.toiletReady() && boat.changingSeat(child);
    }

    boolean completePracticeMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() != null || service.toiletReady() || boat.changingSeat(child)) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    boolean cancelPracticeMove(YukkuriEntity child) {
        var service = child == null ? null : serviceSeats.get(child.getUUID());
        if (service == null || service.ownerService() != null || service.toiletReady()) return false;
        releaseCareSeat(child.getUUID());
        return true;
    }

    boolean brimSupport(double x, double z) {
        return Math.abs(HatVessel.surface(boat.mode(), x, z)-HatVessel.freeboard(boat.mode())) < 1e-6;
    }

    ServiceSeat serviceSeatFor(YukkuriEntity child, YukkuriEntity parent, double[] original, boolean rearCare) {
        if(boat.cargo().tier()>=3||boat.cargo().handling())return null;
        double r=boat.radiusOf(child); Vec3 from=boat.toLocal(child.position()), parentFrom=boat.toLocal(parent.position()), base=boat.ownerSeat(parent);
        var childBody=HatVessel.bodySpace(child,boat.hullScale(),boat.unit());
        var parentBody=HatVessel.bodySpace(parent,boat.hullScale(),boat.unit());
        var taken=boat.takenExcept(child.getUUID());
        var childPlaces=new ArrayList<Vec3>();
        // Enumerate measured contact patches once. A parent's station is not restricted to a few stern offsets.
        var opening=HatVessel.opening(boat.mode());var brim=HatVessel.brim(boat.mode());
        for(double x=opening[0]+r;x<=opening[1]-r;x+=.25)
            for(double z=-brim[1]+r+.15;z<=Math.min(.5,opening[3]-r);z+=.25){
                double y=HatVessel.footprint(boat.mode(),x,z,r);
                var at=new Vec3(x,y,z);
                if(Double.isFinite(y)&&HatVessel.fits(boat.mode(),at,r,taken,childBody))childPlaces.add(at);
            }
        record CarePose(Vec3 ownerAt,Vec3 childAt,double cost) {}
        var candidates=new ArrayList<CarePose>();
        double min=FamilyFeedingContact.minimumMouthDistance(parent,child)/boat.unit(), max=FamilyFeedingContact.mouthDistance(parent,child)/boat.unit();
        // The adult fits through the centre of this measured opening, but that strip is narrower
        // than the search grid. Always include the exact centre instead of aliasing it away.
        var ownerXs=new ArrayList<Double>();ownerXs.add(0.0);
        for(double x=opening[0];x<=opening[1];x+=.5)ownerXs.add(x);
        for(double x:ownerXs)
            for(double z=opening[2];z<=opening[3];z+=.5){
                double y=HatVessel.travelSupport(boat.mode(),x,z,boat.radiusOf(parent));
                if(!Double.isFinite(y)||!Double.isFinite(HatVessel.surface(boat.mode(),x,z)))continue;
                Vec3 ownerAt=new Vec3(x,y,z);
                if(!HatVessel.fitsSupported(boat.mode(),ownerAt,boat.radiusOf(parent),taken,parentBody))continue;
                for(var candidate:childPlaces){
                    // Adjacent real treads need not have equal elevations. The shared mouth and
                    // rear-contact envelopes, rather than an obsolete two-tier floor, bound care.
                    if(rearCare&&Math.abs(candidate.y-ownerAt.y)*boat.unit()>.18)continue;
                    double dx=candidate.x-x,dz=candidate.z-z,distance=dx*dx+dz*dz;
                    if(distance<min*min || distance>max*max || !serviceGeometryFits(parent,child,ownerAt,candidate))continue;
                    if(!HatVessel.fits(boat.mode(),candidate,r,List.of(boat.rescueBody(parent,ownerAt)),childBody)
                            ||!HatVessel.fitsSupported(boat.mode(),ownerAt,boat.radiusOf(parent),List.of(boat.rescueBody(child,candidate)),parentBody))continue;
                    double cost=candidate.distanceToSqr(from)+ownerAt.distanceToSqr(parentFrom)*.5+ownerAt.distanceToSqr(base)*.25;
                    candidates.add(new CarePose(ownerAt,candidate,cost));
                }
            }
        candidates.sort(Comparator.comparingDouble(CarePose::cost));
        int carryBlocked=0,childBlocked=0,ownerBlocked=0;
        var childRoutes=new HashMap<Vec3,List<Vec3>>();
        var carryGeometry=new CarrySearch();
        // Cheap geometry and travel costs precede routing: stop at the first fully reachable pair.
        for(var pose:candidates){
            Vec3 ownerAt=pose.ownerAt(), candidate=pose.childAt();
            var path=childRoutes.computeIfAbsent(candidate,at->boat.walkingRoute(child,at));
            boolean assisted=!child.childhood().canJump()&&(HatBoatEntity.hasStep(path)||Math.abs(candidate.y-from.y)>.01);
            if(assisted){if(boat.carryPickup(child,parent,from,candidate,ownerAt,carryGeometry)==null){carryBlocked++;continue;}}
            else{
                if(from.distanceToSqr(candidate)>.01&&path.isEmpty()){childBlocked++;continue;}
                var obstacles=boat.takenExcept(child.getUUID());
                obstacles.add(boat.rescueBody(child,candidate));
                if(parentFrom.distanceToSqr(ownerAt)>.01&&HatSurfaceRoute.find(boat.mode(),parentFrom,ownerAt,
                        boat.radiusOf(parent),obstacles,parentBody).isEmpty()){ownerBlocked++;continue;}
            }
            carePlanDebug="ready child="+candidate+" owner="+ownerAt+" assisted="+assisted;
            return new ServiceSeat(original.clone(),boat.region(),new double[]{candidate.x,candidate.y,candidate.z,r},ownerAt,rearCare,false);
        }
        carePlanDebug="no route childPatches="+childPlaces.size()+" pairs="+candidates.size()
                +" carryBlocked="+carryBlocked+" childBlocked="+childBlocked+" ownerBlocked="+ownerBlocked;
        return null;
    }

    boolean serviceGeometryFits(YukkuriEntity parent, YukkuriEntity child, Vec3 ownerAt, Vec3 childAt) {
        Vec3 parentWorld = boat.toWorld(ownerAt), childWorld = boat.toWorld(childAt);
        double horizontal = parentWorld.subtract(childWorld).multiply(1, 0, 1).length();
        return horizontal >= FamilyFeedingContact.minimumMouthDistance(parent, child)
                && horizontal <= FamilyFeedingContact.mouthDistance(parent, child)
                && Math.abs(parentWorld.y + parent.getBbHeight() * .5 - (childWorld.y + child.getBbHeight() * .5))
                        <= FamilyFeedingContact.MAX_MOUTH_HEIGHT_DELTA;
    }

    boolean moveCarePair(YukkuriEntity child, YukkuriEntity parent, double[] destination, Vec3 ownerDestination) {
        if (boat.carry.carryTrip != null) return boat.carry.carryTrip.child.equals(child.getUUID());
        Vec3 target = new Vec3(destination[0],destination[1],destination[2]);
        var path = boat.walkingRoute(child,target);
        if (!child.childhood().canJump() && (HatBoatEntity.hasStep(path) || Math.abs(boat.toLocal(child.position()).y-target.y)>.01)) return boat.beginCarry(child,parent,destination,ownerDestination);
        var oldSeat = boat.seatState.residentSeat(child.getUUID());
        var oldMove = boat.seatState.move(child.getUUID());
        if (!boat.moveYoungSeat(child, destination, false)) return false;
        if (boat.moveOwnerSeat(parent, ownerDestination, false)) return true;
        if (oldSeat == null) boat.seatState.removeResident(child.getUUID()); else boat.seatState.putResident(child.getUUID(), oldSeat);
        if (oldMove == null) boat.seatState.clearMove(child.getUUID()); else boat.seatState.setMove(child.getUUID(), oldMove);
        return false;
    }
}
