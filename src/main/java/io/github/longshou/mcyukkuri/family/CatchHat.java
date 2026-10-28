package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife.HatPose;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stand;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Catcher staging for one real hat; the owner retains the goal and pickup controllers. */
final class CatchHat {
    static final int APPROACH_LEAD=1200, CATCH_WAIT=600;
    private static final double SEEK_RANGE_SQR=48*48;
    private final FamilyLife o;
    /** Marisa's braid takes off the real hat or puts it back on. */
    private enum HatMove { OFF, ON, OFF_SELF, ON_SELF }
    /**
     * One in-flight clip binds the delivery agreement that authorises a physical accessory transfer.
     * It is deliberately transient: loading never resumes a
     * half-finished braid clip, while an already placed hat remains in the existing station NBT.
     */
    private record HatAction(HatMove move,UUID brood,UUID mother,FamilyLife.DeliveryStation motherAt,
                             FamilyLife.DeliveryStation meeting,HatPose pose,Stand stand) {}
    private HatAction action;
    private int hatTicks, cradleLinger, fetchTicks, hatApproach, releaseWait, dutyTicks;
    private int fetchPassengers, retrieveTicks;
    /** Presentation of the last executed duty phase, never a probe that starts or changes the duty. */
    private String dutyActivity=Behaviors.IDLE;
    private PlacedHatEntity fetching;
    /** The NEST unload of {@link #fetching}, recreated after a reload and never saved (spec 11.2, 12). */
    private IntentRunner runner;
    private PlacedHatEntity runnerHat;
    private boolean dutyGaveUp;
    private long fetchRetryAt;
    /** The helper's confirmed land hat and the mother pose it was placed for. */
    private UUID deliveryBrood, deliveryHat;
    private Vec3 deliveryStation, deliveryHatAt;
    private float deliveryStationYaw, deliveryHatYaw;
    /** Agreed before either hat or child is created; both parents walk to this same physical site. */
    private FamilyLife.DeliveryStation rendezvous;
    private UUID dutyBrood;
    private int nextSiteSearch;
    private record SiteCandidate(FamilyLife.DeliveryStation site,Vec3 helper,double lowerBound) {}
    private record RejectedSite(Vec3 mother,Vec3 helper,int until) {}
    private final List<RejectedSite> rejectedSites=new ArrayList<>();
    private int siteSelectedAt;

    CatchHat(FamilyLife o) { this.o=o; }
    boolean moving() { return action!=null; }
    boolean fetching() { return fetching!=null; }
    boolean cleanupPending() { return fetching!=null||action!=null&&(action.move()==HatMove.ON||action.move()==HatMove.ON_SELF)||cradleLinger>0&&cradleEntity()!=null; }
    String describe() { return "move="+(action==null?"none":action.move())+" linger="+cradleLinger+" fetch="+(fetching==null?"none":fetching.getUUID())
            +" fetchTicks="+fetchTicks+" dutyTicks="+dutyTicks+" gaveUp="+dutyGaveUp
            +" intent="+(runner==null?"none":runner.intent().id()+":"+runner.result())+" rendezvous="+rendezvous+" approach="+hatApproach; }
    boolean dutyGaveUp() { return dutyGaveUp; }
    void resetDuty() { stopAction("duty_reset");dutyTicks=nextSiteSearch=0;dutyGaveUp=false;dutyBrood=null;rendezvous=null;rejectedSites.clear();clearDeliveryStation(); }
    void resetApproach() { hatApproach=0;dutyActivity=Behaviors.IDLE; }
    void linger(int ticks) { cradleLinger=ticks; }
    void tickLinger() {
        if(cradleLinger>0&&--cradleLinger==0){
            var hat=cradleEntity();if(hat!=null)fetchHat(hat);
        }
    }
    String dutyActivity() { return dutyActivity; }
    String activity() {
        if(action==null)return Behaviors.IDLE;
        return switch(action.move()){case OFF->Behaviors.HAT_OFF;case ON->Behaviors.HAT_ON;case OFF_SELF->Behaviors.HAT_OFF_SELF;case ON_SELF->Behaviors.HAT_ON_SELF;};
    }

    private static boolean hatted(YukkuriEntity y) {
        return y.getVariant()==YukkuriVariant.MARISA&&y.life().hasAccessory()&&!y.isPassenger()
                &&y.subspecies().kind()!=SubspeciesLife.Kind.SHELL_MARISA
                &&y.subspecies().kind()!=SubspeciesLife.Kind.KOTATSU_MARISA; // a kotatsu is no hat to catch in
    }

