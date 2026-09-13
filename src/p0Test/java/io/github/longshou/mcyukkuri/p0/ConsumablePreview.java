package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Disposable scene setup; screenshots always come from the running client and current exported assets. */
final class ConsumablePreview {
    private static final AABB AREA=new AABB(2800,63,0,2865,76,20);
    private static void clear(ServerLevel level){
        for(var entity:level.getEntitiesOfClass(YukkuriEntity.class,AREA)){
            FamilyData.get(level).cancel(entity.family().brood());entity.discard();
        }
        level.getEntitiesOfClass(Entity.class,AREA,e->e.getTags().contains("consumable_preview")).forEach(Entity::discard);
    }
    private static YukkuriEntity actor(ServerLevel level,YukkuriVariant variant,double x,double z){
        var e=ModEntities.forVariant(variant).create(level);e.moveTo(x,65,z,180,0);e.yBodyRot=e.yBodyRotO=e.yRotO=e.yHeadRotO=180;e.setYHeadRot(180);
        e.setOnGround(true);e.setPersistenceRequired();e.addTag("consumable_preview");level.addFreshEntity(e);
        var state=e.life().save();state.putFloat("Nutrition",100);state.putFloat("Energy",95);state.putFloat("Hygiene",100);e.life().load(state);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);return e;
    }
    private static FamilyData.Brood pregnancy(YukkuriEntity e,int count){
        if(!e.life().assignNaturalHome(e.blockPosition()))throw new IllegalStateException("Preview home unavailable "+e.position());
        var family=e.family().save();int serial=0;
        while(FamilyData.offspringCount(e.family().adultScale(),e.getUUID().getMostSignificantBits()^serial)!=count)serial++;
        family.putInt("Conceptions",serial);e.family().load(family);
        var brood=FamilyData.get((ServerLevel)e.level()).assist(e);if(brood==null)throw new IllegalStateException("Preview assistance ineligible");return brood;
    }
    static void command(MinecraftServer server,JsonObject request){
        server.execute(()->{try{
            var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();String op=request.get("command").getAsString();
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,24000,0,false,false));
            Object result=Map.of("command",op);
            switch(op){
                case "consumable-setup" -> {
                    for(int chunk=175;chunk<=179;chunk++)for(int z=0;z<=1;z++)level.setChunkForced(chunk,z,true);
                    clear(level);
                    for(int x=2800;x<2865;x++)for(int z=0;z<20;z++){
                        for(int y=65;y<76;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x,64,z),(x%4==0?Blocks.SMOOTH_STONE:Blocks.SMOOTH_QUARTZ).defaultBlockState());
                        if(z>=6&&z<=14)level.setBlockAndUpdate(new BlockPos(x,70,z),Blocks.OAK_PLANKS.defaultBlockState());
                    }
                    for(int x=2800;x<2865;x+=3)for(int z=0;z<20;z+=3)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.LIGHT.defaultBlockState());
                    level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,server);
                    player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,2810,66,1,0,10);
                }
                case "consumable-props" -> {
                    clear(level);int i=0;
                    for(var kind:Consumables.Kind.values()){
                        Display.ItemDisplay display=EntityType.ITEM_DISPLAY.create(level);display.moveTo(2806+i*1.25,65.5,4.5,180,0);display.addTag("consumable_preview");
                        var tag=new CompoundTag();display.saveWithoutId(tag);tag.put("item",new ItemStack(Consumables.ITEMS.get(kind).get()).save(level.registryAccess()));tag.putString("item_display","fixed");
                        display.load(tag);level.addFreshEntity(display);i++;
                    }
                    player.teleportTo(level,2809.2,64.38,-1,0,4);
                }
                case "consumable-mouth" -> {
                    clear(level);int i=0;
                    for(var kind:new Consumables.Kind[]{Consumables.Kind.YOGURT,Consumables.Kind.STIMULANT,Consumables.Kind.SITATARAN}){
                        var e=actor(level,YukkuriVariant.REIMU,2805.5+i*2,8.5);var state=e.life().save();state.putFloat("Dryness",80);e.life().load(state);
                        if(!e.life().beginDose(new ItemStack(Consumables.ITEMS.get(kind).get()),null,false))throw new IllegalStateException("dose "+kind);
                        for(int tick=0;tick<20;tick++)e.life().tick();e.setNoAi(true);e.syncFamily();i++;
                    }
                    player.teleportTo(level,2807.5,64.25,4.0,0,8);
                }
                case "consumable-pregnancy" -> {
                    clear(level);int i=0;
                    for(float phase:new float[]{0,.5f,1}){
                        var e=actor(level,YukkuriVariant.REIMU,2805.5+i*2,8.5);
                        if(phase>0){var brood=pregnancy(e,2);brood.elapsed=(int)(brood.duration*phase)-1;FamilyData.get(level).setDirty();}
                        e.setNoAi(true);e.syncFamily();i++;
                    }
                    player.teleportTo(level,2807.5,64.25,4.3,0,8);
                }
                case "consumable-emergence" -> {
                    clear(level);int phase=request.has("phase")?request.get("phase").getAsInt():14;
                    var variant=request.has("variant")?YukkuriVariant.valueOf(request.get("variant").getAsString()):YukkuriVariant.REIMU;
                    var e=actor(level,variant,2840.5,8.5);var brood=pregnancy(e,2);
                    if(request.has("childFlags"))for(var child:brood.children)child.subspeciesFlags=request.get("childFlags").getAsInt();
                    brood.elapsed=brood.duration+phase;e.setNoAi(true);e.syncFamily();
                    FamilyData.get(level).setDirty();player.teleportTo(level,2840.5,64.13,6.1,0,14);
                    result=Map.of("phase",phase,"mother",e.getUUID(),"id",e.getId(),"brood",brood.id);
                }
                case "consumable-live" -> {
                    clear(level);var variant=request.has("variant")?YukkuriVariant.valueOf(request.get("variant").getAsString()):YukkuriVariant.REIMU;
                    var e=actor(level,variant,2840.5,8.5);var brood=pregnancy(e,2);
                    if(request.has("childFlags"))for(var child:brood.children)child.subspeciesFlags=request.get("childFlags").getAsInt();
                    brood.duration=200;brood.elapsed=195;FamilyData.get(level).setDirty();
                    player.setGameMode(GameType.SPECTATOR);player.fallDistance=0;
                    player.teleportTo(level,2840.5,64.15,5.6,0,8);result=Map.of("id",e.getId(),"mother",e.getUUID(),"brood",brood.id);
                }
                case "consumable-dose" -> {
                    clear(level);var e=actor(level,YukkuriVariant.REIMU,2840.5,8.5);e.life().assignNaturalHome(e.blockPosition());
                    player.fallDistance=0;player.setHealth(player.getMaxHealth());player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,2840.5,65,9.65,180,48);player.setDeltaMovement(0,0,0);player.getInventory().clearContent();
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Consumables.ITEMS.get(Consumables.Kind.REPRODUCTIVE).get(),2));
                    result=Map.of("id",e.getId(),"mother",e.getUUID(),"itemCount",2);
                }
                case "consumable-view" -> {
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(level,request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble(),request.get("yaw").getAsFloat(),request.get("pitch").getAsFloat());
                }
                case "consumable-state" -> {
                    var rows=new ArrayList<Map<String,Object>>();
                    for(var e:level.getEntitiesOfClass(YukkuriEntity.class,AREA)){
                        var brood=e.family().brood();var row=new java.util.LinkedHashMap<String,Object>();row.put("uuid",e.getUUID());row.put("id",e.getId());row.put("position",e.position().toString());
                        row.put("family",e.family().save().toString());row.put("liveBelly",e.livePregnancyProgress());row.put("delivery",e.liveDeliveryTicks());row.put("count",e.liveChildCount());row.put("bornMask",e.liveBornMask());row.put("mouth",e.getMouthItem().toString());
                        row.put("blockLight",level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,e.blockPosition()));row.put("skyLight",level.getBrightness(net.minecraft.world.level.LightLayer.SKY,e.blockPosition()));
                        row.put("asset",e.subspecies().assetId());
                        if(brood!=null){row.put("brood",brood.id);row.put("elapsed",brood.elapsed);row.put("children",brood.children.stream().map(c->Map.of("uuid",c.id,"born",c.born,"morph",c.subspeciesFlags)).toList());}rows.add(row);
                    }result=rows;
                }
                default -> throw new IllegalArgumentException(op);
            }
            P0Control.reply(request,true,result);
        }catch(Throwable error){P0Control.reply(request,false,error.toString());}});
    }
}
