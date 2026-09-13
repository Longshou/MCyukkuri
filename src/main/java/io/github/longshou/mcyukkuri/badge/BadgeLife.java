package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** Certification belongs to an individual; the removable token never grants a qualification. */
public final class BadgeLife {
    public static final int TRAIN_TICKS = 300, RETRY_TICKS = 1200;
    private final YukkuriEntity animal;
    private final int[] skills = new int[3], routines = new int[3], scores = new int[5];
    private int source, attempts, cooldown, result, session, elapsed, stage, wantedTier;
    private boolean worn = true;
    private UUID serial, issuer, trainer, examIssuer;
    private BlockPos station;
    private Vec3 origin;
    private int socialCooldown;
    public BadgeLife(YukkuriEntity animal) { this.animal = animal; }
    public int skill(int i) { return skills[i]; }
    public int routine(int i) { return routines[i]; }
    public int score(int i) { return scores[i]; }
    public int source() { return source; } // 0 none, 1 legacy, 2 shop stock, 3 examined
    public int attempts() { return attempts; }
    public int cooldown() { return cooldown; }
    public int result() { return result; } // 0 none, 1 passed, 2 interrupted, 10..14 failed dimension
    public int session() { return session; } // 1..3 practice, 4 examination
    public int stage() { return stage; }
    public int wantedTier() { return wantedTier; }
    public int elapsed() { return elapsed; }
    public boolean active() { return session != 0; }
    public boolean examinedBy(UUID npc) { return session == 4 && npc.equals(examIssuer); }
    public boolean worn() { return worn; }
    public int visibleTier() { return worn && animal.life().hasAccessory() ? animal.life().medal() : 0; }
    public void routineCompleted(int kind) { if (!animal.level().isClientSide) routines[kind] = Math.min(100, routines[kind] + 1); }
    public boolean owned(Player p) { return HumanCommerce.ownedBy(animal, p.getUUID()); }
    public boolean register(Player p) {
        if (!near(p) || HumanCommerce.data(animal).hasUUID("Owner") || !HumanCommerce.mayCapture(p, animal)
                || animal.life().personality().trust(p.getUUID()) < 10) return false;
        HumanCommerce.assign(animal, p.getUUID(), "companion"); return true;
    }
    public boolean near(Player p) {
        return !animal.level().isClientSide && p != null && p.isAlive() && !p.isSpectator() && animal.isAlive()
                && p.level() == animal.level() && p.distanceToSqr(animal) <= 64 && p.hasLineOfSight(animal);
    }
    private boolean ready(Player p) {
        return near(p) && owned(p) && !animal.isNoAi() && !animal.isPassenger() && !animal.isPanicking()
                && !animal.isCommandPlaying() && !animal.operations().active() && !animal.life().hasUrgentNeed()
                && animal.life().mealTicks() == 0 && !animal.life().isSleeping() && animal.hurtTime == 0
                && animal.life().bladder() < 80 && animal.life().hygiene() >= 30;
    }
    public boolean train(Player p, int kind) {
        if (kind < 0 || kind >= 3 || active() || cooldown > 0 || !ready(p) || skills[kind] >= 100) return false;
        begin(p, kind + 1); return true;
    }
    private void begin(Player p, int type) {
        animal.life().interrupt(); animal.social().stop(); animal.groupInteraction().stop(); animal.playLife().stop();
        animal.dailyLife().stop(); animal.predator().stop();
        session = type; trainer = p.getUUID(); elapsed = stage = 0; origin = animal.position(); result = 0;
        animal.setPersistenceRequired(); animal.sayBadge(type == 4 ? "exam" : "practice");
    }
    public boolean exam(Player p, HumanNpc examiner, int tier) {
        if (tier < 1 || tier > 2 || animal.life().medal() >= tier || active() || cooldown > 0 || !ready(p)
                || !validExaminer(examiner, p) || !animal.life().hasAccessory() || !pay(p, tier == 2 ? 8 : 3, false)) return false;
        BlockPos destination = null;
        for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var pos = animal.blockPosition().relative(dir, 2);
            if (animal.life().dryStand(pos) && animal.life().reachable(pos, false) && pos.distSqr(examiner.blockPosition()) <= 36) { destination = pos; break; }
        }
        if (destination == null || !examiner.beginBadgeExam(p, animal)) return false;
        pay(p, tier == 2 ? 8 : 3, true); begin(p, 4); station = destination;
        examIssuer = examiner.getUUID(); wantedTier = tier; attempts++; Arrays.fill(scores, -1); return true;
    }
    private boolean validExaminer(HumanNpc npc, Player p) {
        return npc != null && npc.job() == HumanNpc.Job.SHOPKEEPER && HumanMenu.valid(p, npc)
                && npc.level().isDay() && animal.distanceToSqr(npc) <= 36 && animal.hasLineOfSight(npc);
    }
    public static boolean pay(Player p, int amount, boolean take) {
        if (p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum() < amount) return false;
        if (take) for (var stack : p.getInventory().items) if (stack.is(Items.EMERALD)) {
            int use = Math.min(amount, stack.getCount()); stack.shrink(use); amount -= use; if (amount == 0) break;
        }
        return true;
    }
    /** Authored stock has provenance, not a fabricated player examination history. */
    public void initializeStock(int tier, UUID shop) {
        if (source != 0 || tier < 1 || tier > 2) return;
        for (int i = 0; i <= tier; i++) animal.life().personality().lessonCompleted(shop);
        Arrays.fill(skills, tier == 2 ? 90 : 65); Arrays.fill(routines, tier == 2 ? 4 : 2);
        animal.life().setMedal(tier); source = 2; issuer = shop; serial = UUID.randomUUID(); worn = true;
    }
    public void markAccessory(ItemStack accessory) {
        CustomData.update(DataComponents.CUSTOM_DATA, accessory, tag -> {
            tag.putInt("BadgeTier", worn ? animal.life().medal() : 0);
            tag.putString("BadgeVariant", animal.getVariant().assetId());
        });
    }
    private ItemStack token() {
        var stack = new ItemStack(animal.life().medal() == 2 ? HumanWorld.GOLD_BADGE.get() : HumanWorld.SILVER_BADGE.get());
        if (serial == null) serial = UUID.randomUUID();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("BadgeAnimal", animal.getUUID()); tag.putUUID("BadgeSerial", serial); tag.putInt("BadgeTier", animal.life().medal());
        });
        return stack;
    }
    public boolean detach(Player p) {
        if (!near(p) || !owned(p) || active() || !worn || !animal.life().hasAccessory() || animal.life().medal() == 0) return false;
        var stack = token(); worn = false; give(p, stack); return true;
    }
    public boolean attach(Player p, ItemStack stack) {
        if (!near(p) || !owned(p) || active() || worn || !animal.life().hasAccessory() || animal.life().medal() == 0) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!stack.is(animal.life().medal() == 2 ? HumanWorld.GOLD_BADGE.get() : HumanWorld.SILVER_BADGE.get())
                || !tag.hasUUID("BadgeAnimal") || !tag.getUUID("BadgeAnimal").equals(animal.getUUID()) || !tag.hasUUID("BadgeSerial")
                || serial == null || !serial.equals(tag.getUUID("BadgeSerial"))) return false;
        worn = true; stack.shrink(1); return true;
    }
    public boolean reissue(Player p, HumanNpc npc) {
        if (!near(p) || !owned(p) || active() || worn && animal.life().hasAccessory() || animal.life().medal() == 0 || !validExaminer(npc, p) || !pay(p, 1, true)) return false;
        serial = UUID.randomUUID(); worn = false; give(p, token()); return true;
    }
    private static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    public void tick() {
        if (animal.level().isClientSide) return;
        if (cooldown > 0) cooldown--; if (socialCooldown > 0) socialCooldown--;
        if (!active()) return;
        var p = animal.level().getPlayerByUUID(trainer);
        var npc = examIssuer == null ? null : ((ServerLevel)animal.level()).getEntity(examIssuer);
        if (!ready(p) || session == 4 && (!(npc instanceof HumanNpc examiner) || !validExaminer(examiner, p)
                || !animal.life().hasAccessory())) { finish(2); return; }
        elapsed++;
        if (session == 4 && stage == 4 && elapsed % 20 == 0) {
            ((ServerLevel)animal.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    station.getX()+.5,station.getY()+.1,station.getZ()+.5,3,.12,.02,.12,0);
        }
        if (session < 4) {
            if (elapsed >= TRAIN_TICKS) {
                int kind = session - 1; skills[kind] = Math.min(100, skills[kind] + 10);
                animal.life().spendEnergy(2); animal.life().personality().experienced(kind == 2 ? Trait.CARE : Trait.CURIOSITY, 1);
                session = 0; cooldown = 100; animal.sayBadge("learned");
            }
            return;
        }
        if (stage == 0 && animal.position().distanceToSqr(origin) > .64) { scores[0] = 0; finish(10); return; }
        int duration = stage == 4 ? 240 : 100;
        if (stage == 4 && animal.distanceToSqr(Vec3.atBottomCenterOf(station)) < .64) {
            scores[4] = 100; completeExam(); return;
        }
        if (elapsed < duration) return;
        int threshold = wantedTier == 2 ? 80 : 60;
        scores[stage] = switch (stage) {
            case 0 -> Math.min(100, 40 + animal.life().personality().lessons() * 20);
            case 1 -> Math.min(skills[0], Math.min(100, Math.min(routines[0] * 30, Math.min(routines[1] * 30, routines[2] * 60))));
            case 2 -> skills[1];
            case 3 -> skills[2];
            default -> 0;
        };
        if (scores[stage] < threshold) { finish(10 + stage); return; }
        stage++; elapsed = 0; animal.sayBadge(stage == 4 ? "recall" : "thinking");
    }
    private void completeExam() {
        animal.life().setMedal(wantedTier); source = 3; issuer = examIssuer; serial = UUID.randomUUID(); worn = true;
        finish(1);
    }
    public void cancel(Player p) { if (owned(p) && active()) finish(2); }
    private void finish(int code) {
        if (session == 4 && examIssuer != null && ((ServerLevel)animal.level()).getEntity(examIssuer) instanceof HumanNpc npc) npc.endBadgeExam(animal);
        result = code; session = 0; cooldown = RETRY_TICKS; animal.getNavigation().stop();
        animal.sayBadge(code == 1 ? "passed" : code == 2 ? "interrupted" : "failed");
    }
    public boolean reactTo(YukkuriEntity peer) {
        if (active() || socialCooldown > 0 || peer.badges().visibleTier() == 0 && visibleTier() == 0) return false;
        socialCooldown = 600;
        int mine = visibleTier(), theirs = peer.badges().visibleTier();
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
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putIntArray("Skills", skills.clone()); tag.putIntArray("Routines", routines.clone()); tag.putIntArray("Scores", scores.clone());
        tag.putInt("Source", source); tag.putInt("Attempts", attempts); tag.putInt("Cooldown", cooldown); tag.putInt("Result", active() ? 2 : result);
        tag.putBoolean("Worn", worn); tag.putBoolean("InterruptedSession", active());
        if (serial != null) tag.putUUID("Serial", serial); if (issuer != null) tag.putUUID("Issuer", issuer); return tag;
    }
    public void load(CompoundTag tag) {
        loadArray(tag, "Skills", skills); loadArray(tag, "Routines", routines); loadArray(tag, "Scores", scores);
        source = tag.contains("Source") ? Math.clamp(tag.getInt("Source"), 0, 3) : animal.life().medal() > 0 ? 1 : 0;
        attempts = Math.max(0, tag.getInt("Attempts")); result = tag.getInt("Result"); cooldown = Math.clamp(tag.getInt("Cooldown"), 0, RETRY_TICKS);
        if (tag.getBoolean("InterruptedSession")) { result = 2; cooldown = RETRY_TICKS; }
        worn = !tag.contains("Worn") || tag.getBoolean("Worn"); serial = tag.hasUUID("Serial") ? tag.getUUID("Serial") : null;
        issuer = tag.hasUUID("Issuer") ? tag.getUUID("Issuer") : null; session = elapsed = stage = 0;
    }
    private static void loadArray(CompoundTag tag, String key, int[] values) {
        Arrays.fill(values, 0); var from = tag.getIntArray(key);
        for (int i = 0; i < Math.min(from.length, values.length); i++) values[i] = Math.clamp(from[i], key.equals("Scores") ? -1 : 0, 100);
    }
    public static final class SessionGoal extends Goal {
        private final YukkuriEntity animal;
        public SessionGoal(YukkuriEntity animal) { this.animal = animal; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return animal.badges().active(); }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() {
            var state = animal.badges();
            if (state.session == 4 && state.stage == 4 && state.station != null) {
                if (state.elapsed % 10 == 0) animal.life().navigate(state.station, 1, false);
            } else animal.getNavigation().stop();
            var p = animal.level().getPlayerByUUID(state.trainer); if (p != null) animal.getLookControl().setLookAt(p, 25, 25);
        }
    }
}
