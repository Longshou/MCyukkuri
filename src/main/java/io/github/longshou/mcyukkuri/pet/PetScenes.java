package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Short owner-facing performances of a pet (badge pinned/taken, breeding permit, held-back courtship). Server only, calm animals only, cooldown per animal. */
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

    /** Action + face + line in one go; a looping action is ended after ticks, and the animal keeps glancing at look (may be null) meanwhile. */
    public static void act(YukkuriEntity y, String action, String face, String event, int ticks, Entity look) {
        if (!(y.level() instanceof ServerLevel level) || !y.isAlive()) return;
        if (look != null) y.getLookControl().setLookAt(look, 30, 30);
        boolean played = action != null && y.playAction(action);
        if (face != null) y.showReaction(face, event == null ? "" : event, ticks);
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
    /** The owner takes the badge off. */
    public static void taken(YukkuriEntity y, Player owner) {
        if (!y.pet().calm() || !ready(y, "take", 100)) return;
        act(y, "droop", "teary", "badge_taken", 80, owner);
    }
    /** Staff took the badge off an abandoned pet: it cries. */
    public static void revoked(YukkuriEntity y) {
        if (!y.pet().calm() || !ready(y, "take", 100)) return;
        act(y, "droop", "cry", "badge_taken", 90, null);
    }

    /** Both pets within 16 blocks of the owner look at each other and blush; the second one a beat later. */
    public static void permitGranted(YukkuriEntity a, YukkuriEntity b, Player owner) {
        for (int i = 0; i < 2; i++) {
            var y = i == 0 ? a : b; var other = i == 0 ? b : a;
            if (!(y.level() instanceof ServerLevel level) || y.distanceToSqr(owner) > 256 || !y.pet().calm() || !ready(y, "permit", 200)) continue;
            y.getLookControl().setLookAt(other, 30, 30);
            later(y, i * 8, () -> act(y, "shy", "closed_smile", "permit_glad", 70, other));
        }
    }
    /** The permit is withdrawn: both droop a moment. */
    public static void permitRevoked(YukkuriEntity a, YukkuriEntity b, Player owner) {
        for (var y : new YukkuriEntity[] {a, b}) {
            if (y == null || y.distanceToSqr(owner) > 256 || !y.pet().calm() || !ready(y, "permit", 200)) continue;
            act(y, "droop", "teary", null, 50, null);
        }
    }
    /** Courtship refused by the gate: the pet holds back (shy if it can control itself, otherwise droops); inside the cooldown only the teary face. */
    public static void courtshipHeld(YukkuriEntity courting, YukkuriEntity other) {
        var y = courting.pet().isPet() ? courting : other.pet().isPet() ? other : courting;
        var peer = y == courting ? other : courting;
        if (!y.pet().calm() || !ready(y, "held", 2400)) { y.showReaction("teary", "", 40); return; }
        act(y, y.life().personality().selfControl() >= Temperament.SELF_CONTROL_AT_50 ? "shy" : "droop", "teary", "courtship_held", 70, peer);
    }
}
