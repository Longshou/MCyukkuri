package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Static synchronized pose samples for geometry QA. Behaviour is exercised separately by GameTest. */
final class OperationPreview {
    static void command(MinecraftServer server,JsonObject request){
        var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();
        switch(request.get("command").getAsString()){
            case "operation-instruments"->{
                for(int x=53;x<=58;x++)for(int z=0;z<=1;z++)level.setChunkForced(x,z,true);
                level.getEntitiesOfClass(Entity.class,new AABB(845,63,0,935,80,26),e->e.getTags().contains("instrument_preview")).forEach(Entity::discard);
                for(int x=845;x<935;x++)for(int z=0;z<25;z++){
                    level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                    for(int y=65;y<78;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                }
                int i=0;for(var variant:io.github.longshou.mcyukkuri.entity.YukkuriVariant.values()){
                    var e=ModEntities.forVariant(variant).create(level);e.setNoAi(true);e.setPersistenceRequired();e.moveTo(850+i++*5,65,6,180,0);e.yBodyRot=e.yBodyRotO=180;
                    e.syncOperation("BOX",0);e.addTag("instrument_preview");level.addFreshEntity(e);
                }
                for(int j=0;j<2;j++){
                    double x=903+j*16;var mother=previewParent(level,x);var father=previewParent(level,x+2);
                    var relations=io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level);relations.relate(mother.getUUID(),father.getUUID(),30);relations.relate(father.getUUID(),mother.getUUID(),30);
                    var brood=io.github.longshou.mcyukkuri.family.FamilyData.get(level).conceive(mother,father,io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.STEM,
                            net.minecraft.core.GlobalPos.of(level.dimension(),mother.blockPosition().south()));
                    if(brood==null)throw new IllegalStateException("Preview conception");brood.elapsed=brood.duration*2/3;
                    player.setGameMode(GameType.CREATIVE);player.teleportTo(level,x-2,65,15.5,0,0);
                    var water=new ItemStack(net.minecraft.world.item.Items.POTION);water.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                            new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,water);
                    if(!io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity.cut(mother,player,j==1))throw new IllegalStateException("Preview stem cut");
                    mother.discard();father.discard();
                }
                player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,865,69,0,0,20);
            }
            case "operation-setup"->{
                for(int x=49;x<=52;x++)for(int z=0;z<=1;z++)level.setChunkForced(x,z,true);
                level.getEntitiesOfClass(Entity.class,new AABB(795,63,0,843,78,25),e->e.getTags().contains("operation_preview")).forEach(Entity::discard);
                for(int x=795;x<843;x++)for(int z=0;z<25;z++){
                    level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                    for(int y=65;y<77;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                }
                level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
                String[] poses={"NONE","SUPINE","FIXED","PIN","WRAPPED","HOOK","ROLL","RUMP"};
                for(int i=0;i<poses.length;i++){
                    var e=(i%2==0?ModEntities.REIMU:ModEntities.MARISA).get().create(level);e.setNoAi(true);e.setPersistenceRequired();
                    double x=800+i*5;e.moveTo(x,65,6,180,0);e.yBodyRot=e.yBodyRotO=180;
                    if(i==4||i==5){var support=new BlockPos((int)x,68,6);level.setBlockAndUpdate(support,Blocks.OAK_PLANKS.defaultBlockState());e.setPos(x+.5,66.4,6.5);e.setNoGravity(true);e.syncOperationAnchor(support);}
                    e.syncOperation(poses[i],i==6?145:0);e.addTag("operation_preview");level.addFreshEntity(e);
                }
                for(int i=0;i<3;i++){
                    var e=ModEntities.REIMU.get().create(level);e.setNoAi(true);e.moveTo(801+i*4,65,13,180,0);e.yBodyRot=e.yBodyRotO=180;
                    var body=e.body().save();body.putInt("HairLoss",i==0?0:i==1?1:7);e.body().load(body);var saved=new CompoundTag();e.saveWithoutId(saved);e.load(saved);
                    e.addTag("operation_preview");level.addFreshEntity(e);
                }
                int i=0;for(var tool:io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.values()){
                    var display=EntityType.ITEM_DISPLAY.create(level);var tag=new CompoundTag();tag.put("item",new ItemStack(tool.item()).save(level.registryAccess()));tag.putString("item_display","fixed");
                    display.load(tag);display.moveTo(800+(i%5)*3,65.9,19+(i/5)*3,180,0);display.addTag("operation_preview");level.addFreshEntity(display);i++;
                }
                player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,807,69,24,180,23);
            }
            case "operation-view"->player.teleportTo(level,request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble(),request.get("yaw").getAsFloat(),request.get("pitch").getAsFloat());
            case "operation-menu"->{
                player.setGameMode(GameType.CREATIVE);player.teleportTo(level,800,65,9,180,10);
                var tool=request.has("tool")?io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.valueOf(request.get("tool").getAsString()):io.github.longshou.mcyukkuri.item.OperationToolItem.Tool.PIN;
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(tool.item()));
                var e=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.YukkuriEntity.class,new AABB(799,64,5,802,68,7)).getFirst();
                e.setNoAi(false);e.setOnGround(true);
                io.github.longshou.mcyukkuri.interaction.CareMenu.open(player,e);
            }
            case "operation-state"->{
                P0Control.reply(request,true,level.getEntitiesOfClass(io.github.longshou.mcyukkuri.entity.YukkuriEntity.class,new AABB(799,63,0,802,69,9)).stream()
                        .map(e->java.util.Map.of("id",e.getId(),"onGround",e.onGround(),"contact",e.contactPose(),"active",e.operations().active(),"rollEligible",e.operations().canStart(player,io.github.longshou.mcyukkuri.interaction.CareAction.ROLL),"position",e.position().toString())).toList());return;
            }
            default->throw new IllegalArgumentException("Unknown operation preview");
        }
        P0Control.reply(request,true,"Operation preview updated");
    }
    private static io.github.longshou.mcyukkuri.entity.YukkuriEntity previewParent(net.minecraft.server.level.ServerLevel level,double x){
        var e=ModEntities.REIMU.get().create(level);e.setNoAi(true);e.setPersistenceRequired();e.moveTo(x,65,15.5,0,0);e.setOnGround(true);e.addTag("instrument_preview");
        var life=e.life().save();life.putFloat("Nutrition",90);life.putFloat("Energy",90);e.life().load(life);level.addFreshEntity(e);
        var home=e.blockPosition().south();level.setBlockAndUpdate(home,io.github.longshou.mcyukkuri.registry.ModBlocks.NEST.get().defaultBlockState());
        for(int xx=-1;xx<=1;xx++)for(int z=-1;z<=1;z++)level.setBlockAndUpdate(home.offset(xx,3,z),Blocks.OAK_PLANKS.defaultBlockState());
        if(!e.life().assignHome(net.minecraft.core.GlobalPos.of(level.dimension(),home)))throw new IllegalStateException("Preview home");return e;
    }
}
