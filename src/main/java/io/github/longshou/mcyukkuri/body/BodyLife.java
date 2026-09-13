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
    public BodyLife(YukkuriEntity entity){this.entity=entity;}
    public int filling(){return filling;}
    public int wound(){return wound;}
    public int infection(){return infection;}
    public int bandage(){return bandage;}
    public int mold(){return mold;}
    public int burns(){return burns;}
    public int hairLoss(){return hairLoss;}
    public java.util.List<BodyWound> wounds(){return java.util.List.copyOf(wounds);}
    private void refreshWounds(){wound=Math.min(8,wounds.stream().filter(w->!w.kind().equals("scorch")).mapToInt(BodyWound::severity).sum());burns=Math.min(8,wounds.stream().filter(w->w.kind().equals("scorch")).mapToInt(BodyWound::severity).sum());entity.syncBody();}
    public boolean cutHair(int parts,net.minecraft.world.entity.player.Player player){
        parts&=7;int cut=parts&~hairLoss;if(cut==0||entity.level().isClientSide||!entity.isAlive())return false;
        var stack=new ItemStack(ModItems.TRIMMED_HAIR.get());
        final int removed=cut;
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Source",entity.getUUID());tag.putString("Variant",entity.getVariant().assetId());
            tag.putInt("HairParts",removed);tag.putFloat("SourceScale",entity.family().scale()*(entity.getVariant().isHouse()?.32f:1));});
        var drop=new net.minecraft.world.entity.item.ItemEntity(entity.level(),entity.getX(),entity.getY(),entity.getZ(),stack);
        if(!entity.level().addFreshEntity(drop))return false;
        hairLoss|=cut;entity.showReaction("surprise","afraid",100);entity.life().personality().confiscated(player.getUUID());return true;
    }
    public boolean restorePhysical() {
        if(!treatmentAllowed()||(wound==0&&burns==0&&hairLoss==0&&filling==12&&entity.getHealth()>=entity.getMaxHealth()))return false;
        wounds.clear();wound=bandage=burns=hairLoss=0;filling=12;entity.syncBody();entity.setHealth(entity.getMaxHealth());entity.showReaction("happy","treated",80);return true;
    }
    public boolean removeMold() {
        if(!treatmentAllowed()||mold==0)return false;
        mold=moldExposure=0;entity.showReaction("happy","treated",80);return true;
    }
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
    public void irritated(){
        releaseFilling("vomited");internalDamage=true;
        try{entity.hurt(new DamageSource(entity.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(io.github.longshou.mcyukkuri.life.Consumables.SPICE_DAMAGE)),1);}
        finally{internalDamage=false;}
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
        if(internalDamage||entity.level().isClientSide||!entity.isAlive()||lost<1||source.is(DamageTypeTags.IS_DROWNING)||source.is(net.minecraft.world.damagesource.DamageTypes.STARVE))return;
        var hit=WoundPlacement.hit(entity,source);if(hit==null)return;
        String kind=source.is(DamageTypeTags.IS_FIRE)?"scorch":source.is(DamageTypeTags.IS_PROJECTILE)?"tear":"dent";
        int severity=Math.clamp((int)Math.ceil(lost/2),1,3);
        if(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker){var item=attacker.getMainHandItem();String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            if(item.is(net.minecraft.tags.ItemTags.SWORDS)||item.is(net.minecraft.tags.ItemTags.AXES)||java.util.Set.of("care_awl","cutting_knife","pin_tool","hanging_hook").contains(id))kind="tear";
            if(id.equals("heated_awl")){boolean hot=io.github.longshou.mcyukkuri.item.OperationToolItem.isHot(item,entity.level());kind=hot?"scorch":"tear";if(hot)severity=Math.max(2,severity);}
        }
        if(entity.contactPose().equals("ROLL")||entity.contactPose().equals("SLIDE"))kind="scratch";
        if(entity.contactPose().equals("GRIP"))kind="tear";
        if(source.is(io.github.longshou.mcyukkuri.life.Facilities.ABRASION))kind="scratch";
        addWound(new BodyWound(java.util.UUID.randomUUID(),kind,hit.triangle(),hit.u(),hit.v(),severity,entity.getVariant().isHouse()?.35f:1,0,0));
        entity.syncBody();entity.showReaction("surprise","wounded",80);
    }
    /** At capacity strengthen the nearest existing injury; never move an old scar. */
    public void addWound(BodyWound wound){if(entity.level().isClientSide||!entity.isAlive()||settled)return;var mesh=WoundSurface.get(entity.subspecies().assetId());if(!mesh.valid(wound))return;
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
        if(mold>0&&ticks%400==0)damage(.5f);
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
    private void damage(float amount){internalDamage=true;try{entity.hurt(entity.damageSources().generic(),amount);}finally{internalDamage=false;}}
    public boolean treat(boolean medicine){
        if(!treatmentAllowed())return false;
        if(medicine){if(infection==0)return false;infection=Math.max(0,infection-4);}
        else {if(wound==0||bandage>200)return false;bandage=1200;}
        entity.syncBody();entity.showReaction("happy","treated",80);return true;
    }
    public void died(){
        if(settled||entity.level().isClientSide)return;settled=true;
        var remains=new ItemStack(ModItems.REMAINS.get());
        CustomData.update(DataComponents.CUSTOM_DATA,remains,tag->{tag.putUUID("Identity",entity.getUUID());tag.putString("Variant",entity.getVariant().assetId());tag.putInt("Filling",filling);tag.putFloat("SourceScale",entity.family().scale()*(entity.getVariant().isHouse()?.32f:1));});
        remains.set(DataComponents.CUSTOM_NAME,entity.getName());entity.spawnAtLocation(remains);filling=0;wounds.clear();wound=infection=bandage=0;entity.syncBody();
    }
    public CompoundTag save(){var tag=BodyWound.write(wounds);tag.putInt("Version",2);tag.putInt("ReleaseCursor",releaseCursor);tag.putInt("Filling",filling);tag.putInt("Wound",wound);tag.putInt("Infection",infection);
        tag.putInt("Bandage",bandage);tag.putInt("Ticks",ticks);tag.putBoolean("Settled",settled);
        var knowledge=new net.minecraft.nbt.ListTag();knownPortions.forEach((portion,source)->{var entry=new CompoundTag();entry.putUUID("Portion",portion);entry.putUUID("Source",source);knowledge.add(entry);});
        tag.put("KnownPortions",knowledge);tag.putInt("Mold",mold);tag.putInt("MoldExposure",moldExposure);tag.putInt("Burns",burns);tag.putInt("HairLoss",hairLoss);return tag;}
    public void load(CompoundTag tag){filling=tag.contains("Filling")?Math.clamp(tag.getInt("Filling"),0,12):12;wound=Math.clamp(tag.getInt("Wound"),0,8);
        infection=Math.clamp(tag.getInt("Infection"),0,8);bandage=Math.clamp(tag.getInt("Bandage"),0,1200);ticks=Math.max(0,tag.getInt("Ticks"));settled=tag.getBoolean("Settled");
        mold=Math.clamp(tag.getInt("Mold"),0,8);moldExposure=Math.clamp(tag.getInt("MoldExposure"),0,1199);
        burns=Math.clamp(tag.getInt("Burns"),0,8);hairLoss=tag.getInt("HairLoss")&7;
        releaseCursor=Math.max(0,tag.getInt("ReleaseCursor"));wounds.clear();var savedWounds=BodyWound.read(tag);
        if(!savedWounds.isEmpty()){var mesh=WoundSurface.get(entity.subspecies().assetId());wounds.addAll(savedWounds.stream().filter(mesh::valid).toList());}
        if(!tag.contains("Wounds")&&(wound>0||burns>0)){
            var mesh=WoundSurface.get(entity.subspecies().assetId());
            // Preserve both old totals; the former scalar data contains no individual hit positions.
            for(boolean scorch:new boolean[]{false,true})for(int remaining=scorch?burns:wound,index=0;remaining>0;remaining-=3,index++){
                float x=(1-index)*(entity.getVariant().isHouse()?1.2f:3.6f);
                var hit=mesh.ray(new org.joml.Vector3f(x,entity.getVariant().isHouse()?1.4f:2.2f,scorch?30:-30),new org.joml.Vector3f(0,0,scorch?-1:1),null,java.util.List.of());
                if(hit!=null)wounds.add(new BodyWound(java.util.UUID.randomUUID(),scorch?"scorch":"tear",hit.triangle(),hit.u(),hit.v(),Math.min(3,remaining),entity.getVariant().isHouse()?.35f:1,0,0));
            }
        }
        refreshWounds();
        knownPortions.clear();for(var raw:tag.getList("KnownPortions",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;if(entry.hasUUID("Portion")&&entry.hasUUID("Source"))knownPortions.put(entry.getUUID("Portion"),entry.getUUID("Source"));
            if(knownPortions.size()==64)break;
        }}
}
