package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import java.util.UUID;

/**
 * A walk on the lead (docs/FEATURES.md): while the pet is leashed to its own owner, the owner's horizontal travel adds up
 * and the pet's actual travel add up by their smaller distance (teleports, vehicles and elytra flight do not count). Every 64 blocks is one walk unit, at most 4 units a game day take effect: boredom,
 * the need for affection, stress, energy and a little nutrition change. Everything is counted in blocks moved, never in frames.
 * {@link #walking(YukkuriEntity)} is the hook for encounters: a pet on a walk passing wild yukkuri is a natural poise practice.
 * An unowned individual on a player's lead is not walked for its own good; it answers the pull by its nature.
 */
public final class PetWalk {
    /** Blocks per walk unit, units per game day that count, ticks between two "I do not want to go" protests. */
    public static final double UNIT = 64;
    public static final int UNITS_PER_DAY = 4, TUG_INTERVAL = 400;
    private static final double TURN_RANGE_SQR = 16;
    private final YukkuriEntity animal;
    private Vec3 last, lastPet;
    private boolean moving;
    private double carry;
    private double freeCarry;
    private int units, tugWait;
    private Vec3 heading;
    private long turnUntil;
    private boolean waitsAtTurn;
    private Vec3 turnAside;
    private Boolean manners;
    private UUID strangerHolder;

    public PetWalk(YukkuriEntity animal) { this.animal = animal; }

    /** Whether the pet is currently on a lead held by its owner. */
    public static boolean walking(YukkuriEntity pet) { return pet.pet().care().walk().walking(); }
    public boolean walking() { return moving; }
    public boolean onOwnerLead() { return onOwnerLead(animal.getLeashHolder()); }
    public double carry() { return carry; }

    private boolean onOwnerLead(Entity holder) {
        return (holder instanceof Player || holder instanceof HumanNpc) && animal.pet().isPet()
                && animal.pet().ownedBy(holder.getUUID()) && holder.level() == animal.level();
    }

    private boolean manners() {
        if (manners == null) manners = animal.getRandom().nextDouble() < animal.skills().will();
        return manners;
    }

    public boolean ignoresScraps() {
        return onOwnerLead() && animal.life().nutrition() >= 30 && manners();
    }

    /** A turn can briefly take over close following. Vanilla still owns the lead and its elastic range. */
    public boolean followTurn(Entity holder, double speed) {
        if (!onOwnerLead(holder)) return false;
        boolean near = animal.distanceToSqr(holder) < TURN_RANGE_SQR && animal.level().getGameTime() < turnUntil;
        if (waitsAtTurn && near) {
            animal.getNavigation().stop();
            return true;
        }
        if (turnAside == null) return false;
        if (near) {
            var result = SurfaceApproach.walkResult(animal, turnAside, null, speed);
            if (result == SurfaceApproach.Result.MOVING || result == SurfaceApproach.Result.WAITING) return true;
            if (result == SurfaceApproach.Result.ARRIVED)
                GameplayLog.event(animal, "lesson", "lead wandered at={} owner={}", GameplayLog.at(animal.position()), holder.getUUID());
        }
        turnAside = null;
        SurfaceApproach.stopWalking(animal);
        return false;
    }

    void newDay() { units = 0; }

