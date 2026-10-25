package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import io.github.longshou.mcyukkuri.subspecies.crew.WaterIncidents;
import io.github.longshou.mcyukkuri.subspecies.boat.AfloatActivity;
import io.github.longshou.mcyukkuri.subspecies.boat.AfloatAgenda;
import io.github.longshou.mcyukkuri.subspecies.boat.AfloatAgenda.Context;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.subspecies.boat.ShoreBerth;
import io.github.longshou.mcyukkuri.subspecies.boat.VoyageController.Answer;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Everything a rider of an afloat hull can be doing, as {@link AfloatActivity} entries of one {@link AfloatAgenda}. The branches of
 * the old chain keep their conditions and their relative order; what changed is that each one is a named activity with a time limit,
 * that a more urgent one displaces a running one with an explicit stop, and that nothing is left to a matrix of "yield" calls.
 *
 * <pre>
 *  1 incident   unsteady hull, being carried, carrying a child to safety
 *  2 threat     a danger near the hull (offshore and taunt, or cover the young), a parent's call answered
 *  3 labor      giving birth
 *  4 physiology eating, drinking, urgent food, cold and heat
 *  5 care       the family, the crowd, fear, the toilet, the young's meals and sleep
 *  6 weather    rain: cover the young; a soaked crew asks for the shore
 *  7 provision  paddle, own meals, foraging at the shore, floating food
 *  9 social     courtship
 * 10 rest       the night berth (wedged against the bank, no rope), sleep, pushing off at dawn
 * 11 idle       family paddling, daily life, drifting and paddling about
 * </pre>
 */
final class AfloatActivities {
    private final YukkuriEntity entity;
    private final AquaticLife life;
    // Shared observations only.
    private ItemEntity floatingSource;
    private long nextFoodSearch, nextCourtshipSearch;
    private int gatherTicks;
    /** Set when the hull was wedged at night: the push-off at dawn clears it. */
    private Vec3 wedgedAt;
    private Vec3 wedgeAway;
    private long wedgeUntil;

    private AfloatActivities(YukkuriEntity entity, AquaticLife life) { this.entity = entity; this.life = life; }

    static AfloatActivities register(AfloatAgenda agenda, YukkuriEntity entity, AquaticLife life) {
        var all = new AfloatActivities(entity, life);
        for (var activity : all.build()) agenda.register(activity);
        return all;
    }

    /** Stop the floating-food chase and the like when the voyage ends. */
    void clear() { floatingSource = null; nextFoodSearch = 0; gatherTicks = 0; }

    // ================================================================= base
    private abstract class Base implements AfloatActivity {
        private final String id; private final double priority; private final int maxTicks;
        Base(String id, double priority, int maxTicks) { this.id = id; this.priority = priority; this.maxTicks = maxTicks; }
        @Override public String id() { return id; }
        @Override public double priority() { return priority; }
        @Override public int maxTicks() { return maxTicks; }
        @Override public boolean wants(Context c) { return afloatOwner(c); }
    }

    private static boolean afloatOwner(Context c) { return c.owner() && c.boat().isAfloat(); }
    private ServerLevel level() { return (ServerLevel) entity.level(); }
    private AquaticFamilyLife family() { return life.familyLife(); }
    private HatFamilyCare care() { return life.care(); }

    private java.util.List<AfloatActivity> build() {
        return java.util.List.of(
                new Unsteady(), new Carried(), new RescueCarrier(), new Threat(), new AlarmAnswer(), new Labor(),
                new Eating(), new Drink(), new UrgentFood(), new Climate(), new ThermalRefuge(),
                new Passenger(), new FamilyRoute(), new CrowdGuard(), new FamilyReact(), new Distress(), new WaterPartner(),
                new Collect(), new PassengerDistress(), new Toilet(), new CareSession(), new Weather(),
                new HungryCrewFood(), new NonAquatic(), new Courtship(), new NoPaddle(), new PaddleHandoff(), new SelfFeed(),
                new ShoreForage(), new Gather(), new NightRest(), new PushOff(), new Sleep(),
                new FloatingFood(), new FamilyPaddle(), new Daily(), new Idle());
    }

    // ================================================================= 1 incident
    private final class Unsteady extends Base {
        Unsteady() { super("unsteady", 1.0, 1800); }
        @Override public boolean wants(Context c) { return c.boat().unsteadyMotion(); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            entity.childhood().stopHatPractice();
            entity.life().wakeInHat();
            if (c.owner()) boat.helm().drift(entity, Purpose.ROUTINE);
            else boat.helm().drift(entity, Purpose.ROUTINE);
            c.show(boat.capsizing() ? Behaviors.BOAT_CAPSIZING : c.owner() ? Behaviors.BOAT_STEADY : Behaviors.BOAT_ROCKED);
            return true;
        }
    }

    private final class Carried extends Base {
        Carried() { super("carried", 1.1, 900); }
        @Override public boolean wants(Context c) { return c.boat().transportingInHat(entity); }
        @Override public boolean tick(Context c) {
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            c.show(Behaviors.BOAT_CARRY_YOUNG);
            return true;
        }
    }

