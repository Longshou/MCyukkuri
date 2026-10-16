package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/** Uses actual overworld noise, structure placement and entity loading, without /place. */
public final class HumanNaturalChecks {
    private static final Map<String,BoundingBox> boxes=new LinkedHashMap<>();
    private static final List<String> TYPES=List.of("yukkuri_shop","yukkuri_labor_factory","yukkuri_manju_factory","yukkuri_bag_factory");
    public static void freezeNatural(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){
        if(e.getLevel().isClientSide||!e.getLevel().dimension().location().equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(P0Mod.ID,"human_world")))return;
        // Entity join can run while its chunk is still loading; do not perform collision queries here.
        if(e.getEntity() instanceof net.minecraft.world.entity.Mob mob)mob.setNoAi(true);
    }
    private static void require(boolean pass,String message) { if(!pass)throw new IllegalStateException(message); }
    public static void view(net.minecraft.server.level.ServerPlayer p,String name) {
        var box=boxes.get(name);require(box!=null,"Locate building first");
        var level=p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(P0Mod.ID,"human_world")));
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        p.teleportTo(level,(box.minX()+box.maxX())/2.0,box.maxY()+10,box.minZ()-36,0,25);
        p.getAbilities().flying=true;p.onUpdateAbilities();
    }
    public static Object run(MinecraftServer server,boolean locate) {
        return run(server,locate,null);
    }
    public static Object run(MinecraftServer server,boolean locate,String wanted) {
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(P0Mod.ID,"human_world")));
        require(level!=null,"Noise test dimension exists");
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        P0Control.write("human-natural-diagnostics",Map.of("structuresEnabled",server.getWorldData().worldGenOptions().generateStructures(),"generator",level.getChunkSource().getGenerator().getClass().getSimpleName(),"worldSeed",level.getSeed(),"origin",2100000));
        List<Object> results=new ArrayList<>();
        if(wanted!=null)require(TYPES.contains(wanted),"Known building type");
        if(locate) for(String name:wanted==null?TYPES:List.of(wanted)) {
            var holder=registry.getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE,MCYukkuri.id(name)));
            var found=level.getChunkSource().getGenerator().findNearestMapStructure(level,HolderSet.direct(holder),new BlockPos(2100000,0,2100000),24,false);
            require(found!=null,"Locate natural "+name);
            var pos=found.getFirst();
            var start=level.getChunk(pos.getX()>>4,pos.getZ()>>4).getStartForStructure(holder.value());
            require(start!=null&&start.isValid(),"Natural start exists: "+name);
            var box=start.getPieces().getFirst().getBoundingBox();boxes.put(name,box);
            for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)level.setChunkForced(x,z,true);
        }
        if(locate)return boxes.toString();
        require(boxes.size()==4,"Locate all four structures first");
            for(var box:boxes.values())for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)
                require(level.isPositionEntityTicking(new BlockPos(x*16+8,box.minY()+3,z*16+8)),"Waiting for generated entity chunks");
            for(var entry:boxes.entrySet()) {
                var area=AABB.of(entry.getValue()).inflate(3);
                var humans=level.getEntitiesOfClass(HumanNpc.class,area,n->!n.siteRole().isEmpty());var animals=level.getEntitiesOfClass(YukkuriEntity.class,area,y->!HumanCommerce.data(y).getString("Use").isEmpty());
                humans.forEach(n->n.setNoAi(true));animals.forEach(y->y.setNoAi(true));
                require(humans.size()==(entry.getKey().equals("yukkuri_labor_factory")?2:3),"Natural resident count: "+entry.getKey()+" = "+humans.stream().map(HumanNpc::siteRole).toList());
                require(animals.size()==(entry.getKey().equals("yukkuri_shop")?14:4),"Natural animal count: "+entry.getKey()+" = "+animals.size());
                require(animals.stream().noneMatch(y->HumanCommerce.data(y).contains("PendingRole")),"Natural animal ownership binds");
            }
            for(var entry:boxes.entrySet()) {
                var box=entry.getValue();var area=AABB.of(box).inflate(3);
                results.add(Map.of("structure",entry.getKey(),"bounds",box.toString(),"humans",level.getEntitiesOfClass(HumanNpc.class,area,n->!n.siteRole().isEmpty()).size(),"animals",level.getEntitiesOfClass(YukkuriEntity.class,area,y->!HumanCommerce.data(y).getString("Use").isEmpty()).size(),"ambientHumans",level.getEntitiesOfClass(HumanNpc.class,area,n->n.siteRole().isEmpty()).size(),"pass",true));
                for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)level.setChunkForced(x,z,false);
            }
            P0Control.write("human-natural-checks",results);
        return results;
    }
}
