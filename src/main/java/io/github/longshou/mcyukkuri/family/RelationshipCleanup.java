package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** World-owned maintenance, registered once by MCYukkuri. No living entity is needed to advance it. */
public final class RelationshipCleanup {
    private static final int INTERVAL=20, WORK_PER_SLICE=128;
    private final List<ServerLevel> levels=new ArrayList<>();
    private final List<SettlementData> settlements=new ArrayList<>();
    private Iterator<ServerLevel> discovering;
    private YukkuriWorldData custody;
    private int initialization, rootLevel, lane, groupLevel;

    /** One cross-dimension entity lookup per step, without enumerating entities or loading chunks. */
    public static final class Presence {
        private final UUID id;
        private int dimension;
        private boolean alive;
        public Presence(UUID id) { this.id=id; }
        public boolean alive() { return alive; }
        public boolean complete(List<ServerLevel> levels) { return alive||dimension>=levels.size(); }
        public void step(List<ServerLevel> levels) {
            if(complete(levels))return;
            var entity=levels.get(dimension++).getEntity(id);
            alive=entity instanceof YukkuriEntity&&entity.isAlive()&&!entity.isRemoved();
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();if(server.getTickCount()%INTERVAL!=0)return;
        var family=FamilyData.get(server.overworld());
        for(int work=0;work<WORK_PER_SLICE;work++)family.cleanup.step(server,family);
    }

    private void step(MinecraftServer server,FamilyData family) {
        // Each loader only reads its own NBT. Bind references after get() has returned, never from load().
        if(initialization==0) {
            custody=YukkuriWorldData.get(server.overworld());custody.bindCleanup(family::retainForCleanup);initialization++;return;
        }
        if(initialization==1) {
            discovering=server.getAllLevels().iterator();initialization++;return;
        }
        if(initialization==2) {
            if(discovering.hasNext()) {
                var level=discovering.next();var data=SettlementData.get(level);data.bindCleanup(family::retainForCleanup);
                levels.add(level);settlements.add(data);return;
            }
            discovering=null;initialization++;return;
        }
        if(initialization==3) { if(!custody.cleanupRootStep())initialization++;return; }
        if(initialization==4) {
            if(rootLevel<settlements.size()) { if(!settlements.get(rootLevel).cleanupRootStep())rootLevel++;return; }
            initialization++;return;
        }
        // Initial roots are complete. Subsequent writes retain their new references synchronously.
        switch(lane++%2) {
            case 0 -> custody.cleanupEdgeStep(family);
            case 1 -> {
                if(!settlements.isEmpty()) {
                    int index=groupLevel;groupLevel=(groupLevel+1)%settlements.size();
                    settlements.get(index).cleanupGroupStep(levels.get(index),family,custody,levels);
                }
            }
            default -> throw new IllegalStateException("Unknown cleanup lane");
        }
        if(lane==2)lane=0;
    }
}
