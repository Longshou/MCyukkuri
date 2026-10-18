package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.SeatMove;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Retained voyage berths and supported departure walks, using the entity's shared seat book. */
final class HatDepartureBerth {
    private final HatBoatEntity boat;
    UUID departureYielding;
    Vec3 departureYieldTarget;
    float departureYieldYaw;
    long departureYieldUntil;
    String departureDebug="not requested";
    /** One pupil at a time; its berth remains occupied while its own hat is away. */
    UUID voyageYoung;
    double[] voyageBerth;

    HatDepartureBerth(HatBoatEntity boat) { this.boat = boat; }

    boolean keepVoyageBerth(YukkuriEntity child) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || boat.cargo().handling() || !boat.young(child) || !boat.onBrim(child) || !boat.protects(child) || boat.hasIncoming()
                || boat.ownerSeatBusy() || voyageYoung != null && !voyageYoung.equals(child.getUUID())) return false;
        var others=boat.takenExcept(child.getUUID());
        double r=boat.radiusOf(child);var at=HatVessel.brimPlace(r,others,true);if(at==null)return false;
        voyageYoung = child.getUUID(); voyageBerth = new double[]{at.x,at.y,at.z,r};
        var from=boat.toLocal(child.position());
        if(from.distanceToSqr(at)>.01)boat.seatMoves.put(voyageYoung,new SeatMove(from,boat.level().getGameTime(),
                HatVessel.solve(child.position(),boat.toWorld(at),boat,HatVessel.radius(child.getBbWidth()),HatVessel.HOP_TMIN),true));
        boat.seats.put(voyageYoung,voyageBerth.clone());boat.syncSeats();return true;
    }

    boolean keepDepartureBerth(YukkuriEntity child) {
        if (boat.level().isClientSide || boat.unsteadyMotion() || boat.cargo().handling() || !boat.young(child) || !boat.protects(child) || boat.hasIncoming() || boat.hasServiceSeats()
                || boat.hasReturningSeats() || boat.hasCarryTrip() || boat.ownerSeatBusy()
                || voyageYoung != null && !voyageYoung.equals(child.getUUID())) return false;
        if(preparingDepartureBerth(child))return true;
        double r = boat.radiusOf(child); Vec3 at = boat.frontEdgePlace(child, boat.takenExcept(child.getUUID()), false);
        if (at == null) return yieldDepartureSpace(child);
        var berth = new double[]{at.x, at.y, at.z, r};
        if (!boat.moveYoungSeat(child, berth, false)) return false;
        voyageYoung = child.getUUID(); voyageBerth = berth;
        departureDebug="ready "+at;
        boat.syncSeats();
        return true;
    }

    String departureDebug(){return departureDebug;}

    boolean preparingDepartureBerth(YukkuriEntity child){return departureYielding!=null&&voyageBerth==null&&child.getUUID().equals(voyageYoung);}

    boolean advanceDepartureBerth(YukkuriEntity child){
        if(!preparingDepartureBerth(child)||!(boat.level() instanceof ServerLevel server))return false;
        if(boat.level().getGameTime()>departureYieldUntil||!(server.getEntity(departureYielding) instanceof YukkuriEntity sibling)
                ||sibling.getVehicle()!=boat){departureDebug="yield expired or rider left";return false;}
        if(boat.changingSeat(sibling))return true;
        if(boat.toLocal(sibling.position()).distanceToSqr(departureYieldTarget)>1e-5){departureDebug="yield did not reach feet";return false;}
        var at=boat.frontEdgePlace(child,boat.takenExcept(child.getUUID()),false);
        if(at==null){departureDebug="yield settled but full departure envelope still blocked";return false;}
        var berth=new double[]{at.x,at.y,at.z,boat.radiusOf(child)};
        if(!boat.moveYoungSeat(child,berth,false)){departureDebug="pupil route blocked after yield";return false;}
        voyageBerth=berth;departureDebug="ready after yield "+at;boat.syncSeats();return true;
    }

    boolean yieldDepartureSpace(YukkuriEntity child){
        // Only a real, short supported walk is accepted. This is not a layout reset or a care-return reservation.
        int routes=0,empty=0,steps=0;String last="none";
        for(var passenger:boat.youngPassengers())if(passenger instanceof YukkuriEntity sibling&&sibling!=child
                &&!boat.changingSeat(sibling)&&!sibling.family().flying()&&!sibling.life().isSleeping()){
            var taken=boat.takenExcept(child.getUUID());
            var pupilAt=boat.frontEdgePlace(child,taken,false,sibling);if(pupilAt==null)continue;
            var from=boat.toLocal(sibling.position());
            int siblingRoutes=0;var directions=new HashSet<Integer>();
            var body=departureBody(sibling);
            var places=new ArrayList<Vec3>();
            for(double x=-HatVessel.BRIM[0];x<=HatVessel.BRIM[0];x+=.4)
                for(double z=-HatVessel.BRIM[1];z<=HatVessel.BRIM[1];z+=.4){
                    double y=HatVessel.travelSupport(boat.mode(),x,z,boat.radiusOf(sibling));
                    if(Double.isFinite(y))places.add(new Vec3(x,y,z));
                }
            places.sort(Comparator.comparingDouble(from::distanceToSqr));
            for(var wanted:places){
                if(!boat.cargoSeatClear(sibling,wanted)||!Double.isFinite(HatVessel.surface(boat.mode(),wanted.x,wanted.z))
                        ||!HatVessel.cabinVolumeClear(boat.mode(),wanted.x,wanted.y,wanted.z,boat.radiusOf(sibling),body)
                        ||!sibling.childhood().canJump()&&Math.abs(wanted.y-from.y)>1e-4)continue;
                float yaw=sibling.yBodyRot;
                if(!departureEnvelopeClear(sibling,wanted,yaw,null)
                        ||departureEnvelopesMeet(child,pupilAt,boat.getYRot(),sibling,wanted,yaw))continue;
                int direction=(int)Math.floor((Math.atan2(wanted.z-from.z,wanted.x-from.x)+Math.PI)*4/Math.PI);
                if(!directions.add(direction+16*(int)Math.round(wanted.y*100)))continue;
                if(siblingRoutes>=3||routes>=12)break;
                siblingRoutes++;routes++;last=sibling.getUUID()+" to="+wanted;
                var route=departureYieldRoute(sibling,wanted);
                if(route.size()<2){empty++;continue;}
                if(HatBoatEntity.hasStep(route)&&!sibling.childhood().canJump()){steps++;continue;}
                if(!boat.moveCheckedSeatRoute(sibling,wanted,route,sibling.childhood().canJump()))continue;
                voyageYoung=child.getUUID();voyageBerth=null;departureYielding=sibling.getUUID();departureYieldTarget=wanted;
                departureYieldYaw=yaw;
                var move=boat.seatMoves.get(departureYielding);departureYieldUntil=move.start()+move.ticks()+40;
                departureDebug="yield "+departureYielding+" from="+from+" to="+wanted+" pupil="+pupilAt+" points="+route.size();
                boat.syncSeats();return true;
            }
        }
        departureDebug="no departure yield routes="+routes+" empty="+empty+" untrainedSteps="+steps+" last="+last;return false;
    }

    HatVessel.BodySpace departureBody(YukkuriEntity rider){
        var body=HatVessel.bodySpace(rider,boat.hullScale(),boat.unit());double angle=Math.toRadians(rider.yBodyRot-boat.getYRot());
        double c=Math.abs(Math.cos(angle)),s=Math.abs(Math.sin(angle));
        return new HatVessel.BodySpace(body.height(),c*body.hatX()+s*body.hatZ(),s*body.hatX()+c*body.hatZ(),body.hatBase(),body.hatHeight());
    }

    List<Vec3> departureYieldRoute(YukkuriEntity rider,Vec3 destination){
        var from=boat.toLocal(rider.position());var obstacles=new ArrayList<double[]>();
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity other&&other!=rider){
            var p=boat.toLocal(other.position());var shape=departureBody(other);
            obstacles.add(new double[]{p.x,p.y,p.z,boat.radiusOf(other),shape.height(),0,shape.hatX(),shape.hatZ(),shape.hatBase(),shape.hatHeight()});
        }
        boat.addCargoFootprints(obstacles);var body=departureBody(rider);
        var direct=HatSurfaceRoute.find(boat.mode(),from,destination,boat.radiusOf(rider),obstacles,body);
        if(!direct.isEmpty()&&(rider.childhood().canJump()||!HatBoatEntity.hasStep(direct)))return direct;
        // Keep an exact source axis when the coarse grid misses a narrow exit between passengers.
        for(var axis:List.of(new Vec3(destination.x,0,from.z),new Vec3(from.x,0,destination.z))){
            double support=HatVessel.travelSupport(boat.mode(),axis.x,axis.z,boat.radiusOf(rider));
            if(!Double.isFinite(support))continue;
            var corner=new Vec3(axis.x,support,axis.z);
            var route=new ArrayList<Vec3>();route.add(from);boolean clear=true;
            for(var stop:List.of(corner,destination)){
                var previous=route.getLast();if(previous.distanceToSqr(stop)<1e-10)continue;
                var leg=HatSurfaceRoute.find(boat.mode(),previous,stop,boat.radiusOf(rider),obstacles,body);
                if(leg.size()<2||(!rider.childhood().canJump()&&HatBoatEntity.hasStep(leg))){clear=false;break;}
                route.addAll(leg.subList(1,leg.size()));
            }
            if(clear&&route.size()>=2){departureDebug="supported axis elbow "+corner;return List.copyOf(route);}
        }
        if(Math.abs(from.y-destination.y)>1e-4)return List.of();
        var corners=HatVessel.brimRouteCorners();corners.sort(Comparator.comparingDouble(from::distanceToSqr));
        for(var corner:corners){
            if((corner.x-from.x)*(destination.x-corner.x)<0||(corner.z-from.z)*(destination.z-corner.z)<0)continue;
            var stops=List.of(new Vec3(from.x,from.y,corner.z),new Vec3(corner.x,from.y,corner.z),
                    new Vec3(corner.x,from.y,destination.z),destination);
            var route=new ArrayList<Vec3>();route.add(from);boolean clear=true;
            for(var stop:stops){
                var previous=route.getLast();if(previous.distanceToSqr(stop)<1e-10)continue;
                var leg=HatSurfaceRoute.find(boat.mode(),previous,stop,boat.radiusOf(rider),obstacles,body);
                if(leg.size()<2||HatBoatEntity.hasStep(leg)){clear=false;break;}
                route.addAll(leg.subList(1,leg.size()));
            }
            if(clear){departureDebug="supported brim elbow "+corner;return List.copyOf(route);}
        }
        return List.of();
    }

    boolean departureEnvelopesMeet(YukkuriEntity a,Vec3 at,float yaw,YukkuriEntity b,Vec3 bt,float byaw){
        for(var shape:BraidRescueGeometry.boardingEnvelope(a,boat.toWorld(at),0,0,yaw))
            for(var other:BraidRescueGeometry.boardingEnvelope(b,boat.toWorld(bt),0,0,byaw))
                if(shape.deflate(.002).intersects(other))return true;
        return false;
    }

    boolean departureEnvelopeClear(YukkuriEntity rider,Vec3 at,float yaw,YukkuriEntity ignored){
        var shapes=BraidRescueGeometry.boardingEnvelope(rider,boat.toWorld(at),0,0,yaw);
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity other&&other!=rider&&other!=ignored)
            for(var shape:shapes)for(var obstacle:BraidRescueGeometry.envelope(other,other.position()))
                if(shape.deflate(.002).intersects(obstacle))return false;
        return true;
    }

    void releaseVoyageBerth(UUID child) {
        if (child.equals(voyageYoung)) { voyageYoung = null; voyageBerth = null; departureYielding=null;departureYieldTarget=null;boat.seatRegion = ""; }
    }

    Vec3 voyageBerth(YukkuriEntity child) {
        return child.getUUID().equals(voyageYoung) && voyageBerth != null ? new Vec3(voyageBerth[0],voyageBerth[1],voyageBerth[2]) : null;
    }

    boolean onVoyageBerth(YukkuriEntity child) {
        if (child == null) return false;
        var berth = voyageBerth(child); var seat = child == null ? null : boat.seatOf(child);
        return berth != null && seat != null && !boat.changingSeat(child) && Math.abs(seat.y-HatVessel.FREEBOARD) < 1e-5
                && seat.distanceToSqr(berth) < 1e-5 && Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, seat.x, seat.z));
    }
}
