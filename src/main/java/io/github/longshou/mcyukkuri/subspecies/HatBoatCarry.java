package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.SeatMove;
import io.github.longshou.mcyukkuri.subspecies.HatServiceSeats.ReturnRetry;
import io.github.longshou.mcyukkuri.subspecies.HatServiceSeats.ServiceSeat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** One physical parent-child transport; resident seats remain owned by the vessel. */
final class HatBoatCarry {
    private final HatBoatEntity boat;
    /** -2 crawl on one tread, -1 tuck, 0 approach, 1 lift, 2 carry, 3 lower, 4 retreat, 5 unfold. */
    static final class CarryTrip {
        final UUID child;
        final Vec3 from, destination, pickup, ownerDestination, offset;
        Vec3 facing;
        Vec3 lastAnchor;
        int phase, contact;
        long at;
        HatCareGeometry.Context geometry;
        CarryTrip(UUID child, Vec3 from, Vec3 destination, Vec3 pickup, Vec3 ownerDestination, Vec3 offset, long at) {
            this.child=child; this.from=from; this.destination=destination; this.pickup=pickup;
            this.ownerDestination=ownerDestination; this.offset=offset; this.at=at;
            this.lastAnchor=from;
            this.facing=offset.multiply(1,0,1).normalize();
        }
    }
    record CarryPickup(Vec3 childAt,Vec3 at,Vec3 offset,Vec3 delivery,HatCareGeometry.Context geometry) {}
    record PickupKey(Vec3 from,Vec3 direction) {}
    record DeliveryKey(Vec3 to,Vec3 parentAt,Vec3 direction) {}
    static final class CarrySearch {
        final Map<Vec3,HatCareGeometry.Context> geometry=new HashMap<>();
        final Map<PickupKey,CarryPickup> pickups=new HashMap<>();
        final Map<DeliveryKey,String> deliveries=new HashMap<>();
        List<Vec3> preparePlaces;
    }
    CarryTrip carryTrip;
    boolean recoverCarry;
    String carryPlanDebug="not planned";
    String carryTransferDebug="not checked";

    HatBoatCarry(HatBoatEntity boat) { this.boat = boat; }

