package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Commands are reachable only through the opt-in isolated development mod. */
final class CorePreview {
    private static final AABB AREA=new AABB(160,60,0,224,85,56);
    static List<YukkuriEntity> entities(ServerLevel level){return level.getEntitiesOfClass(YukkuriEntity.class,AREA,e->e.getTags().contains("core_preview"))
            .stream().sorted(Comparator.comparing(e->e.getTags().stream().filter(t->t.startsWith("core_index_")).findFirst().orElse("z"))).toList();}
    static int index(YukkuriEntity e){return e.getTags().stream().filter(t->t.startsWith("core_index_")).mapToInt(t->Integer.parseInt(t.substring(11))).findFirst().orElse(99);}
    static void command(MinecraftServer server,JsonObject request){
        try{
            var level=server.overworld();String op=request.get("command").getAsString();
            switch(op){
                case "core-props" -> PeripheralPreview.setup(server);
                case "core-setup" -> setup(server,request);
                case "core-tour" -> Showcase.setup(server);
                case "core-view" -> {
                    double x=request.has("x")?request.get("x").getAsDouble():178.5,y=request.has("y")?request.get("y").getAsDouble():66,z=request.has("z")?request.get("z").getAsDouble():14;
                    float yaw=request.has("yaw")?request.get("yaw").getAsFloat():180,pitch=request.has("pitch")?request.get("pitch").getAsFloat():10;
                    for(var player:server.getPlayerList().getPlayers()){player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,x,y,z,yaw,pitch);}
                }
                case "core-progress" -> {
                    for(var e:entities(level)){var brood=e.family().brood();if(brood!=null){
                        brood.elapsed=Math.round(request.get("progress").getAsFloat()*brood.duration);
                        if(brood.kind==FamilyData.BirthKind.EGG)for(var child:brood.children)if(!child.born)child.eggElapsed=Math.clamp(brood.elapsed,0,brood.duration);
                        FamilyData.get(level).setDirty();e.syncFamily();}}
                }
                case "core-run" -> {
                    for(var e:entities(level))if(e.family().brood()!=null){e.setNoAi(false);var brood=e.family().brood();
                        brood.duration=request.has("duration")?request.get("duration").getAsInt():400;brood.elapsed=request.has("elapsed")?request.get("elapsed").getAsInt():300;
                        if(brood.kind==FamilyData.BirthKind.EGG)for(var child:brood.children)if(!child.born)child.eggElapsed=Math.clamp(brood.elapsed,0,brood.duration);
                        FamilyData.get(level).setDirty();}
                }
                case "core-freeze" -> level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(e->e.setNoAi(true));
                case "core-unload" -> {
                    for(var player:server.getPlayerList().getPlayers()){player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,1024,90,1024,0,20);}
                    for(int x=10;x<=13;x++)for(int z=0;z<=3;z++)level.setChunkForced(x,z,false);
                }
                case "core-unloaded" -> {P0Control.reply(request,true,Map.of("chunk",level.getChunkSource().hasChunk(11,0),"entities",level.getEntitiesOfClass(YukkuriEntity.class,AREA).size()));return;}
                case "core-load" -> {for(int x=10;x<=13;x++)for(int z=0;z<=3;z++)level.setChunkForced(x,z,true);}
                case "core-hatch" -> {
                    var nest=(HouseNestBlockEntity)level.getBlockEntity(new BlockPos(178,65,8));var brood=nest.brood();
                    if(brood==null)throw new IllegalStateException("No actual eggs");brood.duration=400;brood.elapsed=request.has("elapsed")?request.get("elapsed").getAsInt():250;
                    for(var child:brood.children)if(!child.born)child.eggElapsed=Math.clamp(brood.elapsed,0,brood.duration);
                    level.setDayTime(6000);for(var e:entities(level))if(e.getVariant().isHouse())e.setNoAi(false);FamilyData.get(level).setDirty();
                }
                case "core-hand" -> {
                    int i=request.has("index")?request.get("index").getAsInt():0;var e=entities(level).stream().filter(entity->index(entity)==i).findFirst().orElseThrow();
                    int playerIndex=0;
                    for(var player:server.getPlayerList().getPlayers()){
                        player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,e.getX()+(playerIndex++%2==0?-.6:.6),65,e.getZ()+2,180,32);
                        String item=request.get("item").getAsString();var held=switch(item){
                            case "bandage","juice"->new ItemStack(ModItems.ORANGE_JUICE.get(),2);case "medicine","mold"->new ItemStack(ModItems.MOLD_REMOVER.get(),2);
                            case "notebook"->new ItemStack(ModItems.FIELD_NOTEBOOK.get());case "carrier"->new ItemStack(ModItems.CARRIER.get());
                            case "cloth"->new ItemStack(ModItems.WASH_CLOTH.get());case "scrap"->new ItemStack(ModItems.FOOD_SCRAP.get(),12);
                            default->new ItemStack(Items.APPLE,2);};
                        if(item.equals("cloth"))net.minecraft.world.item.component.CustomData.update(DataComponents.CUSTOM_DATA,held,t->t.putInt("Water",4));
                        player.setItemInHand(InteractionHand.MAIN_HAND,held);player.inventoryMenu.broadcastChanges();
                    }
                }
                case "core-save" -> {P0Control.write("core-before-reload",snapshot(server));server.saveEverything(false,true,true);}
                case "core-snapshot" -> { }
                default -> throw new IllegalArgumentException("Unknown core operation");
            }
            P0Control.reply(request,true,snapshot(server));
        }catch(Exception error){P0Control.reply(request,false,error.toString());}
    }
    static YukkuriEntity spawn(ServerLevel level,EntityType<YukkuriEntity> type,double x,double z,int index){
        var e=type.create(level);e.moveTo(x,65,z,0,0);e.setNoAi(true);e.setOnGround(true);e.setPersistenceRequired();e.addTag("core_preview");e.addTag("core_index_"+index);
        var life=e.life().save();life.putFloat("Nutrition",95);life.putFloat("Energy",95);life.putFloat("Hygiene",100);life.putInt("RestWait",2000);
        life.putString("HomeDimension",level.dimension().location().toString());life.putLong("HomePos",BlockPos.containing(x,65,z).asLong());life.putBoolean("NaturalHome",true);e.life().load(life);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);level.addFreshEntity(e);return e;
    }
    static void setup(MinecraftServer server,JsonObject request){
        var level=server.overworld();for(int x=10;x<=13;x++)for(int z=0;z<=3;z++)level.setChunkForced(x,z,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(e->{FamilyData.get(level).cancel(e.family().brood());e.discard();});
        level.getEntitiesOfClass(ItemEntity.class,AREA).forEach(Entity::discard);
        for(int x=160;x<200;x++)for(int z=0;z<35;z++){
            for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
        }
        for(int x=170;x<189;x++)for(int z=4;z<12;z++)level.setBlockAndUpdate(new BlockPos(x,71,z),Blocks.OAK_PLANKS.defaultBlockState());
        String scene=request.has("scene")?request.get("scene").getAsString():"family";
        if(scene.equals("family")){
            int count=request.has("count")?request.get("count").getAsInt():3;
            for(int row=0;row<2;row++){
                var a=spawn(level,row==0?ModEntities.REIMU.get():ModEntities.MARISA.get(),176+row*5,8,row*2);
                var b=spawn(level,row==0?ModEntities.MARISA.get():ModEntities.REIMU.get(),177.2+row*5,7,row*2+1);
                var data=FamilyData.get(level);data.register(a);data.register(b);YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
                long seed=a.getUUID().getMostSignificantBits()^b.getUUID().getLeastSignificantBits();int serial=0;
                while(FamilyData.offspringCount(1,seed^serial)!=count&&serial<100000)serial++;
                var family=a.family().save();family.putInt("Conceptions",serial);a.family().load(family);
                var brood=data.conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),a.blockPosition()));
                if(brood==null)throw new IllegalStateException("Core display pair not eligible: "+a.family().ready()+" / "+a.life().homeStand());
                brood.elapsed=Math.round(brood.duration*(request.has("progress")?request.get("progress").getAsFloat():.9f));data.setDirty();a.syncFamily();
                b.moveTo(b.getX(),65,3,0,0);
            }
        }else if(scene.equals("house")||scene.equals("house_work")){
            level.setDayTime(scene.equals("house_work")?18000:6000);
            BlockPos pos=new BlockPos(178,65,8);level.setBlockAndUpdate(pos,ModBlocks.HOUSE_DUST_NEST.get().defaultBlockState());var nest=(HouseNestBlockEntity)level.getBlockEntity(pos);
            var a=spawn(level,ModEntities.HOUSE_REIMU.get(),178.2,7.3,0);var b=spawn(level,ModEntities.HOUSE_MARISA.get(),178.8,7.3,1);
            a.house().choose(nest);b.house().choose(nest);YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),30);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),30);
            if(scene.equals("house")){for(int i=0;i<6;i++)nest.deposit(new ItemStack(ModItems.NEST_LINT.get()));for(int i=0;i<20;i++)nest.deposit(new ItemStack(ModItems.FOOD_SCRAP.get()));a.setNoAi(false);b.setNoAi(false);}
            else {
                var lint=new ItemEntity(level,175.5,65,7.3,new ItemStack(ModItems.NEST_LINT.get(),6));lint.setNoPickUpDelay();level.addFreshEntity(lint);
                var scraps=new ItemEntity(level,175.5,65,6.3,new ItemStack(ModItems.FOOD_SCRAP.get(),20));scraps.setNoPickUpDelay();level.addFreshEntity(scraps);a.setNoAi(false);b.setNoAi(false);
            }
        }else if(scene.equals("body")){
            var a=spawn(level,ModEntities.REIMU.get(),176,8,0);var data=a.body().save();data.putInt("Wound",3);data.putInt("Filling",8);a.body().load(data);
            var b=spawn(level,ModEntities.MARISA.get(),179,8,1);data=b.body().save();data.putInt("Wound",3);data.putInt("Bandage",1000);b.body().load(data);
        }else if(scene.equals("population")){
            int count=request.get("count").getAsInt();if(count<1||count>100)throw new IllegalArgumentException("Performance scene count");
            for(int i=0;i<count;i++){var e=spawn(level,i%2==0?ModEntities.REIMU.get():ModEntities.MARISA.get(),163+(i%10)*3,3+(i/10)*3,i);e.setNoAi(false);}
        }
        for(var p:server.getPlayerList().getPlayers()){p.setGameMode(GameType.SPECTATOR);p.teleportTo(level,178.5,66,15,180,12);}
    }
    static Object snapshot(MinecraftServer server){
        var level=server.overworld();var list=level.getEntitiesOfClass(YukkuriEntity.class,AREA).stream().map(e->{
            JsonObject state=P0Server.state(e);state.addProperty("index",index(e));state.addProperty("behavior",e.getBehaviorId());state.addProperty("scale",e.growthScale());
            state.addProperty("broodCount",e.broodCount());state.addProperty("broodBorn",e.broodBornMask());state.addProperty("broodProgress",e.broodProgress());state.addProperty("broodVariation",e.broodVariation());
            state.addProperty("familyNBT",e.family().save().toString());state.addProperty("houseNBT",e.house().save().toString());state.addProperty("bodyNBT",e.body().save().toString());
            state.addProperty("familyAge",e.family().age());state.addProperty("nutrition",e.life().nutrition());return state;
        }).toList();
        var nest=level.getBlockEntity(new BlockPos(178,65,8));
        return Map.of("tick",level.getGameTime(),"entities",list,"nest",nest instanceof HouseNestBlockEntity value?value.saveWithFullMetadata(level.registryAccess()).toString():"none",
                "meanTickMs",server.getAverageTickTimeNanos()/1_000_000.0);
    }
    private CorePreview(){}
}
