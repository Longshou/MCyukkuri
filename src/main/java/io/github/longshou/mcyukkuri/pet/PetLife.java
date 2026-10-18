package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.pet.social.BadgeLedger;
import io.github.longshou.mcyukkuri.pet.social.BadgePraise;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Everything an owner-registered individual carries: grade, abilities, records and growth state. Qualification belongs to the individual. */
public final class PetLife {
    public interface Director { void tick(YukkuriEntity animal); default void stop(YukkuriEntity animal) {} }
    private final YukkuriEntity animal;
    private final int[] abilities = new int[4], routines = new int[3];
    private final List<ExamRecord> history = new ArrayList<>();
    private final Map<UUID, Long> permits = new HashMap<>();
    private BadgeGrade grade = BadgeGrade.NONE;
    private boolean worn = true, pedigreeSet;
    private long issuedAt, expiresAt = -1;
    private int source, pedigree, stress, indulgence, cooldown, unauthorized, socialCooldown, stressClock, indulgenceClock, dayClock, lockGone;
    private UUID serial, issuer, lockedBy, formerOwner;
    private int neglect, neglectClock, searchClock, abandonedGrade, abandonStage, joyDelay, wildPoise;
    private long lastContact, abandonedAt, dropPos;
    private boolean voidBadge, reunionDue, noticeSent, waits;
    private String lockKind;
    private Director director;
    private final PetLessons lessons;
    private final BadgeLedger ledger = new BadgeLedger();
    private final PetCare care;
    public PetLife(YukkuriEntity animal) { this.animal = animal; lessons = new PetLessons(animal); care = new PetCare(animal); }
    public PetLessons lessons() { return lessons; }
    /** What this individual remembers of the badge-holders it has noticed (pet/social). */
    public BadgeLedger badgeLedger() { return ledger; }
    /** Sweets, habits, requests, walks and worn gear (docs/PET_SHOP.md section 12). */
    public PetCare care() { return care; }

    // ownership
    public UUID owner() { var t = HumanCommerce.data(animal); return t.hasUUID("Owner") ? t.getUUID("Owner") : null; }
    public boolean ownedBy(UUID id) { return id != null && id.equals(owner()); }
    public boolean isPet() { return owner() != null && HumanCommerce.data(animal).getString("Use").equals("companion"); }
    public boolean owned(Player p) { return ownedBy(p.getUUID()); }
    public boolean near(Player p) {
        return !animal.level().isClientSide && p != null && p.isAlive() && !p.isSpectator() && animal.isAlive()
                && p.level() == animal.level() && p.distanceToSqr(animal) <= 64 && p.hasLineOfSight(animal);
    }

    // grade
    private long now() { return animal.level().getGameTime(); }
    public BadgeGrade grade() { return grade; }
    public boolean expired() { return expiresAt >= 0 && now() > expiresAt; }
    public BadgeGrade effectiveGrade() { return voidBadge ? BadgeGrade.NONE : expired() ? grade.lower() : grade; }
    /** Transient (never saved): the exam ceremony keeps a freshly decided badge off the animal until the pinning moment. */
    private boolean badgeHidden;
    public void hideBadge(boolean hidden) { badgeHidden = hidden; }
    public BadgeGrade visibleGrade() { return worn && !badgeHidden && animal.life().hasAccessory() ? grade : BadgeGrade.NONE; }
    public BadgeGrade visibleEffective() { return voidBadge ? BadgeGrade.NONE : expired() ? visibleGrade().lower() : visibleGrade(); }
    public boolean worn() { return worn; }
    public long issuedAt() { return issuedAt; }
    public long expiresAt() { return expiresAt; }
    public int source() { return source; }
    public UUID serial() { return serial; }
    public UUID issuer() { return issuer; }
    private long expiry(BadgeGrade g) {
        int days; try { days = HumanWorld.BADGE_VALIDITY_DAYS.get(); } catch (RuntimeException e) { days = 20; }
        return g.id() >= BadgeGrade.SILVER.id() && days > 0 ? now() + days * 24000L : -1;
    }
    public void issue(BadgeGrade g, int source, UUID issuer) {
        if (g == null || g == BadgeGrade.NONE) { revoke(); return; }
        if (grade == BadgeGrade.NONE) animal.getPersistentData().putInt("ExamUnauthBase", unauthorized); // births before the first badge are not held against it
        grade = g; this.source = source; this.issuer = issuer; serial = UUID.randomUUID(); issuedAt = now(); expiresAt = expiry(g); worn = true; voidBadge = false;
    }
    public void renew(UUID issuer) { if (grade == BadgeGrade.NONE) return; this.issuer = issuer; source = 6; issuedAt = now(); expiresAt = expiry(grade); voidBadge = false; }
    public void revoke() { grade = BadgeGrade.NONE; source = 0; issuer = null; serial = null; issuedAt = 0; expiresAt = -1; worn = true; voidBadge = false; }

