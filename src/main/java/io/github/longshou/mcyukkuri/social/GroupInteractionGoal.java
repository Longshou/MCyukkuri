package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;

import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** A shared, transient bout reserves both live participants. Reload cancels it, preserving cooldown. */
public final class GroupInteractionGoal extends Goal {
    private enum Kind { SUMO, BULLY, PROTECT, CONTEST }
    /** A bout nobody ticks (its goal never took the body) ends at this age in game ticks. */
    private static final int STALE = 300;
    private static final class Bout {
        final YukkuriEntity first, second;
        final Kind kind;
        YukkuriEntity protectedPeer;
        Contest contest;
        long created;
        int ticks, contactTicks;
        boolean ended;
        Choice rule = Choice.ALLOW;
        String lastPushedFace = "";
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
    /** Verified contact already accumulated by the running bout; observation only. */
    public int contactProgress() { return active() ? bout.contactTicks : 0; }
    /** The other body of a contest that has reached real contact, or null; standing face to face is not yet a fight. */
    public UUID contestRival() {
        if (!active() || bout.kind != Kind.CONTEST || bout.contest == null || !bout.contest.fighting()) return null;
        return (entity == bout.first ? bout.second : bout.first).getUUID();
    }
    public String activity() {
        if (!active()) return Behaviors.IDLE;
        if (defending) return Behaviors.PROTECT_PEER;
        return switch (bout.kind) {
            case BULLY -> Behaviors.BULLY_PEER;
            case CONTEST -> bout.contest == null ? Behaviors.CONTEST_FACEOFF
                    : bout.contest.shoving() ? Behaviors.SUMO_PUSH
                    : bout.contest.sumo() ? Behaviors.SUMO_PREPARE : Behaviors.CONTEST_FACEOFF;
            case SUMO -> bout.contactTicks < 20 ? Behaviors.SUMO_PREPARE : Behaviors.SUMO_PUSH;
            case PROTECT -> Behaviors.IDLE;
        };
    }
    public int cooldown() { return cooldown; }
    public String detail() {
        return (!active()?"idle":"kind="+bout.kind
                +" contact="+bout.contactTicks+" elapsed="+bout.ticks) + " " + defense.detail();
    }
    public void clock() {
        defense.clock();
        if(cooldown>0)cooldown--;if(scanWait>0)scanWait--;
        if(active()&&!valid())stop();
    }
    public CompoundTag save() { var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);return tag; }
    public void load(CompoundTag tag) { interrupt();cooldown=Math.clamp(tag.getInt("Cooldown"),0,24000); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    /** Free of every other engagement; the needs are asked separately, because a contest is fought by the needy. */
    private static boolean free(YukkuriEntity e) {
        return !WildEvents.reserved(e) && !e.groupInteraction().defense().active() && e.isAlive()&&!e.isRemoved()&&!e.isPanicking()&&!e.isCommandPlaying()&&!e.operations().active()
                &&!e.life().isSleeping()&&e.life().mealTicks()==0&&e.body().condition().readyForGroupPlay()
                &&!e.dailyLife().active()&&!e.playLife().active()&&!e.family().active()&&!e.house().active()
                &&!e.independence().active()&&!e.forageTrip().outing()&&!e.settlement().active()&&!e.settlement().duties().active();
    }
    private static boolean available(YukkuriEntity e) {
        return free(e)
                &&!(e.groupInteraction().active()?e.life().nutrition()<15||e.life().energy()<15||e.isOnFire()||e.isInWaterOrBubble():e.life().hasUrgentNeed())
                &&e.life().nutrition()>=(e.groupInteraction().active()?15:40)&&e.life().energy()>=(e.groupInteraction().active()?15:35);
    }
    /** A contest starts and runs with any needs, tired included (a sleepy or hungry body is who contests a bed), but not in fire or water. */
    private static boolean contestant(YukkuriEntity e) {
        return free(e)&&!e.isOnFire()&&!e.isInWaterOrBubble()&&e.family().adult();
    }
    private static boolean space(YukkuriEntity e) {
        if(e.isPassenger()||!e.onGround())return false;
        BlockPos pos=e.blockPosition();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(!e.life().dryStand(pos.offset(x,0,z)))return false;
        return true;
    }
    private static boolean challengeSpace(YukkuriEntity e) {
        return !e.isPassenger()&&space(e);
    }
    public boolean challenge(YukkuriEntity peer) {
        if(peer==entity||peer.level()!=entity.level()||cooldown>0||peer.groupInteraction().cooldown>0||active()||peer.groupInteraction().active()
                ||!available(entity)||!available(peer)||!entity.family().adult()||!peer.family().adult()
                ||entity.life().faltering()||peer.life().faltering()
                ||entity.distanceToSqr(peer)>16||!entity.hasLineOfSight(peer)||!challengeSpace(entity)||!challengeSpace(peer))return false;
        var relations=YukkuriWorldData.get(level());
        if(relations.affinity(entity.getUUID(),peer.getUUID())<BondLevels.AFFINITY_ACQUAINTED||relations.affinity(peer.getUUID(),entity.getUUID())<BondLevels.AFFINITY_ACQUAINTED)return false;
        return reserve(peer,Kind.SUMO);
    }
    /**
     * Two bodies reach one prize (a bed at its lip, a food portion, a free play station). Both are stood down from their errand
     * (the life and play goals yield while a bout is on), and the bout plays the contest where they stand; a pair owned by one hand
     * does not tussle.
     */
    public boolean contest(YukkuriEntity rival, Contest.Prize prize) {
        if(rival.level()!=entity.level()||cooldown>0||rival.groupInteraction().cooldown>0||active()||rival.groupInteraction().active()
                ||!contestant(entity)||!contestant(rival))return false;
        boolean mayPush=Contest.mayPush(entity,rival);
        if(!reserve(rival,Kind.CONTEST))return false;
        bout.contest=Contest.begin(entity,rival,prize,mayPush);
        return true;
    }
    /** The pushed body is shoved away from the pusher; the one sumo and contest contact. */
    static void shove(YukkuriEntity pushed, YukkuriEntity pusher) {
        shove(pushed, pusher, .13);
    }
    static void shove(YukkuriEntity pushed, YukkuriEntity pusher, double strength) {
        Vec3 direction=pushed.position().subtract(pusher.position()).multiply(1,0,1).normalize();
        if(direction.lengthSqr()<.01)direction=new Vec3(1,0,0);
        pushed.setDeltaMovement(direction.scale(strength));pushed.hurtMarked=true;
    }
    private boolean reserve(YukkuriEntity peer, Kind kind) { return reserve(peer, kind, false); }
    private boolean reserve(YukkuriEntity peer, Kind kind, boolean ownOpening) {
        if(kind!=Kind.SUMO&&kind!=Kind.CONTEST&&(!space(entity)||!space(peer)))return false;
        entity.social().stop();peer.social().stop();
        bout=new Bout(entity,peer,kind);peer.groupInteraction().bout=bout;bout.created=level().getGameTime();
        entity.life().personality().protectRelation(peer.getUUID());peer.life().personality().protectRelation(entity.getUUID());
        cooldown=peer.groupInteraction().cooldown=2400;
        // A contest and a keepsake snatch own their opening beats; other bouts open with this shared reaction.
        if(kind!=Kind.CONTEST&&!ownOpening) {
            if (kind == Kind.SUMO) {
                entity.showReaction("smug", "sumo_start", FaceTier.Tier.SHORT);
                peer.showReaction("smug", "sumo_start", FaceTier.Tier.SHORT);
            } else {
                entity.showReaction("surprise", "mine", FaceTier.Tier.MEDIUM);
                peer.mind().felt(EmotionEvent.FRIGHTENED);
                peer.showReaction("surprise", "afraid", FaceTier.Tier.MEDIUM);
            }
        }
        GameplayLog.event(entity, "social", "bout reserved kind={} peer={}", kind, peer.getUUID());
        return true;
    }
    @Override public boolean canUse() {
        if(active())return valid();
        if(scanWait>0||cooldown>0||!available(entity)||entity.inert())return false;
        scanWait=160;
        for(var peer:level().getEntitiesOfClass(YukkuriEntity.class,entity.getBoundingBox().inflate(4),e->e!=entity&&!e.inert())) {
            if(entity.getRandom().nextFloat()<.25f*entity.species().traits().sumo()&&challenge(peer))return true;
            var personality=entity.life().personality();
            // A keepsake the peer keeps is a reason of its own: the bout's strike takes the doll instead of hitting for it.
            boolean keepsake = entity.keepsake().keepsDoll(peer) && entity.keepsake().canSnatch();
            // A provocative species (Marisa) picks fights sooner, and everybody picks on the easily bullied (Chen).
            if(personality.temperament().is(Gate.HARSH)&&available(peer)&&!peer.groupInteraction().active()
                    &&!SettlementSociety.sparesHatlessBlame(entity,peer)&&!SettlementSociety.sparesHatlessBlame(peer,entity)
                    &&peer.groupInteraction().cooldown==0&&entity.family().adult()&&entity.getBbWidth()>peer.getBbWidth()*1.3
                    &&(YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_HOSTILE||
                            !entity.treasure().full()&&peer.treasure().count()>0&&YukkuriWorldData.get(level()).affinity(entity.getUUID(),
                            peer.getUUID())<=BondLevels.AFFINITY_NEUTRAL||
                            keepsake&&YukkuriWorldData.get(level()).affinity(entity.getUUID(), peer.getUUID())<=BondLevels.AFFINITY_NEUTRAL)
                    &&entity.hasLineOfSight(peer)&&space(entity)&&space(peer))
            {
                cooldown = 600;
                // A doll the peer keeps is a stronger motive than a mood: the snatch does not wait on the escalation roll.
                if (!keepsake && entity.getRandom().nextInt(100) >= personality.escalationScore()) return false;
                Choice choice = Choice.ALLOW;
                if (peer.pet().isPet() && peer.pet().ownedBy(entity.pet().owner())) {
                    choice = entity.pet().conscience().tempted(HouseRule.NO_BULLYING, personality.escalationScore() / 100.0, 1);
                    if (choice == Choice.HOLD) {
                        PetScenes.act(entity, YukkuriAction.PUFF, "cheek_puff", "", 30, peer);
                        PetScenes.later(entity, 30, () -> entity.pet().conscience().showHold(HouseRule.NO_BULLYING, personality.escalationScore() / 100.0, peer));
                    }
                    if (choice == Choice.HOLD || choice == Choice.WAIT) return false;
                }
                if (!reserve(peer,Kind.BULLY,keepsake)) return false;
                bout.rule = choice;
                if (keepsake) entity.keepsake().claim(peer);
                return true;
            }
            // A hatless stranger is despised and bullied through headwear.Contempt and the sanction, not here.
        }
        return false;
    }
    private boolean live(YukkuriEntity e) { return e.level()==entity.level()&&level().getEntity(e.getUUID())==e&&(bout.kind==Kind.CONTEST?stillContesting(e):available(e)); }
    /** A running contest outlasts the wounds it deals: only death, dying, panic, sleep, commands, operations, events, defense or fire and water end it early. */
    private static boolean stillContesting(YukkuriEntity e) {
        return e.isAlive()&&!e.isRemoved()&&!e.body().dying()&&!e.isPanicking()&&!e.life().isSleeping()
                &&!e.isOnFire()&&!e.isInWaterOrBubble()&&!e.isCommandPlaying()&&!e.operations().active()
                &&!WildEvents.reserved(e)&&!e.groupInteraction().defense().active();
    }
    private boolean valid() {
        if(active()&&level().getGameTime()-bout.created>=STALE)return false;
        if(!active()||bout.first.isPassenger()||bout.second.isPassenger()||!live(bout.first)||!live(bout.second)||bout.first.distanceToSqr(bout.second)>36||bout.ticks>=240)return false;
        if (bout.kind == Kind.BULLY && (SettlementSociety.sparesHatlessBlame(bout.first, bout.second)
                || SettlementSociety.sparesHatlessBlame(bout.second, bout.first))) return false;
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
        if(current.kind==Kind.CONTEST) {
            // Stand face-to-face for a stare or a puff; a sumo winner and both fighters step into contact (wide enough that body separation does not restart walking).
            double contestContact = (entity.getBbWidth() + peer.getBbWidth()) * .5 + .30;
            if (current.contest.approaching(entity) && entity.distanceToSqr(peer) > contestContact * contestContact) {
                var stand = SurfaceApproach.beside(entity, peer, contestContact - .10, .25, contestContact);
                if (stand == null || SurfaceApproach.walkResult(entity, stand, peer, .8) == SurfaceApproach.Result.BLOCKED) {
                    SurfaceApproach.stopWalking(entity);
                }
            } else {
                SurfaceApproach.stopWalking(entity);
            }
            if(entity==current.first){current.ticks++;if(current.contest.tick(current.ticks))stop();}
            return;
        }
        double contact=(entity.getBbWidth()+peer.getBbWidth())*.5+.15;
        if(entity.distanceToSqr(peer)>contact*contact) {
            if(entity==current.first){
                var stand=SurfaceApproach.beside(entity,peer,contact-.05,.25,contact);
                if (stand == null || SurfaceApproach.walkResult(entity, stand, peer, .8) == SurfaceApproach.Result.BLOCKED) {
                    stop();
                    return;
                }
            }else SurfaceApproach.stopWalking(entity);
        } else SurfaceApproach.stopWalking(entity);
        if(entity!=current.first)return;
        current.ticks++;
        if(entity.distanceToSqr(peer)>contact*contact)return;
        current.contactTicks++;
        if(current.contactTicks<20)return;
        if(current.kind==Kind.BULLY) {
            entity.life().personality().decided("bully_peer");
            stop();
            // A keepsake the victim keeps is what this bout was fought over: the doll changes holders and nobody is hit.
            boolean snatched = entity.keepsake().beginSnatch(peer);
            boolean hit = false, stole = false;
            if (!snatched) {
                float healthBefore = peer.getHealth();
                hit = peer.hurt(level().damageSources().mobAttack(entity),1);
                if (hit) peer.life().personality().bulliedBy(entity.life().personality());
                GameplayLog.event(entity, "social", "bully strike peer={} accepted={} health={} -> {}", peer.getUUID(), hit, healthBefore, peer.getHealth());
                stole = entity.treasure().snatchFrom(peer);
                if (hit || stole) YukkuriSocial.quarrelled(entity, peer);
                if(stole) {
                    entity.species().narrate("steal");
                    if (!hit) peer.respondToBullying(entity);
                }
            }
            if (current.rule.breached(snatched || hit || stole) && peer.pet().isPet() && peer.pet().ownedBy(entity.pet().owner()))
                entity.pet().conscience().breach(HouseRule.NO_BULLYING);
            if (snatched) return;
            if(!peer.life().hasAccessory()&&!Recognition.recognizes(entity,peer)&&peer.isAlive()){
                peer.mind().addSlavePressure(6,"bullied_hatless");peer.mind().feltSlavePressure(EmotionEvent.SOCIAL_HURT);
                if (!peer.isPanicking() && !peer.groupInteraction().defense().active()
                        && peer.showReaction("teary", "", FaceTier.Tier.MEDIUM)) {
                    peer.getLookControl().setLookAt(entity, 30, 30);
                    peer.speak(ReactionSpeech.contempt(SettlementSociety.sameGroup(entity, peer)));
                }
            }
            return;
        }
        if(current.kind==Kind.PROTECT) {
            stop();peer.groupInteraction().defense().provoke(entity);entity.showReaction("neutral","known_peer",FaceTier.Tier.MEDIUM);
            GameplayLog.event(entity, "social", "protection puff directed attacker={} protected={}",
                    peer.getUUID(), current.protectedPeer == null ? null : current.protectedPeer.getUUID());
            return;
        }
        if(current.contactTicks%20!=0)return;
        if(!entity.life().spendEnergy(2)||!peer.life().spendEnergy(2)){stop();return;}
        boolean firstStronger=Contest.might(entity)>=Contest.might(peer);
        var pusher=firstStronger?entity:peer;
        var pushed=firstStronger?peer:entity;
        shove(pushed,pusher);
        if(current.contactTicks<80) {
            var pp = pushed.life().personality();
            String pushedFace = ContestOdds.sumoPushedFace(pp.boldness(), pp.selfControl(), current.lastPushedFace, pushed.getRandom().nextDouble());
            current.lastPushedFace = pushedFace;
            pushed.forceReaction(pushedFace, "", FaceTier.Tier.SHORT);
            pushed.beat("sumo_pushed_back", pushedFace, "");
            GameplayLog.event(pushed, "social", "sumo pushed_back face={} contact={} pusher={}",
                    pushedFace, current.contactTicks, pusher.getUUID());
            return;
        }
        var winner=pusher;var loser=pushed;
        var lp = loser.life().personality();
        String endFace = ContestOdds.sumoPushedFace(lp.boldness(), lp.selfControl(), current.lastPushedFace, loser.getRandom().nextDouble());
        current.lastPushedFace = endFace;
        stop();
        winner.beat("sumo_win", "open_smile", "sumo_win");
        winner.showWithDeferredLine(YukkuriAction.SUMO_WIN, YukkuriAction.SUMO_WIN.durationTicks(), "smug", "sumo_win", FaceTier.Tier.MEDIUM, 2,
                res -> GameplayLog.event(winner, "social", "sumo speech role=winner event=sumo_win spoken={}", res.spoken()));
        loser.beat("sumo_push_out", endFace, "sumo_lose");
        loser.showWithDeferredLine(YukkuriAction.SUMO_PUSH_OUT, YukkuriAction.SUMO_PUSH_OUT.durationTicks(), endFace, "sumo_lose", FaceTier.Tier.MEDIUM, 2,
                res -> GameplayLog.event(loser, "social", "sumo speech role=loser event=sumo_lose spoken={}", res.spoken()));
        var relations=YukkuriWorldData.get(level());relations.relate(entity.getUUID(),peer.getUUID(),1);relations.relate(peer.getUUID(),entity.getUUID(),1);
        entity.life().personality().experienced(Trait.FRIENDLINESS, 1, "sumo");
        peer.life().personality().experienced(Trait.FRIENDLINESS, 1, "sumo");
        // A bout that really hurt the loser ends in a quarrel; a clean one is the friendly game it started as.
        if(YukkuriConfig.SUMO_DAMAGE.get()&&loser.hurtInBout(level().damageSources().mobAttack(winner),1))YukkuriSocial.quarrelled(winner,loser);
        GameplayLog.event(entity, "social", "sumo completed winner={} loser={} winnerHealth={} loserHealth={} contact={} loserFace={}",
                winner.getUUID(), loser.getUUID(), winner.getHealth(), loser.getHealth(), current.contactTicks, endFace);
    }
    /** Only present, awake witnesses with actual sight react to an attack. No later knowledge is fabricated. */
    public static void witnessedAttack(YukkuriEntity victim,YukkuriEntity attacker) {
        if(!(victim.level() instanceof ServerLevel level))return;
        var relations=YukkuriWorldData.get(level);
        for(var witness:level.getEntitiesOfClass(YukkuriEntity.class,victim.getBoundingBox().inflate(6),e->e!=victim&&e!=attacker)) {
            if(!available(witness)||witness.inert()||!witness.hasLineOfSight(victim)||!witness.hasLineOfSight(attacker))continue;
            // A recognised parent answers through the family puff response, which already drew for this same hit.
            if(ProtectivePuff.parentOf(victim, witness))continue;
            witness.mind().felt(EmotionEvent.FRIGHTENED); witness.showReaction("surprise","afraid",FaceTier.Tier.MEDIUM);
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
            witness.showReaction("surprise","mine",FaceTier.Tier.MEDIUM);relations.relate(witness.getUUID(),attacker.getUUID(),-5);break;
        }
    }
    @Override public void stop() {
        var current=bout;bout=null;defending=false;
        if(current!=null&&!current.ended){current.ended=true;
            GameplayLog.event(current.first, "social", "bout ended kind={} peer={} elapsed={} contact={} protected={}",
                    current.kind, current.second.getUUID(), current.ticks, current.contactTicks,
                    current.protectedPeer == null ? null : current.protectedPeer.getUUID());
            for(var e:List.of(current.first,current.second)) {
            e.life().personality().releaseRelation((e == current.first ? current.second : current.first).getUUID());
            e.groupInteraction().bout=null;e.groupInteraction().defending=false;e.getNavigation().stop();
        }}
    }
}
