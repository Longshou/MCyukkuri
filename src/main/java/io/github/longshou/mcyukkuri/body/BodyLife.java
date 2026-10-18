package io.github.longshou.mcyukkuri.body;

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
    private int filling=12,wound,infection,bandage,ticks;
    private int mold,moldExposure;
    private int burns,hairLoss;
    private final java.util.ArrayList<BodyWound> wounds=new java.util.ArrayList<>();
    private int releaseCursor;
    private boolean settled,internalDamage;
    /** Centre filling (中樞餡): the sweetest core of the body. While it is unhurt ordinary damage cannot kill. */
    private boolean coreHurt;
    /** A baked sole never regains its spring (komicolle 11518802): permanent. footScorch accumulates sole burns. */
    private boolean footBurned;
    private int footScorch,toxin,moldSpread;
    /** Filling at or below this many portions leaves the body too weak to run or jump (it still lives). */
    public static final int WEAK_FILLING=4;
    /** Poison load (bitter, spicy, toxic food): 1-4 is bearable, 5+ makes it sick, 9+ reaches the core. */
    public static final int SICK_TOXIN=5,LETHAL_TOXIN=9;
    public static final int MOLD_TERMINAL=8,MOLD_CURABLE=3;
    private static final net.minecraft.resources.ResourceLocation MOBILITY=io.github.longshou.mcyukkuri.MCYukkuri.id("body_mobility");
    public BodyLife(YukkuriEntity entity){this.entity=entity;}
    public int filling(){return filling;}
    public int wound(){return wound;}
    public int infection(){return infection;}
    public int bandage(){return bandage;}
    public int mold(){return mold;}
    public int burns(){return burns;}
    public int hairLoss(){return hairLoss;}
    public boolean coreIntact(){return !coreHurt;}
    public boolean footBurned(){return footBurned;}
    public int toxin(){return toxin;}
    /** Too little filling left to move properly (the core still holds it alive). */
    public boolean sick(){return toxin>=SICK_TOXIN;}
    public boolean weak(){return filling<=WEAK_FILLING&&filling>0;}
    public java.util.List<BodyWound> wounds(){return java.util.List.copyOf(wounds);}
    private void refreshWounds(){wound=Math.min(8,wounds.stream().filter(w->!w.kind().equals("scorch")).mapToInt(BodyWound::severity).sum());burns=Math.min(8,wounds.stream().filter(w->w.kind().equals("scorch")).mapToInt(BodyWound::severity).sum());entity.syncBody();}
    public boolean cutHair(int parts,net.minecraft.world.entity.player.Player player){
        parts&=7;int cut=parts&~hairLoss;if(cut==0||entity.level().isClientSide||!entity.isAlive())return false;
        var stack=new ItemStack(ModItems.TRIMMED_HAIR.get());
        final int removed=cut;
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Source",entity.getUUID());tag.putString("Variant",entity.getVariant().assetId());
            tag.putInt("HairParts",removed);tag.putFloat("SourceScale",entity.growthScale()*entity.getScale()*(entity.getVariant().isHouse()?.32f:1));});
        var drop=new net.minecraft.world.entity.item.ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),stack);
        if(!entity.level().addFreshEntity(drop))return false;
        hairLoss|=cut;entity.showReaction("surprise","afraid",100);entity.life().personality().confiscated(player.getUUID());return true;
    }
    /** A wound the skin cannot regrow by juice alone (komicolle 11518847: badly damaged skin also needs wheat-flour paste). */
    private static boolean severe(BodyWound w){return w.severity()>=3&&!w.kind().equals("scar")&&!w.kind().equals("dirt");}
    /**
     * Orange juice repairs skin and filling only (komicolle reply 11520402): teeth, eyes, the hair and the hair ornament are
     * not regrown. Severe skin loss (a severity-3 wound) stays until wheat-flour paste has patched it.
     */
    public boolean restorePhysical() {
        if(!treatmentAllowed())return false;
        boolean skin=wounds.stream().anyMatch(w->!severe(w)),severeLeft=wounds.stream().anyMatch(BodyLife::severe);
        boolean needHealth=entity.getHealth()<entity.getMaxHealth(),needFilling=filling<12;
        if(!skin&&!needHealth&&!needFilling){if(severeLeft)entity.showReaction("teary","hurt",60);return false;}
        wounds.removeIf(w->!severe(w));bandage=0;filling=12;
        refreshWounds();entity.setHealth(Math.max(entity.getHealth(),severeLeft?Math.min(entity.getMaxHealth(),entity.getHealth()+entity.getMaxHealth()/2):entity.getMaxHealth()));
        entity.showReaction(severeLeft?"teary":"happy",severeLeft?"hurt":"treated",80);return true;
    }
    /** Wheat-flour paste fills in severe skin loss: severity 3 becomes 2, which juice (or time) can then finish. */
    public boolean patchSkin(){
        if(!treatmentAllowed()||wounds.stream().noneMatch(BodyLife::severe))return false;
        for(int i=0;i<wounds.size();i++)if(severe(wounds.get(i)))wounds.set(i,wounds.get(i).heal());
        refreshWounds();entity.showReaction("happy","treated",80);return true;
    }
    /** Mould is a death sentence (komicolle 11518802): the remover cures it only early; later it just slows it, and the terminal stage cannot be treated. */
    public boolean removeMold() {
        if(!treatmentAllowed()||mold==0||mold>=MOLD_TERMINAL)return false;
        if(mold<=MOLD_CURABLE){mold=moldExposure=moldSpread=0;entity.showReaction("happy","treated",80);}
        else{mold=Math.max(MOLD_CURABLE,mold-2);moldSpread=0;entity.showReaction("teary","hurt",80);}
        return true;
    }
    public void setMold(int level){mold=Math.clamp(level,0,MOLD_TERMINAL);moldSpread=0;}
    private boolean treatmentAllowed(){return !entity.level().isClientSide&&entity.isAlive()&&!entity.isPanicking()&&!entity.isInWaterOrBubble()&&!entity.isOnFire();}
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
        listener.body().learnSource(stack);listener.showReaction("surprise","wounded",60);return true;
    }
    public void contaminated(){infection=Math.min(8,infection+1);}
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
        if(toxin>=SICK_TOXIN){releaseFilling("vomited");damage(1);entity.showReaction("teary","bitter",100);}
        else entity.showReaction("surprise","bitter",80);
    }
    public void setToxin(int value){toxin=Math.clamp(value,0,12);}
    /** The centre filling is hurt: fatal. Poison, terminal mould and a hit that reaches the middle all end here. */
    public void damageCore(String cause){
        if(entity.level().isClientSide||!entity.isAlive()||settled)return;
        coreHurt=true;
        internalDamage=true;try{entity.hurt(entity.damageSources().generic(),1000);}finally{internalDamage=false;}
    }
    /**
     * Called first by {@code YukkuriEntity.hurt}: damage that would take the last health is turned aside while the core is unhurt and
     * filling remains. Attrition (bleeding, hunger, mould, cold) leaves 1 health and then drains filling; a glancing blow from an
     * attacker (overkill under 2) spares a body that still has more than 1 health. Bigger blows, fire, water, falls and
     * /kill reach the core. With no filling left the core has gone too.
     */
    public float guardCore(DamageSource source,float amount){
        if(entity.level().isClientSide||amount<=0||coreHurt||filling==0||settled)return amount;
        float health=entity.getHealth();
        if(health-amount>0)return amount;
        if(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)){coreHurt=true;return amount;}
        boolean attrition=internalDamage||source.is(net.minecraft.world.damagesource.DamageTypes.STARVE)||source.is(DamageTypeTags.IS_FREEZING);
        if(attrition){
            if(health>1)return health-1;
            filling--;entity.syncBody();// wasting away: the ordinary filling is spent, then the core goes
            return 0;
        }
        if(source.getEntity()!=null&&amount-health<2&&health>1)return health-1;
        coreHurt=true;return amount;
    }
    /** Debit only after the world accepts the physical drop. */
    public boolean releaseFilling(String method) {
        var outlet=(method.equals("wound")||method.equals("squeeze"))?wounds.stream().filter(w->w.kind().equals("tear")).reduce((a,b)->b).orElse(null):null;
        return releaseFilling(method,outlet);
    }
    public boolean releaseFilling(String method,BodyWound outlet) {
        if(entity.level().isClientSide || filling==0 || settled)return false;
        if(outlet!=null&&(!wounds.contains(outlet)||!outlet.kind().equals("tear")))return false;
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
        if(internalDamage||settled||entity.level().isClientSide||lost<=0||source.is(DamageTypeTags.IS_DROWNING)||source.is(net.minecraft.world.damagesource.DamageTypes.STARVE))return;
        boolean underfoot=source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR)||source.is(net.minecraft.world.damagesource.DamageTypes.CAMPFIRE)
                ||source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE)||source.is(net.minecraft.world.damagesource.DamageTypes.LAVA);
        var hit=underfoot?soleSite():WoundPlacement.hit(entity,source);if(hit==null)return;
        String kind=source.is(DamageTypeTags.IS_FIRE)?"scorch":source.is(DamageTypeTags.IS_PROJECTILE)?"tear":"dent";
        int severity=Math.clamp((int)Math.ceil(lost/2),1,3);
        if(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker){var item=attacker.getMainHandItem();String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            if(item.is(net.minecraft.tags.ItemTags.SWORDS)||item.is(net.minecraft.tags.ItemTags.AXES)||java.util.Set.of("care_awl","cutting_knife","pin_tool","hanging_hook").contains(id))kind="tear";
            if(id.equals("heated_awl")){boolean hot=io.github.longshou.mcyukkuri.item.OperationToolItem.isHot(item,entity.level());kind=hot?"scorch":"tear";if(hot)severity=Math.max(2,severity);}
        }
        if(entity.contactPose().equals("ROLL")||entity.contactPose().equals("SLIDE"))kind="scratch";
        if(entity.contactPose().equals("GRIP"))kind="tear";
        if(source.is(io.github.longshou.mcyukkuri.life.Facilities.ABRASION))kind="scratch";
        insertWound(new BodyWound(java.util.UUID.randomUUID(),kind,hit.triangle(),hit.u(),hit.v(),severity,entity.getVariant().isHouse()?.35f:1,0,0));
        entity.syncBody();entity.showReaction("surprise","wounded",80);
    }
    /** A point on the underside (lowest fifth of the skin) where a hot floor or flame touches the sole. */
    private WoundSurface.Hit soleSite(){
        var mesh=WoundSurface.get(entity.subspecies().assetId());var b=mesh.bounds();float limit=b[1]+(b[4]-b[1])*.2f;
        var low=new java.util.ArrayList<Integer>();
        for(int t=0;t+2<mesh.skinCount;t+=3)if((mesh.rest(t).y+mesh.rest(t+1).y+mesh.rest(t+2).y)/3<=limit)low.add(t);
        if(low.isEmpty())return null;
        return new WoundSurface.Hit(low.get(entity.getRandom().nextInt(low.size())),.33f,.33f,0);
    }
    /** Burn the sole badly enough to lose its spring for good (scenario and status tool; real fire does it through insertWound). */
    public boolean burnFoot(){
        var hit=soleSite();if(hit==null||entity.level().isClientSide||!entity.isAlive())return false;
        insertWound(new BodyWound(java.util.UUID.randomUUID(),"scorch",hit.triangle(),hit.u(),hit.v(),3,entity.getVariant().isHouse()?.35f:1,0,0));
        return footBurned;
    }
    /** Test and scenario only: the sole has no cure in the setting. */
    public void mendFoot(){footBurned=false;footScorch=0;updateMobility();}
    private void checkSole(BodyWound wound,WoundSurface mesh){
        if(!wound.kind().equals("scorch"))return;
        var b=mesh.bounds();if(mesh.anchor(wound).y>b[1]+(b[4]-b[1])*.22f)return;
        footScorch=Math.min(12,footScorch+wound.severity());
        if(!footBurned&&(wound.severity()>=3||footScorch>=4)){
            footBurned=true;entity.showReaction("cry","sole_burned",120);
            if(entity.level() instanceof ServerLevel level)level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,entity.getX(),entity.getY()+.05,entity.getZ(),6,.1,.02,.1,.01);
            updateMobility();
        }
    }
    private void updateMobility(){
        var speed=entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);if(speed==null)return;
        double factor=(footBurned?.3:1)*(weak()?.6:1);
        if(factor>=1)speed.removeModifier(MOBILITY);
        else speed.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(MOBILITY,factor-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    /** At capacity strengthen the nearest existing injury; never move an old scar. */
    public void addWound(BodyWound wound){if(!entity.isAlive())return;insertWound(wound);}
    private void insertWound(BodyWound wound){if(entity.level().isClientSide||settled)return;var mesh=WoundSurface.get(entity.subspecies().assetId());if(!mesh.valid(wound))return;
        checkSole(wound,mesh);
        if(wounds.size()<8)wounds.add(wound);else{int best=0;float distance=Float.MAX_VALUE;var p=mesh.anchor(wound);
            for(int i=0;i<wounds.size();i++){var w=wounds.get(i);float d=mesh.anchor(w).distanceSquared(p)+(w.kind().equals(wound.kind())?0:10000);if(d<distance){distance=d;best=i;}}
            var old=wounds.get(best);wounds.set(best,new BodyWound(old.id(),old.kind(),old.triangle(),old.u(),old.v(),Math.min(3,old.severity()+1),old.size(),old.emitted(),old.lastRelease()));}
        refreshWounds();
    }
    public void tick(){
        if(entity.level().isClientSide||!entity.isAlive()||entity.isNoAi())return;ticks++;
        if(bandage>0)bandage--;
        if(entity.life().wetness()>40&&entity.life().hygiene()<40){
            if(++moldExposure>=1200){mold=Math.min(8,mold+1);moldExposure=0;}
        }else moldExposure=Math.max(0,moldExposure-1);
        if(mold>0){
            // Mould spreads over the skin, faster on dirty skin, and the terminal stage reaches the core.
            moldSpread+=entity.life().hygiene()>=70?1:2;
            if(moldSpread>=12000){moldSpread=0;mold=Math.min(MOLD_TERMINAL,mold+1);}
            if(ticks%400==0){if(mold>=MOLD_TERMINAL){damageCore("mold");return;}damage(.5f+mold*.1f);}
        }
        if(toxin>0&&ticks%1200==0)toxin--;
        if(ticks%20==0)updateMobility();
        if(burns>0&&ticks%400==0){if(entity.life().hygiene()>=60&&entity.life().nutrition()>=40){healOne(true);entity.life().spendNutrition(2);}else damage(.5f);}
        if(wound>0&&ticks%200==0){
            if(bandage==0){
                var open=wounds.stream().filter(w->w.kind().equals("tear")).toList();
                if(!open.isEmpty()){var outlet=open.get(Math.floorMod(releaseCursor++,open.size()));releaseFilling("wound",outlet);}
                if(entity.life().hygiene()<40)infection=Math.min(8,infection+1);
                // Wound damage must not create another wound or replay the initial hit.
                damage(filling==0?2:.5f);
            } else if(entity.life().nutrition()>=40&&entity.life().hygiene()>=40){healOne(false);entity.life().spendNutrition(2);entity.heal(1);}
        }
        if(infection>0&&ticks%400==0){
            if(wound==0&&entity.life().hygiene()>=60&&entity.life().nutrition()>=40)infection--;
            else damage(.5f);
        }
        if(wound==0&&infection==0&&filling<12&&ticks%1200==0&&entity.life().nutrition()>65){entity.life().spendNutrition(5);filling++;}
    }
    private void healOne(boolean scorch){for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(w.kind().equals("scorch")!=scorch)continue;if(w.severity()==1)wounds.remove(i);else wounds.set(i,w.heal());break;}refreshWounds();}
    /** True while the body is applying its own bleeding, infection or mould damage (not an attack). */
    public boolean internalDamage(){return internalDamage;}
    private void damage(float amount){internalDamage=true;try{entity.hurt(entity.damageSources().generic(),amount);}finally{internalDamage=false;}}
    /** Light enough for sugar-water saliva to close: severity 1, or a scratch of severity 2 (komicolle 11518838). */
    public static boolean lickable(BodyWound w){return w.severity()==1||w.kind().equals("scratch")&&w.severity()==2;}
    public boolean canLickMend(){return wounds.stream().anyMatch(BodyLife::lickable);}
    /** True while a wound is too deep for a lick to help (deep tear or dent, serious burn). */
    public boolean hasUnlickable(){return wounds.stream().anyMatch(w->!lickable(w));}
    /** A finished lick: the oldest mild wound closes one level and a little health returns. Deep wounds are left alone. */
    public boolean lickMend(){
        if(entity.level().isClientSide||!entity.isAlive())return false;
        for(int i=0;i<wounds.size();i++){var w=wounds.get(i);if(!lickable(w))continue;
            if(w.severity()==1)wounds.remove(i);else wounds.set(i,w.heal());
            refreshWounds();entity.heal(1);return true;}
        return false;
    }
    public boolean treat(boolean medicine){
        if(!treatmentAllowed())return false;
        if(medicine){if(infection==0)return false;infection=Math.max(0,infection-4);}
        else {if(wound==0||bandage>200)return false;bandage=1200;}
        entity.syncBody();entity.showReaction("happy","treated",80);return true;
    }
    public void died(){died(null);}
    public void died(DamageSource source){died(source,false);}
    public void died(DamageSource source,boolean stemDepletion){
        if(settled||entity.level().isClientSide)return;
        var remains=io.github.longshou.mcyukkuri.entity.RemainsEntity.snapshot(entity,source,stemDepletion);
        if(!io.github.longshou.mcyukkuri.entity.RemainsEntity.place(entity.level(),entity.position(),entity.yBodyRot,remains,false))return;
        settled=true;filling=0;entity.syncBody();
    }
    public CompoundTag save(){var tag=BodyWound.write(wounds);tag.putInt("Version",3);if(!wounds.isEmpty())WoundMigration.record(tag,WoundSurface.get(entity.subspecies().assetId()));tag.putInt("ReleaseCursor",releaseCursor);tag.putInt("Filling",filling);tag.putInt("Wound",wound);tag.putInt("Infection",infection);
        tag.putInt("Bandage",bandage);tag.putInt("Ticks",ticks);tag.putBoolean("Settled",settled);
        var knowledge=new net.minecraft.nbt.ListTag();knownPortions.forEach((portion,source)->{var entry=new CompoundTag();entry.putUUID("Portion",portion);entry.putUUID("Source",source);knowledge.add(entry);});
        tag.put("KnownPortions",knowledge);tag.putInt("Mold",mold);tag.putInt("MoldExposure",moldExposure);tag.putBoolean("CoreHurt",coreHurt);tag.putBoolean("FootBurned",footBurned);tag.putInt("FootScorch",footScorch);tag.putInt("Toxin",toxin);tag.putInt("MoldSpread",moldSpread);tag.putInt("Burns",burns);tag.putInt("HairLoss",hairLoss);return tag;}
    public void remapSurface(String previous){
        if(wounds.isEmpty()||previous.equals(entity.subspecies().assetId()))return;
        var old=WoundSurface.get(previous);var current=WoundSurface.get(entity.subspecies().assetId());
        for(int i=0;i<wounds.size();i++){var wound=wounds.get(i);if(old.valid(wound))wounds.set(i,current.remap(wound,old.anchor(wound),old.normal(wound.triangle()),old.bounds()));}
        refreshWounds();
    }
    public void load(CompoundTag tag){filling=tag.contains("Filling")?Math.clamp(tag.getInt("Filling"),0,12):12;wound=Math.clamp(tag.getInt("Wound"),0,8);
        infection=Math.clamp(tag.getInt("Infection"),0,8);bandage=Math.clamp(tag.getInt("Bandage"),0,1200);ticks=Math.max(0,tag.getInt("Ticks"));settled=tag.getBoolean("Settled");
        mold=Math.clamp(tag.getInt("Mold"),0,8);moldExposure=Math.clamp(tag.getInt("MoldExposure"),0,1199);
        burns=Math.clamp(tag.getInt("Burns"),0,8);hairLoss=tag.getInt("HairLoss")&7;
        // Saves from before the centre filling existed carry no flag: their core is unhurt.
        coreHurt=tag.getBoolean("CoreHurt");footBurned=tag.getBoolean("FootBurned");footScorch=Math.clamp(tag.getInt("FootScorch"),0,12);toxin=Math.clamp(tag.getInt("Toxin"),0,12);moldSpread=Math.clamp(tag.getInt("MoldSpread"),0,12000);
        releaseCursor=Math.max(0,tag.getInt("ReleaseCursor"));wounds.clear();var savedWounds=BodyWound.read(tag);
        if(!savedWounds.isEmpty()){var mesh=WoundSurface.get(entity.subspecies().assetId());for(var wound:savedWounds){var restored=WoundMigration.restore(entity.subspecies().assetId(),mesh,tag,wound);if(restored!=null&&mesh.valid(restored))wounds.add(restored);}}
        refreshWounds();
        knownPortions.clear();for(var raw:tag.getList("KnownPortions",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;if(entry.hasUUID("Portion")&&entry.hasUUID("Source"))knownPortions.put(entry.getUUID("Portion"),entry.getUUID("Source"));
            if(knownPortions.size()==64)break;
        }}
}
