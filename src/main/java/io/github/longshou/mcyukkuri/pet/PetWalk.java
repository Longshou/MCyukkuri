package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
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
    /** Blocks a released body puts between itself and the lead holder, and ticks the leaving may take before it ends. */
    private static final double LEAVE_DISTANCE = 3;
    private static final int LEAVE_TICKS = 120;
    private final YukkuriEntity animal;
    private final LeadFollow follow;
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
    private Vec3 strangerPoint, strangerTarget;
    private boolean strangerAttached, strangerLeashed, strangerAnswered;
    private int strangerLeave;
    private long strangerUntil;

    public PetWalk(YukkuriEntity animal) { this.animal = animal; follow = new LeadFollow(animal); }

    /** The walk request of the vanilla close-range follow: a hop-run while an owner's pet trails (see {@link LeadFollow}). */
    public double followSpeed() { return follow.speed(); }
    /** The snap-on answer to a stray's new lead was shown: its pull-back bouts may begin. */
    private void leashAnswered() { follow.leashAnswered(); }
    /** A stray's pull-back bout takes the place of vanilla's close-range follow; false when none runs. */
    public boolean pullBack() { return follow.pull(); }

    /** Whether the pet is currently on a lead held by its owner. */
    public static boolean walking(YukkuriEntity pet) { return pet.pet().care().walk().walking(); }
    public boolean walking() { return moving; }
    public boolean onOwnerLead() { return onOwnerLead(animal.getLeashHolder()); }
    /** On a person's lead, owner's or not: the lead is its walk, so no stroll of its own is chosen (a fence knot only tethers it). */
    public boolean towed() { return animal.getLeashHolder() instanceof LivingEntity; }
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
        follow.tick(holder instanceof Player player ? player : null);
        // A free individual on someone's lead is no pet walk; it answers the lead itself, beat by beat.
        if (holder instanceof Player player && animal.status().identity().free()) {
            strangerLead(player);
            return;
        }
        if (strangerAttached) strangerReleased(holder);
        strangerLeaveTick();
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
                    animal.showReaction(waitsAtTurn ? "neutral" : "teary", waitsAtTurn ? "wait_turn" : "harness_tug", FaceTier.Tier.ofTicks(duration));
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
            animal.showReaction("teary", "harness_tug", FaceTier.Tier.MEDIUM);
        }
    }

    /**
     * An unowned individual on a player's lead (docs/features/care-and-life.md): the lead snaps on (a timid or fearful body startles,
     * a bold one protests, a friendly one is merely surprised), then it strains against the lead in bouts until its energy runs out
     * ({@link LeadFollow}), and when the lead comes off the fearful bolts and looks back, the bold huffs off and the friendly lingers.
     * Every beat ends: the leaving walk ends on arrival, a blocked step or {@link #LEAVE_TICKS}.
     */
    private void strangerLead(Player holder) {
        if (!strangerAttached || !holder.getUUID().equals(strangerHolder)) {
            if (strangerLeave != 0) SurfaceApproach.stopWalking(animal); // a new lead ends an unfinished leave
            strangerHolder = holder.getUUID();
            strangerAttached = true; strangerLeashed = false; strangerAnswered = false;
            strangerLeave = 0; strangerTarget = null; tugWait = 0;
        }
        strangerPoint = holder.position();
        if (!strangerLeashed) {
            // A refused answer (a held reaction) is not burned: the snap-on answer is asked again next tick.
            if (answer(holder)) { strangerLeashed = true; leashAnswered(); }
            return;
        }
    }

    /** The snap-on answer to the lead; false when a held reaction refused it, so the caller asks again next tick. */
    private boolean answer(Player holder) {
        var personality = animal.life().personality();
        var traits = personality.temperament();
        int fear = personality.fear(holder.getUUID());
        boolean scared = traits.is(Gate.TIMID) || fear >= 50;
        boolean friendly = personality.friendliness() >= 60;
        boolean bold = !friendly && traits.is(Gate.BOLDISH);
        YukkuriAction action;
        String face, line = "lead_stranger_on";
        if (scared) { action = YukkuriAction.STARTLE; face = "horrified"; }
        else if (bold) { action = YukkuriAction.PUFF; face = "fierce"; }
        else { action = YukkuriAction.STARTLE; face = "surprise"; }
        if (!animal.showReaction(action, face, line, Math.max(action.durationTicks(), 40), FaceTier.Tier.ofTicks(Math.max(action.durationTicks(), 40)))) return false;
        animal.beat("stranger_leashed", face, line);
        GameplayLog.event(animal, "lead", "beat=stranger_leashed face={} line={} fear={} timid={} bold={} friendly={} holder={}",
                face, line, fear, traits.is(Gate.TIMID), traits.is(Gate.BOLDISH), friendly, holder.getUUID());
        return true;
    }

    /** The lead came off (right-click, a broken lead or a pick-up): the last beat, then the body leaves or lingers. */
    private void strangerReleased(Entity holder) {
        strangerAttached = false;
        tugWait = 0;
        if (holder instanceof Player) { strangerLeave = 0; strangerHolder = null; return; } // ownership took the lead over
        var personality = animal.life().personality();
        var traits = personality.temperament();
        int fear = strangerHolder == null ? 0 : personality.fear(strangerHolder);
        boolean scared = traits.is(Gate.TIMID) || fear >= 50;
        boolean friendly = personality.friendliness() >= 60;
        boolean bold = !friendly && traits.is(Gate.BOLDISH);
        strangerLeave = scared ? 1 : bold ? 2 : 3;
        strangerAnswered = false;
        strangerTarget = strangerLeave == 3 ? null : awayStand();
        strangerUntil = animal.level().getGameTime() + LEAVE_TICKS;
    }

    /** A supported stand about {@link #LEAVE_DISTANCE} blocks from where the holder last stood, the way away first. */
    private Vec3 awayStand() {
        if (strangerPoint == null) return null;
        var away = animal.position().subtract(strangerPoint).multiply(1, 0, 1);
        if (away.lengthSqr() < .01) away = new Vec3(1, 0, 0);
        for (double angle : new double[]{0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2,
                3 * Math.PI / 4, -3 * Math.PI / 4, Math.PI}) {
            var direction = new Vec3(away.x * Math.cos(angle) - away.z * Math.sin(angle), 0,
                    away.x * Math.sin(angle) + away.z * Math.cos(angle)).normalize();
            var stand = SurfaceApproach.floor(animal, animal.position().add(direction.scale(LEAVE_DISTANCE)));
            if (stand != null) return stand;
        }
        return null;
    }

    /** The last beat in progress: its answer, then a few blocks of fleeing or walking off, or just lingering. */
    private void strangerLeaveTick() {
        if (strangerLeave == 0) return;
        long now = animal.level().getGameTime();
        if (!strangerAnswered) {
            if (strangerAnswer()) { strangerAnswered = true; return; }
            if (now >= strangerUntil) { strangerLeave = 0; strangerHolder = null; }
            return;
        }
        if (strangerLeave == 3) { strangerLeave = 0; strangerHolder = null; return; } // it lingers where it is
        boolean walking = strangerTarget != null && animal.onGround() && !animal.isPassenger() && now < strangerUntil
                && SurfaceApproach.walkResult(animal, strangerTarget, null, 1) != SurfaceApproach.Result.ARRIVED;
        if (walking) return;
        SurfaceApproach.stopWalking(animal);
        boolean look = strangerLeave == 1;
        strangerLeave = 0;
        strangerHolder = null;
        if (look) lookBack();
    }

    private boolean strangerAnswer() {
        YukkuriAction action;
        String face, line = "lead_stranger_off";
        if (strangerLeave == 1) { action = YukkuriAction.FLEE; face = "horrified"; }
        else if (strangerLeave == 2) { action = YukkuriAction.MOOD_FLICK; face = "hmph"; }
        else { action = YukkuriAction.LOOK_AROUND; face = "teary_smile"; }
        int said = animal.speechSequence();
        follow.clearOwnLine();
        if (!animal.showReaction(action, face, line, Math.max(action.durationTicks(), 40), FaceTier.Tier.MEDIUM)) return false;
        follow.noteOwnLine(said);
        animal.beat("stranger_released", face, line);
        GameplayLog.event(animal, "lead", "beat=stranger_released face={} line={} leave={}", face, line, strangerLeave);
        return true;
    }

    /** The frightened one stops a few blocks away and looks back at where the holder last stood. */
    private void lookBack() {
        if (strangerPoint != null)
            animal.getLookControl().setLookAt(strangerPoint.x, strangerPoint.y + animal.getEyeHeight(), strangerPoint.z, 30, 30);
        if (!animal.showReaction(YukkuriAction.LOOK_AROUND, "teary", "lead_stranger_look", YukkuriAction.LOOK_AROUND.durationTicks(), FaceTier.Tier.ofTicks(YukkuriAction.LOOK_AROUND.durationTicks()))) return;
        animal.beat("stranger_release_look", "teary", "lead_stranger_look");
        GameplayLog.event(animal, "lead", "beat=stranger_release_look face={} line={}", "teary", "lead_stranger_look");
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
            animal.showReaction("happy", "walk_enjoy", FaceTier.Tier.MEDIUM);
            if (animal.level() instanceof ServerLevel level) Staging.sparkle(level, animal.position().add(0, animal.getBbHeight() + .3, 0));
        }
    }

    public int saveUnits() { return units; }
    double freeCarry() { return freeCarry; }
    public void load(int savedUnits, double savedCarry, double savedFreeCarry) { units = Math.clamp(savedUnits, 0, UNITS_PER_DAY); carry = Math.clamp(savedCarry, 0, UNIT); freeCarry = savedFreeCarry; last = null; lastPet = null; moving = false; }
}
