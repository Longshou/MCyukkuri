package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.badge.BadgeRequirements;
import io.github.longshou.mcyukkuri.badge.BadgeRequirements.Need;
import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.mind.Naming;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.ExamRecord;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One examiner and candidate. The verdict commits the fee and any badge change together. */
public final class ExamSession {
    public static final int CERTIFY = 8, REISSUE_FEE = 1;
    public static final List<String> REASONS = List.of("", "busy", "far", "night", "not_owned", "not_ready", "labor",
            "sick", "uneasy", "scared", "cooldown", "no_accessory", "max_grade", "deficient", "too_late", "no_hall",
            "no_fee", "occupied", "disqualified", "need_badge");
    private static final String LOCK = "exam";
    private static final int COOLDOWN = 1200;
    private static final int DUTY_TICKS = 600, APPROACH_TICKS = 180, LOOK_TICKS = 80, RESULT_TICKS = 90;
    private static final Map<UUID, ExamSession> ACTIVE = new HashMap<>();
    private enum Phase { APPROACH, LOOK, VERDICT, RESULT }

    private final ServerLevel level;
    private final HumanNpc examiner;
    private final YukkuriEntity candidate;
    private final UUID ownerId;
    private final FakePlayer fixtureOwner;
    private final BadgeGrade before, target;
    private Phase phase = Phase.APPROACH;
    private int phaseTicks;
    private UUID sayCard, nameCard;
    private boolean passed;

    private ExamSession(HumanNpc examiner, Player owner, YukkuriEntity candidate) {
        this.level = (ServerLevel) examiner.level();
        this.examiner = examiner;
        this.candidate = candidate;
        ownerId = owner.getUUID();
        fixtureOwner = owner instanceof FakePlayer fake ? fake : null;
        before = candidate.pet().grade();
        target = before.higher();
    }

    public static void reset() { ACTIVE.clear(); }
    public static void stopping(ServerStoppingEvent event) {
        for (var session : List.copyOf(ACTIVE.values()))
            if (session.level.getServer() == event.getServer()) session.interrupt("unload");
    }
    public static void forget(Set<UUID> ids) {
        for (var session : List.copyOf(ACTIVE.values()))
            if (ids.contains(session.examiner.getUUID()) || ids.contains(session.candidate.getUUID()) || ids.contains(session.ownerId))
                session.interrupt("unload");
    }
    private static ExamSession candidateSession(YukkuriEntity candidate) {
        for (var session : ACTIVE.values()) if (session.candidate == candidate) return session;
        return null;
    }
    public static boolean running(YukkuriEntity candidate) { return candidateSession(candidate) != null; }
    public static boolean active(HumanNpc examiner) { return ACTIVE.containsKey(examiner.getUUID()); }
    public static boolean ownerPresent(YukkuriEntity candidate, UUID id) {
        var session = candidateSession(candidate);
        return session != null && session.ownerId.equals(id) && session.owner() != null;
    }