    // token
    public ItemStack token() {
        if (grade == BadgeGrade.NONE) return ItemStack.EMPTY;
        var stack = new ItemStack(item(grade));
        if (serial == null) serial = UUID.randomUUID();
        final var id = serial;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("BadgeAnimal", animal.getUUID()); tag.putUUID("BadgeSerial", id); tag.putInt("BadgeTier", grade.id());
        });
        return stack;
    }
    private static net.minecraft.world.item.Item item(BadgeGrade g) {
        return g == BadgeGrade.GOLD ? HumanWorld.GOLD_BADGE.get() : g == BadgeGrade.SILVER ? HumanWorld.SILVER_BADGE.get() : HumanWorld.BRONZE_BADGE.get();
    }
    public boolean detach(Player p) {
        if (!near(p) || !owned(p) || busy() || !worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var stack = token(); worn = false; give(p, stack); PetScenes.taken(animal, p); return true;
    }
    public boolean attach(Player p, ItemStack stack) {
        if (!near(p) || !owned(p) || busy() || worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!stack.is(item(grade)) || !tag.hasUUID("BadgeAnimal") || !tag.getUUID("BadgeAnimal").equals(animal.getUUID()) || !tag.hasUUID("BadgeSerial")
                || serial == null || !serial.equals(tag.getUUID("BadgeSerial"))) return false;
        worn = true; stack.shrink(1); PetScenes.pinned(animal, p); return true;
    }
    /** New serial, old tokens die; the caller charges the fee and hands the stack over. */
    /** A sold animal's badge: new serial (every earlier token dies, detached or not) and the badge is worn again. */
    public void rotateSerial() { if (grade == BadgeGrade.NONE) return; serial = UUID.randomUUID(); worn = true; }
    public ItemStack reissueToken() { if (grade == BadgeGrade.NONE) return ItemStack.EMPTY; serial = UUID.randomUUID(); worn = false; return token(); }
    private static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    public void markAccessory(ItemStack accessory) {
        CustomData.update(DataComponents.CUSTOM_DATA, accessory, tag -> {
            tag.putInt("BadgeTier", worn ? grade.id() : 0);
            tag.putString("BadgeVariant", animal.getVariant().assetId());
        });
    }

    // abilities and growth
    public int ability(Ability a) { return abilities[a.ordinal()]; }
    public void setAbility(Ability a, int v) { abilities[a.ordinal()] = Math.clamp(v, 0, 100); }
    public int learn(Ability a, int base) {
        int old = ability(a); setAbility(a, old + Math.round(base * learningMultiplier()));
        if (a == Ability.MANNERS) care.practiced();
        return ability(a) - old;
    }
    public int routine(int kind) { return routines[kind]; }
    /** Poise earned by calmly ignoring a wild yukkuri in front of the owner: at most 3 a game day. Returns whether anything was gained. */
    public boolean wildPoiseGain() {
        if (wildPoise >= 3) return false;
        wildPoise++; return learn(Ability.POISE, 1) > 0;
    }
    public void routineCompleted(int kind) { if (!animal.level().isClientSide) { routines[kind] = Math.min(100, routines[kind] + 1); care.habit(kind); } }
    public int pedigree() { return pedigree; }
    public void setPedigree(int v) { pedigree = Math.clamp(v, 0, 100); pedigreeSet = true; }
    public float learningMultiplier() { return 0.7f + pedigree * 0.006f; }
    public int stress() { return stress; }
    public void addStress(int d) { stress = Math.clamp(stress + d, 0, 100); }
    public int indulgence() { return indulgence; }
    public void addIndulgence(int d) { indulgence = Math.clamp(indulgence + d, 0, 100); }
    public double successChance(Ability a, BadgeGrade tier) {
        int difficulty = switch (tier == null ? BadgeGrade.BRONZE : tier) { case SILVER -> 50; case GOLD -> 75; default -> 25; };
        if (a == Ability.ACADEMICS) difficulty -= 10; else if (a == Ability.WITS) difficulty += 10;
        double p = 0.15 + (ability(a) - difficulty) / 60.0 + (pedigree - 50) / 625.0
                + (animal.life().personality().selfControl() - 50) / 250.0 - stress / 200.0;
        return Math.clamp(p, 0.05, 0.95);
    }

    // records
    public List<ExamRecord> history() { return Collections.unmodifiableList(history); }
    public void record(ExamRecord r) { history.add(r); while (history.size() > 16) history.remove(0); }
    /** Flavour title derived from the history (nothing saved): 0 none, 1 eternal silver, 2 hidden gold. M02 basis: docs/PET_SHOP.md 2. */
    public static final int TITLE_ETERNAL_SILVER = 1, TITLE_HIDDEN_GOLD = 2;
    public int title() { return title(history, grade); }
    public static int title(List<ExamRecord> records, BadgeGrade now) {
        var cur = BadgeGrade.NONE; int silverMisses = 0, fails = 0; long bronzeSince = -1; boolean hidden = false;
        for (var r : records) {
            if (r.outcome() == 4) continue;
            if (cur == BadgeGrade.SILVER && (r.type() == 4 || r.type() == 5) && r.outcome() >= 1 && r.outcome() <= 3) silverMisses++;
            if (r.outcome() == 2) fails++;
            if (r.result() == BadgeGrade.GOLD && r.outcome() == 0) {
                boolean low = cur.id() <= BadgeGrade.BRONZE.id();
                hidden = r.type() == 4 && low && (bronzeSince >= 0 && r.gameTime() - bronzeSince >= 48000 || fails >= 2);
            }
            if (r.result() == BadgeGrade.BRONZE && cur != BadgeGrade.BRONZE) bronzeSince = r.gameTime();
            else if (r.result() == BadgeGrade.NONE) bronzeSince = -1;
            cur = r.result();
        }
        return now == BadgeGrade.SILVER && silverMisses >= 3 ? TITLE_ETERNAL_SILVER : now == BadgeGrade.GOLD && hidden ? TITLE_HIDDEN_GOLD : 0;
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
    public boolean pedigreeKnown() { return pedigreeSet; }
    /** Rolled once per courtship attempt. 0 = no restriction or permitted, 1 = proceeds without permission, 2 = the pet backs off. */
    public int courtshipGate(YukkuriEntity partner) {
        if (!isPet() || breedingPermitted(partner.getUUID())) return 0;
        double chance = (100 - animal.life().personality().selfControl()) / 400.0 * (1 + indulgence / 100.0) * (1 + stress / 200.0);
        return animal.getRandom().nextDouble() < chance ? 1 : 2;
    }
    /** Conception without permission: counted on every owned parent and reported once to each owner who is online. */
    public static void unauthorizedConception(YukkuriEntity a, YukkuriEntity b) {
        Set<UUID> told = new HashSet<>();
        for (var y : List.of(a, b)) if (y.pet().isPet()) {
            y.pet().noteUnauthorizedBirth(); var owner = y.pet().owner(); var other = y == a ? b : a;
            if (owner != null && told.add(owner) && y.level() instanceof ServerLevel level && level.getServer().getPlayerList().getPlayer(owner) instanceof net.minecraft.server.level.ServerPlayer sp)
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable("pet.mcyukkuri.breed.unauthorized", y.getDisplayName(), other.getDisplayName()));
        }
    }
    public int unauthorizedBirths() { return unauthorized; }
    public void noteUnauthorizedBirth() { unauthorized = Math.min(99, unauthorized + 1); }

    // owner line: neglect and abandonment (docs/PET_SHOP.md 5.5)
    public int neglect() { return neglect; }
    public boolean voidBadge() { return voidBadge; }
    /** Former owner while the abandonment record stands; cleared by the next owner. */
    public UUID formerOwner() { return formerOwner; }
    public boolean abandoned() { return formerOwner != null && owner() == null; }
    /** Owner feeding, talking or caring for the pet, or merely being near it. Cruel actions refresh presence only. */
    public void contact(UUID who, boolean care) {
        if (animal.level().isClientSide || !ownedBy(who) || !isPet()) return;
        lastContact = now(); neglectClock = 0;
        if (!care) return;
        BadgePraise.contacted(animal, who);
        if (reunionDue && neglect >= 30 && calm()) { reunionDue = false; animal.playAction("joy_jump"); animal.showReaction("happy", "hello", 80); }
        neglect = Math.max(0, neglect - 10); if (neglect < 40) noticeSent = false;
    }
    /** Proximity counts as presence; the first sight after a long neglect is a reunion. */
    private void tickOwnerLine() {
        if (!isPet()) { neglect = Math.max(0, neglect); return; }
        if (lastContact == 0) lastContact = now();
        if (animal.tickCount % 100 == 11 && animal.level() instanceof net.minecraft.server.level.ServerLevel sl && sl.getEntity(owner()) instanceof io.github.longshou.mcyukkuri.human.HumanNpc resident && resident.distanceToSqr(animal) <= 256) {
            lastContact = now(); neglectClock = 0; // a resident's pet is with its keeper
        }
        if (animal.tickCount % 100 == 11 && animal.level().getPlayerByUUID(owner()) instanceof Player p && p.distanceToSqr(animal) <= 256) {
            if (reunionDue && neglect >= 30) contact(p.getUUID(), true); else { lastContact = now(); neglectClock = 0; }
        }
        if (now() - lastContact <= 72000) { searchClock = 0; return; }
        if (++neglectClock >= 2400) {
            neglectClock = 0; int before = neglect; neglect = Math.min(100, neglect + 1); addStress(2);
            if (before < 30 && neglect >= 30) reunionDue = true;
            if (before < 50 && neglect >= 50 && !noticeSent) {
                noticeSent = true;
                if (animal.level() instanceof ServerLevel level && level.getServer().getPlayerList().getPlayer(owner()) instanceof net.minecraft.server.level.ServerPlayer sp)
                    sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("pet.mcyukkuri.neglect.notice", animal.getDisplayName()), true);
            }
        }
        if (neglect > 0 && --searchClock <= 0) {
            searchClock = 2400 + animal.getRandom().nextInt(2400);
            if (calm()) { animal.playAction(animal.getRandom().nextBoolean() ? "look_around" : "droop"); animal.showReaction("teary", "lost_parent", 100); }
        }
    }
    /** Called by WildEvents.abandon before the owner is cleared. A worn badge stays on but its registry entry is void. */
    public void abandonedBy(UUID former) {
        if (animal.level() instanceof ServerLevel sl) AbandonLog.get(sl).record(former, now());
        care.ownerChanged(null);
        formerOwner = former; abandonedAt = now(); abandonedGrade = grade.id(); dropPos = animal.blockPosition().asLong(); abandonStage = 0; searchClock = 0;
        voidBadge = grade != BadgeGrade.NONE && worn && animal.life().hasAccessory();
        var p = animal.life().personality();
        waits = p.attachment() >= 60 && p.selfishness() < 60 || !animal.family().adult();
        neglect = 0; neglectClock = 0; reunionDue = false; noticeSent = false;
    }
    /** HumanCommerce.assign: any new owner ends the abandoned state. The former owner returning gets one tremble then joy, and the registry entry is honoured again; trust is left as it is. */
    public void newOwner(UUID owner) {
        care.ownerChanged(owner);
        if (formerOwner == null) return;
        boolean same = formerOwner.equals(owner);
        formerOwner = null; abandonStage = 0; waits = false; lastContact = now(); neglect = 0; neglectClock = 0; reunionDue = false; noticeSent = false;
        if (same) { voidBadge = false; animal.playAction("tremble"); joyDelay = 30; }
    }
    private void tickAbandoned() {
        if (joyDelay > 0 && --joyDelay == 0) { animal.playAction("joy_jump"); animal.showReaction("happy", "hello", 80); }
        if (formerOwner == null || owner() != null || now() - abandonedAt < 240 || !calm()) return;
        var p = animal.life().personality();
        if (abandonStage == 0) {
            abandonStage = 1;
            if (!waits && p.selfishness() >= 60) { animal.playAction("proud"); animal.showReaction("smug", "hello", 80); return; }
        }
        if (!waits || now() - abandonedAt > 24000) return; // gives up: an ordinary wild yukkuri, the record only keeps the badge void
        if (--searchClock > 0) return;
        searchClock = (animal.family().adult() ? 500 : 250) + animal.getRandom().nextInt(300);
        var drop = net.minecraft.core.BlockPos.of(dropPos);
        if (animal.blockPosition().distSqr(drop) > 36 && animal.life().dryStand(drop)) animal.life().navigate(drop, .6, false);
        else { animal.playAction("droop"); animal.showReaction("cry", "lost_parent", 100); }
    }

    /** A pet chased by a predator runs to its owner when the owner is close enough to help (called by the flee goal). */
    public boolean fleeToOwner() {
        if (!isPet() || !(animal.getLastHurtByMob() instanceof YukkuriEntity threat) || !threat.getVariant().isPredator()) return false;
        var o = animal.level().getPlayerByUUID(owner());
        if (o == null || !o.isAlive() || o.isSpectator() || o.distanceToSqr(animal) > 256) return false;
        if (o.distanceToSqr(animal) > 6.25) animal.getNavigation().moveTo(o, 1.7); else animal.getNavigation().stop();
        return true;
    }

    // sessions
    public boolean busy() { return lockKind != null; }
    public String busyWith() { return lockKind == null ? "" : lockKind; }
    public UUID lockedBy() { return lockedBy; }
    public boolean lock(String kind, UUID by) {
        if (busy() || kind == null || kind.isEmpty()) return false;
        animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        animal.dailyLife().stop(); animal.predator().stop();
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

    // stock
    public void initializeStock(BadgeGrade g, UUID shop, int pedigree) {
        if (source != 0 || g == null || g == BadgeGrade.NONE) return;
        for (int i = 0; i < g.id(); i++) animal.life().personality().lessonCompleted(shop);
        int base = g == BadgeGrade.GOLD ? 88 : g == BadgeGrade.SILVER ? 65 : 35, r = g == BadgeGrade.GOLD ? 4 : g == BadgeGrade.SILVER ? 2 : 1;
        for (var a : Ability.values()) setAbility(a, base + animal.getRandom().nextInt(11) - 5);
        Arrays.fill(routines, r); setPedigree(pedigree); issue(g, 2, shop);
    }
    // social
    private final Map<UUID, Long> noticed = new HashMap<>();
    private UUID stareAt, faceAt;
    private int stareTicks, faceTicks;
    /** Nothing that a badge performance may interrupt: sleep, meals, sessions, panic, family or daily tasks, command actions. */
    public boolean calm() {
        return !busy() && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger() && !animal.isPanicking() && !animal.isCommandPlaying()
                && !animal.operations().active() && !animal.life().isSleeping() && !animal.life().hasUrgentNeed() && animal.life().mealTicks() == 0
                && !animal.dailyLife().active() && !animal.groupInteraction().active() && !animal.playLife().active() && animal.hurtTime == 0
                && !animal.family().active();
    }
    private void face(YukkuriEntity peer, int ticks) { faceAt = peer.getUUID(); faceTicks = ticks; animal.getLookControl().setLookAt(peer, 30, 30); }
    public boolean reactTo(YukkuriEntity peer) {
        if (socialCooldown > 0 || stareTicks > 0 || !calm() || !peer.isAlive() || peer.pet().busy() || peer.life().isSleeping()
                || peer.pet().visibleEffective() == BadgeGrade.NONE && visibleEffective() == BadgeGrade.NONE) return false;
        if (BadgeSocial.enabled()) return BadgeSocial.greeting(animal, peer);
        if (!pairReady(peer)) return false; // one full reaction per pair per 10 minutes
        if (startStare(peer)) return true;
        react(peer); return true;
    }
    /** A look toward a peer that costs nothing and takes no control (tier 0 of the badge budget). */
    public void glanceAt(YukkuriEntity peer, int ticks) { face(peer, ticks); }
    /** The classic one-liner (action, face, line, one small relation step) for a deliberate greeting; returns the ledger flag it implies. */
    public int reactNow(YukkuriEntity peer) { return react(peer); }
    private boolean pairReady(YukkuriEntity peer) { Long last = noticed.get(peer.getUUID()); return last == null || now() - last >= 12000; }
    private void notePair(YukkuriEntity peer) {
        long t = now(); noticed.values().removeIf(v -> t - v > 12000);
        if (noticed.size() >= 48) noticed.clear();
        noticed.put(peer.getUUID(), t);
    }
    /** A wild observer pauses to stare at a visible silver/gold badge before it reacts, once per pair per 10 minutes. */
    private boolean startStare(YukkuriEntity peer) {
        if (owner() != null || peer.pet().visibleEffective().id() < BadgeGrade.SILVER.id() || !animal.hasLineOfSight(peer)) return false;
        long t = now(); Long last = noticed.get(peer.getUUID());
        if (last != null && t - last < 12000) return false;
        noticed.values().removeIf(v -> t - v > 12000); noticed.put(peer.getUUID(), t);
        stareAt = peer.getUUID(); stareTicks = 40; animal.life().deferSocial(60);
        animal.playAction("nervous"); animal.showReaction("surprise", "", 40);
        face(peer, 40);
        return true;
    }
    private int react(YukkuriEntity peer) {
        socialCooldown = 600; notePair(peer);
        int mine = visibleEffective().id(), theirs = peer.pet().visibleEffective().id();
        var personality = animal.life().personality();
        boolean bold = personality.trait(Trait.BOLDNESS) >= 55;
        var world = io.github.longshou.mcyukkuri.life.YukkuriWorldData.get((ServerLevel)animal.level());
        boolean kind = personality.trait(Trait.CARE) >= 55 || animal.family().caresFor(peer) || world.affinity(animal.getUUID(), peer.getUUID()) >= 10;
        int relation, flag = 0;
        if (theirs > mine) {
            boolean envy = !kind && bold; relation = envy ? -1 : 1; flag = envy ? BadgeLedger.ENVIED : BadgeLedger.ADMIRED;
            if (envy) { animal.playAction(animal.getRandom().nextBoolean() ? "puff" : "sulk"); animal.showReaction(animal.getRandom().nextBoolean() ? "angry" : "teary", "badge_envy", 80); }
            else { animal.playAction("beg"); animal.showReaction("open_smile", "badge_admire", 80); }
        } else if (mine > theirs) {
            boolean boast = !kind && bold; relation = boast ? -1 : 1;
            if (boast) { animal.playAction("proud"); animal.showReaction("smug", "badge_proud", 80); }
            else { animal.playAction(kind ? "bow" : "greet"); animal.showReaction("closed_smile", kind ? "peer" : "hello", 80); }
        } else {
            relation = 1; animal.playAction("greet"); animal.showReaction("happy", "peer", 80);
        }
        face(peer, 60);
        BadgeSocial.nudge(world, animal.getUUID(), peer.getUUID(), relation); // never past the bully / nuzzle guard rails
        if (relation < 0) animal.life().deferSocial(200);
        return flag;
    }
    /** Wild individuals notice nearby visible silver/gold badges without waiting for a formal greeting. */
    private void scanBadges() {
        if (owner() != null || socialCooldown > 0 || stareTicks > 0 || !calm()) return;
        var peer = animal.level().getEntitiesOfClass(YukkuriEntity.class, animal.getBoundingBox().inflate(6), y -> y != animal && y.isAlive()
                && y.pet().visibleEffective().id() >= BadgeGrade.SILVER.id() && animal.hasLineOfSight(y)).stream()
                .min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if (peer != null) reactTo(peer);
    }
    private void tickPerformance() {
        if (faceTicks > 0) {
            faceTicks--;
            if (((ServerLevel)animal.level()).getEntity(faceAt) instanceof YukkuriEntity y) animal.getLookControl().setLookAt(y.getX(), y.getEyeY(), y.getZ(), 30, 30);
        }
        if (stareTicks > 0) {
            stareTicks--;
            var y = ((ServerLevel)animal.level()).getEntity(stareAt) instanceof YukkuriEntity o && o.isAlive() ? o : null;
            if (y == null || !calm() || !animal.hasLineOfSight(y)) { stareTicks = 0; return; }
            if (stareTicks == 0 && socialCooldown == 0) react(y);
        } else if (animal.tickCount % 40 == 7) { if (BadgeSocial.enabled()) BadgeSocial.scan(animal); else scanBadges(); }
        else if (animal.tickCount % 40 == 27 && owner() == null && socialCooldown == 0 && !BadgeSocial.enabled()) WildEncounter.scan(animal);
    }

    public void tick() {
        if (animal.level().isClientSide) return;
        if (!pedigreeSet) setPedigree(15 + animal.getRandom().nextInt(31));
        if (cooldown > 0) cooldown--; if (socialCooldown > 0) socialCooldown--;
        lessons.tick(); care.tick(); tickPerformance(); tickOwnerLine(); tickAbandoned();
        stressClock += animal.life().isSleeping() ? 2 : 1;
        if (stressClock >= 1200) { stressClock = 0; addStress(-1); }
        if (++indulgenceClock >= 6000) { indulgenceClock = 0; addIndulgence(-1); }
        if (++dayClock >= 24000) {
            dayClock = 0; wildPoise = 0;
            if (indulgence > 60) { var p = animal.life().personality(); p.experienced(Disposition.SELFISHNESS, 1); p.experienced(Disposition.SELF_CONTROL, -1); }
            permits.values().removeIf(until -> until < now());
            if (neglect >= 60 && isPet()) { var p = animal.life().personality(); p.experienced(Disposition.ATTACHMENT, -1); p.experienced(Disposition.SELFISHNESS, 1); }
        }
        if (lockKind != null) {
            boolean present = lockedBy == null || animal.level().getPlayerByUUID(lockedBy) != null || ((ServerLevel)animal.level()).getEntity(lockedBy) != null;
            if (present) lockGone = 0; else if (++lockGone > 200) unlock(null);
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putIntArray("Abilities", abilities.clone()); tag.putIntArray("Routines", routines.clone());
        tag.putInt("Grade", grade.id()); tag.putBoolean("Worn", worn); tag.putLong("IssuedAt", issuedAt); tag.putLong("ExpiresAt", expiresAt); tag.putInt("Source", source);
        tag.putInt("Pedigree", pedigree); tag.putBoolean("PedigreeSet", pedigreeSet); tag.putInt("Stress", stress); tag.putInt("Indulgence", indulgence);
        tag.putInt("Neglect", neglect); tag.putLong("LastContact", lastContact); tag.putBoolean("ReunionDue", reunionDue); tag.putBoolean("NoticeSent", noticeSent);
        tag.putBoolean("VoidBadge", voidBadge); tag.putLong("AbandonedAt", abandonedAt); tag.putInt("AbandonedGrade", abandonedGrade); tag.putLong("DropPos", dropPos);
        tag.putBoolean("Waits", waits); tag.putInt("AbandonStage", abandonStage); if (formerOwner != null) tag.putUUID("FormerOwner", formerOwner);
        tag.putInt("Cooldown", cooldown); tag.putInt("WildPoise", wildPoise); tag.putInt("Unauthorized", unauthorized); tag.putInt("LessonsToday", lessons.today()); tag.putInt("LessonDayClock", lessons.dayClock());
        if (serial != null) tag.putUUID("Serial", serial); if (issuer != null) tag.putUUID("Issuer", issuer);
        var list = new ListTag(); for (var r : history) list.add(r.save()); tag.put("History", list);
        var permitList = new ListTag();
        permits.forEach((id, until) -> { var e = new CompoundTag(); e.putUUID("Partner", id); e.putLong("Until", until); permitList.add(e); });
        tag.put("Permits", permitList); tag.put("BadgeLedger", ledger.save()); tag.put("Care", care.save()); return tag;
    }
    public void load(CompoundTag tag) {
        loadArray(tag.getIntArray("Abilities"), abilities); loadArray(tag.getIntArray("Routines"), routines);
        grade = BadgeGrade.of(tag.getInt("Grade")); worn = !tag.contains("Worn") || tag.getBoolean("Worn");
        issuedAt = tag.getLong("IssuedAt"); expiresAt = tag.contains("ExpiresAt") ? tag.getLong("ExpiresAt") : -1; source = Math.clamp(tag.getInt("Source"), 0, 6);
        pedigree = Math.clamp(tag.getInt("Pedigree"), 0, 100); pedigreeSet = tag.getBoolean("PedigreeSet");
        stress = Math.clamp(tag.getInt("Stress"), 0, 100); indulgence = Math.clamp(tag.getInt("Indulgence"), 0, 100);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 72000); wildPoise = Math.clamp(tag.getInt("WildPoise"), 0, 3); unauthorized = Math.clamp(tag.getInt("Unauthorized"), 0, 99);
        lessons.restore(tag.getInt("LessonsToday"), tag.getInt("LessonDayClock"));
        care.load(tag.getCompound("Care"));
        serial = tag.hasUUID("Serial") ? tag.getUUID("Serial") : null; issuer = tag.hasUUID("Issuer") ? tag.getUUID("Issuer") : null;
        history.clear(); for (var e : tag.getList("History", 10)) { history.add(ExamRecord.load((CompoundTag)e)); }
        while (history.size() > 16) history.remove(0);
        permits.clear(); for (var e : tag.getList("Permits", 10)) { var c = (CompoundTag)e; if (c.hasUUID("Partner")) permits.put(c.getUUID("Partner"), c.getLong("Until")); }
        neglect = Math.clamp(tag.getInt("Neglect"), 0, 100); lastContact = tag.getLong("LastContact"); reunionDue = tag.getBoolean("ReunionDue"); noticeSent = tag.getBoolean("NoticeSent");
        voidBadge = tag.getBoolean("VoidBadge"); abandonedAt = tag.getLong("AbandonedAt"); abandonedGrade = Math.clamp(tag.getInt("AbandonedGrade"), 0, 3); dropPos = tag.getLong("DropPos");
        waits = tag.getBoolean("Waits"); abandonStage = tag.getInt("AbandonStage"); formerOwner = tag.hasUUID("FormerOwner") ? tag.getUUID("FormerOwner") : null;
        ledger.load(tag.getCompound("BadgeLedger"));
        lockKind = null; lockedBy = null; director = null; lockGone = 0;
    }
    private static void loadArray(int[] from, int[] values) {
        Arrays.fill(values, 0); for (int i = 0; i < Math.min(from.length, values.length); i++) values[i] = Math.clamp(from[i], 0, 100);
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
