package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife.HatPose;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stand;
import io.github.longshou.mcyukkuri.family.FamilyLife.Task;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
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
    private enum HatMove { NONE, OFF, ON, OFF_SELF, ON_SELF }
    private HatMove hatMove=HatMove.NONE;
    private int hatTicks, cradleLinger, fetchTicks, hatApproach, releaseWait, dutyTicks;
    private float hatYaw;
    private HatPose hatTarget;
    private HatBoatEntity fetching;
    private boolean dutyGaveUp;
    /** The helper's confirmed land hat and the mother pose it was placed for. */
    private UUID deliveryBrood, deliveryHat;
    private Vec3 deliveryStation, deliveryHatAt;
    private float deliveryStationYaw, deliveryHatYaw;
    /** The mother pose used to author the helper's in-progress hat placement. */
    private UUID plannedDeliveryBrood;
    private Vec3 plannedDeliveryStation;
    private float plannedDeliveryYaw;
    /** Agreed before either hat or child is created; both parents walk to this same physical site. */
    private FamilyLife.DeliveryStation rendezvous;
    private UUID dutyBrood;
    private int nextSiteSearch;

    CatchHat(FamilyLife o) { this.o=o; }
    boolean moving() { return hatMove!=HatMove.NONE; }
    boolean fetching() { return fetching!=null; }
    boolean dutyGaveUp() { return dutyGaveUp; }
    void resetDuty() { dutyTicks=nextSiteSearch=0;dutyGaveUp=false;dutyBrood=null;rendezvous=null;clearDeliveryStation(); }
    void resetApproach() { hatApproach=0; }
    void linger(int ticks) { cradleLinger=ticks; }
    void tickLinger() { if(cradleLinger>0)cradleLinger--; }
    String activity(boolean aquatic) {
        return Behaviors.aquatic(switch(hatMove){case OFF->Behaviors.HAT_OFF;case ON->Behaviors.HAT_ON;case OFF_SELF->Behaviors.HAT_OFF_SELF;default->Behaviors.HAT_ON_SELF;}, aquatic);
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
     *  before the outlet, the live exit just inside its opening (a mother on her own sets it where her own clip puts it);
     *  the aquatic Marisa floats her larger hat at the bank's edge before the outlet, its near brim slipped under the bank. */
    static HatPose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,HatVessel.Role role){
        boolean aquatic=catcher.profile().aquatic();var place=HatVessel.placement(aquatic,role);
        double k=HatVessel.hatScale(catcher)*catcher.getScale()/16,body=mother.growthScale()*mother.getScale()/16;
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);float yaw=mother.yBodyRot+place.hatYawInMother();
        if(aquatic){
            var edge=mother.position().add(forward.scale(mother.getBbWidth()*.5));
            return new HatPose(edge.add(forward.scale(HatVessel.brim(place.mode())[0]*k-HatVessel.underBank(place.mode())*body)),yaw,place.mode(),role);
        }
        if(role==HatVessel.Role.SELF){
            var at=FamilyGeometry.toWorld(mother,place.offset().scale(mother.growthScale()/16));
            return new HatPose(new Vec3(at.x,mother.getY(),at.z),yaw,HatVessel.Mode.NEST,role);
        }
        if(brood.kind==FamilyData.BirthKind.STEM){
            // Under the fruit, its long axis along the stem so they drop into the opening (the near brim clear of her);
            // turned whichever way puts the helper on the far side, facing her.
            double sx=0,sz=0;int n=brood.children.size();
            for(int i=0;i<n;i++){var c=FamilyGeometry.childBase(n,i,1,mother.growthScale(),brood.variation,mother.getVariant(),brood.children.get(i).variant);sx+=c.x;sz+=c.z;}
            var under=FamilyGeometry.toWorld(mother,new Vec3(sx/Math.max(1,n),0,sz/Math.max(1,n)));
            double ahead=under.subtract(mother.position()).dot(forward),least=mother.getBbWidth()*.5+HatVessel.brim(place.mode())[1]*k+.1;
            var at=under.add(forward.scale(Math.max(0,least-ahead)));HatPose best=null;double far=-1;
            for(float turn:new float[]{0,180}){
                var pose=new HatPose(new Vec3(at.x,mother.getY(),at.z),mother.yBodyRot+turn,HatVessel.Mode.NEST,role);
                double d=stand(pose,catcher).at().distanceToSqr(mother.position());if(d>far){far=d;best=pose;}
            }
            return best;
        }
        int next=0;
        if(brood.kind==FamilyData.BirthKind.LIVE)while(next<brood.children.size()-1&&brood.children.get(next).born)next++;
        // The long axis across her, the near brim just beyond the live exit and the newborn's round body: she pushes it
        // out over the brim into the hat.
        double r=HatVessel.radius(LiveBirthGeometry.childDimensions(brood.children.get(next),mother.getScale()).width());
        var at=LiveBirthGeometry.position(mother,next).add(forward.scale(r+place.gap()*body+place.nearBrim()*k));
        return new HatPose(new Vec3(at.x,mother.getY(),at.z),yaw,HatVessel.Mode.NEST,role);
    }

    /** The catcher's place for a hat at this pose: the inverse of the clip's placement. */
    static Stand stand(HatPose pose,YukkuriEntity catcher){
        var place=HatVessel.placement(catcher.profile().aquatic(),pose.role());float yaw=pose.yaw()-place.yaw();
        var offset=new Vec3(place.offset().x,0,place.offset().z).scale(catcher.growthScale()*catcher.getScale()/16);
        var at=pose.at().subtract(offset.yRot((float)Math.toRadians(180-yaw)));
        return new Stand(new Vec3(at.x,pose.mode()==HatVessel.Mode.NEST?pose.at().y:catcher.getY(),at.z),yaw);
    }

    private static HatPose poseOf(HatBoatEntity hat){return new HatPose(hat.position(),hat.getYRot(),hat.mode(),hat.role());}

    /** The ground or water where the hat would go takes it. */
    private boolean settles(HatPose pose){
        var spot=BlockPos.containing(pose.at());
        if(!o.level().hasChunkAt(spot))return false;
        if(pose.mode().afloat()){
            for(int down=0;down<3;down++)if(o.level().getFluidState(spot.below(down)).is(FluidTags.WATER))return true;
            return false;
        }
        return o.level().getFluidState(spot).isEmpty()&&o.level().getBlockState(spot.below()).isFaceSturdy(o.level(),spot.below(),Direction.UP);
    }

    /** Step onto the spot the braid clip is authored from and start it. */
    private void beginHat(HatMove move,HatPose target,Stand stand){
        // Place only from the authored stand; the bounded duty handles an unreachable spot.
        hatApproach++;
        if(!approachHatStand(stand))return;
        hatApproach=0;
        BirthStaging.caption(o.entity(),switch(move){
            case OFF->"接生者走到出口前方，用辫子摘下自己的帽子（hat_off）";
            case OFF_SELF->"母油自己摘下帽子（hat_off_self）";
            case ON->"幼体已被放出，接生者用辫子把帽子戴回（hat_on）";
            default->"接生者把帽子戴回头上（hat_on_self）";});
        o.entity().getNavigation().stop();o.face(stand.yaw());
        planDeliveryStation(move,target);
        hatMove=move;hatYaw=stand.yaw();hatTarget=target;
        hatTicks=(int)Math.round((move==HatMove.OFF_SELF||move==HatMove.ON_SELF?1.8:1.6)*20);
    }

    private boolean approachHatStand(Stand stand){
        return SurfaceApproach.walk(o.entity(),stand.at(),.8);
    }

    FamilyLife.DeliveryStation rendezvous(YukkuriEntity mother,FamilyData.Brood brood) {
        return !dutyGaveUp&&brood!=null&&brood.id.equals(dutyBrood)&&o.target()==mother
                &&o.task()==Task.MIDWIFE&&o.dutyReady()?rendezvous:null;
    }

    private HatPose atStation(YukkuriEntity mother,FamilyData.Brood brood,FamilyLife.DeliveryStation site) {
        var pose=catchPose(mother,brood,o.entity(),HatVessel.Role.HELPER);
        float turn=site.yaw()-mother.yBodyRot;
        var at=site.at().add(pose.at().subtract(mother.position()).yRot((float)Math.toRadians(-turn)));
        return new HatPose(at,pose.yaw()+turn,pose.mode(),pose.role());
    }

    private boolean usableSite(YukkuriEntity mother,FamilyData.Brood brood,FamilyLife.DeliveryStation site) {
        if(!SurfaceApproach.supported(mother,site.at()))return false;
        var pose=atStation(mother,brood,site);var helper=stand(pose,o.entity());
        if(!settles(pose)||!SurfaceApproach.supported(o.entity(),helper.at()))return false;
        // Check the whole visible brim, not just the centre block beneath the hat.
        double unit=HatVessel.hatScale(o.entity())*o.entity().getScale()/16;
        var brim=HatVessel.brim(pose.mode());double width=Math.max(brim[0],brim[1])*unit;
        var hatBox=new AABB(pose.at().x-width,pose.at().y+.01,pose.at().z-width,
                pose.at().x+width,pose.at().y+.3,pose.at().z+width);
        var motherBox=mother.getBoundingBox().move(site.at().subtract(mother.position()));
        var helperBox=o.entity().getBoundingBox().move(helper.at().subtract(o.entity().position()));
        return !motherBox.intersects(helperBox)
                &&site.at().subtract(pose.at()).horizontalDistance()>mother.getBbWidth()*.5+width
                &&helper.at().subtract(pose.at()).horizontalDistance()>o.entity().getBbWidth()*.5+width
                &&!o.level().getBlockCollisions(o.entity(),hatBox).iterator().hasNext();
    }

    private void findSite(YukkuriEntity mother,FamilyData.Brood brood) {
        // Bounded local search; prefer the mother's current position, then nearby open floor.
        int paths=0;
        for(int radius=0;radius<=3;radius++)for(int side=0;side<(radius==0?1:8);side++) {
            double angle=side*Math.PI/4;
            var at=mother.position().add(Math.cos(angle)*radius,0,Math.sin(angle)*radius);
            for(float turn:new float[]{0,90,-90,180}) {
                var site=new FamilyLife.DeliveryStation(at,mother.yBodyRot+turn);
                if(!usableSite(mother,brood,site))continue;
                if(++paths>8)return;
                var helper=stand(atStation(mother,brood,site),o.entity());
                if(!mother.life().reachable(BlockPos.containing(at),false)||!o.entity().life().reachable(BlockPos.containing(helper.at()),false))continue;
                rendezvous=site;return;
            }
        }
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
        if(!(o.level().getEntity(deliveryHat) instanceof HatBoatEntity hat)||hat.isRemoved()||!hat.isCradle()||!hat.catches()
                ||hat.role()!=HatVessel.Role.HELPER||!o.entity().getUUID().equals(hat.ownerYukkuri())){
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
        if(mother!=null&&brood!=null&&brood.id!=null&&brood.id.equals(deliveryBrood)){
            clearDeliveryStation();dutyGaveUp=true;
            BirthStaging.caption(o.entity(),"母油无法回到接生帽前，这一胎改为无垫分娩");
        }
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
        dutyBrood=null;rendezvous=null;dutyTicks=nextSiteSearch=0;dutyGaveUp=false;
        clearDeliveryStation();
        if(!tag.hasUUID("CatchStationBrood")||!tag.hasUUID("CatchStationHat"))return;
        deliveryBrood=tag.getUUID("CatchStationBrood");deliveryHat=tag.getUUID("CatchStationHat");
        deliveryStation=new Vec3(tag.getDouble("CatchStationX"),tag.getDouble("CatchStationY"),tag.getDouble("CatchStationZ"));
        deliveryStationYaw=tag.getFloat("CatchStationYaw");
        deliveryHatAt=new Vec3(tag.getDouble("CatchHatX"),tag.getDouble("CatchHatY"),tag.getDouble("CatchHatZ"));
        deliveryHatYaw=tag.getFloat("CatchHatYaw");
        dutyBrood=deliveryBrood;
    }

    private void planDeliveryStation(HatMove move,HatPose pose) {
        clearPlannedDeliveryStation();
        if(move!=HatMove.OFF||pose.role()!=HatVessel.Role.HELPER||o.target()==null)return;
        var brood=o.target().family().brood();
        if(brood==null||brood.id==null||brood.kind==FamilyData.BirthKind.EGG)return;
        plannedDeliveryBrood=brood.id;plannedDeliveryStation=o.target().position();plannedDeliveryYaw=o.target().yBodyRot;
    }

    private boolean rememberDeliveryStation(FamilyData.Brood brood,HatBoatEntity hat,HatPose pose) {
        if(brood==null||brood.id==null||brood.kind==FamilyData.BirthKind.EGG||hat==null||!hat.isCradle()||pose.role()!=HatVessel.Role.HELPER
                ||!brood.id.equals(plannedDeliveryBrood)||plannedDeliveryStation==null)return false;
        deliveryBrood=brood.id;deliveryHat=hat.getUUID();deliveryStation=plannedDeliveryStation;deliveryStationYaw=plannedDeliveryYaw;
        deliveryHatAt=pose.at();deliveryHatYaw=pose.yaw();clearPlannedDeliveryStation();
        return true;
    }

    private void clearDeliveryStation(){deliveryBrood=deliveryHat=null;deliveryStation=deliveryHatAt=null;deliveryStationYaw=deliveryHatYaw=0;clearPlannedDeliveryStation();}
    private void clearPlannedDeliveryStation(){plannedDeliveryBrood=null;plannedDeliveryStation=null;plannedDeliveryYaw=0;}

    /** This Marisa's hat, set down nearby to catch. */
    HatBoatEntity cradleEntity() {
        if(o.entity().level().isClientSide)return null;
        return o.level().getEntitiesOfClass(HatBoatEntity.class,o.entity().getBoundingBox().inflate(16),b->b.catches()&&o.entity().getUUID().equals(b.ownerYukkuri()))
                .stream().findFirst().orElse(null);
    }

    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    boolean keepsCradle() {
        if(cradleLinger>0||o.task()==Task.MIDWIFE||o.cradlePickup().active())return true;
        var pending=o.brood();return pending!=null&&!pending.cancelled&&!pending.complete();
    }

    private List<YukkuriEntity> youngAround(double radius){
        return o.level().getEntitiesOfClass(YukkuriEntity.class,o.entity().getBoundingBox().inflate(radius),y->!y.family().adult()
                &&(Bonds.parentOf(o.entity(), y)));
    }

    void tickHat(){
        if(hatMove!=HatMove.NONE){
            o.entity().getNavigation().stop();o.face(hatYaw);
            if(--hatTicks<=0){
                // The clip ends with the hat where it now lies: it becomes the set-down hat (the accessory leaves the head).
                if((hatMove==HatMove.OFF||hatMove==HatMove.OFF_SELF)&&hatTarget!=null){
                    var down=HatBoatEntity.setDown(o.entity(),hatTarget.at(),hatTarget.yaw(),hatTarget.mode(),hatTarget.role());
                    // A hat that cannot be set down is a failed catch, not a clip to repeat for ever.
                    if(down==null){
                        dutyGaveUp=true;clearDeliveryStation();BirthStaging.caption(o.entity(),"帽子没能放下（落点不可用），这次改为无垫分娩");
                        GameplayLog.event(o.entity(),"family","catch hat refused brood={} reason=set_down_unavailable move={}",dutyBrood,hatMove);
                    }
                    else {
                        var brood=o.target()==null?null:o.target().family().brood();
                        if(rememberDeliveryStation(brood,down,hatTarget))o.target().family().catchStationActivated(brood);
                        BirthStaging.caption(o.entity(),"帽子已翻面放在出口前（帽子离开了头顶，成为真实的帽实体），接生者扶住帽沿等待");
                        GameplayLog.event(o.entity(),"family","catch hat placed brood={} hat={} mother={}",brood==null?null:brood.id,
                                down.getUUID(),o.target()==null?null:o.target().getUUID());
                    }
                }else if(hatMove==HatMove.ON||hatMove==HatMove.ON_SELF)BirthStaging.caption(o.entity(),"帽子已戴回头上");
                hatMove=HatMove.NONE;hatTarget=null;
            }
            return;
        }
        if(fetching==null)return;
        if(fetching.isRemoved()||!fetching.catches()||o.entity().isPassenger()){fetching=null;return;}
        if(keepsCradle()||o.entity().isPanicking())return;
        var stand=stand(poseOf(fetching),o.entity());fetchTicks++;
        if(o.entity().distanceToSqr(stand.at())>.36&&fetchTicks<200){if(fetchTicks%10==1)o.entity().life().navigate(BlockPos.containing(stand.at()),.9,false);return;}
        if(o.entity().distanceToSqr(stand.at())>16){fetching=null;return;}
        var role=fetching.role();var hat=fetching;
        if(!approachHatStand(stand))return;
        o.entity().getNavigation().stop();o.face(stand.yaw());
        // Dependent newborns leave in the parent's braid; capable children hop out before the empty hat is retrieved.
        if(hat.releaseYoung(o.entity())){releaseWait=14;return;}
        if(releaseWait>0){releaseWait--;return;}
        fetching=null;
        if(hat.retrieve(o.entity()))beginHat(role==HatVessel.Role.SELF?HatMove.ON_SELF:HatMove.ON,null,stand);
    }

    /** A Marisa mother with nobody to catch for her sets her own hat before her outlet (true while she is doing so). */
    boolean catchAlone(FamilyData.Brood brood){
        if(hatMove!=HatMove.NONE)return true;
        if(dutyGaveUp||o.withholdsCushion()||catcher(o.entity(),brood)!=o.entity()||cradleEntity()!=null)return false;
        var pose=catchPose(o.entity(),brood,o.entity(),HatVessel.Role.SELF);
        if(!settles(pose))return false;
        beginHat(HatMove.OFF_SELF,pose,stand(pose,o.entity()));return true;
    }

    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. */
    void fetchHat(HatBoatEntity hat){if(hatMove==HatMove.NONE&&fetching==null&&!o.entity().isPassenger()){fetching=hat;fetchTicks=0;}}

    /** The captain's hat goes back on (the authored hat_on_self clip) once the token has been returned to her. */
    boolean playHatOn(){
        if(hatMove!=HatMove.NONE)return false;
        hatMove=HatMove.ON_SELF;hatYaw=o.entity().yBodyRot;hatTarget=null;hatTicks=(int)Math.round(1.8*20);
        o.entity().getNavigation().stop();return true;
    }

    boolean hatOnPlaying(){return hatMove==HatMove.ON_SELF||hatMove==HatMove.ON;}

    /** Guards a parent must pass to take a duty at the birth: the same ones the family goal applies. */
    boolean dutyReady(){
        return o.adult()&&o.entity().isAlive()&&!o.entity().isNoAi()&&!o.entity().isPassenger()&&!o.entity().isPanicking()&&!o.entity().isCommandPlaying()
                &&!o.entity().operations().active()&&!o.entity().life().isSleeping()
                &&!o.entity().life().hasUrgentNeed()&&o.entity().life().mealTicks()==0&&!o.awaitingLanding()&&!o.hasFlightArc()&&!o.heldByBraid();
    }

    /** The father notices the labour on the server clock and takes the duty himself, so no goal arbitration leaves him idle. */
    void claimMidwife(){
        if(o.partner()==null||o.task()==Task.MIDWIFE||o.comfortActive()||!dutyReady())return;
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
        var mate=o.target();var b=mate==null?null:mate.family().brood();
        if(mate==null||!mate.isAlive()||b==null||b.cancelled||b.complete()||mate.family().boatHome()!=null){
            GameplayLog.event(o.entity(),"family","catch duty ended brood={} mother={} reason={}",dutyBrood,mate==null?null:mate.getUUID(),
                    mate==null?"mother_missing":!mate.isAlive()?"mother_dead":b==null?"brood_cleared":b.cancelled?"brood_cancelled":b.complete()?"birth_complete":"water_birth");
            if(cradleEntity()!=null)cradleLinger=120;
            dutyTicks=0;dutyGaveUp=false;o.stop();return;
        }
        if(hatMove!=HatMove.NONE)return;
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
            o.face(stand.yaw());return;
        }
        boolean mayCatch=!dutyGaveUp&&catcher(mate,b)==o.entity();
        if(mayCatch&&dutyTicks>=APPROACH_LEAD+CATCH_WAIT){
            dutyGaveUp=true;mayCatch=false;
            rendezvous=null;
            BirthStaging.caption(o.entity(),"父油没能及时赶到出口前，这一胎只能无垫分娩");
            GameplayLog.event(o.entity(),"family","catch duty gave up brood={} mother={} reason=approach_timeout ticks={}",b.id,mate.getUUID(),dutyTicks);
        }
        if(mayCatch&&!o.entity().profile().aquatic()) {
            if(o.entity().distanceToSqr(mate)>64){seekMother(mate);return;}
            if(rendezvous!=null&&!usableSite(mate,b,rendezvous))rendezvous=null;
            if(rendezvous==null&&dutyTicks>=nextSiteSearch){nextSiteSearch=dutyTicks+100;findSite(mate,b);}
            if(rendezvous==null){seekMother(mate);return;}
            var station=rendezvous;
            if(!SurfaceApproach.arrived(mate,station.at())||Math.abs(Mth.wrapDegrees(mate.yBodyRot-station.yaw()))>3) {
                approachHatStand(stand(atStation(mate,b,station),o.entity()));return;
            }
        }
        var pose=mayCatch?catchPose(mate,b,o.entity(),HatVessel.Role.HELPER):null;
        if(pose!=null&&settles(pose)){
            // Walk to the place beside the outlet; the braid takes the hat off and sets it down there.
            beginHat(HatMove.OFF,pose,stand(pose,o.entity()));
            return;
        }
        // Without a hat to set (a shell Marisa, or no ground or water before the outlet) he cheers beside it.
        var spot=midwifeSpot(mate);var outlet=LiveBirthGeometry.position(mate,0);
        if(o.entity().distanceToSqr(spot)>.3) { if(dutyTicks%10==1)o.entity().life().navigate(BlockPos.containing(spot),.9,false); }
        else o.entity().getNavigation().stop();
        o.entity().getLookControl().setLookAt(outlet.x,outlet.y+.1,outlet.z,20,20);
    }

    /** A Marisa father comes to the mother when her young are about to come, on land, live or stem birth (user design). */
    YukkuriEntity mateInLabor() {
        if(o.entity().getVariant()!=YukkuriVariant.MARISA||o.partner()==null||o.entity().isPassenger()
                ||!(o.level().getEntity(o.partner()) instanceof YukkuriEntity mate)||!mate.isAlive()||mate.family().boatHome()!=null
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