    public void tick() {
        var holder = animal.getLeashHolder();
        // A free individual on someone's lead is no pet walk; it reacts to the pull itself.
        if (holder instanceof Player player && animal.status().identity().free()) {
            strangerLead(player);
            return;
        }
        strangerHolder = null;
        // The owner is a player, or a resident (HumanNpc) walking its own pet: both count the same blocks.
        boolean led = onOwnerLead(holder);
        if (!led) holder = animal.outing().escort();
        if (holder == null) {
            last = null; lastPet = null; heading = null; turnUntil = 0;
            waitsAtTurn = false; turnAside = null; manners = null; moving = false; return;
        }
        var owner = (LivingEntity) holder;
        boolean canTurn = !animal.life().hasUrgentNeed() && !animal.life().isSleeping() && !animal.pet().busy() && !animal.isPanicking()
                && animal.life().mealTicks() == 0 && !animal.life().pursuingFoodOnLead()
                && !animal.isCommandPlaying() && !animal.operations().active() && !owner.isPassenger() && !owner.isFallFlying();
        if (!canTurn || !animal.onGround() || animal.isPassenger()) {
            waitsAtTurn = false;
            turnAside = null;
        }
        if (!animal.onGround() || animal.isPassenger()) { last = null; lastPet = null; moving = false; return; }
        var now = owner.position();
        var petNow = animal.position();
        moving = false;
        if (last != null && lastPet != null) {
            double dx = now.x - last.x, dz = now.z - last.z;
            double px = petNow.x - lastPet.x, pz = petNow.z - lastPet.z;
            double ownerMoved = Math.hypot(dx, dz), petMoved = Math.hypot(px, pz);
            // A strolling resident can take smaller steps than a player and still make a real turn.
            if (led && animal.outing().escort() == null && ownerMoved > .02 && ownerMoved <= 1) {
                Vec3 direction = new Vec3(dx / ownerMoved, 0, dz / ownerMoved);
                long nowTick = animal.level().getGameTime();
                if (canTurn && nowTick >= turnUntil && heading != null && heading.dot(direction) < .35
                        && animal.distanceToSqr(owner) < TURN_RANGE_SQR) {
                    double will = animal.skills().will();
                    waitsAtTurn = animal.getRandom().nextDouble() < will;
                    manners = waitsAtTurn;
                    int duration = ClipMarkers.of("look_around").length();
                    turnUntil = nowTick + duration;
                    turnAside = waitsAtTurn ? null : aside(owner);
                    animal.showReaction(waitsAtTurn ? "neutral" : "teary", waitsAtTurn ? "wait_turn" : "harness_tug", duration);
                    GameplayLog.event(animal, "lesson", "lead turn owner={} waits={} aside={} will={}",
                            owner.getUUID(), waitsAtTurn, turnAside != null, will);
                }
                heading = direction;
            }
            double moved = Math.min(ownerMoved, petMoved);
            if (moved > .001 && ownerMoved <= 1.0 && petMoved <= 1.0 && dx * px + dz * pz > 0
                    && animal.distanceToSqr(owner) <= 36 && !animal.life().hasUrgentNeed() && !animal.pet().busy()
                    && !owner.isPassenger() && !owner.isFallFlying()) {
                moving = true;
                animal.pet().care().requests().walked(moved);
                if (led) {
                    carry += moved;
                    while (carry >= UNIT) { carry -= UNIT; unit(owner); }
                } else {
                    freeCarry += moved;
                    while (freeCarry >= UNIT) { freeCarry -= UNIT; unit(owner); }
                }
            }
        }
        last = now; lastPet = petNow;
        if (tugWait > 0) tugWait--;
        if (led && animal.life().hasUrgentNeed() && tugWait == 0) { // it will not follow while something is urgent: the owner feels the pull of the lead
            tugWait = TUG_INTERVAL;
            animal.showReaction("teary", "harness_tug", 70);
        }
    }

    /**
     * An unowned individual on a player's lead (docs/FEATURES.md): it answers the pull by its own nature. A tame, friendly one walks
     * along quietly; a bold one protests; a timid or fearful one struggles against the pull. The lead is taut when it lags beyond the
     * vanilla close-follow range, which is when the pull asks for an answer; the first answer comes as soon as the lead is on.
     */
    private void strangerLead(Player holder) {
        boolean first = !holder.getUUID().equals(strangerHolder);
        if (first) { strangerHolder = holder.getUUID(); tugWait = 0; }
        if (tugWait > 0) { tugWait--; return; }
        if (!first && animal.distanceToSqr(holder) <= TURN_RANGE_SQR) return;
        tugWait = TUG_INTERVAL;
        var personality = animal.life().personality();
        var traits = personality.temperament();
        int fear = personality.fear(holder.getUUID());
        boolean timid = traits.is(Gate.TIMID), bold = traits.is(Gate.BOLDISH), friendly = personality.friendliness() >= 60;
        String face, line;
        if (!timid && fear < 50 && friendly) { face = "none"; line = "none"; } // a tame stray simply comes along
        else if (!timid && fear < 50 && bold) { face = "cheek_puff"; line = "puff"; }
        else { face = "teary"; line = "harness_tug"; }
        boolean answered = line.equals("none") || (line.equals("puff")
                ? animal.showReaction(YukkuriAction.PUFF, face, line, 70) : animal.showReaction(face, line, 70));
        if (!answered) return;
        GameplayLog.event(animal, "lead", "beat={} face={} line={} fear={} timid={} bold={} friendly={} holder={}",
                line.equals("none") ? "stranger_follow" : line.equals("puff") ? "stranger_protest" : "stranger_pull",
                face, line, fear, timid, bold, friendly, holder.getUUID());
    }

    private Vec3 aside(LivingEntity owner) {
        // Keep going along the old line for two body widths, only on a safe stand inside the short lead.
        var stand = SurfaceApproach.floor(animal, animal.position().add(heading.scale(2 * animal.getBbWidth())));
        return stand != null && owner.distanceToSqr(stand) < TURN_RANGE_SQR ? stand : null;
    }

    private void unit(LivingEntity owner) {
        if (units >= UNITS_PER_DAY) return;
        units++;
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
    double freeCarry() { return freeCarry; }
    public void load(int savedUnits, double savedCarry, double savedFreeCarry) { units = Math.clamp(savedUnits, 0, UNITS_PER_DAY); carry = Math.clamp(savedCarry, 0, UNIT); freeCarry = savedFreeCarry; last = null; lastPet = null; moving = false; }
}
