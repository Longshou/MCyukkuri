package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.badge.*;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.human.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Current renderer evidence in the isolated P0 world. The preview fixtures are labelled stock certificates. */
public final class BadgePreview {
    private static final int X=2400;
    private static final AABB AREA=new AABB(X-3,63,-4,X+40,76,20);
    public static void command(MinecraftServer server,JsonObject request) {
        try {
            var level=server.overworld();String op=request.get("command").getAsString();
            if(op.equals("badge-setup"))setup(server);
            else if(op.equals("badge-view")) {
                int index=request.has("index")?request.get("index").getAsInt():0;
                var y=subject(level,index);String view=request.has("view")?request.get("view").getAsString():"front";
                for(var p:server.getPlayerList().getPlayers()) {
                    p.closeContainer();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();
                    double size=y.getVariant().isHouse()?.42:1;
                    double eye=y.getY()+(y.getVariant().isHouse()?.29:.78)*y.growthScale();
                    double distance=1.8*size*y.growthScale();
                    double angle=switch(view){case "side"->90;case "oblique"->40;case "mount-side"->-90;case "mount-oblique"->-40;case "back"->180;default->0;};
                    double rad=Math.toRadians(angle);
                    p.teleportTo(level,y.getX()+Math.sin(rad)*distance,eye-p.getEyeHeight(),y.getZ()+Math.cos(rad)*distance,(float)(180-angle),8);
                }
            } else if(op.equals("badge-state")) {
                var y=subject(level,request.get("index").getAsInt());
                if(request.has("action"))y.playAction(request.get("action").getAsString());
                if(request.has("bare")) {
                    boolean bare=request.get("bare").getAsBoolean();var life=y.life().save();life.putBoolean("Accessory",!bare);y.life().load(life);
                }
                if(request.has("juvenile")) {
                    var family=y.family().save();boolean juvenile=request.get("juvenile").getAsBoolean();
                    family.putString("Stage",juvenile?"JUVENILE":"ADULT");family.putFloat("Scale",juvenile?.4f:1);y.family().load(family);
                }
                y.aiStep();
            } else if(op.equals("badge-menu")) {
                var y=subject(level,request.has("index")?request.get("index").getAsInt():0);
                for(var p:server.getPlayerList().getPlayers()) {
                    p.closeContainer();p.setGameMode(GameType.CREATIVE);p.teleportTo(level,y.getX(),65,y.getZ()+2,180,10);
                    HumanCommerce.assign(y,p.getUUID(),"companion");
                    var npc=level.getEntitiesOfClass(HumanNpc.class,AREA).getFirst();npc.moveTo(y.getX()+1,65,y.getZ(),0,0);
                    BadgeMenu.open(p,y);
                }
            } else if(op.equals("badge-camera")) {
                for(var p:server.getPlayerList().getPlayers()) {
                    p.closeContainer();p.teleportTo(level,request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble(),request.get("yaw").getAsFloat(),request.get("pitch").getAsFloat());
                }
            } else if(op.equals("badge-detached")) {
                var y=subject(level,request.get("index").getAsInt());
                var stack=new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.accessoryFor(y.getVariant()));
                y.pet().markAccessory(stack);
                var display=EntityType.ITEM_DISPLAY.create(level);display.moveTo(y.getX(),65.65,7,180,0);
                var tag=new net.minecraft.nbt.CompoundTag();display.saveWithoutId(tag);
                tag.put("item",stack.save(level.registryAccess()));tag.putString("item_display","fixed");display.load(tag);level.addFreshEntity(display);
                for(var p:server.getPlayerList().getPlayers()) {
                    p.closeContainer();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();
                    p.teleportTo(level,y.getX()-1.2,64.3,8.8,214,8);
                }
            } else if(op.equals("badge-cleanup")) {
                for(int x=X>>4;x<=(X+47)>>4;x++)for(int z=-1;z<=1;z++)level.setChunkForced(x,z,false);
            } else if(!op.equals("badge-snapshot"))throw new IllegalArgumentException(op);
            var data=level.getEntitiesOfClass(YukkuriEntity.class,AREA).stream().map(y->Map.of("variant",y.getVariant().assetId(),"uuid",y.getUUID().toString(),"visibleTier",y.visibleMedal(),"accessory",y.hasVisibleAccessory(),"action",y.getAnimationId(),"scale",y.growthScale(),"badge",y.pet().save().toString())).toList();
            P0Control.reply(request,true,data);
        } catch(Throwable e) {P0Control.reply(request,false,e.toString());}
    }
    private static YukkuriEntity subject(ServerLevel level,int index) {
        return level.getEntitiesOfClass(YukkuriEntity.class,AREA,y->y.getTags().contains("badge_subject_"+index)).getFirst();
    }
    private static void setup(MinecraftServer server) {
        var level=server.overworld();for(int x=X>>4;x<=(X+47)>>4;x++)for(int z=-1;z<=1;z++)level.setChunkForced(x,z,true);
        level.setDayTime(6000);level.setWeatherParameters(60000,0,false,false);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for(int x=X-2;x<X+40;x++)for(int z=-3;z<18;z++) {
            level.setBlock(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
            for(int y=65;y<76;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        var shop=HumanWorld.BROTHER.get().create(level);shop.setJob(HumanNpc.Job.SHOPKEEPER);shop.moveTo(X+2,65,10);shop.setNoAi(true);level.addFreshEntity(shop);
        int index=0;
        for(var variant:YukkuriVariant.values()) {
            var type=BuiltInRegistries.ENTITY_TYPE.get(io.github.longshou.mcyukkuri.MCYukkuri.id(variant.assetId()));
            var y=(YukkuriEntity)type.create(level);y.moveTo(X+2+index*3.5,65,4,0,0);y.setNoAi(true);y.setPersistenceRequired();
            y.addTag("badge_subject_"+index);y.pet().initializeStock(io.github.longshou.mcyukkuri.pet.BadgeGrade.of(index%2==0?3:2),shop.getUUID(),60);level.addFreshEntity(y);y.aiStep();index++;
        }
    }
}
