package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;

import static io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.MAX_YOUNG;

/** Joint rescue destinations and the captain's supported walk, sharing the entity's reservations. */
final class HatRescuePair {
    private final HatBoatEntity boat;
    boolean rescueOwnerHeld;

    HatRescuePair(HatBoatEntity boat) { this.boat = boat; }

    List<Vec3> rescueOwnerCandidates(YukkuriEntity parent){
        if(parent==null)return List.of();
        return supportedRescueOwnerCandidates(parent,boat.takenExcept(parent.getUUID()));
    }

    List<Vec3> rescueOwnerCandidates(YukkuriEntity parent,YukkuriEntity child){
        if(parent==null)return List.of();
        return supportedRescueOwnerCandidates(parent,boat.takenExcept(child!=null&&!boat.hasPassenger(child)?child.getUUID():parent.getUUID()));
    }

    List<Vec3> supportedRescueOwnerCandidates(YukkuriEntity parent,List<double[]> obstacles){
        if(parent!=boat.owner()||parent.getVehicle()!=boat||!boat.isAfloat()||boat.unsteadyMotion())return List.of();
        var result=new ArrayList<Vec3>();
        var body=HatVessel.bodySpace(parent,boat.hullScale(),boat.unit());double radius=boat.radiusOf(parent);
        for(double x=-HatVessel.BRIM[0];x<=HatVessel.BRIM[0];x+=.4)
            for(double z=-HatVessel.BRIM[1];z<=HatVessel.BRIM[1];z+=.4){
                double y=HatVessel.travelSupport(boat.mode(),x,z,radius);if(!Double.isFinite(y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fitsSupported(boat.mode(),at,radius,obstacles,body))result.add(at);
            }
        return result;
    }

    List<Vec3> rescueYoungCandidates(YukkuriEntity child){
        if(child==null||!boat.young(child)||child.isPassenger()||!boat.isAfloat()||boat.unsteadyMotion())return List.of();
        var result=new ArrayList<Vec3>();var obstacles=boat.takenExcept(child.getUUID());
        var body=HatVessel.bodySpace(child,boat.hullScale(),boat.unit());double radius=boat.radiusOf(child);
        var preferred=HatVessel.place(boat.mode(),radius,boat.region(),null,obstacles,null,Double.NaN,body);
        if(preferred!=null)result.add(preferred);
        var previous=boat.seatState.incomingSeat(child.getUUID());
        if(previous!=null){
            var at=new Vec3(previous[0],previous[1],previous[2]);
            if(HatVessel.fits(boat.mode(),at,radius,obstacles,body))result.add(at);
        }
        // Include the delivery seat lattice as well as the walking lattice; a half-unit side shift
        // can put a finite braid within reach even when the nearest default seat is unreachable.
        for(double step:new double[]{.5,.4})for(double x=HatVessel.OPENING[0]+radius;x<=HatVessel.OPENING[1]-radius+1e-6;x+=step)
            for(double z=HatVessel.OPENING[2]+radius;z<=HatVessel.OPENING[3]-radius+1e-6;z+=step){
                double y=HatVessel.footprint(boat.mode(),x,z,radius);
                if(!Double.isFinite(y)||Math.abs(HatVessel.surface(boat.mode(),x,z)-y)>1e-4)continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(boat.mode(),at,radius,obstacles,body))result.add(at);
            }
        // A braid may use the child's own side before a later supported walk into the cabin.
        // These existing inner-brim patches require the complete footprint and its safety margin.
        for(double x=-HatVessel.BRIM[0]+radius+.18;x<=HatVessel.BRIM[0]-radius-.18;x+=.25)
            for(double z=-HatVessel.BRIM[1]+radius+.18;z<=HatVessel.BRIM[1]-radius-.18;z+=.25){
                if(x>HatVessel.OPENING[0]&&x<HatVessel.OPENING[1]&&z>HatVessel.OPENING[2]&&z<HatVessel.OPENING[3])continue;
                double y=HatVessel.top(boat.mode());
                if(!HatVessel.supported(boat.mode(),x,z,radius+.18,y))continue;
                var at=new Vec3(x,y,z);if(HatVessel.fits(boat.mode(),at,radius,obstacles,body))result.add(at);
            }
        // This is only a cheap preference. Reachable fixed-length braid geometry chooses the final pair.
        result.removeIf(at->!boat.cargoSeatClear(child,at));
        if(preferred!=null)result.sort(Comparator.comparingDouble(at->at.distanceToSqr(preferred)));
        return result;
    }

