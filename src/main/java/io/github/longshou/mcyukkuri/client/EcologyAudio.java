package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Starts once at the synchronized singing phase; stops on interruption or loss of tracking. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID,value=Dist.CLIENT)
public final class EcologyAudio {
    private static ClientLevel previous;
    private static final Map<UUID,Long> SEEN=new HashMap<>();
    private static final Map<UUID,Song> ACTIVE=new HashMap<>();
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(mc.level!=previous){ACTIVE.values().forEach(mc.getSoundManager()::stop);ACTIVE.clear();SEEN.clear();previous=mc.level;}
        if(mc.level==null||mc.isPaused())return;
        var present=new HashSet<UUID>();
        for(var visible:mc.level.entitiesForRendering())if(visible instanceof YukkuriEntity e){
            present.add(e.getUUID());
            if(!e.getBehaviorId().equals("sing"))continue;
            Long old=SEEN.put(e.getUUID(),e.getBehaviorStartTick());
            if(old!=null&&old==e.getBehaviorStartTick())continue;
            long age=e.level().getGameTime()-e.getBehaviorStartTick();
            if(age<0||age>5||e.isSilent()||!e.isAlive())continue;
            Song song=new Song(e);var prior=ACTIVE.put(e.getUUID(),song);
            if(prior!=null)mc.getSoundManager().stop(prior);mc.getSoundManager().play(song);
        }
        SEEN.keySet().retainAll(present);
        ACTIVE.entrySet().removeIf(entry->{
            if(present.contains(entry.getKey())&&!entry.getValue().expired())return false;
            mc.getSoundManager().stop(entry.getValue());return true;
        });
    }
    private static final class Song extends EntityBoundSoundInstance {
        private final YukkuriEntity owner;
        private final long start;
        Song(YukkuriEntity e){
            super(SoundEvent.createVariableRangeEvent(MCYukkuri.id("ecology.song."+(e.getVariant().assetId().contains("marisa")?"marisa":"reimu"))),
                    SoundSource.NEUTRAL,.65f,e.isBaby()?1.12f:1,e,e.getUUID().getLeastSignificantBits());
            owner=e;start=e.getBehaviorStartTick();
        }
        boolean expired(){return isStopped()||!owner.isAlive()||owner.isRemoved()||owner.isSilent()
                ||!owner.getBehaviorId().equals("sing")||owner.getBehaviorStartTick()!=start;}
        @Override public void tick(){super.tick();if(expired())stop();}
    }
    private EcologyAudio(){}
}
