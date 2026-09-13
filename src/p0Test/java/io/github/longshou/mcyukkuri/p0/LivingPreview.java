package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Opt-in singleplayer fixture. P0Server checks the exact isolated world before forwarding here. */
final class LivingPreview {
    private static final AABB AREA=new AABB(80,60,0,136,85,56);
    private static final BlockPos NEST=new BlockPos(103,65,6);
    static void command(MinecraftServer server,JsonObject request) {
        try {
            ServerLevel level=server.overworld();
            String op=request.get("command").getAsString();
            switch(op) {
                case "living-setup" -> setup(server);
                case "living-view" -> {
                    String view=request.get("view").getAsString();
                    for(ServerPlayer player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SPECTATOR);
                        switch(view) {
                            case "front" -> player.teleportTo(level,94.5,65.5,16,180,10);
                            case "close" -> player.teleportTo(level,91.5,65.2,8.5,180,12);
                            case "back" -> player.teleportTo(level,94.5,65.3,-6,0,9);
                            case "side" -> player.teleportTo(level,104,66,9,120,14);
                            case "house" -> player.teleportTo(level,97.5,65,6.5,180,26);
                            case "care" -> player.teleportTo(level,99,70,15,180,38);
                            default -> throw new IllegalArgumentException("Unknown living view");
                        }
                    }
                }
                case "living-hand" -> prepareHand(server,request);
                case "living-care" -> {
                    for(ServerPlayer player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,94.5,65,10,180,45);
                        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    }
                }
                case "living-bowl" -> {
                    var bowl=(CareBlockEntity)level.getBlockEntity(new BlockPos(94,65,8));
                    int count=request.get("count").getAsInt();bowl.setItem(0,count==0?ItemStack.EMPTY:new ItemStack(Items.APPLE,count));
                }
                case "living-prepare-save" -> {
                    for(YukkuriEntity entity:entities(level)) {
                        entity.setNoAi(true);
                        var data=entity.life().save();data.putFloat("Nutrition",43);data.putFloat("Energy",62);data.putFloat("Hygiene",71);entity.life().load(data);
                    }
                    var first=entities(level).stream().filter(e->index(e)==0).findFirst().orElseThrow();
                    boolean home=first.life().assignHome(GlobalPos.of(level.dimension(),NEST));
                    if(!home)throw new IllegalStateException("Preview resident could not claim its home");
                }
                case "living-save" -> { P0Control.write("living-before-reload",snapshot(server));server.saveEverything(false,true,true); }
                case "living-snapshot" -> { }
                default -> throw new IllegalArgumentException("Unknown living operation");
            }
            P0Control.reply(request,true,snapshot(server));
        } catch(Exception error) { P0Control.reply(request,false,error.toString()); }
    }
    private static int index(YukkuriEntity entity) {
        return entity.getTags().stream().filter(t->t.startsWith("living_index_")).mapToInt(t->Integer.parseInt(t.substring(13))).findFirst().orElse(99);
    }
    private static List<YukkuriEntity> entities(ServerLevel level) {
        return level.getEntitiesOfClass(YukkuriEntity.class,AREA,e->e.getTags().contains("living_preview")).stream().sorted(Comparator.comparingInt(LivingPreview::index)).toList();
    }
    private static void setup(MinecraftServer server) {
        ServerLevel level=server.overworld();
        for(int x=5;x<=8;x++)for(int z=0;z<=3;z++)level.setChunkForced(x,z,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,server);
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        level.getEntitiesOfClass(Entity.class,AREA,e->e.getTags().contains("living_preview")||e instanceof ItemEntity).forEach(Entity::discard);
        for(int x=86;x<=107;x++)for(int z=0;z<=12;z++) {
            for(int y=65;y<=69;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
        }
        List<EntityType<YukkuriEntity>> types=List.of(ModEntities.REIMU.get(),ModEntities.MARISA.get(),ModEntities.HOUSE_REIMU.get(),ModEntities.HOUSE_MARISA.get());
        for(int i=0;i<types.size();i++) {
            YukkuriEntity entity=types.get(i).create(level);entity.moveTo(90+i*3,65,4,0,0);entity.setNoAi(true);entity.setPersistenceRequired();
            entity.addTag("living_preview");entity.addTag("living_index_"+i);level.addFreshEntity(entity);
        }
        level.setBlockAndUpdate(new BlockPos(94,65,8),ModBlocks.FOOD_BOWL.get().defaultBlockState());
        ((CareBlockEntity)level.getBlockEntity(new BlockPos(94,65,8))).setItem(0,new ItemStack(Items.APPLE,3));
        level.setBlockAndUpdate(new BlockPos(98,65,8),ModBlocks.TOILET.get().defaultBlockState());
        level.setBlockAndUpdate(NEST,ModBlocks.NEST.get().defaultBlockState());
        for(int x=102;x<=104;x++)for(int z=5;z<=7;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        P0Control.write("living-display-ids",entities(level).stream().map(Entity::getUUID).toList());
    }
    private static void prepareHand(MinecraftServer server,JsonObject request) {
        ServerLevel level=server.overworld();int slot=request.has("index")?request.get("index").getAsInt():0;
        YukkuriEntity entity=entities(level).stream().filter(e->index(e)==slot).findFirst().orElseThrow();
        String item=request.get("item").getAsString();
        for(ServerPlayer player:server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,entity.getX(),65,entity.getZ()+2,180,34);
            ItemStack held=switch(item) {
                case "apple" -> new ItemStack(Items.APPLE,2);
                case "notebook" -> new ItemStack(ModItems.FIELD_NOTEBOOK.get());
                case "cloth" -> new ItemStack(ModItems.WASH_CLOTH.get());
                case "shears" -> new ItemStack(Items.SHEARS);
                case "whistle" -> new ItemStack(ModItems.WHISTLE.get());
                case "carrier" -> new ItemStack(ModItems.CARRIER.get());
                case "empty" -> ItemStack.EMPTY;
                case "accessory" -> {
                    ItemStack found=ItemStack.EMPTY;
                    for(int i=0;i<player.getInventory().getContainerSize();i++) {
                        ItemStack candidate=player.getInventory().getItem(i);
                        var data=candidate.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
                        if(data.hasUUID("Owner")&&data.getUUID("Owner").equals(entity.getUUID())) {found=player.getInventory().removeItem(i,1);break;}
                    }
                    if(found.isEmpty())throw new IllegalStateException("No actual returned accessory in observer inventory");
                    yield found;
                }
                default -> throw new IllegalArgumentException("Unknown fixture item");
            };
            player.setItemInHand(InteractionHand.MAIN_HAND,held);
            if(item.equals("apple")) {
                entity.setNoAi(false);var data=entity.life().save();data.putFloat("Nutrition",60);entity.life().load(data);entity.setHealth(6);
            } else if(item.equals("cloth")) {
                var data=entity.life().save();data.putFloat("Hygiene",30);entity.life().load(data);
                CustomData.update(DataComponents.CUSTOM_DATA,held,tag->tag.putInt("Water",2));
            } else if(item.equals("whistle")) entity.setNoAi(false);
            player.inventoryMenu.broadcastChanges();
        }
    }
    private static Object snapshot(MinecraftServer server) {
        List<Object> states=new ArrayList<>();
        for(YukkuriEntity entity:entities(server.overworld())) {
            JsonObject state=P0Server.state(entity);state.addProperty("index",index(entity));state.addProperty("behavior",entity.getBehaviorId());
            state.addProperty("behaviorStart",entity.getBehaviorStartTick());state.addProperty("nutrition",entity.life().nutrition());
            state.addProperty("energy",entity.life().energy());state.addProperty("hygiene",entity.life().hygiene());
            state.addProperty("accessory",entity.life().hasAccessory());state.addProperty("mouthCount",entity.life().mouth().getCount());
            state.addProperty("lifeNBT",entity.life().save().toString());states.add(state);
        }
        return Map.of("tick",server.overworld().getGameTime(),"entities",states,
                "players",server.getPlayerList().getPlayers().stream().map(player->Map.of("uuid",player.getUUID(),"name",player.getGameProfile().getName(),
                        "held",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString(),"count",player.getMainHandItem().getCount(),
                        "carrierToken",String.valueOf(CarrierItem.token(player.getMainHandItem())))).toList());
    }
    private LivingPreview() {}
}