    private final class RescueCarrier extends Base {
        RescueCarrier() { super("rescue_carrier", 1.5, 900); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && entity.family().crew().asCarrier(); }
        @Override public boolean tick(Context c) {
            c.boat().helm().hold(entity, Purpose.RESCUE);
            c.show(Behaviors.BOAT_RESCUE_YOUNG);
            return true;
        }
        @Override public void stop(Context c, String reason) { care().cancel(); }
    }

    // ================================================================= 2 threat
    private enum Locus { SHORE, AIR, WATER }

    private Locus locusOf(HatBoatEntity boat) {
        var id = family().alarm().sourceId();
        Entity source = id == null ? null : level().getEntity(id);
        if (source == null) return Locus.WATER;
        if (source instanceof YukkuriEntity y && EcologyRoutines.predator(y)
                || !source.onGround() && !source.isInWaterOrBubble() && source.getY() - boat.getY() > 1.5) return Locus.AIR;
        if (source.isInWaterOrBubble() || source instanceof HatBoatEntity) return Locus.WATER;
        return Locus.SHORE;
    }

    private final class Threat extends Base {
        private boolean taunted;
        Threat() { super("threat", 2.0, 700); }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && family().alarm().active() && !life.firstVoyage().active()
                    && !entity.isNoAi() && !entity.isCommandPlaying() && !entity.operations().active();
        }
        @Override public void start(Context c) { taunted = false; }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            var locus = locusOf(boat);
            entity.life().wakeInHat();
            if (locus == Locus.AIR && !boat.unsteadyMotion()) {
                // Nothing outruns a flier: the young are pressed into the cavity and covered.
                boat.helm().hold(entity, Purpose.SAFETY);
                c.show(Behaviors.BOAT_COVER_YOUNG);
                if (c.age() % 100 == 1) entity.speak("afraid");
                return true;
            }
            if (locus == Locus.SHORE && !taunted) {
                // Off the bank the danger is across the water: a puffed-up jeer from the safe side.
                taunted = true;
                entity.showReaction("angry", "wild_role_provoke", 60);
            }
            if (!family().tickThreat(boat)) return false;
            c.show(family().alarmActivity());
            return true;
        }
        @Override public void stop(Context c, String reason) { taunted = false; }
    }

    private final class AlarmAnswer extends Base {
        AlarmAnswer() { super("alarm_answer", 2.1, 700); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && family().alarm().responding() && !life.firstVoyage().returningToParent(); }
        @Override public boolean tick(Context c) {
            if (!family().alarm().tickResponse()) return false;
            c.show(family().alarmActivity());
            return true;
        }
    }

    // ================================================================= 3 labor
    private final class Labor extends Base {
        Labor() { super("labor", 3.0, 6000); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && entity.family().laboring(); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            // In labour she cannot leave the delivery (a missed catch is a drowning child); only in the rest between two births
            // may she lift a newborn that is floating at her rim, and the next push waits for it.
            boat.helm().drift(entity, Purpose.CARE);
            if (entity.family().deliveryGap() && WaterIncidents.liftNearby(entity, boat, 3)) c.show(Behaviors.BOAT_RESCUE_YOUNG);
            return true;
        }
    }

    // ================================================================= 4 physiology
    private final class Eating extends Base {
        Eating() { super("eating", 4.0, 600); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && entity.life().mealTicks() > 0; }
        @Override public boolean tick(Context c) {
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            c.show(Behaviors.BOAT_EAT);
            return true;
        }
    }

    private final class Drink extends Base {
        Drink() { super("drink", 4.1, 400); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && entity.life().dryness() >= 40 && !care().active(); }
        @Override public boolean tick(Context c) {
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            entity.life().wakeInHat();
            c.show(Behaviors.BOAT_DRINK);
            if (c.tick() % 40 == 0) entity.life().drink(30);
            return true;
        }
    }

    private final class UrgentFood extends Base {
        UrgentFood() { super("urgent_food", 4.2, 400); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && entity.life().nutrition() < 25 && c.boat().cargo().count() > 0; }
        @Override public boolean tick(Context c) {
            entity.life().wakeInHat();
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            if (!c.boat().cargo().feed(entity)) return false;
            c.show(Behaviors.BOAT_EAT);
            return true;
        }
    }

    private final class Climate extends Base {
        Climate() { super("climate", 4.5, 1200); }
        @Override public boolean wants(Context c) {
            var climate = entity.life().temperature();
            return afloatOwner(c) && !life.voyage().shoreVisit() && (climate.active() || climate.unsafeSleep());
        }
        @Override public boolean tick(Context c) {
            var climate = entity.life().temperature();
            if (!climate.active() && !climate.plan()) return false;
            entity.life().wakeInHat();
            if (!climate.tickGoal()) return false;
            c.show(climate.activity());
            return true;
        }
    }

    /** Asks for the shore when the captain or a child is too cold or too hot to stay; never takes the turn itself. */
    private final class ThermalRefuge extends Base {
        ThermalRefuge() { super("thermal_refuge", 4.6, 40); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && c.tick() % 20 == 11 && !life.voyage().shoreVisit(); }
        @Override public boolean tick(Context c) {
            var climate = entity.life().temperature();
            boolean refuge = climate.needsShoreRefuge() || !climate.unsafeSleep() && c.boat().youngPassengers().stream()
                    .anyMatch(y -> y instanceof YukkuriEntity young && young.life().temperature().unsafeSleep());
            return refuge && life.requestShoreWork(ShoreWork.THERMAL) && life.voyage().shoreVisit();
        }
    }

    // ================================================================= 5 care: passenger and family
    private final class Passenger extends Base {
        Passenger() { super("passenger", 5.05, Integer.MAX_VALUE); }
        @Override public int cooldownAfterTimeout() { return 0; }
        @Override public boolean wants(Context c) { return !c.owner() && c.boat().protects(entity); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            var captain = boat.getControllingPassenger() instanceof YukkuriEntity y ? y : null;
            String response = captain == null ? Behaviors.IDLE : captain.aquaticLife().care().response(entity);
            if (response.equals(Behaviors.IDLE) && captain != null) response = captain.aquaticLife().familyPaddle().response(entity);
            if (!response.equals(Behaviors.IDLE)) {
                entity.childhood().stopHatPractice();
                // Rear care owns the child's facing until contact completes. Turning a stimulated newborn back toward its parent
                // here undoes the parent's opposite turn every tick.
                if (!response.equals(Behaviors.FIRST_TOILET_WAIT) && !response.equals(Behaviors.FIRST_TOILET_STIMULATED)
                        && !response.equals(Behaviors.BOAT_TOILET) && !response.equals(Behaviors.HAT_WALK) && !response.equals(Behaviors.BOAT_EAT)) {
                    var toward = captain.position().subtract(entity.position());
                    SurfaceApproach.face(entity, (float) (Math.toDegrees(Math.atan2(toward.z, toward.x)) - 90));
                }
                c.show(response);
                return true;
            }
            // Normally the parent serves the young. An unavailable parent never blocks emergency self-feeding.
            if (entity.life().nutrition() < 25 || captain == null || captain.isNoAi() || captain.isPanicking()) boat.cargo().feed(entity);
            if (entity.life().dryness() >= 40 && c.tick() % 40 == 0) entity.life().drink(30);
            if (entity.life().toiletInHat(boat)) {
                entity.childhood().stopHatPractice();
                c.show(boat.walkingInHat(entity) ? Behaviors.HAT_WALK : Behaviors.BOAT_TOILET);
                return true;
            }
            if (family().alarm().tickOnboard(boat)) { entity.childhood().stopHatPractice(); c.show(family().alarmActivity()); return true; }
            if (family().reactInHat(boat)) { entity.childhood().stopHatPractice(); c.show(family().activity()); return true; }
            if (captain == null || captain.aquaticLife().resting() || entity.life().isSleeping()
                    || entity.life().wantsHatNap() || entity.life().energy() <= (entity.family().needsMash() ? 65 : 35)) entity.life().restInHat(boat);
            else entity.life().wakeInHat();
            if (family().tickPassenger(boat)) { c.show(family().activity()); return true; }
            if (life.daily().tick(boat)) { entity.childhood().stopHatPractice(); c.show(life.daily().activity()); return true; }
            if (entity.childhood().practiseInHat(boat)) { c.show(entity.childhood().activity()); return true; }
            c.show(boat.walkingInHat(entity) ? Behaviors.HAT_WALK : entity.life().isSleeping() ? Behaviors.BOAT_SLEEP
                    : boat.onBrim(entity) ? Behaviors.HAT_BRIM : Behaviors.HAT_CRADLE);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().yieldToOtherActivity(); }
    }

    private final class FamilyRoute extends Base {
        FamilyRoute() { super("family", 5.0, 2400); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !life.firstVoyage().active(); }
        @Override public void adopted(Context c) { care().cancel(); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean tick(Context c) {
            if (!family().tickOwner(c.boat())) return false;
            c.show(family().activity());
            return true;
        }
        @Override public void stop(Context c, String reason) {
            life.daily().yieldToOtherActivity(); life.familyPaddle().yieldToOtherActivity(); life.shoreForage().stop();
        }
        @Override public long progress(Context c) { return c.boat().paddling() ? Math.round(c.boat().getX() * 4) * 31 + Math.round(c.boat().getZ() * 4) : Long.MIN_VALUE; }
    }

    private final class CrowdGuard extends Base {
        CrowdGuard() { super("crowd_guard", 5.1, 1200); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && c.boat().overcrowded(); }
        @Override public boolean tick(Context c) {
            c.boat().helm().hold(entity, Purpose.SAFETY);
            c.show(Behaviors.BOAT_CROWD_GUARD);
            if (care().tend(c.boat(), false)) c.show(care().activity());
            return true;
        }
        @Override public void stop(Context c, String reason) { care().cancel(); }
    }

    private final class FamilyReact extends Base {
        FamilyReact() { super("family_react", 5.2, 600); }
        @Override public boolean tick(Context c) {
            if (!family().reactInHat(c.boat())) return false;
            c.show(family().activity());
            return true;
        }
    }

    private final class Distress extends Base {
        Distress() { super("distress", 5.3, 1200); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && (entity.isPanicking() || c.boat().flooding() > 0); }
        @Override public boolean tick(Context c) {
            c.boat().helm().drift(entity, Purpose.SAFETY);
            c.show(Behaviors.BOAT_STEADY);
            if (c.age() % 100 == 1) entity.speak("afraid");
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); care().cancel(); }
    }

    private final class WaterPartner extends Base {
        WaterPartner() { super("water_partner", 5.4, 1800); }
        @Override public boolean tick(Context c) {
            // Rendezvous follows rescue and distress, and keeps its route only while both real parents and hulls remain available.
            if (!entity.family().tickWaterPartner()) return false;
            c.show(c.boat().paddling() ? Behaviors.BOAT_PADDLE : Behaviors.BOAT_WAIT_YOUNG);
            return true;
        }
    }

    /** Picks up what floats against the hull; never takes the turn. */
    private final class Collect extends Base {
        Collect() { super("collect", 5.5, 40); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && c.tick() % 20 == 1; }
        @Override public boolean tick(Context c) { collect(c.boat()); return false; }
    }

    private final class PassengerDistress extends Base {
        PassengerDistress() { super("passenger_distress", 5.6, 900); }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && c.boat().getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y != entity && y.isPanicking());
        }
        @Override public boolean tick(Context c) {
            c.boat().helm().hold(entity, Purpose.SAFETY);
            c.show(Behaviors.BOAT_STEADY);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); care().cancel(); }
    }

    private final class Toilet extends Base {
        Toilet() { super("toilet", 5.7, 600); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !care().active(); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            if (!entity.life().toiletInHat(boat)) return false;
            boat.helm().hold(entity, Purpose.CARE);
            c.show(boat.walkingInHat(entity) ? Behaviors.HAT_WALK : Behaviors.BOAT_TOILET);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); life.shoreForage().stop(); }
    }

    private final class CareSession extends Base {
        CareSession() { super("care", 5.9, 900); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && (c.boat().cargo().paddle() || care().active()); }
        @Override public boolean tick(Context c) {
            if (!care().tend(c.boat(), level().isNight() && quiet(c.boat()))) return false;
            c.show(care().active() ? care().activity() : Behaviors.BOAT_DRIFT);
            return true;
        }
        @Override public void adopted(Context c) { life.daily().stop(); life.familyPaddle().yieldToOtherActivity(); life.shoreForage().stop(); }
        @Override public void stop(Context c, String reason) { care().cancel(); }
        @Override public long progress(Context c) { return care().meals() * 1000L + care().comforts() * 100L + care().songs() * 10L + care().contactProgress(); }
    }

    private boolean quiet(HatBoatEntity boat) {
        return HatBoatRoute.quietSurface(level(), BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ()));
    }

    // ================================================================= 6 weather
    private final class Weather extends Base {
        Weather() { super("weather", 6.0, 1200); }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && !life.voyage().shoreVisit() && level().isRainingAt(c.boat().blockPosition());
        }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            boolean young = boat.youngPassengers().stream().anyMatch(YukkuriEntity.class::isInstance);
            boolean soaked = entity.life().wetness() >= 60 || boat.youngPassengers().stream()
                    .anyMatch(p -> p instanceof YukkuriEntity y && y.life().wetness() >= 60);
            if (soaked && c.tick() % 20 == 0) {
                var answer = life.voyage().requestShore("rain", level().getGameTime() + 1200);
                if (answer == Answer.ACCEPTED) return true;
            }
            // Covering is the first answer to rain: hunched over the cavity, still, the young under her.
            boat.helm().hold(entity, Purpose.ROUTINE);
            c.show(young ? Behaviors.BOAT_COVER_YOUNG : Behaviors.BOAT_STEADY);
            return true;
        }
    }

    // ================================================================= 7 provision
    private final class HungryCrewFood extends Base {
        HungryCrewFood() { super("hungry_crew_food", 6.5, 1200); }
        @Override public boolean wants(Context c) {
            var boat = c.boat();
            return afloatOwner(c) && !life.shoreForage().active() && boat.cargo().count() == 0 && care().serving().isEmpty()
                    && boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 30 && y.life().mealTicks() == 0);
        }
        @Override public boolean tick(Context c) {
            if (!seekFloatingFood(c.boat())) return false;
            c.show(Behaviors.BOAT_PADDLE);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().yieldToOtherActivity(); life.familyPaddle().yieldToOtherActivity(); }
        @Override public long progress(Context c) { return Math.round(c.boat().getX() * 4) * 31 + Math.round(c.boat().getZ() * 4); }
    }

    private final class NonAquatic extends Base {
        NonAquatic() { super("non_aquatic_day", 6.6, 100); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !EcologyRoutines.aquaticVoyage(entity) && c.tick() % 20 == 5; }
        @Override public boolean tick(Context c) {
            return life.voyage().requestShore("non_aquatic", level().getGameTime() + 1200) == Answer.ACCEPTED;
        }
    }

    private boolean seeksShoreCourtship(HatBoatEntity boat) {
        long now = level().getGameTime();
        if (now < nextCourtshipSearch) return false;
        nextCourtshipSearch = now + 100;
        UUID partner = entity.family().partner();
        if (partner != null && level().getEntity(partner) instanceof YukkuriEntity other && entity.family().seeksShoreCourtshipWith(other)) return true;
        return level().getEntitiesOfClass(YukkuriEntity.class, boat.getBoundingBox().inflate(8, 3, 8),
                other -> entity.family().seeksShoreCourtshipWith(other)).stream().findFirst().isPresent();
    }

    private final class Courtship extends Base {
        Courtship() { super("courtship", 6.7, 100); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !life.voyage().shoreVisit() && c.tick() % 20 == 9; }
        @Override public boolean tick(Context c) {
            return seeksShoreCourtship(c.boat()) && life.voyage().requestShore("courtship", level().getGameTime() + 1200) == Answer.ACCEPTED;
        }
    }

    private final class NoPaddle extends Base {
        NoPaddle() { super("no_paddle", 7.0, 1800); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !c.boat().cargo().paddle(); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            if (life.familyPaddle().recoverOwnPaddle(boat)) { c.show(Behaviors.BOAT_GATHER); return true; }
            boat.helm().drift(entity, Purpose.ROUTINE);
            c.show(boat.drifting() ? Behaviors.BOAT_STEADY : Behaviors.BOAT_CALL);
            // A lost paddle is not a reason to throw a dry family onto land. Food, care and sleep still work.
            if (care().tend(boat, level().isNight())) c.show(care().activity());
            else if (level().isNight() || entity.life().energy() <= 20) { entity.life().restInHat(boat); c.show(Behaviors.BOAT_SLEEP); }
            return true;
        }
        @Override public void stop(Context c, String reason) { care().cancel(); life.daily().stop(); }
    }

    private final class PaddleHandoff extends Base {
        PaddleHandoff() { super("paddle_handoff", 7.1, 600); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && life.familyPaddle().handingOver(); }
        @Override public boolean tick(Context c) {
            if (!life.familyPaddle().tick(c.boat())) return false;
            c.show(life.familyPaddle().activity());
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().yieldToOtherActivity(); life.shoreForage().stop(); }
    }

    private final class SelfFeed extends Base {
        SelfFeed() { super("self_feed", 7.2, 400); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && !entity.life().isSleeping() && entity.life().nutrition() < 60 && c.boat().cargo().count() > 0; }
        @Override public boolean tick(Context c) {
            if (!c.boat().cargo().feed(entity)) return false;
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            c.show(Behaviors.BOAT_EAT);
            return true;
        }
    }

    private boolean lowStores(HatBoatEntity boat) {
        return boat.cargo().count() < Math.min(life.stockTarget(), boat.cargo().count() + boat.cargo().remainingCapacity())
                && (Seasons.on(level(), entity.blockPosition(), Season.WINTER) || family().followsParent()
                    || boat.getPassengers().stream().anyMatch(p -> p instanceof YukkuriEntity y && y.life().nutrition() < 60));
    }

    private final class ShoreForage extends Base {
        ShoreForage() { super("shore_forage", 7.3, 2400); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean wants(Context c) {
            var forage = life.shoreForage();
            if (!afloatOwner(c)) return false;
            if (forage.active() && (c.boat().transportingInHat(entity) || care().active() || c.boat().flooding() > 0 || !c.boat().cargo().paddle()
                    || c.boat().cargo().count() >= life.stockTarget() || entity.life().mealTicks() > 0 || entity.life().isSleeping()
                    || entity.life().dryness() >= 40 || entity.isPanicking() || entity.family().laboring() || c.boat().hasIncoming())) return false;
            return forage.active() || lowStores(c.boat());
        }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            if (life.shoreForage().tick(boat)) { c.show(life.shoreForage().activity()); return true; }
            if (lowStores(boat) && floatingSource == null && !life.shoreForage().active() && entity.family().adult()
                    && c.tick() % 80 == 1 && !seekFloatingFood(boat) && life.requestShoreWork(ShoreWork.FORAGE) && life.voyage().shoreVisit()) return true;
            return false;
        }
        @Override public void stop(Context c, String reason) { life.shoreForage().stop(); life.daily().yieldToOtherActivity(); life.familyPaddle().yieldToOtherActivity(); }
        @Override public long progress(Context c) { return Math.round(c.boat().getX() * 4) * 31 + Math.round(c.boat().getZ() * 4) + (life.shoreForage().harvesting() ? 1 : 0); }
    }

    private final class Gather extends Base {
        Gather() { super("gather", 7.4, 80); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && gatherTicks > 0; }
        @Override public boolean tick(Context c) {
            gatherTicks--;
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            c.show(Behaviors.BOAT_GATHER);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); }
    }

    // ================================================================= 10 rest: the night berth
    private enum Stage { SEEK, ROUTE, WEDGE, SETTLED, FALLBACK_ROUTE, DRIFT_SLEEP }

    /**
     * The night without a rope. At dusk the captain looks for still water in a bank notch, among reeds or under a roof; she paddles
     * there and wedges the bow in (the helm eases the hull in and then lets go: the bank is the only thing holding it). The young
     * go into the cavity, she covers them and sings. Every 200 ticks she checks how far the hull has drifted: past three blocks she
     * wakes and berths again, at most twice, then sleeps adrift in still water. Nobody sleeps in a current.
     */
    private final class NightRest extends Base {
        private Stage stage = Stage.SEEK;
        private ShoreBerth.Berth berth;
        private BlockPos fallback;
        private Vec3 anchor;
        private int rebert, seekTicks, routeFails;
        private long stageSince;

        NightRest() { super("night_rest", 10.0, 20000); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && (level().isNight() || HatBoatRoute.exposedWeather(level(), c.boat().blockPosition()))
                    && !life.voyage().shoreVisit() && !life.firstVoyage().active();
        }
        @Override public void start(Context c) {
            stage = Stage.SEEK; berth = null; fallback = null; anchor = null; rebert = seekTicks = routeFails = 0; stageSince = c.now();
        }
        @Override public void stop(Context c, String reason) {
            life.nightSettled(false);
            care().cancel();
            if (stage == Stage.SETTLED && berth != null && c.boat() != null) {
                wedgedAt = berth.center();
                wedgeAway = berth.inland() == null ? null : berth.inland().scale(-1);
                wedgeUntil = c.now() + 600;
            }
            stage = Stage.SEEK;
        }
        private void go(Stage next, Context c) {
            if (next == stage) return;
            GameplayLog.limited(entity, "aquatic", "night." + next, 100, "night berth {} -> {} rebert={} berth={} fallback={}",
                    stage, next, rebert, berth == null ? null : berth.center(), fallback);
            stage = next; stageSince = c.now();
        }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            // A flooded or shaken hull is no place to sleep; the incident activities rank above this one and take over.
            switch (stage) {
                case SEEK -> {
                    seekTicks++;
                    c.show(Behaviors.BOAT_SEEK_REST);
                    if (seekTicks == 1 || seekTicks % 80 == 0) {
                        var outcome = ShoreBerth.find(level(), new ShoreBerth.Query(entity, boat, boat.hullScale(), boat.blockPosition(),
                                ShoreBerth.Purpose.NIGHT, life.boardingBank(), ShoreBerth.defaultRange(ShoreBerth.Purpose.NIGHT), 0,
                                p -> family().allowsDestination(boat, HatBoatRoute.surface(level(), p))));
                        if (outcome instanceof ShoreBerth.Berth found) { berth = found; routeFails = 0; go(Stage.ROUTE, c); return true; }
                        GameplayLog.limited(entity, "aquatic", "night.noberth", 600, "night berth not found {}", ((ShoreBerth.Refusal) outcome).describe());
                        fallback = HatBoatRoute.restingPlace(boat, life.boardingBank(), p -> family().allowsDestination(boat, p));
                        if (fallback != null) { go(Stage.FALLBACK_ROUTE, c); return true; }
                    }
                    if (seekTicks > 200) go(Stage.DRIFT_SLEEP, c);
                    else boat.helm().drift(entity, Purpose.ROUTINE);
                }
                case ROUTE -> {
                    c.show(Behaviors.BOAT_SEEK_REST);
                    if (berth == null || !HatBoatRoute.calmSurface(level(), berth.water())) { berth = null; go(Stage.SEEK, c); seekTicks = 0; return true; }
                    double gap = boat.position().subtract(berth.center()).horizontalDistance();
                    if (gap <= 2.0) { go(Stage.WEDGE, c); return true; }
                    if (c.age() % 20 == 1 && !boat.helm().travel(entity, Purpose.ROUTINE, berth.center()) && ++routeFails > 6) { berth = null; seekTicks = 0; go(Stage.SEEK, c); }
                    if (c.now() - stageSince > 900) { berth = null; seekTicks = 0; go(Stage.DRIFT_SLEEP, c); }
                }
                case WEDGE -> {
                    c.show(Behaviors.BOAT_WEDGE_BERTH);
                    var status = boat.helm().berth(entity, Purpose.CARE, berth.center(), berth.yaw(), .03, 6f);
                    boolean close = boat.position().subtract(berth.center()).horizontalDistanceSqr() <= .36;
                    if (status.arrived() || c.now() - stageSince > 80 && close || c.now() - stageSince > 160) {
                        boat.helm().release(entity.getUUID());
                        anchor = boat.position();
                        go(Stage.SETTLED, c);
                    }
                }
                case SETTLED -> {
                    life.nightSettled(true);
                    if (c.now() - stageSince > 1 && c.age() % 200 == 0) {
                        double drifted = boat.position().distanceTo(anchor);
                        if (drifted > 3 || boat.currentStrength() > .05) {
                            entity.life().wakeInHat();
                            life.nightSettled(false);
                            GameplayLog.event(entity, "aquatic", "night berth lost drift={} current={} rebert={}", String.format(java.util.Locale.ROOT, "%.1f", drifted), boat.currentStrength(), rebert);
                            if (++rebert <= 2) { seekTicks = 0; go(Stage.SEEK, c); } else go(Stage.DRIFT_SLEEP, c);
                            return true;
                        }
                    }
                    sleepAboard(c, boat, true);
                }
                case FALLBACK_ROUTE -> {
                    c.show(Behaviors.BOAT_SEEK_REST);
                    var target = fallback == null ? null : HatBoatRoute.surface(level(), fallback);
                    if (target == null || !HatBoatRoute.quietSurface(level(), fallback)) { fallback = null; seekTicks = 0; go(Stage.SEEK, c); return true; }
                    if (boat.position().distanceToSqr(target) <= 1) { go(Stage.DRIFT_SLEEP, c); return true; }
                    if (c.age() % 20 == 1) paddleWithinFamily(boat, target);
                    if (c.now() - stageSince > 600) go(Stage.DRIFT_SLEEP, c);
                }
                case DRIFT_SLEEP -> {
                    // Only actual soaking forces a shore visit; a new night never does.
                    if (c.age() % 40 == 0 && (entity.life().wetness() > 60 || boat.youngPassengers().stream()
                            .anyMatch(p -> p instanceof YukkuriEntity y && y.life().wetness() > 60)))
                        if (life.voyage().requestShore("rest_soaked", level().getGameTime() + 1200) == Answer.ACCEPTED) return true;
                    boat.helm().drift(entity, Purpose.ROUTINE);
                    boolean still = level().isNight() && !HatBoatRoute.exposedWeather(level(), boat.blockPosition())
                            && HatBoatRoute.calmSurface(level(), BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ())) && boat.currentStrength() <= .05;
                    if (still) sleepAboard(c, boat, false);
                    else { c.show(Behaviors.BOAT_STEADY); if (c.now() - stageSince > 200) { seekTicks = 0; go(Stage.SEEK, c); } }
                }
            }
            return true;
        }
        private void sleepAboard(Context c, HatBoatEntity boat, boolean covering) {
            if (care().tend(boat, true)) { c.show(care().active() ? care().activity() : Behaviors.BOAT_DRIFT); return; }
            // Sheltering from daytime weather is not sleep: she only dozes when it is night or she is tired.
            if (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping() || entity.life().wantsHatNap()) entity.life().restInHat(boat);
            boolean young = boat.youngPassengers().stream().anyMatch(YukkuriEntity.class::isInstance);
            c.show(covering && young ? Behaviors.BOAT_COVER_YOUNG : Behaviors.BOAT_SLEEP);
        }
        @Override public long progress(Context c) {
            // Moving, wedging or sleeping all count as progress; a captain going nowhere in the search is stalled.
            return switch (stage) {
                case ROUTE, FALLBACK_ROUTE -> Math.round(c.boat().getX() * 4) * 31 + Math.round(c.boat().getZ() * 4);
                case SEEK -> seekTicks > 220 ? 0 : Long.MIN_VALUE;
                default -> Long.MIN_VALUE;
            };
        }
    }

    /** At dawn the hull that spent the night wedged is pushed off the bank with the braid before anything else. */
    private final class PushOff extends Base {
        private long started;
        PushOff() { super("push_off", 10.05, 100); }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && wedgedAt != null && !level().isNight() && c.now() < wedgeUntil;
        }
        @Override public void start(Context c) { started = c.now(); }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            c.show(Behaviors.BOAT_PUSH_OFF);
            if (wedgeAway != null && c.now() - started < 24) {
                var away = boat.position().add(wedgeAway.scale(.04));
                boat.helm().berth(entity, Purpose.CARE, away, boat.getYRot(), .04, 3f);
            }
            if (c.now() - started >= 20) { wedgedAt = null; wedgeAway = null; boat.helm().release(entity.getUUID()); return false; }
            return true;
        }
        @Override public void stop(Context c, String reason) { wedgedAt = null; wedgeAway = null; }
    }

    private final class Sleep extends Base {
        Sleep() { super("sleep", 10.1, 20000); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean wants(Context c) {
            return afloatOwner(c) && (level().isNight() || entity.life().energy() <= 20 || entity.life().isSleeping() || entity.life().wantsHatNap());
        }
        @Override public boolean tick(Context c) {
            c.boat().helm().drift(entity, Purpose.ROUTINE);
            entity.life().restInHat(c.boat());
            c.show(Behaviors.BOAT_SLEEP);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); }
    }

    // ================================================================= 11 idle
    private final class FloatingFood extends Base {
        FloatingFood() { super("floating_food", 10.5, 1200); }
        @Override public boolean wants(Context c) { return afloatOwner(c) && (lowStores(c.boat()) || floatingSource != null); }
        @Override public boolean tick(Context c) {
            if (!seekFloatingFood(c.boat())) return false;
            c.show(Behaviors.BOAT_PADDLE);
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().yieldToOtherActivity(); life.familyPaddle().yieldToOtherActivity(); }
        @Override public long progress(Context c) { return Math.round(c.boat().getX() * 4) * 31 + Math.round(c.boat().getZ() * 4); }
    }

    private final class FamilyPaddle extends Base {
        FamilyPaddle() { super("family_paddle", 10.6, 2400); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean wants(Context c) { return afloatOwner(c); }
        @Override public boolean tick(Context c) {
            var paddle = life.familyPaddle();
            if (paddle.active() && (!c.boat().cargo().paddle() || care().active() || entity.life().mealTicks() > 0 || entity.life().isSleeping()
                    || entity.life().nutrition() < 60 || entity.life().energy() < 40 || entity.life().dryness() >= 40 || entity.isPanicking()
                    || entity.family().laboring() || c.boat().hasIncoming() || level().isNight() || level().isRainingAt(c.boat().blockPosition()))) {
                paddle.stop();
                return false;
            }
            if (!paddle.tick(c.boat())) return false;
            c.show(paddle.activity());
            return true;
        }
        @Override public void stop(Context c, String reason) { life.familyPaddle().stop(); life.daily().yieldToOtherActivity(); }
    }

    private final class Daily extends Base {
        Daily() { super("daily", 10.7, 2400); }
        @Override public int cooldownAfterTimeout() { return 40; }
        @Override public boolean tick(Context c) {
            if (!life.daily().tick(c.boat())) return false;
            c.show(life.daily().activity());
            return true;
        }
        @Override public void stop(Context c, String reason) { life.daily().stop(); }
    }

    private final class Idle extends Base {
        Idle() { super("idle", 11.0, 400); }
        @Override public int cooldownAfterTimeout() { return 0; }
        @Override public boolean tick(Context c) {
            var boat = c.boat();
            if (c.tick() % 40 == 1 && boat.cargo().remainingCapacity() > 0) seekFloatingFood(boat);
            if (!boat.paddling() && c.tick() % 160 == 1) {
                var around = boat.position().add(entity.getRandom().nextInt(21) - 10, 0, entity.getRandom().nextInt(21) - 10);
                paddleWithinFamily(boat, around);
            }
            c.show(boat.paddling() ? Behaviors.BOAT_PADDLE : Behaviors.BOAT_DRIFT);
            return true;
        }
    }

    // ================================================================= shared helpers
    private boolean seekFloatingFood(HatBoatEntity boat) {
        if (!boat.cargo().paddle() || !EventHooks.canEntityGrief(level(), entity)) return false;
        if (floatingSource != null && (!floatingSource.isAlive() || floatingSource.hasPickUpDelay() || !floatingSource.isInWater()
                || floatingSource.distanceToSqr(boat) > 576 || !boat.cargo().canStore(floatingSource.getItem())
                || !family().allowsDestination(boat, floatingSource.position()))) floatingSource = null;
        long now = level().getGameTime();
        if (floatingSource == null && now >= nextFoodSearch) {
            nextFoodSearch = now + 40;
            var food = level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(20, 2, 20), i -> i.isAlive()
                    && !i.hasPickUpDelay() && i.isInWater() && entity.life().acceptsFood(i.getItem()) && boat.cargo().canStore(i.getItem())
                    && family().allowsDestination(boat, i.position())).stream()
                    .sorted(Comparator.comparingDouble(boat::distanceToSqr)).limit(5).toList();
            for (var item : food) {
                if (boat.distanceToSqr(item) < 2) { collect(boat); if (boat.cargo().count() > 0) return false; }
                if (paddleWithinFamily(boat, item.position())) { floatingSource = item; return true; }
            }
        }
        if (floatingSource != null) {
            if (boat.paddling()) return true;
            if (paddleWithinFamily(boat, floatingSource.position())) return true;
            floatingSource = null;
        }
        return false;
    }

    private boolean paddleWithinFamily(HatBoatEntity boat, Vec3 destination) {
        if (destination == null || !family().allowsDestination(boat, destination)) return false;
        var path = HatBoatRoute.find(boat, destination, p -> family().allowsDestination(boat, p));
        return !path.isEmpty() && path.stream().allMatch(p -> family().allowsDestination(boat, p)) && boat.helm().route(entity, Purpose.ROUTINE, path);
    }

    private void collect(HatBoatEntity boat) {
        if (!EventHooks.canEntityGrief(level(), entity)) return;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(.65, .8, .65), i -> i.isAlive() && !i.hasPickUpDelay())) {
            if (!entity.hasLineOfSight(item)) continue;
            boolean changed = !boat.cargo().paddle() && boat.cargo().givePaddle(item.getItem(), false);
            if (entity.life().acceptsFood(item.getItem())) changed |= boat.cargo().store(item.getItem(), 2, false) > 0;
            if (changed) { if (item.getItem().isEmpty()) item.discard(); gatherTicks = 32; entity.speak("take_food"); }
        }
    }
}