    List<Vec3> pickupRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 target,Vec3 childAt,HatCareGeometry.Context geometry){
        var obstacles=boat.takenExcept(child.getUUID());var from=boat.toLocal(parent.position());
        var path=HatSurfaceRoute.find(boat.mode(),from,target,boat.radiusOf(parent),obstacles,Vec3.ZERO,0,
                HatVessel.bodySpace(parent,boat.hullScale(),boat.unit()),null,at->geometry.pickupClear(at,childAt));
        return posedRouteClear(path,parent,childAt,null,geometry,obstacles)?path:List.of();
    }

    boolean posedRouteClear(List<Vec3> path,YukkuriEntity parent,Vec3 childAt,Vec3 offset,
                                    HatCareGeometry.Context geometry,List<double[]> obstacles){
        if(path.isEmpty())return false;
        for(int segment=1;segment<path.size();segment++){
            var a=path.get(segment-1);var b=path.get(segment);int count=Math.max(1,(int)Math.ceil(a.distanceTo(b)/.05));
            for(int i=0;i<=count;i++){
                var at=a.lerp(b,(double)i/count);double support=HatVessel.travelSupport(boat.mode(),at.x,at.z,boat.radiusOf(parent));
                at=new Vec3(at.x,Math.max(at.y,support),at.z);
                if(!geometry.clear(at,offset==null?childAt:at.add(offset),boat.mode(),obstacles))return false;
            }
        }
        return true;
    }

    boolean carryTransferClear(YukkuriEntity child,Vec3 from,Vec3 to,Vec3 parentAt,HatCareGeometry.Context geometry,List<double[]> obstacles){
        if(!HatSurfaceRoute.transferClear(boat.mode(),from,to,boat.radiusOf(child),obstacles,HatVessel.bodySpace(child,boat.hullScale(),boat.unit()))){
            carryTransferDebug="upright body/hat envelope";return false;
        }
        int count=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.05));
        for(int i=0;i<=count;i++){
            var at=from.lerp(to,(double)i/count);
            if(!geometry.clear(parentAt,at,boat.mode(),obstacles)){carryTransferDebug="posed "+geometry.debug()+" at="+at;return false;}
        }
        carryTransferDebug="clear";
        return true;
    }

    List<Vec3> carriedRoute(YukkuriEntity parent,YukkuriEntity child,Vec3 from,Vec3 to,Vec3 offset,HatCareGeometry.Context geometry){
        var obstacles=boat.takenExcept(child.getUUID());
        var path=HatSurfaceRoute.find(boat.mode(),from,to,boat.radiusOf(parent),obstacles,offset,boat.radiusOf(child),
                HatVessel.bodySpace(parent,boat.hullScale(),boat.unit()),HatVessel.bodySpace(child,boat.hullScale(),boat.unit()));
        if(posedRouteClear(path,parent,null,offset,geometry,obstacles))return path;
        // A tucked hat may need the adult to climb the side tread before advancing past a front riser.
        // These two finite alternatives use the same support, full envelopes and exact posed checks.
        for(double x:new double[]{-.12,.12}){
            double startY=HatVessel.travelSupport(boat.mode(),x,from.z,boat.radiusOf(parent));
            double endY=HatVessel.travelSupport(boat.mode(),x,to.z,boat.radiusOf(parent));
            if(!Double.isFinite(startY)||!Double.isFinite(endY))continue;
            var route=new ArrayList<Vec3>();route.add(from);boolean clear=true;
            for(var target:List.of(new Vec3(x,startY,from.z),new Vec3(x,endY,to.z),to)){
                var segment=HatSurfaceRoute.find(boat.mode(),route.getLast(),target,boat.radiusOf(parent),obstacles,offset,boat.radiusOf(child),
                        HatVessel.bodySpace(parent,boat.hullScale(),boat.unit()),HatVessel.bodySpace(child,boat.hullScale(),boat.unit()));
                if(!posedRouteClear(segment,parent,null,offset,geometry,obstacles)){clear=false;break;}
                route.addAll(segment.subList(1,segment.size()));
            }
            if(clear)return List.copyOf(route);
        }
        return List.of();
    }

    CarryPickup carryPickup(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                    CarrySearch cache) {
        if(cache.preparePlaces==null){
        var preparePlaces=new ArrayList<Vec3>();preparePlaces.add(from);
        // A dependent child can crawl along its current tread before tucking; it never crosses a riser unheld.
        for(double dz:new double[]{.03,.032,.034,.08,.16,.24,.36,.48,-.08,-.16}){
            var at=from.add(0,0,dz);
            if(Math.abs(HatVessel.surface(boat.mode(),at.x,at.z)-from.y)>1e-5)continue;
            if(!HatVessel.fits(boat.mode(),at,boat.radiusOf(child),boat.takenExcept(child.getUUID()),HatVessel.bodySpace(child,boat.hullScale(),boat.unit())))continue;
            var path=boat.walkingRoute(child,at);if(!path.isEmpty()&&!HatBoatEntity.hasStep(path))preparePlaces.add(at);
        }
        cache.preparePlaces=List.copyOf(preparePlaces);
        }
        for(var at:cache.preparePlaces){var plan=carryPickupAt(child,parent,at,to,ownerDestination,cache);if(plan!=null)return plan;}
        return null;
    }

    CarryPickup carryPickupAt(YukkuriEntity child,YukkuriEntity parent,Vec3 from,Vec3 to,Vec3 ownerDestination,
                                      CarrySearch cache){
        var straight=HatCareAnchor.offset(parent,child,new Vec3(0,0,-1),boat.unit());
        if(straight==null){carryPlanDebug="missing authored mouth anchor";return null;}
        var directions=new ArrayList<Vec3>();double reach=straight.horizontalDistance();
        // A small yaw lets a child beside the centre aisle meet an adult whose whole body fits it.
        for(double x:new double[]{0,-.12,.12,boat.toLocal(parent.position()).x,from.x}){
            double dx=from.x-x;if(Math.abs(dx)<reach)directions.add(new Vec3(dx,0,-Math.sqrt(reach*reach-dx*dx)).normalize());
        }
        var obstacles=boat.takenExcept(child.getUUID());
        for(var direction:directions){
            var offset=HatCareAnchor.offset(parent,child,direction,boat.unit());var xz=from.subtract(offset);
            var pickup=new Vec3(xz.x,HatVessel.travelSupport(boat.mode(),xz.x,xz.z,boat.radiusOf(parent)),xz.z);
            if(!Double.isFinite(pickup.y)||!boat.serviceGeometryFits(parent,child,pickup,from)){carryPlanDebug="pickup contact out of range "+pickup;continue;}
            var geometry=cache.geometry.computeIfAbsent(direction,key->HatCareGeometry.plan(parent,child,boat.unit(),key));
            if(geometry==null)continue;
            // Reject an impossible delivery before building preparation samples or routing the pickup.
            var delivery=ownerDestination;
            if(delivery==null){
                var at=to.subtract(offset);
                double support=HatVessel.travelSupport(boat.mode(),at.x,at.z,boat.radiusOf(parent));
                if(!Double.isFinite(support))continue;
                delivery=new Vec3(at.x,support,at.z);
            }
            var deliveryKey=new DeliveryKey(to,delivery,direction);
            String lowerFailure=cache.deliveries.get(deliveryKey);
            if(lowerFailure==null){
                lowerFailure=carryTransferClear(child,delivery.add(offset),to,delivery,geometry,obstacles)?"":carryTransferDebug;
                cache.deliveries.put(deliveryKey,lowerFailure);
            }
            if(!lowerFailure.isEmpty()){
                carryPlanDebug="lower to="+to+" from="+delivery.add(offset)+" blocked "+lowerFailure;continue;
            }
            var key=new PickupKey(from,direction);
            if(cache.pickups.containsKey(key)){if(cache.pickups.get(key)==null)continue;}
            else{
                cache.pickups.put(key,null);
                if(!geometry.clear(pickup,from,boat.mode(),obstacles)){carryPlanDebug="posed pickup endpoint blocked "+geometry.debug();continue;}
                var prepareObstacles=new ArrayList<double[]>(obstacles);prepareObstacles.add(boat.rescueBody(parent,boat.toLocal(parent.position())));
                if(!geometry.prepareClear(from,boat.mode(),prepareObstacles)){carryPlanDebug="supported tuck blocked "+geometry.debug();continue;}
                if(pickupRoute(parent,child,pickup,from,geometry).isEmpty()){carryPlanDebug="posed pickup route blocked "+pickup+" "+geometry.debug();continue;}
                if(!carryTransferClear(child,from,pickup.add(offset),pickup,geometry,obstacles)){carryPlanDebug="lift blocked "+carryTransferDebug;continue;}
                cache.pickups.put(key,new CarryPickup(from,pickup,offset,ownerDestination,geometry));
            }
            if(carriedRoute(parent,child,pickup,delivery,offset,geometry).isEmpty()){carryPlanDebug="posed carried pair route blocked";continue;}
            carryPlanDebug="ready pickup="+pickup+" offset="+offset;
            return new CarryPickup(from,pickup,offset,delivery,geometry);
        }
        return null;
    }

    boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination) {
        return beginCarry(child,parent,destination,ownerDestination,new CarrySearch());
    }

    boolean beginCarry(YukkuriEntity child,YukkuriEntity parent,double[] destination,Vec3 ownerDestination,CarrySearch search) {
        if(carryTrip!=null)return carryTrip.child.equals(child.getUUID());
        Vec3 from=boat.toLocal(child.position()),to=new Vec3(destination[0],destination[1],destination[2]);
        var plan=carryPickup(child,parent,from,to,ownerDestination,search);if(plan==null)return false;
        boolean crawl=from.distanceToSqr(plan.childAt())>1e-6;
        if(crawl&&!boat.movePreparationCrawl(child,plan.childAt()))return false;
        boat.seatState.ownerServiceSeat(boat.toLocal(parent.position()));boat.seatState.clearOwnerSeatMove();
        if(!crawl){boat.seatState.clearMove(child.getUUID());boat.seatState.putResident(child.getUUID(),new double[]{from.x,from.y,from.z,boat.radiusOf(child)});}
        carryTrip=new CarryTrip(child.getUUID(),plan.childAt(),to,plan.at(),plan.delivery(),plan.offset(),boat.level().getGameTime());
        carryTrip.phase=crawl?-2:-1;carryTrip.geometry=plan.geometry();carryTrip.lastAnchor=from;
        boat.syncSeats();return true;
    }

    boolean beginReturnCarry(YukkuriEntity child,YukkuriEntity parent,Vec3 target){
        // A tucked hat may need more of the current tread before it unfolds. Keep the
        // original reservation: returnCareSeats subsequently crawls the last distance upright.
        long stamp=boat.mode().ordinal();
        for(var point:List.of(boat.toLocal(child.position()),boat.toLocal(parent.position()),target))
            for(double value:new double[]{point.x,point.y,point.z})stamp=stamp*31+Math.round(value*1000);
        for(double value:new double[]{child.getBbWidth(),parent.getBbWidth(),child.growthScale(),parent.growthScale(),boat.unit()})stamp=stamp*31+Math.round(value*100000);
        for(var obstacle:boat.takenExcept(child.getUUID()))for(double value:obstacle)stamp=stamp*31+Math.round(value*1000);
        var retry=boat.careSeats.returnRetries.get(child.getUUID());long now=boat.level().getGameTime();
        if(retry!=null&&retry.geometryStamp()==stamp&&now<retry.at())return false;
        var search=new CarrySearch();var failures=new StringBuilder();
        for(double dz:new double[]{0,.03,.08,.16,.24,.36,.48,.60,.72,.84,-.08,-.24}){
            var at=target.add(0,0,dz);
            if(Math.abs(HatVessel.surface(boat.mode(),at.x,at.z)-target.y)>1e-5)continue;
            if(!HatVessel.fits(boat.mode(),at,boat.radiusOf(child),boat.layoutTaken(child.getUUID()),HatVessel.bodySpace(child,boat.hullScale(),boat.unit())))continue;
            var destination=new double[]{at.x,at.y,at.z,boat.radiusOf(child)};
            if(beginCarry(child,parent,destination,null,search)){
                boat.careSeats.returnRetries.remove(child.getUUID());
                boat.careSeats.returningSeats.putIfAbsent(child.getUUID(),new ServiceSeat(
                        new double[]{target.x,target.y,target.z,boat.radiusOf(child)},boat.region(),destination,null,false,false));
                return true;
            }
            if(failures.length()<1800)failures.append(" dz=").append(dz).append(' ').append(carryPlanDebug).append(';');
        }
        carryPlanDebug="return original="+target+failures;
        boat.careSeats.returnRetries.put(child.getUUID(),new ReturnRetry(stamp,now+80));
        return false;
    }

    void setCarryWalk(YukkuriEntity parent,Vec3 target,List<Vec3> path){
        boat.seatState.ownerServiceSeat(target);
        boat.seatState.ownerSeatMove(new SeatMove(boat.toLocal(parent.position()),boat.level().getGameTime(),Math.max(16,(int)Math.ceil(HatSurfaceRoute.length(path)/.20)),false,path));
    }

    void tickCarry() {
        if (carryTrip==null) return;
        var trip=carryTrip;
        var parent=boat.owner();
        var child=boat.level() instanceof ServerLevel server && server.getEntity(trip.child) instanceof YukkuriEntity y?y:null;
        if (parent==null || child==null) return;
        if (!parent.isAlive() || parent.getVehicle()!=boat || child.getVehicle()!=boat || !boat.isAfloat()) {
            // Losing a holder releases a real short fall onto the measured patch below the child.
            Vec3 at=boat.toLocal(child.position()); double h=HatVessel.footprint(boat.mode(),at.x,at.z,boat.radiusOf(child));
            if (child.getVehicle()==boat && Double.isFinite(h)) {
                var target=new Vec3(at.x,h,at.z); boat.seatState.putResident(child.getUUID(),new double[]{target.x,target.y,target.z,boat.radiusOf(child)});
                boat.seatState.setMove(child.getUUID(),new SeatMove(at,boat.level().getGameTime(),12,true));
            }
            carryTrip=null; boat.syncSeats(); return;
        }
        boat.stopPaddling();
        if(trip.phase==-2){
            if(recoverCarry){
                if(!boat.movePreparationCrawl(child,trip.from))return;
                recoverCarry=false;
            }
            if(boat.atSeat(child,trip.from)){boat.seatState.clearMove(child.getUUID());trip.phase=-1;trip.at=boat.level().getGameTime();boat.syncSeats();}
            return;
        }
        if(trip.geometry==null)trip.geometry=HatCareGeometry.plan(parent,child,boat.unit(),trip.facing);
        if(trip.geometry==null)return;
        float carryYaw=SurfaceApproach.yaw(Vec3.ZERO,trip.facing.yRot(boat.turn()));
        SurfaceApproach.face(parent,carryYaw);
        SurfaceApproach.face(child,carryYaw+180);
        var obstacles=boat.takenExcept(child.getUUID());
        if(trip.phase==-1){
            var preparation=new ArrayList<double[]>(obstacles);preparation.add(boat.rescueBody(parent,boat.toLocal(parent.position())));
            if(!trip.geometry.preparationClear(trip.from,boat.mode(),preparation,Math.min(.6,(boat.level().getGameTime()-trip.at)/20.0),false))return;
            if(boat.level().getGameTime()-trip.at<12)return;
            var path=pickupRoute(parent,child,trip.pickup,trip.from,trip.geometry);
            if(path.isEmpty())return;
            setCarryWalk(parent,trip.pickup,path);trip.phase=0;trip.at=boat.level().getGameTime();recoverCarry=false;
            trip.geometry.discardPreparationSamples();boat.syncSeats();return;
        }
        if(recoverCarry){
            recoverCarry=false;
            if(trip.phase==0||trip.phase==2){
                var path=trip.phase==0?pickupRoute(parent,child,trip.pickup,trip.from,trip.geometry)
                        :carriedRoute(parent,child,boat.toLocal(parent.position()),trip.ownerDestination,trip.offset,trip.geometry);
                if(path.isEmpty()){recoverCarry=true;return;}
                setCarryWalk(parent,trip.phase==0?trip.pickup:trip.ownerDestination,path);boat.syncSeats();
            }
        }
        if(trip.phase==0){
            if(boat.atSeat(parent,trip.pickup)){
                if(FamilyFeedingContact.mouthsMeet(parent,child))trip.contact++;else trip.contact=0;
                if(trip.contact>=12){trip.phase=1;trip.at=boat.level().getGameTime();boat.syncSeats();}
            }
            return;
        }
        if(trip.phase==1&&boat.level().getGameTime()-trip.at>=12){
            var path=carriedRoute(parent,child,boat.toLocal(parent.position()),trip.ownerDestination,trip.offset,trip.geometry);
            if(path.isEmpty()){
                carryTrip=new CarryTrip(trip.child,trip.from,trip.from,trip.pickup,trip.pickup,trip.offset,boat.level().getGameTime());
                carryTrip.geometry=trip.geometry;carryTrip.phase=3;boat.syncSeats();return;
            }
            setCarryWalk(parent,trip.ownerDestination,path);trip.phase=2;trip.at=boat.level().getGameTime();boat.syncSeats();
        }else if(trip.phase==2&&boat.atSeat(parent,trip.ownerDestination)){
            trip.phase=3;trip.at=boat.level().getGameTime();boat.syncSeats();
        }else if(trip.phase==3&&boat.level().getGameTime()-trip.at>=12){
            boat.seatState.putResident(child.getUUID(),new double[]{trip.destination.x,trip.destination.y,trip.destination.z,boat.radiusOf(child)});
            trip.phase=4;trip.at=boat.level().getGameTime();boat.syncSeats();
        }else if(trip.phase==4){
            var releaseObstacles=new ArrayList<double[]>(obstacles);releaseObstacles.add(boat.rescueBody(parent,boat.toLocal(parent.position())));
            boolean clear=true;
            for(int tick=0;tick<=12&&clear;tick++)clear=trip.geometry.preparationClear(trip.destination,boat.mode(),releaseObstacles,tick/20.0,true);
            if(clear){trip.phase=5;trip.at=boat.level().getGameTime();boat.syncSeats();return;}
            // A failed/recovered transfer keeps the child supported and tucked until the adult really withdraws.
            if(!boat.changingOwnerSeat()&&boat.level().getGameTime()%20==0){
                var retreat=boat.ownerSeat(parent);var path=pickupRoute(parent,child,retreat,trip.destination,trip.geometry);
                if(!path.isEmpty())setCarryWalk(parent,retreat,path);
            }
        }else if(trip.phase==5&&boat.level().getGameTime()-trip.at>=12){
            carryTrip=null;
            var service=boat.careSeats.serviceSeats.get(child.getUUID());
            if(service!=null&&service.ownerService()!=null){
                if(!boat.moveOwnerSeat(parent,service.ownerService(),false))boat.releaseCareSeat(child.getUUID());
            }
            else if(boat.careSeats.serviceSeats.isEmpty()&&boat.careSeats.returningSeats.isEmpty())boat.moveOwnerSeat(parent,boat.ownerSeat(parent),false);
            boat.syncSeats();
        }
    }

    String carryActivity(Entity rider){
        var trip=carryPose();if(trip==null)return Behaviors.IDLE;
        if(rider.getUUID().equals(boat.ownerYukkuri()))return Behaviors.BOAT_CARRY_YOUNG;
        if(!rider.getUUID().equals(trip.child))return Behaviors.IDLE;
        return trip.phase==-2?Behaviors.HAT_WALK:trip.phase==-1?Behaviors.BOAT_PREPARE_CARRY:trip.phase==5?Behaviors.BOAT_RELEASE_CARRY:Behaviors.BOAT_BE_CARRIED;
    }

    float carrySeconds(float partial){
        var trip=carryPose();return trip==null?0:(float)Math.max(0,(boat.level().getGameTime()-trip.at+partial)/20.0);
    }

    boolean carryingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && trip.phase<=3 && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(boat.ownerYukkuri()));
    }

    boolean transportingInHat(Entity rider) {
        var trip=carryPose(); return trip!=null && (rider.getUUID().equals(trip.child)||rider.getUUID().equals(boat.ownerYukkuri()));
    }

    UUID hatCarrier(YukkuriEntity child) {
        var trip=carryPose(); return trip!=null && trip.phase>0 && trip.phase<=3 && trip.child.equals(child.getUUID())?boat.ownerYukkuri():null;
    }

    Vec3 hatCarryAnchor(YukkuriEntity child) {
        var trip=carryPose();
        if(trip==null || !trip.child.equals(child.getUUID()))return null;
        var parent=boat.getPassengers().stream().filter(p->p.getUUID().equals(boat.ownerYukkuri())).findFirst().orElse(null);
        return trip.phase==2 && parent!=null?parent.position().add(trip.offset.scale(boat.unit()).yRot(boat.turn())):boat.toWorld(carriedLocal(trip));
    }

    Vec3 ownerCarryLocal(YukkuriEntity parent) {
        Vec3 target=boat.ownerServiceSeat(parent); if (target==null) target=boat.ownerSeat(parent);
        var movement=boat.ownerServiceMove(parent);
        if (movement==null) return target;
        double time=Math.clamp(boat.level().getGameTime()-movement.start(),0,movement.ticks());
        return movement.route().isEmpty()?movement.from():boat.walkingLocal(parent,movement,time);
    }

    Vec3 carriedLocal(CarryTrip trip) {
        if(trip.phase==-2){
            var child=boat.getPassengers().stream().filter(p->p.getUUID().equals(trip.child)).findFirst().orElse(null);
            return child==null?trip.lastAnchor:boat.toLocal(child.position());
        }
        if (trip.phase<=0) return trip.from;
        if (trip.phase>=4) return trip.destination;
        var parent=boat.getPassengers().stream().filter(p->p.getUUID().equals(boat.ownerYukkuri())&&p instanceof YukkuriEntity)
                .map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent==null) return trip.lastAnchor;
        Vec3 anchor=ownerCarryLocal(parent).add(trip.offset);
        double q=Math.clamp((boat.level().getGameTime()-trip.at)/12.0,0,1); q=q*q*(3-2*q);
        trip.lastAnchor=trip.phase==1?trip.from.lerp(anchor,q):trip.phase==3?anchor.lerp(trip.destination,q):anchor;
        return trip.lastAnchor;
    }

    CompoundTag saveCarry(CarryTrip trip) {
        var tag=new CompoundTag();tag.putUUID("Child",trip.child);if(boat.ownerYukkuri()!=null)tag.putUUID("Holder",boat.ownerYukkuri());
        tag.putInt("Phase",trip.phase);tag.putLong("At",trip.at);tag.putLong("Elapsed",Math.max(0,boat.level().getGameTime()-trip.at)); HatBoatEntity.point(tag,
                "From",trip.from);HatBoatEntity.point(tag,"To",trip.destination);
        HatBoatEntity.point(tag,"Pickup",trip.pickup);HatBoatEntity.point(tag,"OwnerTo",trip.ownerDestination);HatBoatEntity.point(tag,"Offset",
                trip.offset);HatBoatEntity.point(tag,"Facing",trip.facing);
        // The pickup/preparation point can be a temporary care station, not the child's real bed.
        var reservation=boat.careSeats.returningSeats.get(trip.child);
        if(reservation==null)reservation=boat.careSeats.serviceSeats.get(trip.child);
        if(reservation!=null){
            var original=reservation.original();
            HatBoatEntity.point(tag,"Return",new Vec3(original[0],original[1],original[2]));
            tag.putDouble("ReturnRadius",original[3]);tag.putString("ReturnRegion",reservation.originalRegion());
        }
        var parent=boat.getPassengers().stream().filter(p->p.getUUID().equals(boat.ownerYukkuri())&&p instanceof YukkuriEntity).map(p->(YukkuriEntity)p).findFirst().orElse(null);
        if (parent!=null) HatBoatEntity.point(tag,"OwnerNow",ownerCarryLocal(parent));
        HatBoatEntity.point(tag,"ChildNow",carriedLocal(trip)); return tag;
    }

    CarryTrip carryPose() {
        if (!boat.level().isClientSide) return carryTrip;
        var tag=boat.seatsData().getCompound("Carry"); if (!tag.hasUUID("Child")) return null;
        var trip=new CarryTrip(tag.getUUID("Child"),HatBoatEntity.point(tag,"From"),HatBoatEntity.point(tag,"To"),HatBoatEntity.point(tag,"Pickup"),
                HatBoatEntity.point(tag,"OwnerTo"),HatBoatEntity.point(tag,"Offset"),tag.getLong("At"));
        trip.phase=tag.getInt("Phase");trip.lastAnchor=HatBoatEntity.point(tag,"ChildNow");if(tag.contains("FacingX"))trip.facing=HatBoatEntity.point(tag,"Facing");return trip;
    }

    Vec3 escortPlace(YukkuriEntity parent,YukkuriEntity child,Vec3 brim){
        double distance=(parent.getBbWidth()+child.getBbWidth())*.55/boat.unit();
        for(double side:new double[]{0,.8,-.8,1.6,-1.6}){
            double x=brim.x+side,z=brim.z+distance;
            double y=HatVessel.travelSupport(boat.mode(),x,z,boat.radiusOf(parent));
            var at=new Vec3(x,y,z);
            if(Double.isFinite(y))return at;
        }
        return null;
    }
}
