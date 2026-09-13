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
    private enum Task { NONE, COURT, RECEIVE_COURT, BROOD, FOLLOW, WAIT_CHILD, SEARCH }
    private final YukkuriEntity entity;
    private Stage stage=Stage.ADULT;
    private long age=24001, nourishment=24000, lifespan=72000;
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
    public boolean canTakeStimulant(){return adult()&&!sterile&&!elder()&&sexDrive==0;}
    public boolean takeStimulant(){if(!canTakeStimulant())return false;sexDrive=1200;entity.setPersistenceRequired();return true;}
    public boolean adult() { return stage==Stage.ADULT; }
    public boolean caresFor(YukkuriEntity child) {
        return adult()&&!child.family().adult()&&(entity.getUUID().equals(child.family().mother)||entity.getUUID().equals(child.family().father));
    }
    public boolean hasHungryChild() {
        return !entity.level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(1.5),child->caresFor(child)
                &&entity.life().canRecognize(child)&&child.life().nutrition()<entity.life().nutrition()-15).isEmpty();
    }
    public boolean elder() { return age>=lifespan*.85; }
    public boolean active() { return task!=Task.NONE; }
    public String activity() { return switch(task) {
        case COURT, RECEIVE_COURT -> "courtship"; case BROOD -> "tend_brood"; case FOLLOW -> "follow_parent";
        case WAIT_CHILD -> "wait_child"; case SEARCH -> "search_family"; default -> awaitingLanding?"newborn_fall":"idle";
    }; }
    public FamilyData.Brood brood() {
        var result=gestation==null||entity.level().isClientSide?null:FamilyData.get(level()).brood(gestation);
        return result!=null&&result.detachedHolder==null?result:null;
    }
    public boolean sterile(){return sterile;}
    public int stimulation(){return stimulation;}
    public boolean stimulate(){if(!adult()||sterile)return false;stimulation=Math.min(1200,stimulation+240);return true;}
    public boolean sterilize(){if(!adult()||sterile||stimulation==0)return false;sterile=true;stimulation=0;return true;}
    public void detached(){gestation=null;spentStemCount=0;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());stop();entity.syncFamily();}
    public void tick() {
        if(entity.level().isClientSide||!entity.isAlive()||entity.isNoAi())return;
        if(!initialized) {
            initialized=true;lifespan=Math.round(FamilyConfig.LIFESPAN.get()*(.95+new java.util.Random(entity.getUUID().getLeastSignificantBits()).nextDouble()*.1));
            age=lifespan/3+1;nourishment=lifespan/3;
        }
        age++;if(entity.life().nutrition()>=45&&entity.getHealth()>=entity.getMaxHealth()*.6)nourishment++;
        if(recovery>0)recovery--;if(wait>0)wait--;if(stimulation>0)stimulation--;if(liveRecovery>0)liveRecovery--;
        if(sexDrive>0){sexDrive--;if(sexDrive%200==0)entity.life().spendNutrition(1);}
        if(entity.tickCount%100==0)FamilyData.get(level()).register(entity);
        if(awaitingLanding&&entity.onGround()) { awaitingLanding=false;entity.showReaction("happy","newborn",100); }
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
        Stage next=stage;long threshold=Math.max(1,lifespan/3);
        float desired=scale;
        if(stage==Stage.BABY&&age>=lifespan/12&&nourishment>=lifespan/16)next=Stage.JUVENILE;
        if(next==Stage.JUVENILE&&age>threshold&&nourishment>=threshold*.75)next=Stage.ADULT;
        if(next==Stage.BABY)desired=entity.getVariant().isHouse()?Mth.lerp(Math.min(1,nourishment/(float)Math.max(1,lifespan/12)),.32f,.5f):Mth.lerp(Math.min(1,nourishment/(float)Math.max(1,lifespan/12)),.12f,.3f);
        else if(next==Stage.JUVENILE)desired=Mth.lerp(Math.min(1,nourishment/(float)threshold),entity.getVariant().isHouse()?.5f:.3f,.8f);
        else desired=Mth.lerp(Math.min(1,Math.max(0,nourishment-threshold)/(float)Math.max(1,lifespan/6)),.8f,adultScale);
        desired=Math.max(scale,desired);
        var box=LiveBirthGeometry.bodyDimensions(entity,desired,liveBelly()).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,box))return false;
        boolean changed=next!=stage||Math.abs(desired-scale)>.001f;stage=next;scale=desired;
        entity.syncFamily();if(changed)entity.showReaction("happy","grown",100);return changed;
    }
    public void newborn(FamilyData.Child child,FamilyData.Brood brood) {
        initialized=true;mother=brood.mother;father=brood.father;birthId=brood.id;adultScale=child.adultScale;lifespan=child.lifespan;
        age=nourishment=0;stage=brood.kind==FamilyData.BirthKind.STEM?Stage.JUVENILE:Stage.BABY;
        scale=brood.kind==FamilyData.BirthKind.STEM?.3f:brood.kind==FamilyData.BirthKind.LIVE?LiveBirthGeometry.BABY_SCALE:.32f;
        awaitingLanding=brood.kind!=FamilyData.BirthKind.EGG;
        juvenileVoice=new java.util.Random(child.id.getLeastSignificantBits()).nextInt(10)==0;
        CompoundTag personality=entity.life().personality().save();personality.putIntArray("Innate",child.traits);personality.putIntArray("Experience",new int[6]);entity.life().personality().load(personality);
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
                &&!entity.isNoAi()&&gestation==null&&adult()&&!sterile&&!elder()&&recovery==0
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
        lifespan = FamilyConfig.LIFESPAN.get(); age = lifespan / 8; nourishment = age;
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
        return adult()&&!sterile&&!elder()&&recovery==0&&entity.isAlive()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()&&!entity.predator().active()
                &&!entity.life().hasUrgentNeed()&&entity.life().nutrition()>=65&&entity.life().energy()>=50&&entity.life().hygiene()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.8&&entity.body().wound()==0&&entity.body().infection()==0&&entity.life().mealTicks()==0&&entity.life().homeStand()!=null;
    }
    public boolean canReproduceWith(YukkuriEntity other) {
        if(other==entity||entity.dailyLife().active()||other.dailyLife().active()||entity.level()!=other.level()||!ready()||!other.family().ready()||entity.distanceToSqr(other)>9
                ||entity.getVariant().isHouse()!=other.getVariant().isHouse()||entity.getVariant().isPredator()!=other.getVariant().isPredator()||!entity.life().canRecognize(other)||!other.life().canRecognize(entity))return false;
        if(partner!=null&&!partner.equals(other.getUUID())||other.family().partner!=null&&!other.family().partner.equals(entity.getUUID()))return false;
        var data=FamilyData.get(level());var relations=YukkuriWorldData.get(level());
        if(data.related(entity.getUUID(),other.getUUID())||relations.affinity(entity.getUUID(),other.getUUID())<12||relations.affinity(other.getUUID(),entity.getUUID())<12)return false;
        var a=entity.life().homeStand();var b=other.life().homeStand();
        return a!=null&&b!=null&&a.distSqr(b)<=64&&entity.life().reachable(other.blockPosition(),false);
    }
    private void advanceStem(FamilyData.Brood brood) {
        if(entity.operations().active())return;
        if(entity.isPanicking()||entity.isOnFire()||entity.isInWaterOrBubble()||entity.isCommandPlaying()||entity.life().nutrition()<30||entity.body().wound()>0||entity.body().infection()>0)return;
        BlockPos home=entity.life().homeStand();
        if(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4||!FamilyData.get(level()).relocate(brood,level(),home))return;
        if(entity.life().mealTicks()>0||entity.life().isSleeping())return;
        if(brood.elapsed<brood.duration+40)brood.elapsed++;
        if(brood.elapsed%200==0)entity.life().spendNutrition(1);
        FamilyData.get(level()).setDirty();
        for(int i=0;i<brood.children.size();i++) {
            if(brood.children.get(i).born||brood.elapsed<brood.duration+i*8)continue;
            Vec3 local=FamilyGeometry.childBase(brood.children.size(),i,1,scale,brood.variation,entity.getVariant(),brood.children.get(i).variant);
            Vec3 world=FamilyGeometry.toWorld(entity,local);
            // A blocked attachment delays birth; it does not spawn through the ceiling or into a wall.
            var child=FamilyData.get(level()).birth(brood,i,level(),world);
            if(child!=null) { child.setDeltaMovement(0,-.015,0);entity.showReaction("happy","birth",100); }
        }
        if(brood.complete()) {
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
        // A zero-displacement physics step can clear onGround for one tick while the sole still touches a block.
        return entity.onGround()||Math.abs(entity.getDeltaMovement().y)<.09
                &&entity.level().getBlockCollisions(entity,entity.getBoundingBox().move(0,-.025,0)).iterator().hasNext();
    }
    private void advanceLive(FamilyData.Brood brood){
        Vec3 before=deliveryLast;deliveryLast=entity.position();
        if(!readyForDelivery())return;
        var home=entity.life().homeStand();
        if(home==null||entity.distanceToSqr(Vec3.atBottomCenterOf(home))>4||!FamilyData.get(level()).relocate(brood,level(),home))return;
        if(brood.elapsed>=brood.duration&&(before==null||before.distanceToSqr(deliveryLast)>.0004))return;
        int next=0;while(next<brood.children.size()&&brood.children.get(next).born)next++;
        if(next==brood.children.size())return;
        if(brood.elapsed>=brood.duration){
            entity.getNavigation().stop();
            if(!LiveBirthGeometry.clear(entity,brood.children.get(next),next))return;
        }
        float nextBelly=LiveBirthGeometry.belly(Math.min(1,(brood.elapsed+1f)/brood.duration),brood.children.size()-brood.unborn(),brood.children.size());
        var room=LiveBirthGeometry.bodyDimensions(entity,scale,nextBelly).scale(entity.getScale()).makeBoundingBox(entity.position());
        if(!level().noCollision(entity,room))return;
        brood.elapsed++;if(brood.elapsed%200==0)entity.life().spendNutrition(1);FamilyData.get(level()).setDirty();
        if(brood.elapsed>=brood.duration){
            if(brood.elapsed%24==0)entity.showReaction("chewing_closed","birth",25);
            if(brood.elapsed>=brood.duration+LiveBirthGeometry.birthTick(next)){
                var child=FamilyData.get(level()).birth(brood,next,level(),LiveBirthGeometry.position(entity,next));
                if(child!=null){
                    // A soft physical release lets the landed sibling clear the next child's outlet.
                    child.setDeltaMovement(new Vec3(0,-.015,-.075).yRot((float)Math.toRadians(180-entity.yBodyRot)).scale(entity.getScale()));
                    entity.showReaction("happy","birth",60);
                }
            }
        }
        if(brood.complete()){gestation=null;liveRecovery=70;recovery=Math.max(recovery,FamilyConfig.RECOVERY.get());deliveryLast=null;stop();}
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
    private YukkuriEntity closestParent() {
        return level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(12),other->other.isAlive()
                &&(other.getUUID().equals(mother)||other.getUUID().equals(father))&&entity.life().canRecognize(other))
                .stream().filter(other->entity.life().reachable(other.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }
    public boolean canContinue() {
        return task!=Task.NONE&&!entity.isPanicking()&&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!entity.isCommandPlaying()
                &&taskTicks<240&&(task==Task.BROOD||task==Task.SEARCH||target!=null&&target.isAlive()&&target.level()==entity.level()&&entity.distanceToSqr(target)<256);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}taskTicks++;
        switch(task) {
            case BROOD -> {
                BlockPos home=entity.life().homeStand();if(home==null){stop();return;}
                if(entity.distanceToSqr(Vec3.atBottomCenterOf(home))>.36) { if(taskTicks%10==1)entity.life().navigate(home,.9,false); }
                else { entity.getNavigation().stop();entity.getLookControl().setLookAt(entity.getX(),entity.getEyeY(),entity.getZ()-2,10,10); }
            }
            case COURT -> {
                if(!canReproduceWith(target)){stop();return;}
                entity.getLookControl().setLookAt(target,20,20);
                if(entity.distanceToSqr(target)>2.25) { if(taskTicks%10==1)entity.life().navigate(target.blockPosition(),.8,false); }
                else if(taskTicks>=(sexDrive>0?60:120)) {
                    var brood=FamilyData.get(level()).conceive(entity,target,naturalBirthKind(target),GlobalPos.of(level().dimension(),entity.life().homeStand()));
                    if(brood!=null) { partner=target.getUUID();target.family().partner=entity.getUUID();entity.showReaction("happy","expecting",100); }
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
        for(var other:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(10),other->other!=entity&&other.isAlive()))
            if(data.related(entity.getUUID(),other.getUUID())&&other.life().canRecognize(entity)&&other.hasLineOfSight(entity)) {
                other.life().personality().experienced(Trait.BOLDNESS,-3);other.showReaction("surprise","family_died",120);
            }
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag();tag.putInt("Version",1);tag.putString("Stage",stage.name());tag.putLong("Age",age);tag.putLong("Nourishment",nourishment);
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
