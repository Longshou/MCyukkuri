package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.freezer.FreezerModule;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Joint network/disk fixture; requests are available only in the opt-in P0 development mod. */
final class IntegrationAcceptance {
    private static final int X = 6144;
    private static final AABB AREA = new AABB(X, 60, 0, X+64, 80, 32);
    private static final BlockPos CABINET = new BlockPos(X+9,65,7);

    static Map<String,Object> network(YukkuriEntity e) {
        var row = new LinkedHashMap<String,Object>();
        row.put("uuid",e.getUUID().toString()); row.put("variant",e.getVariant().assetId());
        row.put("asset",e.subspecies().assetId()); row.put("flags",e.subspeciesFlags());
        row.put("medal",e.visibleMedal()); row.put("accessory",e.hasVisibleAccessory());
        row.put("scale",e.growthScale()); row.put("stage",e.lifeStage());
        row.put("pregnancy",e.livePregnancyProgress()); row.put("delivery",e.liveDeliveryTicks());
        row.put("children",e.liveChildCount()); row.put("born",e.liveBornMask());
        var children = new ArrayList<String>();
        for(int i=0;i<e.liveChildCount();i++)children.add(e.liveChildAssetId(i));
        row.put("childAssets",children); row.put("wound",e.visibleWound());
        row.put("filling",e.visibleFilling()); row.put("bandage",e.visibleBandage());
        return row;
    }

