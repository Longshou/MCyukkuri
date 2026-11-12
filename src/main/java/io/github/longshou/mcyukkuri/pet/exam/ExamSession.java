package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.pet.AbandonLog;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.ExamRecord;
import io.github.longshou.mcyukkuri.pet.PetConversation;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.util.FakePlayer;

/** One examiner and candidate, with question receipts written before any result transaction. */
public final class ExamSession implements ExamContext {
    public static final int SIMPLE = 3, JUDGMENT = 4, PROMOTION = 5, RENEWAL = 6, COOLDOWN = 1200, REISSUE = 7;
    public static final String LOCK = "exam";
    public static final List<String> REASONS = List.of("", "busy", "night", "no_hall", "far", "not_owned", "wild_simple_only", "not_ready",
            "cooldown", "no_accessory", "already_badged", "need_badge", "max_grade", "need_silver", "not_needed", "no_section", "no_fee",
            "no_management", "too_late", "stage", "deficient", "sick", "uneasy", "labor", "occupied", "need_sweet", "need_reading",
            "need_count", "need_care", "need_walk", "no_participant", "same_conditions", "need_gold", "platinum_potential",
            "platinum_skills", "platinum_traits", "share_nutrition");
    private static final Set<String> PLAYER_CAUSES = Set.of("owner_far", "owner_gone", "hurt", "panic", "fire", "water", "held", "accessory", "lost");
    private static final Map<UUID, ExamSession> ACTIVE = new HashMap<>();
    private enum Phase { APPROACH, ITEM, PAYMENT, RESULT }

    private final ServerLevel level;
    private final HumanNpc examiner;
    private final YukkuriEntity candidate;
    private final UUID ownerId;
    private final FakePlayer fixtureOwner;
    private final BadgeGrade before, target;
    private final int service, fee;
    private final long attempt;
    private final List<ExamPlan.Question> questions;
    private final List<String> notes = new ArrayList<>();
    private final List<UUID> cards = new ArrayList<>();
    private Phase phase = Phase.APPROACH;
    private ExamItem item;
    private UUID sayCard;
    private BlockPos examinerGoal;
    private int index, successes, phaseTicks, itemTicks, farTicks, offlineTicks;
    private String firstFailure;
    private boolean done;

    private ExamSession(ServerLevel level, HumanNpc examiner, Player owner, YukkuriEntity candidate, int service,
                        BadgeGrade target, List<ExamPlan.Question> questions, ExamRecord resume, int fee) {
        this.level = level;
        this.examiner = examiner;
        this.candidate = candidate;
        this.ownerId = owner.getUUID();
        this.fixtureOwner = owner instanceof FakePlayer fake ? fake : null;
        this.before = candidate.pet().grade();
        this.target = target;
        this.service = service;
        this.fee = fee;
        this.questions = questions;
        var history = candidate.pet().history();
        this.attempt = history.isEmpty() ? level.getGameTime() : Math.max(level.getGameTime(), history.getLast().attempt() + 1);
        if (resume != null) {
            index = resume.next();
            successes = resume.passed();
            firstFailure = resume.evidence().isEmpty() ? null : resume.evidence();
        }
    }

