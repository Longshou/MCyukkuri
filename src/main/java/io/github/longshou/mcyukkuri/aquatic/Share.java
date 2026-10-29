package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Ask;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * A young sent over to its father's boat (refactor WP6b; design 7 帽船分娩: 产后把孩子送到父船，是意图，手段依次为等两船靠近、请父油划近、
 * 叼着越船; AQUATIC 家庭、照护: 母亲嫌自己一侧明显更重时): the mother's {@code share_young} for a young of hers that cannot hop. Her ways,
 * in order: wait a while for the boats to drift together; ask him to paddle over (an ask of an incident she owns: he decides
 * once, a refusal is his); with the hulls side by side, take it in her mouth or braid, walk to her side nearest his hat, turn the
 * hold over his hull and let it go, so it comes down on his hat. A hold that does not reach over his hull puts the young back in
 * her own hollow; two such refusals keep it with her ({@code [aquatic] share refused}). Done once it rides his hat.
 */
final class Share implements Intent {
    /** The whole sending, the wait for drift, the wait for his answer and paddling (ticks). */
    private static final int DEADLINE = 1600, DRIFT = 200, ASK = 500;
    /** How far past the two brims the hulls may lie to count as side by side (blocks): what a hold reaches across. */
    private static final double ACROSS = .35;

    private final YukkuriEntity mother, father, child;
    private final long started;
    private String incident;
    private boolean putBack;
    private int refusals;

    Share(YukkuriEntity mother, YukkuriEntity father, YukkuriEntity child) {
        this.mother = mother;
        this.father = father;
        this.child = child;
        this.started = mother.level().getGameTime();
    }

