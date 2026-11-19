package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class Discovery {
    private final Observation observation = new Observation();
    private List<BadgeConduct.Case> open = List.of();
    private List<HumanNpc> staff = List.of();

    public void tick(YukkuriEntity parent) {
        var level = (ServerLevel) parent.level();
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
                        if (record(parent, judge, entry, child, "babies", exam, attendee)) break;
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
        if (!observation.seen(sign, level.getGameTime())) return false;
        String cause = exam ? "exam" : evidence;
        if (!entry.discover(new BadgeConduct.Verdict(cause, level.getDayTime() / 24000 + 1, judge.getUUID()))) return false;
        PetPrivileges.get(level).setDirty();
        for (var p : entry.parents) {
            if (p.owner() == null) continue;
            GameplayLog.event(parent, "badge", "disqualified cause={} judge={} brood={} parent={} owner={} evidence={} subject={} observed=40",
                    cause, judge.getUUID(), entry.brood, p.id(), p.owner(), evidence, subject.getUUID());
            Player recipient = attendee != null && p.owner().equals(attendee.getUUID()) ? attendee : level.getServer().getPlayerList().getPlayer(p.owner());
            if (recipient != null) PetPrivileges.award(recipient, entry);
        }
        return true;
    }
}
