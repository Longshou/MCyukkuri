package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Short owner-facing performances of a pet (badge pinned/taken, held-back courtship). Server only, calm animals only, cooldown per animal. */
public final class PetScenes {
    private record SceneKey(UUID animal, String kind) {}
    private static final DeadlineMap<SceneKey> COOLDOWN = new DeadlineMap<>();
    private PetScenes() {}

    /** True once per cooldown window for this animal and kind of scene. */
    public static boolean ready(YukkuriEntity y, String kind, int ticks) {
        long now = y.level().getGameTime(); var key = new SceneKey(y.getUUID(), kind);
        if (COOLDOWN.active(key, now)) return false;
        if (COOLDOWN.size() > 256) { COOLDOWN.prune(now); if (COOLDOWN.size() > 2048) COOLDOWN.clear(); }
        COOLDOWN.put(key, now + ticks); return true;
    }
    public static void reset() { COOLDOWN.clear(); }
    public static void forget(Set<UUID> ids) {
        for (var key : COOLDOWN.snapshot().keySet()) if (ids.contains(key.animal)) COOLDOWN.remove(key);
    }

    /** Ordinary reaction beats for a performer that already holds a duty. */
    public static boolean act(YukkuriEntity y, YukkuriAction action, String face, String event, int ticks) {
        return act(y, action, face, event, ticks, null);
    }

    /** True when the face and clip were taken (a more urgent reaction can refuse them). */
    public static boolean act(YukkuriEntity y, YukkuriAction action, String face, String event, int ticks, Entity look) {
        if (look != null) y.getLookControl().setLookAt(look, 30, 30);
        if (!y.showReaction(action, face, "", ticks, FaceTier.Tier.ofTicks(ticks))) return false;
        if (!event.isEmpty()) y.speakCue(BadgeSocial.reactionLine(y, event, look));
        return true;
    }

    /** Report only intelligible words actually delivered, including when a speaker can only babble. */
    public static boolean say(YukkuriEntity y, YukkuriAction action, String face, String event, Entity look) {
        if (look != null) y.getLookControl().setLookAt(look, 30, 30);
        if (!y.showReaction(action, face, "", action.durationTicks(), FaceTier.Tier.ofTicks(action.durationTicks()))
                || !y.speakCue(BadgeSocial.reactionLine(y, event, look), null, 2)) return false;
        var line = NativeDialogue.byKey(y.getDialogueKey());
        return line != null && line.events().contains(event);
    }

    /** Action + face + line in one go; a looping action is ended after ticks, and the animal keeps glancing at look (may be null) meanwhile. */
    public static void act(YukkuriEntity y, String action, String face, String event, int ticks, Entity look) {
        if (!(y.level() instanceof ServerLevel level) || !y.isAlive()) return;
        if (look != null) y.getLookControl().setLookAt(look, 30, 30);
        boolean played = action != null && y.playAction(action);
        if (face != null) y.showReaction(face, event == null ? "" : BadgeSocial.reactionLine(y, event, look), FaceTier.Tier.ofTicks(ticks));
        long start = y.getAnimationStartTick();
        boolean loops = played && YukkuriAction.byId(action).map(YukkuriAction::loops).orElse(false);
        if (!loops && look == null) return;
        ShopPerformance.schedule(level, look == null ? Set.of(y.getUUID()) : new HashSet<>(List.of(y.getUUID(), look.getUUID())), age -> {
            if (!y.isAlive()) return true;
            if (look != null && look.isAlive() && age % 10 == 0 && age < ticks) y.getLookControl().setLookAt(look, 30, 30);
            if (age < ticks) return false;
            if (loops && y.isCommandPlaying() && y.getAnimationStartTick() == start) y.playAction("idle");
            return true;
        }, () -> {
            if (loops && y.isAlive() && !y.isRemoved() && y.isCommandPlaying() && y.getAnimationStartTick() == start) y.playAction("idle");
        });
    }
    /** Run later on the server thread (transient: dropped when the world stops). */
    public static void later(YukkuriEntity owner, int ticks, Runnable run) {
        if (ticks <= 0) { run.run(); return; }
        var level = (ServerLevel) owner.level();
        ShopPerformance.schedule(level, owner.getUUID(), age -> { if (age < ticks) return false; run.run(); return true; });
    }

