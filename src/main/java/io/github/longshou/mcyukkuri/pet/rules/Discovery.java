package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.pet.rules.Observation.Kind;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class Discovery {
    private static final int RESPONSE_WINDOW = 200, CONFESSION_WINDOW = 800;
    private final Observation observation = new Observation();
    private List<BadgeConduct.Case> open = List.of();
    private List<HumanNpc> staff = List.of();
    private BadgeConduct.Case exchange;
    private ArcStyle style;
    private long spokeAt = -1;
    private int sequence;
    private UUID introduced;
    private final Set<Integer> heard = new HashSet<>();
    private final Set<Observation.Sign> calls = new HashSet<>();

    List<BadgeConduct.Case> open() { return open; }

    public void fed(YukkuriEntity parent, YukkuriEntity child) {
        for (var entry : open) {
            if (entry.verdict() != null || !entry.brood.equals(child.family().birthId())) continue;
            var actor = entry.parents.get(parent.getUUID().equals(child.family().mother()) ? 0 : 1);
            if (actor.style() != ArcStyle.HIDE || !parent.pet().isPet()) continue;
            var owner = parent.pet().presentOwner();
            if (owner != null && parent.distanceToSqr(owner) <= 256 && !GlassSight.clearTo(owner, parent.getEyePosition())
                    && !GlassSight.clearTo(owner, child.getEyePosition()))
                GameplayLog.event(parent, "rules", "private feed brood={} child={} owner={}", entry.brood, child.getUUID(), owner.getUUID());
        }
    }

    void beginExchange(YukkuriEntity parent, BadgeConduct.Case entry, int approachTicks) {
        exchange = entry;
        spokeAt = -1;
        entry.exchange(parent.getUUID(), parent.level().getGameTime() + approachTicks);
    }

    void spoke(YukkuriEntity parent, BadgeConduct.Case entry, ArcStyle response, LivingEntity owner, UUID child) {
        exchange = entry;
        style = response;
        spokeAt = parent.level().getGameTime();
        entry.exchange(parent.getUUID(), spokeAt + window(response));
        sequence = parent.speechSequence();
        introduced = child;
        calls.clear();
        heard.clear();
        if (HumanSight.sees(owner, parent)) heard.add(owner.getId());
        for (var npc : nearbyStaff(parent)) if (HumanSight.sees(npc, parent)) heard.add(npc.getId());
    }

    /** A confession stays open through its whole penance (bow, lying low, the owner's answer); the other answers last ten seconds. */
    private static int window(ArcStyle response) { return response == ArcStyle.CONFESS ? CONFESSION_WINDOW : RESPONSE_WINDOW; }

    /** The goal ended: an exchange that never reached its words, or a confession whose penance did not get to its answer, is dropped. */
    void endApproach(YukkuriEntity parent) {
        if (exchange != null && (spokeAt < 0 || style == ArcStyle.CONFESS)) abandon(parent);
    }

    /**
     * The owner answered the confessing parent's plea (forgave, punished, or left it unanswered): the admission stands now, so the
     * disqualification and the confession award follow the answer, not the bow.
     */
    void admitted(YukkuriEntity parent, LivingEntity owner) {
        var entry = exchange;
        if (entry == null || spokeAt < 0 || style != ArcStyle.CONFESS || entry.verdict() != null) return;
        var level = (ServerLevel) parent.level();
        exchange = null;
        var proof = new BadgeConduct.Evidence(new BadgeConduct.Verdict("confession", level.getDayTime() / 24000 + 1, owner.getUUID()),
                parent.getUUID(), parent.getUUID(), "confession");
        publish(parent, entry, entry.spoken(proof, level.getGameTime()), null);
    }

    public void abandon(YukkuriEntity parent) {
        if (exchange == null) return;
        var entry = exchange;
        exchange = null;
        publish(parent, entry, entry.abandonExchange(parent.getUUID(), parent.level().getGameTime()), null);
    }

    /** Children hear the actual denial even when the owner missed it. No exchange survives an unload. */
    public long denialHeard(YukkuriEntity mother, YukkuriEntity child) {
        return exchange != null && style == ArcStyle.LIE && spokeAt >= 0 && exchange.verdict() == null
                && mother.level().getGameTime() - spokeAt < RESPONSE_WINDOW
                && mother.distanceToSqr(child) <= 64 && child.hasLineOfSight(mother)
                && Observation.identifyingCall(exchange.brood, mother.getUUID(), child.family().birthId(), child.family().mother(),
                        child.childhood().shouldStayWithParents(), true) ? spokeAt : -1;
    }

    public void addressed(YukkuriEntity mother, YukkuriEntity child) {
        GameplayLog.event(child, "rules", "arc spoke=addr_mother brood={} mother={}", exchange.brood, mother.getUUID());
        var owner = mother.pet().presentOwner();
        if (owner != null) heardCall(mother, child, owner);
        for (var npc : nearbyStaff(mother)) if (npc != owner) heardCall(mother, child, npc);
    }

    private void heardCall(YukkuriEntity mother, YukkuriEntity child, LivingEntity judge) {
        if (!heard.contains(judge.getId()) || !HumanSight.sees(judge, mother) || !visibleYoung(child) || !HumanSight.sees(judge, child)) return;
        calls.add(new Observation.Sign(exchange.brood, judge.getId(), child.getId(), false, Kind.YOUNG));
        if (!(judge instanceof HumanNpc npc && ExamSession.examines(npc, mother))) witness(mother, judge, false, null);
    }

    public boolean leavesNest(YukkuriEntity mother, FamilyData.Brood brood) {
        if (open.isEmpty() || !mother.pet().conscience().responseDue() || !mother.pet().isPet()) return false;
        for (var entry : open) {
            if (!entry.brood.equals(brood.id) || entry.verdict() != null) continue;
            var style = entry.parents.getFirst().style();
            var owner = mother.pet().presentOwner();
            return (style == ArcStyle.HIDE || style == ArcStyle.CONFESS && mother.pet().obedience() >= 80)
                    && owner != null && HumanSight.sees(owner, mother) && mother.pet().conscience().mayApproach(entry, owner, null);
        }
        return false;
    }

    public void tick(YukkuriEntity parent) {
        var level = (ServerLevel) parent.level();
        if (exchange != null && (exchange.verdict() != null || parent.pet().conscience().endangered()
                || spokeAt >= 0 && (level.getGameTime() - spokeAt >= window(style) || !hasListener(parent, level)))) abandon(parent);
        if (parent.tickCount % 20 == 0) {
            var cases = PetPrivileges.get(level).conduct().cases(parent.getUUID());
            open = cases.isEmpty() ? List.of() : cases.stream().filter(c -> c.verdict() == null).toList();
            parent.pet().conscience().retainEncounters(open);
            staff = open.isEmpty() ? List.of() : nearbyStaff(parent);
            observation.expire(level.getGameTime());
        }
        if (open.isEmpty()) return;
        for (var entry : open) publish(parent, entry, entry.expireExchanges(level.getGameTime()), null);
        var owner = parent.pet().owner() == null ? null : parent.pet().presentOwner();
        if (owner != null && !(owner instanceof HumanNpc npc && npc.badgeStaff())) witness(parent, owner, false, null);
        for (var npc : staff) if (npc.badgeStaff() && !ExamSession.examines(npc, parent)) witness(parent, npc, false, null);
    }

    private boolean hasListener(YukkuriEntity parent, ServerLevel level) {
        for (int id : heard) {
            if (level.getEntity(id) instanceof LivingEntity judge && judge.isAlive() && !judge.isSpectator() && !judge.isSleeping()
                    && parent.distanceToSqr(judge) <= 256 && (parent.pet().ownedBy(judge.getUUID())
                    || judge instanceof HumanNpc npc && npc.badgeStaff())) return true;
        }
        return false;
    }

    private static List<HumanNpc> nearbyStaff(YukkuriEntity parent) {
        return parent.level().getEntitiesOfClass(HumanNpc.class, parent.getBoundingBox().inflate(16), HumanNpc::badgeStaff);
    }

    /** Called on each actual LOOK tick, with the booking owner available for immediate advancement delivery. */
    public void inspect(YukkuriEntity parent, HumanNpc examiner, Player owner) {
        if (examiner.badgeStaff()) witness(parent, examiner, true, owner);
    }

    private void witness(YukkuriEntity parent, LivingEntity judge, boolean exam, Player attendee) {
        if (open.isEmpty() || !HumanSight.sees(judge, parent)) return;
        var level = (ServerLevel) parent.level();
        var families = FamilyData.get(level);
        var attached = parent.family().brood();
        for (var entry : open) {
            if (entry.verdict() != null) continue;
            boolean witnessed = entry == exchange && spokeAt >= 0 && heard.contains(judge.getId());
            if (attached != null && Observation.pregnancy(entry.brood, attached.id, !attached.complete() && !attached.cancelled,
                    attached.kind == FamilyData.BirthKind.STEM && attached.detachedHolder == null && parent.broodProgress() > 0
                    || attached.kind == FamilyData.BirthKind.LIVE && parent.livePregnancyProgress() > .001f)) {
                record(parent, judge, entry, parent, "pregnancy", Kind.PREGNANCY, exam, attendee, false);
            } else {
                var brood = families.brood(entry.brood);
                for (var member : brood.children) {
                    if (!member.born || !(level.getEntity(member.id) instanceof YukkuriEntity child) || parent.distanceToSqr(child) > 9) continue;
                    var person = families.person(member.id);
                    if (Observation.young(entry.brood, parent.getUUID(), person.brood, person.mother, person.father, child.family().adult(),
                            visibleYoung(child) && HumanSight.sees(judge, child))) {
                        var sign = new Observation.Sign(entry.brood, judge.getId(), child.getId(), false, Kind.YOUNG);
                        String spoken = !witnessed ? null : switch (style) {
                            case LIE -> Observation.words(true, true, calls.contains(sign));
                            case NAIVE -> child.getUUID().equals(introduced) ? "naive_introduction" : null;
                            default -> null;
                        };
                        if (record(parent, judge, entry, child, spoken == null ? "babies" : spoken, Kind.YOUNG,
                                exam, attendee, spoken != null)) break;
                    }
                }
            }
        }
    }
    private static boolean visibleYoung(YukkuriEntity child) {
        return !(child.getVehicle() instanceof YukkuriEntity carrier && ParentCarry.multi(carrier)
                && ParentCarry.hold(carrier, child) == ParentCarry.Hold.MOUTH);
    }
    private boolean record(YukkuriEntity parent, LivingEntity judge, BadgeConduct.Case entry, YukkuriEntity subject,
                           String evidence, Kind kind, boolean exam, Player attendee, boolean spoken) {
        var level = (ServerLevel) parent.level();
        var sign = new Observation.Sign(entry.brood, judge.getId(), subject.getId(), exam, kind);
        long now = level.getGameTime();
        if (!observation.seen(sign, now)) return false;
        String cause = exam && !spoken ? "exam" : evidence;
        var proof = new BadgeConduct.Evidence(new BadgeConduct.Verdict(cause, level.getDayTime() / 24000 + 1, judge.getUUID()),
                parent.getUUID(), subject.getUUID(), evidence);
        return publish(parent, entry, spoken ? entry.spoken(proof, now) : entry.sight(proof, now), attendee);
    }

    private static boolean publish(YukkuriEntity parent, BadgeConduct.Case entry, BadgeConduct.Evidence proof, Player attendee) {
        if (proof == null) return false;
        var level = (ServerLevel) parent.level();
        PetPrivileges.get(level).setDirty();
        var verdict = proof.verdict();
        var judge = level.getEntity(verdict.judge());
        if (judge instanceof LivingEntity human) confrontation(level, entry, human);
        var awarded = new HashSet<UUID>();
        for (var p : entry.parents) {
            if (p.owner() == null) continue;
            GameplayLog.event(parent, "badge", "disqualified cause={} judge={} brood={} parent={} owner={} evidence={} subject={} observed=40 witness_parent={}",
                    verdict.cause(), verdict.judge(), entry.brood, p.id(), p.owner(), proof.clue(), proof.subject(), proof.parent());
            if (!awarded.add(p.owner())) continue;
            Player recipient = judge instanceof Player player && p.owner().equals(player.getUUID()) ? player
                    : attendee != null && p.owner().equals(attendee.getUUID()) ? attendee : level.getServer().getPlayerList().getPlayer(p.owner());
            if (recipient != null) PetPrivileges.award(recipient, entry);
        }
        return true;
    }

    private static void confrontation(ServerLevel level, BadgeConduct.Case entry, LivingEntity judge) {
        for (int i = 0; i < 2; i++) {
            var parent = entry.parents.get(i);
            if (!(level.getEntity(parent.id()) instanceof YukkuriEntity pet) || !pet.pet().isPet()
                    || !HumanSight.sees(judge, pet) || pet.isPanicking() || pet.hurtTime > 0) continue;
            if (parent.style() == ArcStyle.DEFIANT && PetScenes.ready(pet, "brood_defiance", 1200)
                    && PetScenes.say(pet, YukkuriAction.PUFF, "cheek_puff", "sukkiri_defiant", judge)) continue;
            if (level.getEntity(entry.parents.get(1-i).id()) instanceof YukkuriEntity partner && partner.isAlive()
                    && partner.pet().isPet() && partner.pet().ownedBy(pet.pet().owner()) && pet.distanceToSqr(partner) <= 64
                    && pet.hasLineOfSight(partner) && pet.pet().obedience() < partner.pet().obedience()
                    && pet.life().personality().temperament().is(Gate.HARSH)
                    && PetScenes.say(pet, YukkuriAction.TURN, "pursed", "partner_blame", partner)) {
                partner.showReaction("surprise", "", FaceTier.Tier.SHORT);
                GameplayLog.event(pet, "rules", "arc spoke=partner_blame brood={} partner={}", entry.brood, partner.getUUID());
            }
        }
    }
}
