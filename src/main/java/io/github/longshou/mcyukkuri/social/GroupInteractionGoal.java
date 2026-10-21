package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.EnumSet;
import java.util.List;
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
        boolean ended, shorePending;
        long shoreDeadline;
        Bout(YukkuriEntity first, YukkuriEntity second, Kind kind) { this.first=first;this.second=second;this.kind=kind; }
    }
    private final YukkuriEntity entity;
    private final PeerDefense defense;
    private Bout bout;
    private int cooldown, scanWait;
    private boolean defending;
    public GroupInteractionGoal(YukkuriEntity entity) { this.entity=entity;defense=new PeerDefense(entity);setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
    public PeerDefense defense() { return defense; }
    public void interrupt() { stop(); defense.stop(); }
    public boolean active() { return bout!=null&&!bout.ended; }
    public String activity() {
            return !active()||bout.shorePending?Behaviors.IDLE:defending?Behaviors.PROTECT_PEER:bout.kind==Kind.BULLY?Behaviors.BULLY_PEER:bout.contactTicks<20?Behaviors.SUMO_PREPARE:Behaviors.SUMO_PUSH; }
    public int cooldown() { return cooldown; }
    public String detail() {
        return (!active()?"idle":"kind="+bout.kind+" shorePending="+bout.shorePending
                +" contact="+bout.contactTicks+" elapsed="+bout.ticks) + " " + defense.detail();
    }
    public void clock() {
        defense.clock();
        if(cooldown>0)cooldown--;if(scanWait>0)scanWait--;
        if(active()&&bout.shorePending){if(bout.first==entity)tickShoreChallenge();}
        else if(active()&&!valid())stop();
    }
    public CompoundTag save() { var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);return tag; }
    public void load(CompoundTag tag) { interrupt();cooldown=Math.clamp(tag.getInt("Cooldown"),0,24000); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    private static boolean available(YukkuriEntity e) {
        return !WildEvents.reserved(e) && !e.groupInteraction().defense().active() && e.isAlive()&&!e.isRemoved()&&!e.isPanicking()&&!e.isCommandPlaying()&&!e.operations().active()
                &&!(e.groupInteraction().active()?e.life().nutrition()<15||e.life().energy()<15||e.isOnFire()||e.isInWaterOrBubble():e.life().hasUrgentNeed())&&!e.life().isSleeping()&&e.life().mealTicks()==0
                &&e.life().nutrition()>=(e.groupInteraction().active()?15:40)&&e.life().energy()>=(e.groupInteraction().active()?15:35)&&e.body().condition().readyForGroupPlay()
                &&!e.dailyLife().active()&&!e.playLife().active()&&!e.family().active()&&!e.house().active()
                &&!e.independence().active()&&!e.forageTrip().outing()&&!e.settlement().active()&&!e.settlement().duties().active();
    }
    private static boolean space(YukkuriEntity e) {
        if(e.isPassenger()||!e.onGround())return false;
        BlockPos pos=e.blockPosition();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(!e.life().dryStand(pos.offset(x,0,z)))return false;
        return true;
    }
    private static boolean challengeSpace(YukkuriEntity e) {
        if(!e.isPassenger())return space(e);
        var boat=EcologyMovement.boat(e);
        return boat!=null&&EcologyMovement.captain(e)&&EcologyMovement.supported(e)&&boat.nearestBank()!=null;
    }
    private static void requestPlayShore(YukkuriEntity e) {
        if(e.isPassenger())e.aquaticLife().requestShoreWork(ShoreWork.PLAY);
        else e.aquaticLife().holdShoreWork(ShoreWork.PLAY);
    }
    private static boolean waitingSafe(YukkuriEntity e) {
        return e.isAlive()&&!e.isRemoved()&&!e.isNoAi()&&!e.isPanicking()&&!e.isOnFire()
                &&!e.isInWaterOrBubble()&&!e.isCommandPlaying()&&!e.operations().active()
                &&e.life().nutrition()>=15&&e.life().energy()>=15&&!e.life().isSleeping();
    }
    private void tickShoreChallenge() {
        var current=bout;var peer=current.second;
        if(level().getGameTime()>=current.shoreDeadline||peer.level()!=entity.level()
                ||!waitingSafe(entity)||!waitingSafe(peer)||entity.distanceToSqr(peer)>256){
            GameplayLog.event(entity,"social","shore challenge cancelled peer={} reason=timeout_or_state",peer.getUUID());
            stop();return;
        }
        entity.aquaticLife().holdShoreWork(ShoreWork.PLAY);peer.aquaticLife().holdShoreWork(ShoreWork.PLAY);
        if(entity.isPassenger()||peer.isPassenger()||!entity.onGround()||!peer.onGround()
                ||entity.aquaticLife().shoreVisitRequested()||peer.aquaticLife().shoreVisitRequested())return;
        var relations=YukkuriWorldData.get(level());
        if(!available(entity)||!available(peer)||!space(entity)||!space(peer)||entity.distanceToSqr(peer)>36
                ||!entity.hasLineOfSight(peer)||relations.affinity(entity.getUUID(),peer.getUUID())<BondLevels.AFFINITY_ACQUAINTED
                ||relations.affinity(peer.getUUID(),entity.getUUID())<BondLevels.AFFINITY_ACQUAINTED){
            GameplayLog.event(entity,"social","shore challenge cancelled peer={} reason=landing_recheck",peer.getUUID());
            stop();return;
        }
        current.shorePending=false;
        GameplayLog.event(entity,"social","shore challenge resumed peer={}",peer.getUUID());
    }
    public boolean challenge(YukkuriEntity peer) {
        if(peer==entity||peer.level()!=entity.level()||cooldown>0||peer.groupInteraction().cooldown>0||active()||peer.groupInteraction().active()
                ||!available(entity)||!available(peer)||!entity.family().adult()||!peer.family().adult()
                ||entity.distanceToSqr(peer)>16||!entity.hasLineOfSight(peer)||!challengeSpace(entity)||!challengeSpace(peer))return false;
        var relations=YukkuriWorldData.get(level());
        if(relations.affinity(entity.getUUID(),peer.getUUID())<BondLevels.AFFINITY_ACQUAINTED||relations.affinity(peer.getUUID(),entity.getUUID())<BondLevels.AFFINITY_ACQUAINTED)return false;
        return reserve(peer,Kind.SUMO);
    }
    private boolean reserve(YukkuriEntity peer, Kind kind) {
        if(kind!=Kind.SUMO&&(!space(entity)||!space(peer)))return false;
        entity.social().stop();peer.social().stop();
        bout=new Bout(entity,peer,kind);peer.groupInteraction().bout=bout;
        entity.life().personality().protectRelation(peer.getUUID());peer.life().personality().protectRelation(entity.getUUID());
        cooldown=peer.groupInteraction().cooldown=2400;
        if(kind==Kind.SUMO&&(entity.isPassenger()||peer.isPassenger())){
            bout.shorePending=true;bout.shoreDeadline=level().getGameTime()+1200;
            requestPlayShore(entity);requestPlayShore(peer);
            GameplayLog.event(entity,"social","shore challenge accepted peer={} deadline={}",peer.getUUID(),bout.shoreDeadline);
        }
        entity.showReaction(kind==Kind.SUMO?"happy":"surprise",kind==Kind.SUMO?"peer":"mine",100);
        if (kind != Kind.SUMO) peer.mind().felt(EmotionEvent.FRIGHTENED); peer.showReaction(kind==Kind.SUMO?"happy":"surprise",kind==Kind.SUMO?"known_peer":"afraid",100);
        GameplayLog.event(entity, "social", "bout reserved kind={} peer={}", kind, peer.getUUID());
        return true;
    }
    @Override public boolean canUse() {
        if(active())return !bout.shorePending&&valid();
        if(scanWait>0||cooldown>0||!available(entity)||entity.isNoAi())return false;
        scanWait=160;
        for(var peer:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(4),e->e!=entity&&!e.isNoAi())) {
            if(entity.getRandom().nextFloat()<.25f*entity.species().traits().sumo()&&challenge(peer))return !bout.shorePending;
            var personality=entity.life().personality();
            // A provocative species (Marisa) picks fights sooner, and everybody picks on the easily bullied (Chen).
            if(personality.friendliness()<40&&available(peer)&&!peer.groupInteraction().active()
                    &&peer.groupInteraction().cooldown==0&&entity.family().adult()&&entity.getBbWidth()>peer.getBbWidth()*1.3
                    &&(YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_HOSTILE||
                            !entity.treasure().full()&&peer.treasure().count()>0&&YukkuriWorldData.get(level()).affinity(entity.getUUID(),
                            peer.getUUID())<=BondLevels.AFFINITY_NEUTRAL)&&entity.hasLineOfSight(peer)&&space(entity)&&space(peer))
            {
                cooldown = 600;
                if (entity.getRandom().nextInt(100) < personality.escalationScore()) return reserve(peer,Kind.BULLY);
                return false;
            }
            // A hatless stranger is despised and bullied through headwear.Contempt and the sanction, not here.
        }
        return false;
    }
    private boolean live(YukkuriEntity e) { return e.level()==entity.level()&&level().getEntity(e.getUUID())==e&&available(e); }
    private boolean valid() {
        if(!active()||bout.shorePending||bout.first.isPassenger()||bout.second.isPassenger()||!live(bout.first)||!live(bout.second)||bout.first.distanceToSqr(bout.second)>36||bout.ticks>=240)return false;
        if(bout.protectedPeer!=null&&(!bout.protectedPeer.isAlive()||bout.protectedPeer.isRemoved()||bout.protectedPeer.level()!=entity.level()
                ||level().getEntity(bout.protectedPeer.getUUID())!=bout.protectedPeer||bout.first.distanceToSqr(bout.protectedPeer)>64))return false;
        return bout.first.hasLineOfSight(bout.second)&&bout.first.groupInteraction().bout==bout&&bout.second.groupInteraction().bout==bout;
    }
    @Override public boolean canContinueToUse() { return valid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if(!valid()){stop();return;}
        var current=bout;var peer=entity==current.first?current.second:current.first;
        entity.aquaticLife().holdShoreWork(ShoreWork.PLAY);
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
            stop();
            float healthBefore = peer.getHealth();
            boolean hit = peer.hurt(level().damageSources().mobAttack(entity),1);
            if(hit)entity.life().personality().experienced(YukkuriPersonality.Trait.FRIENDLINESS,-1);
            GameplayLog.event(entity, "social", "bully strike peer={} accepted={} health={} -> {}", peer.getUUID(), hit, healthBefore, peer.getHealth());
            if(entity.treasure().snatchFrom(peer)) {
                entity.species().narrate("steal");
                if (!hit) peer.respondToBullying(entity);
            }
            if(!peer.life().hasAccessory()&&!Recognition.recognizes(entity,peer)&&peer.isAlive()){peer.mind().add(6,"bullied_hatless");peer.mind().felt(EmotionEvent.SOCIAL_HURT); peer.showReaction("teary","settlement_hurt",100);}
            return;
        }
        if(current.kind==Kind.PROTECT) {
            stop();peer.frightenFrom(entity);entity.showReaction("neutral","known_peer",100);
            GameplayLog.event(entity, "social", "protection frighten requested attacker={} protected={}",
                    peer.getUUID(), current.protectedPeer == null ? null : current.protectedPeer.getUUID());
            return;
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
            entity.life().personality().experienced(YukkuriPersonality.Trait.FRIENDLINESS,1);peer.life().personality().experienced(YukkuriPersonality.Trait.FRIENDLINESS,1);
            if(YukkuriConfig.SUMO_DAMAGE.get())loser.hurtInBout(level().damageSources().mobAttack(winner),1);
            GameplayLog.event(entity, "social", "sumo completed winner={} loser={} winnerHealth={} loserHealth={} contact={}",
                    winner.getUUID(), loser.getUUID(), winner.getHealth(), loser.getHealth(), current.contactTicks);
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
            if(!close||!witness.family().adult()||goal.cooldown>0||goal.active())continue;
            if(witness.getBbWidth()<attacker.getBbWidth()*.7||witness.getHealth()<witness.getMaxHealth()*.6)continue;
            if(!available(attacker))continue;
            goal.cooldown = 600;
            float roll = witness.getRandom().nextFloat();
            float chance = witness.life().personality().helpProbability(true, true);
            GameplayLog.event(witness, "social", "peer protection decision victim={} attacker={} chance={} roll={} accepted={}",
                    victim.getUUID(), attacker.getUUID(), chance, roll, roll < chance);
            if (roll >= chance) continue;
            attacker.groupInteraction().stop();
            if(!goal.reserve(attacker,Kind.PROTECT))continue;
            goal.defending=true;goal.bout.protectedPeer=victim;
            witness.life().personality().decided("protect_peer");
            witness.showReaction("surprise","mine",100);relations.relate(witness.getUUID(),attacker.getUUID(),-5);break;
        }
    }
    @Override public void stop() {
        var current=bout;bout=null;defending=false;
        if(current!=null&&!current.ended){current.ended=true;
            GameplayLog.event(current.first, "social", "bout ended kind={} peer={} elapsed={} contact={} protected={}",
                    current.kind, current.second.getUUID(), current.ticks, current.contactTicks,
                    current.protectedPeer == null ? null : current.protectedPeer.getUUID());
            for(var e:List.of(current.first,current.second)) {
            e.aquaticLife().releaseShoreWork(ShoreWork.PLAY);
            e.life().personality().releaseRelation((e == current.first ? current.second : current.first).getUUID());
            e.groupInteraction().bout=null;e.groupInteraction().defending=false;e.getNavigation().stop();
        }}
    }
}
