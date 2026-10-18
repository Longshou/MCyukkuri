package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals.Actions;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;

/** Server presentation: fixed physical takeovers, persistent states, then actual running Goal order. */
public final class PresentationArbiter {
    private record Source(String id, PresentationSource source) {}

    // A winner returns from aiStep: later guards and their possible effects must not run.
    private static final List<Source> EARLY = List.of(
            new Source("braid_rescue", PresentationArbiter::braidRescue),
            new Source("hat_motion", PresentationArbiter::hatMotion),
            new Source("boarding", PresentationArbiter::boarding),
            new Source("self_rescue", PresentationArbiter::selfRescue),
            new Source("water_distress", PresentationArbiter::waterDistress),
            new Source("aquatic_alarm", PresentationArbiter::aquaticAlarm),
            new Source("panic", PresentationArbiter::panic));
    // Reached only after the surviving-path panic decrement, even when a command suppresses panic.
    private static final List<Source> LATE = List.of(
            new Source("shell", PresentationArbiter::shell),
            new Source("agitation", PresentationArbiter::agitation));
    private final PresentationAccess state;
    // Observation only, never loaded or used by gameplay. Serialization shares the D4 view gate.
    private String lastSource;
    private int lastEntityTick;
    private long lastGameTick;

    public PresentationArbiter(PresentationAccess state) { this.state = state; }

    /** Called at the original server/alive boundary, after every component tick and its sync writes. */
    public void tick(double travelledX, double travelledZ) {
        var e = state.owner();
        var m = new Motion(travelledX, travelledZ);
        if (presentFirst(EARLY, m)) return;
        if (state.panicTicks() > 0) state.decrementPanic();
        if (presentFirst(LATE, m)) return;
        if (state.requestedAction() != null && !state.requestedAction().loops() && !state.requestedAction().holdsEndPose()
                && e.level().getGameTime() - e.getAnimationStartTick() >= state.requestedAction().durationTicks()) {
            state.clearRequestedAction();
        }
        presentOrdinary(m);
    }

    private boolean presentFirst(List<Source> sources, Motion motion) {
        for (var source : sources) {
            Presentation result = source.source().present(state, motion);
            if (result == null) continue;
            publish(source.id(), result);
            return true;
        }
        return false;
    }

    private void publish(String id, Presentation result) {
        result.publish();
        if (YukkuriConfig.SPEC.isLoaded() && YukkuriConfig.DEBUG_ENTITY_SAVE_VIEWS.get()) {
            lastSource = id;
            lastEntityTick = state.owner().tickCount;
            lastGameTick = state.owner().level().getGameTime();
        }
    }

    /**
     * Never re-runs a source. A skipped/dead tick cannot report an old winner as current.
     * For ordinary rows Source names the behavior provider; command/flight can still override its action.
     */
    public CompoundTag debugSnapshot() {
        var tag = new CompoundTag();
        boolean current = lastSource != null && lastEntityTick == state.owner().tickCount
                && lastGameTick == state.owner().level().getGameTime();
        tag.putBoolean("Current", current);
        if (lastSource != null) {
            tag.putString("LastSource", lastSource);
            tag.putLong("LastTick", lastGameTick);
        }
        if (current) tag.putString("Source", lastSource);
        return tag;
    }

