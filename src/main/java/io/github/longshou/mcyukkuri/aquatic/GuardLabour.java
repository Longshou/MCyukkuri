package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Her mate waits beside her through her own-hat water birth (项目设计): from the birth's approach lead on he paddles his own hat to
 * a band of open water beside hers — outside both brims, never on the side her newborn comes out of — and waits there facing her
 * until the births end, so a newborn that lands in the water is within a stroke of him. The aim is her moving hull (as the rescue
 * chases a faller), and only the shared paddle and turn move him: nothing here catches, seats or teleports.
 */
final class GuardLabour implements Intent {
    /** Open water kept between the two brims (blocks); how far past that the band reaches; how wide (degrees) the arc of her outlet
     *  stays clear of him; how long one watch pass lasts before her place is read again. */
    private static final double GAP = .8, BAND = 1.5, CLEAR = 60;
    private static final int WATCH = 40;

    private final YukkuriEntity mate, mother;
    private final long started;
    private boolean arrived;

    GuardLabour(YukkuriEntity mate, YukkuriEntity mother) {
        this.mate = mate;
        this.mother = mother;
        started = mate.level().getGameTime();
        var hat = herHat();
        GameplayLog.event(mate, "aquatic", "guard start mate={} mother={} hat={} at={} distance={}", mate.getUUID(), mother.getUUID(),
                hat == null ? null : hat.getUUID(), GameplayLog.at(mate.position()), Math.round(mate.distanceTo(mother) * 10) / 10.0);
    }

    /** The mother whose own-hat water birth this body waits beside, or null: his mate, alive and within family range, with her own
     *  hat afloat and herself aboard it from the birth's own approach lead on, and him aboard his own afloat hat. A mate without one
     *  cannot hold station out there (a swimming body belongs to the escape from water), so it offers nothing. */
    static YukkuriEntity labouringMate(YukkuriEntity body) {
        UUID id = body.family().partner();
        if (id == null || !(body.level() instanceof ServerLevel level) || !(level.getEntity(id) instanceof YukkuriEntity mother)
                || mother == body || !mother.isAlive() || mother.distanceTo(body) > AquaticLife.FAMILY_RANGE) return null;
        var mine = AquaticLife.hat(body);
        if (mine == null || !AquaticLife.afloat(mine) || !AquaticLife.aboard(body, mine)) return null;
        return labour(mother) && mine != herHat(mother) ? mother : null;
    }

    /** Her own-hat water birth is cast and running into her own hat afloat (from its approach lead, past term into the labour) and her
     *  brood is not complete: the window a mate waits beside her for. */
    static boolean labour(YukkuriEntity mother) {
        var brood = mother.family().brood();
        if (brood == null || brood.cancelled || brood.complete()) return false;
        var hat = herHat(mother);
        return hat != null && AquaticLife.afloat(hat) && AquaticLife.aboard(mother, hat);
    }

    private static PlacedHatEntity herHat(YukkuriEntity mother) {
        var cabin = mother.family().birthCabin();
        return cabin == null ? null : cabin.hat();
    }
    private PlacedHatEntity herHat() { return herHat(mother); }

    @Override public String id() { return "guard_labour"; }
    @Override public long deadline() { return started + (long) DriveWeights.of(AquaticGoal.DOMAIN, "guard_ticks"); }
    /** The births are over once her own-hat birth is no longer running (the last child has arrived). */
    @Override public boolean satisfied() { return !labour(mother); }

    @Override public List<Method> methods() { return List.of(Plan.of("paddle_beside", this::derive)); }

    private List<Supplier<Step>> derive() {
        var her = herHat();
        var mine = AquaticLife.hat(mate);
        if (her == null || mine == null || !AquaticLife.afloat(mine) || !AquaticLife.aboard(mate, mine)) return null;
        if (waits(her, mine)) {
            if (!arrived) {
                arrived = true;
                GameplayLog.event(mate, "aquatic", "guard arrive mate={} mother={} hat={} at={} gap={}", mate.getUUID(), mother.getUUID(),
                        her.getUUID(), GameplayLog.at(mine.position()),
                        Math.round((mine.position().distanceTo(her.position()) - brim(her) - brim(mine)) * 10) / 10.0);
            }
            float yaw = SurfaceApproach.yaw(mate.position(), mother.position());
            long until = mate.level().getGameTime() + WATCH;
            return List.of(() -> Math.abs(Mth.wrapDegrees(yaw - mate.yBodyRot)) > Face.FACE_ARRIVED ? new Step.Do(new Face(yaw)) : null,
                    () -> new Step.Wait(() -> mate.level().getGameTime() >= until || !labour(mother) || !waits(her, mine),
                            WATCH + 2, Behaviors.BOAT_DRIFT));
        }
        // Her hull drifts while she waits for term: he paddles at where it is beside her now and stops on the band itself, as the
        // rescue paddles at a faller's place rather than at a fixed aim.
        return List.of(() -> new Step.Do(new Paddle(() -> her.isRemoved() ? null : beside(her, mine), distance -> waits(her, mine))));
    }

    /** Where his hull waits beside hers: the water outside both brims, away from the side her newborn comes out of. */
    private record Band(Vec3 centre, Vec3 outlet, double near, double far) {}
    private Band band(PlacedHatEntity her, PlacedHatEntity mine) {
        var centre = her.position();
        var outlet = LiveBirthGeometry.position(mother, 0);
        var away = new Vec3(outlet.x - centre.x, 0, outlet.z - centre.z);
        double reach = away.horizontalDistance();
        if (reach < 1e-6) return null;
        double near = brim(her) + brim(mine) + GAP;
        return new Band(centre, away.scale(1 / reach), near, near + BAND);
    }
    /** The middle of the band, on the side of her hull he is already on. */
    private Vec3 beside(PlacedHatEntity her, PlacedHatEntity mine) {
        var band = band(her, mine);
        if (band == null) return null;
        var side = new Vec3(-band.outlet().z, 0, band.outlet().x);
        if (side.dot(mine.position().subtract(band.centre())) < 0) side = side.scale(-1);
        return band.centre().add(side.scale((band.near() + band.far()) / 2));
    }
    /** His hull lies in her band now: outside both brims and off her outlet side. */
    private boolean waits(PlacedHatEntity her, PlacedHatEntity mine) {
        var band = band(her, mine);
        if (band == null) return false;
        var to = new Vec3(mine.position().x - band.centre().x, 0, mine.position().z - band.centre().z);
        double distance = to.horizontalDistance();
        if (distance < band.near() || distance > band.far()) return false;
        return distance < 1e-6 || Math.abs(Mth.wrapDegrees(bearing(to) - bearing(band.outlet()))) >= CLEAR;
    }
    private static double brim(PlacedHatEntity hat) { return HullBounds.RADIUS * hat.hullScale() / HullBounds.SCALE; }
    private static float bearing(Vec3 v) { return (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG); }

    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.event(mate, "aquatic", "guard end result={} mate={} mother={} arrived={} ticks={}", result, mate.getUUID(),
                mother.getUUID(), arrived, mate.level().getGameTime() - started);
    }
}
