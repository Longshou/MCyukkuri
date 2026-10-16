package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Individual growth and interruptible family goals; durable births live in FamilyData. */
public final class FamilyLife {
    public enum Stage { BABY, JUVENILE, ADULT }
    private enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, MIDWIFE, FEED_CHILD, STAY_HOME }
    private final YukkuriEntity entity;
    private Stage stage=Stage.ADULT;
    private long maturity=GrowthTiming.DEFAULT_MATURITY, age=maturity+1, nourishment=maturity,
            lifespan=maturity+GrowthTiming.DEFAULT_ADULT_LIFESPAN;
    private float adultScale=1, scale=1;
    private UUID mother, father, birthId, gestation, partner;
    private int conceptions, recovery, wait, taskTicks, spentStemCount, careCarryTicks, careChewTicks, careOfferTicks;
    private float spentStemVariation=1;
    private boolean initialized, awaitingLanding, juvenileVoice;
    private boolean sterile;
    private int stimulation;
    private int sexDrive;
    private boolean babyTalk;
    private int liveRecovery;
    private Vec3 deliveryLast;
    /** Marisa's braid moving her hat (lore hat clips): taken off and set down to catch, or taken back and put on. */
    private enum HatMove { NONE, OFF, ON, OFF_SELF, ON_SELF }
    private HatMove hatMove=HatMove.NONE;
    /** Ticks left of the hat clip; ticks the set-down hat stays after the births; ticks spent walking back to it. */
    private int hatTicks, cradleLinger, fetchTicks;
    private float hatYaw;
    private HatPose hatTarget;
    private HatBoatEntity fetching;
    /** A hat set down to catch young: where, facing (entity yaw) and how it lies, and who set it. */
    public record HatPose(Vec3 at, float yaw, HatVessel.Mode mode, HatVessel.Role role) {}
    /** Where a catcher stands, and her yaw, to set down, hold or take back a hat at a pose. */
    public record Stand(Vec3 at, float yaw) {}
    /** A newborn pushed past the hat by an overstrained delivery: it will not survive the landing. */
    private boolean doomed;
    /** A young on its way into a hat's kept place (seat, hat units) or out to a spot (to, world): a parabola under gravity
     *  from where it left (HatVessel.arc), ticks long. Not saved: an interrupted flight just falls. */
    private record Flight(UUID hat,Vec3 from,Vec3 seat,Vec3 to,int ticks){}
    private Flight flight;
    private int flightTick, releaseWait;
    /** Afloat and heading for a bank to give birth there (decided every 40 ticks, needs a bank in reach). */
    public float liveBelly(){
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE)
            return LiveBirthGeometry.belly(pregnancy.progress(),pregnancy.children.size()-pregnancy.unborn(),pregnancy.children.size());
        return .22f*liveRecovery/70f;
    }
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    /** A single real portion reserved for a hungry child. It survives an interrupted trip and reload. */
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity foodSource;
    private BlockPos foodStore;
    public FamilyLife(YukkuriEntity entity) { this.entity=entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public Stage stage() { return stage; }
    public long age() { return age; }
    public long nourishment() { return nourishment; }
    public long lifespan() { return lifespan; }
    public long maturityAge() { return maturity; }
    public float adultScale() { return adultScale; }
    public float scale() { return scale; }
    public UUID mother() { return mother; }
    public UUID father() { return father; }
    public UUID birthId() { return birthId; }
    public UUID partner() { return partner; }
    public int conceptions() { return conceptions; }
    public int recovery() { return recovery; }
    public int spentStemCount() { return spentStemCount; }
    public float spentStemVariation() { return spentStemVariation; }
    public boolean awaitingLanding() { return awaitingLanding; }
    /** Ordinary children need a parent-prepared serving until their juvenile nourishment and age can support chewing. */
    public boolean needsMash() {
        if (adult() || entity.getVariant().isHouse() || entity.getVariant().isPredator()) return false;
        return stage != Stage.JUVENILE || age < maturity / 4 || nourishment < maturity * 3 / 16;
    }
    public boolean canEatIndependently() { return !awaitingLanding && !needsMash(); }
    /** Late-child gate for a personal nest; stem-born juveniles still start at age and nourishment zero. */
    public boolean readyForOwnNest() { return !awaitingLanding&&stage==Stage.JUVENILE&&age>=maturity/2&&nourishment>=maturity*3/8&&entity.childhood().canLearn(); }
    public ItemStack carrying() { return carrying; }
    public int careChewTicks() { return careChewTicks; }
    /** Client-sync source: the child a land caregiver is currently approaching, or {@code -1}. */
    public int careTargetId() { return task == Task.FEED_CHILD && target != null ? target.getId() : -1; }
    /** Client-sync source: uninterrupted mouth-contact progress, from zero through twenty. */
    public int careContactProgress() { return task == Task.FEED_CHILD ? careOfferTicks : 0; }
    public boolean youngVoice() { return stage!=Stage.ADULT||juvenileVoice||babyTalk; }
    public boolean babyTalk(){return babyTalk;}
    public boolean applyBabyTalk(){if(!adult()||babyTalk)return false;babyTalk=true;entity.setPersistenceRequired();return true;}
    public int sexDrive(){return sexDrive;}
    public boolean canTakeStimulant(){return adult()&&!sterile()&&!elder()&&sexDrive==0;}
    public boolean takeStimulant(){if(!canTakeStimulant())return false;sexDrive=1200;entity.setPersistenceRequired();return true;}
    public boolean adult() { return stage==Stage.ADULT; }
    public boolean caresFor(YukkuriEntity child) {
        return adult()&&!child.family().adult()&&(entity.getUUID().equals(child.family().mother)||entity.getUUID().equals(child.family().father));
    }
    public boolean hasHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(1.5),child->caresFor(child)
                &&entity.life().canRecognize(child)&&child.life().nutrition()<entity.life().nutrition()-15).isEmpty();
    }
    public boolean elder() { return age>=maturity+(lifespan-maturity)*.85; }
    public boolean active() { return task!=Task.NONE||laboring()||hatMove!=HatMove.NONE||fetching!=null||flight!=null; }
    public String activity() {
        String big=HatVessel.aquatic(entity)?"_aq":"";
        if(hatMove!=HatMove.NONE)return switch(hatMove){case OFF->"hat_off";case ON->"hat_on";case OFF_SELF->"hat_off_self";default->"hat_on_self";}+big;
        if(laboring())return "labor";
        return switch(task) {
            case COURT, RECEIVE_COURT -> "courtship"; case BROOD -> "tend_brood"; case FOLLOW -> "follow_parent";
            case WAIT_CHILD -> "wait_child"; case SEARCH -> "search_family";
            case MIDWIFE -> cradleEntity()!=null?"hat_hold"+big:"midwife";
            case FEED_CHILD -> !carrying.isEmpty() && target != null && target.family().needsMash() && !InfantFood.isMash(carrying) ? "family_chew" : "feed_child";
            case STAY_HOME -> "stay_home"; default -> awaitingLanding?"newborn_fall":"idle";
        };
    }
    /** A live delivery is under way (server side). */
    public boolean laboring() {
        if(entity.level().isClientSide)return false;
        var b=brood();return b!=null&&!b.cancelled&&!b.complete()&&b.kind==FamilyData.BirthKind.LIVE&&b.elapsed>=b.duration;
    }
    /** The mate this partner is helping deliver, while acting as midwife. */
    public YukkuriEntity midwifeTarget() { return task==Task.MIDWIFE?target:null; }
    /** An aquatic mother afloat in her own hat boat nests in it: her young are born into the hat (water birth). */
    public io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boatHome() {
        return entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat && boat.isAfloat()
                && entity.getUUID().equals(boat.ownerYukkuri()) ? boat : null;
    }
    private static boolean hatted(YukkuriEntity y) {
        return y.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&y.life().hasAccessory()&&!y.isPassenger()
                &&y.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA;
    }
    /** Only Marisa midwife with her hat: a hatted Marisa father close by, else a hatted Marisa mother catches her own young. */
    public static YukkuriEntity catcher(YukkuriEntity mother,FamilyData.Brood brood) {
        if(brood.father!=null&&mother.level() instanceof ServerLevel level&&level.getEntity(brood.father) instanceof YukkuriEntity father
                &&father.isAlive()&&hatted(father)&&father.distanceToSqr(mother)<256&&father.life().canRecognize(mother))return father;
        return hatted(mother)?mother:null;
    }
    /** Where the hat goes to catch (hat_vessel.json, the end pose of the braid clips). A land Marisa presses it upside down
     *  before the outlet, the live exit just inside its opening (a mother on her own sets it where her own clip puts it);
     *  the aquatic Marisa floats her larger hat at the bank's edge before the outlet, its near brim slipped under the bank. */
    public static HatPose catchPose(YukkuriEntity mother,FamilyData.Brood brood,YukkuriEntity catcher,HatVessel.Role role){
        boolean aquatic=HatVessel.aquatic(catcher);var place=HatVessel.placement(aquatic,role);
        double k=HatVessel.hatScale(catcher)*catcher.getScale()/16,body=mother.growthScale()*mother.getScale()/16;
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);float yaw=mother.yBodyRot+place.hatYawInMother();
        if(aquatic){
            var edge=mother.position().add(forward.scale(mother.getBbWidth()*.5));
            return new HatPose(edge.add(forward.scale(HatVessel.BRIM[0]*k-HatVessel.UNDER_BANK*body)),yaw,HatVessel.Mode.FLOAT,role);
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
            double ahead=under.subtract(mother.position()).dot(forward),least=mother.getBbWidth()*.5+HatVessel.BRIM[1]*k+.1;
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
    public static Stand stand(HatPose pose,YukkuriEntity catcher){
        var place=HatVessel.placement(HatVessel.aquatic(catcher),pose.role());float yaw=pose.yaw()-place.yaw();
        var offset=new Vec3(place.offset().x,0,place.offset().z).scale(catcher.growthScale()*catcher.getScale()/16);
        var at=pose.at().subtract(offset.yRot((float)Math.toRadians(180-yaw)));
        return new Stand(new Vec3(at.x,catcher.getY(),at.z),yaw);
    }
    private static HatPose poseOf(HatBoatEntity hat){return new HatPose(hat.position(),hat.getYRot(),hat.isMoored()?HatVessel.Mode.FLOAT:HatVessel.Mode.NEST,hat.role());}
    /** The ground or water where the hat would go takes it. */
    private boolean settles(HatPose pose){
        var spot=BlockPos.containing(pose.at());
        if(!level().hasChunkAt(spot))return false;
        if(pose.mode()==HatVessel.Mode.FLOAT){
            for(int down=0;down<3;down++)if(level().getFluidState(spot.below(down)).is(net.minecraft.tags.FluidTags.WATER))return true;
            return false;
        }
        return level().getFluidState(spot).isEmpty()&&level().getBlockState(spot.below()).isFaceSturdy(level(),spot.below(),net.minecraft.core.Direction.UP);
    }
    private void face(float yaw){entity.setYRot(yaw);entity.yBodyRot=yaw;entity.yBodyRotO=yaw;entity.setYHeadRot(yaw);entity.yHeadRotO=yaw;}
    /** Step onto the spot the braid clip is authored from and start it. */
    private void beginHat(HatMove move,HatPose target,Stand stand){
        entity.getNavigation().stop();entity.setPos(stand.at().x,entity.getY(),stand.at().z);face(stand.yaw());
        hatMove=move;hatYaw=stand.yaw();hatTarget=target;
        hatTicks=(int)Math.round((move==HatMove.OFF_SELF||move==HatMove.ON_SELF?1.8:1.6)*20);
    }
    /** This Marisa's hat, set down nearby to catch. */
    public HatBoatEntity cradleEntity() {
        if(entity.level().isClientSide)return null;
        return level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(16),b->b.catches()&&entity.getUUID().equals(b.ownerYukkuri()))
                .stream().findFirst().orElse(null);
    }
    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    public boolean keepsCradle() {
        if(cradleLinger>0||task==Task.MIDWIFE)return true;
        var pending=brood();return pending!=null&&!pending.cancelled&&!pending.complete();
    }
    private java.util.List<YukkuriEntity> youngAround(double radius){
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(radius),y->!y.family().adult()
                &&(entity.getUUID().equals(y.family().mother)||entity.getUUID().equals(y.family().father)));
    }
    private void tickHat(){
        if(hatMove!=HatMove.NONE){
            entity.getNavigation().stop();face(hatYaw);
            if(--hatTicks<=0){
                // The clip ends with the hat where it now lies: it becomes the set-down hat (the accessory leaves the head).
                if((hatMove==HatMove.OFF||hatMove==HatMove.OFF_SELF)&&hatTarget!=null)
                    HatBoatEntity.setDown(entity,hatTarget.at(),hatTarget.yaw(),hatTarget.mode(),hatTarget.role());
                hatMove=HatMove.NONE;hatTarget=null;
            }
            return;
        }
        if(fetching==null)return;
        if(fetching.isRemoved()||!fetching.catches()||entity.isPassenger()){fetching=null;return;}
        if(keepsCradle()||entity.isPanicking())return;
        var stand=stand(poseOf(fetching),entity);fetchTicks++;
        if(entity.distanceToSqr(stand.at())>.36&&fetchTicks<200){if(fetchTicks%10==1)entity.life().navigate(BlockPos.containing(stand.at()),.9,false);return;}
        if(entity.distanceToSqr(stand.at())>16){fetching=null;return;}
        var role=fetching.role();var hat=fetching;
        entity.getNavigation().stop();entity.setPos(stand.at().x,entity.getY(),stand.at().z);face(stand.yaw());
        // The young hop out over the brim first; then the braid lifts the hat from where it lay and puts it back on.
        if(hat.releaseYoung(entity)){releaseWait=14;return;}
        if(releaseWait>0){releaseWait--;return;}
        fetching=null;
        if(hat.retrieve(entity))beginHat(role==HatVessel.Role.SELF?HatMove.ON_SELF:HatMove.ON,null,stand);
    }
    /** A Marisa mother with nobody to catch for her sets her own hat before her outlet (true while she is doing so). */
    private boolean catchAlone(FamilyData.Brood brood){
        if(hatMove!=HatMove.NONE)return true;
        if(catcher(entity,brood)!=entity||cradleEntity()!=null)return false;
        var pose=catchPose(entity,brood,entity,HatVessel.Role.SELF);
        if(!settles(pose))return false;
        beginHat(HatMove.OFF_SELF,pose,stand(pose,entity));return true;
    }
    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. */
    public void fetchHat(HatBoatEntity hat){if(hatMove==HatMove.NONE&&fetching==null&&!entity.isPassenger()){fetching=hat;fetchTicks=0;}}
    public void doom(){doomed=true;}
    public boolean doomed(){return doomed;}
    public boolean flying(){return flight!=null;}
    /** Into a free place in the hat (kept for it now; nearest prefer, in hat units, or the usual one), over the brim on
     *  a parabola. */
    public void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer){
        var seat=hat.reserve(entity,prefer);double r=HatVessel.radius(entity.getBbWidth());
        var from=HatVessel.takeoff(entity.position(),hat.toWorld(seat),hat,r);
        flight=new Flight(hat.getUUID(),from,seat,null,HatVessel.solve(from,hat.toWorld(seat),hat,r,tmin));flightTick=0;
    }
    /** Pushed too hard: on along the same line past the hat's far brim, down where it comes to the ground or the water. */
    public void launchPast(HatBoatEntity hat){
        var from=entity.position();double r=HatVessel.radius(entity.getBbWidth());
        var far=HatVessel.overshoot(from,hat.toWorld(hat.peekSeat(entity)),hat,r);
        var hit=level().clip(new net.minecraft.world.level.ClipContext(far.add(0,1.5,0),far.add(0,-3,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.ANY,entity));
        var to=hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS?new Vec3(far.x,hat.getY(),far.z):hit.getLocation();
        flight=new Flight(null,from,null,to,HatVessel.solve(from,to,hat,r,HatVessel.TMIN));flightTick=0;
    }
    /** Out of the hat over its brim to a spot beside it. */
    public void hop(HatBoatEntity hat,Vec3 from,Vec3 to){
        entity.setPos(from.x,from.y,from.z);
        flight=new Flight(null,from,null,to,HatVessel.solve(from,to,hat,HatVessel.radius(entity.getBbWidth()),HatVessel.HOP_TMIN));flightTick=0;
    }
    /** A parent lowers a newborn over the brim; the child does not need its own jump ability for this short safe arc. */
    public void assistedClimbOut(HatBoatEntity hat,Vec3 from,Vec3 to){
        entity.setPos(from.x,from.y,from.z);
        flight=new Flight(null,from,null,to,HatVessel.solve(from,to,hat,HatVessel.radius(entity.getBbWidth()),4));flightTick=0;
        entity.getNavigation().stop();
    }
    /** The owner hops from the bank into the stern; her offspring use the same physical flight solver. */
    public void embark(HatBoatEntity hat) {
        var seat = hat.ownerSeat(entity);
        flight = new Flight(hat.getUUID(), entity.position(), seat, null,
                HatVessel.solve(entity.position(), hat.toWorld(seat), hat, HatVessel.radius(entity.getBbWidth()), HatVessel.HOP_TMIN));
        flightTick = 0; entity.getNavigation().stop();
    }
    /** A physical hop between two hats, clearing both brims; leave the source only after reserving the landing. */
    public boolean transferHat(HatBoatEntity from, HatBoatEntity to) {
        if (entity.getVehicle()!=from || !from.protects(entity) || !to.isAfloat()
                || !entity.getUUID().equals(to.ownerYukkuri()) && (!to.young(entity) || !to.hasRoomFor(entity))) return false;
        var seat=entity.getUUID().equals(to.ownerYukkuri())?to.ownerSeat(entity):to.reserve(entity,null);
        var start=entity.position(); var end=to.toWorld(seat); double r=HatVessel.radius(entity.getBbWidth());
        int duration=Math.max(HatVessel.solve(start,end,from,r,HatVessel.HOP_TMIN),HatVessel.solve(start,end,to,r,HatVessel.HOP_TMIN));
        from.holdPosition(); to.holdPosition(); entity.stopRiding();
        flight=new Flight(to.getUUID(),start,seat,null,duration); flightTick=0; entity.getNavigation().stop(); return true;
    }
    /** One tick along the flight; at its end a young bound for a hat boards it. */
    private boolean tickFlight(){
        if(flight==null)return false;
        var hat=flight.hat()!=null&&level().getEntity(flight.hat()) instanceof HatBoatEntity h&&!h.isRemoved()?h:null;
        if(flight.seat()!=null&&(hat==null||!hat.isAfloat()&&!hat.catches())){flight=null;return false;}  // no dry landing on a capsized hat
        var f=flight;var to=hat!=null?hat.toWorld(f.seat()):f.to();flightTick++;
        var at=HatVessel.arc(f.from(),to,f.ticks(),flightTick);
        entity.setPos(at.x,at.y,at.z);entity.fallDistance=0;entity.getNavigation().stop();entity.hasImpulse=true;
        if(flightTick<f.ticks()){entity.setDeltaMovement(HatVessel.arc(f.from(),to,f.ticks(),flightTick+1).subtract(at));return true;}
        flight=null;entity.setDeltaMovement(Vec3.ZERO);
        if(hat!=null) { if (entity.getUUID().equals(hat.ownerYukkuri())) hat.board(entity); else hat.boardYoung(entity); }
        return true;
    }
    /** The own parents' hat (set down to catch, or afloat) with room for this young, within reach. */
    private HatBoatEntity nearHat(double reach){
        return level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(reach,2,reach),b->(b.catches()||b.isAfloat())&&b.young(entity)&&b.hasRoomFor(entity))
                .stream().min(Comparator.comparingDouble(b->b.distanceToSqr(entity))).orElse(null);
    }
    /** A falling stem fruit meets the hat: on the floor it settles there (or bounces to its place), off the brim it
     *  bounces in. */
    private boolean catchFalling(){
        if(flight!=null||entity.getDeltaMovement().y>0)return false;
        double r=HatVessel.radius(entity.getBbWidth());
        for(var hat:level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(3,4,3),b->(b.catches()||b.isAfloat())&&b.young(entity)&&b.hasRoomFor(entity))){
            var l=hat.toLocal(entity.position());double h=HatVessel.footprint(hat.mode(),l.x,l.z,r/hat.unit());
            if(Double.isNaN(h)||l.y>h+1e-3)continue;
            var contact=hat.toWorld(new Vec3(l.x,h,l.z));entity.setPos(contact.x,contact.y,contact.z);entity.setDeltaMovement(Vec3.ZERO);
            var seat=hat.reserve(entity,l);  // where it came down, if free
            if(h<HatVessel.top(hat.mode())-1e-6&&Math.hypot(seat.x-l.x,seat.z-l.z)<=HatVessel.SETTLE)hat.boardYoung(entity);
            else{flight=new Flight(hat.getUUID(),contact,seat,null,HatVessel.solve(contact,hat.toWorld(seat),hat,r,HatVessel.HOP_TMIN));flightTick=0;}
            return true;
        }
        return false;
    }
    /** Aquatic mothers may give birth on a riverbank, which serves as a temporary nest. */
    private boolean bankNest(){
        if(entity.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA)return false;
        for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-4,-2,-4),entity.blockPosition().offset(4,1,4)))
            if(level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER))return true;
        return false;
    }
    private Vec3 nearestWater(){
        Vec3 best=null;
        for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-3,-2,-3),entity.blockPosition().offset(3,0,3)))
            if(level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)&&level().getFluidState(p.above()).isEmpty()){
                var c=Vec3.atCenterOf(p);if(best==null||c.distanceToSqr(entity.position())<best.distanceToSqr(entity.position()))best=c;
            }
        return best;
    }
    public FamilyData.Brood brood() {
        var result=gestation==null||entity.level().isClientSide?null:FamilyData.get(level()).brood(gestation);
        return result!=null&&result.detachedHolder==null?result:null;
    }
    public boolean sterile(){return sterile||!entity.subspecies().canReproduce();}
    public int stimulation(){return stimulation;}
    public boolean stimulate(){if(!adult()||sterile())return false;stimulation=Math.min(1200,stimulation+240);return true;}
    public boolean sterilize(){if(!adult()||sterile()||stimulation==0)return false;sterile=true;stimulation=0;return true;}
    public void detached(){gestation=null;spentStemCount=0;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());stop();entity.syncFamily();}
    public void tick() {
        if(entity.level().isClientSide||!entity.isAlive())return;
        var finished=brood();
        if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.STEM&&finished.complete())finishStem(finished);
        if(!entity.subspecies().canReproduce()) {
            // Retire invalid legacy maternal gestation while retaining born children and ancestry records.
            var pending=brood();
            if(pending!=null&&!pending.cancelled&&!pending.complete())FamilyData.get(level()).cancel(pending);
            gestation=null;stimulation=sexDrive=0;
        }
        if(entity.isNoAi()) {
            // Physics still lands a NoAI body. Confirm contact without advancing growth, flights or hat care.
            handleLanding(false);
            if(birthId!=null)FamilyData.get(level()).landed(entity);
            return;
        }
        if(!initialized) {
            initialized=true;double variation=.95+new java.util.Random(entity.getUUID().getLeastSignificantBits()).nextDouble()*.1;
            maturity=Math.round(FamilyConfig.MATURITY.get()*variation);
            lifespan=maturity+Math.round(FamilyConfig.ADULT_LIFESPAN.get()*variation);
            age=maturity+1;nourishment=maturity;
        }
        age++;if(entity.life().nutrition()>=45&&entity.getHealth()>=entity.getMaxHealth()*.6)nourishment++;
        if(recovery>0)recovery--;if(wait>0)wait--;if(stimulation>0)stimulation--;if(liveRecovery>0)liveRecovery--;
        if(sexDrive>0){sexDrive--;if(sexDrive%200==0)entity.life().spendNutrition(1);}
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        if(cradleLinger>0)cradleLinger--;
        tickHat();
        if(tickFlight())return;
        if(awaitingLanding&&!doomed&&!entity.isPassenger()&&catchFalling())return;
        if(handleLanding(true))return;
        if(birthId!=null)FamilyData.get(level()).landed(entity);
        settleCareFood();
        if(entity.tickCount%40==0)retryMotherBurrow();
        if(FamilyConfig.AGING.get()&&age>=lifespan) { entity.hurt(level().damageSources().generic(),entity.getMaxHealth()+100);return; }
        if(entity.isPanicking()||entity.life().hasUrgentNeed()||entity.isCommandPlaying())stop();
        var brood=brood();
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.STEM) advanceStem(brood);
        else if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE)advanceLive(brood);
        else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
    }
    /** Pure contact confirmation also runs with AI disabled; optional hat seeking remains an AI behavior. */
    private boolean handleLanding(boolean hopIntoHat) {
        if(!awaitingLanding||flight!=null||!(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof HatBoatEntity))return false;
        awaitingLanding=false;
        if(doomed){
            // Pushed past the hat: drowned in the water, or killed by the hard landing.
            doomed=false;entity.hurt(entity.isInWaterOrBubble()?level().damageSources().drown():level().damageSources().generic(),entity.getMaxHealth()*4);return true;
        }
        if(hopIntoHat&&!entity.isPassenger()){
            var hat=nearHat(2.5);
            if(hat!=null)launchInto(hat,HatVessel.HOP_TMIN,hat.toLocal(entity.position()));
        }
        entity.showReaction("happy","newborn",100);return false;
    }
    /** Called once when a real sleep completes. Neither observation nor time-of-day commands grow the entity. */
    public boolean woke() {
        if(entity.level().isClientSide||entity.life().nutrition()<35)return false;
        Stage next=stage;long threshold=maturity;
        float desired=scale;
        if(stage==Stage.BABY&&age>=threshold/4&&nourishment>=threshold*3/16)next=Stage.JUVENILE;
        if(next==Stage.JUVENILE&&age>threshold&&nourishment>=threshold*.75)next=Stage.ADULT;
        // The size standard (GrowthSize): one curve over nourishment, capped by the stage reached, never shrinking.
        desired=Math.max(scale,GrowthSize.scale(next,nourishment,threshold,adultScale,entity.getVariant().isHouse()));
        var box=LiveBirthGeometry.bodyDimensions(entity,desired*entity.subspecies().formScale(),liveBelly()).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,box))return false;
        boolean changed=next!=stage||Math.abs(desired-scale)>.001f;stage=next;scale=desired;
        entity.syncFamily();if(changed)entity.showReaction("happy","grown",100);return changed;
    }
    public void newborn(FamilyData.Child child,FamilyData.Brood brood) {
        initialized=true;mother=brood.mother;father=brood.father;birthId=brood.id;adultScale=child.adultScale;lifespan=child.lifespan;
        maturity=Math.clamp(child.maturity,1,Math.max(1,lifespan-1));
        age=nourishment=0;stage=GrowthSize.birthStage(brood.kind);
        scale=GrowthSize.birth(brood.kind);
        awaitingLanding=brood.kind!=FamilyData.BirthKind.EGG;
        juvenileVoice=new java.util.Random(child.id.getLeastSignificantBits()).nextInt(10)==0;
        CompoundTag personality=new CompoundTag();personality.putIntArray("Innate",child.traits);personality.putIntArray("Experience",new int[6]);
        if(child.dispositions.length==4){personality.putInt("Version",2);personality.putIntArray("DispositionInnate",child.dispositions);}
        entity.life().personality().load(personality);
        entity.childhood().newborn();
        entity.syncFamily();
    }
    public void conceived(UUID brood,UUID other) { gestation=brood;partner=other;conceptions++;recovery=FamilyConfig.RECOVERY.get();entity.setPersistenceRequired(); }
    /** Manufactured filling has no identified father and does not create or replace a partner. */
    public void assisted(UUID brood) {
        if(gestation!=null)return;
        gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();spentStemCount=0;
        stop();entity.setPersistenceRequired();entity.syncFamily();
    }
    public boolean readyForAssistance() {
        return !entity.level().isClientSide&&!entity.getVariant().isHouse()&&!entity.getVariant().isPredator()
                &&!entity.isNoAi()&&gestation==null&&adult()&&!sterile()&&!elder()&&recovery==0
                &&entity.isAlive()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.predator().active()
                &&(!entity.operations().active()||entity.operations().pose()==io.github.longshou.mcyukkuri.interaction.OperationLife.Pose.ASSIST)
                &&!entity.dailyLife().active()
                &&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&supported()
                &&!entity.life().hasUrgentNeed()&&!entity.life().isSleeping()&&entity.life().mealTicks()==0
                &&entity.life().nutrition()>=65&&entity.life().energy()>=50&&entity.life().hygiene()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.8&&entity.body().wound()==0&&entity.body().infection()==0
                &&entity.life().homeStand()!=null;
    }
    public void partnerConceived(UUID other) { partner=other;recovery=FamilyConfig.RECOVERY.get();entity.setPersistenceRequired(); }
    /** An already-born juvenile in a naturally discovered household, not a simulated conception. */
    public void generatedChild(YukkuriEntity first, YukkuriEntity second) {
        initialized = true; mother = first.getUUID(); father = second == null ? null : second.getUUID();
        maturity = FamilyConfig.MATURITY.get(); lifespan = maturity + FamilyConfig.ADULT_LIFESPAN.get();
        age = maturity * 3 / 8; nourishment = age;
        stage = Stage.JUVENILE; adultScale = 1; scale = GrowthSize.scale(stage, nourishment, maturity, adultScale, entity.getVariant().isHouse());
        entity.syncFamily();
        var relations = YukkuriWorldData.get(level());
        for (YukkuriEntity parent : second == null ? java.util.List.of(first) : java.util.List.of(first, second)) {
            relations.relate(entity.getUUID(), parent.getUUID(), 30);
            relations.relate(parent.getUUID(), entity.getUUID(), 30);
        }
    }
    public boolean introduce(YukkuriEntity other) {
        if(other==entity||!adult()||!other.family().adult()||entity.getVariant().isHouse()!=other.getVariant().isHouse()
                ||HatVessel.aquatic(entity)!=HatVessel.aquatic(other)
                ||entity.getVariant().isPredator()!=other.getVariant().isPredator()
                ||entity.level()!=other.level()||entity.distanceToSqr(other)>64)return false;
        var data=FamilyData.get(level());data.register(entity);data.register(other);
        if(data.related(entity.getUUID(),other.getUUID())||data.busyParent(entity.getUUID())||data.busyParent(other.getUUID()))return false;
        if(partner!=null&&!partner.equals(other.getUUID())||other.family().partner!=null&&!other.family().partner.equals(entity.getUUID()))return false;
        partner=other.getUUID();other.family().partner=entity.getUUID();wait=other.family().wait=0;
        entity.setPersistenceRequired();other.setPersistenceRequired();entity.showReaction("happy","introduced",80);other.showReaction("happy","introduced",80);
        return true;
    }
    public boolean ready() {
        return adult()&&!sterile()&&!elder()&&recovery==0&&entity.isAlive()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()&&!entity.predator().active()
                &&!entity.life().hasUrgentNeed()&&entity.life().nutrition()>=65&&entity.life().energy()>=50&&entity.life().hygiene()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.8&&entity.body().wound()==0&&entity.body().infection()==0&&entity.life().mealTicks()==0&&entity.life().homeStand()!=null;
    }
    public boolean canReproduceWith(YukkuriEntity other) {
        if(other==entity||entity.dailyLife().active()||other.dailyLife().active()||entity.level()!=other.level()||!ready()||!other.family().ready()||entity.distanceToSqr(other)>9
                ||entity.getVariant().isHouse()!=other.getVariant().isHouse()||entity.getVariant().isPredator()!=other.getVariant().isPredator()||!entity.life().canRecognize(other)||!other.life().canRecognize(entity))return false;
        if(partner!=null&&!partner.equals(other.getUUID())||other.family().partner!=null&&!other.family().partner.equals(entity.getUUID()))return false;
        // The aquatic kind only pairs within itself: aquatic couples are two aquatic Marisa.
        var aquatic=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA;
        if((entity.subspecies().kind()==aquatic)!=(other.subspecies().kind()==aquatic))return false;
        var data=FamilyData.get(level());var relations=YukkuriWorldData.get(level());
        if(data.related(entity.getUUID(),other.getUUID())||relations.affinity(entity.getUUID(),other.getUUID())<12||relations.affinity(other.getUUID(),entity.getUUID())<12)return false;
        var a=entity.life().homeStand();var b=other.life().homeStand();
        return a!=null&&b!=null&&a.distSqr(b)<=64&&entity.life().reachable(other.blockPosition(),false);
    }
    private void advanceStem(FamilyData.Brood brood) {
        if(entity.operations().active())return;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()||entity.life().nutrition()<30||entity.body().wound()>0||entity.body().infection()>0)return;
        var boat=boatHome();
        if(boat!=null){ if(!FamilyData.get(level()).relocate(brood,level(),boat.blockPosition()))return; }
        else if(bankNest()&&(entity.life().homeStand()==null||entity.distanceToSqr(Vec3.atBottomCenterOf(entity.life().homeStand()))>4)) {
            if(!FamilyData.get(level()).relocate(brood,level(),entity.blockPosition()))return;
        } else {
            BlockPos home=entity.life().homeStand();
            if(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4||!FamilyData.get(level()).relocate(brood,level(),home))return;
        }
        if(entity.life().mealTicks()>0||entity.life().isSleeping())return;
        if(boat==null&&brood.elapsed>=brood.duration-200&&catchAlone(brood))return;
        if(hatMove!=HatMove.NONE)return;
        if(brood.elapsed<brood.duration+40)brood.elapsed++;
        if(brood.elapsed%200==0)entity.life().spendNutrition(1);
        FamilyData.get(level()).setDirty();
        for(int i=0;i<brood.children.size();i++) {
            if(brood.children.get(i).born||brood.elapsed<brood.duration+i*8)continue;
            Vec3 local=FamilyGeometry.childBase(brood.children.size(),i,1,entity.growthScale(),brood.variation,entity.getVariant(),brood.children.get(i).variant);
            Vec3 world=FamilyGeometry.toWorld(entity,local);
            // A blocked attachment delays birth; it does not spawn through the ceiling or into a wall.
            var child=FamilyData.get(level()).birth(brood,i,level(),world);
            if(child!=null) { if(boat==null&&!child.family().doomed()&&!child.family().flying())child.setDeltaMovement(0,-.015,0);entity.showReaction("happy","birth",100); }
        }
        if(brood.complete())finishStem(brood);
    }
    private void finishStem(FamilyData.Brood brood) {
        var drops=ShedStemDrops.get(level());drops.queue(entity,brood);
        recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());
        if(!drops.resolve(level(),brood.id))return;
        if(cradleEntity()!=null)cradleLinger=120;
        spentStemCount=0;
        gestation=null;stop();entity.syncFamily();
    }
    public FamilyData.BirthKind naturalBirthKind(YukkuriEntity other){
        if(entity.getVariant().isHouse())return FamilyData.BirthKind.EGG;
        if(entity.getVariant().isPredator())return FamilyData.BirthKind.STEM;
        long seed=entity.getUUID().getMostSignificantBits()^other.getUUID().getLeastSignificantBits()^conceptions^0x4c495645L;
        return new java.util.Random(seed).nextDouble()<FamilyConfig.LIVE_BIRTH_CHANCE.get()?FamilyData.BirthKind.LIVE:FamilyData.BirthKind.STEM;
    }
    public boolean readyForDelivery(){
        return !entity.level().isClientSide&&entity.isAlive()&&!entity.isNoAi()&&supported()&&!entity.operations().active()
                &&!entity.dailyLife().active()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.life().hasUrgentNeed()
                &&!entity.life().isSleeping()&&entity.life().mealTicks()==0&&entity.body().wound()==0&&entity.body().infection()==0;
    }
    private boolean supported(){
        if(boatHome()!=null)return true;
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    private void advanceLive(FamilyData.Brood brood){
        Vec3 before=deliveryLast;deliveryLast=entity.position();
        if(!readyForDelivery())return;
        var boat=boatHome();
        if(boat!=null){ if(!FamilyData.get(level()).relocate(brood,level(),boat.blockPosition()))return; }
        else {
            var home=entity.life().homeStand();
            boolean bank=bankNest()&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4);
            if(!bank&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4)||!FamilyData.get(level()).relocate(brood,level(),bank?entity.blockPosition():home))return;
            // On land the mother must hold still; afloat, drifting with the water does not stop a delivery.
            if(brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004))return;
        }
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(boat==null&&brood.elapsed>=brood.duration-2&&catchAlone(brood))return;
        if(hatMove!=HatMove.NONE)return;
        if(brood.elapsed>=brood.duration){
            entity.getNavigation().stop();
            if(!LiveBirthGeometry.clear(entity,brood.children.get(next),next))return;
        }
        float nextBelly=LiveBirthGeometry.belly(Math.min(1,(brood.elapsed+1f)/brood.duration),brood.children.size()-brood.unborn(),brood.children.size());
        var room=LiveBirthGeometry.bodyDimensions(entity,entity.growthScale(),nextBelly).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,room))return;
        brood.elapsed++;if(brood.elapsed%200==0)entity.life().spendNutrition(1);FamilyData.get(level()).setDirty();
        if(brood.elapsed>=brood.duration){
            if(brood.elapsed%24==0)entity.showReaction("chewing_closed","birth",25);
            if(brood.elapsed>=brood.duration+LiveBirthGeometry.birthTick(next)){
                var child=FamilyData.get(level()).birth(brood,next,level(),LiveBirthGeometry.position(entity,next));
                if(child!=null){
                    // A soft physical release lets the landed sibling clear the next child's outlet.
                    if(boat==null&&!child.isPassenger()&&!child.family().doomed()&&!child.family().flying())child.setDeltaMovement(new Vec3(0,-.015,-.075).yRot((float)Math.toRadians(180-entity.yBodyRot)).scale(entity.getScale()));
                    entity.showReaction("happy","birth",60);
                }
            }
        }
        if(brood.complete()){if(cradleEntity()!=null)cradleLinger=120;gestation=null;liveRecovery=70;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());deliveryLast=null;stop();}
    }
    public boolean canUse() {
        if(entity.dailyLife().active())return false;
        if(entity.isPanicking()||entity.isCommandPlaying()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        if(task!=Task.NONE)return true;
        if(wait>0)return false;wait=40;
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind!=FamilyData.BirthKind.EGG) { task=Task.BROOD;taskTicks=0;return true; }
        if(!adult()) {
            if(!entity.childhood().shouldStayWithParents())return false;
            var home=earlyHomeStand();
            if(home!=null) {
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4) { task=Task.STAY_HOME;target=null;taskTicks=0;return true; }
                return false;
            }
            target=closestParent();
            if(target!=null&&entity.distanceToSqr(target)>2.25) { task=Task.FOLLOW;taskTicks=0;return true; }
            if(target==null) {
                var data=FamilyData.get(level());var known=data.person(mother);
                if(known==null||known.status==FamilyData.Status.DEAD)known=data.person(father);
                if(known!=null&&known.status==FamilyData.Status.ALIVE&&known.position!=null&&known.position.dimension().equals(level().dimension())
                        &&level().hasChunkAt(known.position.pos())&&entity.distanceToSqr(Vec3.atBottomCenterOf(known.position.pos()))>4
                        &&entity.distanceToSqr(Vec3.atBottomCenterOf(known.position.pos()))<256) {
                    searchAt=Vec3.atBottomCenterOf(known.position.pos());task=Task.SEARCH;taskTicks=0;entity.showReaction("surprise","lost_parent",100);return true;
                }
            }
            return false;
        }
        var mate=mateInLabor();
        if(mate!=null) { task=Task.MIDWIFE;target=mate;taskTicks=0;entity.showReaction("happy","caregiver",60);return true; }
        if(startCare())return true;
        var child=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(10),other->!other.family().adult()
                &&other.childhood().shouldStayWithParents()
                &&(entity.getUUID().equals(other.family().mother)||entity.getUUID().equals(other.family().father))&&entity.life().canRecognize(other))
                .stream().filter(other->entity.distanceToSqr(other)>6.25&&entity.hasLineOfSight(other)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(child!=null) { task=Task.WAIT_CHILD;target=child;taskTicks=0;entity.showReaction("happy","wait_family",80);return true; }
        if(!entity.getVariant().isHouse()&&ready()&&(FamilyConfig.NATURAL_PAIRING.get()||partner!=null||sexDrive>0)) {
            var data=FamilyData.get(level());
            if(data.busyParent(entity.getUUID()))return false;
            target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    other.family().task==Task.COURT&&other.family().target==entity&&canReproduceWith(other)).stream().findFirst().orElse(null);
            if(target!=null) { task=Task.RECEIVE_COURT;taskTicks=0;return true; }
            target=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(3),other->
                    io.github.longshou.mcyukkuri.settlement.Seasons.mayCourt(entity)&&entity.getUUID().compareTo(other.getUUID())<0&&(partner==null||partner.equals(other.getUUID()))
                            &&(other.family().partner==null||entity.getUUID().equals(other.family().partner))&&!data.busyParent(other.getUUID())&&canReproduceWith(other))
                    .stream().findFirst().orElse(null);
            if(target!=null) { task=Task.COURT;taskTicks=0;entity.showReaction("happy","courtship",100);return true; }
        }
        return false;
    }
    /** A Marisa father comes to the mother when her young are about to come, on land, live or stem birth (user design). */
    private YukkuriEntity mateInLabor() {
        if(entity.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA||partner==null||entity.isPassenger()
                ||!(level().getEntity(partner) instanceof YukkuriEntity mate)||!mate.isAlive()||mate.family().boatHome()!=null
                ||entity.distanceToSqr(mate)>256||!entity.life().canRecognize(mate))return null;
        var b=mate.family().brood();
        if(b==null||b.cancelled||b.complete()||b.kind==FamilyData.BirthKind.EGG||!entity.getUUID().equals(b.father))return null;
        return b.elapsed>=b.duration-(b.kind==FamilyData.BirthKind.LIVE?40:300)?mate:null;
    }
    /** Beside the outlet, clear of the exit box, on whichever side is free. */
    private Vec3 midwifeSpot(YukkuriEntity mate) {
        var forward=Vec3.directionFromRotation(0,mate.yBodyRot);var side=new Vec3(-forward.z,0,forward.x);
        double reach=mate.getBbWidth()*.5+entity.getBbWidth()*.5+.12;
        Vec3 best=null;
        for(int sign:new int[]{1,-1}){
            var spot=mate.position().add(forward.scale(mate.getBbWidth()*.45)).add(side.scale(sign*reach));
            if(best==null||level().noCollision(entity,entity.getBoundingBox().move(spot.subtract(entity.position()))))best=spot;
            if(level().noCollision(entity,entity.getBoundingBox().move(spot.subtract(entity.position()))))break;
        }
        return best;
    }
    private YukkuriEntity closestParent() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),other->other.isAlive()
                &&(other.getUUID().equals(mother)||other.getUUID().equals(father))&&entity.life().canRecognize(other))
                .stream().filter(other->entity.life().reachable(other.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    /** Early children wait by their real shared shelter instead of continually chasing a foraging parent. */
    private BlockPos earlyHomeStand() {
        if(readyForOwnNest())return null;
        var home=entity.habitat().shelter();
        return home!=null&&home.usable(entity)?home.bed(entity.getUUID()):null;
    }
    /** A failed conception-time path does not move either parent; retry only while this father's brood is pending. */
    private void retryMotherBurrow() {
        if(partner==null||!(level().getEntity(partner) instanceof YukkuriEntity mother)||mother==entity)return;
        var brood=mother.family().brood();
        if(brood==null||brood.cancelled||brood.complete()||!entity.getUUID().equals(brood.father))return;
        var home=mother.habitat().shelter();
        if(FamilyData.ordinaryWildBurrow(mother,entity,home)&&!home.owns(entity.getUUID()))entity.habitat().choose(home);
    }
    private boolean careReady() {
        return adult()&&!awaitingLanding&&!entity.isNoAi()&&!entity.isPassenger()&&!entity.getVariant().isHouse()&&!entity.getVariant().isPredator()
                &&!HatVessel.aquatic(entity)&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&!entity.operations().active()&&!entity.predator().active()
                &&!entity.life().isSleeping()&&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!entity.independence().active()
                &&entity.habitat().carrying().isEmpty()&&entity.independence().carrying().isEmpty()
                &&entity.settlement().duties().carrying().isEmpty()&&entity.house().carrying().isEmpty();
    }
    private boolean hungryOwnChild(YukkuriEntity child) {
        return child!=entity&&child.isAlive()&&!child.family().adult()&&!child.family().awaitingLanding()
                &&caresFor(child)&&entity.life().canRecognize(child)&&child.life().canReceiveCareFood()
                &&(carrying.isEmpty()||child.life().canReceiveCareFood(carrying))
                &&child.life().nutrition()<entity.life().nutrition()-15&&entity.life().reachable(child.blockPosition(),false);
    }
    private YukkuriEntity hungriestOwnChild() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),this::hungryOwnChild).stream()
                .min(Comparator.comparingDouble((YukkuriEntity child)->child.life().nutrition()).thenComparingDouble(entity::distanceToSqr)).orElse(null);
    }
    private boolean startCare() {
        if(!careReady())return false;
        target=hungriestOwnChild();
        if(target==null)return false;
        foodSource=null;foodStore=null;careOfferTicks=0;
        if(carrying.isEmpty())selectFoodSource();
        if(carrying.isEmpty()&&foodSource==null&&foodStore==null){target=null;return false;}
        task=Task.FEED_CHILD;taskTicks=0;return true;
    }
    /** Choose a real nearby drop or the caregiver's own stocked shelter, without reserving either. */
    private void selectFoodSource() {
        foodSource=null;foodStore=null;
        ItemEntity nearby=EventHooks.canEntityGrief(level(),entity)
                ?level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(8),item->item.isAlive()&&!item.hasPickUpDelay()
                        &&entity.life().acceptsFood(item.getItem())&&target!=null&&target.life().canReceiveCareFood(item.getItem())
                        &&entity.life().reachable(item.blockPosition(),false)).stream()
                        .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null):null;
        var shelter=entity.habitat().shelter();
        BlockPos bed=shelter==null?null:shelter.bed(entity.getUUID());
        boolean stocked=shelter!=null&&shelter.usable(entity)&&target!=null&&shelter.hasFoodFor(entity,target)&&bed!=null&&entity.life().reachable(bed,false);
        if(nearby!=null&&(!stocked||entity.distanceToSqr(nearby)<=entity.distanceToSqr(Vec3.atBottomCenterOf(bed))))foodSource=nearby;
        else if(stocked)foodStore=shelter.getBlockPos();
    }
    private boolean validCareTarget() { return target!=null&&hungryOwnChild(target); }
    /** A cancelled handoff does not mint or erase food: return the held portion or leave it where the parent stands. */
    private void settleCareFood() {
        if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        if(task==Task.FEED_CHILD){careCarryTicks=0;return;}
        if(++careCarryTicks<200)return;
        var home=entity.habitat().shelter();var bed=home==null?null:home.bed(entity.getUUID());
        if(home!=null&&bed!=null&&home.usable(entity)&&entity.distanceToSqr(Vec3.atBottomCenterOf(bed))<.64) {
            carrying=home.returnFood(carrying);
            if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        }
        if(entity.life().dryStand(entity.blockPosition())) {
            var food=entity.spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;careCarryTicks=careChewTicks=careOfferTicks=0;}
        }
    }
    private void tickCare() {
        if(!validCareTarget()) {
            target=hungriestOwnChild();foodSource=null;foodStore=null;careOfferTicks=0;
            if(target==null){stop();return;}
        }
        if(carrying.isEmpty()) {
            if(foodSource==null&&foodStore==null)selectFoodSource();
            if(foodSource!=null) {
                if(!foodSource.isAlive()||foodSource.hasPickUpDelay()||!entity.life().acceptsFood(foodSource.getItem())) { foodSource=null;return; }
                entity.getLookControl().setLookAt(foodSource,20,20);
                if(entity.distanceToSqr(foodSource)<.5&&entity.hasLineOfSight(foodSource)) {
                    ItemStack remaining=foodSource.getItem().copy();carrying=remaining.split(1);careChewTicks=careOfferTicks=0;
                    if(remaining.isEmpty())foodSource.discard();else foodSource.setItem(remaining);
                    foodSource=null;
                } else if(taskTicks%10==1&&!entity.life().navigate(foodSource.blockPosition(),1.1,false))foodSource=null;
                return;
            }
            if(foodStore!=null) {
                var home=level().getBlockEntity(foodStore) instanceof io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity shelter?shelter:null;
                var bed=home==null?null:home.bed(entity.getUUID());
                if(home==null||bed==null||!home.usable(entity)||!home.hasFoodFor(entity,target)) { foodStore=null;return; }
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(bed))<.64) {
                    var food=home.takeFood(entity,target);
                    if(!food.isEmpty()&&target.life().canReceiveCareFood(food)){carrying=food;careChewTicks=careOfferTicks=0;}
                    else if(!food.isEmpty()){carrying=home.returnFood(food);foodStore=null;}
                }
                else if(taskTicks%10==1&&!entity.life().navigate(bed,.9,false))foodStore=null;
                return;
            }
            stop();return;
        }
        if(target.family().needsMash()&&!InfantFood.isMash(carrying)) {
            entity.getNavigation().stop();
            if(++careChewTicks >= 40) { carrying=InfantFood.mash(carrying,entity.getUUID());careOfferTicks=0; }
            return;
        }
        FamilyFeedingContact.faceEachOther(entity,target);
        if(FamilyFeedingContact.mouthsMeet(entity,target)) {
            entity.getNavigation().stop();
            if(++careOfferTicks<FamilyFeedingContact.REQUIRED_CONTACT_TICKS)return;
            boolean handed=target.family().needsMash() ? target.life().beginParentMeal(entity,carrying) : target.life().beginMeal(carrying,null,true);
            if(handed) { carrying=ItemStack.EMPTY;careChewTicks=careOfferTicks=0;entity.showReaction("happy","share",80); }
            stop();return;
        }
        careOfferTicks=0;
        Vec3 contact=FamilyFeedingContact.approachSpot(entity,target);
        if(entity.distanceToSqr(target)<=4&&entity.hasLineOfSight(target)&&target.hasLineOfSight(entity)) {
            // Path navigation stops at a block-sized tolerance.  The last fraction must be a real
            // precise approach, or the two mouths can remain permanently just outside contact.
            entity.getNavigation().stop();
            entity.getMoveControl().setWantedPosition(contact.x,contact.y,contact.z,.8);
        } else if(taskTicks%5==1&&!entity.life().navigate(target.blockPosition(),1.1,false))stop();
    }
    public boolean canContinue() {
        if(task==Task.NONE||entity.isPanicking()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||entity.isCommandPlaying())return false;
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        }
        if(task==Task.FEED_CHILD)return careReady()&&validCareTarget()&&taskTicks<480;
        return (taskTicks<240||task==Task.MIDWIFE)&&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        switch(task) {
            case BROOD -> {
                if(boatHome()!=null){entity.getNavigation().stop();return;}
                BlockPos home=entity.life().homeStand();
                if(bankNest()&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4)){
                    // Ashore at a bank she gives birth where she landed, facing the river: her hat floats there to catch.
                    entity.getNavigation().stop();var water=nearestWater();
                    if(water!=null&&hatMove==HatMove.NONE&&!laboring())face((float)(Mth.atan2(water.z-entity.getZ(),water.x-entity.getX())*Mth.RAD_TO_DEG)-90);
                    return;
                }
                if(home==null){stop();return;}
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>.36) { if(taskTicks%10==1)entity.life().navigate(home,.9,false); }
                else { entity.getNavigation().stop();entity.getLookControl().setLookAt(entity.getX(),entity.getEyeY(),entity.getZ()-2,10,10); }
            }
            case COURT -> {
                if(!canReproduceWith(target)){stop();return;}
                entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)>2.25) { if(taskTicks%10==1)entity.life().navigate(target.blockPosition(),.8,false); }
                else if(taskTicks>=(sexDrive>0?60:120)) {
                    // A Reimu and Marisa couple: Reimu always carries the young and Marisa is the father.
                    YukkuriEntity mother=entity,father=target;
                    if(entity.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&target.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU){mother=target;father=entity;}
                    var brood=FamilyData.get(level()).conceive(mother,father,mother.family().naturalBirthKind(father),GlobalPos.of(level().dimension(),mother.life().homeStand()));
                    if(brood!=null) { partner=target.getUUID();target.family().partner=entity.getUUID();mother.showReaction("happy","expecting",100); }
                    stop();wait=200;
                } else entity.getNavigation().stop();
            }
            case RECEIVE_COURT -> {
                if(target.family().task!=Task.COURT||target.family().target!=entity||!canReproduceWith(target)){stop();return;}
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
            }
            case FOLLOW -> {
                if(!entity.life().canRecognize(target)||entity.distanceToSqr(target)<2.25){stop();return;}
                var bank=target.getVehicle() instanceof HatBoatEntity && target.aquaticLife().awaiting(entity)?target.aquaticLife().boardingBank():null;
                entity.getLookControl().setLookAt(target,20,20);
                if(taskTicks%10==1&&!entity.life().navigate(bank==null?target.blockPosition():bank,1.15,false))stop();
            }
            case STAY_HOME -> {
                var home=earlyHomeStand();
                if(home==null){stop();return;}
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4) {
                    if(taskTicks%10==1&&!entity.life().navigate(home,1.0,false))stop();
                } else entity.getNavigation().stop();
            }
            case FEED_CHILD -> tickCare();
            case WAIT_CHILD -> {
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)<4||!entity.life().canRecognize(target))stop();
            }
            case MIDWIFE -> {
                var b=target.family().brood();
                if(b==null||b.cancelled||b.complete()||target.family().boatHome()!=null){ if(cradleEntity()!=null)cradleLinger=120; stop();return; }
                if(hatMove!=HatMove.NONE)return;
                var hat=cradleEntity();
                if(hat!=null){
                    // Holding the set-down hat's rim with the braid from the place the clip is authored from.
                    var stand=stand(poseOf(hat),entity);entity.getNavigation().stop();
                    if(entity.distanceToSqr(stand.at())>.04)entity.setPos(stand.at().x,entity.getY(),stand.at().z);
                    face(stand.yaw());return;
                }
                var pose=catcher(target,b)==entity?catchPose(target,b,entity,HatVessel.Role.HELPER):null;
                if(pose!=null&&settles(pose)){
                    // Walk to the place beside the outlet, then the braid takes the hat off and sets it down there.
                    var stand=stand(pose,entity);
                    if(entity.distanceToSqr(stand.at())>.36){ if(taskTicks%10==1)entity.life().navigate(BlockPos.containing(stand.at()),.9,false); }
                    else beginHat(HatMove.OFF,pose,stand);
                    return;
                }
                // Without a hat to set (a shell Marisa, or no ground or water before the outlet) she cheers beside it.
                var spot=midwifeSpot(target);var outlet=LiveBirthGeometry.position(target,0);
                if(entity.distanceToSqr(spot)>.3) { if(taskTicks%10==1)entity.life().navigate(BlockPos.containing(spot),.9,false); }
                else entity.getNavigation().stop();
                entity.getLookControl().setLookAt(outlet.x,outlet.y+.1,outlet.z,20,20);
            }
            case SEARCH -> {
                if(searchAt==null||entity.distanceToSqr(searchAt)<2){stop();return;}
                if(taskTicks%20==1&&!entity.life().navigate(BlockPos.containing(searchAt),.9,false))stop();
            }
            default -> { }
        }
    }
    public void stop() { task=Task.NONE;target=null;searchAt=null;foodSource=null;foodStore=null;taskTicks=0;careOfferTicks=0;wait=Math.max(wait,40); }
    public void died() {
        if(!carrying.isEmpty()) {
            var food=entity.spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;}
        }
        var data=FamilyData.get(level());data.died(entity);stop();
        for(var other:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(16),other->other!=entity&&other.isAlive()))
            if(age<1200&&(other.getUUID().equals(mother)||other.getUUID().equals(father))) {
                // A newborn lost at birth: the parents stare blankly, then cry.
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.bereave();
            } else if(data.related(entity.getUUID(),other.getUUID())&&entity.distanceToSqr(other)<100&&other.life().canRecognize(entity)&&other.hasLineOfSight(entity)) {
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.showReaction("surprise","family_died",120);
            }
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putInt("Version",2);tag.putLong("Maturity",maturity);tag.putString("Stage",stage.name());tag.putLong("Age",age);tag.putLong("Nourishment",nourishment);
        tag.putLong("Lifespan",lifespan);tag.putFloat("AdultScale",adultScale);tag.putFloat("Scale",scale);tag.putInt("Conceptions",conceptions);tag.putInt("Recovery",recovery);
        tag.putBoolean("Initialized",initialized);tag.putBoolean("AwaitingLanding",awaitingLanding);tag.putBoolean("JuvenileVoice",juvenileVoice);
        tag.putBoolean("Sterile",sterile);tag.putInt("Stimulation",stimulation);
        tag.putBoolean("BabyTalk",babyTalk);tag.putInt("SexDrive",sexDrive);
        tag.putInt("LiveRecovery",liveRecovery);
        if (flight != null) {
            var f = new CompoundTag(); if (flight.hat()!=null) f.putUUID("Hat",flight.hat());
            putVector(f,"From",flight.from()); putVector(f,"Seat",flight.seat()); putVector(f,"To",flight.to());
            f.putInt("Ticks",flight.ticks()); f.putInt("Elapsed",flightTick); tag.put("HatFlight",f);
        }
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
        if(!carrying.isEmpty())tag.put("CareFood",carrying.save(entity.registryAccess()));
        tag.putInt("CareFoodTicks",careCarryTicks);
        tag.putInt("CareChewTicks",careChewTicks);tag.putInt("CareOfferTicks",careOfferTicks);
        if(mother!=null)tag.putUUID("Mother",mother);if(father!=null)tag.putUUID("Father",father);if(birthId!=null)tag.putUUID("BirthId",birthId);
        if(gestation!=null)tag.putUUID("Gestation",gestation);if(partner!=null)tag.putUUID("Partner",partner);return tag;
    }
    public void load(CompoundTag tag) {
        try { stage=Stage.valueOf(tag.getString("Stage")); }catch(IllegalArgumentException ignored){stage=Stage.ADULT;}
        lifespan=Math.max(600,tag.getLong("Lifespan"));age=Math.max(0,tag.getLong("Age"));nourishment=Math.max(0,tag.getLong("Nourishment"));
        if(tag.getInt("Version")<2) {
            long oldLifespan=lifespan;
            maturity=GrowthTiming.migratedMaturity(oldLifespan,FamilyConfig.MATURITY.get());
            lifespan=GrowthTiming.migratedLifespan(oldLifespan,FamilyConfig.MATURITY.get(),FamilyConfig.ADULT_LIFESPAN.get());
            age=GrowthTiming.migrateAge(age,oldLifespan,maturity,lifespan);
            nourishment=GrowthTiming.migrateNourishment(nourishment,oldLifespan,maturity);
        } else maturity=Math.clamp(tag.getLong("Maturity"),1,Math.max(1,lifespan-1));
        adultScale=Math.clamp(tag.getFloat("AdultScale"),.8f,1.2f);scale=Math.clamp(tag.getFloat("Scale"),.1f,1.2f);
        conceptions=Math.max(0,tag.getInt("Conceptions"));recovery=Math.max(0,tag.getInt("Recovery"));initialized=tag.getBoolean("Initialized");
        awaitingLanding=tag.getBoolean("AwaitingLanding");juvenileVoice=tag.getBoolean("JuvenileVoice");
        sterile=tag.getBoolean("Sterile");stimulation=Math.clamp(tag.getInt("Stimulation"),0,1200);
        babyTalk=tag.getBoolean("BabyTalk");sexDrive=Math.clamp(tag.getInt("SexDrive"),0,1200);
        liveRecovery=Math.clamp(tag.getInt("LiveRecovery"),0,70);deliveryLast=null;
        flight = null;
        if (tag.contains("HatFlight")) {
            var f=tag.getCompound("HatFlight"); var from=vector(f,"From"); var seat=vector(f,"Seat"); var to=vector(f,"To");
            int ticks=Math.clamp(f.getInt("Ticks"),1,HatVessel.TMAX);
            if (from!=null && (f.hasUUID("Hat")&&seat!=null || to!=null)) {
                flight=new Flight(f.hasUUID("Hat")?f.getUUID("Hat"):null,from,seat,to,ticks);
                flightTick=Math.clamp(f.getInt("Elapsed"),0,ticks-1);
            }
        }
        // Legacy permanent dry stems disappear; they do not mint retrospective items.
        spentStemCount=0;spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        carrying=tag.contains("CareFood")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("CareFood")):ItemStack.EMPTY;
        if(carrying.getCount()!=1)carrying=ItemStack.EMPTY;
        careCarryTicks=Math.clamp(tag.getInt("CareFoodTicks"),0,200);
        careChewTicks=Math.clamp(tag.getInt("CareChewTicks"),0,40);careOfferTicks=Math.clamp(tag.getInt("CareOfferTicks"),0,20);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        stop();entity.syncFamily();
    }
    private static void putVector(CompoundTag tag,String key,Vec3 value) {
        if(value==null)return; var v=new CompoundTag();v.putDouble("X",value.x);v.putDouble("Y",value.y);v.putDouble("Z",value.z);tag.put(key,v);
    }
    private static Vec3 vector(CompoundTag tag,String key) {
        if(!tag.contains(key))return null;var v=tag.getCompound(key);
        double x=v.getDouble("X"),y=v.getDouble("Y"),z=v.getDouble("Z");
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)?new Vec3(x,y,z):null;
    }
}
