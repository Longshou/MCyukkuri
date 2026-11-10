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
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import net.minecraft.nbt.CompoundTag;

/** Server presentation: fixed physical takeovers, persistent states, then actual running Goal order. */
public final class PresentationArbiter {
    private final PresentationAccess state;
    // Observation only, never loaded or used by gameplay. Serialization shares the D4 view gate.
    private String lastSource;
    private int lastEntityTick;
    private long lastGameTick;
    private String lastBirthBehavior;
    private String lastBirthClip;

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
        publishMoment(id);
        state.expression(expression());
        var e = state.owner();
        if (e.level().isClientSide) return;
        String behavior = e.family().laboring() || e.getBehaviorId().equals(Behaviors.POSTPARTUM) ? e.getBehaviorId() : null;
        String clip = e.getAnimationId();
        if (behavior != null && (!behavior.equals(lastBirthBehavior) || !clip.equals(lastBirthClip)))
            GameplayLog.event(e, "family", "birth presentation source={} behavior={} clip={} delivery={} animationStart={} host={}",
                    id, behavior, clip, e.liveDeliveryTicks(), e.getAnimationStartTick(),
                    e.getVehicle() == null ? null : e.getVehicle().getUUID());
        lastBirthBehavior = behavior;
        lastBirthClip = clip;
        if (YukkuriConfig.SPEC.isLoaded() && YukkuriConfig.DEBUG_ENTITY_SAVE_VIEWS.get()) {
            lastSource = id;
            lastEntityTick = state.owner().tickCount;
            lastGameTick = state.owner().level().getGameTime();
        }
    }

    private void publishMoment(String source) {
        var e = state.owner();
        String id = "";
        if ((source.startsWith("goal.") || source.startsWith("state.")) && state.requestedAction() == null
                && !e.operations().active()) {
            String candidate = Moments.select("groups", e.getBehaviorId(), EcologyPresentation.moment(e.getBehaviorId()));
            if (!candidate.equals("fallback_idle") && e.getAnimationId().equals(Moments.clip(candidate))) id = candidate;
            else if (state.reactionTicks() > 0) {
                candidate = Moments.select("reactions", state.reactionEvent(), "fallback_idle");
                if (!candidate.equals("fallback_idle") && e.getAnimationId().equals(Moments.clip(candidate))) id = candidate;
            }
        }
        boolean changed = !id.equals(e.getMomentId());
        e.setMoment(id);
        if (id.isEmpty()) return;
        long start = e.getAnimationStartTick(), now = e.level().getGameTime();
        var frame = Moments.frame(id, (now-start)/20f);
        var previous = Moments.frame(id, (now-start-1)/20f);
        if (changed || frame.index() != previous.index() || frame.start() != previous.start())
            MomentEffects.start(e, id, frame);
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
        if(court.getString("Phase").equals("CONTACT")&&e.getBehaviorId().equals(Behaviors.PAIRING)) {
            float progress=(e.level().getGameTime()-court.getLong("Start"))/(float)court.getInt("Required");
            return AdultFaces.face(court.getString("Role").equals("PENI")?"pair_peni":"pair_mamu",progress);
        }
        // Agitation already published these faces. The generic idle/tremble clip must not replace them.
        switch(e.getBehaviorId()) {
            case Behaviors.HEAT_RELIEF, Behaviors.ALICE_ACT, Behaviors.RAPEM_HELD -> { return e.getExpressionId(); }
        }
        // Stem drain: tears first, then the dry face's own tired (2) and closed (3) lids on a plain face.
        if(e.aftermathDrain()>0&&(e.getBehaviorId().equals(Behaviors.IDLE)||e.getBehaviorId().equals(Behaviors.TEND_BROOD)))
            return e.aftermathDrain()==1?"teary":"neutral";
        String clip=e.getAnimationId();long start=e.getAnimationStartTick();
        switch(e.getBehaviorId()){
            case Behaviors.TOILET,Behaviors.GROUND_TOILET,Behaviors.BOAT_TOILET,Behaviors.TOILET_ACCIDENT,
                 Behaviors.FIRST_TOILET_ASSIST,Behaviors.FIRST_TOILET_WAIT,Behaviors.FIRST_TOILET_STIMULATED->{clip=e.getBehaviorId();start=e.getBehaviorStartTick();}
        }
        if (!e.getMomentId().isEmpty() && clip.equals(e.getAnimationId())) {
            String selected = Moments.face(e.getMomentId(), (e.level().getGameTime()-e.getAnimationStartTick())/20f, "");
            if (!selected.isEmpty()) return EcologyPresentation.expression(e, selected);
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
        tag.putString("Moment", state.owner().getMomentId());
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
            // A running water action (righting, climbing) shows its own clip from its phase; otherwise the distress phase's clip.
            var pose = actionPose(e);
            state.animation(pose != null ? pose.action() : EcologyPresentation.action(distress));
            state.animationStart(pose != null ? pose.startTick() : e.waterDistressStartTick());
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
            var pose = actionPose(e);
            state.animation(pose != null ? pose.action() : m.distanceSquared() > .000001 && !e.agitation().poseLocked()
                    ? YukkuriAction.WADDLE : e.agitation().action());
            if (pose != null) state.animationStart(pose.startTick());
            state.expression(e.agitation().expression());
            return;
        });
    }

    /** One common action/start/expression tail for the selected ordinary behavior. */
    private static void ordinary(PresentationAccess state, Motion m, String behavior, Actions actions) {
        var e = state.owner();
        // Priority: command clip, then the running motor action's pose, then the behavior-derived clips.
        var pose = state.requestedAction() == null ? actionPose(e) : null;
        if (state.requestedAction() == null && e.isPassenger() && e.social().greeting()
                && (e.motor().current() == null || e.motor().current().interruptible()))
            pose = new ActionPose(YukkuriAction.GREET, e.social().greetStart());
        YukkuriAction action = state.requestedAction() != null ? state.requestedAction()
                : pose != null ? pose.action()
                : e.getVehicle() instanceof PlacedHatEntity placed ? hatRiderAction(state, placed, behavior)
                : e.isInWaterOrBubble() ? YukkuriAction.WATER_STRUGGLE
                : m.distanceSquared() > 0.000001 && e.onGround() && !e.life().isSleeping()
                ? YukkuriAction.WADDLE
                : actions == Actions.GROUP ? EcologyPresentation.group(behavior)
                : stationaryAction(state, behavior, actions);
        String flightAnimation = state.requestedAction() == null && pose == null ? predatorAnimation(e, actions) : null;
        if (flightAnimation != null) {
            // The old raw flight write has the reverse order of setAnimation: ID, then start.
            if (!e.getAnimationId().equals(flightAnimation)) { state.animationId(flightAnimation); state.animationStart(e.level().getGameTime()); }
        } else {
            state.animation(action);
            if (pose != null) state.animationStart(pose.startTick());
            else if(behavior.equals(Behaviors.PAIRING_ACCEPT)&&action==YukkuriAction.GREET
                    ||behavior.equals(Behaviors.PAIRING)&&action==YukkuriAction.NUZZLE) {
                long start=e.family().courtAnimationStart();
                if(start>=0&&e.getAnimationStartTick()!=start)state.animationStart(start);
            }
            // A pressed pair samples one clip clock, so the two shiver in step.
            else if(action==YukkuriAction.RAIN_HUDDLE_LEFT||action==YukkuriAction.RAIN_HUDDLE_RIGHT) {
                long start=e.status().reactions().weather().huddleClock();
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
        // Social/daily requests observe an executed contact. They never create a command goal or own movement.
        if (state.requestedAction() == null && pose == null) {
            boolean contactGoal = behavior.equals(Behaviors.PEER) || behavior.equals(Behaviors.SEEK_AFFECTION)
                    || behavior.equals(Behaviors.NUZZLE) || behavior.equals(Behaviors.SING)
                    || behavior.equals(Behaviors.LISTEN_SONG) || behavior.equals(Behaviors.EXERCISE_WATCH)
                    || behavior.equals(Behaviors.UNUN_EXERCISE);
            if (contactGoal && e.social().greeting()) {
                state.animationId(e.social().greetingClip());
                state.animationStart(e.social().greetStart());
            } else if (behavior.equals(Behaviors.NUZZLE) && e.dailyLife().contactClip() != null) {
                String clip = e.dailyLife().contactClip();
                state.animationId(clip);
                state.animationStart(e.level().getGameTime() - e.dailyLife().observedContactTicks());
            }
        }
        // EcologyPresentation.expression reads this new behavior/start; keep fallback evaluation here.
        state.behavior(behavior);
        state.expression("predator_bite".equals(flightAnimation) ? "biting" : EcologyPresentation.expression(e,
                state.reactionTicks() > 0 ? state.reactionExpression() : e.body().dying() ? "blank_cry" : e.mind().face(e.body().weak() ||
                        e.settlement().society().dejected() ? "teary" : "neutral")));
    }

    /**
     * A young riding the placed catch hat: real sleep and meals win over a lingering newborn reaction, then cradle or brim by
     * where its feet are actually supported. The hat's owner never rides a cradle.
     */
    private static YukkuriAction hatRiderAction(PresentationAccess state, PlacedHatEntity hat, String behavior) {
        var e = state.owner();
        // A supported mother's delivery keeps the same clip and phase as on land; riding only supplies the hull tilt.
        if (behavior.equals(Behaviors.LABOR)) return e.family().cabinBirth() ? YukkuriAction.BOAT_LABOR : YukkuriAction.LABOR;
        if (behavior.equals(Behaviors.POSTPARTUM)) return EcologyPresentation.action(behavior);
        if (behavior.equals(Behaviors.HAT_WALK)) return EcologyPresentation.action(behavior);
        if (e.life().isSleeping()) return YukkuriAction.SLEEP;
        if (e.life().mealTicks() > 0) return EcologyPresentation.action(behavior);
        // Crawling, hopping and the first-toilet beats keep their own clips inside the hat.
        if (behavior.equals(Behaviors.NEWBORN_CRAWL) || behavior.equals(Behaviors.LEARN_JUMP) || behavior.equals(Behaviors.BOAT_TOILET)
                || behavior.equals(Behaviors.FIRST_TOILET_WAIT) || behavior.equals(Behaviors.FIRST_TOILET_STIMULATED))
            return EcologyPresentation.action(behavior);
        // Its own reaction keeps its own clip aboard: startled by the rocking hull, afraid, relieved after a rescue, a
        // newborn's first wake. Only a reaction with a real clip replaces the seated pose.
        if (state.reactionTicks() > 0) {
            YukkuriAction reaction = EcologyPresentation.reaction(state.reactionEvent());
            // Seated, the land puff would sink the braid into the hull: the seated puff keeps it at its boat rest.
            if (reaction == YukkuriAction.PUFF) reaction = YukkuriAction.BOAT_PUFF;
            if (reaction != YukkuriAction.IDLE) return reaction;
        }
        if (onBrim(hat, e)) return YukkuriAction.HAT_BRIM;
        return YukkuriAction.HAT_CRADLE;
    }
    /** The rider's feet are supported at the brim's top edge rather than down in the cavity. */
    public static boolean onBrim(PlacedHatEntity hat, YukkuriEntity e) {
        var support = hat.support(hat.frame().toLocal(e.position()), e);
        return support.isPresent() && support.getAsDouble() >= hat.profile().rimY() - 1e-6;
    }

    /** The running motor action's own clip as a known action; the single source of action clips and phases. */
    private record ActionPose(YukkuriAction action, long startTick) {}
    private static ActionPose actionPose(YukkuriEntity e) {
        var current = e.motor().current();
        if (current == null) return null;
        var pose = current.pose();
        return YukkuriAction.byId(pose.clip()).map(a -> new ActionPose(a, pose.startTick())).orElse(null);
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
        if(e.aftermathDrain()==3&&(behavior.equals(Behaviors.IDLE)||behavior.equals(Behaviors.TEND_BROOD)))return YukkuriAction.DROOP;
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
