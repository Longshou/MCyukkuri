package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** A shared, transient bout reserves both live participants. Reload cancels it, preserving cooldown. */
public final class GroupInteractionGoal extends Goal {
    private enum Kind { SUMO, BULLY, PROTECT }
    private static final class Bout {
        final YukkuriEntity first, second;
        final Kind kind;
        YukkuriEntity protectedPeer;
        int ticks, contactTicks;
        boolean ended;
        Bout(YukkuriEntity first, YukkuriEntity second, Kind kind) { this.first=first;this.second=second;this.kind=kind; }
    }
    private final YukkuriEntity entity;
    private Bout bout;
    private int cooldown, scanWait;
    private boolean defending;
    public GroupInteractionGoal(YukkuriEntity entity) { this.entity=entity;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
    public boolean active() { return bout!=null&&!bout.ended; }
    public String activity() { return !active()?Behaviors.IDLE:defending?Behaviors.PROTECT_PEER:bout.kind==Kind.BULLY?Behaviors.BULLY_PEER:bout.contactTicks<20?Behaviors.SUMO_PREPARE:Behaviors.SUMO_PUSH; }
    public int cooldown() { return cooldown; }
    public void clock() { if(cooldown>0)cooldown--;if(scanWait>0)scanWait--;if(active()&&!valid())stop(); }
    public CompoundTag save() { var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);return tag; }
    public void load(CompoundTag tag) { stop();cooldown=Math.clamp(tag.getInt("Cooldown"),0,24000); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private static boolean available(YukkuriEntity e) {
        return !io.github.longshou.mcyukkuri.event.WildEvents.reserved(e) && e.isAlive()&&!e.isRemoved()&&!e.isPanicking()&&!e.isCommandPlaying()&&!e.operations().active()
                &&!e.life().hasUrgentNeed()&&!e.life().isSleeping()&&e.life().mealTicks()==0
                &&e.life().nutrition()>=45&&e.life().energy()>=40&&e.body().condition().readyForGroupPlay()
                &&!e.dailyLife().active()&&!e.playLife().active()&&!e.family().active()&&!e.house().active();
    }
    private static boolean space(YukkuriEntity e) {
        BlockPos pos=e.blockPosition();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(!e.life().dryStand(pos.offset(x,0,z)))return false;
        return true;
    }
    public boolean challenge(YukkuriEntity peer) {
        if(peer==entity||peer.level()!=entity.level()||cooldown>0||peer.groupInteraction().cooldown>0||active()||peer.groupInteraction().active()
                ||!available(entity)||!available(peer)||!entity.family().adult()||!peer.family().adult()
                ||entity.distanceToSqr(peer)>16||!entity.hasLineOfSight(peer)||!space(entity)||!space(peer))return false;
        var relations=YukkuriWorldData.get(level());
        if(relations.affinity(entity.getUUID(),peer.getUUID())<BondLevels.AFFINITY_ACQUAINTED||relations.affinity(peer.getUUID(),entity.getUUID())<BondLevels.AFFINITY_ACQUAINTED)return false;
        return reserve(peer,Kind.SUMO);
    }
    private boolean reserve(YukkuriEntity peer, Kind kind) {
        entity.social().stop();peer.social().stop();
        bout=new Bout(entity,peer,kind);peer.groupInteraction().bout=bout;
        cooldown=peer.groupInteraction().cooldown=2400;
        entity.showReaction(kind==Kind.SUMO?"happy":"surprise",kind==Kind.SUMO?"peer":"mine",100);
        if (kind != Kind.SUMO) peer.mind().felt(EmotionEvent.FRIGHTENED); peer.showReaction(kind==Kind.SUMO?"happy":"surprise",kind==Kind.SUMO?"known_peer":"afraid",100);
        return true;
    }
    @Override public boolean canUse() {
        if(active())return valid();
        if(scanWait>0||cooldown>0||!available(entity)||entity.isNoAi())return false;
        scanWait=160;
        for(var peer:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(4),e->e!=entity&&!e.isNoAi())) {
            if(entity.getRandom().nextFloat()<.25f*entity.species().traits().sumo()&&challenge(peer))return true;
            var personality=entity.life().personality();
            // A provocative species (Marisa) picks fights sooner, and everybody picks on the easily bullied (Chen).
            float bully=65/(entity.species().traits().provoke()*peer.species().traits().bullied());
            if(personality.escalationScore()>=bully&&personality.selfishness()>=bully&&available(peer)&&!peer.groupInteraction().active()
                    &&peer.groupInteraction().cooldown==0&&entity.family().adult()&&entity.getBbWidth()>peer.getBbWidth()*1.3
                    &&(YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_HOSTILE||!entity.treasure().full()&&peer.treasure().count()>0&&YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_NEUTRAL)&&entity.hasLineOfSight(peer)&&space(entity)&&space(peer))
                return reserve(peer,Kind.BULLY);
            // A hatless stranger is despised and bullied through headwear.Contempt and the sanction, not here.
        }
        return false;
    }
    private boolean live(YukkuriEntity e) { return e.level()==entity.level()&&level().getEntity(e.getUUID())==e&&available(e); }
    private boolean valid() {
        if(!active()||!live(bout.first)||!live(bout.second)||bout.first.distanceToSqr(bout.second)>36||bout.ticks>=240)return false;
        if(bout.protectedPeer!=null&&(!bout.protectedPeer.isAlive()||bout.protectedPeer.isRemoved()||bout.protectedPeer.level()!=entity.level()
                ||level().getEntity(bout.protectedPeer.getUUID())!=bout.protectedPeer||bout.first.distanceToSqr(bout.protectedPeer)>64))return false;
        return bout.first.hasLineOfSight(bout.second)&&bout.first.groupInteraction().bout==bout&&bout.second.groupInteraction().bout==bout;
    }
    @Override public boolean canContinueToUse() { return valid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if(!valid()){stop();return;}
        var current=bout;var peer=entity==current.first?current.second:current.first;
        entity.getLookControl().setLookAt(peer,30,30);
        double contact=(entity.getBbWidth()+peer.getBbWidth())*.5+.15;
        if(entity.distanceToSqr(peer)>contact*contact) {
            if(current.ticks%10==0&&!entity.life().navigate(peer.blockPosition(),.8,false)){stop();return;}
        } else entity.getNavigation().stop();
        if(entity!=current.first)return;
        current.ticks++;
        if(entity.distanceToSqr(peer)>contact*contact)return;
        current.contactTicks++;
        if(current.contactTicks<20)return;
        if(current.kind==Kind.BULLY) {
            entity.life().personality().decided("bully_peer");
            stop();if(peer.hurt(level().damageSources().mobAttack(entity),1))entity.life().personality().experienced(YukkuriPersonality.Disposition.AGGRESSION,1);
            YukkuriWorldData.get(level()).relate(peer.getUUID(),entity.getUUID(),-8);if(entity.treasure().snatchFrom(peer))entity.species().narrate("steal");
            if(!peer.life().hasAccessory()&&peer.isAlive()){peer.mind().add(6,"bullied_hatless");peer.mind().felt(EmotionEvent.SOCIAL_HURT); peer.showReaction("teary","settlement_hurt",100);}
            return;
        }
        if(current.kind==Kind.PROTECT) {
            stop();peer.frightenFrom(entity);entity.showReaction("neutral","known_peer",100);return;
        }
        if(current.contactTicks%20!=0)return;
        if(!entity.life().spendEnergy(2)||!peer.life().spendEnergy(2)){stop();return;}
        Vec3 direction=peer.position().subtract(entity.position()).multiply(1,0,1).normalize();
        if(direction.lengthSqr()<.01)direction=new Vec3(1,0,0);
        boolean firstStronger=entity.getBbWidth()*entity.life().energy()*entity.species().traits().might()>=peer.getBbWidth()*peer.life().energy()*peer.species().traits().might();
        var pushed=firstStronger?peer:entity;
        pushed.setDeltaMovement(direction.scale(firstStronger?.13:-.13));pushed.hurtMarked=true;
        if(current.contactTicks>=80) {
            var winner=firstStronger?entity:peer;var loser=firstStronger?peer:entity;
            stop();winner.showReaction("happy","puff",120);loser.showReaction("surprise","shy",120);
            var relations=YukkuriWorldData.get(level());relations.relate(entity.getUUID(),peer.getUUID(),1);relations.relate(peer.getUUID(),entity.getUUID(),1);
            entity.life().personality().experienced(YukkuriPersonality.Disposition.AGGRESSION,-1);peer.life().personality().experienced(YukkuriPersonality.Disposition.AGGRESSION,-1);
            if(YukkuriConfig.SUMO_DAMAGE.get())loser.hurtInBout(level().damageSources().mobAttack(winner),1);
        }
    }
    /** Only present, awake witnesses with actual sight react to an attack. No later knowledge is fabricated. */
    public static void witnessedAttack(YukkuriEntity victim,YukkuriEntity attacker) {
        if(!(victim.level() instanceof ServerLevel level))return;
        var relations=YukkuriWorldData.get(level);
        for(var witness:level.getEntitiesOfClass(YukkuriEntity.class,victim.getBoundingBox().inflate(6),e->e!=victim&&e!=attacker)) {
            if(!available(witness)||witness.isNoAi()||!witness.hasLineOfSight(victim)||!witness.hasLineOfSight(attacker))continue;
            witness.mind().felt(EmotionEvent.FRIGHTENED); witness.showReaction("surprise","afraid",100);
            // Kin and friends defend only someone they can actually recognise: without its accessory the victim is a stranger to them.
            boolean close=witness.life().canRecognize(victim)&&(Bonds.careKin(witness, victim)||relations.affinity(witness.getUUID(),victim.getUUID())>=BondLevels.AFFINITY_ATTACHED);
            var goal=witness.groupInteraction();
            if(!close||!witness.family().adult()||!witness.life().personality().protects()||goal.cooldown>0||goal.active())continue;
            if(witness.getBbWidth()<attacker.getBbWidth()*.7||witness.getHealth()<witness.getMaxHealth()*.6)continue;
            if(!available(attacker))continue;
            attacker.groupInteraction().stop();goal.reserve(attacker,Kind.PROTECT);goal.defending=true;goal.bout.protectedPeer=victim;
            witness.life().personality().decided("protect_peer");
            witness.showReaction("surprise","mine",100);relations.relate(witness.getUUID(),attacker.getUUID(),-5);break;
        }
    }
    @Override public void stop() {
        var current=bout;bout=null;defending=false;
        if(current!=null&&!current.ended){current.ended=true;for(var e:java.util.List.of(current.first,current.second)) {
            e.groupInteraction().bout=null;e.groupInteraction().defending=false;e.getNavigation().stop();
        }}
    }
}