    private static List<YukkuriEntity> entities(ServerLevel level) {
        return level.getEntitiesOfClass(YukkuriEntity.class,AREA)
                .stream().sorted(Comparator.comparing(e->e.getUUID().toString())).toList();
    }
    private static YukkuriEntity subject(ServerLevel level,int index) {
        return entities(level).stream().filter(e->e.getTags().contains("integration_index_"+index)).findFirst().orElseThrow();
    }
    private static void force(ServerLevel level,boolean loaded) {
        for(int x=X/16;x<(X+64)/16;x++)for(int z=0;z<2;z++)level.setChunkForced(x,z,loaded);
    }
    private static void view(MinecraftServer server) {
        for(var player:server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);player.teleportTo(server.overworld(),X+16,69,22,180,20);
        }
    }
    private static void prepare(MinecraftServer server) {
        var level=server.overworld();force(level,true);
        if(!entities(level).isEmpty())throw new IllegalStateException("Use a fresh joint test world; setup does not replace prior subjects");
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
        level.setDayTime(6000);level.setWeatherParameters(24000,0,false,false);
        for(int x=X;x<X+64;x++)for(int z=0;z<32;z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
            for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            if(z>3&&z<16)level.setBlockAndUpdate(new BlockPos(x,72,z),Blocks.OAK_PLANKS.defaultBlockState());
        }
    }
    private static void setup(MinecraftServer server) {
        var level=server.overworld();
        if(!level.isPositionEntityTicking(new BlockPos(X+16,65,10)))throw new IllegalStateException("Prepare and wait for the joint arena to tick before spawning");
        if(!entities(level).isEmpty())throw new IllegalStateException("Joint subjects already exist");
        int index=0;
        for(var variant:YukkuriVariant.values()) {
            var e=ModEntities.forVariant(variant).create(level);e.moveTo(X+4+index*3,65,10,0,0);
            e.setNoAi(true);e.setPersistenceRequired();e.setOnGround(true);
            e.addTag("integration_subject");e.addTag("integration_index_"+index);
            var family=e.family().save();family.putBoolean("Initialized",true);family.putLong("Age",25000);
            family.putLong("Lifespan",72000);family.putLong("Nourishment",25000);family.putFloat("Scale",1);
            family.putFloat("AdultScale",1);family.putString("Stage","ADULT");e.family().load(family);
            var life=e.life().save();life.putFloat("Nutrition",100);life.putFloat("Energy",95);life.putFloat("Hygiene",100);e.life().load(life);
            var cognition=new CompoundTag();cognition.putInt("Innate",40+index);cognition.putInt("Learned",3+index);cognition.putString("Origin","joint-fixture");
            e.getPersistentData().put(Cognition.KEY,cognition);
            e.badges().initializeStock(index%2+1,server.getPlayerList().getPlayers().getFirst().getUUID());
            if(variant==YukkuriVariant.REIMU)e.subspecies().set(SubspeciesLife.Kind.WASA_REIMU,SubspeciesLife.Congenital.NORMAL);
            if(variant==YukkuriVariant.MARISA)e.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA,SubspeciesLife.Congenital.NORMAL);
            level.addFreshEntity(e);FamilyData.get(level).register(e);index++;
        }
        var mother=subject(level,0);
        mother.setNoAi(false);
        if(!mother.life().assignNaturalHome(mother.blockPosition()))throw new IllegalStateException("Pregnancy fixture home is not usable");
        var brood=FamilyData.get(level).assist(mother);
        if(brood==null)throw new IllegalStateException("Pregnancy fixture not eligible");
        brood.elapsed=brood.duration/2;mother.setNoAi(true);mother.syncFamily();FamilyData.get(level).setDirty();
        level.setBlockAndUpdate(CABINET,FreezerModule.CABINET.get().defaultBlockState());view(server);
    }

    private static Map<String,Object> snapshot(MinecraftServer server) {
        var level=server.overworld();var world=YukkuriWorldData.get(level);
        var rows=new ArrayList<Object>();
        for(var e:entities(level)) {
            var row=new LinkedHashMap<String,Object>();row.put("network",network(e));row.put("id",e.getId());
            row.put("index",e.getTags().stream().filter(t->t.startsWith("integration_index_")).findFirst().orElse("born_child"));
            row.put("family",e.family().save().toString());row.put("badges",e.badges().save().toString());
            row.put("cognition",Cognition.get(e));row.put("subspecies",e.subspecies().save().toString());
            row.put("custody",HumanCommerce.data(e).toString());
            row.put("position",e.position().toString());row.put("life",e.life().save().toString());
            row.put("readyForAssistance",e.family().readyForAssistance());row.put("operation",e.operations().pose().name());
            var brood=e.family().brood();if(brood!=null)row.put("brood",brood);
            rows.add(row);
        }
        var players=new ArrayList<Object>();
        for(var player:server.getPlayerList().getPlayers()) {
            var row=new LinkedHashMap<String,Object>();row.put("name",player.getGameProfile().getName());row.put("uuid",player.getUUID());
            row.put("held",player.getMainHandItem().toString());row.put("count",player.getMainHandItem().getCount());
            row.put("token",CarrierItem.token(player.getMainHandItem()));row.put("vault",world.freezer.count(player.getUUID()));
            row.put("emeralds",player.getInventory().items.stream().filter(s->s.is(net.minecraft.world.item.Items.EMERALD)).mapToInt(ItemStack::getCount).sum());
            row.put("inventory",player.getInventory().items.stream().filter(s->!s.isEmpty()).map(ItemStack::toString).toList());
            row.put("position",player.position().toString());
            row.put("occupants",world.freezer.occupants(player.getUUID()).stream().map(CompoundTag::toString).toList());players.add(row);
        }
        return Map.of("entities",rows,"players",players,"tick",level.getGameTime(),
                "broods",FamilyData.get(level).save(new CompoundTag(),level.registryAccess()).toString());
    }

    static void command(MinecraftServer server,JsonObject request) {
        try {
            var level=server.overworld();String op=request.get("op").getAsString();
            if(op.startsWith("integration-stem-")) {
                var forwarded=request.deepCopy();forwarded.addProperty("command",op);
                DetachedStemIntegrationPreview.command(server,forwarded);return;
            }
            if(op.equals("integration-human-state")) {
                var rows=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.human.HumanNpc.class,new AABB(798,63,-2,1060,86,70))
                        .stream().map(n->Map.of("uuid",n.getUUID().toString(),"role",n.siteRole(),"job",n.job().name(),
                                "production",n.production().save().toString())).sorted(Comparator.comparing(n->n.get("uuid"))).toList();
                P0Control.reply(request,true,rows);return;
            }
            if(op.equals("integration-human-menu-one")) {
                var npc=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.human.HumanNpc.class,new AABB(798,63,-2,1060,86,35),
                        n->n.siteRole().equals("shopkeeper")).getFirst();
                var player=server.getPlayerList().getPlayerByName(request.get("player").getAsString());
                player.setGameMode(GameType.CREATIVE);player.teleportTo(level,npc.getX(),npc.getY(),npc.getZ()-2,0,0);
                io.github.longshou.mcyukkuri.human.HumanMenu.open(player,npc);P0Control.reply(request,true,snapshot(server));return;
            }
            if(op.startsWith("integration-human-")) {
                var forwarded=request.deepCopy();forwarded.addProperty("command",op.substring("integration-".length()));
                HumanPreview.command(server,forwarded);return;
            }
            switch(op) {
                case "integration-prepare" -> prepare(server);
                case "integration-setup" -> setup(server);
                case "integration-view" -> view(server);
                case "integration-hand" -> {
                    var e=subject(level,request.has("index")?request.get("index").getAsInt():0);
                    if(request.get("item").getAsString().equals("reproductive")) {
                        e.setNoAi(false);if(!e.life().assignNaturalHome(e.blockPosition()))throw new IllegalStateException("Dose subject home unavailable");
                    }
                    int i=0;for(var player:server.getPlayerList().getPlayers()) {
                        if(request.has("player")&&!player.getGameProfile().getName().equals(request.get("player").getAsString()))continue;
                        String item=request.get("item").getAsString();
                        player.setGameMode(GameType.SURVIVAL);player.fallDistance=0;player.setDeltaMovement(0,0,0);
                        player.teleportTo(level,e.getX()+(i++==0?-.7:.7),65,e.getZ()+(item.equals("reproductive")?1.0:1.8),180,20);
                        player.setItemInHand(InteractionHand.MAIN_HAND,switch(item) {
                            case "carrier" -> new ItemStack(ModItems.CARRIER.get());
                            case "notebook" -> new ItemStack(ModItems.FIELD_NOTEBOOK.get());
                            case "juice" -> new ItemStack(ModItems.ORANGE_JUICE.get(),2);
                            case "reproductive" -> new ItemStack(io.github.longshou.mcyukkuri.life.Consumables.ITEMS.get(io.github.longshou.mcyukkuri.life.Consumables.Kind.REPRODUCTIVE).get(),2);
                            default -> throw new IllegalArgumentException(item);
                        });player.inventoryMenu.broadcastChanges();
                    }
                }
                case "integration-cabinet" -> {
                    for(var player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,CABINET.getX()+.5,65,CABINET.getZ()+2,180,30);
                    }
                }
                case "integration-release-view" -> {
                    for(var player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SURVIVAL);player.teleportTo(level,X+4.5,65,12.5,180,40);
                    }
                }
                case "integration-save" -> server.saveEverything(false,true,true);
                case "integration-freeze" -> entities(level).forEach(e->e.setNoAi(true));
                case "integration-birth" -> {
                    var mother=subject(level,0);var brood=mother.family().brood();
                    if(brood==null||brood.complete())throw new IllegalStateException("Original live brood missing or already complete");
                    // Explicitly accelerated acceptance; physical exit and newborn identity remain production behavior.
                    brood.duration=200;brood.elapsed=195;FamilyData.get(level).setDirty();mother.setNoAi(false);
                }
                case "integration-unload" -> {
                    force(level,false);for(var player:server.getPlayerList().getPlayers()) {
                        player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,X+512,80,512,0,0);
                    }
                }
                case "integration-unloaded" -> {
                    P0Control.reply(request,true,Map.of("chunk",level.getChunkSource().hasChunk(X/16,0),"entities",entities(level).size()));return;
                }
                case "integration-load" -> {force(level,true);view(server);}
                case "integration-snapshot" -> {}
                default -> throw new IllegalArgumentException(op);
            }
            P0Control.reply(request,true,snapshot(server));
        }catch(Throwable error){error.printStackTrace();P0Control.reply(request,false,error.toString());}
    }
}