    /** The owner pins the badge on: bold ones swagger, the rest hop or bow. */
    public static void pinned(YukkuriEntity y, Player owner) {
        if (!y.pet().calm() || !ready(y, "pin", 100)) return;
        var bond = y.life().personality().relation(owner.getUUID());
        double confidence = SocialDisposition.confidence(SocialDisposition.selfEsteem(y));
        if (confidence * (1 - SocialDisposition.wariness(y, bond)) >= .55) act(y, "proud", "smug", "badge_pinned", 70, owner);
        else act(y, y.getRandom().nextBoolean() ? "joy_jump" : "bow", "happy", "badge_pinned", 70, owner);
    }
    /** Complete stat-driven confiscation performance (cause -> reaction -> ending) for own pets and strays. */
    public static void confiscated(YukkuriEntity y, LivingEntity actor, boolean ownedPet) {
        if (!(y.level() instanceof ServerLevel) || !y.isAlive() || y.isRemoved() || y.hurtTime > 0 || y.isPanicking()) return;
        var p = y.life().personality();
        var bond = p.relation(actor == null ? null : actor.getUUID());
        int fear = bond.fear();
        double wariness = SocialDisposition.wariness(y, bond);
        double esteem = SocialDisposition.selfEsteem(y);
        int boldness = p.boldness();
        int obedience = y.pet().obedience();
        String style;
        String reactAction, reactFace, reactLine;
        String endAction, endFace, endLine;
        boolean stepAway = false;
        if (ownedPet) {
            if (fear >= 30 || wariness >= .45) {
                style = "fearful";
                reactAction = "tremble"; reactFace = "afraid"; reactLine = "plea_spare";
                endAction = "thunder_cower"; endFace = "horrified"; endLine = "";
            } else if (boldness >= 60 && obedience < 50) {
                style = "proud";
                reactAction = "puff"; reactFace = "cheek_puff"; reactLine = "badge_taken";
                endAction = "sulk"; endFace = "pursed"; endLine = "";
            } else {
                style = "attached";
                reactAction = "beg"; reactFace = "teary"; reactLine = "badge_taken";
                endAction = "droop"; endFace = "ashamed"; endLine = "";
            }
        } else {
            double insistence = SocialDisposition.insistence(p, esteem, wariness, bond);
            if ((insistence >= .2 || boldness >= 55) && fear < 45) {
                style = "stray_cling";
                reactAction = "puff"; reactFace = "cheek_puff"; reactLine = "badge_taken";
                endAction = "sulk"; endFace = "angry_stare"; endLine = "";
                stepAway = true;
            } else {
                style = "stray_run";
                reactAction = "droop"; reactFace = "open_cry"; reactLine = "badge_taken";
                endAction = "droop"; endFace = "afraid"; endLine = "afraid";
                stepAway = true;
            }
        }
        // Beat 1 (Cause): brief startle on the moment the badge is taken.
        act(y, "startle", "surprise", "", 14, actor);
        y.pet().expectBeat("confiscate_cause", "surprise", "none", style);
        final String finalStyle = style;
        final String fReactAction = reactAction, fReactFace = reactFace, fReactLine = reactLine;
        final String fEndAction = endAction, fEndFace = endFace, fEndLine = endLine;
        final boolean fStepAway = stepAway;
        // Beat 2 (Reaction): stat-driven protest / plead / cower / cry.
        later(y, 12, () -> {
            if (!y.isAlive() || y.isRemoved() || y.hurtTime > 0 || y.isPanicking()) return;
            if (finalStyle.equals("stray_run"))
                y.mind().feeling().begin(EmotionCourse.Kind.GRIEF, .5f, actor == null ? null : actor.getUUID(), y.blockPosition());
            act(y, fReactAction, fReactFace, fReactLine, 45, actor);
            y.pet().expectBeat("confiscate_react", fReactFace, fReactLine, finalStyle);
        });
        // Beat 3 (Ending): sulk / droop / cower, or turn round and walk/run away for a stray.
        later(y, 57, () -> {
            if (!y.isAlive() || y.isRemoved() || y.hurtTime > 0 || y.isPanicking()) return;
            act(y, fEndAction, fEndFace, fEndLine, 40, fStepAway ? null : actor);
            y.pet().expectBeat("confiscate_end", fEndFace, fEndLine, finalStyle);
            if (fStepAway && actor != null && actor.isAlive() && y.distanceToSqr(actor) <= 144) {
                Vec3 away = y.position().subtract(actor.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < .01 ? new Vec3(1, 0, 0) : away.normalize();
                double dist = finalStyle.equals("stray_run") ? 7 : 5;
                for (double f : new double[] {1, .75, .5}) {
                    BlockPos goal = BlockPos.containing(y.position().add(away.scale(dist * f)));
                    if (y.life().dryStand(goal) && y.life().navigate(goal, finalStyle.equals("stray_run") ? 1.0 : .75, false)) break;
                }
            }
        });
    }

    public static void courtshipHeld(YukkuriEntity y, YukkuriEntity peer) {
        int hesitation = 20 + (int) (30 * y.life().personality().temperament().patient());
        act(y, YukkuriAction.SHY, "flushed_smile", "courtship_held", hesitation, peer);
        later(y, hesitation, () -> {
            if (!y.isAlive() || y.hurtTime > 0 || y.isPanicking()) return;
            var away = y.position().scale(2).subtract(peer.position());
            y.getLookControl().setLookAt(away.x, y.getEyeY(), away.z);
            act(y, YukkuriAction.TURN, "teary", "", 20);
        });
    }
}