    /**
     * Keep independent physiology/contact alive when no corresponding Goal owns it. In particular,
     * Life.stopGoal clears its task but leaves a real meal, and Family.stop clears its task while
     * birth, hat motion, flight and delivery comfort continue on their own clocks.
     * These layers retain their old relative order: illness, water, life, family. A planned route
     * is not a continuing pose (Life.plan can even prepare a family task before its Goal starts).
     * Goal candidates follow; only the final family/newborn fallback is unconditional.
     */
    private void presentOrdinary(Motion motion) {
        var e = state.owner();
        var running = state.runningGoals(); // Exactly one snapshot; never ask canUse/continue/plan here.
        if (!running.contains(PresentationGoals.MIND) && e.mind().active()) {
            publishOrdinary("state.mind", motion, e.mind().activity(), Actions.ORDINARY);
            return;
        }
        if (e.isInWaterOrBubble()) {
            publishOrdinary("state.water", motion, Behaviors.ESCAPE_WATER, Actions.ORDINARY);
            return;
        }
        if (e.life().mealTicks() > 0) {
            publishOrdinary("state.meal", motion, e.life().activity(), Actions.ORDINARY);
            return;
        }
        if (!running.contains(PresentationGoals.LIFE)) {
            String behavior = e.life().activity();
            if (continuingLifePose(behavior)) {
                publishOrdinary("state.life", motion, behavior, Actions.ORDINARY);
                return;
            }
        }
        if (!running.contains(PresentationGoals.FAMILY) && e.family().active()) {
            String behavior = e.family().activity();
            if (continuingFamilyPose(e, behavior)) {
                publishOrdinary("state.family", motion, behavior, Actions.ORDINARY);
                return;
            }
        }
        for (var candidate : running.candidates()) {
            String behavior = candidate.activity().get(); // At most once, in the final sorted order.
            if (behavior == null) continue;
            publishOrdinary("goal." + candidate.source().id(), motion, behavior, candidate.source().actions());
            return;
        }
        publishOrdinary("state.family_fallback", motion, e.family().activity(), Actions.ORDINARY);
    }

    private static boolean continuingLifePose(String behavior) {
        return switch (behavior) {
            case Behaviors.SLEEP_ENTER, Behaviors.SLEEP, Behaviors.WAKE, Behaviors.DRINK,
                    Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.TOILET_ACCIDENT -> true;
            default -> false;
        };
    }

    private static boolean continuingFamilyPose(YukkuriEntity e, String behavior) {
        if (e.family().flying() || e.family().awaitingLanding()
                || e.family().careChewTicks() > 0 || e.family().careContactProgress() > 0) return true;
        if ((behavior.equals(Behaviors.PAIRING) || behavior.equals(Behaviors.PAIRING_ACCEPT))
                && e.family().courtAnimationStart() >= 0) return true;
        return switch (behavior) {
            case Behaviors.LABOR, Behaviors.POSTPARTUM, Behaviors.NUZZLE,
                    Behaviors.HAT_OFF, Behaviors.HAT_ON, Behaviors.HAT_OFF_SELF, Behaviors.HAT_ON_SELF,
                    Behaviors.HAT_OFF_AQ, Behaviors.HAT_ON_AQ, Behaviors.HAT_OFF_SELF_AQ, Behaviors.HAT_ON_SELF_AQ,
                    Behaviors.HAT_HOLD, Behaviors.HAT_HOLD_AQ -> true;
            default -> false;
        };
    }

    private void publishOrdinary(String id, Motion motion, String behavior, Actions actions) {
        publish(id, new Presentation(() -> ordinary(state, motion, behavior, actions)));
    }

