package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;

/**
 * A walk on the harness (docs/FEATURES.md): while the pet is leashed to its own owner, the owner's horizontal travel adds up
 * and the pet's actual travel add up by their smaller distance (teleports, vehicles and elytra flight do not count). Every 64 blocks is one walk unit, at most 4 units a game day take effect: boredom,
 * the need for affection, stress, energy and a little nutrition change. Everything is counted in blocks moved, never in frames.
 * {@link #walking(YukkuriEntity)} is the hook for encounters: a pet on a walk passing wild yukkuri is a natural poise practice.
 */
public final class PetWalk {
    /** Blocks per walk unit, units per game day that count, ticks between two "I do not want to go" protests. */
    public static final double UNIT = 64;
    public static final int UNITS_PER_DAY = 4, TUG_INTERVAL = 400;
    private final YukkuriEntity animal;
    private Vec3 last, lastPet;
    private boolean moving;
    private double carry;
    private int units, tugWait;
    private Vec3 heading;
    private long turnUntil;
    private Boolean manners;

    public PetWalk(YukkuriEntity animal) { this.animal = animal; }

    /** Whether the pet is currently on a lead held by its owner. */
    public static boolean walking(YukkuriEntity pet) { return pet.pet().care().walk().walking(); }
    public boolean walking() { return moving; }
    public double carry() { return carry; }

    private boolean onOwnerLead(Entity holder) {
        return (holder instanceof Player || holder instanceof HumanNpc) && animal.pet().isPet()
                && animal.pet().ownedBy(holder.getUUID()) && holder.level() == animal.level();
    }

    private boolean manners() {
        if (manners == null) manners = animal.pet().lessons().attempt(PetSkill.HARNESS);
        return manners;
    }

    public boolean ignoresScraps() {
        return onOwnerLead(animal.getLeashHolder()) && animal.life().nutrition() >= 30 && manners();
    }

    /** Vanilla still owns the lead and its elastic range. This is the close-range choice to stop or follow. */
    public boolean pausesFollow(Entity holder) {
        return onOwnerLead(holder) && !animal.life().hasUrgentNeed() && animal.level().getGameTime() < turnUntil
                && animal.distanceToSqr(holder) < 16;
    }

    void newDay() { units = 0; }

    public void tick() {
        var holder = animal.getLeashHolder();
        // The owner is a player, or a resident (HumanNpc) walking its own pet: both count the same blocks.
        if (!onOwnerLead(holder)) { last = null; lastPet = null; heading = null; turnUntil = 0; manners = null; moving = false; return; }
        var owner = (LivingEntity) holder;
        if (!animal.onGround() || animal.isPassenger()) { last = null; lastPet = null; moving = false; return; }
        var now = owner.position();
        var petNow = animal.position();
        moving = false;
        if (last != null && lastPet != null) {
            double dx = now.x - last.x, dz = now.z - last.z;
            double px = petNow.x - lastPet.x, pz = petNow.z - lastPet.z;
            double ownerMoved = Math.hypot(dx, dz), petMoved = Math.hypot(px, pz);
            if (ownerMoved > .06 && ownerMoved <= 1) {
                Vec3 direction = new Vec3(dx / ownerMoved, 0, dz / ownerMoved);
                if (heading != null && heading.dot(direction) < .35 && animal.distanceToSqr(owner) < 16
                        && !animal.life().hasUrgentNeed() && !animal.pet().busy()) {
                    boolean waiting = manners();
                    int duration = ClipMarkers.of("look_around").length();
                    if (waiting) turnUntil = animal.level().getGameTime() + duration;
                    animal.showReaction(waiting ? "neutral" : "teary", waiting ? "wait_turn" : "harness_tug", duration);
                    GameplayLog.event(animal, "lesson", "harness turn owner={} waits={}", owner.getUUID(), waiting);
                    animal.pet().lessons().performed(PetSkill.HARNESS, waiting);
                }
                heading = direction;
            }
            double moved = Math.min(ownerMoved, petMoved);
            if (moved > .001 && ownerMoved <= 1.0 && petMoved <= 1.0 && dx * px + dz * pz > 0
                    && animal.distanceToSqr(owner) <= 36 && !animal.life().hasUrgentNeed() && !animal.pet().busy()
                    && !owner.isPassenger() && !owner.isFallFlying()) {
                moving = true;
                carry += moved;
                animal.pet().care().requests().walked(moved);
                while (carry >= UNIT) { carry -= UNIT; unit(owner); }
            }
        }
        last = now; lastPet = petNow;
        if (tugWait > 0) tugWait--;
        if (animal.life().hasUrgentNeed() && tugWait == 0) { // it will not follow while something is urgent: the owner feels the pull of the lead
            tugWait = TUG_INTERVAL;
            animal.showReaction("teary", "harness_tug", 70);
        }
    }

    private void unit(LivingEntity owner) {
        if (units >= UNITS_PER_DAY) return;
        units++;
        animal.pet().lessons().performed(PetSkill.HARNESS, true);
        manners = null;
        var daily = animal.dailyLife(); var pet = animal.pet();
        daily.entertained(6); daily.comfort(4); pet.addStress(-2);
        animal.life().spendEnergy(2); animal.life().spendNutrition(1);
        pet.contact(owner.getUUID(), true);
        if (units == 3) {
            animal.showReaction("happy", "walk_enjoy", 80);
            if (animal.level() instanceof ServerLevel level) Staging.sparkle(level, animal.position().add(0, animal.getBbHeight() + .3, 0));
        }
    }

    public int saveUnits() { return units; }
    public void load(int savedUnits, double savedCarry) { units = Math.clamp(savedUnits, 0, UNITS_PER_DAY); carry = Math.clamp(savedCarry, 0, UNIT); last = null; lastPet = null; moving = false; }
}
