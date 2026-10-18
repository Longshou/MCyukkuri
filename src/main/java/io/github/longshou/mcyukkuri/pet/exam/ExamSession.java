package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.ExamRecord;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One running exam (docs/PET_SHOP.md section 3): gather, then every question in turn, then the result ceremony. One session per examiner.
 *  Types follow ExamRecord: 3 simple, 4 judgment, 5 promotion, 6 renewal. Everything here runs on the server thread. Transient: a restart mid-exam drops it (locks are not saved). */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class ExamSession implements ExamContext {
    public static final int SIMPLE = 3, JUDGMENT = 4, PROMOTION = 5, RENEWAL = 6, COOLDOWN = 1200, REISSUE = 7;
    public static final String LOCK = "exam", LOCK_EXTRA = "exam_extra";
    /** Index in this list is the refusal code synced to the screen (0 = allowed). Lang: exam.mcyukkuri.refuse.&lt;name&gt;. */
    public static final List<String> REASONS = List.of("", "busy", "night", "no_hall", "far", "not_owned", "wild_simple_only", "not_ready", "cooldown", "no_accessory",
            "already_badged", "need_badge", "max_grade", "need_silver", "not_needed", "no_section", "no_fee", "no_management");
    private static final Map<UUID, ExamSession> ACTIVE = new HashMap<>();
    public static void reset() { ACTIVE.clear(); }
    /** Identity matters: a reloaded candidate cannot resume a session holding its old instance. */
    public static boolean running(YukkuriEntity candidate) {
        return ACTIVE.values().stream().anyMatch(s -> !s.done && s.candidate == candidate);
    }
    private enum Phase { GATHER, MOVE, ITEM, RESULT }

    public static boolean active(HumanNpc examiner) { return ACTIVE.containsKey(examiner.getUUID()); }
    public static ExamSession of(HumanNpc examiner) { return ACTIVE.get(examiner.getUUID()); }
    public static int fee(int type) { return switch (type) { case JUDGMENT -> 9; case PROMOTION -> 4; case RENEWAL -> 2; case REISSUE -> 1; default -> 0; }; }
    public static int emeralds(Player p) { return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    public static void debit(Player p, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(Items.EMERALD)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    private static void give(ServerLevel level, Player p, Vec3 at, ItemStack stack) {
        if (p != null) { if (!p.getInventory().add(stack)) p.drop(stack, false); return; }
        var item = new ItemEntity(level, at.x, at.y + .15, at.z, stack); item.setDefaultPickUpDelay(); level.addFreshEntity(item);
    }
    public static void giveTo(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }

    /** The grade an exam is pitched at: judgment aims at gold, promotion at the next grade, renewal at the current one. */
    public static BadgeGrade targetOf(int type, BadgeGrade current) {
        return switch (type) { case JUDGMENT -> BadgeGrade.GOLD; case PROMOTION -> current.higher(); case RENEWAL -> current; default -> BadgeGrade.BRONZE; };
    }

    /** M02 refusal 3 (docs/PET_SHOP.md 3): the owner cannot be expected to manage a pet. True if the player abandoned one within the last 3 game days, or one of their pets within 32 blocks has neglect >= 60. */
    public static boolean unmanaged(Player p) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        if (io.github.longshou.mcyukkuri.pet.AbandonLog.get(level).recent(p.getUUID(), level.getGameTime())) return true;
        return !level.getEntitiesOfClass(YukkuriEntity.class, p.getBoundingBox().inflate(32), y -> y.isAlive() && y.pet().owned(p) && y.pet().isPet() && y.pet().neglect() >= 60).isEmpty();
    }

    /** Why this subject may not sit this exam now (a REASONS entry), or null if it may. Reissue (REISSUE) is checked here too. */
    public static String refusal(HumanNpc examiner, Player p, YukkuriEntity y, int type) {
        if (y == null || !y.isAlive() || y.level() != p.level() || y.distanceToSqr(p) > 64) return "far";
        if (!examiner.level().isDay()) return "night";
        if (active(examiner)) return "busy";
        var pet = y.pet(); boolean mine = pet.owned(p);
        if (!mine) {
            if (pet.owner() != null || !io.github.longshou.mcyukkuri.event.WildEvents.canAdopt(y, p)) return "not_owned";
            if (type != SIMPLE) return "wild_simple_only";
        }
        if (pet.busy() || y.isPassenger() || y.isNoAi()) return "not_ready";
        if (type == REISSUE) return pet.grade() == BadgeGrade.NONE ? "need_badge" : emeralds(p) < fee(type) ? "no_fee" : null;
        if (y.isPanicking() || y.hurtTime > 0 || y.operations().active()) return "not_ready";
        if (pet.cooldown() > 0) return "cooldown";
        if (!y.life().hasAccessory()) return "no_accessory";
        var grade = pet.grade();
        switch (type) {
            case SIMPLE -> { if (grade != BadgeGrade.NONE) return "already_badged"; if (unmanaged(p)) return "no_management"; }
            case PROMOTION -> { if (grade == BadgeGrade.NONE) return "need_badge"; if (grade == BadgeGrade.GOLD) return "max_grade"; }
            case RENEWAL -> { if (grade.id() < BadgeGrade.SILVER.id()) return "need_silver"; if (pet.expiresAt() < 0) return "not_needed"; }
            default -> {}
        }
        if (type != SIMPLE) {
            var hall = ExamHall.of(examiner); if (hall == null) return "no_hall";
            var need = ExamPlan.sections(type, targetOf(type, grade));
            if (type == JUDGMENT ? !hall.supports(Ability.MANNERS) : !need.stream().allMatch(hall::supports)) return "no_section";
        }
        return emeralds(p) < fee(type) ? "no_fee" : null;
    }

    /** Begin an exam. Returns null when started, else the refusal reason. charge=false skips the fee (tests). Wild animals with trust &gt;= 10 are registered as the player's pet first. */
    public static String start(HumanNpc examiner, Player owner, YukkuriEntity candidate, int type, boolean charge) {
        if (!(examiner.level() instanceof ServerLevel level) || type < SIMPLE || type > RENEWAL) return "busy";
        var why = refusal(examiner, owner, candidate, type);
        if (why != null && !(why.equals("no_fee") && !charge)) return why;
        var hall = ExamHall.of(examiner);
        var before = candidate.pet().grade();
        var plan = ExamPlan.items(type, targetOf(type, before), hall);
        if (plan.isEmpty()) return "no_section";
        if (!candidate.pet().lock(LOCK, owner.getUUID())) return "not_ready";
        if (candidate.pet().owner() == null) candidate.pet().beginWildExam(owner.getUUID());
        int fee = charge ? fee(type) : 0; if (fee > 0) debit(owner, fee);
        var s = new ExamSession(level, examiner, owner, candidate, type, hall, plan, fee);
        ACTIVE.put(examiner.getUUID(), s);
        candidate.pet().direct(s.walker);
        owner.displayClientMessage(Component.translatable("exam.mcyukkuri.started", candidate.getDisplayName()), true);
        return null;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        for (var s : List.copyOf(ACTIVE.values())) {
            if (s.level.getServer() != event.getServer()) { ACTIVE.values().remove(s); continue; }
            try { s.tick(); } catch (RuntimeException e) { MCYukkuri.LOGGER.warn("Exam session failed", e); s.interrupt("error"); }
            if (s.done) ACTIVE.values().remove(s);
        }
    }

    /** Stands the animal still, optionally walking to a goal and looking at something; the PetLife.SessionGoal drives it. */
    private static final class Walker implements PetLife.Director {
        BlockPos goal; Entity look; Vec3 lookPos;
        @Override public void tick(YukkuriEntity a) {
            if (goal != null) Staging.walkTo(a, goal, 1.0); else a.getNavigation().stop();
            if (lookPos != null) a.getLookControl().setLookAt(lookPos.x, lookPos.y, lookPos.z);
            else if (look != null) a.getLookControl().setLookAt(look, 30, 30);
        }
    }

    private final ServerLevel level; private final HumanNpc examiner; private final YukkuriEntity candidate; private final UUID ownerId;
    private final int type, fee; private final ExamHall hall; private final List<ExamItem> plan; private final BadgeGrade before;
    private final Random random = new Random();
    private final Walker walker = new Walker();
    private final int[] sum = new int[4], count = new int[4];
    private final List<UUID> cards = new ArrayList<>(), extraIds = new ArrayList<>();
    private final List<YukkuriEntity> extras = new ArrayList<>(), directed = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final int unauthorizedBase;
    private Phase phase = Phase.GATHER;
    private ExamItem item;
    private Ability section;
    private BlockPos walkGoal;
    private Entity lookTarget;
    private UUID sayCard;
    private int index, pt, itemTicks, farTicks, offlineTicks, simpleScore = 100, rs, ceremony;
    private boolean done, applied;
    // decision
    private int outcome; private BadgeGrade result = BadgeGrade.NONE; private int[] finalScores = {-1, -1, -1, -1};

    private ExamSession(ServerLevel level, HumanNpc examiner, Player owner, YukkuriEntity candidate, int type, ExamHall hall, List<ExamItem> plan, int fee) {
        this.level = level; this.examiner = examiner; this.candidate = candidate; this.ownerId = owner.getUUID(); this.type = type; this.hall = hall; this.plan = plan; this.fee = fee;
        before = candidate.pet().grade(); unauthorizedBase = candidate.getPersistentData().getInt("ExamUnauthBase");
        walker.look = examiner; lookTarget = candidate;
    }

    // ---- ExamContext
    @Override public ServerLevel level() { return level; }
    @Override public HumanNpc examiner() { return examiner; }
    @Override public YukkuriEntity candidate() { return candidate; }
    @Override public Player owner() { return level.getServer().getPlayerList().getPlayer(ownerId); }
    @Override public ExamHall hall() { return hall; }
    @Override public BadgeGrade tier() {
        return switch (type) { case JUDGMENT -> item != null && item.ability() == Ability.POISE ? BadgeGrade.GOLD : BadgeGrade.SILVER;
            case PROMOTION -> BadgeGrade.of(Math.max(1, before.higher().id())); case RENEWAL -> before; default -> BadgeGrade.BRONZE; };
    }
    @Override public Random random() { return random; }
    @Override public int itemTicks() { return itemTicks; }
    @Override public boolean roll(Ability a) { return random.nextDouble() < chance(a); }
    @Override public double chance(Ability a) { return candidate.pet().successChance(a, tier()); }
    @Override public void examinerSay(String langKey, Object... args) {
        var text = Component.translatable(langKey, args);
        if (sayCard != null) Staging.removeCard(level, sayCard);
        sayCard = Staging.card(level, examiner.position().add(0, examiner.getBbHeight() + .45, 0), text, 60, 1.0f);
        var p = owner(); if (p != null) p.displayClientMessage(text, true);
    }
    @Override public UUID card(Vec3 pos, Component text, int ticks, float scale) { var id = Staging.card(level, pos, text, ticks, scale); if (id != null) cards.add(id); return id; }
    @Override public UUID itemCard(Vec3 pos, ItemStack stack, int ticks, float scale) { var id = Staging.itemCard(level, pos, stack, ticks, scale); if (id != null) cards.add(id); return id; }
    @Override public void examinerWalkTo(BlockPos pos) { walkGoal = pos; examiner.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, .9); }
    @Override public boolean examinerAt(BlockPos pos) {
        double dx = examiner.getX() - (pos.getX() + .5), dz = examiner.getZ() - (pos.getZ() + .5);
        return dx * dx + dz * dz <= 1.44 && Math.abs(examiner.getY() - pos.getY()) < 2;
    }
    @Override public void examinerLookAt(Entity e) { examiner.getLookControl().setLookAt(e, 30, 30); }
    @Override public void examinerSwing() { examiner.swing(InteractionHand.MAIN_HAND); }
    @Override public List<YukkuriEntity> trainees() {
        return examiner.animals("trainee").stream().filter(y -> y != candidate && (!y.pet().busy() || mine(y)) && !y.isPanicking())
                .sorted(Comparator.comparingDouble(y -> y.distanceToSqr(Vec3.atCenterOf(hall != null && hall.room != null ? hall.room : examiner.blockPosition())))).limit(3).toList();
    }
    /** Locked as an extra by this examiner's own session (not by another one). */
    private boolean mine(YukkuriEntity y) { return y.pet().busyWith().equals(LOCK_EXTRA) && examiner.getUUID().equals(y.pet().lockedBy()); }
    @Override public YukkuriEntity actor() {
        return examiner.animals("exam_actor").stream().filter(y -> !y.pet().busy() || mine(y)).findFirst().orElse(null);
    }
    @Override public void direct(YukkuriEntity y, PetLife.Director d) {
        if (y != candidate && !extraIds.contains(y.getUUID())) {
            if (!y.pet().lock(LOCK_EXTRA, examiner.getUUID())) return;
            extraIds.add(y.getUUID()); extras.add(y);
        }
        y.pet().direct(d); if (!directed.contains(y)) directed.add(y);
    }
    @Override public void note(String langKey) { if (notes.size() < 12 && !notes.contains(langKey)) notes.add(langKey); }

    // ---- flow
    private void clearCards() { for (var id : cards) Staging.removeCard(level, id); cards.clear(); }
    private void resetDirectors() {
        for (var y : directed) if (y.isAlive()) { if (y == candidate) y.pet().direct(walker); else { var w = new Walker(); w.look = examiner; y.pet().direct(w); } }
        directed.clear();
    }
    private int[] averages() {
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) r[i] = count[i] == 0 ? -1 : Math.round((float)sum[i] / count[i]);
        return r;
    }
    private BlockPos candidateSpot(Ability a) { return switch (a) { case MANNERS -> hall.room; case POISE -> hall.gold; default -> hall.podium; }; }
    private BlockPos examinerSpot(Ability a) { return switch (a) { case MANNERS, POISE -> hall.examinerSpot != null ? hall.examinerSpot : hall.desk; default -> hall.desk; }; }
    private void setSection(Ability a) { section = a; walker.goal = candidateSpot(a); examinerWalkTo(examinerSpot(a)); }
    private boolean arrived() {
        var g = walker.goal;
        boolean c = g == null || Math.abs(candidate.getY() - g.getY()) < 2 && Math.pow(candidate.getX() - g.getX() - .5, 2) + Math.pow(candidate.getZ() - g.getZ() - .5, 2) <= 1.0;
        return c && (walkGoal == null || examinerAt(walkGoal));
    }
    private String interruption() {
        if (!candidate.isAlive() || candidate.isRemoved() || !LOCK.equals(candidate.pet().busyWith())) return "lost";
        if (!examiner.isAlive()) return "examiner";
        if (examiner.distanceToSqr(Vec3.atCenterOf(examiner.home())) > 576) return "examiner";
        if (!level.isDay()) return "night";
        if (candidate.isPanicking()) return "panic";
        if (candidate.hurtTime > 0) return "hurt";
        if (candidate.isOnFire()) return "fire";
        if (candidate.isInWaterOrBubble()) return "water";
        if (candidate.isPassenger() || candidate.operations().active()) return "held";
        if (!candidate.life().hasAccessory()) return "accessory";
        var p = owner();
        if (p == null) { if (++offlineTicks > 200) return "owner_gone"; farTicks = 0; }
        else {
            offlineTicks = 0;
            if (p.level() != level || p.distanceToSqr(candidate) > 144) { if (farTicks++ == 0) p.displayClientMessage(Component.translatable("exam.mcyukkuri.stay_near"), true); if (farTicks > 60) return "owner_far"; }
            else farTicks = 0;
        }
        return null;
    }
    private void tick() {
        if (done) return;
        if (phase == Phase.RESULT) { if (!candidate.isAlive() || candidate.isRemoved() || !examiner.isAlive()) { settle(); return; } }
        else { var why = interruption(); if (why != null) { interrupt(why); return; } }
        if (walkGoal != null) { if (!examinerAt(walkGoal)) { if (examiner.tickCount % 10 == 0) examiner.getNavigation().moveTo(walkGoal.getX() + .5, walkGoal.getY(), walkGoal.getZ() + .5, .9); } else examiner.getNavigation().stop(); }
        if (lookTarget != null && phase != Phase.ITEM) examiner.getLookControl().setLookAt(lookTarget, 30, 30);
        pt++;
        switch (phase) {
            case GATHER -> {
                if (pt == 1) {
                    examinerSay("exam.mcyukkuri.say.intro");
                    Staging.act(candidate, "nervous", "confused", "exam_nervous", 60);
                    if (type != SIMPLE) setSection(plan.get(0).ability());
                }
                if (type == SIMPLE ? pt >= 30 : pt >= 40 && arrived() || pt > 240) {
                    if (type != SIMPLE) examinerSay("exam.mcyukkuri.say.section." + section.key()); else examinerSay("exam.mcyukkuri.say.go");
                    startItem();
                }
            }
            case MOVE -> {
                if (pt == 1) examinerSay("exam.mcyukkuri.say.section." + section.key());
                if (pt >= 30 && arrived() || pt > 240) { examinerSay("exam.mcyukkuri.say.go"); startItem(); }
            }
            case ITEM -> {
                itemTicks++;
                if (itemTicks > 2400) { interrupt("timeout"); return; }
                if (item.tick(this)) endItem();
            }
            case RESULT -> ceremony();
        }
    }
    private void startItem() {
        item = plan.get(index); itemTicks = 0; phase = Phase.ITEM; pt = 0; lookTarget = candidate;
        item.start(this);
    }
    private void endItem() {
        int score = Math.clamp(item.score(), 0, 100);
        if (type == SIMPLE) simpleScore = score; else { int a = item.ability().ordinal(); sum[a] += score; count[a]++; }
        clearCards(); resetDirectors(); item = null; index++; pt = 0;
        lookTarget = candidate;
        if (index >= plan.size()) { enterResult(); return; }
        var next = plan.get(index).ability();
        if (type != SIMPLE && next != section) { phase = Phase.MOVE; setSection(next); } else startItem();
    }
    /** Items are cleaned up, locks released and the animal left a "droop"; the exam is recorded as interrupted (outcome 4) with no refund. */
    private void interrupt(String reason) {
        if (done) return;
        if (phase == Phase.ITEM && item != null) { try { item.abort(this); } catch (RuntimeException e) { MCYukkuri.LOGGER.warn("Exam item abort failed", e); } }
        clearCards();
        candidate.pet().record(new ExamRecord(type, level.getGameTime(), type == SIMPLE ? null : averages(), before, 4, examiner.getUUID()));
        candidate.pet().setCooldown(COOLDOWN);
        var p = owner();
        if (p != null) p.displayClientMessage(Component.translatable("exam.mcyukkuri.interrupted", Component.translatable("exam.mcyukkuri.interrupt." + reason)), false);
        if (examiner.isAlive()) examinerSay("exam.mcyukkuri.say.interrupt");
        release();
        if (candidate.isAlive()) { candidate.playAction("droop"); candidate.showReaction("teary", "", 60); }
    }
    private void release() {
        done = true; clearCards();
        candidate.pet().unlock(LOCK);
        candidate.pet().hideBadge(false);
        candidate.pet().finishWildExam();
        for (var y : extras) y.pet().unlock(LOCK_EXTRA);
        if (examiner.isAlive()) examiner.getNavigation().stop();
        ACTIVE.remove(examiner.getUUID());
    }

    // ---- result
    private boolean bold() { return candidate.life().personality().trait(Trait.BOLDNESS) >= 55; }
    private void enterResult() {
        phase = Phase.RESULT; rs = 0; pt = 0; ceremony = 0;
        decide();
        if (type != SIMPLE && hall.podium != null) { walker.goal = hall.podium; examinerWalkTo(hall.podium.relative(hall.podiumFacing)); }
        else if (examiner.distanceToSqr(candidate) > 9) examinerWalkTo(candidate.blockPosition());
        lookTarget = candidate;
    }
    private void decide() {
        var pet = candidate.pet(); int[] s = type == SIMPLE ? new int[] {-1, -1, -1, -1} : averages();
        boolean unauthorized = (type == PROMOTION || type == RENEWAL) && pet.unauthorizedBirths() > unauthorizedBase;
        if (unauthorized && s[0] >= 0) { s[0] = Math.max(0, s[0] - 15); note("exam.mcyukkuri.log.unauthorized"); }
        switch (type) {
            case SIMPLE -> { outcome = simpleScore >= 100 ? 0 : 2; result = outcome == 0 ? BadgeGrade.BRONZE : BadgeGrade.NONE; }
            case JUDGMENT -> {
                result = BadgeGrade.NONE;
                for (var g : new BadgeGrade[] {BadgeGrade.GOLD, BadgeGrade.SILVER, BadgeGrade.BRONZE}) if (ExamPlan.meets(g, s)) { result = g; break; }
                outcome = result == BadgeGrade.NONE ? (before == BadgeGrade.NONE ? 2 : 3) : result.id() > before.id() ? 0 : result == before ? 1 : 3;
            }
            case PROMOTION -> {
                var next = before.higher();
                if (ExamPlan.meets(next, s)) { outcome = 0; result = next; }
                else if (ExamPlan.meets(before, s)) { outcome = 1; result = before; }
                else { outcome = 3; result = before.lower(); }
            }
            default -> {
                if (unauthorized) { outcome = 3; result = before.lower(); note("exam.mcyukkuri.log.renew_demoted"); }
                else if (s[0] >= (before == BadgeGrade.GOLD ? 75 : 60)) { outcome = 1; result = before; }
                else { outcome = 2; result = before; note("exam.mcyukkuri.log.renew_failed"); }
            }
        }
        finalScores = s;
        // consequences (M02): pressure of higher-level questions, then a possible slip after a failure
        if (type == JUDGMENT) { pet.addStress(8); for (int v : s) if (v >= 0 && v < 40) { pet.addStress(12); break; } }
        if (outcome >= 2) {
            if (owner() != null) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(owner(), "exam_retry");
            pet.addStress(15);
            var discipline = io.github.longshou.mcyukkuri.shop.CommodityLife.disciplineExam(candidate, examiner);
            if (discipline != null) note(discipline);
            var p = candidate.life().personality();
            if (pet.stress() >= 60 && p.selfControl() <= 40 && random.nextBoolean()) {
                if (random.nextBoolean()) { p.experienced(Disposition.SELFISHNESS, 5 + random.nextInt(6)); p.experienced(Disposition.SELF_CONTROL, -5); note("exam.mcyukkuri.log.gesu"); }
                else { pet.setAbility(Ability.MANNERS, pet.ability(Ability.MANNERS) - 5 - random.nextInt(11)); note("exam.mcyukkuri.log.forgot"); }
            }
        }
        pet.record(new ExamRecord(type, level.getGameTime(), s, result, outcome, examiner.getUUID()));
        pet.setCooldown(COOLDOWN);
        applyGrade(); // persisted now; the ceremony only reveals it (an unloaded candidate keeps its result)
        if (outcome == 0 || outcome == 3) pet.hideBadge(true);
        candidate.getPersistentData().putString("ExamNotes", String.join("|", notes));
        if (type == JUDGMENT && result.id() >= BadgeGrade.SILVER.id() && fee > 0) give(level, owner(), candidate.position(), new ItemStack(Items.EMERALD, 3));
        var p = owner();
        if (p != null) {
            p.sendSystemMessage(Component.translatable("exam.mcyukkuri.summary", candidate.getDisplayName(), Component.translatable("exam.mcyukkuri.type." + type),
                    sc(s[0]), sc(s[1]), sc(s[2]), sc(s[3]), Component.translatable("exam.mcyukkuri.outcome." + outcome, Component.translatable("badge.mcyukkuri.profile.grade." + result.id()))));
            for (var n : notes) p.sendSystemMessage(Component.literal("  ").append(Component.translatable(n)));
        }
    }
    private static String sc(int v) { return v < 0 ? "-" : Integer.toString(v); }
    /** Applies the grade change exactly once (at the pinning moment, or immediately if the ceremony cannot finish). */
    private void applyGrade() {
        if (applied) return; applied = true;
        var pet = candidate.pet(); var id = examiner.getUUID();
        switch (outcome) {
            case 0 -> pet.issue(result, type, id);
            case 1 -> pet.renew(id);
            case 3 -> { if (result == BadgeGrade.NONE) pet.revoke(); else pet.issue(result, type, id); }
            default -> {}
        }
        var owner = owner();
        if (owner != null && outcome == 0) {
            if (before == BadgeGrade.NONE) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(owner, "pet_registered");
            if (result.id() >= BadgeGrade.SILVER.id()) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(owner, "badge_silver");
            if (result == BadgeGrade.GOLD) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(owner, "badge_gold");
        }
        if ((type == PROMOTION || type == RENEWAL) && outcome != 2) candidate.getPersistentData().putInt("ExamUnauthBase", pet.unauthorizedBirths());
    }
    private void settle() { applyGrade(); release(); }
    private Vec3 headOf(Entity e) { return e.position().add(0, e.getBbHeight() + .35, 0); }
    private void ceremony() {
        if (rs == 0) {
            boolean near = examiner.distanceToSqr(candidate) <= 9 && (walker.goal == null || arrived() || pt > 60);
            if (!(pt >= 20 && near || pt >= 120)) return;
            rs = 1; ceremony = 0; walker.goal = null; walker.look = examiner; walkGoal = null; examiner.getNavigation().stop();
        }
        int c = ++ceremony; var pet = candidate.pet();
        if (c == 1) {
            var mid = candidate.position().add(examiner.position()).scale(.5).add(0, 1.9, 0);
            String kind = outcome == 0 ? (type == PROMOTION ? "promote" : "pass") : outcome == 1 ? (type == RENEWAL ? "renew" : "keep") : outcome == 2 ? "fail" : "demote";
            var id = Staging.card(level, mid, Component.translatable("exam.mcyukkuri.card." + kind, Component.translatable("badge.mcyukkuri.profile.grade." + result.id())), 100, 1.5f);
            if (id != null) cards.add(id);
            examinerSay(outcome <= 1 ? (outcome == 0 ? "exam.mcyukkuri.say.pass" : "exam.mcyukkuri.say.keep") : "exam.mcyukkuri.say.fail");
        }
        var chest = candidate.position().add(0, candidate.getBbHeight() * .7, 0);
        if (outcome == 0) {
            if (c == 20) { candidate.playAction("joy_jump"); candidate.showReaction("happy", "exam_pass", 60); Staging.sparkle(level, chest); Staging.sound(level, candidate.position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f); }
            if (c == 28) Staging.sparkle(level, chest);
            if (c == 34) examinerSwing();
            if (c == 40) { candidate.pet().hideBadge(false); Staging.sparkle(level, chest); Staging.sound(level, candidate.position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 1.5f); if (result.id() > before.id()) neighbours(); }
            if (c == 80 && bold()) { candidate.playAction("proud"); candidate.showReaction("smug", "badge_proud", 50); }
            if (c >= (bold() ? 120 : 90)) finish();
        } else if (outcome == 1) {
            if (c == 20) { candidate.playAction("bow"); candidate.showReaction("satisfied", "", 50); }
            if (c == 30) examinerSwing();
            if (c == 34) Staging.sparkle(level, chest);
            if (c >= 70) finish();
        } else {
            if (c == 15) candidate.showReaction("dazed", "", 24);
            if (c == 38) { candidate.playAction("tremble"); candidate.showReaction("cry", "exam_fail", 60); Staging.sound(level, candidate.position(), SoundEvents.VILLAGER_NO, 1.2f); }
            if (c == 40) candidate.pet().hideBadge(false);
            if (c == 80) {
                candidate.playAction("sulk"); candidate.showReaction("teary", "", 80);
                var p = owner(); double dx = p == null ? 0 : candidate.getX() - p.getX(), dz = p == null ? 1 : candidate.getZ() - p.getZ();
                float yaw = (float)(Math.atan2(dz, dx) * 57.29578) - 90f;
                walker.look = null; walker.lookPos = null; candidate.setYRot(yaw); candidate.yBodyRot = yaw; candidate.setYHeadRot(yaw);
            }
            if (c >= 140) finish();
        }
    }
    /** Trainees within 8 blocks react to a higher grade earned: envy from the bold, admiration from the caring, nothing from the rest. */
    private void neighbours() {
        for (var y : examiner.animals("trainee")) {
            if (y == candidate || y.distanceToSqr(candidate) > 64 || y.isPanicking()) continue;
            var p = y.life().personality();
            if (p.trait(Trait.BOLDNESS) >= 55) y.showReaction("teary", "badge_envy", 60);
            else if (p.trait(Trait.CARE) >= 55) y.showReaction("happy", "badge_admire", 60);
        }
    }
    private void finish() {
        applyGrade(); release();
        if (candidate.isAlive() && candidate.isCommandPlaying()) candidate.playAction("idle");
    }
}
