package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** Vanilla health is the sole live-body resource; wounds and the one-time death snapshot belong here. */
public final class BodyLife {
    private final YukkuriEntity entity;
    private int wound,infection,ticks;
    private int mold,moldExposure;
    private int burns,hairLoss;
    private final ArrayList<BodyWound> wounds=new ArrayList<>();
    private int releaseCursor;
    private boolean settled;
    private float deathFood = -1;
    private long lastWitness = Long.MIN_VALUE;
    /** Core damage is irreversible; an intact core no longer supplies a second life bar. */
    private boolean coreHurt;
    /** A baked sole never regains its spring (komicolle 11518802): permanent. footScorch accumulates sole burns. */
    private boolean footBurned;
    private int footScorch,toxin,moldSpread;
    /** Ticks left of the short dying state after the core is hurt (0 = not dying); see startDying. */
    private int dying;
    private String dyingCause="";
    private DamageSource dyingSource;
    /** Length of the dying state: long enough for a last look and line, short enough not to be a second life. */
    public static final int DYING_TICKS=80;
    /** Called once when a body starts dying (cause: poison, mold, non_yukkuri, core_front, ...). The last-words package hooks in here. */
    public interface DyingListener{void dying(YukkuriEntity entity,String cause,int ticks);}
    public static final List<DyingListener> DYING_LISTENERS=new CopyOnWriteArrayList<>();
    public static final float WEAK_HEALTH_RATIO = .3f;
    /** Poison load (bitter, spicy, toxic food): 1-4 is bearable, 5+ makes it sick, 9+ reaches the core. */
    public static final int SICK_TOXIN=5,LETHAL_TOXIN=9;
    /** Mould stages: 1-2 early, 3-5 middle (the remover still cures both), 6-7 late (one dose sets it back to early), 8 terminal. */
    public static final int MOLD_TERMINAL=8,MOLD_CURABLE=5,MOLD_LATE_RESET=2;
    private static final ResourceLocation MOBILITY=MCYukkuri.id("body_mobility");
    public BodyLife(YukkuriEntity entity){this.entity=entity;}
    public float healthRatio(){return Math.clamp(entity.getHealth()/Math.max(1,entity.getMaxHealth()),0,1);}
    /**
     * House bodies use their life stage for maximum health.  Keep the live-health fraction when a save is read or a
     * body grows, so the one health resource continues to drive weakness, food and remains consistently.
     */
    public void syncStageHealth(){
        if(entity.level().isClientSide||!entity.profile().houseBody()||!entity.isAlive())return;
        var health=entity.getAttribute(Attributes.MAX_HEALTH);if(health==null)return;
        float maximum=switch(entity.family().stage()){
            case BABY -> BodyConstants.HOUSE_BABY_HEALTH;
            case JUVENILE -> BodyConstants.HOUSE_JUVENILE_HEALTH;
            case ADULT -> BodyConstants.HOUSE_ADULT_HEALTH;
        };
        if(Math.abs(health.getBaseValue()-maximum)<.0001)return;
        float ratio=healthRatio();
        health.setBaseValue(maximum);
        // A live entity must remain live through a stage/save migration; vanilla will perform the next real death transaction.
        entity.setHealth(Math.max(.01f,Math.min(entity.getMaxHealth(),ratio*entity.getMaxHealth())));
        // Health and its MAX_HEALTH attribute have vanilla synchronization.  Body slots did not change here.
        updateMobility();
    }
    /** Legacy readout scale, derived from health; never stored or separately replenished. */
    public int filling(){return Math.round(healthRatio()*BodyConstants.MAX_FILLING);}
    public float remainsFood(){return Math.max(0,deathFood);}
    private void freezeFood(){if(deathFood<0)deathFood=BodyFood.capacity(entity)*healthRatio();}
    public BodyCondition condition() {
        return new BodyCondition(wound, burns, infection, mold, filling(), weak(), dying(), sick(), footBurned);
    }
    public int wound(){return wound;}
    public int infection(){return infection;}
    public int mold(){return mold;}
    public int burns(){return burns;}
    public int hairLoss(){return hairLoss;}
    public boolean coreIntact(){return !coreHurt;}
    public boolean dying(){return dying>0;}
    public int dyingTicks(){return dying;}
    public String dyingCause(){return dyingCause;}
    /** The visible core opening derives from the same health and skin state as rendering. */
    public boolean coreExposed(){return BodySigns.coreWound(WoundSurface.get(entity.subspecies().assetId()),wounds,false,healthRatio())!=null;}
    /** Regions that have a deep wound: blows there can reach the core. */
    public List<BodyPart> deepParts(){var mesh=WoundSurface.get(entity.subspecies().assetId());return wounds.stream().filter(w->mesh.valid(w)&&
            BodySigns.deep(w)).map(w->BodyPart.of(mesh,w)).distinct().toList();}
    /** Lasting states drawn on the skin (synced): mould stage, baked sole, broken core, dying. */
    public int signs(){return BodySigns.pack(mold,footBurned,coreHurt,dying>0);}
    public boolean footBurned(){return footBurned;}
    public int toxin(){return toxin;}
    /** Physiological thresholds derive from the one live health value. */
    public boolean sick(){return toxin>=SICK_TOXIN;}
    public boolean weak(){return entity.isAlive()&&healthRatio()<=WEAK_HEALTH_RATIO;}
    public List<BodyWound> wounds(){return List.copyOf(wounds);}
    private void refreshWounds(){wound=Math.min(8,wounds.stream().filter(w->w.kind()!=WoundKind.SCORCH).mapToInt(BodyWound::severity).sum());burns=
            Math.min(8,wounds.stream().filter(w->w.kind()==WoundKind.SCORCH).mapToInt(BodyWound::severity).sum());entity.syncBody();}
    public boolean cutHair(int parts,Player player){
        parts&=7;int cut=parts&~hairLoss;if(cut==0||entity.level().isClientSide||!entity.isAlive())return false;
        var stack=new ItemStack(ModItems.TRIMMED_HAIR.get());
        final int removed=cut;
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Source",entity.getUUID());tag.putString("Variant",entity.getVariant().assetId());
            tag.putInt("HairParts",removed);tag.putFloat("SourceScale",entity.growthScale()*entity.getScale()*(entity.profile().modelScale()));});
        var drop=new ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),stack);
        if(!entity.level().addFreshEntity(drop))return false;
        hairLoss|=cut;entity.syncBody();entity.mind().felt(EmotionEvent.FRIGHTENED);
                entity.showReaction("surprise","afraid",100);entity.life().personality().confiscated(player.getUUID());return true;
    }
    /** A wound the skin cannot regrow by juice alone (komicolle 11518847: badly damaged skin also needs wheat-flour paste). */
    private static boolean severe(BodyWound w){return w.severity()>=3&&w.kind()!=WoundKind.SCAR&&w.kind()!=WoundKind.DIRT;}
    /**
     * Orange juice repairs skin and filling only (komicolle reply 11520402): teeth, eyes, the hair and the hair ornament are
     * not regrown. Severe skin loss (a severity-3 wound) stays until wheat-flour paste has patched it.
     */
    public boolean restorePhysical() {
        if(!treatmentAllowed())return false;
        boolean skin=wounds.stream().anyMatch(w->!severe(w)),severeLeft=wounds.stream().anyMatch(BodyLife::severe);
        boolean needHealth=entity.getHealth()<entity.getMaxHealth();
        if(!skin&&!needHealth){if(severeLeft)entity.showReaction("teary","hurt",60);return false;}
        wounds.removeIf(w->!severe(w));
        refreshWounds();entity.setHealth(Math.max(entity.getHealth(),severeLeft?Math.min(entity.getMaxHealth(),entity.getHealth()+entity.getMaxHealth()/2):entity.getMaxHealth()));
        if (!severeLeft) entity.mind().felt(EmotionEvent.TREATED); entity.showReaction(severeLeft?"teary":"happy",severeLeft?"hurt":"treated",80);return true;
    }
    /** Wheat-flour paste fills in severe skin loss: severity 3 becomes 2, which juice (or time) can then finish. */
    public boolean patchSkin(){
        if(!treatmentAllowed()||wounds.stream().noneMatch(BodyLife::severe))return false;
        for(int i=0;i<wounds.size();i++)if(severe(wounds.get(i)))wounds.set(i,wounds.get(i).heal());
        refreshWounds();entity.mind().felt(EmotionEvent.TREATED); entity.showReaction("happy","treated",80);return true;
    }
    /**
     * Mould is a death sentence once it has gone deep (komicolle 11518802). The remover cures the early and middle stages in one
     * dose; a late stage is set back to early (a second dose then cures it); the terminal stage cannot be treated.
     */
    public boolean removeMold() {
        if(!treatmentAllowed()||mold==0||mold>=MOLD_TERMINAL)return false;
        if(mold<=MOLD_CURABLE){mold=moldExposure=moldSpread=0;entity.mind().felt(EmotionEvent.TREATED); entity.showReaction("happy","treated",80);}
        else{mold=MOLD_LATE_RESET;moldSpread=0;entity.showReaction("teary","hurt",80);}
        entity.syncBody();return true;
    }
    public void setMold(int level){mold=Math.clamp(level,0,MOLD_TERMINAL);moldSpread=0;entity.syncBody();}
    /** A hurt core cannot be treated: the dying state runs its course. */
    private boolean treatmentAllowed(){return !entity.level().isClientSide&&entity.isAlive()&&dying==0&&!coreHurt&&!entity.isPanicking()&&!entity.isInWaterOrBubble()&&!entity.isOnFire();}
    public void contaminated(){infection=Math.min(8,infection+1);}
    /** Eating a centre filling restores health and eases unease (project design). */
    public void ateCore(int sweetness){
        if(entity.level().isClientSide||!entity.isAlive()||dying>0)return;
        entity.heal(4);entity.mind().add(-(10+sweetness/5f),"core_filling");entity.syncBody();
    }
    /** Spice hurts only when the poison load is already high; a bearable dose just stings (komicolle 11518847). */
    public void irritated(){
        if(toxin<SICK_TOXIN)return;
        vomit();
        entity.hurtInternal(new DamageSource(entity.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(Consumables.SPICE_DAMAGE)),1);
    }
    /** Bitter, spicy or poisonous food: a little is endured, a lot makes the body sick, a great deal reaches the core. */
    public void poisoned(int dose){
        if(entity.level().isClientSide||!entity.isAlive()||settled||dose<=0)return;
        toxin=Math.min(12,toxin+dose);
        if(toxin>=LETHAL_TOXIN){damageCore("poison");return;}
        if(toxin>=SICK_TOXIN){vomit();damage(1);entity.mind().felt(EmotionEvent.BITTER_TASTE); entity.showReaction("teary","bitter",100);}
        else { entity.mind().felt(EmotionEvent.BITTER_TASTE); entity.showReaction("surprise","bitter",80); }
    }
    public void setToxin(int value){toxin=Math.clamp(value,0,12);}
    /** Deprecated scenario scale: write health, never an independent filling field. */
    public void setFilling(int value){
        if(settled||!entity.isAlive()||dying())return;
        entity.setHealth(Math.max(1,Math.clamp(value,0,BodyConstants.MAX_FILLING)*entity.getMaxHealth()/BodyConstants.MAX_FILLING));
        updateMobility();entity.syncBody();
    }
    /** Vomiting is a short visible event and a capped energy cost, not another health debit. */
    public boolean vomit(){
        if(entity.level().isClientSide||!entity.isAlive()||settled)return false;
        entity.life().spendEnergy(Math.min(2,entity.life().energy()));
        BodyEffects.emit(entity,"vomit",1,MouthPlacement.position(entity));
        witnessInjury();
        return true;
    }
    /**
     * The centre filling is hurt: fatal after a short dying state ({@link #DYING_TICKS}). Poison, terminal mould, the non-yukkuri
     * syndrome and a blow that reaches the middle all end here. Repeated calls while dying do nothing.
     */
    public void damageCore(String cause){
        if(entity.level().isClientSide||!entity.isAlive()||settled||dying>0)return;
        startDying(null,cause==null?"core":cause);
    }
    private void startDying(DamageSource source,String cause){
        if(dying>0||settled||!entity.isAlive())return;
        freezeFood();
        coreHurt=true;dying=DYING_TICKS;dyingCause=cause;dyingSource=source;
        entity.life().interrupt();entity.getNavigation().stop();
        entity.showReaction("blank_cry","dying",DYING_TICKS);
        updateMobility();entity.syncBody();
        MCYukkuri.LOGGER.info("[body] {} #{} core hurt ({}), dying",entity.getVariant().assetId(),entity.getId(),cause);
        for(var listener:DYING_LISTENERS){try{listener.dying(entity,cause,DYING_TICKS);}catch(RuntimeException ex){MCYukkuri.LOGGER.warn("Dying listener failed",ex);}}
    }
    private void finishDying(){
        terminalKill(dyingSource!=null?dyingSource:entity.damageSources().generic(),true,false);
        if(entity.isAlive())dying=1;
    }
    /** Explicit terminal causes retain the pre-terminal food snapshot, never the final execution damage. */
    public void terminalKill(DamageSource source,boolean destroyCore,boolean destroyFood){
        if(entity.level().isClientSide||!entity.isAlive()||settled)return;
        float previousFood=deathFood;boolean previousCore=coreHurt;
        freezeFood();
        if(destroyFood)deathFood=0;
        if(destroyCore)coreHurt=true;
        entity.hurtTerminal(source,1000);
        if(entity.isAlive())entity.hurtTerminal(entity.damageSources().genericKill(),Float.MAX_VALUE);
        // A vetoed terminal transition must not leave a living animal with a frozen corpse balance.
        if(entity.isAlive()){deathFood=previousFood;coreHurt=previousCore;}
    }
    public BodyHit prepareHurt(DamageSource source,float incoming,BodyHit.Kind kind){
        boolean physical=kind!=BodyHit.Kind.INTERNAL&&kind!=BodyHit.Kind.TERMINAL
                &&!InjuryModel.attrition(source)&&!source.is(DamageTypeTags.IS_DROWNING);
        var impact=physical&&!dying()?InjuryModel.analyse(entity,source,incoming,wounds,kind==BodyHit.Kind.BITE||kind==BodyHit.Kind.BITE_WOUND):null;
        var mesh=WoundSurface.get(entity.subspecies().assetId());
        var core=BodySigns.coreWound(mesh,wounds,false,healthRatio());
        boolean exposedAtHit=impact!=null&&core!=null&&BodyPart.of(mesh,core)==impact.part();
        return new BodyHit(kind,source,entity.getHealth(),incoming,impact,exposedAtHit,dying());
    }
    /** Called once after vanilla accepts a hit, including inside the fatal callback before remains are made. */
    public HurtResult onHurt(BodyHit hit,boolean accepted,float lost){
        float loss=accepted&&Float.isFinite(lost)?Math.clamp(lost,0,Math.max(0,hit.healthBefore())):0;
        if(loss<=0)return new HurtResult(accepted,0,hit.part(),BodyFood.Bite.EMPTY);
        if(hit.kind()==BodyHit.Kind.TERMINAL)return new HurtResult(true,loss,null,BodyFood.Bite.EMPTY);
        if(InjuryModel.destroysBody(hit.source(),loss,hit.healthBefore(),hit.incoming())){deathFood=0;coreHurt=true;}
        if(hit.dyingBefore())return new HurtResult(true,loss,hit.part(),BodyFood.Bite.EMPTY);
        var food=hit.kind()==BodyHit.Kind.BITE?BodyFood.live(entity,loss):BodyFood.Bite.EMPTY;
        var outcome=InjuryModel.decide(entity,hit,loss);
        var strike=hit.strike();
        if(strike!=null&&!settled){
            var mesh=WoundSurface.get(entity.subspecies().assetId());
            int severity=InjuryModel.severity(entity,hit.source(),loss);
            int closest=-1;float distance=Float.MAX_VALUE;
            var point=mesh.anchor(strike.hit());
            for(int i=0;i<wounds.size();i++){
                var old=wounds.get(i);
                if(old.kind()!=strike.kind()||!mesh.valid(old)||BodyPart.of(mesh,old)!=strike.part())continue;
                float d=mesh.anchor(old).distanceSquared(point);
                if(d<distance){distance=d;closest=i;}
            }
            BodyWound placed;
            if(closest>=0){
                var old=wounds.get(closest);
                int next=Math.min(3,Math.max(old.severity(),severity)+(loss>=2?1:0));
                placed=old.withSeverity(next);wounds.set(closest,placed);
                checkSole(placed,mesh);refreshWounds();
            }else{
                placed=new BodyWound(UUID.randomUUID(),strike.kind(),strike.hit().triangle(),strike.hit().u(),strike.hit().v(),
                        severity,entity.profile().portionScale(),0,0);
                insertWound(placed);
                for(var wound:wounds)if(wound.id().equals(placed.id())){placed=wound;break;}
            }
            if(BodySigns.open(placed)&&!hit.source().is(DamageTypeTags.IS_FIRE))emitWound("hit",loss,placed);
            if(entity.isAlive()){
                entity.mind().felt(EmotionEvent.WOUND_DISTRESS);
                entity.showReaction("surprise","wounded",80);
            }
            witnessInjury();
        }
        if(outcome!=InjuryModel.Outcome.NONE){
            coreHurt=true;
            if(entity.isAlive()){
                if(outcome==InjuryModel.Outcome.INSTANT)terminalKill(hit.source(),true,true);
                else startDying(hit.source(),"core_"+(hit.part()==null?"body":hit.part().id()));
            }
        }
        updateMobility();entity.syncBody();
        return new HurtResult(true,loss,hit.part(),food);
    }
    private void emitWound(String reason,float loss,BodyWound outlet){
        if(loss<=0)return;
        int index=wounds.indexOf(outlet);
        if(index>=0)wounds.set(index,outlet.released(entity.level().getGameTime()));
        // House yukkuri retain the same server-side wound and leak accounting, without emitting injury particles.
        if(!entity.profile().houseBody())BodyEffects.emit(entity,reason,loss/Math.max(1,entity.getMaxHealth()),WoundPlacement.outlet(entity,outlet));
    }
    private void witnessInjury(){
        if(!(entity.level() instanceof ServerLevel level))return;
        long now=level.getGameTime();
        if(lastWitness!=Long.MIN_VALUE&&now-lastWitness<200)return;
        lastWitness=now;
        for(var witness:level.getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(8),other->other!=entity&&other.isAlive()
                &&!other.life().isSleeping()&&other.distanceToSqr(entity)<=64&&other.hasLineOfSight(entity))){
            witness.mind().felt(EmotionEvent.WOUND_DISTRESS);witness.showReaction("surprise","wounded",60);
        }
    }
    /** A point on the underside (lowest fifth of the skin) where a hot floor or flame touches the sole. */
    private WoundSurface.Hit soleSite(){return InjuryModel.soleSite(entity);}
    /** Test and scenario tool: a wound of the given kind and severity on a region, through the normal wound logic. */
    public boolean woundAt(BodyPart part,String kind,int severity){
        if(entity.level().isClientSide||!entity.isAlive()||settled)return false;
        var mesh=WoundSurface.get(entity.subspecies().assetId());var hit=part.site(mesh,new Random(entity.getRandom().nextLong()));if(hit==null)return false;
        insertWound(new BodyWound(UUID.randomUUID(),kind,hit.triangle(),hit.u(),hit.v(),Math.clamp(severity,1,3),entity.profile().portionScale(),0,0));
        entity.syncBody();return true;
    }
    /** Burn the sole badly enough to lose its spring for good (scenario and status tool; real fire does it through insertWound). */
    public boolean burnFoot(){
        var hit=soleSite();if(hit==null||entity.level().isClientSide||!entity.isAlive())return false;
        insertWound(new BodyWound(UUID.randomUUID(),WoundKind.SCORCH,hit.triangle(),hit.u(),hit.v(),3,entity.profile().portionScale(),0,0));
        return footBurned;
    }
    /** Test and scenario only: the sole has no cure in the setting. */
    public void mendFoot(){footBurned=false;footScorch=0;updateMobility();}
    private void checkSole(BodyWound wound,WoundSurface mesh){
        if(wound.kind()!=WoundKind.SCORCH)return;
        var b=mesh.bounds();if(mesh.anchor(wound).y>b[1]+(b[4]-b[1])*BodyConstants.SOLE_HEIGHT_BAND)return;
        footScorch=Math.min(12,footScorch+wound.severity());
        if(!footBurned&&(wound.severity()>=3||footScorch>=4)){
            footBurned=true;entity.mind().felt(EmotionEvent.SOLE_BURN); entity.showReaction("cry","sole_burned",120);
            if(entity.level() instanceof ServerLevel level)level.sendParticles(ParticleTypes.SMOKE,entity.getX(),entity.getY()+.05,entity.getZ(),6,.1,.02,.1,.01);
            updateMobility();
        }
    }
    private void updateMobility(){
        var speed=entity.getAttribute(Attributes.MOVEMENT_SPEED);if(speed==null)return;
        double factor=dying>0?0:(footBurned?.3:1)*(weak()?.6:1);
        if(factor>=1)speed.removeModifier(MOBILITY);
        else speed.addOrUpdateTransientModifier(new AttributeModifier(MOBILITY,factor-1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    /** At capacity strengthen the nearest existing injury; never move an old scar. */
    public void addWound(BodyWound wound){if(!entity.isAlive())return;insertWound(wound);}
    private void insertWound(BodyWound wound){if(entity.level().isClientSide||settled)return;var mesh=WoundSurface.get(entity.subspecies().assetId());if(!mesh.valid(wound))return;
        checkSole(wound,mesh);
        if(wounds.size()<8)wounds.add(wound);else{int best=0;float distance=Float.MAX_VALUE;var p=mesh.anchor(wound);
            for(int i=0;i<wounds.size();i++){var w=wounds.get(i);float d=mesh.anchor(w).distanceSquared(p)+(w.kind().equals(wound.kind())?0:10000);if(d<distance){distance=d;best=i;}}
            var old=wounds.get(best);wounds.set(best,old.withSeverity(Math.min(3,old.severity()+1)));}
        refreshWounds();
    }
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        if(dying>0){entity.getNavigation().stop();if(--dying==0)finishDying();else if(dying%20==0)entity.syncBody();return;}
        if(entity.isNoAi())return;ticks++;
        var life=entity.life();
        if(life.wetness()>40&&life.hygiene()<40){
            if(++moldExposure>=1200){mold=Math.min(8,mold+1);moldExposure=0;entity.syncBody();}
        }else moldExposure=Math.max(0,moldExposure-1);
        if(mold>0){
            // Mould spreads on dirty skin and holds still on clean skin; the terminal stage reaches the core.
            if(life.hygiene()<50&&++moldSpread>=6000){moldSpread=0;mold=Math.min(MOLD_TERMINAL,mold+1);entity.syncBody();}
            if(ticks%400==0){if(mold>=MOLD_TERMINAL){damageCore("mold");return;}if(mold>=3)damage(.25f+mold*.1f);}
        }
        if(toxin>0&&ticks%400==0)toxin--;
        if(ticks%20==0)updateMobility();
        boolean open=wounds.stream().anyMatch(BodySigns::open);
        if(burns>0&&ticks%400==0){if(life.hygiene()>=40&&life.nutrition()>=30){if(healBurn())life.spendNutrition(1);}else if(burns>=4)damage(.5f);}
        if(wound>0&&ticks%200==0){
            // Only an open cut (severity 2+) leaks; paste reduces a deep cut to 2, and closure finally stops the leak.
            var leaking=wounds.stream().filter(w->w.kind()==WoundKind.TEAR&&BodySigns.open(w)).toList();
            float total=0;for(var cut:leaking)total+=cut.severity()>=3?1:.5f;
            if(total>0){
                float before=entity.getHealth();damage(Math.min(1,total));
                float loss=Math.max(0,before-entity.getHealth());
                for(var cut:leaking)emitWound("leak",loss*(cut.severity()>=3?1:.5f)/total,cut);
                if(!entity.isAlive())return;
            }
            if(open&&life.hygiene()<40)infection=Math.min(8,infection+1);
        }
        // Every severity-3 wound needs flour paste first, including burns and scratches.
        if(wound>0&&infection==0&&ticks%400==0&&life.nutrition()>=30&&life.hygiene()>=30)healMild();
        if(infection>0&&ticks%400==0){
            if(!open&&life.hygiene()>=45&&life.nutrition()>=30)infection--;
            else damage(.5f);
        }
        if(!open&&infection==0&&mold<3&&!sick()&&ticks%600==0&&life.nutrition()>50&&entity.getHealth()<entity.getMaxHealth()){
            if(life.spendNutrition(4))entity.heal(1);
        }
    }
    /** The oldest light (not deep) wound that is not a burn closes one level. */
    private void healMild(){for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(w.kind()==WoundKind.SCORCH||severe(w))continue;
            healWound(i);refreshWounds();return;}}
    private boolean healBurn(){for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(w.kind()!=WoundKind.SCORCH||severe(w))continue;
            healWound(i);refreshWounds();return true;}return false;}
    private void healWound(int index){var wound=wounds.get(index);
        if(wound.severity()==1){wounds.remove(index);entity.status().marks().healed(wound);}
        else wounds.set(index,wound.heal());}
    private void damage(float amount){entity.hurtInternal(entity.damageSources().generic(),amount);}
    /** Light enough for sugar-water saliva to close: anything short of a deep wound (komicolle 11518838; C2: 重伤治不了). */
    public static boolean lickable(BodyWound w){return !severe(w);}
    public boolean canLickMend(){return wounds.stream().anyMatch(BodyLife::lickable);}
    /** True while a wound is too deep for a lick to help (deep tear or dent, serious burn). */
    public boolean hasUnlickable(){return wounds.stream().anyMatch(w->!lickable(w));}
    /** A finished lick: the oldest mild wound closes one level and a little health returns. Deep wounds are left alone. */
    public boolean lickMend(){
        if(entity.level().isClientSide||!entity.isAlive())return false;
        for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(!lickable(w))continue;
            healWound(i);
            refreshWounds();entity.heal(2);return true;}
        return false;
    }
    public boolean treatInfection(){
        if(!treatmentAllowed()||infection==0)return false;
        infection=Math.max(0,infection-4);
        entity.syncBody();entity.mind().felt(EmotionEvent.TREATED); entity.showReaction("happy","treated",80);return true;
    }
    public void died(){died(null);}
    public void died(DamageSource source){died(source,false);}
    public void died(DamageSource source,boolean stemDepletion){died(source,stemDepletion,false);}
    public void died(DamageSource source,boolean stemDepletion,boolean sukkiriExhaustion){
        if(settled||entity.level().isClientSide)return;
        var remains=RemainsEntity.snapshot(entity,source,stemDepletion,sukkiriExhaustion);
        if(!RemainsEntity.place(entity.level(),entity.position(),entity.yBodyRot,remains,false))return;
        settled=true;entity.syncBody();
    }
    public CompoundTag save(){var tag=BodyWound.write(wounds);tag.putInt("Version",3);if(!wounds.isEmpty())WoundMigration.record(tag,
            WoundSurface.get(entity.subspecies().assetId()));tag.putInt("ReleaseCursor",releaseCursor);tag.putInt("HealthVersion",1);tag.putInt("Infection",infection);
        // Preserve the old integer path for saved bodies, without retaining the removed bandage effect.
        tag.putInt("Bandage",0);tag.putInt("Ticks",ticks);tag.putBoolean("Settled",settled);
        if(deathFood>=0)tag.putFloat("PendingFood",deathFood);
        tag.putInt("Mold",mold);tag.putInt("MoldExposure",moldExposure);tag.putBoolean("CoreHurt",coreHurt);
                tag.putBoolean("FootBurned",footBurned);tag.putInt("FootScorch",footScorch);tag.putInt("Toxin",toxin);tag.putInt("MoldSpread",moldSpread);tag.putInt("HairLoss",hairLoss);
        tag.putInt("Dying",dying);tag.putString("DyingCause",dyingCause);
        // The centre filling is the sweetest part; unease before death sweetens it further (docs/EMOTION_CHAIN.md).
        if(!coreHurt)tag.putInt("CoreSweetness",Math.min(100,entity.mind().fillingSweetness()+25));return tag;}
    public void remapSurface(String previous){
        if(wounds.isEmpty()||previous.equals(entity.subspecies().assetId()))return;
        var old=WoundSurface.get(previous);var current=WoundSurface.get(entity.subspecies().assetId());
        for(int i=0;i<wounds.size();i++){var wound=wounds.get(i);if(!old.valid(wound))continue;
            var migrated=current.remap(wound,old.anchor(wound),old.normal(wound.triangle()),old.bounds());
            wounds.set(i,migrated);
        }
        refreshWounds();
    }
    public void load(CompoundTag tag){wound=Math.clamp(tag.getInt("Wound"),0,BodyConstants.WOUND_CAP);
        // Old Bandage time is deliberately ignored: loading a save must not restore the removed treatment.
        infection=Math.clamp(tag.getInt("Infection"),0,8);ticks=Math.max(0,tag.getInt("Ticks"));settled=tag.getBoolean("Settled");
        mold=Math.clamp(tag.getInt("Mold"),0,8);moldExposure=Math.clamp(tag.getInt("MoldExposure"),0,1199);
        burns=Math.clamp(tag.getInt("Burns"),0,8);hairLoss=tag.getInt("HairLoss")&7;
        // Saves from before the centre filling existed carry no flag: their core is unhurt.
        coreHurt=tag.getBoolean("CoreHurt");footBurned=tag.getBoolean("FootBurned");footScorch=Math.clamp(tag.getInt("FootScorch"),0,12);toxin=
                Math.clamp(tag.getInt("Toxin"),0,12);moldSpread=Math.clamp(tag.getInt("MoldSpread"),0,12000);
        dying=Math.clamp(tag.getInt("Dying"),0,DYING_TICKS);dyingCause=tag.getString("DyingCause");if(dying>0)coreHurt=true;
        releaseCursor=Math.max(0,tag.getInt("ReleaseCursor"));wounds.clear();var savedWounds=BodyWound.read(tag);
        if(!savedWounds.isEmpty()){var mesh=WoundSurface.get(entity.subspecies().assetId());for(var wound:savedWounds){var restored=
                WoundMigration.restore(entity.subspecies().assetId(),mesh,tag,wound);if(restored!=null&&mesh.valid(restored))wounds.add(restored);}}
        refreshWounds();
        deathFood=tag.contains("PendingFood")&&Float.isFinite(tag.getFloat("PendingFood"))?Math.max(0,tag.getFloat("PendingFood")):-1;
        if(tag.getInt("HealthVersion")==0&&tag.contains("Filling")&&!settled&&entity.getHealth()>0){
            float before=entity.getHealth();
            float converted=Math.min(before,entity.getMaxHealth()*Math.clamp(tag.getInt("Filling")/12f,0,1));
            // Migration never kills a loaded live body without its death transaction, or revives a dying one.
            float liveMinimum=entity.profile().houseBody()?.01f:1;
            entity.setHealth(coreHurt?Math.min(before,Math.max(liveMinimum,converted)):Math.max(liveMinimum,converted));
        }
        if(dying>0&&deathFood<0)freezeFood();
        updateMobility();
    }
}