    public static int fee(BadgeGrade target) { return target == BadgeGrade.BRONZE ? 0 : 4; }
    public static int emeralds(Player p) {
        return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }
    public static void debit(Player p, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(Items.EMERALD)) {
            int used = Math.min(amount, stack.getCount());
            stack.shrink(used);
            amount -= used;
            if (amount == 0) return;
        }
    }
    public static void giveTo(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }

    public static Need firstMissing(BadgeGrade target, YukkuriEntity y) {
        var s = y.skills();
        var t = y.life().personality().temperament();
        return BadgeRequirements.firstMissing(target.id(), new BadgeRequirements.Candidate(
                s.grade(Ability.WAIT), s.grade(Ability.TOILET), s.grade(Ability.FOOD_GUARD), s.grade(Ability.MANNERS),
                s.grade(Ability.GREETING), s.cap(), t.is(Gate.WARM), t.is(Gate.DEVOTED), y.pet().talent()));
    }

    private static boolean eligibleOwner(YukkuriEntity y, Player p) {
        var pet = y.pet();
        return pet.owner() != null ? pet.isPet() && pet.owned(p)
                : pet.grade() == BadgeGrade.NONE
                && y.status().identity() != Identity.WILD_SETTLEMENT && WildEvents.canAdopt(y, p);
    }

    public static String refusal(HumanNpc examiner, Player p, YukkuriEntity y, boolean reissue) {
        if (examiner.job() != HumanNpc.Job.EXAMINER || active(examiner)) return "busy";
        if (y == null || !y.isAlive() || y.level() != p.level() || y.distanceToSqr(p) > 64) return "far";
        if (!examiner.level().isDay()) return "night";
        if (running(y)) return "busy";
        var pet = y.pet();
        if (!eligibleOwner(y, p) || reissue && !pet.owned(p)) return "not_owned";
        if (y.isPassenger() || pet.busy() || y.isNoAi()) return "not_ready";
        var grade = pet.grade();
        if (reissue) return grade == BadgeGrade.NONE ? "need_badge" : emeralds(p) < REISSUE_FEE ? "no_fee" : null;
        if (pet.effectiveGrade() != grade) return "not_owned";
        if (y.profile().aquatic() || y.profile().dangerToYukkuri() || y.profile().houseBody()) return "not_ready";
        if (y.family().laboring() || y.family().birthDuty()) return "labor";
        if (y.body().mold() > 0 || y.body().toxin() > 0 || y.body().condition().infection() > 0 || y.mind().nonYukkuri()
                || y.getHealth() <= y.getMaxHealth() * .3f) return "sick";
        if (y.mind().sweetness() >= 60 || y.mind().unease() >= 60) return "uneasy";
        if (y.life().personality().fear(examiner.getUUID()) >= 50) return "scared";
        if (y.isPanicking() || y.hurtTime > 0 || y.isCommandPlaying() || y.operations().active() || y.life().isSleeping()
                || y.life().mealTicks() > 0 || y.isInWaterOrBubble() || y.isOnFire() || y.life().hasUrgentNeed()) return "not_ready";
        if (pet.cooldown() > 0) return "cooldown";
        if (!y.life().hasAccessory()) return "no_accessory";
        if (grade == BadgeGrade.PLATINUM) return "max_grade";
        if (grade != BadgeGrade.NONE && y.subspecies().congenital() != Congenital.NORMAL) return "deficient";
        if (y.isVehicle() || y.family().carry().carrying() || y.family().awaitingLanding()) return "occupied";
        if (!y.duty().mayTake(Duty.Kind.EXAM)) return "busy";
        long day = Math.floorMod(examiner.level().getDayTime(), 24000);
        if (day < 12000 && 12000 - day < DUTY_TICKS) return "too_late";
        if (!ExamHall.of(examiner)) return "no_hall";
        if (emeralds(p) < fee(grade.higher())) return "no_fee";
        return null;
    }

    public static String start(HumanNpc examiner, Player owner, YukkuriEntity candidate) {
        var why = refusal(examiner, owner, candidate, false);
        if (why != null) return why;
        var pet = candidate.pet();
        pet.lock(LOCK, owner.getUUID());
        candidate.duty().take(Duty.Kind.EXAM, DUTY_TICKS, COOLDOWN, "certify");
        var session = new ExamSession(examiner, owner, candidate);
        ACTIVE.put(examiner.getUUID(), session);
        candidate.setPersistenceRequired();
        GameplayLog.event(candidate, "exam", "action=start examiner={} owner={} target={} need={} fee={} talent={}",
                examiner.getUUID(), owner.getUUID(), session.target, firstMissing(session.target, candidate), fee(session.target), pet.talent());
        owner.displayClientMessage(Component.translatable("exam.mcyukkuri.started", candidate.getDisplayName()), true);
        return null;
    }

    public static void tick(ServerTickEvent.Post event) {
        for (var session : List.copyOf(ACTIVE.values()))
            if (session.level.getServer() == event.getServer()) session.tick();
    }

    private void tick() {
        phaseTicks++;
        var owner = owner();
        var reason = interruption(owner);
        if (reason != null) { interrupt(reason); return; }
        examiner.getLookControl().setLookAt(candidate, 30, 30);
        switch (phase) {
            case APPROACH -> {
                if (phaseTicks == 1 && !inContact()) examiner.getNavigation().moveTo(candidate, .9);
                if (phaseTicks % 20 == 1) {
                    waitingBeat(owner);
                }
                if (phaseTicks >= 20 && inContact()) {
                    examiner.getNavigation().stop();
                    next(Phase.LOOK);
                    var need = firstMissing(target, candidate);
                    GameplayLog.event(candidate, "exam", "action=look target={} need={} fee={} talent={}",
                            target, need, fee(target), candidate.pet().talent());
                } else if (phaseTicks >= APPROACH_TICKS) interrupt("site");
            }
            case LOOK -> {
                if (phaseTicks == 20) introduce();
                if (phaseTicks == 40) examiner.swing(InteractionHand.MAIN_HAND);
                if (phaseTicks == LOOK_TICKS) {
                    if (candidate.pet().talent() >= BadgeRequirements.TALENT) say(owner, "exam.mcyukkuri.say.talent");
                    next(Phase.VERDICT);
                }
            }
            case VERDICT -> {
                // Let the examiner's talent remark read before it is replaced with the result.
                if (candidate.pet().talent() < BadgeRequirements.TALENT || phaseTicks >= 40) verdict(owner);
            }
            case RESULT -> {
                if (passed && phaseTicks == 50) pinnedBeat();
                if (phaseTicks >= RESULT_TICKS) release(Duty.End.DONE, "result");
            }
        }
    }

    private boolean inContact() {
        return examiner.distanceToSqr(candidate) <= 6.25 && examiner.hasLineOfSight(candidate) && candidate.hasLineOfSight(examiner);
    }

    private String interruption(Player owner) {
        if (candidate.isDeadOrDying()) return "lost";
        if (candidate.level() != level || candidate.isPassenger() || candidate.operations().active()) return "held";
        if (level.getEntity(candidate.getUUID()) != candidate) return "unload";
        if (!examiner.isAlive() || level.getEntity(examiner.getUUID()) != examiner
                || examiner.distanceToSqr(Vec3.atCenterOf(examiner.home())) > 576) return "examiner";
        if (!level.isDay()) return "night";
        if (!candidate.life().hasAccessory()) return "accessory";
        if (candidate.isInWaterOrBubble()) return "water";
        if (candidate.isOnFire()) return "fire";
        if (candidate.hurtTime > 0) return "hurt";
        if (candidate.isPanicking()) return "panic";
        if (!candidate.duty().holds(Duty.Kind.EXAM) || !LOCK.equals(candidate.pet().busyWith())) return "duty";
        if (owner == null || !owner.isAlive()) return "owner_gone";
        if (owner.level() != level || owner.distanceToSqr(candidate) > 144) return "owner_far";
        if (phase != Phase.RESULT && (!eligibleOwner(candidate, owner) || candidate.pet().grade() != before
                || candidate.pet().effectiveGrade() != before)) return "not_owned";
        if (phase != Phase.APPROACH && !inContact()) return "site";
        return null;
    }

    private void waitingBeat(Player owner) {
        var t = candidate.life().personality().temperament();
        if (t.is(Gate.TIMID))
            PetScenes.act(candidate, YukkuriAction.TREMBLE, "afraid", phaseTicks == 1 ? "exam_nervous" : "", 20, examiner);
        else if (t.is(Gate.BOLD)) PetScenes.act(candidate, YukkuriAction.PROUD, "smug", "", 20, examiner);
        else if (t.is(Gate.ATTACHED) && candidate.hasLineOfSight(owner)) {
            PetScenes.act(candidate, YukkuriAction.IDLE, "teary", "", 20, owner);
        } else PetScenes.act(candidate, YukkuriAction.LOOK_AROUND, "surprise", "", 20, examiner);
    }

    private void introduce() {
        int fear = candidate.life().personality().fear(examiner.getUUID());
        boolean shy = fear > 20 || candidate.mind().unease() >= 50;
        boolean deficient = candidate.subspecies().congenital() != Congenital.NORMAL;
        String custom = candidate.getCustomName() == null ? "" : candidate.getCustomName().getString();
        String given = Naming.ownName(candidate, custom) ? custom : candidate.mind().givenName();
        var name = given.isBlank() ? candidate.getType().getDescription() : Component.literal(given);
        candidate.showReaction(shy ? YukkuriAction.SHY : YukkuriAction.BOW, shy ? "shy" : "closed_smile", "");
        int sequence = candidate.speechSequence();
        boolean spoken = candidate.speakCue("introduced") && candidate.speechSequence() != sequence;
        var line = NativeDialogue.byKey(candidate.getDialogueKey());
        boolean voice = spoken && (deficient ? candidate.getDialogueKey().equals("dialogue.mcyukkuri.deficient.babble")
                : line != null && line.events().contains("introduced"));
        int hold = voice ? (int) Math.max(60, candidate.speechEnd() - level.getGameTime()) : 60;
        var reply = deficient ? Component.translatable("exam.mcyukkuri.reply.sound") : Component.translatable("exam.mcyukkuri.reply.name", name);
        nameCard = Staging.card(level, candidate.position().add(0, candidate.getBbHeight() + .6, 0), reply, hold, 1.3f);
        GameplayLog.event(candidate, "exam", "action=introduce voice={} name_card={}", voice, nameCard);
    }

    private void verdict(Player owner) {
        int fee = fee(target);
        if (emeralds(owner) < fee) { interrupt("no_fee"); return; }
        var pet = candidate.pet();
        var need = firstMissing(target, candidate);
        passed = need == null;
        var oldGrade = pet.visibleGrade();
        var old = passed && oldGrade != BadgeGrade.NONE ? pet.token() : ItemStack.EMPTY;
        var oldItem = old.getItem();
        int oldBefore = old.isEmpty() ? 0 : owner.getInventory().countItem(oldItem);
        UUID oldSerial = old.isEmpty() ? null : pet.serial();
        debit(owner, fee);
        if (passed) {
            if (pet.owner() == null) Custody.assign(candidate, owner, Custody.Role.COMPANION);
            pet.issue(target, CERTIFY, examiner.getUUID());
            if (!old.isEmpty()) giveTo(owner, old);
            var families = FamilyData.get(level);
            families.register(candidate);
            families.recordBadge(candidate.getUUID(), target.id(), level.getGameTime());
            switch (target) {
                case BRONZE -> SurvivalProgress.award(owner, "pet_registered");
                case SILVER -> SurvivalProgress.award(owner, "badge_silver");
                case GOLD -> SurvivalProgress.award(owner, "badge_gold");
                case PLATINUM -> SurvivalProgress.award(owner, "badge_platinum");
                case NONE -> throw new IllegalStateException("Certification must award a badge");
            }
            PetPrivileges.note(candidate);
            examiner.swing(InteractionHand.MAIN_HAND);
            Staging.sparkle(level, candidate.position());
            Staging.sound(level, candidate.position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f);
            PetScenes.act(candidate, YukkuriAction.JOY_JUMP, "sparkle", "exam_pass", 50, examiner);
            say(owner, "exam.mcyukkuri.say.pass", gradeName(target));
        } else {
            var t = candidate.life().personality().temperament();
            if (t.is(Gate.TIMID) || t.is(Gate.ATTACHED))
                PetScenes.act(candidate, YukkuriAction.DROOP, "cry", "exam_fail", RESULT_TICKS, examiner);
            else if (t.is(Gate.BOLD) || t.is(Gate.HARSH))
                PetScenes.act(candidate, YukkuriAction.PUFF, "cheek_puff", "request_sulk", RESULT_TICKS, examiner);
            else PetScenes.act(candidate, YukkuriAction.DROOP, "teary_frown", "exam_sorry", RESULT_TICKS, examiner);
            say(owner, "exam.mcyukkuri.say.missing", Component.translatable("exam.mcyukkuri.need." + need.key()));
        }
        int outcome = passed ? 0 : 2;
        pet.record(new ExamRecord(CERTIFY, level.getGameTime(), pet.grade(), outcome, examiner.getUUID(), passed ? "" : need.key()));
        pet.setCooldown(COOLDOWN);
        GameplayLog.event(candidate, "exam", "action=result examiner={} owner={} outcome={} grade={}->{} emeralds_used={} target={} need={} fee={} talent={} old_token={} old_token_inventory_delta={}",
                examiner.getUUID(), ownerId, outcome, before, pet.grade(), fee, target, need, fee, pet.talent(), oldSerial,
                oldSerial == null ? 0 : owner.getInventory().countItem(oldItem) - oldBefore);
        next(Phase.RESULT);
    }

    private void pinnedBeat() {
        if (candidate.life().personality().temperament().is(Gate.BOLD))
            PetScenes.act(candidate, YukkuriAction.PROUD, "smug", "badge_proud", 40, examiner);
        else candidate.speakCue("badge_pinned");
    }

    private void next(Phase next) { phase = next; phaseTicks = 0; }

    private Player owner() {
        return fixtureOwner != null ? fixtureOwner : level.getServer().getPlayerList().getPlayer(ownerId);
    }

    private void interrupt(String reason) {
        if (phase == Phase.RESULT) { release(Duty.End.DONE, "result"); return; }
        var pet = candidate.pet();
        pet.record(new ExamRecord(CERTIFY, level.getGameTime(), pet.grade(), 4, examiner.getUUID(), ""));
        pet.setCooldown(COOLDOWN);
        var owner = owner();
        if (owner != null) {
            String prefix = REASONS.contains(reason) ? "exam.mcyukkuri.refuse." : "exam.mcyukkuri.interrupt.";
            owner.displayClientMessage(Component.translatable("exam.mcyukkuri.interrupted", Component.translatable(prefix + reason)), false);
        }
        GameplayLog.event(candidate, "exam", "action=interrupt reason={} phase={} target={} need={} fee={} talent={} emeralds_used=0 owner_emeralds={}",
                reason, phase, target, firstMissing(target, candidate), fee(target), pet.talent(), owner == null ? null : emeralds(owner));
        release(Duty.End.FAILED, reason);
    }

    private void release(Duty.End end, String reason) {
        Staging.removeCard(level, sayCard);
        Staging.removeCard(level, nameCard);
        candidate.pet().unlock(LOCK);
        candidate.duty().release(Duty.Kind.EXAM, end, reason);
        examiner.getNavigation().stop();
        ACTIVE.remove(examiner.getUUID());
        GameplayLog.event(candidate, "exam", "action=end target={} reason={}", target, reason);
    }

    private void say(Player owner, String key, Object... args) {
        var line = Component.translatable(key, args);
        Staging.removeCard(level, sayCard);
        sayCard = Staging.card(level, examiner.position().add(0, examiner.getBbHeight() + .45, 0), line, 60, 1f);
        owner.displayClientMessage(line, true);
        GameplayLog.event(candidate, "exam", "action=line examiner={} key={}", examiner.getUUID(), key);
    }

    public static Component gradeName(BadgeGrade grade) {
        return Component.translatable("badge.mcyukkuri.profile.grade." + grade.id());
    }
}
