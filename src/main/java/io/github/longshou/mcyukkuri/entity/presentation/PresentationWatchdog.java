package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.Map;

/**
 * The presentation watchdog of the aquatic module (ecology design D 0.3, project design). Every tick the arbiter publishes the
 * clip of the source that owns the body now (a transfer ticket, an incident, an activity or the resting state); this checks that
 * publication: a takeover whose owning ticket is gone or has not been driven for {@link #HEARTBEAT} ticks shows the state's base
 * pose instead, and a clip or face the model does not ship falls back through {@link #CLIPS} / {@link #FACES} with a rate-limited
 * {@code [aquatic]} log. The client only samples the published clip and contacts; it never chooses or extends one.
 */
final class PresentationWatchdog {
    /** A looping clip needs its owner's heartbeat this often (ticks). */
    static final int HEARTBEAT = 40;
    /** Missing clip -> the next simpler clip of the same meaning (the last step is the state's base pose). */
    private static final Map<String, String> CLIPS = Map.ofEntries(
            Map.entry("boat_rim_peek", "hat_cradle"), Map.entry("boat_nest_curl", "sleep"), Map.entry("boat_cover_young", "boat_sleep"),
            Map.entry("boat_sleep", "boat_drift"), Map.entry("be_cradled", "hat_cradle"), Map.entry("braid_cradle_lift", "braid_reach_low"),
            Map.entry("mouth_bite_hold", "water_mouth_grip"), Map.entry("water_hat_right", "water_hat_flip"), Map.entry("boat_thrown", "hat_stumble"),
            Map.entry("boat_wedge_berth", "boat_steady"), Map.entry("boat_push_off", "boat_steady"), Map.entry("boat_cargo_stow", "boat_gather"),
            Map.entry("boat_cargo_fetch", "boat_eat"), Map.entry("boat_jettison", "boat_steady"), Map.entry("boat_brood", "boat_drift"),
            Map.entry("boat_paddle", "boat_steady"), Map.entry("hat_brim", "hat_cradle"), Map.entry("boat_drift", "boat_steady"));
    /** Missing face -> the nearest authored face (ecology design D 3.8). */
    private static final Map<String, String> FACES = Map.of("sad", "teary", "afraid", "surprise", "biting", "angry", "sleep", "sleepy");

    private final PresentationAccess state;

    PresentationWatchdog(PresentationAccess state) { this.state = state; }

    /** The resting pose of the body's current state: drifting captain, steadied rider, otherwise idle. */
    static String basePose(YukkuriEntity e) {
        if (e.getVehicle() instanceof HatBoatEntity boat)
            return e.getUUID().equals(boat.ownerYukkuri()) ? (boat.isAfloat() ? "boat_drift" : "boat_steady") : "hat_cradle";
        return "idle";
    }

    /**
     * A takeover without its ticket: the owner ended or stalled, so the body goes back to its base pose at once
     * (its contacts already vanished with the ticket) and the reason is logged.
     */
    void expire(String source, String reason) {
        var e = state.owner();
        String base = basePose(e), clip = e.getAnimationId();
        state.expression("neutral");
        if (clip.equals(base)) return;
        state.animationId(base);
        state.animationStart(e.level().getGameTime());
        GameplayLog.limited(e, "aquatic", "presentation_expired:" + source, 200,
                "presentation expired source={} reason={} clip={} fallback={}", source, reason, clip, base);
    }

    /** Check what was just published; aboard a hat boat or aquatic, an unknown clip or face falls back. */
    void check(String source) {
        var e = state.owner();
        if (!(e.getVehicle() instanceof HatBoatEntity) && !e.profile().aquatic() && e.family().crew().ticket() == null) return;
        String asset = e.assetId();
        String clip = e.getAnimationId();
        if (!PresentationCatalog.hasClip(asset, clip)) {
            String to = clip;
            for (int i = 0; i < 4 && !PresentationCatalog.hasClip(asset, to); i++) to = CLIPS.getOrDefault(to, basePose(e));
            if (!PresentationCatalog.hasClip(asset, to)) to = "idle";
            state.animationId(to);
            GameplayLog.limited(e, "aquatic", "clip_fallback:" + clip, 600, "clip fallback source={} asset={} id={} to={}", source, asset, clip, to);
        }
        String face = e.getExpressionId();
        if (!face.isEmpty() && !PresentationCatalog.hasFace(asset, face)) {
            String to = FACES.getOrDefault(face, "neutral");
            if (!PresentationCatalog.hasFace(asset, to)) to = "neutral";
            state.expression(to);
            GameplayLog.limited(e, "aquatic", "expression_fallback:" + face, 600, "expression fallback source={} asset={} id={} to={}", source, asset, face, to);
        }
    }
}
