package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriControl;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals.Actions;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.life.WaterDistress;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;

/** Server presentation: fixed physical takeovers, persistent states, then actual running Goal order. */
public final class PresentationArbiter {
    private final PresentationAccess state;
    // Observation only, never loaded or used by gameplay. Serialization shares the D4 view gate.
    private String lastSource;
    private int lastEntityTick;
    private long lastGameTick;

    public PresentationArbiter(PresentationAccess state) { this.state = state; }

    /** Publishes the controls already advanced by the entity; no gameplay clocks or navigation writes. */
    public void tick(YukkuriControl.Frame frame, double travelledX, double travelledZ) {
        var motion = new Motion(travelledX, travelledZ);
        switch (frame.takeover()) {
            case WATER_DISTRESS -> publish("water_distress", waterDistress(state, motion));
            case PANIC -> publish("panic", panic(state, frame.panicTicks()));
            case SHELL -> publish("shell", shell(state, motion));
            case AGITATION -> publish("agitation", agitation(state, motion));
            case NONE -> presentOrdinary(motion);
        }
    }

    private void publish(String id, Presentation result) {
        result.publish();
        state.expression(expression());
        if (YukkuriConfig.SPEC.isLoaded() && YukkuriConfig.DEBUG_ENTITY_SAVE_VIEWS.get()) {
            lastSource = id;
            lastEntityTick = state.owner().tickCount;
            lastGameTick = state.owner().level().getGameTime();
        }
    }

