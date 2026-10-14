package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Individual growth and interruptible family goals; durable births live in FamilyData. */
public final class FamilyLife {
    public enum Stage { BABY, JUVENILE, ADULT }
    private enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH, MIDWIFE }
    private final YukkuriEntity entity;
    private Stage stage=Stage.ADULT;
    private long maturity=GrowthTiming.DEFAULT_MATURITY, age=maturity+1, nourishment=maturity,
            lifespan=maturity+GrowthTiming.DEFAULT_ADULT_LIFESPAN;
    private float adultScale=1, scale=1;
    private UUID mother, father, birthId, gestation, partner;
    private int conceptions, recovery, wait, taskTicks, spentStemCount;
    private float spentStemVariation=1;
    private boolean initialized, awaitingLanding, juvenileVoice;
    private boolean sterile;
    private int stimulation;
    private int sexDrive;
    private boolean babyTalk;
    private int liveRecovery;
    private Vec3 deliveryLast;
    /** Hat bow while the hat comes off or goes back on; ticks the hat cradle stays after the births. */
    private int doffTicks, cradleLinger;
    /** A newborn pushed past the hat by an overstrained delivery: it will not survive the landing. */
    private boolean doomed;
    /** Afloat and heading for a bank to give birth there (decided every 40 ticks, needs a bank in reach). */
    private boolean shoreBound;
    public float liveBelly(){
        var pregnancy=brood();
        if(pregnancy!=null&&!pregnancy.cancelled&&!pregnancy.complete()&&pregnancy.kind==FamilyData.BirthKind.LIVE)
            return LiveBirthGeometry.belly(pregnancy.progress(),pregnancy.children.size()-pregnancy.unborn(),pregnancy.children.size());
        return .22f*liveRecovery/70f;
    }
    private Task task=Task.NONE;
    private YukkuriEntity target;
    private Vec3 searchAt;
    public FamilyLife(YukkuriEntity entity) { this.entity=entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public Stage stage() { return stage; }
    public long age() { return age; }
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
    public boolean active() { return task!=Task.NONE||laboring()||doffTicks>0; }
    public String activity() {
        if(doffTicks>0)return "doff_hat";
        if(laboring())return "labor";
        return switch(task) {
            case COURT, RECEIVE_COURT -> "courtship"; case BROOD -> "tend_brood"; case FOLLOW -> "follow_parent";
            case WAIT_CHILD -> "wait_child"; case SEARCH -> "search_family"; case MIDWIFE -> "midwife"; default -> awaitingLanding?"newborn_fall":"idle";
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
    /** Where the hat is set: in front of the live outlet's exit, or under the stem's fruit, its near brim clear of the mother. */
    public static Vec3 catchSpot(YukkuriEntity mother,FamilyData.Brood brood) {
        var forward=Vec3.directionFromRotation(0,mother.yBodyRot);
        // The turned-over hat reaches .54 of its owner's size toward the mother (hat_boat prop, short side).
        double clear=mother.getBbWidth()*.5+.6*mother.growthScale();
        if(brood.kind==FamilyData.BirthKind.LIVE){
            int next=0;while(next<brood.children.size()-1&&brood.children.get(next).born)next++;
            var exit=LiveBirthGeometry.position(mother,next);
            return exit.add(forward.scale(.6*mother.growthScale()));
        }
        Vec3 sum=Vec3.ZERO;int count=brood.children.size();
        for(int i=0;i<count;i++)sum=sum.add(FamilyGeometry.toWorld(mother,FamilyGeometry.childBase(count,i,1,mother.growthScale(),brood.variation,mother.getVariant(),brood.children.get(i).variant)));
        var centre=sum.scale(1d/Math.max(1,count));
        double ahead=Math.max(clear,centre.subtract(mother.position()).dot(forward));
        var spot=mother.position().add(forward.scale(ahead));return new Vec3(spot.x,mother.getY(),spot.z);
    }
    /** This Marisa's hat, turned over on the ground nearby. */
    public io.github.longshou.mcyukkuri.subspecies.HatBoatEntity cradleEntity() {
        if(entity.level().isClientSide)return null;
        return level().getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,entity.getBoundingBox().inflate(16),b->b.isCradle()&&entity.getUUID().equals(b.ownerYukkuri()))
                .stream().findFirst().orElse(null);
    }
    /** The cradle stays while the young are coming, a little after, and on aquatic nights with young around. */
    public boolean keepsCradle() {
        if(cradleLinger>0||task==Task.MIDWIFE)return true;
        var pending=brood();if(pending!=null&&!pending.cancelled&&!pending.complete())return true;
        return nightCradle();
    }
    /** An aquatic parent's young sleep in her hat at night (user design). */
    private boolean nightCradle() {
        return adult()&&entity.subspecies().kind()==io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA&&level().isNight()
                &&!level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),y->!y.family().adult()
                        &&(entity.getUUID().equals(y.family().mother)||entity.getUUID().equals(y.family().father))).isEmpty();
    }
    /** Turn the hat over at the spot, its long brim pointing away from the mother (or from this Marisa at night). */
    private void placeHat(Vec3 spot,float mothersYaw){
        if(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.placeCradle(entity,spot,mothersYaw+180)!=null){doffTicks=24;entity.getNavigation().stop();}
    }
    /** Called by the cradle when it is time to put the hat back on. */
    public void hatBack(){doffTicks=24;}
    public void doom(){doomed=true;}
    public boolean doomed(){return doomed;}
    /** Aquatic mothers may give birth on a riverbank, which serves as a temporary nest. */
    private boolean bankNest(){
        if(entity.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA)return false;
        for(var p:BlockPos.betweenClosed(entity.blockPosition().offset(-4,-2,-4),entity.blockPosition().offset(4,1,4)))
            if(level().getFluidState(p).is(net.minecraft.tags.FluidTags.WATER))return true;
        return false;
    }
    /** An aquatic mother afloat comes ashore to give birth when the father can catch, or by her own choice [original 1/2]. */
    private boolean wantsShore(FamilyData.Brood brood){
        if(brood.father!=null&&level().getEntity(brood.father) instanceof YukkuriEntity father&&father.isAlive()&&father.distanceToSqr(entity)<1024)return true;
        return new java.util.Random(brood.id.getLeastSignificantBits()^0x53484f5245L).nextBoolean();
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
        if(!entity.subspecies().canReproduce()) {
            // Retire invalid legacy maternal gestation while retaining born children and ancestry records.
            var pending=brood();
            if(pending!=null&&!pending.cancelled&&!pending.complete())FamilyData.get(level()).cancel(pending);
            gestation=null;stimulation=sexDrive=0;
        }
        if(entity.isNoAi())return;
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
        if(doffTicks>0)doffTicks--;if(cradleLinger>0)cradleLinger--;
        if(awaitingLanding&&(entity.onGround()||entity.isInWaterOrBubble()||entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity)) {
            awaitingLanding=false;
            if(doomed){
                // Pushed past the hat: drowned in the water, or killed by the hard landing.
                doomed=false;entity.hurt(entity.isInWaterOrBubble()?level().damageSources().drown():level().damageSources().generic(),entity.getMaxHealth()*4);return;
            }
            if(!entity.isPassenger()){
                var hat=level().getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,entity.getBoundingBox().inflate(.9),b->b.isCradle()&&b.young(entity)&&b.hasRoomForYoung()).stream().findFirst().orElse(null);
                if(hat!=null)hat.boardYoung(entity);
            }
            entity.showReaction("happy","newborn",100);
        }
        if(!adult()&&!entity.isPassenger()&&level().isNight()&&entity.tickCount%10==0) {
            // At night the young of an aquatic parent climb into the parent's hat to sleep.
            var hat=level().getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,entity.getBoundingBox().inflate(1.4),b->b.isCradle()&&b.young(entity)&&b.hasRoomForYoung()).stream().findFirst().orElse(null);
            if(hat!=null)hat.boardYoung(entity);
        }
        if(entity.tickCount%20==0&&entity.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&!entity.life().hasAccessory()&&!entity.isPassenger()&&!keepsCradle()) {
            // Go back for the hat once the young no longer need it; the cradle hands it over when close.
            var hat=cradleEntity();if(hat!=null&&entity.distanceToSqr(hat)>9)entity.life().navigate(hat.blockPosition(),.9,false);
        }
        if(entity.tickCount%40==0&&nightCradle()&&hatted(entity)&&entity.onGround()&&brood()==null&&!active())
            placeHat(entity.position().add(Vec3.directionFromRotation(0,entity.yBodyRot).scale(entity.getBbWidth()*.5+.6*entity.growthScale())),entity.yBodyRot);
        var pendingBrood=brood();var afloat=boatHome();
        if(afloat==null)shoreBound=false;
        else if(pendingBrood!=null&&!pendingBrood.cancelled&&!pendingBrood.complete()&&entity.tickCount%40==0
                &&pendingBrood.elapsed>=pendingBrood.duration-(pendingBrood.kind==FamilyData.BirthKind.LIVE?200:400)){
            var bank=wantsShore(pendingBrood)?afloat.nearestBank():null;shoreBound=bank!=null;
            if(bank!=null)afloat.towTo(Vec3.atBottomCenterOf(bank),60);
        }
        if(FamilyConfig.AGING.get()&&age>=lifespan) { entity.hurt(level().damageSources().generic(),entity.getMaxHealth()+100);return; }
        if(entity.isPanicking()||entity.life().hasUrgentNeed()||entity.isCommandPlaying())stop();
        var brood=brood();
        if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.STEM) advanceStem(brood);
        else if(brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE)advanceLive(brood);
        else if(gestation!=null&&(brood==null||brood.cancelled||brood.complete()))gestation=null;
    }
    /** Called once when a real sleep completes. Neither observation nor time-of-day commands grow the entity. */
    public boolean woke() {
        if(entity.level().isClientSide||entity.life().nutrition()<35)return false;
        Stage next=stage;long threshold=maturity;
        float desired=scale;
        if(stage==Stage.BABY&&age>=threshold/4&&nourishment>=threshold*3/16)next=Stage.JUVENILE;
        if(next==Stage.JUVENILE&&age>threshold&&nourishment>=threshold*.75)next=Stage.ADULT;
        if(next==Stage.BABY)desired=entity.getVariant().isHouse()?Mth.lerp(Math.min(1,nourishment/(float)Math.max(1,threshold/4)),.32f,.5f):Mth.lerp(Math.min(1,nourishment/(float)Math.max(1,threshold/4)),.12f,.3f);
        else if(next==Stage.JUVENILE)desired=Mth.lerp(Math.min(1,nourishment/(float)threshold),entity.getVariant().isHouse()?.5f:.3f,.8f);
        else desired=Mth.lerp(Math.min(1,Math.max(0,nourishment-threshold)/(float)Math.max(1,threshold/2)),.8f,adultScale);
        desired=Math.max(scale,desired);
        var box=LiveBirthGeometry.bodyDimensions(entity,desired*entity.subspecies().formScale(),liveBelly()).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,box))return false;
        boolean changed=next!=stage||Math.abs(desired-scale)>.001f;stage=next;scale=desired;
        entity.syncFamily();if(changed)entity.showReaction("happy","grown",100);return changed;
    }
    public void newborn(FamilyData.Child child,FamilyData.Brood brood) {
        initialized=true;mother=brood.mother;father=brood.father;birthId=brood.id;adultScale=child.adultScale;lifespan=child.lifespan;
        maturity=Math.clamp(child.maturity,1,Math.max(1,lifespan-1));
        age=nourishment=0;stage=brood.kind==FamilyData.BirthKind.STEM?Stage.JUVENILE:Stage.BABY;
        scale=brood.kind==FamilyData.BirthKind.STEM?.3f:brood.kind==FamilyData.BirthKind.LIVE?LiveBirthGeometry.BABY_SCALE:.32f;
        awaitingLanding=brood.kind!=FamilyData.BirthKind.EGG;
        juvenileVoice=new java.util.Random(child.id.getLeastSignificantBits()).nextInt(10)==0;
        CompoundTag personality=new CompoundTag();personality.putIntArray("Innate",child.traits);personality.putIntArray("Experience",new int[6]);
        if(child.dispositions.length==4){personality.putInt("Version",2);personality.putIntArray("DispositionInnate",child.dispositions);}
        entity.life().personality().load(personality);
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
        stage = Stage.JUVENILE; scale = .4f; adultScale = 1;
        entity.syncFamily();
        var relations = YukkuriWorldData.get(level());
        for (YukkuriEntity parent : second == null ? java.util.List.of(first) : java.util.List.of(first, second)) {
            relations.relate(entity.getUUID(), parent.getUUID(), 30);
            relations.relate(parent.getUUID(), entity.getUUID(), 30);
        }
    }
    public boolean introduce(YukkuriEntity other) {
        if(other==entity||!adult()||!other.family().adult()||entity.getVariant().isHouse()!=other.getVariant().isHouse()
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
        if(boat!=null){ if(shoreBound&&brood.elapsed>=brood.duration-400||!FamilyData.get(level()).relocate(brood,level(),boat.blockPosition()))return; }
        else if(bankNest()&&(entity.life().homeStand()==null||entity.distanceToSqr(Vec3.atBottomCenterOf(entity.life().homeStand()))>4)) {
            if(!FamilyData.get(level()).relocate(brood,level(),entity.blockPosition()))return;
        } else {
            BlockPos home=entity.life().homeStand();
            if(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4||!FamilyData.get(level()).relocate(brood,level(),home))return;
        }
        if(entity.life().mealTicks()>0||entity.life().isSleeping())return;
        if(boat==null&&brood.elapsed>=brood.duration-200&&catcher(entity,brood)==entity&&cradleEntity()==null)placeHat(catchSpot(entity,brood),entity.yBodyRot);
        if(brood.elapsed<brood.duration+40)brood.elapsed++;
        if(brood.elapsed%200==0)entity.life().spendNutrition(1);
        FamilyData.get(level()).setDirty();
        for(int i=0;i<brood.children.size();i++) {
            if(brood.children.get(i).born||brood.elapsed<brood.duration+i*8)continue;
            Vec3 local=FamilyGeometry.childBase(brood.children.size(),i,1,entity.growthScale(),brood.variation,entity.getVariant(),brood.children.get(i).variant);
            Vec3 world=FamilyGeometry.toWorld(entity,local);
            // A blocked attachment delays birth; it does not spawn through the ceiling or into a wall.
            var child=FamilyData.get(level()).birth(brood,i,level(),world);
            if(child!=null) { if(boat==null&&!child.family().doomed())child.setDeltaMovement(0,-.015,0);entity.showReaction("happy","birth",100); }
        }
        if(brood.complete()) {
            if(cradleEntity()!=null)cradleLinger=120;
            spentStemCount=brood.children.size();spentStemVariation=brood.variation;
            recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());gestation=null;stop();
        }
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
        if(boat!=null){ if(shoreBound&&brood.elapsed>=brood.duration-200||!FamilyData.get(level()).relocate(brood,level(),boat.blockPosition()))return; }
        else {
            var home=entity.life().homeStand();
            boolean bank=bankNest()&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4);
            if(!bank&&(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4)||!FamilyData.get(level()).relocate(brood,level(),bank?entity.blockPosition():home))return;
            // On land the mother must hold still; afloat, drifting with the water does not stop a delivery.
            if(brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004))return;
        }
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(boat==null&&brood.elapsed>=brood.duration-2&&catcher(entity,brood)==entity&&cradleEntity()==null){placeHat(catchSpot(entity,brood),entity.yBodyRot);return;}
        if(doffTicks>0)return;
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
                    if(boat==null&&!child.isPassenger()&&!child.family().doomed())child.setDeltaMovement(new Vec3(0,-.015,-.075).yRot((float)Math.toRadians(180-entity.yBodyRot)).scale(entity.getScale()));
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
        var child=level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(10),other->!other.family().adult()
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
                    entity.getUUID().compareTo(other.getUUID())<0&&(partner==null||partner.equals(other.getUUID()))
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
    public boolean canContinue() {
        return task!=Task.NONE&&!entity.isPanicking()&&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!entity.isCommandPlaying()
                &&(taskTicks<240||task==Task.MIDWIFE)&&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        switch(task) {
            case BROOD -> {
                if(boatHome()!=null){entity.getNavigation().stop();return;}
                BlockPos home=entity.life().homeStand();if(home==null){stop();return;}
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
                entity.getLookControl().setLookAt(target,20,20);if(taskTicks%10==1&&!entity.life().navigate(target.blockPosition(),1.15,false))stop();
            }
            case WAIT_CHILD -> {
                entity.getNavigation().stop();entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)<4||!entity.life().canRecognize(target))stop();
            }
            case MIDWIFE -> {
                var b=target.family().brood();
                if(b==null||b.cancelled||b.complete()||target.family().boatHome()!=null){ if(cradleEntity()!=null)cradleLinger=120; stop();return; }
                var spot=midwifeSpot(target);var catchAt=catchSpot(target,b);
                if(entity.distanceToSqr(spot)>.3) { if(taskTicks%10==1)entity.life().navigate(BlockPos.containing(spot),.9,false); }
                else {
                    entity.getNavigation().stop();
                    // Take the hat off and set it, turned over, where the young will come down.
                    if(doffTicks==0&&catcher(target,b)==entity&&cradleEntity()==null)placeHat(catchAt,target.yBodyRot);
                }
                entity.getLookControl().setLookAt(catchAt.x,catchAt.y+.1,catchAt.z,20,20);
            }
            case SEARCH -> {
                if(searchAt==null||entity.distanceToSqr(searchAt)<2){stop();return;}
                if(taskTicks%20==1&&!entity.life().navigate(BlockPos.containing(searchAt),.9,false))stop();
            }
            default -> { }
        }
    }
    public void stop() { task=Task.NONE;target=null;searchAt=null;taskTicks=0;wait=Math.max(wait,40); }
    public void died() {
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
        tag.putInt("SpentStemCount",spentStemCount);tag.putFloat("SpentStemVariation",spentStemVariation);
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
        adultScale=Math.clamp(tag.getFloat("AdultScale"),.8f,1.2f);scale=Math.clamp(tag.getFloat("Scale"),.12f,1.2f);
        conceptions=Math.max(0,tag.getInt("Conceptions"));recovery=Math.max(0,tag.getInt("Recovery"));initialized=tag.getBoolean("Initialized");
        awaitingLanding=tag.getBoolean("AwaitingLanding");juvenileVoice=tag.getBoolean("JuvenileVoice");
        sterile=tag.getBoolean("Sterile");stimulation=Math.clamp(tag.getInt("Stimulation"),0,1200);
        babyTalk=tag.getBoolean("BabyTalk");sexDrive=Math.clamp(tag.getInt("SexDrive"),0,1200);
        liveRecovery=Math.clamp(tag.getInt("LiveRecovery"),0,70);deliveryLast=null;
        spentStemCount=Math.clamp(tag.getInt("SpentStemCount"),0,5);spentStemVariation=Math.clamp(tag.getFloat("SpentStemVariation"),.85f,1.15f);
        mother=tag.hasUUID("Mother")?tag.getUUID("Mother"):null;father=tag.hasUUID("Father")?tag.getUUID("Father"):null;
        birthId=tag.hasUUID("BirthId")?tag.getUUID("BirthId"):null;gestation=tag.hasUUID("Gestation")?tag.getUUID("Gestation"):null;partner=tag.hasUUID("Partner")?tag.getUUID("Partner"):null;
        stop();entity.syncFamily();
    }
}