    @Override public String id() { return "share_young"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public boolean satisfied() { var his = AquaticLife.hat(father); return his != null && child.getVehicle() == his; }

    @Override public List<Method> methods() {
        Set<Reason> reduces = Set.of(Reason.NO_PROGRESS, Reason.TOO_FAR);
        return List.of(new Plan("wait_close", () -> apart("boats_close"), reduces, () -> waitFor(DRIFT)),
                new Plan("ask_father", () -> apart("boats_close"), reduces, this::ask),
                new Plan("carry_across", this::near, reduces, this::carry),
                new Plan("carry_across_again", this::near, reduces, this::carry));
    }

    /** Both hulls afloat upright, their brims (each toward the other) within a hold's reach of each other. */
    static boolean close(PlacedHatEntity a, PlacedHatEntity b) {
        if (!AquaticLife.afloat(a) || !AquaticLife.afloat(b) || a == b) return false;
        return a.position().subtract(b.position()).horizontalDistance() <= touching(a, b) + ACROSS;
    }

    /** The middles' distance at which the two brims meet along the line between them. */
    static double touching(PlacedHatEntity a, PlacedHatEntity b) { return HullAfloat.brimToward(a, b.position()) + HullAfloat.brimToward(b, a.position()); }

    private boolean close() { return close(AquaticLife.hat(mother), AquaticLife.hat(father)); }

    private Feasibility apart(String why) { return close() ? Feasibility.of(Reason.NO_PROGRESS, 0, why) : Feasibility.pass(); }

    private Feasibility near() { return close() ? Feasibility.pass() : Feasibility.of(Reason.TOO_FAR, 0, "boats_apart"); }

    private List<Supplier<Step>> waitFor(int ticks) {
        return close() ? carry() : List.of(() -> new Step.Wait(this::close, ticks, Behaviors.BOAT_CALL));
    }

    /** Ask him once (an incident of hers, one offered ask); wait for him, unless he refused or his run is over. */
    private List<Supplier<Step>> ask() {
        if (close()) return carry();
        var ledger = AskLedger.server();
        if (incident == null) {
            incident = ledger.open(mother, "share_young", "helper=" + father.getUUID() + " child=" + child.getUUID());
            var id = incident;
            ledger.offer(new Ask(mother.getUUID(), father.getUUID(), f -> new Alongside(f, mother, id), id));
            return List.of(() -> new Step.Wait(() -> close() || answered(), ASK, Behaviors.BOAT_CALL));
        }
        return null;
    }

    /** He refused, or his run is over without bringing his hat over. */
    private boolean answered() {
        var d = AskLedger.server().decision(incident, father.getUUID());
        return d != null && (!d.accepted() || !AskLedger.server().helping(incident, father.getUUID()));
    }

    private List<Supplier<Step>> carry() {
        var hat = AquaticLife.riding(mother);
        var his = AquaticLife.hat(father);
        if (hat == null || his == null || child.getVehicle() == his || !child.isAlive()) return null;
        if (child.getVehicle() != mother) {
            if (putBack) {
                putBack = false;
                GameplayLog.event(mother, "aquatic", "share refused child={} reason=hold_not_over_his_hull count={}", child.getUUID(), ++refusals);
                return null;
            }
            return child.getVehicle() == hat ? Ferry.grabAboard(mother, hat, child) : null;
        }
        // held: to her side nearest his hat, the hold turned over his hull, and let go there (or back into her own hollow)
        var spot = AquaticLife.spot(hat, mother, false, hat.frame().toLocal(his.position()));
        boolean braid = ParentCarry.hold(mother, child).reach() == ContactGeometry.Reach.BRAID;
        return List.of(() -> spot == null || spot.subtract(hat.frame().toLocal(mother.position())).horizontalDistance() < .1 ? null : new Step.Do(new Walk(hat, spot)),
                () -> new Step.Do(new Face(braid ? ContactGeometry.braidYaw(mother, his) : SurfaceApproach.yaw(mother.position(), his.position()))),
                () -> his.steady() && hat.steady() ? null : new Step.Wait(() -> his.steady() && hat.steady(), 60, "hat_settles"),
                () -> { if (over(his)) return new Step.Do(new Release(child)); putBack = true; return null; },
                () -> putBack ? null : Ferry.landed(child).get(),
                // not over his hull: back into her own hollow, the hold turned to its middle (the next derive counts the refusal)
                () -> putBack ? new Step.Do(new Face(braid ? ContactGeometry.braidYaw(mother, hat) : SurfaceApproach.yaw(mother.position(), hat.position()))) : null,
                () -> putBack ? new Step.Do(new Release(child)) : null,
                () -> putBack ? Ferry.landed(child).get() : null);
    }

    /** The held young hangs over his hull's surface: let go, it comes down on his hat. */
    private boolean over(PlacedHatEntity his) {
        return his.support(his.frame().toLocal(child.position()), Occupancy.foot(child.getBbWidth())).isPresent();
    }

    @Override public void ended(IntentRunner.Result result) {
        if (child.getVehicle() == mother && result != IntentRunner.Result.DONE) Release.now(mother, child, true, "share_ended");
        if (incident != null) AskLedger.server().settle(incident, result.name().toLowerCase(Locale.ROOT));
        GameplayLog.event(mother, "aquatic", "share_young end result={} child={} father={} refusals={}", result, child.getUUID(), father.getUUID(), refusals);
    }

    /**
     * Paddling his hat up beside another (refactor WP6b): {@code come_alongside} when the mother asked it and he accepted, or
     * {@code attend_mate} on his own, his mate near her time on her boat (AQUATIC 有伴侣时，对方可自行划到船边陪护). Route legs over the
     * calm water to the point beside her hull, then in against it; with no stick he only drifts and waits. Done once side by side.
     */
    static final class Alongside implements Intent {
        private static final int DEADLINE = 900;
        /** Within this (blocks) of the point where the brims meet the paddling stops: inside Share's side-by-side reach. */
        private static final double IN = .3;
        private final YukkuriEntity father, mother;
        private final String incident;
        private final long started;

        Alongside(YukkuriEntity father, YukkuriEntity mother, String incident) {
            this.father = father;
            this.mother = mother;
            this.incident = incident;
            this.started = father.level().getGameTime();
        }

        @Override public String id() { return incident == null ? "attend_mate" : "come_alongside"; }
        @Override public long deadline() { return started + DEADLINE; }
        @Override public boolean satisfied() { return close(AquaticLife.hat(father), AquaticLife.hat(mother)); }
        @Override public List<Method> methods() { return List.of(Plan.of("paddle_alongside", this::derive)); }

        private List<Supplier<Step>> derive() {
            var mine = AquaticLife.riding(father);
            var hers = AquaticLife.hat(mother);
            if (mine == null || !AquaticLife.afloat(hers)) return null;
            if (!AquaticLife.stick(father)) return List.of(() -> new Step.Wait(this::satisfied, 200, Behaviors.BOAT_DRIFT));
            var away = mine.position().subtract(hers.position()).multiply(1, 0, 1);
            var dir = away.lengthSqr() < 1E-6 ? new Vec3(1, 0, 0) : away.normalize();
            var beside = hers.position().add(dir.scale(touching(mine, hers)));
            var water = Waters.cell(father.level(), beside);
            var route = water == null ? List.<Vec3>of() : Waters.route(mine, water);
            Waters.log(mine, father, water, route, id());
            if (route.isEmpty()) return null;
            if (route.size() > 1) return List.of(() -> new Step.Do(new Paddle(Waters.leg(route), .6)));
            return List.of(() -> new Step.Do(new Paddle(beside, IN)));
        }

        @Override public void ended(IntentRunner.Result result) {
            if (incident != null && result != IntentRunner.Result.INTERRUPTED) AskLedger.server().ended(incident, father.getUUID(), result.name());
            GameplayLog.event(father, "aquatic", "{} end result={} mate={}", id(), result, mother.getUUID());
        }
    }
}
