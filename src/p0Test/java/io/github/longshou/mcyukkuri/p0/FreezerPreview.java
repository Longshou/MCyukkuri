package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Own control filename and coordinates: never takes another preview's request queue. */
@EventBusSubscriber(modid=P0Mod.ID)
public final class FreezerPreview {
    private static String last="";
    private static UUID target;
    private static final int X=3264;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!P0Control.enabled()||!Boolean.getBoolean("mcyukkuri.freezer.preview")||event.getServer().getTickCount()%10!=0)return;
        var path=P0Control.DIRECTORY.resolve("freezer-request.json");if(!Files.isRegularFile(path))return;
        try {
            var request=JsonParser.parseString(Files.readString(path)).getAsJsonObject();String id=request.get("id").getAsString();
            if(last.equals(id))return;last=id;
            var player=event.getServer().getPlayerList().getPlayers().getFirst();var level=player.serverLevel();
            switch(request.get("action").getAsString()) {
                case "setup" -> {
                    level.setChunkForced(X/16,0,true);level.setChunkForced(X/16+1,0,true);
                    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,event.getServer());
                    level.setDayTime(6000);level.setWeatherParameters(60000,0,false,false);
                    level.getEntitiesOfClass(Entity.class,new AABB(X-2,63,-2,X+32,76,18),e->e instanceof YukkuriEntity).forEach(Entity::discard);
                    for(int x=X;x<X+24;x++)for(int z=0;z<16;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
                        for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    for(int i=0;i<4;i++)level.setBlockAndUpdate(new BlockPos(X+6+i*3,65,8),FreezerModule.CABINET.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,Direction.from2DDataValue((i+2)%4)));
                    var animal=ModEntities.REIMU.get().create(level);animal.moveTo(X+4.5,65,8.5,0,0);animal.setPersistenceRequired();animal.setNoAi(true);level.addFreshEntity(animal);target=animal.getUUID();
                    player.getInventory().clearContent();player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.CARRIER.get()));
                    pose(player,"overview");
                }
                case "camera" -> pose(player,request.get("view").getAsString());
                case "notebook" -> {player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.FIELD_NOTEBOOK.get()));player.inventoryMenu.broadcastChanges();}
                case "carrier" -> {if(!(player.getMainHandItem().getItem() instanceof CarrierItem))player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModItems.CARRIER.get()));player.inventoryMenu.broadcastChanges();}
                case "restore_ai" -> {if(level.getEntity(target) instanceof YukkuriEntity entity)entity.setNoAi(false);}
                case "break" -> level.destroyBlock(new BlockPos(X+6,65,8),true);
                case "rebuild" -> level.setBlockAndUpdate(new BlockPos(X+6,65,8),FreezerModule.CABINET.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,Direction.NORTH));
                case "state" -> {}
                default -> throw new IllegalArgumentException("Unknown freezer preview action");
            }
            var entity=target==null?null:level.getEntity(target);var token=CarrierItem.token(player.getMainHandItem());
            P0Control.write("freezer-response",Map.of("id",id,"pass",true,"targetId",entity==null?-1:entity.getId(),"targetUuid",target==null?"":target.toString(),
                "heldToken",token==null?"":token.toString(),"stored",YukkuriWorldData.get(level).freezer.count(player.getUUID()),"block",new BlockPos(X+6,65,8).toShortString()));
        } catch(Throwable error) {P0Control.write("freezer-response",Map.of("id",last,"pass",false,"error",error.toString()));}
    }
    private static void pose(ServerPlayer player,String view) {
        switch(view) {
            case "front" -> player.teleportTo(player.serverLevel(),X+6.5,65,4.7,0,10);
            case "back" -> player.teleportTo(player.serverLevel(),X+6.5,65,12.3,180,10);
            case "side" -> player.teleportTo(player.serverLevel(),X+2.7,65,8.5,-90,10);
            case "interact" -> player.teleportTo(player.serverLevel(),X+5.5,65,6.1,0,20);
            default -> player.teleportTo(player.serverLevel(),X+8.5,67,1,-8,22);
        }
    }
}