    /** Only Marisa midwife with her hat: a hatted Marisa father close by, else a hatted Marisa mother catches her own young. */
    static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood) {
        if(brood.father!=null&&mother.level() instanceof ServerLevel level&&level.getEntity(brood.father) instanceof YukkuriEntity father
                &&father.isAlive()&&!father.isPassenger()&&(hatted(father)||father.family().cradleEntity()!=null)
                &&father.distanceToSqr(mother)<SEEK_RANGE_SQR&&father.life().canRecognize(mother))return father;
        // Setting down the real accessory must not change who is catching halfway through labor.
        return hatted(mother)||mother.family().cradleEntity()!=null?mother:null;
    }

    /** Where the hat goes to catch (hat_vessel.json, the end pose of the braid clips). A land Marisa presses it upside down
     *  before the outlet, the live exit just inside its opening (a mother on her own sets it where her own clip puts it). */
    static HatPose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,HatGeometry.Role role){
        var place=HatGeometry.placement(role);
        double k=HatGeometry.hatScale(catcher)*catcher.getScale()/16,body=mother.growthScale()*mother.getScale()/16;
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);float yaw=mother.yBodyRot+place.hatYawInMother();
        if(role==HatGeometry.Role.SELF){
            var at=FamilyGeometry.toWorld(mother,place.offset().scale(mother.growthScale()/16));
            return new HatPose(new Vec3(at.x,mother.getY(),at.z),yaw,role);
        }
        if(brood.kind==FamilyData.BirthKind.STEM){
            // The opening right under the fruit (where they drop plumb), its long axis along the stem; the near brim lip
            // beyond the opening may slip under her front, but the opening itself starts in front of her body. Turned
            // whichever way puts the helper on the far side, facing her. (The whole brim used to be kept clear of her:
            // the opening then began past the nearer fruit, which all came down on the ground.)
            double sx=0,sz=0;int n=brood.children.size();
            for(int i=0;i<n;i++){
                var child=brood.children.get(i);
                var c=FamilyGeometry.childBase(n,i,1,mother.growthScale(),brood.variation,mother.getVariant(),child.variant,GrowthSize.physicalBirth(brood.kind,child));
                sx+=c.x;sz+=c.z;
            }
            var under=FamilyGeometry.toWorld(mother,new Vec3(sx/Math.max(1,n),0,sz/Math.max(1,n)));
            double ahead=under.subtract(mother.position()).dot(forward),least=stemClearance(mother,catcher);
            var at=under.add(forward.scale(Math.max(0,least-ahead)));HatPose best=null;double far=-1;
            for(float turn:new float[]{0,180}){
                var pose=new HatPose(new Vec3(at.x,mother.getY(),at.z),mother.yBodyRot+turn,role);
                double d=stand(pose,catcher).at().distanceToSqr(mother.position());if(d>far){far=d;best=pose;}
            }
            return best;
        }
        int next=0;
        if(brood.kind==FamilyData.BirthKind.LIVE)while(next<brood.children.size()-1&&brood.children.get(next).born)next++;
        // The long axis across her, the near brim just beyond the live exit and the newborn's round body: she pushes it
        // out over the brim into the hat.
        double r=HatGeometry.radius(LiveBirthGeometry.childDimensions(brood.children.get(next),mother.getScale()).width());
        var at=LiveBirthGeometry.position(mother,next).add(forward.scale(r+place.gap()*body+place.nearBrim()*k));
        return new HatPose(new Vec3(at.x,mother.getY(),at.z),yaw,role);
    }

    /** Least distance from the mother's centre to a stem catch hat's centre: her body's half width and the opening's half
     *  length along the hat, so the fruit falls into the opening rather than onto her; the brim lip may lie under her. */
    static double stemClearance(YukkuriEntity mother,YukkuriEntity catcher){
        double k=HatGeometry.hatScale(catcher)*catcher.getScale()/16;
        return mother.getBbWidth()*.5+HatGeometry.opening()[3]*k;
    }

    /** The catcher's place for a hat at this pose: the inverse of the clip's placement. */
    static Stand stand(HatPose pose,YukkuriEntity catcher){
        var place=HatGeometry.placement(pose.role());float yaw=pose.yaw()-place.yaw();
        var offset=new Vec3(place.offset().x,0,place.offset().z).scale(catcher.growthScale()*catcher.getScale()/16);
        var at=pose.at().subtract(offset.yRot((float)Math.toRadians(180-yaw)));
        return new Stand(new Vec3(at.x,pose.at().y,at.z),yaw);
    }

    private static HatPose poseOf(PlacedHatEntity hat){return new HatPose(hat.position(),hat.getYRot(),hat.role());}

    /** The ground where the hat would go takes it. */
    private boolean settles(HatPose pose){
        var spot=BlockPos.containing(pose.at());
        if(!o.level().hasChunkAt(spot))return false;
        return o.level().getFluidState(spot).isEmpty()&&o.level().getBlockState(spot.below()).isFaceSturdy(o.level(),spot.below(),Direction.UP);
    }

    /** Step onto the spot the braid clip is authored from and start it. */
    private boolean beginHat(HatMove move,HatPose target,Stand stand){
        var next=newAction(move,target,stand);
        String refusal=actionRefusal(next,false);
        if(refusal!=null)return false;
        // Place only from the authored stand; the bounded duty handles an unreachable spot.
        hatApproach++;
        if(!approachHatStand(stand))return true;
        // Navigation reaching the point has no yaw contract. Turn first, then bind the current maternal pose that
        // authorised this clip; otherwise a normal arrival can be rejected before it gets to face the brim.
        o.entity().getNavigation().stop();o.face(stand.yaw());
        next=bindMotherAt(next);
        refusal=actionRefusal(next,true);
        if(refusal!=null)return false;
        hatApproach=0;
        BirthStaging.caption(o.entity(),switch(move){
            case OFF->"接生者走到出口前方，用辫子摘下自己的帽子（hat_off）";
            case OFF_SELF->"母油自己摘下帽子（hat_off_self）";
            case ON->"幼体已被放出，接生者用辫子把帽子戴回（hat_on）";
            case ON_SELF->"接生者把帽子戴回头上（hat_on_self）";});
        action=next;
        hatTicks=(int)Math.round((move==HatMove.OFF_SELF||move==HatMove.ON_SELF?1.8:1.6)*20);
        return true;
    }

    private HatAction newAction(HatMove move,HatPose pose,Stand stand){
        if(move==HatMove.ON||move==HatMove.ON_SELF)return new HatAction(move,null,null,null,null,null,stand);
        var mother=move==HatMove.OFF?o.target():o.entity();
        var brood=mother==null?null:mother.family().brood();
        if(brood==null||brood.id==null||pose==null||stand==null)return null;
        var at=new FamilyLife.DeliveryStation(mother.position(),mother.yBodyRot);
        return new HatAction(move,brood.id,mother.getUUID(),at,move==HatMove.OFF?rendezvous:null,pose,stand);
    }

    /** Refresh only the actual mother's settled pose after the helper walks in; her agreed brood, target and hat pose stay fixed. */
    private HatAction bindMotherAt(HatAction candidate){
        if(candidate==null||candidate.move()==HatMove.ON||candidate.move()==HatMove.ON_SELF
                ||!(o.level().getEntity(candidate.mother()) instanceof YukkuriEntity mother))return candidate;
        return new HatAction(candidate.move(),candidate.brood(),candidate.mother(),
                new FamilyLife.DeliveryStation(mother.position(),mother.yBodyRot),candidate.meeting(),candidate.pose(),candidate.stand());
    }

    /**
     * The same contract guards start, every animation beat and the irreversible accessory transfer. It must remain
     * side-effect free: only the mother advances her finite acceptance window.
     */
    private String actionRefusal(HatAction candidate,boolean requireHelperStand){
        if(candidate==null)return "missing_action";
        if(candidate.move()==HatMove.ON||candidate.move()==HatMove.ON_SELF)
            return actionOwnerReady()?null:"owner_unavailable";
        if(!dutyReady())return "duty_unready";
        if(!(o.level().getEntity(candidate.mother()) instanceof YukkuriEntity mother)||!mother.isAlive())return "mother_missing";
        var brood=mother.family().brood();
        if(brood==null||brood.cancelled||brood.complete()||brood.id==null||!brood.id.equals(candidate.brood()))return "brood_changed";
        if(catcher(mother,brood)!=o.entity())return "catcher_changed";
        if(!mother.family().acceptsCatch(brood))return "mother_window_closed";
        if(!at(mother,candidate.motherAt()))return "mother_moved";
        if(candidate.move()==HatMove.OFF){
            if(o.task()!=Task.MIDWIFE||o.target()!=mother)return "midwife_handoff_changed";
            if(candidate.meeting()==null||!candidate.meeting().equals(rendezvous)||!at(mother,candidate.meeting()))return "meeting_changed";
            String site=siteRefusal(mother,brood,candidate.meeting());
            if(site!=null)return "meeting_"+site;
        }else if(o.task()!=Task.BROOD||mother!=o.entity())return "self_handoff_changed";
        if(candidate.pose()==null||!settles(candidate.pose()))return "hat_support";
        if(requireHelperStand&&!at(o.entity(),candidate.stand()))return "helper_moved";
        return null;
    }

    /** Common interruption gate for either irreversible hat direction; OFF adds the adult birth-duty requirement below. */
    private boolean actionOwnerReady(){
        return o.entity().isAlive()&&!o.entity().isNoAi()&&!o.entity().isPassenger()&&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()
                &&!o.entity().operations().active()&&!o.entity().life().isSleeping()&&!o.entity().life().hasUrgentNeed()&&o.entity().life().mealTicks()==0
                &&!o.awaitingLanding();
    }

    private static boolean at(YukkuriEntity entity,FamilyLife.DeliveryStation station){
        return station!=null&&SurfaceApproach.arrived(entity,station.at())
                &&Math.abs(Mth.wrapDegrees(entity.yBodyRot-station.yaw()))<=3;
    }

    private static boolean at(YukkuriEntity entity,Stand stand){
        return stand!=null&&SurfaceApproach.arrived(entity,stand.at())
                &&Math.abs(Mth.wrapDegrees(entity.yBodyRot-stand.yaw()))<=3;
    }

    /** Interrupts only an uncommitted clip or the running unload intent. A physical hat and its saved station are never removed here. */
    void stopAction(String reason){
        if(runner!=null){runner.stop(reason);runner=null;runnerHat=null;}
        var stopped=action;
        if(stopped==null)return;
        action=null;hatTicks=0;
        if(stopped.move()==HatMove.OFF||stopped.move()==HatMove.OFF_SELF){
            if(stopped.meeting()!=null&&stopped.meeting().equals(rendezvous))rendezvous=null;
            SurfaceApproach.stopWalking(o.entity());
        }
        GameplayLog.event(o.entity(),"family","catch hat action aborted move={} brood={} mother={} reason={}",
                stopped.move(),stopped.brood(),stopped.mother(),reason);
    }

    private boolean approachHatStand(Stand stand){
        boolean arrived=o.target()!=null
                ?SurfaceApproach.walkAround(o.entity(),stand.at(),o.target(),.8):SurfaceApproach.walk(o.entity(),stand.at(),.8);
        if(!arrived)GameplayLog.limited(o.entity(),"family","catch.approach",200,
                "catch stand approaching brood={} mother={} from={} target={} pathDone={} horizontalCollision={}",
                dutyBrood,o.target()==null?null:o.target().getUUID(),o.entity().position(),stand.at(),
                o.entity().getNavigation().isDone(),o.entity().horizontalCollision);
        return arrived;
    }

    FamilyLife.DeliveryStation rendezvous(YukkuriEntity mother,FamilyData.Brood brood) {
        return !dutyGaveUp&&brood!=null&&brood.id.equals(dutyBrood)&&o.target()==mother
                &&o.task()==Task.MIDWIFE&&o.dutyReady()?rendezvous:null;
    }

    private HatPose atStation(YukkuriEntity mother,FamilyData.Brood brood,FamilyLife.DeliveryStation site) {
        var pose=catchPose(mother,brood,o.entity(),HatGeometry.Role.HELPER);
        float turn=site.yaw()-mother.yBodyRot;
        var at=site.at().add(pose.at().subtract(mother.position()).yRot((float)Math.toRadians(-turn)));
        return new HatPose(at,pose.yaw()+turn,pose.role());
    }

    private String siteRefusal(YukkuriEntity mother,FamilyData.Brood brood,FamilyLife.DeliveryStation site) {
        if(!SurfaceApproach.supported(mother,site.at()))return "mother_support";
        var pose=atStation(mother,brood,site);var helper=stand(pose,o.entity());
        if(!settles(pose))return "hat_support";
        if(!SurfaceApproach.supported(o.entity(),helper.at()))return "helper_support";
        // Check the whole visible brim, not just the centre block beneath the hat.
        double unit=HatGeometry.hatScale(o.entity())*o.entity().getScale()/16;
        var brim=HatGeometry.brim();double width=Math.max(brim[0],brim[1])*unit;
        var hatBox=new AABB(pose.at().x-width,pose.at().y+.01,pose.at().z-width,
                pose.at().x+width,pose.at().y+.3,pose.at().z+width);
        var motherBox=motherAt(mother,brood,site.at());
        if(!o.level().noCollision(mother,motherBox))return "mother_belly_room";
        var helperBox=o.entity().getBoundingBox().move(helper.at().subtract(o.entity().position()));
        // A stem hat's near brim lip may lie under her front (stemClearance); a live catch keeps the whole brim clear of her.
        double motherGap=brood.kind==FamilyData.BirthKind.STEM?stemClearance(mother,o.entity())-.01:mother.getBbWidth()*.5+width;
        if(motherBox.intersects(helperBox))return "parent_bodies_overlap";
        if(site.at().subtract(pose.at()).horizontalDistance()<=motherGap)return "mother_brim_overlap";
        if(helper.at().subtract(pose.at()).horizontalDistance()<=o.entity().getBbWidth()*.5+width)return "helper_brim_overlap";
        return o.level().getBlockCollisions(o.entity(),hatBox).iterator().hasNext()?"hat_blocked":null;
    }

    private AABB motherAt(YukkuriEntity mother,FamilyData.Brood brood,Vec3 at){
        return brood.kind==FamilyData.BirthKind.LIVE
                ?LiveBirthGeometry.bodyDimensions(mother,mother.growthScale(),LiveBirthGeometry.belly(1,
                        brood.children.size()-brood.unborn(),brood.children.size())).scale(mother.getScale()).makeBoundingBox(at)
                :mother.getBoundingBox().move(at.subtract(mother.position()));
    }

    private void findSite(YukkuriEntity mother,FamilyData.Brood brood) {
        // Compare the whole nearby area, not the first geometrically valid point beside the nest.
        rejectedSites.removeIf(site->site.until()<=dutyTicks);
        int paths=0,checked=0;String reason="mother_floor";
        var candidates=new ArrayList<SiteCandidate>();
        for(int radius=0;radius<=3;radius++)for(int side=0;side<(radius==0?1:8);side++) {
            double angle=side*Math.PI/4;
            var at=SurfaceApproach.floor(mother,mother.position().add(Math.cos(angle)*radius,0,Math.sin(angle)*radius));
            if(at==null)continue;
            float heading=brood.kind==FamilyData.BirthKind.LIVE?BirthExit.yawToward(mother,0,o.entity().position()):mother.yBodyRot;
            for(float turn:new float[]{0,45,-45,90,-90,135,-135,180}) {
                var site=new FamilyLife.DeliveryStation(at,heading+turn);
                checked++;String refusal=siteRefusal(mother,brood,site);
                if(refusal!=null){reason=refusal;continue;}
                var helper=stand(atStation(mother,brood,site),o.entity()).at();
                if(rejectedSites.stream().anyMatch(old->old.mother().distanceToSqr(at)<.5625&&old.helper().distanceToSqr(helper)<.5625))continue;
                candidates.add(new SiteCandidate(site,helper,1.5*mother.position().distanceTo(at)+o.entity().position().distanceTo(helper)));
            }
        }
        candidates.sort(Comparator.comparingDouble(SiteCandidate::lowerBound));
        SiteCandidate best=null;double bestCost=Double.POSITIVE_INFINITY;
        for(var candidate:candidates){
            if(candidate.lowerBound()>=bestCost||paths>=12)break;
            var site=candidate.site();paths++;
            double motherRoute=SurfaceApproach.routeLength(mother,site.at(),o.entity().getBoundingBox());
            double helperRoute=Double.isFinite(motherRoute)?SurfaceApproach.routeLength(o.entity(),candidate.helper(),motherAt(mother,brood,site.at())):Double.POSITIVE_INFINITY;
            if(!Double.isFinite(helperRoute)){
                reason=Double.isFinite(motherRoute)?"helper_route":"mother_route";
                rejectSite(site.at(),candidate.helper(),400);continue;
            }
            double cost=1.5*motherRoute+helperRoute;
            if(cost<bestCost){best=candidate;bestCost=cost;}
        }
        if(best!=null){
            rendezvous=best.site();siteSelectedAt=dutyTicks;
            GameplayLog.event(o.entity(),"family","catch site selected brood={} mother={} station={} yaw={} helper={} checked={} paths={} routeCost={}",
                    brood.id,mother.getUUID(),rendezvous.at(),rendezvous.yaw(),best.helper(),checked,paths,bestCost);
            return;
        }
        logSiteFailure(brood,checked,paths,reason);
    }

    private void rejectSite(Vec3 mother,Vec3 helper,int ticks){
        if(rejectedSites.size()>=32)rejectedSites.removeFirst();
        rejectedSites.add(new RejectedSite(mother,helper,dutyTicks+ticks));
    }

    /** Routing owns movement failure; the family owns only the finite lifetime of this agreed site. */
    private String siteStalled(YukkuriEntity mother,FamilyData.Brood brood){
        boolean motherReady=SurfaceApproach.arrived(mother,rendezvous.at())&&Math.abs(Mth.wrapDegrees(mother.yBodyRot-rendezvous.yaw()))<=3;
        var actor=motherReady?o.entity():mother;
        var destination=motherReady?stand(atStation(mother,brood,rendezvous),o.entity()).at():rendezvous.at();
        if(SurfaceApproach.lastResult(actor,destination)==SurfaceApproach.Result.BLOCKED)
            return motherReady?"helper_route_blocked":"mother_route_blocked";
        return dutyTicks-siteSelectedAt>=400?"site_approach_timeout":null;
    }

    private void releaseSite(YukkuriEntity mother,FamilyData.Brood brood,String reason){
        var helper=stand(atStation(mother,brood,rendezvous),o.entity()).at();
        GameplayLog.event(o.entity(),"family","catch site released brood={} reason={} station={} helper={} motherAt={} helperAt={} ticks={}",
                brood.id,reason,rendezvous.at(),helper,mother.position(),o.entity().position(),dutyTicks-siteSelectedAt);
        rejectSite(rendezvous.at(),helper,400);rendezvous=null;nextSiteSearch=dutyTicks+1;
        SurfaceApproach.stopWalking(o.entity());
        if(mother.family().task()==Task.BROOD)SurfaceApproach.stopWalking(mother);
    }

    private void logSiteFailure(FamilyData.Brood brood,int checked,int paths,String reason){
        GameplayLog.limited(o.entity(),"family","catch.site_failed",200,
                "catch site search failed brood={} reason={} checked={} paths={} dutyTicks={}",brood.id,reason,checked,paths,dutyTicks);
    }

    private void seekMother(YukkuriEntity mother) {
        if(dutyTicks%20!=1&&!o.entity().getNavigation().isDone())return;
        var home=o.entity().habitat().shelter();
        if(home!=null&&home.contains(o.entity().blockPosition())&&!home.contains(mother.blockPosition())) {
            o.entity().life().navigate(home.entrance().relative(home.facing(),2),1.15,false);return;
        }
        if(o.entity().life().navigate(mother.blockPosition(),1.15,false))return;
        var delta=mother.position().subtract(o.entity().position());
        if(delta.horizontalDistance()>10) {
            var step=o.entity().position().add(delta.scale(10/delta.horizontalDistance()));
            o.entity().life().navigate(BlockPos.containing(step),1.15,false);
        }
    }

    /** A land catch stays usable only while this exact physical hat remains placed and its helper stays at the authored rim stand. */
    FamilyLife.DeliveryStation deliveryStation(YukkuriEntity mother,FamilyData.Brood brood) {
        if(mother==null||brood==null||brood.id==null||!brood.id.equals(deliveryBrood)||deliveryHat==null||deliveryStation==null||deliveryHatAt==null
                ||!o.entity().getUUID().equals(brood.father)||!mother.getUUID().equals(brood.mother)||!o.dutyReady())return null;
        if(!(o.level().getEntity(deliveryHat) instanceof PlacedHatEntity hat)||hat.isRemoved()||!hat.isCradle()||!hat.catches()
                ||hat.role()!=HatGeometry.Role.HELPER||!o.entity().getUUID().equals(hat.ownerYukkuri())){
            clearDeliveryStation();return null;
        }
        var moved=hat.position().subtract(deliveryHatAt);float turned=Mth.wrapDegrees(hat.getYRot()-deliveryHatYaw);
        if(moved.lengthSqr()>.0225||Math.abs(turned)>3){clearDeliveryStation();return null;}
        var stand=stand(poseOf(hat),o.entity());
        if(o.entity().distanceToSqr(stand.at())>.64){clearDeliveryStation();return null;}
        // The actual entity is the anchor: carry its settle offset through to the mother's exact outlet pose.
        return new FamilyLife.DeliveryStation(deliveryStation.add(moved),deliveryStationYaw+turned);
    }

    void abandonDeliveryStation(YukkuriEntity mother,FamilyData.Brood brood) {
        if(mother==null||brood==null||brood.id==null||!brood.id.equals(dutyBrood))return;
        if(brood.id.equals(deliveryBrood))clearDeliveryStation();
        stopAction("mother_station_abandoned");
        rendezvous=null;dutyGaveUp=true;
        BirthStaging.caption(o.entity(),"母油无法回到接生帽前，这一胎改为无垫分娩");
    }

    void save(CompoundTag tag) {
        if(deliveryBrood==null||deliveryHat==null||deliveryStation==null||deliveryHatAt==null)return;
        tag.putUUID("CatchStationBrood",deliveryBrood);tag.putUUID("CatchStationHat",deliveryHat);
        tag.putDouble("CatchStationX",deliveryStation.x);tag.putDouble("CatchStationY",deliveryStation.y);tag.putDouble("CatchStationZ",deliveryStation.z);
        tag.putFloat("CatchStationYaw",deliveryStationYaw);
        tag.putDouble("CatchHatX",deliveryHatAt.x);tag.putDouble("CatchHatY",deliveryHatAt.y);tag.putDouble("CatchHatZ",deliveryHatAt.z);
        tag.putFloat("CatchHatYaw",deliveryHatYaw);
    }

    void load(CompoundTag tag) {
        action=null;hatTicks=0;fetching=null;fetchTicks=fetchPassengers=releaseWait=0;fetchRetryAt=0;retrieveTicks=0;runner=null;runnerHat=null;
        dutyBrood=null;rendezvous=null;dutyTicks=nextSiteSearch=0;dutyGaveUp=false;rejectedSites.clear();
        clearDeliveryStation();
        if(!tag.hasUUID("CatchStationBrood")||!tag.hasUUID("CatchStationHat"))return;
        deliveryBrood=tag.getUUID("CatchStationBrood");deliveryHat=tag.getUUID("CatchStationHat");
        deliveryStation=new Vec3(tag.getDouble("CatchStationX"),tag.getDouble("CatchStationY"),tag.getDouble("CatchStationZ"));
        deliveryStationYaw=tag.getFloat("CatchStationYaw");
        deliveryHatAt=new Vec3(tag.getDouble("CatchHatX"),tag.getDouble("CatchHatY"),tag.getDouble("CatchHatZ"));
        deliveryHatYaw=tag.getFloat("CatchHatYaw");
        dutyBrood=deliveryBrood;
    }

    private boolean rememberDeliveryStation(HatAction committed,PlacedHatEntity hat) {
        if(committed==null||committed.move()!=HatMove.OFF||committed.brood()==null||committed.motherAt()==null||committed.pose()==null
                ||hat==null||!hat.isCradle()||committed.pose().role()!=HatGeometry.Role.HELPER)return false;
        deliveryBrood=committed.brood();deliveryHat=hat.getUUID();deliveryStation=committed.motherAt().at();deliveryStationYaw=committed.motherAt().yaw();
        deliveryHatAt=committed.pose().at();deliveryHatYaw=committed.pose().yaw();
        return true;
    }

    private void clearDeliveryStation(){deliveryBrood=deliveryHat=null;deliveryStation=deliveryHatAt=null;deliveryStationYaw=deliveryHatYaw=0;}

    /** This Marisa's hat, set down nearby to catch. */
    PlacedHatEntity cradleEntity() {
        if(o.entity().level().isClientSide)return null;
        return o.level().getEntitiesOfClass(PlacedHatEntity.class,o.entity().getBoundingBox().inflate(16),b->b.catches()&&o.entity().getUUID().equals(b.ownerYukkuri()))
                .stream().findFirst().orElse(null);
    }

    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    boolean keepsCradle() {
        if(cradleLinger>0||o.task()==Task.MIDWIFE)return true;
        var pending=o.brood();return pending!=null&&!pending.cancelled&&!pending.complete();
    }

    private boolean ownsCradle(PlacedHatEntity hat){
        return hat!=null&&!hat.isRemoved()&&hat.catches()&&hat.ownsPhysicalHat(o.entity());
    }

    /** An absent owner is not a death report: only the saved family ledger may open this one-parent release duty. */
    private boolean foreignDeadOwnerCradle(PlacedHatEntity hat){
        if(hat==null||hat.isRemoved()||!hat.catches()||hat.ownerYukkuri()==null||hat.ownerYukkuri().equals(o.entity().getUUID()))return false;
        var owner=FamilyData.get(o.level()).person(hat.ownerYukkuri());
        return owner!=null&&owner.status==FamilyData.Status.DEAD;
    }

    /** The survivor may release exactly their own live dependent riders, never adopt the dead owner's other riders. */
    private boolean ownYoungAboard(PlacedHatEntity hat){
        return hat!=null&&hat.getPassengers().stream().anyMatch(rider->rider instanceof YukkuriEntity young
                &&young.isAlive()&&!young.family().adult()&&Bonds.parentOf(o.entity(),young));
    }

    /**
     * A surviving biological parent may only take the existing release duty for a loaded catch hat whose owner is
     * recorded dead. It neither changes hull custody nor marks an absent owner dead; after a reload this is rediscovered
     * from the same real hull and riders.
     */
    boolean claimParentCradle(){
        if(o.entity().level().isClientSide||o.task()!=Task.NONE||o.brood()!=null||!dutyReady()||moving()||fetching!=null
                ||o.level().getGameTime()<fetchRetryAt)return false;
        var cradle=o.level().getEntitiesOfClass(PlacedHatEntity.class,o.entity().getBoundingBox().inflate(48),
                        hat->foreignDeadOwnerCradle(hat)&&ownYoungAboard(hat)).stream()
                .min(Comparator.comparingDouble(o.entity()::distanceToSqr)).orElse(null);
        if(cradle==null)return false;
        fetchHat(cradle);
        return fetching==cradle;
    }

    void tickHat(){
        var running=action;
        if(running==null)return;
        String refusal=actionRefusal(running,true);
        if(refusal!=null){stopAction(refusal);return;}
        o.entity().getNavigation().stop();o.face(running.stand().yaw());
        if(--hatTicks>0)return;
        // The clip ends with the hat where it now lies: validate again immediately before the irreversible transfer.
        refusal=actionRefusal(running,true);
        if(refusal!=null){stopAction(refusal);return;}
        if(running.move()==HatMove.OFF||running.move()==HatMove.OFF_SELF){
            var down=PlacedHatEntity.setDown(o.entity(),running.pose().at(),running.pose().yaw(),running.pose().role());
            // A hat that cannot be set down is a failed catch, not a clip to repeat for ever.
            if(down==null){
                dutyGaveUp=true;
                BirthStaging.caption(o.entity(),"帽子没能放下（落点不可用），这次改为无垫分娩");
                GameplayLog.event(o.entity(),"family","catch hat refused brood={} reason=set_down_unavailable move={}",running.brood(),running.move());
            } else {
                var mother=running.mother()!=null&&o.level().getEntity(running.mother()) instanceof YukkuriEntity found?found:null;
                var brood=mother==null?null:mother.family().brood();
                if(rememberDeliveryStation(running,down)&&mother!=null&&brood!=null)mother.family().catchStationActivated(brood);
                BirthStaging.caption(o.entity(),"帽子已翻面放在出口前（帽子离开了头顶，成为真实的帽实体），接生者扶住帽沿等待");
                GameplayLog.event(o.entity(),"family","catch hat placed brood={} hat={} mother={} kind={} at={} yaw={} motherAt={}",brood==null?null:brood.id,
                        down.getUUID(),mother==null?null:mother.getUUID(),brood==null?null:brood.kind,down.position(),
                        String.format(java.util.Locale.ROOT,"%.1f",down.getYRot()),mother==null?null:mother.position());
            }
        }else BirthStaging.caption(o.entity(),"帽子已戴回头上");
        action=null;hatTicks=0;
    }

    /** The birth goal owns MOVE throughout post-delivery collection; passive ticks never steer it. */
    void tickFetch(){
        if(fetching==null){o.entity().getNavigation().stop();return;}
        if(fetching.isRemoved()||!fetching.catches()||o.entity().isPassenger()){endFetch("hat_or_taker_changed");return;}
        var hat=fetching;
        boolean own=ownsCradle(hat),foreign=foreignDeadOwnerCradle(hat);
        // A live/unknown original owner never grants a survivor any authority over this hat or its riders.
        if(!own&&!foreign){
            GameplayLog.event(o.entity(),"family","parent cradle duty released hat={} owner={} reason=owner_not_confirmed_dead",
                    hat.getUUID(),hat.ownerYukkuri());
            endFetch("owner_not_confirmed_dead");return;
        }
        // The last biological child already left the actual hull. Leave the dead owner's hat and other riders alone.
        if(foreign&&!ownYoungAboard(hat)){
            GameplayLog.event(o.entity(),"family","parent cradle release completed hat={} owner={} reason=own_young_ashore",
                    hat.getUUID(),hat.ownerYukkuri());
            endFetch("own_young_ashore");return;
        }
        if(keepsCradle()||o.entity().isPanicking())return;
        // A NEST catch hat unloads through the Unload intent (spec 14 WP1 step 7).
        unload(hat,foreign);return;
    }

    /**
     * The NEST cleanup: every young this taker must unload leaves through the {@link Unload} intent, then the empty hat goes
     * back on with the authored clip. The intent's own deadline, attempt budget and {@code [intent]}/{@code [method]} lines
     * replace the per-rider counter, the release pause and the fixed timeout the deck flow still uses.
     */
    private void unload(PlacedHatEntity hat,boolean onlyOwnYoung){
        if(runner==null||runnerHat!=hat){
            runner=new IntentRunner(o.entity(),new Unload(hat,o.entity(),onlyOwnYoung),"unload")
                    .target(()->new IntentRunner.Target(o.entity().position(),hat.position(),Set.of()));
            runnerHat=hat;runner.start();
        }
        if(runner.result()==IntentRunner.Result.RUNNING)runner.tick();
        if(runner.result()==IntentRunner.Result.RUNNING)return;
        if(runner.result()!=IntentRunner.Result.DONE){
            // The intent already logged its end with every reason; the duty simply ends and is taken up again after the pause.
            GameplayLog.event(o.entity(),"family","catch cleanup interrupted hat={} passengers={} foreign={} result={}",
                    hat.getUUID(),hat.getPassengers().size(),onlyOwnYoung,runner.result());
            endFetch("intent_"+runner.result());fetchRetryAt=o.level().getGameTime()+100;return;
        }
        // Every young is off the hat: take the empty hat back from the stand the placement clip was authored from.
        retrieve(hat,stand(poseOf(hat),o.entity()));
    }

    /** Walk to the placement stand and take the empty hat back; a stand that cannot be reached is given the same finite window. */
    private void retrieve(PlacedHatEntity hat,Stand stand){
        if(++retrieveTicks>Unload.DEADLINE_TICKS){
            GameplayLog.event(o.entity(),"family","catch cleanup interrupted hat={} passengers={} reason=retrieve_timeout",
                    hat.getUUID(),hat.getPassengers().size());
            endFetch("retrieve_timeout");fetchRetryAt=o.level().getGameTime()+100;return;
        }
        // The placement clip's stand belongs to retrieving the empty hat. Carrying each child chooses its own reachable stand.
        if(!approachHatStand(stand))return;
        var role=hat.role();
        o.entity().getNavigation().stop();o.face(stand.yaw());
        if(hat.retrieve(o.entity())){
            GameplayLog.event(o.entity(),"family","catch cleanup completed hat={} passengers=0",hat.getUUID());
            endFetch("completed");
            beginHat(role==HatGeometry.Role.SELF?HatMove.ON_SELF:HatMove.ON,null,stand);
        }
    }

    /** Ends the fetch duty: the runner (if any) is stopped here and never resumed; the next duty builds a new one. */
    private void endFetch(String why){
        if(runner!=null){runner.stop(why);runner=null;}
        runnerHat=null;fetching=null;fetchTicks=0;fetchPassengers=0;releaseWait=0;retrieveTicks=0;
    }

    /**
     * Pure maternal admission for the passive birth clock: it may wait for a real self-catch hat, but never starts
     * navigation or an animation. The BROOD goal alone calls {@link #catchAlone(FamilyData.Brood)} to execute it.
     */
    boolean needsSelfCatch(FamilyData.Brood brood){
        if(brood==null||dutyGaveUp||o.withholdsCushion()||!o.acceptsCatch(brood)||catcher(o.entity(),brood)!=o.entity()||cradleEntity()!=null)return false;
        var pose=catchPose(o.entity(),brood,o.entity(),HatGeometry.Role.SELF);
        return settles(pose);
    }

    /** A Marisa mother with nobody to catch for her sets her own hat before her outlet; only the BROOD goal executes it. */
    boolean catchAlone(FamilyData.Brood brood){
        if(moving())return true;
        if(!needsSelfCatch(brood))return false;
        var pose=catchPose(o.entity(),brood,o.entity(),HatGeometry.Role.SELF);
        return beginHat(HatMove.OFF_SELF,pose,stand(pose,o.entity()));
    }

    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. A dead owner's hat is release-only. */
    void fetchHat(PlacedHatEntity hat){if(!moving()&&fetching==null&&!o.entity().isPassenger()&&o.level().getGameTime()>=fetchRetryAt
            &&(ownsCradle(hat)||foreignDeadOwnerCradle(hat)&&ownYoungAboard(hat))){
        fetching=hat;fetchTicks=0;fetchPassengers=hat.getPassengers().size();releaseWait=0;retrieveTicks=0;runner=null;runnerHat=null;
        GameplayLog.limited(o.entity(),"family","catch.cleanup",200,"catch cleanup started hat={} passengers={} foreign={}",
                hat.getUUID(),hat.getPassengers().size(),foreignDeadOwnerCradle(hat));
    }}

    /** The captain's hat goes back on (the authored hat_on_self clip) once the token has been returned to her. */
    boolean playHatOn(){
        if(moving())return false;
        var stand=new Stand(o.entity().position(),o.entity().yBodyRot);
        if(!beginHat(HatMove.ON_SELF,null,stand))return false;
        return true;
    }

    boolean hatOnPlaying(){return action!=null&&(action.move()==HatMove.ON_SELF||action.move()==HatMove.ON);}

    /** Guards a parent must pass to take a duty at the birth: the same ones the family goal applies. */
    boolean dutyReady(){
        return o.adult()&&actionOwnerReady();
    }

    /** The father notices the labour on the server clock and takes the duty himself, so no goal arbitration leaves him idle. */
    void claimMidwife(){
        if(o.partner()==null||o.task()==Task.MIDWIFE||!dutyReady())return;
        var mate=mateInLabor();if(mate==null)return;
        if(o.brood()!=null)return;
        var brood=mate.family().brood();
        if(!brood.id.equals(dutyBrood)){resetDuty();dutyBrood=brood.id;}
        o.stop();
        if(o.entity().dailyLife().active())o.entity().dailyLife().stop();
        if(o.entity().playLife().active())o.entity().playLife().stop();
        o.task(Task.MIDWIFE);o.target(mate);o.taskTicks(0);
        o.entity().getNavigation().stop();
        GameplayLog.event(o.entity(),"family","catch duty claimed brood={} mother={}",brood.id,mate.getUUID());
    }

    /** Father's beats: notice the labour, walk to the mother's front outlet, take his hat off (a real accessory change), set it
     *  upturned before the outlet, hold it while she pushes. Everything afterwards is the existing hat flow: the newborn lands
     *  in it, the family greets, the braid lifts the young out and the hat goes back on (tickHat). If he cannot get there in time
     *  or the hat cannot be set down, the delivery goes on without a cushion. Project original staging. */
    void tickMidwife(){
        dutyActivity=Behaviors.IDLE;
        var mate=o.target();var b=mate==null?null:mate.family().brood();
        if(mate==null||!mate.isAlive()||b==null||b.cancelled||b.complete()){
            GameplayLog.event(o.entity(),"family","catch duty ended brood={} mother={} reason={}",dutyBrood,mate==null?null:mate.getUUID(),
                    mate==null?"mother_missing":!mate.isAlive()?"mother_dead":b==null?"brood_cleared":b.cancelled?"brood_cancelled":"birth_complete");
            if(cradleEntity()!=null)cradleLinger=120;
            dutyTicks=0;dutyGaveUp=false;o.stop();return;
        }
        if(moving())return;
        if(dutyTicks++==0){
            o.entity().showReaction("surprise","caregiver",50);
            BirthStaging.caption(o.entity(),"父油察觉母油临产，走向她面前的出口");
        }
        var hat=cradleEntity();
        if(hat!=null){
            // Holding the set-down hat's rim with the braid from the place the clip is authored from.
            var stand=stand(poseOf(hat),o.entity());
            if(!approachHatStand(stand))return;
            o.entity().getNavigation().stop();
            o.face(stand.yaw());dutyActivity=Behaviors.HAT_HOLD;return;
        }
        boolean mayCatch=!dutyGaveUp&&catcher(mate,b)==o.entity()&&mate.family().acceptsCatch(b);
        if(mayCatch) {
            if(o.entity().distanceToSqr(mate)>64){seekMother(mate);return;}
            // Do not agree on the far side of a nest while her own goal is still taking her out the other side.
            // The mother's finite acceptance window, rather than a second helper timer, bounds a genuinely blocked exit.
            if(mate.family().leavingNestForCatcher(b)){
                rendezvous=null;SurfaceApproach.halt(o.entity());
                GameplayLog.limited(o.entity(),"family","catch.mother_exit",200,
                        "catch waiting for nest exit brood={} mother={} motherAt={} dutyTicks={}",b.id,mate.getUUID(),mate.position(),dutyTicks);
                return;
            }
            if(rendezvous!=null){
                String reason=siteRefusal(mate,b,rendezvous);
                if(reason==null)reason=siteStalled(mate,b);
                if(reason!=null)releaseSite(mate,b,reason);
            }
            if(rendezvous==null&&dutyTicks>=nextSiteSearch){nextSiteSearch=dutyTicks+100;findSite(mate,b);}
            if(rendezvous==null){
                // Already nearby: make room beside her while the bounded site search retries, never seek her feet.
                var beside=SurfaceApproach.floor(o.entity(),midwifeSpot(mate));
                if(beside!=null)SurfaceApproach.walkAround(o.entity(),beside,mate,.8);
                else SurfaceApproach.halt(o.entity());
                return;
            }
            var station=rendezvous;
            if(!SurfaceApproach.arrived(mate,station.at())||Math.abs(Mth.wrapDegrees(mate.yBodyRot-station.yaw()))>3) {
                SurfaceApproach.halt(o.entity());
                GameplayLog.limited(o.entity(),"family","catch.mother_approach",200,
                        "catch waiting for mother brood={} mother={} from={} station={} yaw={} targetYaw={}",
                        b.id,mate.getUUID(),mate.position(),station.at(),mate.yBodyRot,station.yaw());
                return;
            }
        }
        var pose=mayCatch?catchPose(mate,b,o.entity(),HatGeometry.Role.HELPER):null;
        if(pose!=null&&settles(pose)){
            // Walk to the place beside the outlet; the braid takes the hat off and sets it down there.
            beginHat(HatMove.OFF,pose,stand(pose,o.entity()));
            return;
        }
        // Without a hat to set (a shell Marisa, or no ground or water before the outlet) he cheers beside it.
        var spot=midwifeSpot(mate);var outlet=LiveBirthGeometry.position(mate,0);
        if(o.entity().distanceToSqr(spot)>.3) { if(dutyTicks%10==1)o.entity().life().navigate(BlockPos.containing(spot),.9,false); }
        else {o.entity().getNavigation().stop();dutyActivity=Behaviors.MIDWIFE;}
        o.entity().getLookControl().setLookAt(outlet.x,outlet.y+.1,outlet.z,20,20);
    }

    /** A Marisa father comes to the mother when her young are about to come, on land, live or stem birth (user design). */
    YukkuriEntity mateInLabor() {
        if(o.entity().getVariant()!=YukkuriVariant.MARISA||o.partner()==null||o.entity().isPassenger()
                ||!(o.level().getEntity(o.partner()) instanceof YukkuriEntity mate)||!mate.isAlive()
                ||o.entity().distanceToSqr(mate)>SEEK_RANGE_SQR||!o.entity().life().canRecognize(mate))return null;
        var b=mate.family().brood();
        if(b==null||b.cancelled||b.complete()||b.kind==FamilyData.BirthKind.EGG||!o.entity().getUUID().equals(b.father))return null;
        // A demonstrated miss: he has not noticed in time, so he is not on duty; he only reacts once the newborn has dropped.
        if(mate.family().withholdsCushion())return null;
        return b.elapsed>=Math.max(0,b.duration-APPROACH_LEAD)?mate:null;
    }

    /** Beside the outlet, clear of the exit box, on whichever side is free. */
    private Vec3 midwifeSpot(YukkuriEntity mate) {
        var forward=Vec3.directionFromRotation(0,mate.yBodyRot);var side=new Vec3(-forward.z,0,forward.x);
        double reach=mate.getBbWidth()*.5+o.entity().getBbWidth()*.5+.12;
        Vec3 best=null;
        for(int sign:new int[]{1,-1}){
            var spot=mate.position().add(forward.scale(mate.getBbWidth()*.45)).add(side.scale(sign*reach));
            if(best==null||o.level().noCollision(o.entity(),o.entity().getBoundingBox().move(spot.subtract(o.entity().position()))))best=spot;
            if(o.level().noCollision(o.entity(),o.entity().getBoundingBox().move(spot.subtract(o.entity().position()))))break;
        }
        return best;
    }
}