    private static Presentation braidRescue(PresentationAccess state, Motion m) {
        var e = state.owner();
        // Contact rescue owns both poses; water panic must not keep a held child paddling.
        if(e.family().braidRescueActive()&&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                if(state.panicTicks()>0)state.decrementPanic();
                String phase=e.braidRescuePhase();boolean parent=e.braidRescueSync().parentId().value()==e.getId();
                if(phase.startsWith("CRADLE_")){
                    state.behavior(parent?Behaviors.CAREGIVER:Behaviors.NEWBORN);state.animation(YukkuriAction.IDLE);
                    state.expression(parent?"closed_smile":"neutral");return;
                }
                // Reaching and gripping retain their real contact phases.
                String motion=parent?switch(phase){
                    case "REACH"->Behaviors.BRAID_RESCUE_REACH;
                    case "GRIP"->Behaviors.BRAID_RESCUE_HOLD;
                    case "OFFER","BITE","SLIDE"->Behaviors.BRAID_RESCUE_OFFER;
                    case "PUSH"->Behaviors.BRAID_RESCUE_PUSH;
                    default->Behaviors.BRAID_RESCUE_SETTLE;
                }:e.family().heldByBraid()?Behaviors.BRAID_BE_RESCUED:e.waterDistress().activity();
                state.behavior(motion);state.animation(EcologyPresentation.action(motion));
                state.animationStart(parent||e.family().heldByBraid()?e.braidRescueStartTick():e.waterDistressStartTick());
                state.expression(switch(phase){
                    case "PUSH","SLIDE"->parent?"closed_smile":"teary";
                    case "SETTLE","RELEASE"->"neutral";
                    default->"teary";
                });
                return;
            });
        }
        return null;
    }

    private static Presentation hatMotion(PresentationAccess state, Motion m) {
        var e = state.owner();
        // Crowd/motion queries precede the command guards. Poses do not grant water protection.
        if(e.getVehicle() instanceof HatBoatEntity movingHat
                &&(movingHat.rocking()||movingHat.capsizing()||!movingHat.crowdingState(e).equals(Behaviors.IDLE))
                &&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                if(state.panicTicks()>0)state.decrementPanic();
                boolean captain=e.getUUID().equals(movingHat.ownerYukkuri());
                String crowd=movingHat.crowdingState(e);
                String motion=movingHat.capsizing()?Behaviors.BOAT_CAPSIZING:!crowd.equals(Behaviors.IDLE)?crowd:captain?Behaviors.BOAT_STEADY:Behaviors.BOAT_ROCKED;
                state.behavior(motion);
                state.animation(movingHat.capsizing()||motion.equals(Behaviors.HAT_STUMBLE)||crowd.equals(Behaviors.IDLE)
                        ?captain?YukkuriAction.BOAT_STEADY:YukkuriAction.HAT_STUMBLE
                        :EcologyPresentation.action(motion));
                if(!movingHat.capsizing()&&!crowd.equals(Behaviors.IDLE))state.animationStart(movingHat.crowdingPhaseStart(e));
                state.expression(e.family().adult()?"surprise":"teary");
                return;
            });
        }
        return null;
    }

    private static Presentation boarding(PresentationAccess state, Motion m) {
        var e = state.owner();
        if(e.boardingSync().active().value()&&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                if(state.panicTicks()>0)state.decrementPanic();
                var boarding=e.boardingSync();String phase=boarding.phase().value();
                String motion=Behaviors.hatBoard(phase.toLowerCase(Locale.ROOT));
                state.behavior(motion);state.animation(EcologyPresentation.action(motion));
                state.animationStart(boarding.start().value());state.expression("teary");return;
            });
        }
        return null;
    }

    private static Presentation selfRescue(PresentationAccess state, Motion m) {
        var e = state.owner();
        if(e.aquaticLife().selfRescue().active()&&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                if(state.panicTicks()>0)state.decrementPanic();
                String motion=e.aquaticLife().selfRescue().activity();state.behavior(motion);
                state.animation(EcologyPresentation.action(motion));
                state.animationStart(e.aquaticLife().selfRescue().phaseStart());
                state.expression(motion.equals(Behaviors.WATER_HAT_SAFE)?"sad":"teary");return;
            });
        }
        return null;
    }

    private static Presentation waterDistress(PresentationAccess state, Motion m) {
        var e = state.owner();
        if ((e.waterDistress().active() || e.waterDistress().airbornePlunge()) && !e.isCommandPlaying() && !e.operations().active()){
            return new Presentation(() -> {
                if (state.panicTicks() > 0) state.decrementPanic();
                String distress = e.waterDistress().airbornePlunge() ? Behaviors.HAT_SLIPPING : e.waterDistress().activity();
                state.behavior(distress);
                state.animation(EcologyPresentation.action(distress));
                state.animationStart(e.waterDistressStartTick());
                state.expression(e.waterDistress().phase() == WaterDistress.Phase.IMPACT ? "cry" : "teary");
                return;
            });
        }
        return null;
    }

    private static Presentation aquaticAlarm(PresentationAccess state, Motion m) {
        var e = state.owner();
        if(!e.aquaticAlarmActivity().equals(Behaviors.IDLE)&&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                if(state.panicTicks()>0)state.decrementPanic();
                String alarm=e.aquaticAlarmActivity();state.behavior(alarm);
                YukkuriAction action=e.getVehicle() instanceof HatBoatEntity boat
                        ?boatAction(state, boat,alarm):EcologyPresentation.action(alarm);
                if(!e.isPassenger()&&e.onGround()&&(m.distanceSquared())>.000001)action=YukkuriAction.WADDLE;
                state.animation(action);state.animationStart(e.aquaticAlarmStartTick());
                state.expression(EcologyPresentation.expression(e,"teary"));
                return;
            });
        }
        return null;
    }

    private static Presentation panic(PresentationAccess state, Motion m) {
        var e = state.owner();
        if (state.panicTicks() > 0&&!e.isCommandPlaying()&&!e.operations().active()){
            return new Presentation(() -> {
                boolean shell = e.shellRetraction().active();
                if (shell) { state.stopNavigation(); }
                else state.animation(e.isInWaterOrBubble()?YukkuriAction.WATER_STRUGGLE:e.shelteredInHat() ? YukkuriAction.BOAT_STEADY : state
                        .panicTicks() > 100 ? YukkuriAction.STARTLE : YukkuriAction.FLEE);
                // Panic chooses the clip before decrementing, then writes the face before the behavior.
                state.decrementPanic();
                state.expression("surprise");
                state.behavior(e.isInWaterOrBubble() ? Behaviors.ESCAPE_WATER : e.shelteredInHat() ? Behaviors.BOAT_STEADY : shell ? Behaviors.SHELL_RETRACT : Behaviors.FLEE);
                return;
            });
        }
        return null;
    }

    private static Presentation shell(PresentationAccess state, Motion m) {
        var e = state.owner();
        if(e.shellRetraction().active()){
            return new Presentation(() -> {
                // Keep the animation/start already sampled by ShellRetraction.tick.
                state.expression("neutral");state.behavior(Behaviors.SHELL_RETRACT);return;
            });
        }
        return null;
    }

    private static Presentation agitation(PresentationAccess state, Motion m) {
        var e = state.owner();
        if (e.agitation().activePresentation()){
            return new Presentation(() -> {
                state.behavior(e.agitation().activity());
                state.animation(m.distanceSquared() > .000001 && !e.agitation().poseLocked()
                        ? YukkuriAction.WADDLE : e.agitation().action());
                state.expression(e.agitation().expression());
                return;
            });
        }
        return null;
    }

    /** One common action/start/expression tail for the selected ordinary behavior. */
    private static void ordinary(PresentationAccess state, Motion m, String behavior, Actions actions) {
        var e = state.owner();
        if (behavior.equals(Behaviors.BOAT_TOILET) && e.childhood().firstToiletDone() && !e.life().hatToiletReleasing()) behavior = Behaviors.SEEK_TOILET;
        YukkuriAction action = state.requestedAction() != null ? state.requestedAction()
                : e.getVehicle() instanceof HatBoatEntity boat ? boatAction(state, boat, behavior)
                : e.isInWaterOrBubble() ? YukkuriAction.WATER_STRUGGLE
                : m.distanceSquared() > 0.000001 && e.onGround() && !e.life().isSleeping()
                ? YukkuriAction.WADDLE
                : actions == Actions.GROUP ? EcologyPresentation.group(behavior)
                : stationaryAction(state, behavior, actions);
        String flightAnimation = state.requestedAction() == null ? predatorAnimation(e, actions) : null;
        if (flightAnimation != null) {
            // The old raw flight write has the reverse order of setAnimation: ID, then start.
            if (!e.getAnimationId().equals(flightAnimation)) { state.animationId(flightAnimation); state.animationStart(e.level().getGameTime()); }
        } else {
            state.animation(action);
            if(behavior.equals(Behaviors.PAIRING_ACCEPT)&&action==YukkuriAction.GREET
                    ||behavior.equals(Behaviors.PAIRING)&&action==YukkuriAction.NUZZLE) {
                long start=e.family().courtAnimationStart();
                if(start>=0&&e.getAnimationStartTick()!=start)state.animationStart(start);
            }
            // Partners sample the authoritative contraction, rescaling the shorter hat clips.
            int phase = action == YukkuriAction.LABOR || action == YukkuriAction.BOAT_LABOR ? e.liveDeliveryTicks()
                    : (action == YukkuriAction.MIDWIFE || action == YukkuriAction.HAT_HOLD || action == YukkuriAction.HAT_HOLD_AQ)
                    && e.family().midwifeTarget() != null ? e.family().midwifeTarget().liveDeliveryTicks() : -1;
            if (phase >= 0) {
                long frame = Math.round(action == YukkuriAction.HAT_HOLD || action == YukkuriAction.HAT_HOLD_AQ
                        ? LiveBirthGeometry.hatAnimationTick(phase)
                        : action == YukkuriAction.MIDWIFE ? LiveBirthGeometry.partnerTick(phase)
                        : LiveBirthGeometry.animationTick(phase));
                long start = e.level().getGameTime() - frame;
                if (e.getAnimationStartTick() != start) state.animationStart(start);
            }
        }
        // EcologyPresentation.expression reads this new behavior/start; keep fallback evaluation here.
        state.behavior(behavior);
        state.expression("predator_bite".equals(flightAnimation) ? "biting" : EcologyPresentation.expression(e,
                state.reactionTicks() > 0 ? state.reactionExpression() : e.body().dying() ? "blank_cry" : e.mind().face(e.body().weak() ||
                        e.settlement().society().dejected() ? "teary" : e.life().hasAccessory() ? "neutral" : "surprise")));
    }

    /** Losing a hunt is not ownership, but real flight and the existing post-contact bite clock persist. */
    private static String predatorAnimation(YukkuriEntity e, Actions actions) {
        String animation = e.predator().animation();
        return actions == Actions.PREDATOR || e.predatorFlying() || "predator_bite".equals(animation) ? animation : null;
    }

    /** Behavior motion, else a short reaction, else the withdrawn droop of a yukkuri without its accessory. */
    private static YukkuriAction stationaryAction(PresentationAccess state, String behavior, Actions actions) {
        var e = state.owner();
        if (e.family().flying() && e.profile().aquatic()) return YukkuriAction.BOAT_BOARD;
        if (state.reactionTicks() > 0 && state.reactionEvent().equals("birth_lost")) return YukkuriAction.BEREAVED;
        if(e.aftermathWeak()&&(behavior.equals(Behaviors.IDLE)||behavior.equals(Behaviors.TEND_BROOD)))return YukkuriAction.DROOP;
        // A real successful conception gets a brief response before ordinary brooding resumes.
        // Food, sleep, danger, movement and other active duties retain their own presentation.
        if(state.reactionTicks()>60&&state.reactionEvent().equals("expecting")
                &&(behavior.equals(Behaviors.IDLE)||behavior.equals(Behaviors.TEND_BROOD)))return YukkuriAction.SHIAWASE;
        YukkuriAction action = behavior.equals(Behaviors.FORAGE_HARVEST) ? YukkuriAction.BRACE : EcologyPresentation.action(behavior);
        // House behaviors and predator hunts own their body clips on the client.
        if (action != YukkuriAction.IDLE || behavior.startsWith(Behaviors.HOUSE_PREFIX) || actions == Actions.PREDATOR || e.isPassenger()) return action;
        if (state.reactionTicks() > 0) action = EcologyPresentation.reaction(state.reactionEvent());
        // Back ashore with her hat, the aquatic Marisa shakes off the water.
        if (action == YukkuriAction.SHIAWASE && state.reactionEvent().equals("returned_accessory")
                && e.subspecies().kind() == SubspeciesLife.Kind.AQUATIC_MARISA) action = YukkuriAction.SHAKE;
        return action == YukkuriAction.IDLE && (!e.life().hasAccessory() || e.body().weak() || e.body().dying()) ? YukkuriAction.DROOP : action;
    }
    /** The aquatic owner's boat set, or the young aboard rocked in the hat. */
    private static YukkuriAction boatAction(PresentationAccess state, HatBoatEntity boat, String behavior) {
        var e = state.owner();
        if(boat.rocking()||boat.capsizing())return e.getUUID().equals(boat.ownerYukkuri())?YukkuriAction.BOAT_STEADY:YukkuriAction.HAT_STUMBLE;
        if(behavior.startsWith(Behaviors.BOAT_ALARM_PREFIX)||behavior.equals(Behaviors.BOAT_ANSWER_CALL)||behavior.equals(Behaviors.BOAT_CALM))
            return EcologyPresentation.action(behavior);
        if(EcologyPresentation.boatFleeing(behavior))
            return e.getUUID().equals(boat.ownerYukkuri())&&boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_FLEE;
        if(EcologyPresentation.boatReassuring(behavior))
            return e.getUUID().equals(boat.ownerYukkuri())&&boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_REASSURE;
        if (e.getUUID().equals(boat.ownerYukkuri()))
            return EcologyPresentation.boat(e, boat, behavior, state.reactionTicks() > 0 ? state.reactionEvent() : "");
        if (behavior.equals(Behaviors.HAT_WALK) || behavior.equals(Behaviors.BOAT_BE_CARRIED))
            return EcologyPresentation.action(behavior);
        // Real sleep, meals and ongoing care win over a lingering newborn reaction.
        return e.life().isSleeping() ? YukkuriAction.SLEEP : e.life().mealTicks() > 0 ? EcologyPresentation.action(behavior)
                : behavior.equals(Behaviors.NEWBORN_CRAWL) || behavior.equals(Behaviors.LEARN_JUMP) || behavior.equals(Behaviors.BOAT_TOILET)
                    || behavior.equals(Behaviors.FIRST_TOILET_WAIT) || behavior.equals(Behaviors.FIRST_TOILET_STIMULATED) ? EcologyPresentation.action(behavior)
                : behavior.equals(Behaviors.BOAT_LISTEN) ? YukkuriAction.SWAY
                : behavior.equals(Behaviors.BOAT_RECEIVE_CARE) ? YukkuriAction.NUZZLE
                : behavior.equals(Behaviors.AQUATIC_WATCH_PARENT) ? YukkuriAction.HAT_CRADLE
                : behavior.equals(Behaviors.AQUATIC_ANSWER_FAMILY) ? YukkuriAction.HAT_CRADLE
                : behavior.equals(Behaviors.BOAT_RECEIVE_PADDLE) ? YukkuriAction.HAT_CRADLE
                : behavior.equals(Behaviors.AQUATIC_PADDLE_PRACTICE) || behavior.equals(Behaviors.AQUATIC_IMITATE_PADDLE)
                    || behavior.equals(Behaviors.BOAT_FOLLOW_FAMILY) || behavior.equals(Behaviors.BOAT_RETURN_FAMILY)
                    || behavior.equals(Behaviors.BOAT_GUARD_YOUNG) ? YukkuriAction.HAT_CRADLE
                : behavior.equals(Behaviors.VOYAGE_LAUNCH) ? YukkuriAction.HAT_OFF_SELF_AQ
                : behavior.equals(Behaviors.VOYAGE_PROUD) ? YukkuriAction.SHIAWASE
                : boat.changingSeat(e) && e.profile().aquatic() ? YukkuriAction.BOAT_BOARD
                : boat.onBrim(e) ? YukkuriAction.HAT_BRIM
                : state.reactionTicks() > 0 && state.reactionEvent().equals("newborn") ? YukkuriAction.FIRST_WAKE : YukkuriAction.HAT_CRADLE;
    }

}
