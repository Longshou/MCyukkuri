package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
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
    private UUID serial, issuer, lockedBy;
    private String lockKind;
    private Director director;
    private final PetLessons lessons;
    public PetLife(YukkuriEntity animal) { this.animal = animal; lessons = new PetLessons(animal); }
    public PetLessons lessons() { return lessons; }

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
    public BadgeGrade effectiveGrade() { return expired() ? grade.lower() : grade; }
    public BadgeGrade visibleGrade() { return worn && animal.life().hasAccessory() ? grade : BadgeGrade.NONE; }
    private BadgeGrade visibleEffective() { return expired() ? visibleGrade().lower() : visibleGrade(); }
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
        grade = g; this.source = source; this.issuer = issuer; serial = UUID.randomUUID(); issuedAt = now(); expiresAt = expiry(g); worn = true;
    }
    public void renew(UUID issuer) { if (grade == BadgeGrade.NONE) return; this.issuer = issuer; source = 6; issuedAt = now(); expiresAt = expiry(grade); }
    public void revoke() { grade = BadgeGrade.NONE; source = 0; issuer = null; serial = null; issuedAt = 0; expiresAt = -1; worn = true; }

    // token
    public ItemStack token() {
        if (grade == BadgeGrade.NONE) return ItemStack.EMPTY;
        var stack = new ItemStack(item(grade));
        if (serial == null) serial = UUID.randomUUID();
        final var id = serial;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("BadgeAnimal", animal.getUUID()); tag.putUUID("BadgeSerial", id); tag.putInt("BadgeTier", grade.id()); tag.putBoolean("BadgeGradeV2", true);
        });
        return stack;
    }
    private static net.minecraft.world.item.Item item(BadgeGrade g) {
        return g == BadgeGrade.GOLD ? HumanWorld.GOLD_BADGE.get() : g == BadgeGrade.SILVER ? HumanWorld.SILVER_BADGE.get() : HumanWorld.BRONZE_BADGE.get();
    }
    public boolean detach(Player p) {
        if (!near(p) || !owned(p) || busy() || !worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var stack = token(); worn = false; give(p, stack); return true;
    }
    public boolean attach(Player p, ItemStack stack) {
        if (!near(p) || !owned(p) || busy() || worn || !animal.life().hasAccessory() || grade == BadgeGrade.NONE) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!stack.is(item(grade)) || !tag.hasUUID("BadgeAnimal") || !tag.getUUID("BadgeAnimal").equals(animal.getUUID()) || !tag.hasUUID("BadgeSerial")
                || serial == null || !serial.equals(tag.getUUID("BadgeSerial"))) return false;
        worn = true; stack.shrink(1); return true;
    }
    /** New serial, old tokens die; the caller charges the fee and hands the stack over. */
    public ItemStack reissueToken() { if (grade == BadgeGrade.NONE) return ItemStack.EMPTY; serial = UUID.randomUUID(); worn = false; return token(); }
    private static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    public void markAccessory(ItemStack accessory) {
        CustomData.update(DataComponents.CUSTOM_DATA, accessory, tag -> {
            tag.putInt("BadgeTier", worn ? grade.id() : 0); tag.putBoolean("BadgeGradeV2", true);
            tag.putString("BadgeVariant", animal.getVariant().assetId());
        });
    }

    // abilities and growth
    public int ability(Ability a) { return abilities[a.ordinal()]; }
    public void setAbility(Ability a, int v) { abilities[a.ordinal()] = Math.clamp(v, 0, 100); }
    public int learn(Ability a, int base) {
        int old = ability(a); setAbility(a, old + Math.round(base * learningMultiplier())); return ability(a) - old;
    }
    public int routine(int kind) { return routines[kind]; }
    public void routineCompleted(int kind) { if (!animal.level().isClientSide) routines[kind] = Math.min(100, routines[kind] + 1); }
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
    public int cooldown() { return cooldown; }
    public void setCooldown(int ticks) { cooldown = Math.max(0, ticks); }
    public boolean breedingPermitted(UUID partner) { var until = permits.get(partner); return until != null && now() <= until; }
    public void permitBreeding(UUID partner, long untilGameTime) { permits.put(partner, untilGameTime); }
    public int unauthorizedBirths() { return unauthorized; }
    public void noteUnauthorizedBirth() { unauthorized = Math.min(99, unauthorized + 1); }

    // sessions
    public boolean busy() { return lockKind != null; }
    public String busyWith() { return lockKind == null ? "" : lockKind; }
    public UUID lockedBy() { return lockedBy; }
    public boolean lock(String kind, UUID by) {
        if (busy() || kind == null || kind.isEmpty()) return false;
        animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        animal.dailyLife().stop(); animal.predator().stop();
        lockKind = kind; lockedBy = by; lockGone = 0; animal.setPersistenceRequired(); return true;
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

    // stock and legacy
    public void initializeStock(BadgeGrade g, UUID shop, int pedigree) {
        if (source != 0 || g == null || g == BadgeGrade.NONE) return;
        for (int i = 0; i < g.id(); i++) animal.life().personality().lessonCompleted(shop);
        int base = g == BadgeGrade.GOLD ? 88 : g == BadgeGrade.SILVER ? 65 : 35, r = g == BadgeGrade.GOLD ? 4 : g == BadgeGrade.SILVER ? 2 : 1;
        for (var a : Ability.values()) setAbility(a, base + animal.getRandom().nextInt(11) - 5);
        Arrays.fill(routines, r); setPedigree(pedigree); issue(g, 2, shop);
    }
    /** Old YukkuriBadges data plus the medal booleans that used to live in YukkuriLife. */
    public void migrateLegacy(CompoundTag legacy, BadgeGrade medal) {
        var skills = legacy.getIntArray("Skills"); var old = legacy.getIntArray("Routines");
        Arrays.fill(abilities, 0); Arrays.fill(routines, 0); int sum = 0;
        for (int i = 0; i < Math.min(3, old.length); i++) { routines[i] = Math.clamp(old[i], 0, 100); sum += routines[i]; }
        for (int i = 0; i < Math.min(3, skills.length); i++) setAbility(Ability.values()[i + 1], skills[i]);
        setAbility(Ability.MANNERS, Math.min(100, sum * 10));
        boolean stock = legacy.getInt("Source") == 2;
        worn = !legacy.contains("Worn") || legacy.getBoolean("Worn");
        serial = legacy.hasUUID("Serial") ? legacy.getUUID("Serial") : null; issuer = legacy.hasUUID("Issuer") ? legacy.getUUID("Issuer") : null;
        grade = medal; source = medal == BadgeGrade.NONE ? 0 : stock ? 2 : 1;
        issuedAt = now(); expiresAt = medal == BadgeGrade.NONE ? -1 : expiry(medal);
        if (medal != BadgeGrade.NONE && serial == null) serial = UUID.randomUUID();
        setPedigree(stock ? 60 : 15 + animal.getRandom().nextInt(31));
    }

    // social
    public boolean reactTo(YukkuriEntity peer) {
        if (busy() || socialCooldown > 0 || peer.pet().visibleEffective() == BadgeGrade.NONE && visibleEffective() == BadgeGrade.NONE) return false;
        socialCooldown = 600;
        int mine = visibleEffective().id(), theirs = peer.pet().visibleEffective().id();
        var personality = animal.life().personality();
        boolean kind = personality.trait(Trait.CARE) >= 55 || animal.family().caresFor(peer)
                || io.github.longshou.mcyukkuri.life.YukkuriWorldData.get((ServerLevel)animal.level()).affinity(animal.getUUID(),peer.getUUID()) >= 10;
        String line; int relation;
        if (theirs > mine) {
            boolean envy = !kind && personality.trait(Trait.BOLDNESS) >= 55;
            line = envy ? "envy" : "admire"; relation = envy ? -2 : 2;
        } else if (mine > theirs) {
            line = kind ? "help" : personality.trait(Trait.BOLDNESS) >= 55 ? "boast" : "polite";
            relation = line.equals("boast") ? -2 : 2;
        } else { line = "peer"; relation = 2; }
        animal.sayBadge(line);
        io.github.longshou.mcyukkuri.life.YukkuriWorldData.get((ServerLevel)animal.level()).relate(animal.getUUID(), peer.getUUID(), relation);
        if (relation < 0) animal.life().deferSocial(200);
        return true;
    }

    public void tick() {
        if (animal.level().isClientSide) return;
        if (!pedigreeSet) setPedigree(15 + animal.getRandom().nextInt(31));
        if (cooldown > 0) cooldown--; if (socialCooldown > 0) socialCooldown--;
        lessons.tick();
        stressClock += animal.life().isSleeping() ? 2 : 1;
        if (stressClock >= 1200) { stressClock = 0; addStress(-1); }
        if (++indulgenceClock >= 6000) { indulgenceClock = 0; addIndulgence(-1); }
        if (++dayClock >= 24000) {
            dayClock = 0;
            if (indulgence > 60) { var p = animal.life().personality(); p.experienced(Disposition.SELFISHNESS, 1); p.experienced(Disposition.SELF_CONTROL, -1); }
            permits.values().removeIf(until -> until < now());
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
        tag.putInt("Cooldown", cooldown); tag.putInt("Unauthorized", unauthorized);
        if (serial != null) tag.putUUID("Serial", serial); if (issuer != null) tag.putUUID("Issuer", issuer);
        var list = new ListTag(); for (var r : history) list.add(r.save()); tag.put("History", list);
        var permitList = new ListTag();
        permits.forEach((id, until) -> { var e = new CompoundTag(); e.putUUID("Partner", id); e.putLong("Until", until); permitList.add(e); });
        tag.put("Permits", permitList); return tag;
    }
    public void load(CompoundTag tag) {
        loadArray(tag.getIntArray("Abilities"), abilities); loadArray(tag.getIntArray("Routines"), routines);
        grade = BadgeGrade.of(tag.getInt("Grade")); worn = !tag.contains("Worn") || tag.getBoolean("Worn");
        issuedAt = tag.getLong("IssuedAt"); expiresAt = tag.contains("ExpiresAt") ? tag.getLong("ExpiresAt") : -1; source = Math.clamp(tag.getInt("Source"), 0, 6);
        pedigree = Math.clamp(tag.getInt("Pedigree"), 0, 100); pedigreeSet = tag.getBoolean("PedigreeSet");
        stress = Math.clamp(tag.getInt("Stress"), 0, 100); indulgence = Math.clamp(tag.getInt("Indulgence"), 0, 100);
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 72000); unauthorized = Math.clamp(tag.getInt("Unauthorized"), 0, 99);
        serial = tag.hasUUID("Serial") ? tag.getUUID("Serial") : null; issuer = tag.hasUUID("Issuer") ? tag.getUUID("Issuer") : null;
        history.clear(); for (var e : tag.getList("History", 10)) { history.add(ExamRecord.load((CompoundTag)e)); }
        while (history.size() > 16) history.remove(0);
        permits.clear(); for (var e : tag.getList("Permits", 10)) { var c = (CompoundTag)e; if (c.hasUUID("Partner")) permits.put(c.getUUID("Partner"), c.getLong("Until")); }
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
