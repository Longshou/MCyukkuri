package io.github.longshou.mcyukkuri.p0;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.*;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.*;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Real integrated-server actors and operations, captured by the existing framebuffer recorder. */
final class WildEventPreview {
    private static final List<YukkuriEntity> actors=new ArrayList<>();
    private static final List<Integer> chunks=new ArrayList<>();
    private static BlockPos center;
    private static YukkuriEntity a,b;
    private static HatBoatEntity boat;
    private static WildEventData.Scene scene;
    private static int index;
    private static String kind;
    static void command(MinecraftServer server,JsonObject request) {
        server.execute(()->{try {
            var level=server.overworld();var player=server.getPlayerList().getPlayers().getFirst();String op=request.get("command").getAsString();
            if(op.equals("event-setup")) {
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
                level.setDayTime(6000);level.setWeatherParameters(24000,0,false,false);player.setGameMode(GameType.SURVIVAL);player.setInvulnerable(true);
            } else if(op.equals("event-scene")) {
                clean(level);kind=request.get("kind").getAsString();scene=null;
                level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,server);
                center=new BlockPos(9000+48*index++,65,8);
                for(int x=center.getX()-8;x<=center.getX()+15;x++)for(int z=0;z<22;z++) {
                    int chunk=x>>4;if(!chunks.contains(chunk)){chunks.add(chunk);level.setChunkForced(chunk,0,true);level.setChunkForced(chunk,1,true);}
                    level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.GRASS_BLOCK.defaultBlockState());
                    for(int y=65;y<71;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                }
                for(int x=center.getX()-2;x<=center.getX()+2;x++)for(int z=-5;z<=-3;z++) {
                    level.setBlockAndUpdate(center.offset(x-center.getX(),-2,z),Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(center.offset(x-center.getX(),-1,z),Blocks.AIR.defaultBlockState());
                }
                a=spawn(level,kind.equals("expedition"),0,0);b=spawn(level,true,1.3,0);
                if(kind.equals("sisters")){var parent=spawn(level,false,6,5);a.family().generatedChild(parent,null);b.family().generatedChild(parent,null);parent.setNoAi(true);food(a,35);food(b,35);}
                if(kind.equals("single_parent")||kind.equals("adoption")){b.family().generatedChild(a,null);if(kind.equals("single_parent")){food(a,35);food(b,35);}}
                if(kind.equals("teach"))b.family().generatedChild(a,null);
                for(var e:actors)FamilyData.get(level).register(e);
                YukkuriWorldData.get(level).relate(a.getUUID(),b.getUUID(),20);YukkuriWorldData.get(level).relate(b.getUUID(),a.getUUID(),20);
                if(kind.equals("concert")||kind.equals("boat")||kind.equals("expedition")||kind.equals("forest-find")) {
                    var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,center,a.getUUID());SettlementData.get(level).add(group);a.settlement().join(group);b.settlement().join(group);
                }
                if(kind.equals("forest-find")){food(a,35);var apple=new net.minecraft.world.entity.item.ItemEntity(level,a.getX()+.2,65,a.getZ(),new ItemStack(Items.APPLE));apple.setNoPickUpDelay();level.addFreshEntity(apple);}
                if(kind.equals("expedition")){level.setBlockAndUpdate(center.offset(7,0,0),Blocks.SHORT_GRASS.defaultBlockState());}
                if(kind.equals("boat")){b.moveTo(center.getX()+2.5,65,center.getZ()+.5);for(int x=4;x<10;x++)for(int z=-2;z<3;z++){level.setBlockAndUpdate(center.offset(x,-2,z),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.WATER.defaultBlockState());}}
                if(kind.equals("abandonment")){b.discard();HumanCommerce.assign(a,player.getUUID(),"companion");}
                for(var e:List.of(a,b)){e.life().personality().fed(player.getUUID());e.life().personality().fed(player.getUUID());}
                if(kind.equals("boat")) {
                    for(int x=0;x<=5;x++)for(int z=-8;z<=-5;z++){level.setBlockAndUpdate(center.offset(x,-2,z),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.AIR.defaultBlockState());}
                    player.teleportTo(level,center.getX()+2,64,center.getZ()-6,0,4);
                } else player.teleportTo(level,center.getX()+.65,64,center.getZ()-3.4,0,7);
            } else if(op.equals("event-start")) {
                if(kind.equals("sisters")||kind.equals("single_parent"))home(level,a);
                for(var e:actors){e.family().stop();e.habitat().stop();e.social().stop();}
                scene=switch(kind) {
                    case "sisters" -> WildEvents.begin(WildEventData.Kind.SISTERS,a,List.of(b),player);
                    case "single_parent" -> WildEvents.begin(WildEventData.Kind.SINGLE_PARENT,a,List.of(b),player);
                    case "adoption" -> WildEvents.begin(WildEventData.Kind.ADOPTION,a,List.of(b),player);
                    case "concert" -> WildEvents.begin(WildEventData.Kind.CONCERT,a,List.of(b),player);
                    case "dialect" -> WildCards.begin("imitate",a,List.of(b),player);
                    case "teach" -> WildCards.begin("teach",a,List.of(b),player);
                    case "forest-find" -> WildCards.begin("forest-find",a,List.of(b),player);
                    case "expedition" -> Expedition.form(a,List.of(b),center.offset(7,0,0));
                    case "boat" -> {boat=HatBoatEntity.createFor(b,center.offset(4,-1,0));yield boat==null?null:RiverEncounter.begin(a,boat);}
                    case "abandonment" -> {WildEvents.abandon(a,player);yield WildEventData.get(level).forMember(a.getUUID());}
                    default -> throw new IllegalArgumentException(kind);
                };
                if(scene==null)throw new IllegalStateException("scene refused: "+kind+" "+a.getBehaviorId()+" "+b.getBehaviorId());
                if(kind.equals("forest-find"))level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,server);
            } else if(op.equals("event-feed")) {
                var eater=request.get("member").getAsInt()==0?a:b;player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE));eater.interact(player,InteractionHand.MAIN_HAND);
            } else if(op.equals("event-capture")){YukkuriWorldData.get(level).capture(b);
            } else if(op.equals("event-rescue")){if(boat==null||!boat.rescue(center.offset(2,0,2),player.getUUID()))throw new IllegalStateException("real boat rescue refused");
            } else if(op.equals("event-state")) {
                P0Control.reply(request,true,Map.of("scene",scene==null?"none":scene.id+":"+scene.phase+":"+scene.result,"fed",scene==null?Set.of():scene.fed,
                        "actors",actors.stream().filter(Entity::isAlive).map(e->Map.of("id",e.getUUID(),"position",e.position().toString(),"behavior",e.getBehaviorId(),"expression",e.getExpressionId(),"speech",e.getDialogueKey(),"food",e.life().nutrition())).toList()));return;
            } else if(op.equals("event-notebook")) {
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.FIELD_NOTEBOOK.get()));player.inventoryMenu.broadcastChanges();
                P0Control.reply(request,true,Map.of("target",a.getId(),"uuid",a.getUUID()));return;
            } else if(op.equals("event-cleanup")){clean(level);for(int chunk:chunks){level.setChunkForced(chunk,0,false);level.setChunkForced(chunk,1,false);}chunks.clear();}
            else throw new IllegalArgumentException(op);
            P0Control.reply(request,true,Map.of("command",op));
        }catch(Throwable e){P0Control.reply(request,false,e.toString());}});
    }
    private static YukkuriEntity spawn(ServerLevel level,boolean marisa,double x,double z) {
        var e=(marisa?ModEntities.MARISA:ModEntities.REIMU).get().create(level);e.moveTo(center.getX()+x,65,center.getZ()+z,180,0);e.setOnGround(true);e.setPersistenceRequired();
        var family=e.family().save();family.putBoolean("Initialized",true);family.putString("Stage","ADULT");family.putLong("Age",20000);family.putLong("Nourishment",20000);family.putLong("Lifespan",72000);e.family().load(family);
        food(e,80);var personality=e.life().personality().save();personality.putIntArray("Innate",new int[]{75,60,75,70,50,70});e.life().personality().load(personality);
        var cooldown=new CompoundTag();cooldown.putInt("Cooldown",1200);e.groupInteraction().load(cooldown);e.life().deferSocial(1200);
        var cognition=new CompoundTag();cognition.putInt("Innate",kind.equals("teach")?(x==0?500:400):kind.equals("forest-find")?1000:100);cognition.putString("Origin","preview_fixture");e.getPersistentData().put(io.github.longshou.mcyukkuri.cognition.Cognition.KEY,cognition);
        level.addFreshEntity(e);actors.add(e);return e;
    }
    private static void food(YukkuriEntity e,float value){var t=e.life().save();t.putFloat("Nutrition",value);t.putFloat("Energy",90);t.putFloat("Hygiene",100);t.putFloat("Bladder",0);e.life().load(t);}
    private static void home(ServerLevel level,YukkuriEntity e){var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(center.offset(5,0,-4),ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));if(placed==null)throw new IllegalStateException("shelter placement refused");if(!e.habitat().choose(placed.shelter()))throw new IllegalStateException("shelter claiming refused: "+e.position()+" ground="+e.onGround()+" bed="+placed.shelter().getBlockPos());e.habitat().stop();}
    private static void clean(ServerLevel level){for(var e:actors){var s=WildEventData.get(level).forMember(e.getUUID());if(s!=null)WildEvents.finish(level,s,WildEventData.Phase.INTERRUPTED,"preview_cleanup");e.discard();}actors.clear();if(boat!=null){boat.discard();boat=null;}}
}