    public static void reset() { ACTIVE.clear(); }
    public static void stopping(ServerStoppingEvent event) {
        for (var session : List.copyOf(ACTIVE.values()))
            if (session.level.getServer() == event.getServer()) session.interrupt("unload");
    }
    public static void forget(Set<UUID> ids) {
        for (var session : List.copyOf(ACTIVE.values()))
            if (ids.contains(session.examiner.getUUID()) || ids.contains(session.candidate.getUUID())
                    || ids.contains(session.ownerId) || ids.contains(session.sayCard) || session.cards.stream().anyMatch(ids::contains))
                session.interrupt("unload");
    }
    private static ExamSession candidateSession(YukkuriEntity y) {
        for (var session : ACTIVE.values())
            if (!session.done && session.phase != Phase.RESULT && session.candidate.getUUID().equals(y.getUUID())) return session;
        return null;
    }
    public static boolean running(YukkuriEntity candidate) { return candidateSession(candidate) != null; }
    public static boolean ownerPresent(YukkuriEntity candidate, UUID id) {
        var session = candidateSession(candidate);
        return session != null && session.ownerId.equals(id) && session.owner() != null;
    }
    public static boolean active(HumanNpc examiner) { return ACTIVE.containsKey(examiner.getUUID()); }
    public static ExamSession of(HumanNpc examiner) { return ACTIVE.get(examiner.getUUID()); }
    public static int fee(int type) {
        return switch (type) { case JUDGMENT -> 9; case PROMOTION -> 4; case RENEWAL -> 2; case REISSUE -> 1; default -> 0; };
    }
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
    public static BadgeGrade targetOf(int type, BadgeGrade current) {
        return switch (type) { case JUDGMENT -> BadgeGrade.GOLD; case PROMOTION -> current.higher(); case RENEWAL -> current; default -> BadgeGrade.BRONZE; };
    }
    private static BadgeGrade target(int type, YukkuriEntity y) {
        if (type == JUDGMENT && y.subspecies().congenital() != Congenital.NORMAL) return BadgeGrade.BRONZE;
        if (type == JUDGMENT && (!y.family().adult() || y.pet().grade() == BadgeGrade.NONE)) return BadgeGrade.SILVER;
        return targetOf(type, y.pet().grade());
    }
    public static boolean unmanaged(Player p) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        return AbandonLog.get(level).recent(p.getUUID(), level.getGameTime())
                || !level.getEntitiesOfClass(YukkuriEntity.class, p.getBoundingBox().inflate(32),
                y -> y.isAlive() && y.pet().owned(p) && y.pet().isPet() && y.pet().neglect() >= 60).isEmpty();
    }

    private static ExamRecord previous(YukkuriEntity y, BadgeGrade target) {
        for (var record : y.pet().history().reversed()) if (record.target() == target) return record;
        return null;
    }
    private static ExamRecord pending(ExamRecord record) {
        return record != null && record.outcome() == 4 && !record.questions().isEmpty() ? record : null;
    }
    private static String evidence(ExamPlan.Question question, HumanNpc examiner, Player p, YukkuriEntity y) {
        var skill = question.skill;
        var personality = y.life().personality();
        String example = switch (question) {
            case READ, COUNT, EXPLAIN, CLARIFY -> {
                var sample = ExamPlan.example(question, p, y);
                yield sample == null ? "" : sample.source() + "/" + sample.example().getString();
            }
            case GREET -> {
                var guest = ExamPlan.visitor(y, p, examiner);
                yield guest == null ? "" : guest.getUUID().toString();
            }
            case HELP, SHARE -> {
                var peer = ExamPlan.needy(y, question == ExamPlan.Question.SHARE);
                yield peer == null ? "" : peer.getUUID().toString();
            }
            case BED -> String.valueOf(y.life().homeStand());
            default -> "";
        };
        int aptitude = switch (question) {
            case NAME, GREET -> personality.attachment();
            case READ, COUNT, EXPLAIN, CLARIFY -> Math.min(personality.intelligence(), personality.selfControl());
            case WAIT, BOWL, TOILET, BED, WALK -> personality.selfControl();
            case HELP, SHARE -> personality.friendliness();
        };
        return question.name() + "|" + (skill == null ? 0 : y.pet().lessons().xp(skill))
                + "|" + y.pet().stress() / 5 + "|" + personality.fear(examiner.getUUID()) / 5
                + "|" + aptitude + "|" + example;
    }

    private static boolean improved(String[] old, String[] current) {
        try {
            return Integer.parseInt(current[1]) > Integer.parseInt(old[1])
                    || Integer.parseInt(current[2]) < Integer.parseInt(old[2])
                    || Integer.parseInt(current[3]) < Integer.parseInt(old[3])
                    || Integer.parseInt(current[4]) > Integer.parseInt(old[4])
                    || !current[5].isEmpty() && !old[5].equals(current[5]);
        } catch (NumberFormatException legacy) {
            return true;
        }
    }

    public static String refusal(HumanNpc examiner, Player p, YukkuriEntity y, int type) {
        if (examiner.job() != HumanNpc.Job.EXAMINER) return "busy";
        if (y == null || !y.isAlive() || y.level() != p.level() || y.distanceToSqr(p) > 64) return "far";
        if (!examiner.level().isDay()) return "night";
        if (active(examiner) || running(y)) return "busy";
        var pet = y.pet();
        boolean mine = pet.isPet() && pet.owned(p);
        if (!mine) {
            if (pet.owner() != null || y.status().identity() == Identity.WILD_SETTLEMENT || !WildEvents.canAdopt(y, p))
                return "not_owned";
            if (type != SIMPLE) return "wild_simple_only";
        }
        if (y.isPassenger() || pet.busy() || y.isNoAi()) return "not_ready";
        var grade = pet.grade();
        if (type == REISSUE) return grade == BadgeGrade.NONE ? "need_badge" : emeralds(p) < fee(type) ? "no_fee" : null;
        if (y.profile().aquatic() || y.profile().dangerToYukkuri() || y.profile().houseBody()) return "not_ready";
        if (y.family().laboring() || y.family().birthDuty()) return "labor";
        if (y.body().mold() > 0 || y.body().toxin() > 0 || y.body().condition().infection() > 0 || y.mind().nonYukkuri()
                || y.getHealth() <= y.getMaxHealth() * .3f) return "sick";
        if (y.mind().sweetness() >= 60 || y.mind().unease() >= 60) return "uneasy";
        if (y.isPanicking() || y.hurtTime > 0 || y.operations().active() || y.life().isSleeping()
                || y.life().mealTicks() > 0 || y.isInWaterOrBubble() || y.isOnFire()) return "not_ready";
        if (pet.cooldown() > 0) return "cooldown";
        if (!y.life().hasAccessory()) return "no_accessory";
        if (type == SIMPLE && grade != BadgeGrade.NONE) return "already_badged";
        if (type == SIMPLE && unmanaged(p)) return "no_management";
        if (type == PROMOTION && grade == BadgeGrade.NONE) return "need_badge";
        if (type == PROMOTION && grade.higher() == grade) return "max_grade";
        if (type == RENEWAL && grade.id() < BadgeGrade.SILVER.id()) return "need_silver";
        if (type == RENEWAL && pet.expiresAt() < 0) return "not_needed";
        var target = target(type, y);
        if (y.family().stage() == FamilyLife.Stage.BABY || target.id() >= BadgeGrade.GOLD.id() && !y.family().adult()) return "stage";
        if (target.id() >= BadgeGrade.SILVER.id() && y.subspecies().congenital() != Congenital.NORMAL) return "deficient";
        if (target == BadgeGrade.PLATINUM) {
            if (type == PROMOTION && pet.effectiveGrade() != BadgeGrade.GOLD) return "need_gold";
            if (!PetConversation.hasPotential(y)) return "platinum_potential";
            for (var skill : List.of(PetSkill.READING, PetSkill.ARITHMETIC, PetSkill.WAIT, PetSkill.GREETING))
                if (pet.lessons().level(skill) < 4) return "platinum_skills";
            if (PetConversation.explanation(y) == 0 || PetConversation.considerate(y) == 0) return "platinum_traits";
        }
        if (y.isVehicle() || y.family().carry().carrying() || y.family().awaitingLanding()) return "occupied";
        if (y.life().hasUrgentNeed()) return "not_ready";
        long day = Math.floorMod(examiner.level().getDayTime(), 24000);
        if (day < 12000 && 12000 - day < (target == BadgeGrade.BRONZE ? 600 : 6000)) return "too_late";
        if (target.id() >= BadgeGrade.SILVER.id() && !ExamHall.of(examiner)) return "no_hall";
        if (emeralds(p) < fee(type)) return "no_fee";
        var prior = previous(y, target);
        var resume = pending(prior);
        var plan = resume == null ? ExamPlan.questions(type, target, p, y) : ExamPlan.decode(resume.questions());
        for (int i = resume == null ? 0 : resume.next(); i < plan.size(); i++) {
            String reason = ExamPlan.refusal(plan.get(i), examiner, p, y);
            if (reason != null) return reason;
        }
        if (prior != null && prior.outcome() >= 2 && prior.outcome() != 4 && !prior.evidence().isEmpty()) {
            var old = prior.evidence().split("\\|", 6);
            if (old.length == 6) {
                var failed = ExamPlan.Question.valueOf(old[0]);
                var current = evidence(failed, examiner, p, y).split("\\|", 6);
                if (!improved(old, current)) return "same_conditions";
            }
        }
        return null;
    }

    public static String start(HumanNpc examiner, Player owner, YukkuriEntity candidate, int type, boolean charge) {
        if (!(examiner.level() instanceof ServerLevel level) || type < SIMPLE || type > RENEWAL) return "busy";
        var why = refusal(examiner, owner, candidate, type);
        if (why != null) return why;
        var target = target(type, candidate);
        var resume = pending(previous(candidate, target));
        var plan = resume == null ? ExamPlan.questions(type, target, owner, candidate) : ExamPlan.decode(resume.questions());
        if (!candidate.pet().lock(LOCK, owner.getUUID())) return "busy";
        var session = new ExamSession(level, examiner, owner, candidate, type, target, plan, resume, charge ? fee(type) : 0);
        ACTIVE.put(examiner.getUUID(), session);
        candidate.setPersistenceRequired();
        GameplayLog.event(candidate, "exam", "action=start examiner={} owner={} type={} target={} questions={} next={}",
                examiner.getUUID(), owner.getUUID(), type, target, ExamPlan.encode(plan), session.index);
        owner.displayClientMessage(Component.translatable("exam.mcyukkuri.started", candidate.getDisplayName()), true);
        return null;
    }

    public static void tick(ServerTickEvent.Post event) {
        for (var session : List.copyOf(ACTIVE.values())) {
            if (session.level.getServer() != event.getServer()) continue;
            try { session.tick(); }
            catch (RuntimeException e) {
                MCYukkuri.LOGGER.warn("Exam session failed", e);
                session.interrupt("error");
            }
        }
    }
    public static void performed(YukkuriEntity y, PetSkill skill, boolean success) {
        var session = candidateSession(y);
        if (session != null && session.phase == Phase.ITEM && session.item instanceof ExamPracticeItem practice
                && session.questions.get(session.index).skill == skill) practice.performed(session, success);
    }
    public static void greeted(YukkuriEntity y, Entity guest, boolean success) {
        var session = candidateSession(y);
        if (session != null && session.phase == Phase.ITEM && session.item instanceof ExamPracticeItem practice && practice.guest(guest))
            performed(y, PetSkill.GREETING, success);
    }
    public static void fed(YukkuriEntity receiver, UUID feeder) {
        for (var session : ACTIVE.values())
            if (session.phase == Phase.ITEM && session.item instanceof ExamPracticeItem practice)
                practice.fed(session, receiver, feeder);
    }
    public static YukkuriEntity sharingTarget(YukkuriEntity donor) {
        var session = candidateSession(donor);
        return session != null && session.phase == Phase.ITEM && session.item instanceof ExamPracticeItem practice
                ? practice.sharingTarget() : null;
    }

    private void tick() {
        phaseTicks++;
        if (phase == Phase.RESULT) {
            if (phaseTicks >= 90 || !candidate.isAlive() || !examiner.isAlive()) release();
            return;
        }
        var why = interruption();
        if (why != null) { interrupt(why); return; }
        examinerLookAt(candidate);
        if (phase == Phase.ITEM) {
            if (++itemTicks >= 1200) { interrupt("timeout"); return; }
            if (!inContact() && phaseTicks % 20 == 1) examinerWalkTo(candidate.blockPosition());
            else if (inContact()) examiner.getNavigation().stop();
            if (item.tick(this) && !done) finishQuestion();
            return;
        }
        if (!inContact()) {
            if (phaseTicks % 20 == 1) examinerWalkTo(candidate.blockPosition());
            if (phaseTicks >= 180 && phase != Phase.PAYMENT) interrupt("site");
            else if (phase == Phase.PAYMENT && phaseTicks >= 2400) interrupt("site");
            return;
        }
        examiner.getNavigation().stop();
        if (phase == Phase.PAYMENT || index >= questions.size()) {
            presentResult();
            return;
        }
        if (!LOCK.equals(candidate.pet().busyWith()) && !candidate.pet().lock(LOCK, ownerId)) {
            interrupt("site");
            return;
        }
        phase = Phase.ITEM;
        phaseTicks = itemTicks = 0;
        item = questions.get(index).item();
        checkpoint(4, before, index, firstFailure);
        GameplayLog.event(candidate, "exam", "action=task_start task={} index={}", item.id(), index);
        item.start(this);
    }

    @Override public boolean inContact() {
        return examiner.distanceToSqr(candidate) <= 6.25 && examiner.hasLineOfSight(candidate) && candidate.hasLineOfSight(examiner);
    }

    private String interruption() {
        if (candidate.isDeadOrDying()) return "lost";
        if (candidate.level() != level || candidate.isPassenger() || candidate.operations().active()) return "held";
        if (candidate.isRemoved() || level.getEntity(candidate.getUUID()) != candidate)
            return candidate.getRemovalReason() == Entity.RemovalReason.DISCARDED ? "lost" : "unload";
        if (!examiner.isAlive() || examiner.isRemoved() || examiner.level() != level
                || examiner.distanceToSqr(Vec3.atCenterOf(examiner.home())) > 576) return "examiner";
        if (!level.isDay()) return "night";
        if (!candidate.life().hasAccessory()) return "accessory";
        if (candidate.isInWaterOrBubble()) return "water";
        if (candidate.isOnFire()) return "fire";
        if (candidate.hurtTime > 0) return "hurt";
        if (candidate.isPanicking()) return "panic";
        var owner = owner();
        if (owner == null) {
            if (++offlineTicks > 200) return "owner_gone";
        } else {
            offlineTicks = 0;
            if (owner.level() != level || owner.distanceToSqr(candidate) > 144) {
                if (farTicks++ == 0) owner.displayClientMessage(Component.translatable("exam.mcyukkuri.stay_near"), true);
                if (farTicks > 60) return "owner_far";
            } else farTicks = 0;
        }
        return null;
    }

    private void finishQuestion() {
        var question = questions.get(index);
        if (item.passed()) successes |= 1 << index;
        else if (firstFailure == null && owner() != null) firstFailure = evidence(question, examiner, owner(), candidate);
        GameplayLog.event(candidate, "exam", "action=task_end task={} passed={} elapsed={}", item.id(), item.passed(), itemTicks);
        item.abort(this);
        item = null;
        clearCards();
        index++;
        checkpoint(4, before, index, firstFailure);
        phase = Phase.APPROACH;
        phaseTicks = 0;
    }

    private void presentResult() {
        var owner = owner();
        if (owner == null || owner.level() != level || owner.distanceToSqr(candidate) > 144) return;
        if (emeralds(owner) < fee) {
            if (phase != Phase.PAYMENT) {
                phase = Phase.PAYMENT;
                phaseTicks = 0;
                examinerSay("exam.mcyukkuri.say.need_payment", fee);
            } else if (phaseTicks >= 2400) interrupt("no_fee");
            return;
        }
        var pet = candidate.pet();
        boolean unauthorized = (service == PROMOTION || service == RENEWAL)
                && pet.unauthorizedBirths() > candidate.getPersistentData().getInt("ExamUnauthBase");
        BadgeGrade earned = BadgeGrade.NONE;
        if (service == JUDGMENT) {
            for (var grade : BadgeGrade.values())
                if (grade.id() <= target.id() && ExamPlan.meets(grade, questions, successes, true)) earned = grade;
        } else if (!unauthorized && ExamPlan.meets(target, questions, successes, false)) earned = target;
        int outcome;
        BadgeGrade result = before;
        if (unauthorized && service == RENEWAL) {
            outcome = 3;
            result = before.lower();
            note("exam.mcyukkuri.log.renew_demoted");
        } else if (earned.id() > before.id()) {
            outcome = 0;
            result = earned;
        } else if (earned != BadgeGrade.NONE && earned == before) outcome = 1;
        else outcome = 2;

        if (outcome == 0 && pet.owner() == null) {
            if (!WildEvents.canAdopt(candidate, owner)) { interrupt("not_owned"); return; }
            Custody.assign(candidate, ownerId, Custody.OwnerKind.PLAYER, Custody.Role.COMPANION);
        }
        debit(owner, fee);
        if (service == JUDGMENT && earned.id() >= BadgeGrade.SILVER.id() && fee > 0)
            giveTo(owner, new ItemStack(Items.EMERALD, 3));
        if (outcome == 0 || outcome == 3) pet.issue(result, service, examiner.getUUID());
        else if (outcome == 1) pet.renew(examiner.getUUID());
        if (outcome == 0) {
            var families = FamilyData.get(level);
            families.register(candidate);
            families.recordBadge(candidate.getUUID(), result.id(), level.getGameTime());
            if (before == BadgeGrade.NONE) SurvivalProgress.award(owner, "pet_registered");
            if (result.id() >= BadgeGrade.SILVER.id()) SurvivalProgress.award(owner, "badge_silver");
            if (result.id() >= BadgeGrade.GOLD.id()) SurvivalProgress.award(owner, "badge_gold");
        }
        if (outcome <= 1) PetPrivileges.note(candidate);
        if ((service == PROMOTION || service == RENEWAL) && outcome != 2)
            candidate.getPersistentData().putInt("ExamUnauthBase", pet.unauthorizedBirths());
        pet.setCooldown(COOLDOWN);
        checkpoint(outcome, result, index, firstFailure);
        phase = Phase.RESULT;
        phaseTicks = 0;
        var gradeName = Component.translatable("badge.mcyukkuri.profile.grade." + result.id());
        examinerSay(outcome <= 1 ? outcome == 0 ? "exam.mcyukkuri.say.pass" : "exam.mcyukkuri.say.keep" : "exam.mcyukkuri.say.fail");
        card(candidate.position().add(0, candidate.getBbHeight() + .7, 0),
                Component.translatable("exam.mcyukkuri.outcome." + outcome, gradeName), 90, 1.5f);
        owner.sendSystemMessage(Component.translatable("exam.mcyukkuri.outcome." + outcome, gradeName));
        if (outcome == 0) {
            examinerSwing();
            Staging.sparkle(level, candidate.position());
            Staging.sound(level, candidate.position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f);
        }
        GameplayLog.event(candidate, "exam", "action=result examiner={} owner={} type={} outcome={} grade={}->{} emeralds_used={}",
                examiner.getUUID(), ownerId, service, outcome, before, result, fee);
        allowRoutine();
    }

    private void checkpoint(int outcome, BadgeGrade result, int next, String evidence) {
        candidate.pet().record(new ExamRecord(service, level.getGameTime(), result, outcome, examiner.getUUID(),
                target, ExamPlan.encode(questions), next, successes, evidence == null ? "" : evidence, attempt));
        if (!notes.isEmpty() || outcome != 4) candidate.getPersistentData().putString("ExamNotes", String.join("|", notes));
    }

    @Override public void interrupt(String reason) {
        if (done) return;
        if (phase == Phase.RESULT) { release(); return; }
        if (item != null) item.abort(this);
        checkpoint(4, before, index, firstFailure);
        if (PLAYER_CAUSES.contains(reason)) candidate.pet().setCooldown(COOLDOWN);
        var owner = owner();
        if (owner != null) {
            String prefix = REASONS.contains(reason) ? "exam.mcyukkuri.refuse." : "exam.mcyukkuri.interrupt.";
            owner.displayClientMessage(Component.translatable("exam.mcyukkuri.interrupted", Component.translatable(prefix + reason)), false);
        }
        GameplayLog.event(candidate, "exam", "action=interrupt reason={} phase={} next={} emeralds_used=0",
                reason, phase, index);
        release();
        if (reason.equals("examiner") && candidate.isAlive() && !candidate.life().isSleeping()) candidate.playAction("droop");
    }
    private void clearCards() {
        for (var id : cards) Staging.removeCard(level, id);
        cards.clear();
        Staging.removeCard(level, sayCard);
        sayCard = null;
    }
    private void release() {
        done = true;
        clearCards();
        allowRoutine();
        candidate.pet().finishWildExam();
        examiner.getNavigation().stop();
        ACTIVE.remove(examiner.getUUID());
    }

    @Override public ServerLevel level() { return level; }
    @Override public HumanNpc examiner() { return examiner; }
    @Override public YukkuriEntity candidate() { return candidate; }
    @Override public Player owner() { return fixtureOwner != null ? fixtureOwner : level.getServer().getPlayerList().getPlayer(ownerId); }
    @Override public int itemTicks() { return itemTicks; }
    @Override public void examinerSay(String key, Object... args) {
        var text = Component.translatable(key, args);
        Staging.removeCard(level, sayCard);
        sayCard = Staging.card(level, examiner.position().add(0, examiner.getBbHeight() + .45, 0), text, 60, 1.0f);
        var p = owner();
        if (p != null) p.displayClientMessage(text, true);
        GameplayLog.event(candidate, "exam", "action=line examiner={} key={}", examiner.getUUID(), key);
    }
    @Override public UUID card(Vec3 at, Component text, int ticks, float scale) {
        var id = Staging.card(level, at, text, ticks, scale);
        if (id != null) cards.add(id);
        return id;
    }
    @Override public void examinerWalkTo(BlockPos pos) {
        if (!pos.equals(examinerGoal) || examiner.getNavigation().isDone()) {
            examinerGoal = pos;
            examiner.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, .9);
        }
    }
    @Override public void examinerLookAt(Entity entity) { examiner.getLookControl().setLookAt(entity, 30, 30); }
    @Override public void examinerSwing() { examiner.swing(InteractionHand.MAIN_HAND); }
    @Override public void allowRoutine() { candidate.pet().unlock(LOCK); }
    @Override public void note(String key) { if (notes.size() < 12 && !notes.contains(key)) notes.add(key); }
    @Override public void answer(String task, boolean success, String observed) {
        GameplayLog.event(candidate, "exam", "action=answer task={} success={} observed={}", task, success, observed);
    }
}
