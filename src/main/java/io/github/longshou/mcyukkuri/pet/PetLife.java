package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.badge.BadgeRequirements;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.entity.component.Interrupt;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.social.BadgeLedger;
import io.github.longshou.mcyukkuri.pet.social.BadgePraise;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/** Everything an owner-registered individual carries: grade, real skills, records and owner state. Qualification belongs to the individual. */
public final class PetLife {
    private static final int OWNER_SAMPLE = 10, OWNER_ABSENCE = 600, OWNER_WELCOME = 600;
    private static final double OWNER_SIGHT_SQ = 12 * 12;
    public interface Director { void tick(YukkuriEntity animal); default void stop(YukkuriEntity animal) {} }
    private final YukkuriEntity animal;
    private final List<ExamRecord> history = new ArrayList<>();
    private final Map<UUID, Long> permits = new HashMap<>();
    private BadgeGrade grade = BadgeGrade.NONE;
    private boolean worn = true;
    private long issuedAt;
    private int source, cooldown, unauthorized, dayClock, lockGone;
    private UUID serial, issuer, lockedBy, formerOwner;
    private int neglect, neglectClock, searchClock, abandonedGrade, abandonStage, joyDelay;
    private long lastContact, abandonedAt, dropPos;
    private boolean voidBadge, noticeSent, waits, townRoots;
    // Loaded ticks only: positive while away, negative during the welcome window.
    private int ownerAbsence;
    private String lockKind;
    private Director director;
    private final PetLessons lessons;
    private final BadgeLedger ledger = new BadgeLedger();
    private final PetCare care;
    public PetLife(YukkuriEntity animal) { this.animal = animal; lessons = new PetLessons(animal); care = new PetCare(animal); }
    public PetLessons lessons() { return lessons; }
    /** What this individual remembers of the badge-holders it has noticed (pet/social). */
    public BadgeLedger badgeLedger() { return ledger; }
    /** Sweets, habits, requests, walks and worn gear (docs/FEATURES.md). */
    public PetCare care() { return care; }

    // ownership
    public UUID owner() { return Custody.view(animal).owner(); }
    public boolean ownedBy(UUID id) { return id != null && id.equals(owner()); }
    public boolean isPet() { return animal.status().identity().companion(); }
    public boolean owned(Player p) { return ownedBy(p.getUUID()); }
    public boolean near(Player p) {
        return !animal.level().isClientSide && p != null && InteractionDispatch.reachRefusal(p,animal,8)==null;
    }

    // grade
    private long now() { return animal.level().getGameTime(); }
    public BadgeGrade grade() { return grade; }
    public BadgeGrade effectiveGrade() { return voidBadge ? BadgeGrade.NONE : grade; }
    public int talent() {
        var p = animal.life().personality();
        return BadgeRequirements.talent(p.potential(Trait.INTELLIGENCE), p.potential(Trait.SELF_CONTROL),
                p.potential(Trait.FRIENDLINESS), p.potential(Trait.ATTACHMENT));
    }
    /** Rollback for a temporary wild-candidate owner from an older save. */
    private CompoundTag examWildTag;
    private UUID examTemporaryOwner;
    public void finishWildExam() {
        if (examWildTag != null && animal.isAlive() && grade == BadgeGrade.NONE && ownedBy(examTemporaryOwner))
            Custody.release(animal, Custody.ReleaseReason.EXAM_ROLLBACK, null, examWildTag);
        examWildTag = null; examTemporaryOwner = null;
    }
    public BadgeGrade visibleGrade() { return worn && animal.life().hasAccessory() ? grade : BadgeGrade.NONE; }
    public BadgeGrade visibleEffective() { return voidBadge ? BadgeGrade.NONE : visibleGrade(); }
    public boolean worn() { return worn; }
    public long issuedAt() { return issuedAt; }
    public int source() { return source; }
    public UUID serial() { return serial; }
    public UUID issuer() { return issuer; }
    public void issue(BadgeGrade g, int source, UUID issuer) {
        if (g == null || g == BadgeGrade.NONE) { revoke(); return; }
        BadgeGrade previous = grade;
        grade = g; this.source = source; this.issuer = issuer; serial = ModRandom.identity(animal.getRandom()); issuedAt = now(); worn = true; voidBadge = false;
        GameplayLog.event(animal, "badge", "issued grade={} -> {} issuer={} serial={} source={}", previous, grade, issuer, serial, source);
    }
    public void revoke() {
        BadgeGrade previous = grade; UUID previousSerial = serial;
        grade = BadgeGrade.NONE; source = 0; issuer = null; serial = null; issuedAt = 0; worn = true; voidBadge = false;
        if (previous != BadgeGrade.NONE) GameplayLog.event(animal, "badge", "revoked grade={} serial={}", previous, previousSerial);
    }

