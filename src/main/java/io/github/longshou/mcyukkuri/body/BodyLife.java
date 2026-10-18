package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Finite body resources; vanilla owns the original hit and this class owns subsequent wound consequences. */
public final class BodyLife {
    private final java.util.LinkedHashMap<java.util.UUID, java.util.UUID> knownPortions = new java.util.LinkedHashMap<>();
    private final YukkuriEntity entity;
    private int filling=BodyConstants.MAX_FILLING,wound,infection,bandage,ticks;
    private int mold,moldExposure;
    private int burns,hairLoss;
    private final java.util.ArrayList<BodyWound> wounds=new java.util.ArrayList<>();
    private int releaseCursor;
    private WoundVisibility.State displayState;
    private boolean settled,internalDamage;
    /** Centre filling (中樞餡): the sweetest core of the body. While it is unhurt ordinary damage cannot kill. */
    private boolean coreHurt;
    /** A baked sole never regains its spring (komicolle 11518802): permanent. footScorch accumulates sole burns. */
    private boolean footBurned;
    private int footScorch,toxin,moldSpread;
    /** Ticks left of the short dying state after the core is hurt (0 = not dying); see startDying. */
    private int dying;
    private String dyingCause="";
    private DamageSource dyingSource;
    private boolean finishing;
    /** The blow analysed by guardCore, consumed when its wound is recorded (InjuryModel). */
    private InjuryModel.Strike strike;
    /** Length of the dying state: long enough for a last look and line, short enough not to be a second life. */
    public static final int DYING_TICKS=80;
    /** Called once when a body starts dying (cause: poison, mold, non_yukkuri, core_front, ...). The last-words package hooks in here. */
    public interface DyingListener{void dying(YukkuriEntity entity,String cause,int ticks);}
    public static final java.util.List<DyingListener> DYING_LISTENERS=new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Filling at or below this many portions leaves the body too weak to run or jump (it still lives). */
    public static final int WEAK_FILLING=4;
    /** Poison load (bitter, spicy, toxic food): 1-4 is bearable, 5+ makes it sick, 9+ reaches the core. */
    public static final int SICK_TOXIN=5,LETHAL_TOXIN=9;
    /** Mould stages: 1-2 early, 3-5 middle (the remover still cures both), 6-7 late (one dose sets it back to early), 8 terminal. */
    public static final int MOLD_TERMINAL=8,MOLD_CURABLE=5,MOLD_LATE_RESET=2;
    private static final net.minecraft.resources.ResourceLocation MOBILITY=io.github.longshou.mcyukkuri.MCYukkuri.id("body_mobility");
    public BodyLife(YukkuriEntity entity){this.entity=entity;}
    public int filling(){return filling;}
    public BodyCondition condition() {
        return new BodyCondition(wound, burns, infection, mold, filling, weak(), dying(), sick(), footBurned);
    }
    public int wound(){return wound;}
    public int infection(){return infection;}
    public int bandage(){return bandage;}
    public int mold(){return mold;}
    public int burns(){return burns;}
    public int hairLoss(){return hairLoss;}
    public boolean coreIntact(){return !coreHurt;}
    public boolean dying(){return dying>0;}
    public int dyingTicks(){return dying;}
    public String dyingCause(){return dyingCause;}
    /** A deep wound lies open and so little filling is left that the centre filling shows. */
    public boolean coreExposed(){return filling<=BodySigns.CORE_SHOWS&&wounds.stream().anyMatch(BodySigns::deep);}
    /** Regions that have a deep wound: blows there can reach the core. */
    public java.util.List<BodyPart> deepParts(){var mesh=WoundSurface.get(entity.subspecies().assetId());return wounds.stream().filter(w->mesh.valid(w)&&BodySigns.deep(w)).map(w->BodyPart.of(mesh,w)).distinct().toList();}
    /** The core chance an ordinary cut or heavy blow through a deep wound has right now. */
    public double coreChance(){return InjuryModel.throughWound(filling,false)*((entity.subspeciesFlags()&io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.DEIBU_FLAG)!=0?.5:1);}
    /** Lasting states drawn on the skin (synced): mould stage, baked sole, broken core, dying. */
    public int signs(){return BodySigns.pack(mold,footBurned,coreHurt,dying>0);}
    public boolean footBurned(){return footBurned;}
    public int toxin(){return toxin;}
    /** Too little filling left to move properly (the core still holds it alive). */
    public boolean sick(){return toxin>=SICK_TOXIN;}
    public boolean weak(){return filling<=WEAK_FILLING&&filling>0;}
    public java.util.List<BodyWound> wounds(){return java.util.List.copyOf(wounds);}
    private void refreshWounds(){wound=Math.min(8,wounds.stream().filter(w->w.kind()!=WoundKind.SCORCH).mapToInt(BodyWound::severity).sum());burns=Math.min(8,wounds.stream().filter(w->w.kind()==WoundKind.SCORCH).mapToInt(BodyWound::severity).sum());entity.syncBody();}
    public boolean cutHair(int parts,net.minecraft.world.entity.player.Player player){
        parts&=7;int cut=parts&~hairLoss;if(cut==0||entity.level().isClientSide||!entity.isAlive())return false;
        var stack=new ItemStack(ModItems.TRIMMED_HAIR.get());
        final int removed=cut;
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Source",entity.getUUID());tag.putString("Variant",entity.getVariant().assetId());
            tag.putInt("HairParts",removed);tag.putFloat("SourceScale",entity.growthScale()*entity.getScale()*(entity.profile().modelScale()));});
        var drop=new net.minecraft.world.entity.item.ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),stack);
        if(!entity.level().addFreshEntity(drop))return false;
        hairLoss|=cut;refreshDisplaySites();entity.syncBody();entity.mind().felt(EmotionEvent.FRIGHTENED); entity.showReaction("surprise","afraid",100);entity.life().personality().confiscated(player.getUUID());return true;
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
        boolean needHealth=entity.getHealth()<entity.getMaxHealth(),needFilling=filling<12;
        if(!skin&&!needHealth&&!needFilling){if(severeLeft)entity.showReaction("teary","hurt",60);return false;}
        wounds.removeIf(w->!severe(w));bandage=0;filling=BodyConstants.MAX_FILLING;
        refreshWounds();entity.setHealth(Math.max(entity.getHealth(),severeLeft?Math.min(entity.getMaxHealth(),entity.getHealth()+entity.getMaxHealth()/2):entity.getMaxHealth()));
        if (!severeLeft) entity.mind().felt(EmotionEvent.TREATED); entity.showReaction(severeLeft?"teary":"happy",severeLeft?"hurt":"treated",80);return true;
    }
    /** Wheat-flour paste fills in severe skin loss: severity 3 becomes 2, which juice (or time) can then finish. */
    public boolean patchSkin(){
        if(!treatmentAllowed()||wounds.stream().noneMatch(BodyLife::severe))return false;
        for(int i=0;i<wounds.size();i++)if(severe(wounds.get(i)))wounds.set(i,WoundVisibility.place(entity,wounds.get(i).heal()));
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
    public boolean knowsSource(ItemStack stack) {
        var tag=FillingResource.data(stack);
        return tag.hasUUID("Portion") && tag.hasUUID("Source")
                && tag.getUUID("Source").equals(knownPortions.get(tag.getUUID("Portion")));
    }
    public void learnSource(ItemStack stack) {
        var tag=FillingResource.data(stack);
        if(!FillingResource.isFilling(stack)||!tag.hasUUID("Portion")||!tag.hasUUID("Source"))return;
        knownPortions.put(tag.getUUID("Portion"),tag.getUUID("Source"));
        while(knownPortions.size()>64)knownPortions.remove(knownPortions.keySet().iterator().next());
    }
    public boolean tellSource(YukkuriEntity listener, ItemStack stack) {
        if(entity.level().isClientSide || !entity.isAlive() || !listener.isAlive()
                || entity.level()!=listener.level() || entity.distanceToSqr(listener)>16
                || !entity.hasLineOfSight(listener) || entity.life().isSleeping() || listener.life().isSleeping()
                || !knowsSource(stack) || listener.body().knowsSource(stack))return false;
        listener.body().learnSource(stack);listener.mind().felt(EmotionEvent.WOUND_DISTRESS); listener.showReaction("surprise","wounded",60);return true;
    }
    public void contaminated(){infection=Math.min(8,infection+1);}
    /** Eating a centre filling: the sweetest food there is. Filling and health come back, and unease melts (project design). */
    public void ateCore(int sweetness){
        if(entity.level().isClientSide||!entity.isAlive()||dying>0)return;
        filling=Math.min(12,filling+3);entity.heal(4);entity.mind().add(-(10+sweetness/5f),"core_filling");entity.syncBody();
    }
    /** Spice hurts only when the poison load is already high; a bearable dose just stings (komicolle 11518847). */
    public void irritated(){
        if(toxin<SICK_TOXIN)return;
        releaseFilling("vomited");internalDamage=true;
        try{entity.hurt(new DamageSource(entity.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(io.github.longshou.mcyukkuri.life.Consumables.SPICE_DAMAGE)),1);}
        finally{internalDamage=false;}
    }
    /** Bitter, spicy or poisonous food: a little is endured, a lot makes the body sick, a great deal reaches the core. */
    public void poisoned(int dose){
        if(entity.level().isClientSide||!entity.isAlive()||settled||dose<=0)return;
        toxin=Math.min(12,toxin+dose);
        if(toxin>=LETHAL_TOXIN){damageCore("poison");return;}
        if(toxin>=SICK_TOXIN){releaseFilling("vomited");damage(1);entity.mind().felt(EmotionEvent.BITTER_TASTE); entity.showReaction("teary","bitter",100);}
        else { entity.mind().felt(EmotionEvent.BITTER_TASTE); entity.showReaction("surprise","bitter",80); }
    }
    public void setToxin(int value){toxin=Math.clamp(value,0,12);}
    /** Test and scenario tool: set the ordinary filling directly (sagging, a bare core, weakness). */
    public void setFilling(int value){if(settled)return;filling=Math.clamp(value,0,BodyConstants.MAX_FILLING);updateMobility();entity.syncBody();}
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
        coreHurt=true;dying=DYING_TICKS;dyingCause=cause;dyingSource=source;
        if(entity.getHealth()<1)entity.setHealth(1);
        entity.life().interrupt();entity.getNavigation().stop();
        entity.showReaction("blank_cry","dying",DYING_TICKS);
        updateMobility();entity.syncBody();
        io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.info("[body] {} #{} core hurt ({}), dying",entity.getVariant().assetId(),entity.getId(),cause);
        for(var listener:DYING_LISTENERS){try{listener.dying(entity,cause,DYING_TICKS);}catch(RuntimeException ex){io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.warn("Dying listener failed",ex);}}
    }
    private void finishDying(){
        finishing=true;
        try{
            var source=dyingSource!=null?dyingSource:entity.damageSources().generic();
            if(!entity.hurt(source,1000)&&entity.isAlive())entity.hurt(entity.damageSources().genericKill(),Float.MAX_VALUE);
        }finally{finishing=false;}
    }
    /**
     * Called first by {@code YukkuriEntity.hurt}: the centre filling decides whether the body dies (komicolle 11518802).
     * <ul><li>Attrition (bleeding, infection, mould, hunger, cold) leaves 1 health and then spends filling; with none left it kills.</li>
     * <li>A blow is analysed by {@link InjuryModel}: crushing, lava, a hard fall or a large explosion kill outright; a piercing blow or
     * a cut through a deep wound may reach the core, which starts the dying state; otherwise a would-be lethal blow is turned aside,
     * leaving 1 health, worsening the wound it lands on and squeezing filling out of an open wound there.</li>
     * <li>Drowning dissolves the core; {@code /kill} reaches it; an overwhelming blow with no attacker (old age) ends the body with the
     * core left whole.</li></ul>
     */
    public float guardCore(DamageSource source,float amount){
        strike=null;
        if(entity.level().isClientSide||amount<=0||settled||finishing||coreHurt||dying>0)return amount;
        if(entity.repeatHitIgnored(source,amount))return amount;
        float health=entity.getHealth();
        if(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)){coreHurt=true;return amount;}
        boolean attrition=internalDamage||InjuryModel.attrition(source);
        if(attrition){
            if(filling==0||health-amount>0)return amount;
            if(health>1)return health-1;
            filling--;entity.syncBody();// wasting away: the ordinary filling is spent, then the core goes
            return 0;
        }
        if(source.is(DamageTypeTags.IS_DROWNING)){if(health-amount<=0&&filling>0)coreHurt=true;return amount;}
        if(amount>=entity.getMaxHealth()*2&&source.getEntity()==null&&!source.is(DamageTypeTags.IS_FIRE)&&!InjuryModel.crush(source))return amount;
        var s=InjuryModel.analyse(entity,source,amount,wounds);
        if(s==null){if(health-amount<=0&&filling>0)coreHurt=true;return amount;}// no skin on the line: the old rule
        strike=s;
        if(filling==0)return amount;// nothing left around the core
        InjuryModel.decide(entity,s,health,filling);
        switch(s.outcome){
            case INSTANT -> {coreHurt=true;return Math.max(amount,health);}
            case DYING -> {return Math.min(amount,Math.max(0,health-1));}
            default -> {
                if(health-amount>0)return amount;
                s.blocked=true;return Math.max(0,health-1);
            }
        }
    }
    /** True while the last analysed blow was turned aside or is fatal later: the hurt must still play though no health is lost. */
    /** Region of the blow being applied right now (between guardCore and injured), for the accessory (headwear.Headwear.struck). */
    public BodyPart strikePart(){return strike==null?null:strike.part;}
    public boolean absorbedBlow(){return strike!=null&&(strike.blocked||strike.outcome==InjuryModel.Outcome.DYING);}
    /** Debit only after the world accepts the physical drop. */
    public boolean releaseFilling(String method) {
        var outlet=(method.equals("wound")||method.equals("squeeze"))?wounds.stream().filter(w->w.kind()==WoundKind.TEAR).reduce((a,b)->b).orElse(null):null;
        return releaseFilling(method,outlet);
    }
    public boolean releaseFilling(String method,BodyWound outlet) {
        if(entity.level().isClientSide || filling==0 || settled)return false;
        if(outlet!=null&&(!wounds.contains(outlet)||outlet.kind()!=WoundKind.TEAR))return false;
        var stack=FillingResource.portion(entity,method);
        var dropped=io.github.longshou.mcyukkuri.registry.ModEntities.MATERIAL_DROP.get().create(entity.level());
        var outward=method.equals("vomited")?MouthPlacement.outward(entity):outlet==null?net.minecraft.world.phys.Vec3.ZERO:WoundPlacement.outward(entity,outlet);
        dropped.setPos(method.equals("vomited")?MouthPlacement.position(entity).add(outward.scale(.07*entity.growthScale())):outlet==null?entity.position():WoundPlacement.outlet(entity,outlet).add(outward.scale(.025*entity.growthScale())));dropped.setItem(stack);dropped.setPickUpDelay(20);
        dropped.setDeltaMovement(outward.scale(.07*entity.growthScale()).add((entity.getRandom().nextDouble()-.5)*.02,.035,(entity.getRandom().nextDouble()-.5)*.02));
        if(outlet!=null)CustomData.update(DataComponents.CUSTOM_DATA,stack,t->t.putUUID("WoundId",outlet.id()));
        if(!placeOutsideBlocks(dropped))return false;
        if(!entity.level().addFreshEntity(dropped))return false;
        filling--;if(outlet!=null){int index=wounds.indexOf(outlet);if(index>=0)wounds.set(index,outlet.released(entity.level().getGameTime()));}entity.syncBody();FillingResource.witnessed(entity,stack);return true;
    }
    private boolean placeOutsideBlocks(net.minecraft.world.entity.item.ItemEntity drop){
        var box=drop.getBoundingBox();double top=box.minY;
        for(var shape:entity.level().getBlockCollisions(drop,box))top=Math.max(top,shape.bounds().maxY+.002);
        // Raise by the small floor overlap. A blocked wall/ceiling outlet waits without spending filling.
        if(top-box.minY>.30)return false;
        if(top>box.minY)drop.setPos(drop.getX(),drop.getY()+top-box.minY,drop.getZ());
        return !entity.level().getBlockCollisions(drop,drop.getBoundingBox()).iterator().hasNext();
    }
    public void injured(DamageSource source,float lost){
        if(!entity.isAlive())return;
        recordInjury(source,lost);
    }
    /** Called during vanilla's death callback, before the body is transferred. */
    public void fatalInjury(DamageSource source,float lost){recordInjury(source,lost);}
    private void recordInjury(DamageSource source,float lost){
        var s=strike;strike=null;
        if(finishing||internalDamage||settled||entity.level().isClientSide||source.is(DamageTypeTags.IS_DROWNING)||InjuryModel.attrition(source))return;
        // A blow that bypassed guardCore (e.g. a fall reported after the fact) is analysed here.
        if(s==null||s.source!=source){if(lost<=0)return;s=InjuryModel.analyse(entity,source,lost,wounds);if(s==null)return;}
        var mesh=WoundSurface.get(entity.subspecies().assetId());
        BodyWound placed=null;
        if(s.blocked){
            // Beating the same place opens it up: the nearest lighter wound of the same kind there worsens instead of a new one.
            var p=mesh.rest(s.hit.triangle());int best=-1;float distance=Float.MAX_VALUE;
            for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(!w.kind().equals(s.kind)||w.severity()>=3||!mesh.valid(w)||BodyPart.of(mesh,w)!=s.part)continue;
                float d=mesh.anchor(w).distanceSquared(p);if(d<distance){distance=d;best=i;}}
            if(best>=0){var old=wounds.get(best);placed=WoundVisibility.place(entity,old.withSeverity(old.severity()+1));wounds.set(best,placed);checkSole(placed,mesh);refreshWounds();}
        }
        if(placed==null){placed=new BodyWound(java.util.UUID.randomUUID(),s.kind,s.hit.triangle(),s.hit.u(),s.hit.v(),s.severity,entity.profile().portionScale(),0,0);insertWound(placed);}
        if(s.blocked&&!source.is(DamageTypeTags.IS_FIRE)){
            // The blow presses filling out of an open wound in that region (komicolle: 餡子流失過多 also kills).
            final var part=s.part;
            var outlet=wounds.stream().filter(w->w.kind()==WoundKind.TEAR&&BodySigns.open(w)&&mesh.valid(w)&&BodyPart.of(mesh,w)==part).reduce((a,b)->b).orElse(null);
            if(outlet!=null||wounds.stream().anyMatch(w->BodySigns.open(w)&&mesh.valid(w)&&BodyPart.of(mesh,w)==part))
                releaseFilling("squeeze",outlet);
        }
        entity.syncBody();
        if(s.outcome==InjuryModel.Outcome.DYING)startDying(source,"core_"+s.part.id());
        else if(dying==0){ entity.mind().felt(EmotionEvent.WOUND_DISTRESS); entity.showReaction("surprise","wounded",80); }
    }
    /** A point on the underside (lowest fifth of the skin) where a hot floor or flame touches the sole. */
    private WoundSurface.Hit soleSite(){return InjuryModel.soleSite(entity);}
    /** Test and scenario tool: a wound of the given kind and severity on a region, through the normal wound logic. */
    public boolean woundAt(BodyPart part,String kind,int severity){
        if(entity.level().isClientSide||!entity.isAlive()||settled)return false;
        var mesh=WoundSurface.get(entity.subspecies().assetId());var hit=part.site(mesh,new java.util.Random(entity.getRandom().nextLong()));if(hit==null)return false;
        insertWound(new BodyWound(java.util.UUID.randomUUID(),kind,hit.triangle(),hit.u(),hit.v(),Math.clamp(severity,1,3),entity.profile().portionScale(),0,0));
        entity.syncBody();return true;
    }
    /** Burn the sole badly enough to lose its spring for good (scenario and status tool; real fire does it through insertWound). */
    public boolean burnFoot(){
        var hit=soleSite();if(hit==null||entity.level().isClientSide||!entity.isAlive())return false;
        insertWound(new BodyWound(java.util.UUID.randomUUID(),WoundKind.SCORCH,hit.triangle(),hit.u(),hit.v(),3,entity.profile().portionScale(),0,0));
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
            if(entity.level() instanceof ServerLevel level)level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,entity.getX(),entity.getY()+.05,entity.getZ(),6,.1,.02,.1,.01);
            updateMobility();
        }
    }
    private void updateMobility(){
        var speed=entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);if(speed==null)return;
        double factor=dying>0?0:(footBurned?.3:1)*(weak()?.6:1);
        if(factor>=1)speed.removeModifier(MOBILITY);
        else speed.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(MOBILITY,factor-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    /** At capacity strengthen the nearest existing injury; never move an old scar. */
    public void addWound(BodyWound wound){if(!entity.isAlive())return;insertWound(wound);}
    private void insertWound(BodyWound wound){if(entity.level().isClientSide||settled)return;var mesh=WoundSurface.get(entity.subspecies().assetId());if(!mesh.valid(wound))return;
        checkSole(wound,mesh);
        if(wounds.size()<8)wounds.add(WoundVisibility.place(entity,wound));else{int best=0;float distance=Float.MAX_VALUE;var p=mesh.anchor(wound);
            for(int i=0;i<wounds.size();i++){var w=wounds.get(i);float d=mesh.anchor(w).distanceSquared(p)+(w.kind().equals(wound.kind())?0:10000);if(d<distance){distance=d;best=i;}}
            var old=wounds.get(best);wounds.set(best,WoundVisibility.place(entity,old.withSeverity(Math.min(3,old.severity()+1))));}
        refreshWounds();
    }
    /** Cheap state comparison each tick; geometry is queried only when an exterior state changes. */
    private void refreshDisplaySites(){
        displayState=WoundVisibility.state(entity);
        for(int i=0;i<wounds.size();i++)wounds.set(i,WoundVisibility.place(entity,wounds.get(i)));
    }
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive())return;
        if(!wounds.isEmpty()&&!WoundVisibility.state(entity).equals(displayState)){refreshDisplaySites();entity.syncBody();}
        if(dying>0){entity.getNavigation().stop();if(--dying==0)finishDying();else if(dying%20==0)entity.syncBody();return;}
        if(entity.isNoAi())return;ticks++;
        if(bandage>0)bandage--;
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
        if(burns>0&&ticks%400==0){if(life.hygiene()>=40&&life.nutrition()>=30){healOne(true);life.spendNutrition(1);}else if(burns>=4)damage(.5f);}
        if(wound>0&&ticks%200==0){
            if(bandage==0){
                // Only an open cut (severity 2+) leaks; a small cut or a bruise just smarts.
                var leaking=wounds.stream().filter(w->w.kind()==WoundKind.TEAR&&BodySigns.open(w)).toList();
                if(!leaking.isEmpty()){var outlet=leaking.get(Math.floorMod(releaseCursor++,leaking.size()));releaseFilling("wound",outlet);}
                if(open&&life.hygiene()<40)infection=Math.min(8,infection+1);
                // Wound damage must not create another wound or replay the initial hit.
                if(open)damage(filling==0?2:.5f);
            } else if(life.nutrition()>=30&&life.hygiene()>=30){healOne(false);life.spendNutrition(2);entity.heal(1);}
        }
        // Light wounds close by themselves on a fed, clean body; deep wounds need a bandage or flour paste.
        if(wound>0&&bandage==0&&infection==0&&ticks%400==0&&life.nutrition()>=30&&life.hygiene()>=30)healMild();
        if(infection>0&&ticks%400==0){
            if((wound==0||bandage>0)&&life.hygiene()>=45&&life.nutrition()>=30)infection--;
            else damage(.5f);
        }
        if(!open&&infection==0&&filling<12&&ticks%600==0&&life.nutrition()>50){life.spendNutrition(4);filling++;entity.syncBody();}
        if(!open&&infection==0&&mold<3&&!sick()&&ticks%400==0&&life.nutrition()>=40&&entity.getHealth()<entity.getMaxHealth())entity.heal(1);
    }
    /** The oldest light (not deep) wound that is not a burn closes one level. */
    private void healMild(){for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(w.kind()==WoundKind.SCORCH||BodySigns.deep(w))continue;if(w.severity()==1)wounds.remove(i);else wounds.set(i,WoundVisibility.place(entity,w.heal()));refreshWounds();return;}}
    private void healOne(boolean scorch){for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if((w.kind()==WoundKind.SCORCH)!=scorch)continue;if(w.severity()==1)wounds.remove(i);else wounds.set(i,WoundVisibility.place(entity,w.heal()));break;}refreshWounds();}
    /** True while the body is applying its own bleeding, infection or mould damage (not an attack). */
    public boolean internalDamage(){return internalDamage;}
    private void damage(float amount){internalDamage=true;try{entity.hurt(entity.damageSources().generic(),amount);}finally{internalDamage=false;}}
    /** Light enough for sugar-water saliva to close: anything short of a deep wound (komicolle 11518838; C2: 重伤治不了). */
    public static boolean lickable(BodyWound w){return !BodySigns.deep(w);}
    public boolean canLickMend(){return wounds.stream().anyMatch(BodyLife::lickable);}
    /** True while a wound is too deep for a lick to help (deep tear or dent, serious burn). */
    public boolean hasUnlickable(){return wounds.stream().anyMatch(w->!lickable(w));}
    /** A finished lick: the oldest mild wound closes one level and a little health returns. Deep wounds are left alone. */
    public boolean lickMend(){
        if(entity.level().isClientSide||!entity.isAlive())return false;
        for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(!lickable(w))continue;
            if(w.severity()==1)wounds.remove(i);else wounds.set(i,WoundVisibility.place(entity,w.heal()));
            refreshWounds();entity.heal(2);return true;}
        return false;
    }
    public boolean treat(boolean medicine){
        if(!treatmentAllowed())return false;
        if(medicine){if(infection==0)return false;infection=Math.max(0,infection-4);}
        else {if(wound==0||bandage>200)return false;bandage=1200;}
        entity.syncBody();entity.mind().felt(EmotionEvent.TREATED); entity.showReaction("happy","treated",80);return true;
    }
    public void died(){died(null);}
    public void died(DamageSource source){died(source,false);}
    public void died(DamageSource source,boolean stemDepletion){died(source,stemDepletion,false);}
    public void died(DamageSource source,boolean stemDepletion,boolean sukkiriExhaustion){
        if(settled||entity.level().isClientSide)return;
        var remains=io.github.longshou.mcyukkuri.entity.RemainsEntity.snapshot(entity,source,stemDepletion,sukkiriExhaustion);
        if(!io.github.longshou.mcyukkuri.entity.RemainsEntity.place(entity.level(),entity.position(),entity.yBodyRot,remains,false))return;
        settled=true;filling=0;entity.syncBody();
    }
    public CompoundTag save(){var tag=BodyWound.write(wounds);tag.putInt("Version",3);if(!wounds.isEmpty())WoundMigration.record(tag,WoundSurface.get(entity.subspecies().assetId()));tag.putInt("ReleaseCursor",releaseCursor);tag.putInt("Filling",filling);tag.putInt("Infection",infection);
        tag.putInt("Bandage",bandage);tag.putInt("Ticks",ticks);tag.putBoolean("Settled",settled);
        var knowledge=new net.minecraft.nbt.ListTag();knownPortions.forEach((portion,source)->{var entry=new CompoundTag();entry.putUUID("Portion",portion);entry.putUUID("Source",source);knowledge.add(entry);});
        tag.put("KnownPortions",knowledge);tag.putInt("Mold",mold);tag.putInt("MoldExposure",moldExposure);tag.putBoolean("CoreHurt",coreHurt);tag.putBoolean("FootBurned",footBurned);tag.putInt("FootScorch",footScorch);tag.putInt("Toxin",toxin);tag.putInt("MoldSpread",moldSpread);tag.putInt("HairLoss",hairLoss);
        tag.putInt("Dying",dying);tag.putString("DyingCause",dyingCause);
        // The centre filling is the sweetest part; unease before death sweetens it further (docs/EMOTION_CHAIN.md).
        if(!coreHurt)tag.putInt("CoreSweetness",Math.min(100,entity.mind().fillingSweetness()+25));return tag;}
    public void remapSurface(String previous){
        if(wounds.isEmpty()||previous.equals(entity.subspecies().assetId()))return;
        var old=WoundSurface.get(previous);var current=WoundSurface.get(entity.subspecies().assetId());
        for(int i=0;i<wounds.size();i++){var wound=wounds.get(i);if(!old.valid(wound))continue;
            var migrated=current.remap(wound,old.anchor(wound),old.normal(wound.triangle()),old.bounds());
            if(old.valid(wound.display())){var site=wound.display();migrated=migrated.withDisplay(current.remapSite(old.anchor(site.hit()),old.normal(site.triangle()),old.bounds()));}
            wounds.set(i,migrated);
        }
        if(!entity.level().isClientSide)refreshDisplaySites();
        refreshWounds();
    }
    public void load(CompoundTag tag){filling=tag.contains("Filling")?Math.clamp(tag.getInt("Filling"),0,BodyConstants.MAX_FILLING):BodyConstants.MAX_FILLING;wound=Math.clamp(tag.getInt("Wound"),0,BodyConstants.WOUND_CAP);
        infection=Math.clamp(tag.getInt("Infection"),0,8);bandage=Math.clamp(tag.getInt("Bandage"),0,1200);ticks=Math.max(0,tag.getInt("Ticks"));settled=tag.getBoolean("Settled");
        mold=Math.clamp(tag.getInt("Mold"),0,8);moldExposure=Math.clamp(tag.getInt("MoldExposure"),0,1199);
        burns=Math.clamp(tag.getInt("Burns"),0,8);hairLoss=tag.getInt("HairLoss")&7;
        // Saves from before the centre filling existed carry no flag: their core is unhurt.
        coreHurt=tag.getBoolean("CoreHurt");footBurned=tag.getBoolean("FootBurned");footScorch=Math.clamp(tag.getInt("FootScorch"),0,12);toxin=Math.clamp(tag.getInt("Toxin"),0,12);moldSpread=Math.clamp(tag.getInt("MoldSpread"),0,12000);
        dying=Math.clamp(tag.getInt("Dying"),0,DYING_TICKS);dyingCause=tag.getString("DyingCause");if(dying>0)coreHurt=true;
        releaseCursor=Math.max(0,tag.getInt("ReleaseCursor"));wounds.clear();var savedWounds=BodyWound.read(tag);
        if(!savedWounds.isEmpty()){var mesh=WoundSurface.get(entity.subspecies().assetId());for(var wound:savedWounds){var restored=WoundMigration.restore(entity.subspecies().assetId(),mesh,tag,wound);if(restored!=null&&mesh.valid(restored))wounds.add(restored);}}
        if(!entity.level().isClientSide&&!wounds.isEmpty())refreshDisplaySites();
        refreshWounds();
        knownPortions.clear();for(var raw:tag.getList("KnownPortions",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;if(entry.hasUUID("Portion")&&entry.hasUUID("Source"))knownPortions.put(entry.getUUID("Portion"),entry.getUUID("Source"));
            if(knownPortions.size()==64)break;
        }}
}
