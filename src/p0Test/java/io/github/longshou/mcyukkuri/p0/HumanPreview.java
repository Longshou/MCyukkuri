package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;

/** A separate arena in the existing isolated preview; never placed in user saves. */
public final class HumanPreview {
    private static final AABB AREA=new AABB(798,63,-2,1060,86,70);
    public static void command(MinecraftServer server,JsonObject request) {
        try {
            var level=server.overworld();String op=request.get("command").getAsString();
            if(op.equals("human-natural-start")||op.equals("human-natural-check")) {
                P0Control.reply(request,true,HumanNaturalChecks.run(server,op.endsWith("start"),request.has("building")?request.get("building").getAsString():null));return;
            }
            switch(op) {
                case "human-natural-view" -> {for(var p:server.getPlayerList().getPlayers())HumanNaturalChecks.view(p,request.get("building").getAsString());}
                case "human-setup" -> setup(server);
                case "human-tray" -> {
                    var n=level.getEntitiesOfClass(HumanNpc.class,AREA,v->v.siteRole().equals("bag_worker")).getFirst();
                    n.production().pipeline().tick();var tray=n.production().pipeline().tray();
                    var y=n.animals("input").getFirst();tray.setNoAi(true);tray.moveTo(1036.5,67,19.5);y.moveTo(1036.5,67.25,19.5);y.startRiding(tray,true);
                }
                case "human-view" -> {for(var player:server.getPlayerList().getPlayers())view(player,request.get("view").getAsString());}
                case "human-menu" -> {
                    String role=request.has("role")?request.get("role").getAsString():"shopkeeper";
                    var npc=level.getEntitiesOfClass(HumanNpc.class,AREA,n->n.siteRole().equals(role)&&n.getZ()<35).getFirst();
                    for(var p:server.getPlayerList().getPlayers()) {
                        p.setGameMode(GameType.CREATIVE);p.teleportTo(level,npc.getX(),npc.getY(),npc.getZ()-2,0,0);
                        p.getInventory().add(new ItemStack(Items.EMERALD,32));HumanMenu.open(p,npc);
                    }
                }
                case "human-save" -> {P0Control.write("human-before-reload",snapshot(level));server.saveEverything(false,true,true);}
                case "human-snapshot" -> { }
                case "human-freeze" -> {level.getEntitiesOfClass(Mob.class,AREA).forEach(n->n.setNoAi(true));}
                case "human-run" -> {level.getEntitiesOfClass(Mob.class,AREA,n->n.getX()>899).forEach(n->n.setNoAi(false));}
                case "human-cleanup" -> {for(int x=49;x<=66;x++)for(int z=-1;z<=4;z++)level.setChunkForced(x,z,false);}
                default -> throw new IllegalArgumentException(op);
            }
            P0Control.reply(request,true,snapshot(level));
        }catch(Throwable e){P0Control.reply(request,false,e.toString());}
    }
    private static void setup(MinecraftServer server) {
        var level=server.overworld();
        for(int x=49;x<=66;x++)for(int z=-1;z<=4;z++)level.setChunkForced(x,z,true);
        level.setDayTime(6000);level.setWeatherParameters(60000,0,false,false);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        var buildings=List.of("yukkuri_shop","yukkuri_factory","yukkuri_labor_factory","yukkuri_manju_factory","yukkuri_bag_factory");
        for(int index=0;index<buildings.size();index++) {
            var template=level.getStructureManager().get(MCYukkuri.id(buildings.get(index))).orElseThrow();var pos=new BlockPos(index==0?800:index==1?845:900+(index-2)*55,64,0);
            template.placeInWorld(level,pos,pos,new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true),level.random,2);
        }
        for(int x=801;x<=825;x++)for(int z=40;z<=53;z++)level.setBlockAndUpdate(new BlockPos(x,65,z),Blocks.SMOOTH_STONE.defaultBlockState());
        var types=List.of(HumanWorld.ONIISAN.get(),HumanWorld.BROTHER.get(),HumanWorld.SISTER.get(),HumanWorld.BROTHER.get(),HumanWorld.BROTHER.get());
        for(int i=0;i<5;i++) {
            var npc=types.get(i).create(level);npc.moveTo(805.5+i*3.5,66,45.5,0,0);
            if(i==3)npc.setJob(HumanNpc.Job.SHOPKEEPER);if(i==4)npc.setJob(HumanNpc.Job.FACTORY);
            if(i>=3)npc.setAttitude(HumanNpc.Attitude.NEUTRAL);
            npc.addTag("human_lineup");level.addFreshEntity(npc);
        }
        level.getEntitiesOfClass(HumanNpc.class,AREA).forEach(n->{n.aiStep();n.setNoAi(true);});
        level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(n->{n.setOnGround(true);n.setNoAi(true);});
        for(var p:server.getPlayerList().getPlayers()){p.setGameMode(GameType.CREATIVE);view(p,"shop");}
    }
    private static void view(ServerPlayer p,String view) {
        var l=p.getServer().overworld();p.closeContainer();
        switch(view) {
            case "shop" -> p.teleportTo(l,812.5,75,-24,0,17);
            case "shop-side" -> p.teleportTo(l,837,75,-12,42,21);
            case "shop-inside" -> p.teleportTo(l,812.5,67.2,3,0,8);
            case "factory" -> p.teleportTo(l,863,81,-33,0,20);
            case "factory-inside" -> p.teleportTo(l,861,69,5,0,14);
            case "npc-front" -> p.teleportTo(l,812.5,67,63,180,4);
            case "npc-back" -> p.teleportTo(l,812.5,67,30,0,4);
            case "npc-side" -> p.teleportTo(l,829,67,52,120,4);
            case "mohawk-front" -> p.teleportTo(l,805.5,66.5,50.5,180,8);
            case "mohawk-side" -> p.teleportTo(l,800.5,66.5,45.5,-90,8);
            case "mohawk-back" -> p.teleportTo(l,805.5,65.95,41,0,0);
            case "silver-pet" -> p.teleportTo(l,819.5,67,3.2,0,20);
            case "gold-pet" -> p.teleportTo(l,819.5,67,9.2,0,20);
            case "labor" -> p.teleportTo(l,920.5,82,-32,0,19);
            case "labor-inside" -> p.teleportTo(l,927,69,11,45,15);
            case "manju" -> p.teleportTo(l,975.5,82,-32,0,19);
            case "manju-inside" -> p.teleportTo(l,980,69,11,20,15);
            case "bag" -> p.teleportTo(l,1030.5,82,-32,0,19);
            case "bag-inside" -> p.teleportTo(l,1035,69,11,20,15);
            case "nursery" -> p.teleportTo(l,1022,68.5,11,28,14);
            case "pipeline-floor" -> p.teleportTo(l,1038,69.3,11,36,24);
            case "tray-front" -> p.teleportTo(l,1036.5,67.5,23.8,180,15);
            case "tray-side" -> p.teleportTo(l,1041,67.5,19.5,90,15);
            case "tray-back" -> p.teleportTo(l,1036.5,67.5,15.2,0,15);
            default -> throw new IllegalArgumentException(view);
        }
        p.getAbilities().flying=true;p.onUpdateAbilities();
    }
    private static Object snapshot(ServerLevel l) {
        return Map.of("humans",l.getEntitiesOfClass(HumanNpc.class,AREA).stream().map(n->Map.of("uuid",n.getUUID().toString(),"type",n.look().name(),"job",n.job().name(),"stock",n.animals("stock").size(),"x",n.getX(),"y",n.getY(),"z",n.getZ())).toList(),
            "animals",l.getEntitiesOfClass(YukkuriEntity.class,AREA).stream().map(y->Map.of("uuid",y.getUUID().toString(),"variant",y.getVariant().assetId(),"custody",HumanCommerce.data(y).toString())).toList());
    }
}