    // token
    public ItemStack token() {
        if (grade == BadgeGrade.NONE) return ItemStack.EMPTY;
        var stack = new ItemStack(item(grade));
        if (serial == null) serial = ModRandom.identity(animal.getRandom());
        final var id = serial;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("BadgeAnimal", animal.getUUID()); tag.putUUID("BadgeSerial", id); tag.putInt("BadgeTier", grade.id());
        });
        return stack;
    }
    private static Item item(BadgeGrade g) {
        return switch (g) {
            case NONE -> throw new IllegalArgumentException("No token for an unbadged individual");
            case BRONZE -> HumanWorld.BRONZE_BADGE.get();
            case SILVER -> HumanWorld.SILVER_BADGE.get();
            case GOLD -> HumanWorld.GOLD_BADGE.get();
            case PLATINUM -> HumanWorld.PLATINUM_BADGE.get();
        };
    }
    public boolean detach(Player p) {
        if (!near(p) || !owned(p) || busy() || ExamSession.running(animal) || !worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var stack = token(); worn = false; give(p, stack); PetScenes.taken(animal, p);
        GameplayLog.event(animal, "badge", "detached player={} grade={} serial={}", p.getUUID(), grade, serial);
        return true;
    }
    public boolean attach(Player p, ItemStack stack) {
        if (!near(p) || !owned(p) || busy() || ExamSession.running(animal) || worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!stack.is(item(grade)) || !tag.hasUUID("BadgeAnimal") || !tag.getUUID("BadgeAnimal").equals(animal.getUUID()) || !tag.hasUUID("BadgeSerial")
                || serial == null || !serial.equals(tag.getUUID("BadgeSerial"))) return false;
        worn = true; stack.shrink(1); PetScenes.pinned(animal, p);
        GameplayLog.event(animal, "badge", "attached player={} grade={} serial={} remaining={}", p.getUUID(), grade, serial, stack.getCount());
        return true;
    }
    /** New serial, old tokens die; the caller charges the fee and hands the stack over. */
    /** A sold animal's badge: new serial (every earlier token dies, detached or not) and the badge is worn again. */
    public void rotateSerial() { if (grade == BadgeGrade.NONE) return; serial = ModRandom.identity(animal.getRandom()); worn = true; }
    public ItemStack reissueToken() { if (grade == BadgeGrade.NONE) return ItemStack.EMPTY; serial = ModRandom.identity(animal.getRandom()); worn = false; return token(); }
    private static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    public void markAccessory(ItemStack accessory) {
        CustomData.update(DataComponents.CUSTOM_DATA, accessory, tag -> {
            tag.putInt("BadgeTier", worn ? grade.id() : 0);
            tag.putString("BadgeVariant", animal.getVariant().assetId());
        });
    }

    // real skills and shared unease
    /** Existing life completions route here after the facility actually worked. */
    public void routineCompleted(Ability ability) {
        lessons.performed(ability, true);
        care.habit();
    }
    /** A view of the individual's unease (mind/MindLife, docs/FEATURES.md): the pet line reads and writes the one shared value. */
    public int stress() { return animal.mind().stressView(); }
    public void addStress(int d) { animal.mind().add(d, "pet"); }

    // records
    public List<ExamRecord> history() { return Collections.unmodifiableList(history); }
    public void record(ExamRecord r) {
        history.add(r);
        if (history.size() > 16) history.removeFirst();
    }
    /** Flavour title derived from the history (nothing saved): 0 none, 1 eternal silver, 2 hidden gold. M02 basis: docs/FEATURES.md. */
    public static final int TITLE_ETERNAL_SILVER = 1, TITLE_HIDDEN_GOLD = 2;
    public int title() { return title(history, grade); }
    public static int title(List<ExamRecord> records, BadgeGrade now) {
        int goldMisses = 0, failures = 0;
        boolean hidden = false;
        for (var r : records) {
            if (r.type() != ExamSession.CERTIFY) continue;
            if (r.outcome() == 2) {
                failures++;
                if (r.result() == BadgeGrade.SILVER) goldMisses++;
            }
            if (r.outcome() == 0 && r.result() == BadgeGrade.GOLD) hidden = failures >= 2;
        }
        return now == BadgeGrade.SILVER && goldMisses >= 3 ? TITLE_ETERNAL_SILVER : now == BadgeGrade.GOLD && hidden ? TITLE_HIDDEN_GOLD : 0;
    }
    public int cooldown() { return cooldown; }
    public void setCooldown(int ticks) { cooldown = Math.max(0, ticks); }
    public boolean breedingPermitted(UUID partner) { var until = permits.get(partner); return until != null && now() <= until; }
    public void permitBreeding(UUID partner, long untilGameTime) { permits.put(partner, untilGameTime); }
    public long permitUntil(UUID partner) { var until = permits.get(partner); return until != null && now() <= until ? until : -1; }
    /** The partner whose permit lasts longest, or null. */
    public UUID permitPartner() {
        UUID best = null; long t = -1;
        for (var e : permits.entrySet()) if (now() <= e.getValue() && e.getValue() > t) { best = e.getKey(); t = e.getValue(); }
        return best;
    }
    public void revokeBreeding(UUID partner) { permits.remove(partner); }
    /** Rolled once per courtship attempt. 0 = no restriction or permitted, 1 = proceeds without permission, 2 = the pet backs off. */
    public int courtshipGate(YukkuriEntity partner) {
        if (!isPet() || breedingPermitted(partner.getUUID())) return 0;
        // One decision per actual courtship opportunity. Pressure and a hidden "spoiled" score do
        // not reopen the decision; self-control's shared rule curve is the only behavior weight.
        return animal.getRandom().nextDouble() >= animal.life().personality().ruleProbability() ? 1 : 2;
    }
    /** Conception without permission: counted on every owned parent and reported once to each owner who is online. */
    public static void unauthorizedConception(YukkuriEntity a, YukkuriEntity b) {
        Set<UUID> told = new HashSet<>();
        for (var y : List.of(a, b)) if (y.pet().isPet()) {
            y.pet().noteUnauthorizedBirth(); var owner = y.pet().owner(); var other = y == a ? b : a;
            if (owner != null && told.add(owner) && y.level() instanceof ServerLevel level && level.getServer().getPlayerList().getPlayer(owner) instanceof ServerPlayer sp)
                sp.sendSystemMessage(Component.translatable("pet.mcyukkuri.breed.unauthorized", y.getDisplayName(), other.getDisplayName()));
        }
    }
    public int unauthorizedBirths() { return unauthorized; }
    public void noteUnauthorizedBirth() { unauthorized = Math.min(99, unauthorized + 1); }

    // owner line: neglect and abandonment (docs/FEATURES.md)
    public int neglect() { return neglect; }
    public boolean voidBadge() { return voidBadge; }
    /** Former owner while the abandonment record stands; cleared by the next owner. */
    public UUID formerOwner() { return formerOwner; }
    public boolean abandoned() { return formerOwner != null && owner() == null; }
    public boolean waitingAfterAbandonment() { return abandoned() && waits && now() - abandonedAt <= 24000; }
    /** An unkept individual still tied to human-town life, including offspring raised in it. */
    public boolean townRoots() { return townRoots; }
    public void markTownRoots() { townRoots = true; }
    public void leaveTownLife() { townRoots = false; waits = false; }
    /** Owner feeding, talking or caring for the pet, or merely being near it. Cruel actions refresh presence only. */
    public void contact(UUID who, boolean care) {
        if (animal.level().isClientSide || !ownedBy(who) || !isPet()) return;
        lastContact = now(); neglectClock = 0;
        if (!care) return;
        BadgePraise.contacted(animal, who);
        neglect = Math.max(0, neglect - 10); if (neglect < 40) noticeSent = false;
    }
    private LivingEntity presentOwner() {
        var person = ((ServerLevel) animal.level()).getEntity(owner());
        return person instanceof HumanNpc resident && resident.isAlive() ? resident
                : person instanceof Player player && player.isAlive() && !player.isSpectator() ? player : null;
    }

    public boolean seesReturningOwner(LivingEntity person) {
        return nearReturningOwner(person) && animal.hasLineOfSight(person) && person.hasLineOfSight(animal);
    }

    /** The owner is still at greeting range: a gate post between them does not end a welcome already under way. */
    public boolean nearReturningOwner(LivingEntity person) {
        return ownedBy(person.getUUID()) && animal.distanceToSqr(person) <= OWNER_SIGHT_SQ;
    }

    public boolean canWelcomeOwner() {
        return calm() && !animal.life().protectsStep() && !animal.isLeashed() && !animal.house().active()
                && !animal.profile().houseBody() && !animal.profile().aquatic()
                && !animal.childhood().needsNewbornCare() && !animal.family().tendsUnweaned();
    }

    /** The social goal spends a welcome once when it can choose an approach. Sleep can outlast the window. */
    public LivingEntity takeOwnerWelcome() {
        if (ownerAbsence >= 0 || !canWelcomeOwner()) return null;
        var person = presentOwner();
        if (person == null || !seesReturningOwner(person)) return null;
        ownerAbsence = 0;
        var personality = animal.life().personality();
        var bond = personality.relation(person.getUUID());
        double affection = SocialDisposition.ramp(animal.dailyLife().affectionNeed(), 40, 80);
        double chance = Math.min(1, .35 + .60 * SocialDisposition.ramp(personality.attachment(), 30, 80) + .25 * affection);
        return SocialDisposition.humanWariness(animal, person, bond) < .50
                && (neglect >= 30 || animal.getRandom().nextDouble() < chance) ? person : null;
    }

    public String ownerWelcomeClip() {
        var personality = animal.life().personality();
        double joy = Math.max(SocialDisposition.ramp(neglect, 20, 40),
                SocialDisposition.ramp(animal.dailyLife().affectionNeed(), 55, 80));
        if (animal.getRandom().nextDouble() < joy) return "joy_jump";
        double manners = SocialDisposition.ramp(animal.skills().grade(Ability.GREETING), 1, 3)
                * SocialDisposition.ramp(personality.selfControl(), 45, 65);
        return animal.getRandom().nextDouble() < manners ? "bow" : "greet";
    }

    public void ownerWelcomed(UUID owner, String clip) {
        boolean joyful = clip.equals("joy_jump");
        contact(owner, true);
        animal.dailyLife().comfort(joyful ? 35 : 15);
        if (joyful) animal.life().personality().relationshipChanged(owner, 3, 0);
        else addStress(-2);
    }

    public void startYardBond(UUID owner) {
        animal.life().personality().relationshipChanged(owner, 24, 0);
        animal.skills().practise(Ability.FOOD_GUARD, 2, "yard");
        animal.skills().practise(Ability.TOILET, 2, "yard");
        GameplayLog.event(animal, "pet", "yard bond established owner={} closeness={} bowl={} toilet={}",
                owner, animal.life().personality().trust(owner), animal.skills().grade(Ability.FOOD_GUARD),
                animal.skills().grade(Ability.TOILET));
    }

    /** Ordinary presence pauses neglect. A visible return opens a separate approach window. */
    private void tickOwnerLine() {
        if (!isPet()) { ownerAbsence = 0; return; }
        if (lastContact == 0) lastContact = now();
        if (animal.tickCount % OWNER_SAMPLE == 1) {
            var person = presentOwner();
            if (person != null && seesReturningOwner(person)) {
                ownerAbsence = ownerAbsence >= OWNER_ABSENCE ? -OWNER_WELCOME : Math.min(0, ownerAbsence + OWNER_SAMPLE);
            } else {
                ownerAbsence = Math.min(OWNER_ABSENCE, Math.max(0, ownerAbsence) + OWNER_SAMPLE);
            }
            if (animal.tickCount % 100 == 11 && person != null && animal.distanceToSqr(person) <= 256) {
                lastContact = now(); neglectClock = 0;
            }
        }
        if (now() - lastContact <= 72000) { searchClock = 0; return; }
        if (++neglectClock >= 2400) {
            neglectClock = 0; int before = neglect; neglect = Math.min(100, neglect + 1); addStress(2);
            if (before < 50 && neglect >= 50 && !noticeSent) {
                noticeSent = true;
                if (animal.level() instanceof ServerLevel level && level.getServer().getPlayerList().getPlayer(owner()) instanceof ServerPlayer sp)
                    sp.displayClientMessage(Component.translatable("pet.mcyukkuri.neglect.notice", animal.getDisplayName()), true);
            }
            if (neglect == 100 && animal.status().identity() == Identity.PET && !busy()) {
                var player = animal.level().getPlayerByUUID(owner());
                if (player == null || player.distanceToSqr(animal) > 256) {
                    Custody.release(animal, Custody.ReleaseReason.LOST_CONTACT, owner(), null);
                    return;
                }
                contact(player.getUUID(), true);
            }
        }
        if (neglect > 0 && --searchClock <= 0) {
            searchClock = 2400 + animal.getRandom().nextInt(2400);
            if (calm()) { animal.playAction(animal.getRandom().nextBoolean() ? "look_around" : "droop"); animal.mind().felt(EmotionEvent.SEPARATED); animal.showReaction("teary", "lost_parent", 100); }
        }
    }
    /** Called by WildEvents.abandon before the owner is cleared. A worn badge stays on but its registry entry is void. */
    public void abandonedBy(UUID former) {
        townRoots = true;
        care.ownerChanged(null);
        formerOwner = former; abandonedAt = now(); abandonedGrade = grade.id(); dropPos = animal.blockPosition().asLong(); abandonStage = 0; searchClock = 0;
        voidBadge = grade != BadgeGrade.NONE && worn && animal.life().hasAccessory();
        waits = animal.life().personality().temperament().is(Gate.WAITS_FOR_OWNER) || !animal.family().adult();
        neglect = 0; neglectClock = 0; ownerAbsence = 0; noticeSent = false;
    }
    /** HumanCommerce.assign: any new owner ends the abandoned state. The former owner returning gets one tremble then joy, and the registry entry is honoured again; trust is left as it is. */
    public void newOwner(UUID owner) {
        animal.outing().forget();
        care.ownerChanged(owner);
        lastContact = now(); neglect = 0; neglectClock = 0; ownerAbsence = 0; noticeSent = false;
        if (formerOwner != null) {
            boolean same = formerOwner.equals(owner);
            formerOwner = null; abandonStage = 0; waits = false;
            if (same) { voidBadge = false; animal.playAction("tremble"); joyDelay = 30; }
        }
        PetPrivileges.note(animal);
    }
    /** End NPC control while preserving the animal's badge and history. */
    public void ownerReleased(UUID former) {
        animal.outing().forget();
        townRoots = true;
        care.ownerChanged(null);
        care.walk().tick(); // no owner/lead: clear only the transient walking position
        if (care.requests().pending() != null) care.requests().tick();
        unlock(null);
        if (director != null) direct(null);
        lastContact = now(); neglect = 0; neglectClock = 0; searchClock = 0;
        ownerAbsence = 0; noticeSent = false; joyDelay = 0;
        if (former.equals(examTemporaryOwner)) { examWildTag = null; examTemporaryOwner = null; }
    }
    public void lostContact() {
        townRoots = true;
        care.ownerChanged(null);
        neglect = 0; neglectClock = 0; searchClock = 0; ownerAbsence = 0; noticeSent = false;
        animal.mind().felt(EmotionEvent.SEPARATED);
        animal.showReaction("teary", "lost_parent", 100);
    }
    private void tickAbandoned() {
        if (joyDelay > 0 && --joyDelay == 0) { animal.playAction("joy_jump"); animal.showReaction("happy", "hello", 80); }
        if (!townRoots || !abandoned() || now() - abandonedAt < 240 || !calm()) return;
        if (abandonStage == 0) {
            abandonStage = 1;
            if (!waits && !animal.life().personality().temperament().is(Gate.WARM)) { animal.playAction("proud"); animal.showReaction("smug", "hello", 80); return; }
        }
        if (!waits || now() - abandonedAt > 24000) return;
        if (--searchClock > 0) return;
        searchClock = (animal.family().adult() ? 500 : 250) + animal.getRandom().nextInt(300);
        var drop = BlockPos.of(dropPos);
        if (animal.blockPosition().distSqr(drop) > 36 && animal.life().dryStand(drop)) animal.life().navigate(drop, .6, false);
        else { animal.playAction("droop"); animal.mind().felt(EmotionEvent.SEPARATED); animal.showReaction("cry", "lost_parent", 100); }
    }

    // sessions
    public boolean busy() { return lockKind != null; }
    public String busyWith() { return lockKind == null ? "" : lockKind; }
    public UUID lockedBy() { return lockedBy; }
    public boolean lock(String kind, UUID by) {
        if (busy() || kind == null || kind.isEmpty()) return false;
        animal.interrupt(Interrupt.PET_LOCK);
        lockKind = kind; lockedBy = by; lockGone = 0; animal.setPersistenceRequired(); care.released(); return true;
    }
    public void unlock(String kind) {
        if (lockKind == null || kind != null && !kind.equals(lockKind)) return;
        lockKind = null; lockedBy = null; lockGone = 0;
        if (director != null) { var d = director; director = null; d.stop(animal); }
        animal.getNavigation().stop();
    }
    public void direct(Director d) { if (director != null && director != d) director.stop(animal); director = d; }
    public Director director() { return director; }
    /** Safety gate shared by every owner-side flow. */
    public boolean readyFor(Player p) {
        return near(p) && owned(p) && !busy() && !animal.isNoAi() && !animal.isPassenger() && !animal.isPanicking()
                && !animal.isCommandPlaying() && !animal.operations().active() && !animal.life().hasUrgentNeed()
                && animal.life().mealTicks() == 0 && !animal.life().isSleeping() && animal.hurtTime == 0
                && animal.life().bladder() < 80 && animal.life().hygiene() >= 30;
    }

    // social
    private UUID faceAt;
    private int faceTicks;
    /** Nothing that a badge performance may interrupt: sleep, meals, sessions, panic, family or daily tasks, command actions. */
    public boolean calm() {
        return !busy() && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger() && !animal.isPanicking() && !animal.isCommandPlaying()
                && !animal.operations().active() && !animal.life().isSleeping() && !animal.life().hasUrgentNeed() && animal.life().mealTicks() == 0
                && !animal.dailyLife().active() && !animal.groupInteraction().active() && !animal.playLife().active() && animal.hurtTime == 0
                && !animal.family().active();
    }
    private void face(YukkuriEntity peer, int ticks) { faceAt = peer.getUUID(); faceTicks = ticks; animal.getLookControl().setLookAt(peer, 30, 30); }
    /** A look toward a peer that costs nothing and takes no control (tier 0 of the badge budget). */
    public void glanceAt(YukkuriEntity peer, int ticks) { face(peer, ticks); }
    private void tickPerformance() {
        if (faceTicks > 0) {
            faceTicks--;
            if (((ServerLevel)animal.level()).getEntity(faceAt) instanceof YukkuriEntity y) animal.getLookControl().setLookAt(y.getX(), y.getEyeY(), y.getZ(), 30, 30);
        }
        if (animal.tickCount % 40 == 7) BadgeSocial.scan(animal);
    }

    public void tick() {
        if (animal.level().isClientSide) return;
        // Run after all entity/NeoForge data has loaded, before ownership-dependent routines.
        if (examWildTag != null && !ExamSession.running(animal)) finishWildExam();
        if (cooldown > 0) cooldown--;
        lessons.tick(); care.tick(); tickPerformance(); tickOwnerLine(); tickAbandoned();
        if (++dayClock >= 24000) {
            dayClock = 0;
            permits.values().removeIf(until -> until < now());
        }
        if (lockKind != null) {
            boolean present = lockedBy == null || animal.level().getPlayerByUUID(lockedBy) != null
                    || ((ServerLevel)animal.level()).getEntity(lockedBy) != null || ExamSession.ownerPresent(animal, lockedBy);
            if (present) lockGone = 0; else if (++lockGone > 200) unlock(null);
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        if (examWildTag != null && examTemporaryOwner != null) {
            tag.put("ExamWildTag", examWildTag.copy()); tag.putUUID("ExamTemporaryOwner", examTemporaryOwner);
        }
        tag.putInt("Grade", grade.id()); tag.putBoolean("Worn", worn); tag.putLong("IssuedAt", issuedAt); tag.putInt("Source", source);
        tag.putInt("Neglect", neglect); tag.putLong("LastContact", lastContact); tag.putBoolean("NoticeSent", noticeSent);
        tag.putBoolean("VoidBadge", voidBadge); tag.putLong("AbandonedAt", abandonedAt); tag.putInt("AbandonedGrade", abandonedGrade); tag.putLong("DropPos", dropPos);
        tag.putBoolean("Waits", waits); tag.putInt("AbandonStage", abandonStage); if (formerOwner != null) tag.putUUID("FormerOwner", formerOwner);
        tag.putBoolean("TownRoots", townRoots);
        tag.putInt("Cooldown", cooldown); tag.putInt("Unauthorized", unauthorized); tag.putInt("LessonsToday",
                lessons.today()); tag.putInt("LessonDayClock", lessons.dayClock());
        if (serial != null) tag.putUUID("Serial", serial); if (issuer != null) tag.putUUID("Issuer", issuer);
        var list = new ListTag(); for (var r : history) list.add(r.save()); tag.put("History", list);
        var permitList = new ListTag();
        permits.forEach((id, until) -> { var e = new CompoundTag(); e.putUUID("Partner", id); e.putLong("Until", until); permitList.add(e); });
        tag.put("Permits", permitList); tag.put("BadgeLedger", ledger.save()); tag.put("Care", care.save()); return tag;
    }
    public void load(CompoundTag tag) {
        grade = BadgeGrade.of(tag.getInt("Grade")); worn = !tag.contains("Worn") || tag.getBoolean("Worn");
        issuedAt = tag.getLong("IssuedAt"); source = Math.clamp(tag.getInt("Source"), 0, ExamSession.CERTIFY);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 72000); unauthorized = Math.clamp(tag.getInt("Unauthorized"), 0, 99);
        lessons.restore(tag.getInt("LessonsToday"), tag.getInt("LessonDayClock"));
        care.load(tag.getCompound("Care"));
        serial = tag.hasUUID("Serial") ? tag.getUUID("Serial") : null; issuer = tag.hasUUID("Issuer") ? tag.getUUID("Issuer") : null;
        history.clear(); for (var e : tag.getList("History", 10)) { history.add(ExamRecord.load((CompoundTag)e)); }
        while (history.size() > 16) history.remove(0);
        permits.clear(); for (var e : tag.getList("Permits", 10)) { var c = (CompoundTag)e; if (c.hasUUID("Partner")) permits.put(c.getUUID("Partner"), c.getLong("Until")); }
        neglect = Math.clamp(tag.getInt("Neglect"), 0, 100); lastContact = tag.getLong("LastContact"); noticeSent = tag.getBoolean("NoticeSent"); ownerAbsence = 0;
        voidBadge = tag.getBoolean("VoidBadge"); abandonedAt = tag.getLong("AbandonedAt"); abandonedGrade = BadgeGrade.of(tag.getInt("AbandonedGrade")).id(); dropPos = tag.getLong("DropPos");
        waits = tag.getBoolean("Waits"); abandonStage = tag.getInt("AbandonStage"); formerOwner = tag.hasUUID("FormerOwner") ? tag.getUUID("FormerOwner") : null;
        townRoots = tag.contains("TownRoots") ? tag.getBoolean("TownRoots") : formerOwner != null;
        ledger.load(tag.getCompound("BadgeLedger"));
        examWildTag = tag.contains("ExamWildTag", 10) && tag.hasUUID("ExamTemporaryOwner") ? tag.getCompound("ExamWildTag").copy() : null;
        examTemporaryOwner = examWildTag == null ? null : tag.getUUID("ExamTemporaryOwner");
        lockKind = null; lockedBy = null; director = null; lockGone = 0;
    }
    public static final class SessionGoal extends Goal {
        private final YukkuriEntity animal;
        public SessionGoal(YukkuriEntity animal) { this.animal = animal; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return animal.pet().busy(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            var pet = animal.pet();
            if (pet.director() != null) { pet.director().tick(animal); return; }
            animal.getNavigation().stop();
            if (pet.lockedBy() != null) { var p = animal.level().getPlayerByUUID(pet.lockedBy()); if (p != null) animal.getLookControl().setLookAt(p, 25, 25); }
        }
    }
}
