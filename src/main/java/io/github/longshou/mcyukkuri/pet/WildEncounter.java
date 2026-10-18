package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** The staged Poise exam out in the world (compare exam/items/PoiseWildItem): a wild yukkuri begs from, provokes or pleads in front of a pet, and the pet answers
 *  by its Poise. Transient and short (about 4 s of the wild one's time); cooldowns are not persisted. This is the legacy path: with badge_social.enabled
 *  (the default) pet/social/BadgeArcs plays the begging, provoking and pleading arcs instead. Encounters happen wherever a wild one meets a pet; the owner is only
 *  involved (praise, the action-bar note, the plea) when close. A shared bite really changes hands or nothing is claimed. */
public final class WildEncounter {
    private enum Mode { BEG, PROVOKE, PLEA }
    private static final DeadlineMap<UUID> WILD = new DeadlineMap<>(), PET = new DeadlineMap<>(), TOLD = new DeadlineMap<>();
    private WildEncounter() {}

    public static void reset() { WILD.clear(); PET.clear(); TOLD.clear(); }
    private static boolean held(DeadlineMap<UUID> map, UUID id, long now) { return map.active(id, now); }
    private static void hold(DeadlineMap<UUID> map, UUID id, long now, int ticks) {
        if (map.size() > 256) { map.prune(now); if (map.size() > 2048) map.clear(); }
        map.put(id, now + ticks);
    }
    /** Wild side: awake, unoccupied, not in trouble. Hunger itself is no obstacle. */
    private static boolean free(YukkuriEntity y) {
        return y.isAlive() && !y.isNoAi() && !y.isPassenger() && !y.isPanicking() && !y.isCommandPlaying() && !y.pet().busy() && !y.operations().active()
                && !y.life().isSleeping() && y.life().mealTicks() == 0 && !y.dailyLife().active() && !y.groupInteraction().active() && !y.playLife().active()
                && !y.family().active() && y.hurtTime == 0 && y.getLastHurtByMob() == null && !y.isInWaterOrBubble() && !y.isOnFire()
                && y.life().bladder() < 90 && y.life().energy() >= 25;
    }
    private static boolean broken(YukkuriEntity y) {
        return y.isNoAi() || y.isPassenger() || y.isPanicking() || y.hurtTime > 0 || y.pet().busy() || y.operations().active() || y.life().isSleeping()
                || y.isInWaterOrBubble() || y.isOnFire();
    }
    private static Player ownerNear(ServerLevel level, YukkuriEntity pet) {
        var id = pet.pet().owner(); if (id == null) return null;
        var o = level.getPlayerByUUID(id);
        return o != null && o.isAlive() && !o.isSpectator() && o.distanceToSqr(pet) <= 144 ? o : null;
    }

    /** Called every 2 s for an unowned animal (PetLife.tickPerformance). */
    public static void scan(YukkuriEntity wild) {
        if (!(wild.level() instanceof ServerLevel level) || wild.profile().dangerToYukkuri() || wild.profile().houseBody() || wild.pet().owner() != null || !free(wild)) return;
        long now = level.getGameTime();
        if (held(WILD, wild.getUUID(), now)) return;
        int bold = wild.life().personality().trait(Trait.BOLDNESS);
        boolean hungry = wild.life().nutrition() < 50;
        if (!hungry && bold >= 45 && bold < 55) { hold(WILD, wild.getUUID(), now, 1200); return; }
        var pet = level.getEntitiesOfClass(YukkuriEntity.class, wild.getBoundingBox().inflate(8), y -> y != wild && y.pet().isPet() && !held(PET, y.getUUID(), now)
                && y.pet().calm() && wild.hasLineOfSight(y)).stream().min(Comparator.comparingDouble(wild::distanceToSqr)).orElse(null);
        if (pet == null) { hold(WILD, wild.getUUID(), now, 1200); return; }
        Mode mode = hungry ? Mode.BEG : bold >= 55 ? (pet.pet().visibleGrade() != BadgeGrade.NONE ? Mode.PROVOKE : null) : ownerNear(level, pet) != null ? Mode.PLEA : null;
        if (mode == null) { hold(WILD, wild.getUUID(), now, 1200); return; }
        // never in the middle of a hunt
        if (!level.getEntitiesOfClass(YukkuriEntity.class, wild.getBoundingBox().inflate(16), v -> v.profile().dangerToYukkuri()
                && v.predator().active()).isEmpty()) { hold(WILD, wild.getUUID(), now, 600); return; }
        hold(WILD, wild.getUUID(), now, 36000); hold(PET, pet.getUUID(), now, 12000); // a pet meets a given wild one a few times a day, not every two minutes
        ShopPerformance.schedule(level, new Scene(level, wild, pet, mode));
    }

    private static final class Scene implements IntPredicate {
        private final ServerLevel level;
        private final UUID wildId, petId;
        private final Mode mode;
        private final boolean share, win, petBold;
        private int respondAt, pleaAt = -1;
        private boolean gain, shared;

        Scene(ServerLevel level, YukkuriEntity wild, YukkuriEntity pet, Mode mode) {
            this.level = level; wildId = wild.getUUID(); petId = pet.getUUID(); this.mode = mode;
            var personality = pet.life().personality(); var rng = pet.getRandom();
            petBold = personality.trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55;
            share = mode == Mode.BEG && personality.selfishness() <= 100 - Temperament.CARE_AT_55 && rng.nextFloat() < .6f;
            var tier = pet.pet().effectiveGrade().id() < BadgeGrade.BRONZE.id() ? BadgeGrade.BRONZE : pet.pet().effectiveGrade();
            win = !share && rng.nextDouble() < pet.pet().successChance(Ability.POISE, tier);
            respondAt = mode == Mode.PLEA ? Integer.MAX_VALUE - 100 : 26;
        }
        private static Vec3 top(YukkuriEntity y) { return y.position().add(0, y.getBbHeight(), 0); }

        @Override public boolean test(int age) {
            var w = level.getEntity(wildId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
            var p = level.getEntity(petId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
            if (w == null || p == null || broken(w) || broken(p)) { end(w, p); return true; }
            var owner = ownerNear(level, p);
            if (age == 1) {
                w.life().deferSocial(160); p.life().deferSocial(160);
                if (mode == Mode.BEG) PetScenes.act(w, "beg", "drool", "wild_role_beg", 60, p);
                else if (mode == Mode.PROVOKE) { PetScenes.act(w, "puff", "angry", "wild_role_provoke", 60, p); Staging.anger(level, top(w)); }
            }
            if (age == 10 && mode != Mode.PLEA) p.getLookControl().setLookAt(w, 30, 30);
            if (mode == Mode.PROVOKE && age == 14) Staging.anger(level, top(w));
            if (mode == Mode.PLEA && pleaAt < 0) {
                // walk up to the owner, then ask to be kept
                if (owner == null) { end(w, p); return true; }
                if (age % 10 == 1) w.getNavigation().moveTo(owner, 1.0);
                if (age >= 10 && w.distanceToSqr(owner) <= 12 || age >= 50) {
                    pleaAt = age; respondAt = age + 22; w.getNavigation().stop();
                    PetScenes.act(w, w.getRandom().nextBoolean() ? "beg" : "shy", "teary", "wild_plea_keep", 60, owner);
                    p.getLookControl().setLookAt(w, 30, 30);
                }
            }
            if (age == respondAt) respond(w, p, owner);
            else if (age == respondAt + 22) followUp(w, p, owner);
            if (age < respondAt + 48) return false;
            end(w, p); return true;
        }
        private void respond(YukkuriEntity w, YukkuriEntity p, Player owner) {
            if (share) {
                // Only a bite that is really in the pet's mouth changes hands (the hand-over writes the relation itself); otherwise it is just a kind look.
                shared = p.life().handOverMouth(w, "badge_share_offer");
                if (shared) Staging.note(level, top(p)); else PetScenes.act(p, "nuzzle", "open_smile", null, 70, w);
            } else if (win) {
                PetScenes.act(p, "ignore", "closed_smile", null, 50, w);
            } else if (petBold) {
                p.pet().addStress(2);
                if (p.pet().visibleGrade() != BadgeGrade.NONE) PetScenes.act(p, "proud", "smug", "badge_proud", 80, w);
                else PetScenes.act(p, "puff", "angry", null, 60, w);
            } else {
                p.pet().addStress(2);
                if (p.level() instanceof ServerLevel && p.isAlive()) p.mind().felt(EmotionEvent.FRIGHTENED); PetScenes.act(p, "tremble", "cry", "afraid", 80, w); Staging.sweat(p);
            }
        }
        private void followUp(YukkuriEntity w, YukkuriEntity p, Player owner) {
            String line;
            if (share) line = shared ? "shared" : null;
            else if (win) {
                if (owner != null) { PetScenes.act(p, "bow", "happy", "pet_report_owner", 60, owner); gain = p.pet().wildPoiseGain(); if (gain) Staging.sparkle(level, top(p)); }
                line = gain ? "ignored_gain" : "ignored";
            } else if (petBold) {
                if (p.pet().visibleGrade() == BadgeGrade.NONE) { PetScenes.act(p, "push", "angry", null, 40, w); Staging.anger(level, top(p)); }
                line = "pushed";
            } else line = "scared";
            if (line != null && owner != null && !held(TOLD, owner.getUUID(), level.getGameTime())) {
                hold(TOLD, owner.getUUID(), level.getGameTime(), 6000);
                owner.displayClientMessage(Component.translatable("pet.mcyukkuri.wild." + line, p.getDisplayName()), true);
            }
        }
        private void end(YukkuriEntity w, YukkuriEntity p) {
            for (var y : new YukkuriEntity[] {w, p}) if (y != null && y.isAlive() && y.isCommandPlaying() && !y.isPanicking()) y.playAction("idle");
        }
    }
}