    boolean canPlaceRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){
        if(child==null||parent==null||parent!=boat.owner()||parent.getVehicle()!=boat||!boat.young(child)||child.isPassenger()
                ||youngSeat==null||parentStand==null||!boat.isAfloat()||boat.unsteadyMotion()||boat.towed()||boat.flooding>0||boat.cargo().handling())return false;
        if(!boat.cargoSeatClear(child,youngSeat)||Math.abs(HatVessel.surface(boat.mode(),youngSeat.x,youngSeat.z)-youngSeat.y)>1e-4)return false;
        var obstacles=boat.takenExcept(child.getUUID());
        var childObstacles=new ArrayList<>(obstacles);childObstacles.add(boat.rescueBody(parent,parentStand));
        var ownerObstacles=new ArrayList<>(obstacles);ownerObstacles.add(boat.rescueBody(child,youngSeat));
        return HatVessel.fits(boat.mode(),youngSeat,boat.radiusOf(child),childObstacles,HatVessel.bodySpace(child,boat.hullScale(),boat.unit()))
                &&HatVessel.fitsSupported(boat.mode(),parentStand,boat.radiusOf(parent),ownerObstacles,HatVessel.bodySpace(parent,boat.hullScale(),boat.unit()));
    }

    boolean reserveRescuePair(YukkuriEntity child,YukkuriEntity parent,Vec3 youngSeat,Vec3 parentStand){
        if(boat.level().isClientSide||!canPlaceRescuePair(child,parent,youngSeat,parentStand)||boat.hasCarryTrip()||boat.ownerUsesToiletSeat()
                ||boat.hasServiceSeats()||boat.hasReturningSeats()
                ||parent.family().rescueTarget()!=null&&!child.getUUID().equals(parent.family().rescueTarget())
                ||!boat.seatState.hasIncoming(child.getUUID())&&boat.youngAboard()+boat.seatState.incomingCount()>=MAX_YOUNG)return false;
        var obstacles=boat.takenExcept(child.getUUID());obstacles.add(boat.rescueBody(child,youngSeat));
        var from=boat.toLocal(parent.position());
        if(from.distanceToSqr(parentStand)>.01&&HatSurfaceRoute.find(boat.mode(),from,parentStand,boat.radiusOf(parent),obstacles,HatVessel.bodySpace(parent,
                boat.hullScale(),boat.unit())).isEmpty())return false;
        boat.seatState.reserveIncoming(child.getUUID(),new double[]{youngSeat.x,youngSeat.y,youngSeat.z,boat.radiusOf(child)});
        boat.syncSeats();return true;
    }

    boolean moveRescueOwner(YukkuriEntity parent,Vec3 localSupported){
        if(boat.level().isClientSide||parent!=boat.owner()||parent.getVehicle()!=boat||!boat.isAfloat()||boat.unsteadyMotion()||boat.towed()
                ||localSupported==null||boat.hasCarryTrip()||boat.ownerUsesToiletSeat()||boat.hasServiceSeats()||boat.hasReturningSeats())return false;
        if(!HatVessel.fitsSupported(boat.mode(),localSupported,boat.radiusOf(parent),boat.takenExcept(parent.getUUID()),HatVessel.bodySpace(parent,boat.hullScale(),boat.unit())))return false;
        if(!boat.moveOwnerSeat(parent,localSupported,false))return false;
        rescueOwnerHeld=true;boat.holdPosition();boat.syncSeats();return true;
    }

    String rescueOwnerRefusal(YukkuriEntity parent,Vec3 localSupported){
        if(parent!=boat.owner()||parent.getVehicle()!=boat)return "not the captain aboard";
        if(!boat.isAfloat()||boat.unsteadyMotion()||boat.towed())return "hull not steady";
        if(boat.hasCarryTrip())return "carry trip";if(boat.ownerUsesToiletSeat())return "toilet seat";
        if(boat.hasServiceSeats())return "service seats "+boat.serviceSeatIdsDebug();if(boat.hasReturningSeats())return "returning seats "+boat.returningSeatIdsDebug();
        if(!HatVessel.fitsSupported(boat.mode(),localSupported,boat.radiusOf(parent),boat.takenExcept(parent.getUUID()),HatVessel.bodySpace(parent,
                boat.hullScale(),boat.unit())))return "stand does not fit";
        var from=boat.toLocal(parent.position());
        if(from.distanceToSqr(localSupported)>.01&&boat.walkingRoute(parent,localSupported).isEmpty())return "no walking route from "+from;
        return null;
    }

    List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child){
        return rescueOwnerRoute(parent,localStand,child,null);
    }

    List<Vec3> rescueOwnerRoute(YukkuriEntity parent,Vec3 localStand,YukkuriEntity child,Vec3 youngSeat){
        if(parent!=boat.owner()||parent.getVehicle()!=boat||localStand==null||!boat.isAfloat()||boat.unsteadyMotion())return List.of();
        var obstacles=boat.takenExcept(child!=null?child.getUUID():parent.getUUID());
        if(child!=null&&youngSeat!=null)obstacles.add(boat.rescueBody(child,youngSeat));
        if(!HatVessel.fitsSupported(boat.mode(),localStand,boat.radiusOf(parent),obstacles,HatVessel.bodySpace(parent,boat.hullScale(),boat.unit())))return List.of();
        var from=boat.toLocal(parent.position());
        if(from.distanceToSqr(localStand)<.0001)return List.of(from);
        return HatSurfaceRoute.find(boat.mode(),from,localStand,boat.radiusOf(parent),obstacles,HatVessel.bodySpace(parent,boat.hullScale(),boat.unit()));
    }

    boolean rescueOwnerReady(YukkuriEntity parent){var seat=boat.seatState.ownerServiceSeat();return rescueOwnerHeld&&parent==boat.owner()&&seat!=null&&boat.atSeat(parent,seat);}

    void releaseRescueOwner(YukkuriEntity parent){
        if(boat.level().isClientSide||parent!=boat.owner())return;
        rescueOwnerHeld=false;if(parent.getVehicle()==boat)boat.moveOwnerSeat(parent,boat.ownerSeat(parent),false);boat.syncSeats();
    }
}
