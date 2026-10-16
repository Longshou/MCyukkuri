package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** Disposable, opt-in integrated-world contact and inventory checks. */
final class HouseInteractionPreview {
    private static YukkuriEntity crawlSubject;
    private static final ArrayList<JsonObject> crawlTrace=new ArrayList<>();
    static void tick(MinecraftServer server){
        if(crawlSubject==null||!crawlSubject.isAlive()||crawlTrace.size()>=600)return;
        var row=new JsonObject();
        row.addProperty("tick",server.overworld().getGameTime());row.addProperty("crawlStart",crawlSubject.getCrawlStartTick());
        row.addProperty("crawling",crawlSubject.isCrawling());row.addProperty("behavior",crawlSubject.getBehaviorId());
        row.addProperty("x",crawlSubject.getX());row.addProperty("y",crawlSubject.getY());row.addProperty("z",crawlSubject.getZ());
        row.addProperty("onGround",crawlSubject.onGround());row.addProperty("yaw",crawlSubject.yBodyRot);
        crawlTrace.add(row);
    }
    static void command(MinecraftServer server,JsonObject request){
        try{
            if(request.get("command").getAsString().equals("house-interaction-trace")){
                P0Control.reply(request,true,Map.of("samples",new ArrayList<>(crawlTrace)));crawlSubject=null;return;
            }
            var setup=new JsonObject();setup.addProperty("scene","house_work");CorePreview.setup(server,setup);
            var level=server.overworld();var area=new AABB(160,64,0,200,80,35);
            level.getEntitiesOfClass(YukkuriEntity.class,area).forEach(Entity::discard);
            level.getEntitiesOfClass(ItemEntity.class,area).forEach(Entity::discard);
            var pos=new BlockPos(178,65,8);
            var nest=(HouseNestBlockEntity)level.getBlockEntity(pos);
            boolean marisa=request.has("variant")&&request.get("variant").getAsString().equals("marisa");
            boolean crawl=request.has("crawl")&&request.get("crawl").getAsBoolean();
            var e=CorePreview.spawn(level,marisa?ModEntities.HOUSE_MARISA.get():ModEntities.HOUSE_REIMU.get(),178.5,crawl?6.43:6.7,0);
            crawlTrace.clear();crawlSubject=crawl?e:null;
            if(!e.house().choose(nest))throw new IllegalStateException("Unreachable fixture nest");
            var item=new ItemEntity(level,178.5,65,6.98,new ItemStack(ModItems.FOOD_SCRAP.get(),3));
            item.setDeltaMovement(0,0,0);item.setNoGravity(true);item.setNoPickUpDelay();level.addFreshEntity(item);
            double x=request.has("x")?request.get("x").getAsDouble():178.5;
            double z=request.has("z")?request.get("z").getAsDouble():7.8;
            float yaw=request.has("yaw")?request.get("yaw").getAsFloat():180;
            for(var p:server.getPlayerList().getPlayers()){p.setGameMode(GameType.SPECTATOR);p.teleportTo(level,x,63.85,z,yaw,12);}
            if(request.get("command").getAsString().equals("house-interaction-check")){
                var checks=new ArrayList<String>();
                e.setNoAi(false); // These synchronous calls do not advance the world's other goals.
                require(e.house().canUse(),"goal selects real source",checks);
                e.house().tickGoal();
                for(int i=0;i<12;i++)e.house().tickGoal();
                require(e.house().carrying().isEmpty()&&item.getItem().getCount()==3,"before contact source stays in world",checks);
                e.house().stop();
                require(e.house().carrying().isEmpty()&&item.getItem().getCount()==3,"interruption before contact preserves source",checks);
                for(int i=0;i<41;i++)e.house().tick();
                require(e.house().canUse(),"goal resumes after interruption",checks);
                e.house().tickGoal();for(int i=0;i<13;i++)e.house().tickGoal();
                require(e.house().carrying().getCount()==1&&item.getItem().getCount()==2,"contact splits exactly one item",checks);
                var saved=e.house().save();e.house().stop();e.house().load(saved);
                require(e.house().carrying().getCount()==1&&item.getItem().getCount()==2,"interruption and reload preserve held item",checks);
                for(int i=0;i<41;i++)e.house().tick();
                e.moveTo(178.5,65,7.22,0,0);
                require(e.house().canUse(),"carrying resumes toward nest",checks);
                e.house().tickGoal();for(int i=0;i<12;i++)e.house().tickGoal();
                require(nest.food()==0&&e.house().carrying().getCount()==1,"deposit waits for contact",checks);
                e.house().tickGoal();
                require(nest.food()==1&&e.house().carrying().isEmpty(),"deposit transfers exactly once",checks);
                for(int i=0;i<13;i++)e.house().tickGoal();
                require(nest.food()==1&&item.getItem().getCount()==2,"completion conserves total stock",checks);
                e.setNoAi(true);
                P0Control.reply(request,true,Map.of("checks",checks,"variant",e.getVariant().assetId()));
            }else{
                e.setNoAi(false);
                P0Control.reply(request,true,Map.of("entity",e.getId(),"variant",e.getVariant().assetId(),"sourceCount",3));
            }
        }catch(Exception error){P0Control.reply(request,false,error.toString());}
    }
    private static void require(boolean value,String name,ArrayList<String> checks){
        if(!value)throw new IllegalStateException(name);checks.add(name);
    }
}
