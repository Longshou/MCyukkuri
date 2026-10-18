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
    private enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, MIDWIFE, FEED_CHILD, STAY_HOME, RECOVER }
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
    /** Transient: a shop-arranged pair skips proximity, home, recognition and affinity gates; and the partner of a courtship the owner never permitted. */
    private UUID arranged, unauthorizedWith;
    private boolean babyTalk;
    private int liveRecovery;
    /** Transient staging after a live birth (project original): the newborn the family turns to, and ticks of welcome left. */
    private UUID newborn;
    private int welcomeTicks;
    private static final int WELCOME=80;
    private Vec3 deliveryLast;
    private float deliveryYaw=Float.NaN;
    private int catcherWait;
    /** Ticks a mother near term has had no usable nest within reach: after a wait she delivers where she stands. */
    private int nestless;
    /** Explicit scene request, scoped to the current real live brood: 0 natural, 1 aimed catch, 2 overstrain (hat in place,
     *  pushed past it: fatal), 3 missed (the father is too late and there is no cushion: the newborn drops and is hurt, but lives). */
    private int deliveryOutcome;
    /** Exhibit staging (project original, server clock only): the father's duty clock and whether he gave up on the hat, ticks
     *  spent walking to a hat place, a newborn's uncushioned fall (landing still to come, tears left, hurt) and the comfort
     *  a parent gives it. */
    private int dutyTicks, bumpTicks, hatApproach, comfortTicks, comfortNear;
    private boolean dutyGaveUp, bumpPending, bumped;
    private UUID comfortBaby;
    private static final int COMFORT=200;
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
    /** A saved gravity arc into a reserved hat place (hat units) or out to a world position. */
    private record Flight(UUID hat,Vec3 from,Vec3 seat,Vec3 to,int ticks){}
    private Flight flight;
    private final HatBoarding boarding;
    private final BraidRescue rescue;
    private final CradlePickup pickup;
    private UUID cradleHolder;
    private UUID rescuingChild;
    private int flightTick, releaseWait;
    /** Visible pregnancy and the brief post-delivery recovery of the mother's belly. */
    public float liveBelly(){
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE)
            return LiveBirthGeometry.belly(pregnancy.progress(),pregnancy.children.size()-pregnancy.unborn(),pregnancy.children.size());
        return .22f*liveRecovery/LiveBirthGeometry.RECOVERY;
    }
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    /** A single real portion reserved for a hungry child. It survives an interrupted trip and reload. */
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity foodSource;
    private BlockPos foodStore;
    /** Mouth contact with the nest's forage pile when a parent fetches a portion for a hungry child. */
    private io.github.longshou.mcyukkuri.settlement.PileContact storeContact;
    private int careFinishTicks;
    public FamilyLife(YukkuriEntity entity) { this.entity=entity;rescue=new BraidRescue(entity);boarding=new HatBoarding(entity);pickup=new CradlePickup(entity); }
    public CradlePickup cradlePickup(){return pickup;}
    public void cradleHolder(UUID id){cradleHolder=id;}
    private CradlePickup pickupController(){
        if(pickup.active())return pickup;
        if(!entity.level().isClientSide&&cradleHolder!=null&&level().getEntity(cradleHolder) instanceof YukkuriEntity parent
                &&parent.family().pickup.active()&&entity.getUUID().equals(parent.family().pickup.childId()))return parent.family().pickup;
        return null;
    }
    public boolean cradleHeld(){var controller=pickupController();return controller!=null&&controller!=pickup&&controller.held();}
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
    public boolean hasUrgentlyHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),
                child->caresFor(child)&&child.life().nutrition()<30&&child.life().canReceiveCareFood()).isEmpty();
    }
    public boolean elder() { return age>=maturity+(lifespan-maturity)*.85; }
    public boolean active() { return pickupController()!=null||task!=Task.NONE||laboring()||liveRecovery>0||hatMove!=HatMove.NONE||fetching!=null||flight!=null||comfortTicks>0; }
    /**
     * An everyday family errand (waiting for, following or looking for a child, staying near the nest) gives way to comforting a hurt kin
     * ({@link io.github.longshou.mcyukkuri.social.WoundComfort}); births, feeding, courtship, brooding and recovery do not.
     * Before this the parent's standing "wait for the child" errand lasted as long as the child wandered, and the parent never noticed the wound.
     */
    public boolean yieldsToComfort() {
        return pickupController()==null&&!laboring()&&liveRecovery==0&&hatMove==HatMove.NONE&&fetching==null&&flight==null&&comfortTicks==0
                &&(task==Task.NONE||task==Task.WAIT_CHILD||task==Task.FOLLOW||task==Task.STAY_HOME||task==Task.SEARCH);
    }
    public String activity() {
        String big=HatVessel.aquatic(entity)?"_aq":"";
        if(hatMove!=HatMove.NONE)return switch(hatMove){case OFF->"hat_off";case ON->"hat_on";case OFF_SELF->"hat_off_self";default->"hat_on_self";}+big;
        if(comfortTicks>0&&comfortNear>=10)return "nuzzle";
        if(laboring())return "labor";
        if(liveRecovery>0)return "postpartum";
        return switch(task) {
            case COURT, RECEIVE_COURT -> "courtship"; case BROOD -> "tend_brood"; case FOLLOW -> "follow_parent";
            case WAIT_CHILD -> "wait_child"; case SEARCH -> "search_family";
            case MIDWIFE -> cradleEntity()!=null?"hat_hold"+big:"midwife";
            case FEED_CHILD -> careFinishTicks>0 ? "care_finish" : !carrying.isEmpty() && target != null && target.family().needsMash() && !InfantFood.isMash(carrying) ? "family_chew" : "feed_child";
            case STAY_HOME -> "stay_home"; case RECOVER -> "postpartum"; default -> awaitingLanding?"newborn_fall":"idle";
        };
    }
    /** A live delivery is under way (server side). */
    /** Why the current delivery is held up (empty when it is not): the last refusal of the ledger, or a guard of the mother's own. */
    public String birthBlock(){
        var b=brood();if(b==null||b.cancelled||b.complete())return "";
        if(!hold.isEmpty())return hold;
        return FamilyData.get(level()).birthBlock(b);
    }
    private String hold="";
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
                &&father.isAlive()&&!father.isPassenger()&&(hatted(father)||father.family().cradleEntity()!=null)
                &&father.distanceToSqr(mother)<256&&father.life().canRecognize(mother))return father;
        // Setting down the real accessory must not change who is catching halfway through labor.
        return hatted(mother)||mother.family().cradleEntity()!=null?mother:null;
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
        // The exact arrival test is two hundredths of a block; a caregiver who cannot settle on it must not hover there for ever.
        double close=++hatApproach>100?.64:.09;
        if(!approachHatStand(stand)&&entity.distanceToSqr(stand.at())>close)return;
        hatApproach=0;
        BirthStaging.caption(entity,switch(move){
            case OFF->"接生者走到出口前方，用辫子摘下自己的帽子（hat_off）";
            case OFF_SELF->"母油自己摘下帽子（hat_off_self）";
            case ON->"幼体已被放出，接生者用辫子把帽子戴回（hat_on）";
            default->"接生者把帽子戴回头上（hat_on_self）";});
        entity.getNavigation().stop();face(stand.yaw());
        hatMove=move;hatYaw=stand.yaw();hatTarget=target;
        hatTicks=(int)Math.round((move==HatMove.OFF_SELF||move==HatMove.ON_SELF?1.8:1.6)*20);
    }
    private boolean approachHatStand(Stand stand){
        return io.github.longshou.mcyukkuri.life.SurfaceApproach.walk(entity,stand.at(),.8);
    }
    /** This Marisa's hat, set down nearby to catch. */
    public HatBoatEntity cradleEntity() {
        if(entity.level().isClientSide)return null;
        return level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(16),b->b.catches()&&entity.getUUID().equals(b.ownerYukkuri()))
                .stream().findFirst().orElse(null);
    }
    /** The hat stays down while the young are coming and a little after, while they wake in it. */
    public boolean keepsCradle() {
        if(cradleLinger>0||task==Task.MIDWIFE||pickup.active())return true;
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
                if((hatMove==HatMove.OFF||hatMove==HatMove.OFF_SELF)&&hatTarget!=null){
                    var down=HatBoatEntity.setDown(entity,hatTarget.at(),hatTarget.yaw(),hatTarget.mode(),hatTarget.role());
                    // A hat that cannot be set down is a failed catch, not a clip to repeat for ever.
                    if(down==null){dutyGaveUp=true;BirthStaging.caption(entity,"帽子没能放下（落点不可用），这次改为无垫分娩");}
                    else BirthStaging.caption(entity,"帽子已翻面放在出口前（帽子离开了头顶，成为真实的帽实体），接生者扶住帽沿等待");
                }else if(hatMove==HatMove.ON||hatMove==HatMove.ON_SELF)BirthStaging.caption(entity,"帽子已戴回头上");
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
        if(!approachHatStand(stand))return;
        entity.getNavigation().stop();face(stand.yaw());
        // Dependent newborns leave in the parent's braid; capable children hop out before the empty hat is retrieved.
        if(hat.releaseYoung(entity)){releaseWait=14;return;}
        if(releaseWait>0){releaseWait--;return;}
        fetching=null;
        if(hat.retrieve(entity))beginHat(role==HatVessel.Role.SELF?HatMove.ON_SELF:HatMove.ON,null,stand);
    }
    /** A Marisa mother with nobody to catch for her sets her own hat before her outlet (true while she is doing so). */
    private boolean catchAlone(FamilyData.Brood brood){
        if(hatMove!=HatMove.NONE)return true;
        if(dutyGaveUp||deliveryOutcome==3||catcher(entity,brood)!=entity||cradleEntity()!=null)return false;
        var pose=catchPose(entity,brood,entity,HatVessel.Role.SELF);
        if(!settles(pose))return false;
        beginHat(HatMove.OFF_SELF,pose,stand(pose,entity));return true;
    }
    /** Called by the set-down hat when it is no longer needed: come back for it and put it on. */
    public void fetchHat(HatBoatEntity hat){if(hatMove==HatMove.NONE&&fetching==null&&!entity.isPassenger()){fetching=hat;fetchTicks=0;}}
    public void doom(){doomed=true;}
    public boolean doomed(){return doomed;}
    public boolean flying(){return flight!=null||boarding.active();}
    /** The hat does not collide with its own incoming passenger: a boarding, a braid transfer, or a newborn's launch into it (it must reach seats inside the well). */
    public boolean boardingThrough(HatBoatEntity hat){
        return boarding.traverses(hat)||rescue.transferringThrough(hat)||flight!=null&&hat!=null&&hat.getUUID().equals(flight.hat());
    }
    public boolean boardingDismountInPlace(HatBoatEntity hat){return boarding.traverses(hat);}
    public boolean hatLandingActive(){return boarding.landingAshore();}
    public boolean startHatLanding(HatBoatEntity hat,Vec3 dryFeet){return flight==null&&!heldByBraid()&&boarding.startLanding(hat,dryFeet);}
    public String hatLandingRefusal(){return boarding.landingRefusal();}
    /** True for a body that can hop between supports on its own; infants are lifted by the parent's braid. */
    public boolean canStepOut(){return boarding.canStep();}
    /** Hop from the hull this body rides onto another hull's reserved seat (a short, reach-tolerant arc). */
    public boolean startHatStep(HatBoatEntity from,HatBoatEntity to,Vec3 seatLocal){return flight==null&&!heldByBraid()&&boarding.startStep(from,to,seatLocal,null);}
    /** The captain's hat goes back on (the authored hat_on_self clip) once the token has been returned to her. */
    public boolean playHatOn(){
        if(hatMove!=HatMove.NONE)return false;
        hatMove=HatMove.ON_SELF;hatYaw=entity.yBodyRot;hatTarget=null;hatTicks=(int)Math.round(1.8*20);
        entity.getNavigation().stop();return true;
    }
    public boolean hatOnPlaying(){return hatMove==HatMove.ON_SELF||hatMove==HatMove.ON;}
    public boolean selfClimbActive(){return boarding.climbing();}
    public boolean climbGripActive(){return boarding.gripped();}
    public CompoundTag boardingState(){return boarding.state();}
    void rescueTarget(UUID id){rescuingChild=id;}
    public UUID rescueTarget(){return rescuingChild;}
    private BraidRescue rescueController(){
        if(rescue.active())return rescue;
        if(!entity.level().isClientSide&&rescuingChild!=null&&level().getEntity(rescuingChild) instanceof YukkuriEntity child
                &&child.family().rescue.active()&&entity.getUUID().equals(child.family().rescue.parentId()))return child.family().rescue;
        return null;
    }
    public boolean braidRescueActive(){return pickupController()!=null||rescueController()!=null;}
    public boolean heldByBraid(){return cradleHeld()||rescue.active()&&rescue.held();}
    public CompoundTag braidRescueState(){var cradle=pickupController();if(cradle!=null)return cradle.state();var controller=rescueController();return controller==null?new CompoundTag():controller.state();}
    public Vec3 braidRescueApproach(HatBoatEntity hat){return hat==null?null:rescue.approach(hat);}
    public void cancelBraidRescue(){boarding.cancel();var cradle=pickupController();if(cradle!=null)cradle.cancel();var controller=rescueController();if(controller!=null)controller.cancel();}
    /** Into a free place in the hat (kept for it now; nearest prefer, in hat units, or the usual one), over the brim on
     *  a parabola. */
    /** How far a newborn may be sent across the water from its mother's outlet into a partner's hat boat alongside. */
    public static final double SHARE_REACH=3.2;
    public void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer){launchInto(hat,tmin,prefer,1.25);}
    private static final boolean FLIGHT_DEBUG=false;
    private void flightLog(String why){if(FLIGHT_DEBUG)io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[flight-debug] {} {}",entity.getId(),why);}
    public void launchInto(HatBoatEntity hat,int tmin,Vec3 prefer,double reach){
        flightLog("launch awaiting="+awaitingLanding+" onGround="+entity.onGround()+" inWater="+entity.isInWaterOrBubble()+" at="+entity.position()+" hat="+(hat==null?null:hat.position()));
        if(hat==null||flying()||heldByBraid()||hat.unsteadyMotion())return;
        // Only the actual birth push is not a voluntary jump. Landed children must
        // approach on real ground; swimming infants wait for their parent's dipped rim.
        if(!awaitingLanding||entity.onGround()||entity.isInWaterOrBubble()){
            boardFromShore(hat);return;
        }
        var seat=hat.reserve(entity,prefer);if(seat==null){flightLog("no_seat");return;}
        double r=HatVessel.radius(entity.getBbWidth());
        var from=entity.position();var to=hat.toWorld(seat);
        int duration=HatVessel.solve(from,to,hat,r,tmin);
        if(from.subtract(to).horizontalDistance()>reach||!birthPathClear(hat,from,to,duration)){flightLog("refused dist="+from.subtract(to).horizontalDistance()+" clear="+birthPathClear(hat,from,to,duration)+" from="+from+" to="+to);hat.cancelIncoming(entity);return;}
        flightLog("flight "+duration+" ticks to "+to);
        pendingNewbornLaunch=awaitingLanding;
        beginFlight(new Flight(hat.getUUID(),from,seat,to,duration));
    }
    /** The flight in progress is a newborn's launch out of its mother's outlet (it is not blocked by the mother it leaves). */
    private boolean newbornLaunch,pendingNewbornLaunch;
    public boolean newbornLaunching(){return newbornLaunch;}
    /**
     * Sweep of a newborn's launch: the same envelope and hull clearance as {@link HatBoarding#sweepClear}, except that the mother
     * (a passenger of the hat, whose outlet the newborn leaves) is not an obstacle; with her in the way no water birth could ever land.
     */
    private String sweepWhy="";
    private boolean birthSweepClear(HatBoatEntity hat,Vec3 from,Vec3 to){
        sweepWhy="";
        var mother=entity.family().mother()!=null&&level().getEntity(entity.family().mother()) instanceof YukkuriEntity m?m:null;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++){
            var feet=from.lerp(to,(double)i/steps);
            if(!level().getWorldBorder().isWithinBounds(BlockPos.containing(feet))){sweepWhy="border";return false;}
            for(var shape:BraidRescueGeometry.boardingEnvelope(entity,feet,0,0,entity.yBodyRot)){
                var box=shape.deflate(.002);
                if(level().getBlockCollisions(entity,box).iterator().hasNext()){sweepWhy="block at "+feet;return false;}
                var nearby=new java.util.HashSet<net.minecraft.world.entity.Entity>(level().getEntities(entity,box));
                for(var other:nearby){
                    // The mother (whose outlet this is) and the siblings already seated in the hat are not obstacles: the seat planner has
                    // kept the seats apart, and the strict boarding envelope of a worn hat would refuse every newborn after the first.
                    if(other==entity||other==mother||other==hat||!other.isAlive()||other.isSpectator())continue;
                    if(other instanceof YukkuriEntity y){
                        if(hat!=null&&y.getVehicle()==hat)continue;
                        for(var part:BraidRescueGeometry.envelope(y,y.position()))if(box.intersects(part)){sweepWhy="yukkuri "+y.getId();return false;}
                    }else if(other.canBeCollidedWith()&&box.intersects(other.getBoundingBox())){sweepWhy="entity "+other;return false;}
                }
            }
            if(hat!=null){
                double clearance=BraidRescueGeometry.poseClearance(entity,feet,0,0,entity.yBodyRot,hat,null);
                if(clearance>feet.y+.002){sweepWhy="hull clearance "+clearance+" > y "+feet.y;return false;}
            }
        }
        return true;
    }
    /** Pushed too hard: on along the same line past the hat's far brim, down where it comes to the ground or the water. */
    public void launchPast(HatBoatEntity hat){
        var from=entity.position();double r=HatVessel.radius(entity.getBbWidth());
        var seat=hat.peekSeat(entity);
        var far=HatVessel.overshoot(from,seat==null?hat.position():hat.toWorld(seat),hat,r);
        var hit=level().clip(new net.minecraft.world.level.ClipContext(far.add(0,1.5,0),far.add(0,-3,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.ANY,entity));
        var to=hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS?new Vec3(far.x,hat.getY(),far.z):hit.getLocation();
        beginFlight(new Flight(null,from,null,to,HatVessel.solve(from,to,hat,r,HatVessel.TMIN)));
    }
    /** Out of the hat over its brim to a spot beside it. */
    public void hop(HatBoatEntity hat,Vec3 from,Vec3 to){
        from=entity.position();
        beginFlight(new Flight(null,from,null,to,HatVessel.solve(from,to,hat,HatVessel.radius(entity.getBbWidth()),HatVessel.HOP_TMIN)));
    }
    /** The owner hops from the bank into the stern; her offspring use the same physical flight solver. */
    public void embark(HatBoatEntity hat) {
        if(hat==null||flying()||heldByBraid()||!entity.getUUID().equals(hat.ownerYukkuri()))return;
        var seat = hat.reserveOwnBoardingSeat(entity,entity.position());
        if(seat==null)return;
        if(!boarding.begin(null,hat,seat)&&!boarding.startStep(null,hat,seat,null))hat.releaseOwnBoardingSeat(entity);
    }
    public String hatBoardingDebug(){return boarding.diagnostic();}
    /** A physical hop or supported step between hats; leave the source only after reserving the landing. */
    public boolean transferHat(HatBoatEntity from, HatBoatEntity to) {
        return transferHat(from,to,false);
    }
    /** Emergency capacity is contested only by reserveEmergency's real close-brim contact. */
    public boolean transferHat(HatBoatEntity from, HatBoatEntity to, boolean emergency) {
        if (boarding.active())return boarding.targets(to);
        if(from==null||to==null||from==to)return false;
        if (flight!=null || heldByBraid() || !entity.childhood().canJump()||stage==Stage.BABY||from.unsteadyMotion() || to.unsteadyMotion()
                || entity.getVehicle()!=from || !from.protects(entity) || !to.isAfloat()
                || !entity.getUUID().equals(to.ownerYukkuri()) && !to.young(entity)) {
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"transfer.pre."+entity.getId(),40,"flight="+(flight!=null)+" held="+heldByBraid()+" canJump="+entity.childhood().canJump()
                    +" fromUnsteady="+from.unsteadyMotion()+" toUnsteady="+to.unsteadyMotion()+" riding="+(entity.getVehicle()==from)+" protects="+from.protects(entity)+" toAfloat="+to.isAfloat());
            return false;
        }
        if(!entity.getUUID().equals(to.ownerYukkuri())&&!to.hasSafeRoomFor(entity)&&!emergency){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"transfer.room."+entity.getId(),40,"parent hull has no safe room: "+to.roomDebug(entity));
            return false;
        }
        if(from.changingSeat(entity)){
            // A long supported walk must retain its departure patch until boarding can retry.
            // approachBoardingEdge renews the lease and leaves an in-progress route untouched.
            from.approachBoardingEdge(entity,to.position());return false;
        }
        boolean own=entity.getUUID().equals(to.ownerYukkuri());
        var seat=own?to.reserveOwnBoardingSeat(entity,entity.position(),at->boarding.landingClear(from,to,at))
                :to.reserveBoardingSeat(entity,entity.position(),emergency);
        if(seat==null){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"transfer.seat."+entity.getId(),40,"no brim seat could be reserved on the target hull emergency="+emergency);
            return false;
        }
        if(boarding.begin(from,to,seat))return true;
        // The detailed rim planner is the first choice; between two hulls that touch, a short reach-tolerant hop is the fallback.
        if(boarding.startStep(from,to,seat,null))return true;
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"transfer.step."+entity.getId(),40,"hop refused: "+boarding.landingRefusal());
        to.cancelIncoming(entity);if(own)to.releaseOwnBoardingSeat(entity);
        var grip=to.boardingGrip(entity);
        from.approachBoardingEdge(entity,grip!=null?grip:to.position());return false;
    }
    /** A pupil back from its first voyage steps from its own hat onto the berth kept for it in the parent's hull. */
    public boolean returnToHat(HatBoatEntity from,HatBoatEntity to,boolean emergency){
        if(boarding.active())return boarding.targets(to);
        if(from==null||to==null||from==to||flight!=null||heldByBraid()||stage==Stage.BABY||!entity.childhood().canJump()
                ||from.unsteadyMotion()||to.unsteadyMotion()||entity.getVehicle()!=from||!from.protects(entity)||!to.isAfloat()||!to.young(entity))return false;
        if(from.changingSeat(entity)){from.approachBoardingEdge(entity,to.position());return false;}
        var seat=to.returnSeat(entity,emergency);
        if(seat==null){
            io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"return.seat."+entity.getId(),40,"no berth or brim place for the returning pupil");
            return false;
        }
        if(boarding.begin(from,to,seat)||boarding.startStep(from,to,seat,null))return true;
        io.github.longshou.mcyukkuri.subspecies.AquaDiag.log(entity,"return.step."+entity.getId(),40,"refused: "+boarding.landingRefusal()+" | "+boarding.diagnostic());
        to.cancelIncoming(entity);
        from.approachBoardingEdge(entity,to.position());return false;
    }
    /** A parent at the rim offers a dipped brim to a swimming young; the child bites, is nudged and slides over from its real water position. */
    public boolean rescueIntoHat(HatBoatEntity hat){
        return hat!=null&&!flying()&&rescue.start(hat);
    }
    /** A capable shore child jumps from its actual feet; infants wait for the parent's dipped rim. */
    public boolean boardFromShore(HatBoatEntity hat){
        if(entity.level().isClientSide||hat==null||hat.level()!=entity.level()||flying()||heldByBraid()
                ||!entity.childhood().canJump()||entity.isPassenger()||!entity.onGround()||entity.isInWaterOrBubble()
                ||!entity.life().dryStand(entity.blockPosition())||hat.unsteadyMotion()||!hat.isAfloat()
                ||hat.towed()||hat.flooding()>0||!hat.young(entity)||entity.distanceToSqr(hat)>6.25||!entity.hasLineOfSight(hat))return false;
        var seat=hat.reserveBoardingSeat(entity,entity.position(),!hat.hasSafeRoomFor(entity));if(seat==null)return false;
        if(boarding.begin(null,hat,seat)||boarding.startStep(null,hat,seat,null))return true;
        hat.cancelIncoming(entity);return false;
    }
    /** A trained young may scramble from real brim contact; an untrained infant is nudged over the dipped rim by its parent. */
    public boolean scrambleIntoHat(HatBoatEntity hat){
        if(entity.level().isClientSide||hat==null||flying()||heldByBraid()||entity.isPassenger()||!entity.isInWaterOrBubble()
                ||!entity.childhood().canJump()||hat.unsteadyMotion()||!hat.isAfloat()||hat.flooding()>0
                ||hat.towed()||!hat.young(entity)||!entity.hasLineOfSight(hat))return false;
        var grip=hat.boardingGrip(entity);if(grip==null)return false;
        var seat=hat.reserveBoardingSeat(entity,entity.position(),true);if(seat==null)return false;
        if(boarding.climb(hat,seat,grip))return true;
        hat.cancelIncoming(entity);return false;
    }
    /** The same real near-brim grip is available to a capable owner of a recovered personal hat. */
    public boolean climbOwnHat(HatBoatEntity hat){
        if(entity.level().isClientSide||hat==null||!entity.getUUID().equals(hat.ownerYukkuri())||flight!=null||heldByBraid())return false;
        if(boarding.active())return boarding.climbing()&&boarding.targets(hat);
        var grip=hat.selfRescueGrip(entity);if(grip==null)return false;
        var seat=hat.reserveOwnBoardingSeat(entity,entity.position());if(seat==null)return false;
        if(boarding.climb(hat,seat,grip))return true;
        hat.releaseOwnBoardingSeat(entity);return false;
    }
    private boolean birthPathClear(HatBoatEntity hat,Vec3 from,Vec3 to,int ticks){
        Vec3 previous=from;
        for(int i=1;i<=ticks*4;i++){
            var at=HatVessel.arc(from,to,ticks,i/4.0);
            if(!birthSweepClear(hat,previous,at))return false;
            previous=at;
        }
        return true;
    }
    private void beginFlight(Flight next){
        newbornLaunch=pendingNewbornLaunch;pendingNewbornLaunch=false;
        flight=next;flightTick=0;
        // A single impulse. The subsequent path is velocity integration with real collision,
        // never a series of absolute positions or corrections toward a moving seat.
        entity.setDeltaMovement(HatVessel.arc(next.from(),next.to(),next.ticks(),1).subtract(next.from()));
        entity.getNavigation().stop();entity.hasImpulse=true;
    }
    /** Called from travel while a checked boarding trajectory or gravity-driven launch owns movement. */
    public boolean travelFlight(){
        // Boarding advances in family.tick(), after vanilla aiStep. Running ordinary travel first
        // would apply a second gravity step before its full-body sweep, dropping the feet into the hull.
        // No persistent gravity flag: completion/cancellation immediately returns control to vanilla.
        if(boarding.active())return true;
        if(flight==null)return false;
        var hat=flight.hat()!=null&&level().getEntity(flight.hat()) instanceof HatBoatEntity h&&!h.isRemoved()?h:null;
        if(flight.seat()!=null&&(hat==null||hat.capsizing()||!hat.isAfloat()&&!hat.catches())){
            if(hat!=null)hat.cancelIncoming(entity);
            flightLog("hat_gone_or_capsizing");
            flight=null;return false; // The committed leap falls physically; never restart it in midair.
        }
        var f=flight;var to=f.to();
        // A moving-seat-only flight is released instead of resuming a remote pull.
        // A floating hat bobs and drifts a little while a newborn crosses to it: that is followed (see the contact test), not a reason to give up.
        if(to==null||hat!=null&&hat.toWorld(f.seat()).distanceToSqr(to)>(newbornLaunch?.16:.0025)){
            if(hat!=null)hat.cancelIncoming(entity);flightLog("seat_moved "+(hat==null||f.seat()==null?"":String.valueOf(Math.sqrt(hat.toWorld(f.seat()).distanceToSqr(to)))));flight=null;return false;
        }
        var velocity=entity.getDeltaMovement();var before=entity.position();var wanted=before.add(velocity);
        if(!(newbornLaunch?birthSweepClear(hat,before,wanted):HatBoarding.sweepClear(entity,null,hat,before,wanted))){
            if(hat!=null)hat.cancelIncoming(entity);flightLog("sweep_blocked at tick "+flightTick+" from "+before+" to "+wanted+" because "+sweepWhy);flight=null;
            // A swept body contact stops this step. Returning to vanilla travel here would
            // immediately repeat the rejected move through a non-solid sibling.
            entity.setDeltaMovement(-velocity.x*.15,Math.max(.02,-velocity.y*.15),-velocity.z*.15);
            entity.hasImpulse=true;return true;
        }
        entity.move(net.minecraft.world.entity.MoverType.SELF,velocity);
        entity.getNavigation().stop();entity.hasImpulse=true;flightTick++;
        entity.setDeltaMovement(velocity.add(0,-HatVessel.GRAVITY/16,0));
        if(entity.position().distanceToSqr(wanted)>.000001){
            if(hat!=null)hat.cancelIncoming(entity);flightLog("moved_short at tick "+flightTick+" wanted "+wanted+" got "+entity.position());flight=null;return true;
        }
        if(flightTick<f.ticks())return true;
        flight=null;boolean newborn=newbornLaunch;newbornLaunch=false;
        if(hat!=null) {
            if(hat.unsteadyMotion()){hat.cancelIncoming(entity);entity.setDeltaMovement(0,-.03,0);return true;}
            // Only actual contact with the reserved seat permits boarding. External impulses,
            // collision or a displaced hat can turn a planned catch into a real miss.
            // A newborn leaves a body it overlaps and is pushed aside by it on the way; it counts as home when it is at the (moving) seat.
            boolean contact=newborn?entity.position().distanceToSqr(hat.toWorld(f.seat()))<=.36&&velocity.y<=0
                    :entity.position().distanceToSqr(to)<=.0025&&velocity.y<=0;
            boolean landed=contact&&(entity.getUUID().equals(hat.ownerYukkuri())?hat.board(entity):hat.boardYoung(entity));
            if(!landed)flightLog("not_landed contact="+contact+" dist="+Math.sqrt(entity.position().distanceToSqr(to))+" vy="+velocity.y+" to="+to+" at="+entity.position());
            if(!landed){hat.cancelIncoming(entity);entity.waterDistress().plunge(hat.getUUID(),null);entity.setDeltaMovement(0,-.03,0);}
        }
        return true;
    }
    /** The own parents' hat (set down to catch, or afloat) with room for this young, within reach. */
    private HatBoatEntity nearHat(double reach){
        return level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(reach,2,reach),b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(entity)&&b.hasRoomFor(entity))
                .stream().min(Comparator.comparingDouble(b->b.distanceToSqr(entity))).orElse(null);
    }
    /** A falling stem fruit meets the hat: on the floor it settles there (or bounces to its place), off the brim it
     *  bounces in. */
    private boolean catchFalling(){
        if(flight!=null||entity.getDeltaMovement().y>0)return false;
        double r=HatVessel.radius(entity.getBbWidth());
        for(var hat:level().getEntitiesOfClass(HatBoatEntity.class,entity.getBoundingBox().inflate(3,4,3),b->!b.unsteadyMotion()&&(b.catches()||b.isAfloat())&&b.young(entity)&&b.hasRoomFor(entity))){
            var l=hat.toLocal(entity.position());double h=HatVessel.footprint(hat.mode(),l.x,l.z,r/hat.unit());
            if(Double.isNaN(h)||l.y>h+1e-3||l.y<h-.12/hat.unit())continue;
            var seat=hat.reserve(entity,l);  // where it came down, if free
            if(seat==null)continue;
            var contact=hat.toWorld(new Vec3(l.x,h,l.z));
            if(!HatBoarding.sweepClear(entity,null,hat,entity.position(),contact)){hat.cancelIncoming(entity);continue;}
            entity.move(net.minecraft.world.entity.MoverType.SELF,contact.subtract(entity.position()));
            if(entity.position().distanceToSqr(contact)>.000001){hat.cancelIncoming(entity);continue;}
            entity.setDeltaMovement(Vec3.ZERO);
            if(h<HatVessel.top(hat.mode())-1e-6&&Math.hypot(seat.x-l.x,seat.z-l.z)<=HatVessel.SETTLE)hat.boardYoung(entity);
            else{
                // An actual falling catch may settle only along a short, collision-free path.
                var to=hat.toWorld(seat);int ticks=HatVessel.solve(contact,to,hat,r,HatVessel.HOP_TMIN);
                if(contact.subtract(to).horizontalDistance()<=.5&&birthPathClear(hat,contact,to,ticks)){
                    beginFlight(new Flight(hat.getUUID(),contact,seat,to,ticks));
                }else hat.cancelIncoming(entity);
            }
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
        if(entity.level().isClientSide)return;
        if(!entity.isAlive()){boarding.cancel();rescue.cancel();pickup.cancel();return;}
        pickup.tick();
        if(pickupController()!=null)return;
        cradleHolder=null;
        boarding.tick();
        rescue.tick();
        if(rescuingChild!=null&&rescueController()==null){
            rescuingChild=null;
            if(entity.getVehicle() instanceof HatBoatEntity home)home.releaseRescueOwner(entity);
        }
        var finished=brood();
        if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.STEM&&finished.complete())finishStem(finished);
        else if(finished!=null&&!finished.cancelled&&finished.kind==FamilyData.BirthKind.LIVE&&finished.complete())finishLive();
        if(!entity.subspecies().canReproduce()) {
            // Retire an invalid maternal gestation while retaining born children and ancestry records.
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
        if(!awaitingLanding&&entity.tickCount%GROW_INTERVAL==0&&entity.life().nutrition()>=35)grow(false);
        if(recovery>0)recovery--;if(wait>0)wait--;if(stimulation>0)stimulation--;if(liveRecovery>0)liveRecovery--;
        if(sexDrive>0){sexDrive--;if(sexDrive%200==0)entity.life().spendNutrition(1);}
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        if(cradleLinger>0)cradleLinger--;
        tickHat();
        if(bumpTicks>0)tickBump();
        if(boarding.active())return;
        if(flight!=null)return;
        if(awaitingLanding&&!doomed&&!entity.isPassenger()&&catchFalling())return;
        if(handleLanding(true))return;
        if(birthId!=null)FamilyData.get(level()).landed(entity);
        if(welcomeTicks>0&&newborn!=null)welcome();
        settleCareFood();
        if(entity.tickCount%40==0)retryMotherBurrow();
        if(FamilyConfig.AGING.get()&&age>=lifespan) { entity.hurt(level().damageSources().generic(),entity.getMaxHealth()+100);return; }
        if(entity.isPanicking()||entity.life().hasUrgentNeed()||entity.isCommandPlaying())stop();
        claimMidwife();
        if(task==Task.MIDWIFE)tickMidwife();
        if(comfortTicks>0)tickComfort();
        var brood=brood();
        tickWaterPartner();
        // Labour wakes a sleeping mother (a night pregnancy used to wait until morning, or for ever in a hat).
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind!=FamilyData.BirthKind.EGG&&brood.elapsed>=brood.duration-60&&entity.life().isSleeping()){
            if(boatHome()!=null)entity.life().wakeInHat();else entity.life().interrupt();
        }
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&FLIGHT_DEBUG&&brood.elapsed>=brood.duration&&entity.tickCount%200==0&&boatHome()!=null)
            io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[birth-debug] hold={} {}",birthBlock(),io.github.longshou.mcyukkuri.subspecies.HatRoomProbe.explain(boatHome()));
        // An expecting mother shows it at a glance: a stem on her head or a swelling belly, and a small heart now and then.
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind!=FamilyData.BirthKind.EGG&&entity.tickCount%60==0)
            level().sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,entity.getX(),entity.getY()+entity.getBbHeight()+.15,entity.getZ(),1,.12,.04,.12,0);
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.STEM) advanceStem(brood);
        else if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE)advanceLive(brood);
        else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()&&brood.kind!=FamilyData.BirthKind.STEM))gestation=null;
    }
    /** Pure contact confirmation also runs with AI disabled; optional hat seeking remains an AI behavior. */
    private boolean handleLanding(boolean hopIntoHat) {
        if(!awaitingLanding||flight!=null||!(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof HatBoatEntity))return false;
        awaitingLanding=false;
        if(doomed){
            // Missed the hat. In water the newborn is simply in the river: the ordinary water rules apply (it cannot swim,
            // drowns by immersion unless a parent's braid brings it out). On the ground it falls hard: a real injury.
            doomed=false;
            if(entity.isInWaterOrBubble()){
                BirthStaging.thud(entity);entity.waterDistress().plunge(null,null);
                if(mother!=null&&level().getEntity(mother) instanceof YukkuriEntity parent)BirthStaging.caption(parent,"漏接落水：新生儿掉进水里，无法自救，须亲油及时救起，否则溺亡");
                return true;
            }
            bump();return false;
        }
        if(bumpPending){bumpPending=false;bump();return false;}
        if(hopIntoHat&&!entity.isPassenger()){
            var hat=nearHat(2.5);
            if(hat!=null)launchInto(hat,HatVessel.HOP_TMIN,hat.toLocal(entity.position()));
        }
        // The greeting waits until first_wake has opened the eyes and reached its happy face (24 ticks), not the landing tick.
        entity.showReactionDelayed("happy","newborn",100,24);return false;
    }
    /** Called once when a real sleep completes. Neither observation nor time-of-day commands grow the entity. */
    public boolean woke() {
        if(entity.level().isClientSide||entity.life().nutrition()<35)return false;
        return grow(true);
    }
    /**
     * Applies the nourishment size curve and, when the gates are met, the next stage. A stage is normally entered on waking;
     * a child that has met every gate but never slept (loaded ticks only, so it also runs under /tick rate and sprint) is promoted
     * after {@link #STAGE_OVERDUE} ticks anyway, so growth never waits on a sleep that may not come.
     */
    private boolean grow(boolean slept) {
        Stage next=stage;long threshold=maturity;
        float desired=scale;
        if(stage==Stage.BABY&&age>=threshold/4&&nourishment>=threshold*3/16)next=Stage.JUVENILE;
        if(next==Stage.JUVENILE&&age>threshold&&nourishment>=threshold*.75)next=Stage.ADULT;
        if(next!=stage&&!slept){
            readyTicks+=GROW_INTERVAL;
            if(readyTicks<STAGE_OVERDUE)next=stage;
        }
        // The size standard (GrowthSize): one curve over nourishment, capped by the stage reached, never shrinking.
        desired=Math.max(scale,GrowthSize.scale(next,nourishment,threshold,adultScale,entity.getVariant().isHouse()));
        if(next==stage&&Math.abs(desired-scale)<=.001f)return false;
        var box=LiveBirthGeometry.bodyDimensions(entity,desired*entity.subspecies().formScale(),liveBelly()).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,box))return false;
        boolean changed=next!=stage||Math.abs(desired-scale)>.001f;
        if(next!=stage)readyTicks=0;
        stage=next;scale=desired;
        entity.syncFamily();if(changed&&(slept||next!=Stage.BABY))entity.showReaction("happy","grown",100);return changed;
    }
    private static final int GROW_INTERVAL=100, STAGE_OVERDUE=3000;
    private int readyTicks;
    /** Observable growth: 0..1 from birth to a full adult, limited by whichever of age and nourishment is further behind. */
    public float growthProgress() {
        if(stage==Stage.ADULT)return 1;
        long m=Math.max(1,maturity);
        return Math.clamp(Math.min(age/(float)(m+1),nourishment/(m*.75f)),0f,1f);
    }
    /** Loaded ticks left until the next stage if nourishment keeps accruing every tick; 0 for an adult. Slower when hungry or hurt. */
    public long stageRemainingTicks() {
        long m=maturity;
        return switch(stage){
            case BABY -> Math.max(0,Math.max(m/4-age,m*3/16-nourishment));
            case JUVENILE -> Math.max(0,Math.max(m+1-age,(long)Math.ceil(m*.75)-nourishment));
            case ADULT -> 0;
        };
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
    public void conceived(UUID brood,UUID other) { gestation=brood;partner=other;conceptions++;recovery=FamilyConfig.RECOVERY.get();deliveryYaw=Float.NaN;catcherWait=deliveryOutcome=0;entity.setPersistenceRequired(); }
    /** Manufactured filling has no identified father and does not create or replace a partner. */
    public void assisted(UUID brood) {
        if(gestation!=null)return;
        gestation=brood;conceptions++;recovery=FamilyConfig.RECOVERY.get();spentStemCount=0;deliveryYaw=Float.NaN;catcherWait=0;
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
                &&nestStand()!=null;
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
    public boolean ready() { return ready(true); }
    public boolean ready(boolean needHome) {
        return adult()&&!sterile()&&!elder()&&recovery==0&&entity.isAlive()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()&&!entity.predator().active()
                &&!entity.life().hasUrgentNeed()&&entity.life().nutrition()>=65&&entity.life().energy()>=50&&entity.life().hygiene()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.8&&entity.body().wound()==0&&entity.body().infection()==0&&entity.life().mealTicks()==0&&(!needHome||nestStand()!=null||boatHome()!=null);
    }
    public void arrange(UUID other){arranged=other;}
    /** Shop service check: can the two mate if brought together, ignoring distance, homes, recognition and affinity. */
    public static boolean arrangedMatch(YukkuriEntity a,YukkuriEntity b) {
        a.family().arranged=b.getUUID();b.family().arranged=a.getUUID();
        try { return a.family().canReproduceWith(b)&&b.family().canReproduceWith(a); }
        finally { a.family().arranged=null;b.family().arranged=null; }
    }
    /** Long-lived compatibility for breeding permits: species rules and fertility, not momentary condition. */
    public boolean compatibleWith(YukkuriEntity other) {
        if(other==entity||entity.level()!=other.level()||!adult()||!other.family().adult()||sterile()||other.family().sterile()||elder()||other.family().elder()
                ||entity.getVariant().isHouse()!=other.getVariant().isHouse()||entity.getVariant().isPredator()!=other.getVariant().isPredator()||HatVessel.aquatic(entity)!=HatVessel.aquatic(other))return false;
        var aquatic=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA;
        return (entity.subspecies().kind()==aquatic)==(other.subspecies().kind()==aquatic)&&!FamilyData.get(level()).related(entity.getUUID(),other.getUUID());
    }
    /** Rolled once per courtship attempt for owned pets; the unauthorized partner is remembered so that conception can count it. */
    private boolean allowCourt(YukkuriEntity other) {
        int gate=Math.max(entity.pet().courtshipGate(other),other.pet().courtshipGate(entity));
        if(gate==2){wait=600;io.github.longshou.mcyukkuri.pet.PetScenes.courtshipHeld(entity,other);return false;}
        unauthorizedWith=gate==1?other.getUUID():null;return true;
    }
    public boolean canReproduceWith(YukkuriEntity other) {
        boolean arr=other.getUUID().equals(arranged)||entity.getUUID().equals(other.family().arranged);
        if(other==entity||!arr&&(entity.dailyLife().active()||other.dailyLife().active())||entity.level()!=other.level()||!ready(!arr)||!other.family().ready(!arr)||!arr&&entity.distanceToSqr(other)>9
                ||entity.getVariant().isHouse()!=other.getVariant().isHouse()||entity.getVariant().isPredator()!=other.getVariant().isPredator()||!arr&&(!entity.life().canRecognize(other)||!other.life().canRecognize(entity)))return false;
        if(partner!=null&&!partner.equals(other.getUUID())||other.family().partner!=null&&!other.family().partner.equals(entity.getUUID()))return false;
        // The aquatic kind only pairs within itself: aquatic couples are two aquatic Marisa.
        var aquatic=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA;
        if((entity.subspecies().kind()==aquatic)!=(other.subspecies().kind()==aquatic))return false;
        var data=FamilyData.get(level());var relations=YukkuriWorldData.get(level());
        if(data.related(entity.getUUID(),other.getUUID())||!arr&&(relations.affinity(entity.getUUID(),other.getUUID())<12||relations.affinity(other.getUUID(),entity.getUUID())<12))return false;
        if(arr)return true;
        // Afloat in her own hat boat the boat is the nest: neither a shore nor a claimed nest is needed.
        var a=nestStand();var b=other.family().nestStand();
        if(a==null&&boatHome()!=null)a=entity.blockPosition();
        if(b==null&&other.family().boatHome()!=null)b=other.blockPosition();
        return a!=null&&b!=null&&a.distSqr(b)<=64&&(boatHome()!=null||other.family().boatHome()!=null||entity.life().reachable(other.blockPosition(),false));
    }
    private void advanceStem(FamilyData.Brood brood) {
        if(entity.operations().active())return;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()||entity.life().nutrition()<30||entity.body().wound()>0||entity.body().infection()>0)return;
        var boat=boatHome();
        if(!placeForTerm(brood,boat))return;
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
    /** Rehearse a real pregnancy through the normal server lifecycle, with its original child identities. */
    public boolean prepareBirthScene(FamilyData.Brood pregnancy,String outcome){
        if(entity.level().isClientSide||pregnancy==null||brood()!=pregnancy||pregnancy.cancelled||pregnancy.complete()
                ||pregnancy.kind!=FamilyData.BirthKind.LIVE||pregnancy.elapsed>pregnancy.duration
                ||pregnancy.children.stream().anyMatch(child->child.born)||!java.util.Set.of("natural","caught","missed","overstrain").contains(outcome))return false;
        deliveryOutcome=outcome.equals("caught")?1:outcome.equals("overstrain")?2:outcome.equals("missed")?3:0;
        // A father who is to catch notices early enough to walk over, take off his hat and set it down before the labour starts.
        int lead=2;
        if(deliveryOutcome==1||deliveryOutcome==2){var helper=catcher(entity,pregnancy);if(helper!=null&&helper!=entity)lead=150;}
        dutyTicks=0;dutyGaveUp=false;
        pregnancy.elapsed=Math.max(0,pregnancy.duration-lead);deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=0;
        FamilyData.get(level()).setDirty();return true;
    }
    /** Overstrain, a demonstrated miss, or the natural 12%: the newborn goes past the hat (into the water afloat, onto the ground ashore). */
    public boolean missesDelivery(boolean naturalMiss){return deliveryOutcome==2||deliveryOutcome==3||deliveryOutcome==0&&naturalMiss;}
    /** A scene outcome is in force for this delivery (caught, overstrain or missed). */
    public boolean sceneRequested(){return deliveryOutcome!=0;}
    /** The demonstrated miss: no hat is set down for this delivery. */
    public boolean withholdsCushion(){return deliveryOutcome==3;}
    /** A Marisa was due to catch this brood and no hat is down at the moment of birth: a miss, not a gentle drop. */
    public boolean catchExpected(FamilyData.Brood brood){return catcher(entity,brood)!=null;}
    /** This newborn came out onto bare ground in a demonstrated miss: it will hit the floor, be hurt and cry. */
    public void bumpOnLanding(){bumpPending=true;}
    public boolean bumped(){return bumped||bumpPending;}
    /** Birth duties that come before first-stool licking and mash feeding: the hat is still down or being taken back,
     *  a hurt newborn is being comforted, or the mother is still recovering. */
    public boolean birthDuty(){return keepsCradle()||hatMove!=HatMove.NONE||fetching!=null||comfortTicks>0||liveRecovery>0;}
    private boolean supported(){
        if(boatHome()!=null)return true;
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    private static final int NEST_GRACE=400, NEST_PATIENCE=100;
    /**
     * Water birth with a partner: his own hat boat comes alongside hers for the last stretch and the labour (he needs no shore), so that a share
     * of the newborns can be sent across into his hat (FamilyData.waterCradle). Without him, or when he cannot come, she gives birth alone in her hat.
     */
    private void tickWaterPartner(){
        if(entity.tickCount%20!=7||partner==null)return;
        var mine=boatHome();if(mine==null||mine.towed())return;
        if(!(level().getEntity(partner) instanceof YukkuriEntity mate)||!mate.isAlive()||mate==entity)return;
        var hers=mate.family().boatHome();var pregnancy=mate.family().brood();
        if(hers==null||hers==mine||pregnancy==null||pregnancy.cancelled||pregnancy.complete()||pregnancy.kind!=FamilyData.BirthKind.LIVE)return;
        if(pregnancy.elapsed<pregnancy.duration-NEST_GRACE||laboring()||entity.isPanicking()||entity.life().hasUrgentNeed()||entity.distanceToSqr(mate)>576)return;
        var forward=Vec3.directionFromRotation(0,mate.yBodyRot).multiply(1,0,1).normalize();
        var right=new Vec3(-forward.z,0,forward.x);
        double sign=mine.position().subtract(hers.position()).dot(right)>=0?1:-1;
        var station=hers.position().add(forward.scale(.5)).add(right.scale(sign*STATION_GAP));
        if(mine.position().subtract(station).multiply(1,0,1).lengthSqr()>.09)mine.paddleTo(station);
        else mine.holdPosition();
    }
    private static final double STATION_GAP=2.1;
    /**
     * Where the gestation may advance. The clock runs wherever a healthy mother is until the last {@link #NEST_GRACE} ticks (it used to run only
     * beside a claimed nest, so a mother whose nest was lost or far never progressed and neither grew a belly or stem nor gave birth); in the
     * last stretch she is at the nest, afloat in her own hat boat (no partner and no shore needed), at a bank, or, with no nest in reach, on
     * firm ground after a short wait. {@link #nestStand()} is the single place where the family code asks for her nest.
     */
    private boolean placeForTerm(FamilyData.Brood brood,HatBoatEntity boat){
        var data=FamilyData.get(level());
        if(boat!=null)return data.relocate(brood,level(),boat.blockPosition());
        if(brood.elapsed<brood.duration-NEST_GRACE)return true;
        var home=nestStand();
        boolean away=home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        if(away&&supported())nestless++;else if(!away)nestless=0;
        boolean bank=away&&(bankNest()||nestless>=NEST_PATIENCE);
        return !(away&&!bank)&&data.relocate(brood,level(),bank?entity.blockPosition():home);
    }
    /**
     * The nest this family member brings its young to (stand position beside it), or null. Interface point for the household/nest model
     * ("family big nest", wild/home agent): swap the body of this method, not its callers.
     */
    public BlockPos nestStand(){return entity.life().homeStand();}
    /** Where a new brood is anchored for the population budget: her nest, else the boat she floats in, else where she stands. */
    public BlockPos birthAnchor(){var nest=nestStand();var boat=boatHome();return nest!=null?nest:boat!=null?boat.blockPosition():entity.blockPosition();}
    private void advanceLive(FamilyData.Brood brood){
        Vec3 before=deliveryLast;deliveryLast=entity.position();
        hold="";
        if(!readyForDelivery()){hold="mother_not_ready";return;}
        var boat=boatHome();
        if(!placeForTerm(brood,boat)){hold="no_place_for_term";return;}
        // On land the mother must hold still; afloat, drifting with the water does not stop a delivery.
        if(boat==null&&brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004)){hold="mother_moving";return;}
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(boat==null&&brood.elapsed>=brood.duration-2&&catchAlone(brood)){hold="waiting_for_hat";return;}
        if(hatMove!=HatMove.NONE){hold="hat_move";return;}
        if(brood.elapsed>=brood.duration){
            entity.getNavigation().stop();
            if(Float.isNaN(deliveryYaw))deliveryYaw=entity.yBodyRot;
            face(deliveryYaw);
            // Let the real father finish walking over and placing his hat before the head emerges.
            // Bounded and saved: an unreachable helper cannot hold a pregnancy forever or restart the wait on reload.
            var helper=catcher(entity,brood);
            if(boat==null&&brood.elapsed<brood.duration+LiveBirthGeometry.emergenceStart(0)
                    &&catcherWait<160&&deliveryOutcome!=3&&helper!=null&&helper!=entity&&!helper.family().dutyGaveUp
                    &&helper.family().cradleEntity()==null&&!helper.isPanicking()&&!helper.life().hasUrgentNeed()){
                catcherWait++;hold="waiting_for_catcher";return;
            }
            if(!LiveBirthGeometry.clear(entity,brood.children.get(next),next)){hold="outlet_blocked";return;}
        }
        float nextBelly=LiveBirthGeometry.belly(Math.min(1,(brood.elapsed+1f)/brood.duration),brood.children.size()-brood.unborn(),brood.children.size());
        var room=LiveBirthGeometry.bodyDimensions(entity,entity.growthScale(),nextBelly).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,room)){hold="no_room_for_belly";return;}
        // Failed entity insertion pauses at this child's release, never running into the next child's clock.
        if(brood.elapsed<brood.duration+LiveBirthGeometry.birthTick(next)){
            brood.elapsed++;if(brood.elapsed%200==0)entity.life().spendNutrition(1);FamilyData.get(level()).setDirty();
        }
        if(brood.elapsed>=brood.duration){
            int delivery=brood.elapsed-brood.duration;
            labourBeats(delivery);
            if(brood.elapsed>=brood.duration+LiveBirthGeometry.birthTick(next)){
                var child=FamilyData.get(level()).birth(brood,next,level(),LiveBirthGeometry.position(entity,next));
                if(child!=null){
                    // A soft physical release lets the landed sibling clear the next child's outlet; a small visible pop (project original).
                    if(boat==null&&!child.isPassenger()&&!child.family().doomed()&&!child.family().flying())child.setDeltaMovement(new Vec3(0,.26,-.12).yRot((float)Math.toRadians(180-entity.yBodyRot)).scale(entity.getScale()));
                    BirthStaging.emerge(entity,child.position());
                    newborn=child.getUUID();welcomeTicks=WELCOME;
                    // Later children come easier but leave her more tired.
                    entity.showReaction(next>0?"sleepy":"closed_smile","birth",LiveBirthGeometry.REST);
                    if(brood.father!=null&&level().getEntity(brood.father) instanceof YukkuriEntity helper&&helper.family().midwifeTarget()==entity)
                        helper.showReaction("happy","caregiver",LiveBirthGeometry.REST);
                }
            }
        }
        if(brood.complete())finishLive();
    }
    /** Guards a parent must pass to take a duty at the birth: the same ones the family goal applies. */
    private boolean dutyReady(){
        return adult()&&entity.isAlive()&&!entity.isNoAi()&&!entity.isPassenger()&&!entity.isPanicking()&&!entity.isCommandPlaying()
                &&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!awaitingLanding&&flight==null&&!heldByBraid();
    }
    /** The father notices the labour on the server clock and takes the duty himself, so no goal arbitration leaves him idle. */
    private void claimMidwife(){
        if(partner==null||task!=Task.NONE||comfortTicks>0||!dutyReady())return;
        var mate=mateInLabor();if(mate==null)return;
        if(entity.dailyLife().active())entity.dailyLife().stop();
        if(entity.playLife().active())entity.playLife().stop();
        task=Task.MIDWIFE;target=mate;taskTicks=0;dutyTicks=0;dutyGaveUp=false;
        entity.getNavigation().stop();
    }
    /** Father's beats: notice the labour, walk to the mother's front outlet, take his hat off (a real accessory change), set it
     *  upturned before the outlet, hold it while she pushes. Everything afterwards is the existing hat flow: the newborn lands
     *  in it, the family greets, the braid lifts the young out and the hat goes back on (tickHat). If he cannot get there in time
     *  or the hat cannot be set down, the delivery goes on without a cushion. Project original staging. */
    private void tickMidwife(){
        var mate=target;var b=mate==null?null:mate.family().brood();
        if(mate==null||!mate.isAlive()||b==null||b.cancelled||b.complete()||mate.family().boatHome()!=null){
            if(cradleEntity()!=null)cradleLinger=120;
            dutyTicks=0;dutyGaveUp=false;stop();return;
        }
        if(hatMove!=HatMove.NONE)return;
        if(dutyTicks++==0){
            entity.showReaction("surprise","caregiver",50);
            BirthStaging.caption(entity,"父油察觉母油临产，走向她面前的出口");
        }
        var hat=cradleEntity();
        if(hat!=null){
            // Holding the set-down hat's rim with the braid from the place the clip is authored from.
            var stand=stand(poseOf(hat),entity);
            if(!approachHatStand(stand))return;
            entity.getNavigation().stop();
            face(stand.yaw());return;
        }
        boolean mayCatch=!dutyGaveUp&&catcher(mate,b)==entity;
        if(mayCatch&&dutyTicks>=300){
            dutyGaveUp=true;mayCatch=false;
            BirthStaging.caption(entity,"父油没能及时赶到出口前，这一胎只能无垫分娩");
        }
        var pose=mayCatch?catchPose(mate,b,entity,HatVessel.Role.HELPER):null;
        if(pose!=null&&settles(pose)){
            // Walk to the place beside the outlet; the braid takes the hat off and sets it down there.
            beginHat(HatMove.OFF,pose,stand(pose,entity));
            return;
        }
        // Without a hat to set (a shell Marisa, or no ground or water before the outlet) he cheers beside it.
        var spot=midwifeSpot(mate);var outlet=LiveBirthGeometry.position(mate,0);
        if(entity.distanceToSqr(spot)>.3) { if(dutyTicks%10==1)entity.life().navigate(BlockPos.containing(spot),.9,false); }
        else entity.getNavigation().stop();
        entity.getLookControl().setLookAt(outlet.x,outlet.y+.1,outlet.z,20,20);
    }
    /** A parent hurries to a newborn that fell hard, nuzzles it and licks it (project original; nuzzling is Wiki E06). */
    public void comfort(YukkuriEntity baby){
        if(entity.level().isClientSide||baby==null||baby==entity||!adult()||entity.isNoAi()||!entity.isAlive())return;
        comfortBaby=baby.getUUID();comfortTicks=COMFORT;comfortNear=0;
        entity.getNavigation().stop();
        entity.showReaction("surprise","afraid",40);
        BirthStaging.caption(entity,"父油大惊，赶到哭泣的新生儿身边");
    }
    private void tickComfort(){
        if(task==Task.MIDWIFE)return;
        comfortTicks--;
        var baby=comfortBaby!=null&&level().getEntity(comfortBaby) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null||!dutyReady()||hatMove!=HatMove.NONE){comfortTicks=0;comfortNear=0;return;}
        double near=(entity.getBbWidth()+baby.getBbWidth())*.5+.12;
        var away=entity.position().subtract(baby.position()).multiply(1,0,1);
        double gap=away.length();
        var side=gap>1.0e-3?away.scale(1/gap):new Vec3(0,0,1);
        if(gap>near+.2){
            comfortNear=0;
            entity.getLookControl().setLookAt(baby,20,20);
            io.github.longshou.mcyukkuri.life.SurfaceApproach.walk(entity,baby.position().add(side.scale(near)),1.1);
            return;
        }
        io.github.longshou.mcyukkuri.life.SurfaceApproach.halt(entity);
        face((float)(Mth.atan2(baby.getZ()-entity.getZ(),baby.getX()-entity.getX())*Mth.RAD_TO_DEG)-90);
        comfortNear++;
        if(comfortNear==10){entity.showReaction("closed_smile","nuzzle",60);BirthStaging.affection(entity);BirthStaging.caption(entity,"蹭脸安抚");}
        if(comfortNear==70){entity.showReaction("open_smile","",40);BirthStaging.affection(entity);BirthStaging.caption(entity,"轻轻舔舐安抚");}
        if(comfortNear>=130){comfortTicks=0;comfortNear=0;}
    }
    /** The uncushioned fall (project original): a thud, a small bounce, a real wound and a lasting cry. The wound goes through
     *  the body's ordinary injury record, so it heals, bandages or festers by the existing rules; it is not fatal. */
    private void bump(){
        bumped=true;bumpTicks=140;
        BirthStaging.thud(entity);BirthStaging.whimper(entity);
        var motion=entity.getDeltaMovement();entity.setDeltaMovement(motion.x,.2,motion.z);entity.hasImpulse=true;
        // A real fall injury through the ordinary damage path (wound record, infection and care rules), never lethal.
        float lost=Math.min(2f,entity.getHealth()-1f);
        if(lost>0&&!entity.hurt(level().damageSources().fall(),lost))entity.body().injured(level().damageSources().fall(),lost);
        entity.showReaction("cry","wounded",140);
        // The newborn carries no exhibit tag; the report goes out under its mother.
        if(mother!=null&&level().getEntity(mother) instanceof YukkuriEntity parent)
            BirthStaging.caption(parent,"漏接：新生儿重重摔在地上，弹了一下，受了摔伤哭了起来（进入伤势系统，不致命，可照料）");
    }
    private void tickBump(){
        bumpTicks--;
        if(bumpTicks%8==0)BirthStaging.tears(entity);
        if(bumpTicks==100)BirthStaging.whimper(entity);
    }
    /** Delivery-tick beats on the server clock: labour signs in the lead-in, the calls and soft sounds at the pushes.
     *  Each fires at one tick value, so a reload or a paused delivery cannot repeat it. Project-original staging. */
    private void labourBeats(int delivery){
        int prep=LiveBirthGeometry.PREPARATION;
        if(delivery<prep){
            // Restless and nervous: a worried face, beads of sweat, then she settles into place.
            if(delivery==1)entity.showReaction("teary","",34);
            if(delivery%30==12)BirthStaging.sweat(entity);
            if(delivery==prep/2)entity.showReaction("surprise","",30);
            if(delivery==prep-4)BirthStaging.settle(entity);
            return;
        }
        int index=LiveBirthGeometry.cycleIndex(delivery),strain=LiveBirthGeometry.strain(index),cycle=(int)LiveBirthGeometry.cycleTick(delivery);
        // Calls of birth at the start of the strain and again at the second push.
        if(cycle==0||cycle==strain+36)entity.showReaction("chewing_closed","birth",28);
        if(cycle==strain+8)BirthStaging.push(entity,.9f);
        if(cycle==strain+46)BirthStaging.push(entity,.8f);
        if(cycle==strain+LiveBirthGeometry.EMERGENCE+10&&index>0)BirthStaging.sweat(entity);
    }
    /** After a birth the father and the older siblings nearby turn to the newborn, hop and greet it. */
    private void welcome(){
        welcomeTicks--;
        if(!(level().getEntity(newborn) instanceof YukkuriEntity baby)||!baby.isAlive()){welcomeTicks=0;return;}
        var audience=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(6),other->other!=entity&&other!=baby&&other.isAlive()
                &&!other.isNoAi()&&!other.isPanicking()&&!other.life().isSleeping()&&!other.family().awaitingLanding()
                &&(other.getUUID().equals(partner)||entity.getUUID().equals(other.family().mother())));
        boolean hurt=baby.family().bumped();
        if(welcomeTicks==WELCOME-20&&!hurt)BirthStaging.caption(entity,baby.getVehicle() instanceof HatBoatEntity?"新生儿落进帽中，被稳稳接住":"新生儿落在地面（这一胎没有放帽）");
        // The father hurries to a newborn that fell hard, wherever he is standing.
        if(hurt&&welcomeTicks==WELCOME-6&&partner!=null&&level().getEntity(partner) instanceof YukkuriEntity mate&&mate.isAlive()&&mate.distanceToSqr(baby)<256)mate.family().comfort(baby);
        int shown=0;
        for(var other:audience){
            if(shown++>=5)break;
            other.getLookControl().setLookAt(baby,20,20);
            if(welcomeTicks==WELCOME-30&&other.onGround()&&!other.isPassenger()&&!other.family().flying()){
                if(hurt){other.showReaction("surprise","afraid",50);BirthStaging.sweat(other);continue;}
                // A small hop and a greeting for the newcomer.
                other.setDeltaMovement(other.getDeltaMovement().add(0,.2,0));
                other.showReaction("happy","caregiver",40);BirthStaging.affection(other);
            }
        }
    }
    /** The mother's recovery after the last birth: she turns to the newborn, nuzzles it and licks it once, then goes on
     *  to feed it (the existing mash feeding). Nuzzling is supported by the wiki (E06); the rest is project original. */
    private void recoveryBeats(){
        int age=LiveBirthGeometry.RECOVERY-liveRecovery;
        YukkuriEntity baby=newborn!=null&&level().getEntity(newborn) instanceof YukkuriEntity found&&found.isAlive()?found:null;
        if(baby==null)baby=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(5),other->other!=entity&&other.isAlive()
                &&entity.getUUID().equals(other.family().mother())&&!other.family().adult()).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(baby==null)return;
        if(age>=24&&age<=150){
            entity.getLookControl().setLookAt(baby,20,20);
            // Turn the body toward the baby a few degrees a tick; the clip adds its own head turn on top.
            float yaw=(float)(Mth.atan2(baby.getZ()-entity.getZ(),baby.getX()-entity.getX())*Mth.RAD_TO_DEG)-90;
            if(age>=40)face(Mth.approachDegrees(entity.getYRot(),yaw,5));
        }
        if(age==24&&baby.family().bumped()){
            entity.showReaction("surprise","afraid",50);BirthStaging.sweat(entity);BirthStaging.caption(entity,"母油见孩子摔伤，惊慌地转向它");
        }
        if(age==88){
            entity.showReaction("happy","nuzzle",60);BirthStaging.affection(entity);
            if(baby.family().bumped())BirthStaging.caption(entity,"母油蹭脸安抚摔伤的孩子，随后轻舔");
        }
        if(age==116){entity.showReaction("open_smile","",30);BirthStaging.affection(entity);}
    }
    private void finishLive(){
        if(cradleEntity()!=null)cradleLinger=LiveBirthGeometry.RECOVERY;
        gestation=null;liveRecovery=LiveBirthGeometry.RECOVERY;
        recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());deliveryLast=null;deliveryYaw=Float.NaN;catcherWait=nestless=0;deliveryOutcome=0;
        dutyTicks=0;dutyGaveUp=false;
        stop();entity.getNavigation().stop();task=Task.RECOVER;wait=0;
    }
    public boolean canUse() {
        if(pickupController()!=null)return false;
        if(entity.dailyLife().active())return false;
        if(entity.isPanicking()||entity.isCommandPlaying()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||awaitingLanding)return false;
        if(task!=Task.NONE)return true;
        if(liveRecovery>0){task=Task.RECOVER;taskTicks=0;return true;}
        if(laboring()){task=Task.BROOD;taskTicks=0;return true;}
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
            if(target!=null&&!allowCourt(target)){target=null;return false;}
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
        // A demonstrated miss: he has not noticed in time, so he is not on duty; he only reacts once the newborn has dropped.
        if(mate.family().deliveryOutcome==3)return null;
        return b.elapsed>=b.duration-(b.kind==FamilyData.BirthKind.LIVE?160:300)?mate:null;
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
                &&child.life().nutrition()<entity.life().nutrition()-15&&entity.careApproach().mayTry(child)&&entity.life().reachable(child.blockPosition(),false)
                // First the licking that brings the first stool (EH08 p.8), only then the mash; hunger below 30 still comes first.
                &&!(child.childhood().needsAssistedFirstToilet()&&child.life().nutrition()>=30);
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
        task=Task.FEED_CHILD;taskTicks=0;entity.showReaction("closed_smile","caregiver",40);
        BirthStaging.caption(entity,"首便之后：亲油取一份粮，嚼成糜再喂赤油");return true;
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
        var pile=shelter==null?null:shelter.pile();
        boolean stocked=shelter!=null&&shelter.usable(entity)&&target!=null&&shelter.hasFoodFor(entity,target)&&pile!=null&&entity.life().reachable(pile.standCell(),false);
        if(nearby!=null&&(!stocked||entity.distanceToSqr(nearby)<=entity.distanceToSqr(pile.standPoint(entity))))foodSource=nearby;
        else if(stocked){foodStore=shelter.getBlockPos();if(storeContact==null)storeContact=new io.github.longshou.mcyukkuri.settlement.PileContact(entity);storeContact.reset();}
    }
    private boolean validCareTarget() { return target!=null&&hungryOwnChild(target); }
    /** A cancelled handoff does not mint or erase food: return the held portion or leave it where the parent stands. */
    private void settleCareFood() {
        if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        if(task==Task.FEED_CHILD){careCarryTicks=0;return;}
        if(++careCarryTicks<200)return;
        var home=entity.habitat().shelter();var heap=home==null?null:home.pile();
        if(home!=null&&heap!=null&&home.usable(entity)&&entity.distanceToSqr(heap.standPoint(entity))<.64) {
            carrying=home.returnFood(carrying);
            if(carrying.isEmpty()){careCarryTicks=careChewTicks=careOfferTicks=0;return;}
        }
        if(entity.life().dryStand(entity.blockPosition())) {
            var food=entity.spawnAtLocation(carrying.copy());
            if(food!=null){food.setPickUpDelay(40);carrying=ItemStack.EMPTY;careCarryTicks=careChewTicks=careOfferTicks=0;}
        }
    }
    private void tickCare() {
        if(careFinishTicks>0) {
            entity.careApproach().hold();
            if(++careFinishTicks>=72 || target==null || !target.isAlive())stop();
            return;
        }
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
                var pile=home==null?null:home.pile();
                if(home==null||pile==null||!home.usable(entity)||!home.hasFoodFor(entity,target)) { foodStore=null;return; }
                if(storeContact==null)storeContact=new io.github.longshou.mcyukkuri.settlement.PileContact(entity);
                if(!storeContact.near(pile)) {
                    storeContact.reset();
                    if(taskTicks%10==1&&!entity.life().navigate(pile.standCell(),.9,false))foodStore=null;
                    return;
                }
                if(storeContact.tick(pile)) {
                    var food=home.takeFood(entity,target);
                    if(!food.isEmpty()&&target.life().canReceiveCareFood(food)){carrying=food;careChewTicks=careOfferTicks=0;}
                    else if(!food.isEmpty()){carrying=home.returnFood(food);foodStore=null;}
                    else foodStore=null;
                }
                return;
            }
            stop();return;
        }
        if(target.family().needsMash()&&!InfantFood.isMash(carrying)) {
            entity.getNavigation().stop();
            if(++careChewTicks >= 40) { carrying=InfantFood.mash(carrying,entity.getUUID());careOfferTicks=0; }
            return;
        }
        var approach=entity.careApproach().tick(target,CareApproach.Kind.FEED);
        if(approach==CareApproach.Result.FAILED){stop();return;}
        if(approach==CareApproach.Result.CONTACT) {
            entity.getNavigation().stop();
            if(++careOfferTicks<FamilyFeedingContact.REQUIRED_CONTACT_TICKS)return;
            boolean handed=target.family().needsMash() ? target.life().beginParentMeal(entity,carrying) : target.life().beginMeal(carrying,null,true);
            if(handed) { carrying=ItemStack.EMPTY;careChewTicks=careOfferTicks=0;careFinishTicks=1;entity.showReaction("happy","share",80); }
            else stop();return;
        }
        careOfferTicks=0;
    }
    public boolean canContinue() {
        if(pickupController()!=null)return false;
        if(task==Task.NONE||entity.isPanicking()||entity.life().hasUrgentNeed()||entity.life().mealTicks()>0||entity.isCommandPlaying())return false;
        if(task==Task.RECOVER)return liveRecovery>0;
        if(task==Task.STAY_HOME) {
            var home=earlyHomeStand();return home!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4;
        }
        if(task==Task.FEED_CHILD)return careReady()&&(careFinishTicks>0&&target!=null&&target.isAlive()||validCareTarget())&&taskTicks<600;
        return (taskTicks<240||task==Task.MIDWIFE||task==Task.BROOD&&laboring())
                &&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        switch(task) {
            case RECOVER -> { entity.getNavigation().stop();recoveryBeats(); }
            case BROOD -> {
                if(boatHome()!=null||laboring()){entity.getNavigation().stop();return;}
                BlockPos home=nestStand();
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
                    var brood=FamilyData.get(level()).conceive(mother,father,mother.family().naturalBirthKind(father),GlobalPos.of(level().dimension(),mother.family().birthAnchor()));
                    if(brood!=null) { partner=target.getUUID();target.family().partner=entity.getUUID();mother.showReaction("happy","expecting",100);
                        if(target.getUUID().equals(unauthorizedWith))io.github.longshou.mcyukkuri.pet.PetLife.unauthorizedConception(mother,father); }
                    unauthorizedWith=null;
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
            // The midwife's duty runs from tick() (tickMidwife), so goal arbitration cannot leave the father idle.
            case MIDWIFE -> { }
            case SEARCH -> {
                if(searchAt==null||entity.distanceToSqr(searchAt)<2){stop();return;}
                if(taskTicks%20==1&&!entity.life().navigate(BlockPos.containing(searchAt),.9,false))stop();
            }
            default -> { }
        }
    }
    public void stop() {
        boolean recovered=task==Task.RECOVER&&liveRecovery<=0;
        hatApproach=0;
        if(task==Task.FEED_CHILD)entity.careApproach().cancel();task=Task.NONE;target=null;searchAt=null;foodSource=null;foodStore=null;taskTicks=0;careOfferTicks=careFinishTicks=0;
        // Recovered: no idle pause before the existing mash feeding picks up the hungriest child.
        wait=recovered?0:Math.max(wait,40);
    }
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
        tag.putBoolean("BirthMiss",doomed);
        tag.put("BraidRescue",rescue.save());
        tag.put("HatBoarding",boarding.save());
        tag.putInt("LiveRecovery",liveRecovery);
        if(!Float.isNaN(deliveryYaw))tag.putFloat("DeliveryYaw",deliveryYaw);
        tag.putInt("CatcherWait",catcherWait);
        tag.putInt("DeliveryOutcome",deliveryOutcome);
        if (flight != null) {
            var f = new CompoundTag(); if (flight.hat()!=null) f.putUUID("Hat",flight.hat());
            putVector(f,"From",flight.from()); putVector(f,"Seat",flight.seat()); putVector(f,"To",flight.to());
            f.putBoolean("Physical",true);f.putInt("Ticks",flight.ticks()); f.putInt("Elapsed",flightTick); tag.put("HatFlight",f);
        }
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
        if(!carrying.isEmpty())tag.put("CareFood",carrying.save(entity.registryAccess()));
        tag.putInt("CareFoodTicks",careCarryTicks);
        tag.putInt("CareChewTicks",careChewTicks);tag.putInt("CareOfferTicks",careOfferTicks);
        if(mother!=null)tag.putUUID("Mother",mother);if(father!=null)tag.putUUID("Father",father);if(birthId!=null)tag.putUUID("BirthId",birthId);
        if(gestation!=null)tag.putUUID("Gestation",gestation);if(partner!=null)tag.putUUID("Partner",partner);return tag;
    }
    public void load(CompoundTag tag) {
        pickup.cancel();cradleHolder=null;
        boarding.cancel();
        try { stage=Stage.valueOf(tag.getString("Stage")); }catch(IllegalArgumentException ignored){stage=Stage.ADULT;}
        lifespan=Math.max(600,tag.getLong("Lifespan"));age=Math.max(0,tag.getLong("Age"));nourishment=Math.max(0,tag.getLong("Nourishment"));
        maturity=Math.clamp(tag.getLong("Maturity"),1,Math.max(1,lifespan-1));
        adultScale=Math.clamp(tag.getFloat("AdultScale"),.8f,1.2f);scale=Math.clamp(tag.getFloat("Scale"),.1f,1.2f);
        conceptions=Math.max(0,tag.getInt("Conceptions"));recovery=Math.max(0,tag.getInt("Recovery"));initialized=tag.getBoolean("Initialized");
        awaitingLanding=tag.getBoolean("AwaitingLanding");juvenileVoice=tag.getBoolean("JuvenileVoice");
        sterile=tag.getBoolean("Sterile");stimulation=Math.clamp(tag.getInt("Stimulation"),0,1200);
        babyTalk=tag.getBoolean("BabyTalk");sexDrive=Math.clamp(tag.getInt("SexDrive"),0,1200);
        doomed=tag.getBoolean("BirthMiss")&&awaitingLanding;
        liveRecovery=Math.clamp(tag.getInt("LiveRecovery"),0,LiveBirthGeometry.RECOVERY);deliveryLast=null;
        deliveryYaw=tag.contains("DeliveryYaw")?tag.getFloat("DeliveryYaw"):Float.NaN;
        catcherWait=Math.clamp(tag.getInt("CatcherWait"),0,160);
        deliveryOutcome=Math.clamp(tag.getInt("DeliveryOutcome"),0,3);
        flight = null;
        if (tag.contains("HatFlight")) {
            var f=tag.getCompound("HatFlight"); var from=vector(f,"From"); var seat=vector(f,"Seat"); var to=vector(f,"To");
            int ticks=Math.clamp(f.getInt("Ticks"),1,HatVessel.TMAX);
            if (f.getBoolean("Physical") && from!=null && to!=null && (!f.hasUUID("Hat")||seat!=null)) {
                flight=new Flight(f.hasUUID("Hat")?f.getUUID("Hat"):null,from,seat,to,ticks);
                flightTick=Math.clamp(f.getInt("Elapsed"),0,ticks-1);
            }
        }
        // Dry stems are no longer kept on the body.
        spentStemCount=0;spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        carrying=tag.contains("CareFood")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("CareFood")):ItemStack.EMPTY;
        if(carrying.getCount()!=1)carrying=ItemStack.EMPTY;
        careCarryTicks=Math.clamp(tag.getInt("CareFoodTicks"),0,200);
        careChewTicks=Math.clamp(tag.getInt("CareChewTicks"),0,40);careOfferTicks=Math.clamp(tag.getInt("CareOfferTicks"),0,20);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        rescuingChild=null;rescue.load(tag.getCompound("BraidRescue"));
        boarding.load(tag.getCompound("HatBoarding"));
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
