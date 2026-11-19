package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class Discovery {
    private final Observation observation = new Observation();
    private List<BadgeConduct.Case> open = List.of();
    private List<HumanNpc> staff = List.of();
    private BadgeConduct.Case exchange;
    private boolean confession;
    private long spokeAt;
    private int sequence;
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

    void spoke(YukkuriEntity parent, BadgeConduct.Case entry, LivingEntity owner, boolean confessed) {
        exchange = entry;
        confession = confessed;
        spokeAt = parent.level().getGameTime();
        sequence = parent.speechSequence();
        calls.clear();
        heard.clear();
        if (HumanSight.sees(owner, parent)) heard.add(owner.getId());
        for (var npc : staff) if (npc.badgeStaff() && HumanSight.sees(npc, parent)) heard.add(npc.getId());
    }

    /** Children hear the actual denial even when the owner missed it. No exchange survives an unload. */
    public long denialHeard(YukkuriEntity mother, YukkuriEntity child) {
        return exchange != null && !confession && exchange.verdict() == null && mother.level().getGameTime() - spokeAt < 200
                && mother.distanceToSqr(child) <= 64 && child.hasLineOfSight(mother)
                && Observation.identifyingCall(exchange.brood, mother.getUUID(), child.family().birthId(), child.family().mother(),
                        child.childhood().shouldStayWithParents(), true) ? spokeAt : -1;
    }

    public void addressed(YukkuriEntity mother, YukkuriEntity child) {
        GameplayLog.event(child, "rules", "arc spoke=addr_mother brood={} mother={}", exchange.brood, mother.getUUID());
        var owner = mother.pet().presentOwner();
        if (owner != null) heardCall(mother, child, owner);
        for (var npc : staff) if (npc.badgeStaff()) heardCall(mother, child, npc);
    }

    private void heardCall(YukkuriEntity mother, YukkuriEntity child, LivingEntity judge) {
        if (!heard.contains(judge.getId()) || !HumanSight.sees(judge, mother) || !visibleYoung(child) || !HumanSight.sees(judge, child)) return;
        calls.add(new Observation.Sign(exchange.brood, judge.getId(), child.getId(), false));
        if (!(judge instanceof HumanNpc npc && ExamSession.examines(npc, mother))) witness(mother, judge, false, null);
    }

    public boolean leavesNest(YukkuriEntity mother, FamilyData.Brood brood) {
        if (open.isEmpty() || !mother.pet().conscience().responseDue() || !mother.pet().isPet()) return false;
        for (var entry : open) {
            if (!entry.brood.equals(brood.id) || entry.verdict() != null) continue;
            var style = entry.parents.getFirst().style();
            var owner = mother.pet().presentOwner();
            return (style == ArcStyle.HIDE || style == ArcStyle.CONFESS && mother.pet().obedience() >= 80)
                    && owner != null && HumanSight.sees(owner, mother) && mother.pet().conscience().mayApproach(entry.brood, owner, null);
        }
        return false;
    }

    public void tick(YukkuriEntity parent) {
        var level = (ServerLevel) parent.level();
        if (exchange != null && (exchange.verdict() != null || level.getGameTime() - spokeAt >= 200)) exchange = null;
        if (parent.tickCount % 20 == 0) {
            var cases = PetPrivileges.get(level).conduct().cases(parent.getUUID());
            open = cases.isEmpty() ? List.of() : cases.stream().filter(c -> c.verdict() == null).toList();
            staff = open.isEmpty() ? List.of() : level.getEntitiesOfClass(HumanNpc.class,
                    parent.getBoundingBox().inflate(16), HumanNpc::badgeStaff);
            observation.expire(level.getGameTime());
        }
        if (open.isEmpty()) return;
        var owner = parent.pet().owner() == null ? null : parent.pet().presentOwner();
        if (owner != null && !(owner instanceof HumanNpc npc && npc.badgeStaff())) witness(parent, owner, false, null);
        for (var npc : staff) if (npc.badgeStaff() && !ExamSession.examines(npc, parent)) witness(parent, npc, false, null);
    }

    /** Called on each actual LOOK tick, with the booking owner available for immediate advancement delivery. */
    public void inspect(YukkuriEntity parent, HumanNpc examiner, Player owner) { witness(parent, examiner, true, owner); }

    private void witness(YukkuriEntity parent, LivingEntity judge, boolean exam, Player attendee) {
        if (open.isEmpty() || !HumanSight.sees(judge, parent)) return;
        var level = (ServerLevel) parent.level();
        var families = FamilyData.get(level);
        var attached = parent.family().brood();
        for (var entry : open) {
            if (entry.verdict() != null) continue;
            boolean witnessed = entry == exchange && heard.contains(judge.getId());
            String admission = Observation.words(witnessed, parent.speechSequence() == sequence && level.getGameTime() < parent.speechEnd(),
                    confession, false);
            if (admission != null && record(parent, judge, entry, parent, admission, exam, attendee)) continue;
            if (attached != null && Observation.pregnancy(entry.brood, attached.id, !attached.complete() && !attached.cancelled,
                    attached.kind == FamilyData.BirthKind.STEM && attached.detachedHolder == null && parent.broodProgress() > 0
                    || attached.kind == FamilyData.BirthKind.LIVE && parent.livePregnancyProgress() > .001f)) {
                record(parent, judge, entry, parent, "pregnancy", exam, attendee);
            } else {
                var brood = families.brood(entry.brood);
                for (var member : brood.children) {
                    if (!member.born || !(level.getEntity(member.id) instanceof YukkuriEntity child) || parent.distanceToSqr(child) > 9) continue;
                    var person = families.person(member.id);
                    if (Observation.young(entry.brood, parent.getUUID(), person.brood, person.mother, person.father, child.family().adult(),
                            visibleYoung(child) && HumanSight.sees(judge, child))) {
                        boolean denial = witnessed && !confession;
                        var sign = new Observation.Sign(entry.brood, judge.getId(), child.getId(), false);
                        String evidence = denial ? Observation.words(true, true, false, calls.contains(sign)) : "babies";
                        if (record(parent, judge, entry, child, evidence, exam, attendee)) break;
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
                           String evidence, boolean exam, Player attendee) {
        var level = (ServerLevel) parent.level();
        var sign = new Observation.Sign(entry.brood, judge.getId(), subject.getId(), exam);
        if (!observation.seen(sign, level.getGameTime()) || evidence == null) return false;
        String cause = exam ? "exam" : evidence;
        if (!entry.discover(new BadgeConduct.Verdict(cause, level.getDayTime() / 24000 + 1, judge.getUUID()))) return false;
        PetPrivileges.get(level).setDirty();
        confrontation(level, entry, judge);
        for (var p : entry.parents) {
            if (p.owner() == null) continue;
            GameplayLog.event(parent, "badge", "disqualified cause={} judge={} brood={} parent={} owner={} evidence={} subject={} observed=40",
                    cause, judge.getUUID(), entry.brood, p.id(), p.owner(), evidence, subject.getUUID());
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
            if (parent.style() == ArcStyle.DEFIANT && PetScenes.ready(pet, "brood_defiance", 1200))
                PetScenes.say(pet, YukkuriAction.PUFF, "cheek_puff", "sukkiri_defiant", judge);
            if (level.getEntity(entry.parents.get(1-i).id()) instanceof YukkuriEntity partner && partner.isAlive()
                    && partner.pet().isPet() && partner.pet().ownedBy(pet.pet().owner()) && pet.distanceToSqr(partner) <= 64
                    && pet.hasLineOfSight(partner) && pet.pet().obedience() < partner.pet().obedience()
                    && pet.life().personality().temperament().is(Gate.HARSH)
                    && PetScenes.say(pet, YukkuriAction.TURN, "pursed", "partner_blame", partner)) {
                partner.showReaction("surprise", "", 30);
                GameplayLog.event(pet, "rules", "arc spoke=partner_blame brood={} partner={}", entry.brood, partner.getUUID());
            }
        }
    }
}