    /** Physical operation, reaction, injury/crying, emotion, then the selected behavior and authored face. */
    private String expression(){
        var e=state.owner();
        if(e.operations().active())return switch(e.operations().pose()){
            case HAT_REMOVE->"teary";
            case HAT_RETURN,RUB->"closed_smile";
            case GRIP,PIN,FIXED,HOOK,RUMP,VIBRATE,ASSIST->"cry";
            default->"surprise";
        };
        if(state.reactionTicks()>0)return state.reactionExpression();
        if(e.body().dying())return "blank_cry";
        if(e.body().condition().needsPainExpression()||e.life().spicyTicks()>0)return "cry";
        if(e.body().weak()||e.settlement().society().dejected())return "teary";
        String mood=e.mind().face("neutral");
        if(!mood.equals("neutral"))return mood;
        var court=e.family().courtshipState();
        if(court.getString("Phase").equals("CONTACT")&&e.getBehaviorId().equals(Behaviors.PAIRING))
            return court.getString("Role").equals("PENI")?"flushed_tongue":"satisfied";
        if(e.aftermathBurden()&&(e.getBehaviorId().equals(Behaviors.IDLE)||e.getBehaviorId().equals(Behaviors.TEND_BROOD)))return "teary";
        String clip=e.getAnimationId();long start=e.getAnimationStartTick();
        switch(e.getBehaviorId()){
            case Behaviors.TOILET,Behaviors.GROUND_TOILET,Behaviors.BOAT_TOILET,Behaviors.TOILET_ACCIDENT,
                 Behaviors.FIRST_TOILET_ASSIST,Behaviors.FIRST_TOILET_WAIT,Behaviors.FIRST_TOILET_STIMULATED->{clip=e.getBehaviorId();start=e.getBehaviorStartTick();}
        }
        return ExpressionClips.sample(e.subspecies().assetId(),clip,Math.max(0,e.level().getGameTime()-start)/20f,e.getExpressionId());
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

    private static Presentation waterDistress(PresentationAccess state, Motion m) {
        var e = state.owner();
        return new Presentation(() -> {
            // The water clock still records immersion and damage. A returning flier uses its real wings,
            // not the generic swimmer's hand bones (which are a predator's side locks).
            if (presentPredatorFlight(state)) { state.expression("teary"); return; }
            String distress = e.waterDistress().airbornePlunge() ? Behaviors.HAT_SLIPPING : e.waterDistress().activity();
            state.behavior(distress);
            state.animation(EcologyPresentation.action(distress));
            state.animationStart(e.waterDistressStartTick());
            state.expression(e.waterDistress().phase() == WaterDistress.Phase.IMPACT ? "cry" : "teary");
            return;
        });
    }

    private static Presentation panic(PresentationAccess state, int panicTicks) {
        var e = state.owner();
        return new Presentation(() -> {
            if (presentPredatorFlight(state)) { state.expression("surprise"); return; }
            boolean shell = e.shellRetraction().active();
            if (!shell) state.animation(e.isInWaterOrBubble()?YukkuriAction.WATER_STRUGGLE : panicTicks > 100 ? YukkuriAction.STARTLE : YukkuriAction.FLEE);
            // The frame retains the pre-decrement count used to choose the clip.
            state.expression("surprise");
            state.behavior(e.isInWaterOrBubble() ? Behaviors.ESCAPE_WATER : shell ? Behaviors.SHELL_RETRACT : Behaviors.FLEE);
            return;
        });
    }

    private static boolean presentPredatorFlight(PresentationAccess state) {
        var e = state.owner();
        if (!e.predatorFlying()) return false;
        String animation = e.predator().animation();
        if (animation == null) return false;
        state.behavior(e.predator().activity());
        if (!e.getAnimationId().equals(animation)) { state.animationId(animation); state.animationStart(e.level().getGameTime()); }
        return true;
    }

    private static Presentation shell(PresentationAccess state, Motion m) {
        var e = state.owner();
        return new Presentation(() -> {
            // Keep the animation/start already sampled by ShellRetraction.tick.
            state.expression("neutral");state.behavior(Behaviors.SHELL_RETRACT);return;
        });
    }

    private static Presentation agitation(PresentationAccess state, Motion m) {
        var e = state.owner();
        return new Presentation(() -> {
            state.behavior(e.agitation().activity());
            state.animation(m.distanceSquared() > .000001 && !e.agitation().poseLocked()
                    ? YukkuriAction.WADDLE : e.agitation().action());
            state.expression(e.agitation().expression());
            return;
        });
    }

    /** One common action/start/expression tail for the selected ordinary behavior. */
    private static void ordinary(PresentationAccess state, Motion m, String behavior, Actions actions) {
        var e = state.owner();
        if (behavior.equals(Behaviors.BOAT_TOILET)) behavior = Behaviors.SEEK_TOILET;
        YukkuriAction action = state.requestedAction() != null ? state.requestedAction()
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
                        e.settlement().society().dejected() ? "teary" : "neutral")));
    }

    /** Losing a hunt is not ownership, but real flight and the existing post-contact bite clock persist. */
    private static String predatorAnimation(YukkuriEntity e, Actions actions) {
        String animation = e.predator().animation();
        return actions == Actions.PREDATOR || e.predatorFlying() || "predator_bite".equals(animation) ? animation : null;
    }

    /** Behavior motion, else a short reaction, else the withdrawn droop of a yukkuri without its accessory. */
    private static YukkuriAction stationaryAction(PresentationAccess state, String behavior, Actions actions) {
        var e = state.owner();
        if (state.reactionTicks() > 0 && state.reactionEvent().equals("birth_lost")) return YukkuriAction.BEREAVED;
        if(e.aftermathWeak()&&(behavior.equals(Behaviors.IDLE)||behavior.equals(Behaviors.TEND_BROOD)))return YukkuriAction.DROOP;
        // A real successful conception gets a brief response before ordinary brooding resumes.
        // Food, sleep, danger, movement and other active duties retain their own presentation.
        if(state.reactionTicks()>60&&state.reactionEvent().equals("expecting")
                &&(behavior.equals(Behaviors.IDLE)||behavior.equals(Behaviors.TEND_BROOD)))return YukkuriAction.SHIAWASE;
        YukkuriAction action = behavior.equals(Behaviors.FORAGE_SEEK) ? YukkuriAction.NERVOUS : behavior.equals(Behaviors.FORAGE_HARVEST) ? YukkuriAction.BRACE : EcologyPresentation.action(behavior);
        // House behaviors and predator hunts own their body clips on the client.
        if (action != YukkuriAction.IDLE || behavior.startsWith(Behaviors.HOUSE_PREFIX) || actions == Actions.PREDATOR || e.isPassenger()) return action;
        if (state.reactionTicks() > 0) action = EcologyPresentation.reaction(state.reactionEvent());
        return action == YukkuriAction.IDLE && (!e.life().hasAccessory() && !e.life().accessoryCustodied() || e.body().weak() || e.body().dying()) ? YukkuriAction.DROOP : action;
    }
}
