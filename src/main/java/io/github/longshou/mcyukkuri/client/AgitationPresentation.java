package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** Visible, non-contact adult state cues. The server owns phases, movement and consequences. */
final class AgitationPresentation {
    private AgitationPresentation() {}
    static final ResourceManagerReloadListener RELOAD = AgitationPresentation::reload;
    private static final int TRANSITION_TICKS = 12, HISTORY_TICKS = 20;
    private static final String[] CHANNELS = {"pitch", "yaw", "roll", "sx", "sy", "sz"};
    /** Standard faces selected for the act (provenance-checked standard expressions). */
    static final String HUNTER_FACE = "open_drool", ACT_FACE = "flushed_tongue", VICTIM_FACE = "squeezed_cry_tears";
    /** Species whose runtime folder carries the standard victim face; others keep the stock cry. */
    private static final Set<String> VICTIM_FACE_IDS =
            Set.of("reimu", "marisa", "alice", "chen", "patchouli");
    private static Map<String, float[][]> cues = Map.of();
    private static float mamuRearOffset;
    // Values never strongly hold their key or level. Unloaded entities/worlds cannot be retained by a pose.
    private static final Map<YukkuriView, DisplayedPose> displayed = new WeakHashMap<>();
    private static WeakReference<Level> displayLevel = new WeakReference<>(null);
    private static long lastPruneTick = Long.MIN_VALUE;

    private static final class DisplayedPose {
        final WeakReference<YukkuriView> owner;
        final String phase;
        final long start;
        final double transitionStart;
        float[] from, value;
        long lastSeen;

        DisplayedPose(YukkuriView entity, String phase, long start, double now, float[] from) {
            this.owner = new WeakReference<>(entity);
            this.phase = phase;
            this.start = start;
            this.transitionStart = now;
            this.from = from == null ? null : from.clone();
        }
    }

    static void reload(ResourceManager resources) {
        displayed.clear();
        displayLevel.clear();
        lastPruneTick = Long.MIN_VALUE;
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/adult_states.json")).openAsReader()) {
            var root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Invalid adult-state presentation");
            var data = root.getAsJsonObject();
            if (!number(data.get("mamu_rear_offset")) || data.get("mamu_rear_offset").getAsDouble() <= 0
                    || data.get("mamu_rear_offset").getAsDouble() > 4)
                throw new IllegalArgumentException("Invalid reproductive mamu rear offset");
            mamuRearOffset=data.get("mamu_rear_offset").getAsFloat();
            if (!number(data.get("format")) || data.get("format").getAsDouble() != 1)
                throw new IllegalArgumentException("Unsupported adult-state presentation");
            if (!data.has("channels") || !data.get("channels").isJsonArray())
                throw new IllegalArgumentException("Missing adult-state channels");
            var channels = data.getAsJsonArray("channels");
            if (channels.size() != CHANNELS.length) throw new IllegalArgumentException("Invalid adult-state channels");
            for (int k = 0; k < CHANNELS.length; k++) {
                var channel = channels.get(k);
                if (!channel.isJsonPrimitive() || !channel.getAsJsonPrimitive().isString()
                        || !CHANNELS[k].equals(channel.getAsString()))
                    throw new IllegalArgumentException("Unexpected adult-state channel order");
            }
            var loaded = new HashMap<String, float[][]>();
            for (String branch : new String[]{"heat", "relief", "act", "pinned", "rapem", "pair_peni", "pair_mamu"}) {
                if (!data.has(branch) || !data.get(branch).isJsonArray())
                    throw new IllegalArgumentException("Missing adult-state keyframes: " + branch);
                var keys = data.getAsJsonArray(branch);
                if (keys.size() < 2 || keys.size() > 1024)
                    throw new IllegalArgumentException("Invalid adult-state keyframe count: " + branch);
                float[][] frames = new float[keys.size()][7];
                for (int i = 0; i < frames.length; i++) {
                    if (!keys.get(i).isJsonArray()) throw new IllegalArgumentException("Invalid adult-state keyframe");
                    var key = keys.get(i).getAsJsonArray();
                    if (key.size() != 7) throw new IllegalArgumentException("Invalid adult-state keyframe");
                    for (int k = 0; k < 7; k++) {
                        if (!number(key.get(k))) throw new IllegalArgumentException("Nonnumeric adult-state keyframe");
                        double value = key.get(k).getAsDouble();
                        // This cue format supports bounded angles and positive, nonsingular scales.
                        double min = k == 0 ? 0 : k <= 3 ? -180 : .25;
                        double max = k == 0 ? 1 : k <= 3 ? 180 : 4;
                        if (!Double.isFinite(value) || value < min || value > max)
                            throw new IllegalArgumentException("Out-of-range adult-state keyframe");
                        frames[i][k] = (float) value;
                    }
                    // Check the stored float times too: distinct JSON numbers can round to the same time.
                    if (i > 0 && frames[i][0] <= frames[i-1][0])
                        throw new IllegalArgumentException("Unordered adult-state keyframes");
                }
                if (keys.get(0).getAsJsonArray().get(0).getAsDouble() != 0
                        || keys.get(keys.size()-1).getAsJsonArray().get(0).getAsDouble() != 1)
                    throw new IllegalArgumentException("Adult-state keyframes must span 0 through 1");
                if (branch.equals("heat")) for (int k = 1; k < 7; k++) {
                    if (frames[0][k] != frames[frames.length-1][k])
                        throw new IllegalArgumentException("Breeding-period attention loop must close");
                }
                loaded.put(branch, frames);
            }
            cues = Map.copyOf(loaded);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static boolean number(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    static boolean selected(YukkuriView entity) {
        boolean selected = matchesPresentation(entity);
        if (!selected) displayed.remove(entity);
        return selected;
    }

    private static boolean matchesPresentation(YukkuriView entity) {
        if (entity.isBaby() || entity.isDeadOrDying() || entity.isPassenger()
                || entity.isRemoved() || !entity.contactPose().equals("NONE")) return false;
        String phase = entity.agitationState().getString("Phase");
        // Preserve the real walking clip even on the boundary tick when navigation has just stopped.
        if ((phase.equals("HEAT") || phase.equals("HEAT_RELIEVED") || phase.equals("HEAT_UNMET") || phase.equals("RECOVER"))
                && entity.getAnimationId().equals("waddle")) return false;
        String behavior = switch (phase) {
            case "HEAT" -> Behaviors.BREEDING_PERIOD;
            case "HEAT_RELIEVED" -> Behaviors.HEAT_RELIEF;
            case "HEAT_UNMET" -> Behaviors.HEAT_UNMET;
            case "RECOVER" -> Behaviors.AGITATION_RECOVER;
            case "ALICE_WATCH" -> Behaviors.ALICE_WATCH;
            case "ALICE_APPROACH" -> Behaviors.ALICE_APPROACH;
            case "ALICE_ACT" -> Behaviors.ALICE_ACT;
            case "HELD" -> Behaviors.RAPEM_HELD;
            default -> "";
        };
        return !behavior.isEmpty() && behavior.equals(entity.getBehaviorId());
    }

    /** Keep the same face on the texture, facial geometry and wound surface. */
    static String expression(YukkuriView entity, String otherwise) {
        if (selected(entity)) {
            String phase = entity.agitationState().getString("Phase");
            // Standard confirmed faces: the hunter drools open-eyed through the hunt and switches
            // to the flushed tongue-out face in the act; the pinned body wails with squeezed tears
            // (stock cry where that species has no standard atlas).
            String id = entity.assetId();
            return switch (phase) {
                case "HEAT_RELIEVED" -> "satisfied";
                case "HEAT_UNMET", "RECOVER" -> "sleepy";
                case "ALICE_WATCH", "ALICE_APPROACH" -> HUNTER_FACE;
                case "ALICE_ACT" -> ACT_FACE;
                case "HELD" -> VICTIM_FACE_IDS.contains(id) ? VICTIM_FACE : "cry";
                default -> otherwise;
            };
        }
        String phase2 = entity.agitationState().getString("Phase");
        if (phase2.equals("HELD")) {
            String id = entity.assetId();
            return VICTIM_FACE_IDS.contains(id) ? VICTIM_FACE : "cry";
        }
        // Aftermath is visible during quiet rest and brooding. Urgent reactions and ordinary
        // eating, sleep, handling and family interactions keep their own expressions.
        String behavior = entity.getBehaviorId(), action = entity.getAnimationId();
        if (entity.aftermathBurden() && (behavior.equals(Behaviors.IDLE) || behavior.equals(Behaviors.TEND_BROOD))
                && (action.equals("idle") || action.equals("brood") || entity.aftermathWeak() && action.equals("droop"))
                && !Set.of("cry", "blank_cry", "surprise").contains(entity.getExpressionId()))
            return "teary";
        return otherwise;
    }

    static void pose(YukkuriView entity, float partialTick, PoseStack pose) {
        long gameTick = entity.level().getGameTime();
        prune(entity, gameTick);
        if (!selected(entity)) return;
        double now = (double) gameTick + (Float.isFinite(partialTick) ? Mth.clamp(partialTick, 0, 1) : 0);
        long start = entity.agitationState().getLong("Start");
        float ticks = (float) Math.max(0, now - start);
        String phase = entity.agitationState().getString("Phase");
        float progress = switch (phase) {
            // A quiet attention loop; only the server's family timer can end the period.
            case "HEAT" -> ticks % 160 / 160;
            // One-shot staged bounce across 120 ticks, then the unmet droop shares the recovery curve.
            case "HEAT_RELIEVED" -> Mth.clamp(ticks / 120, 0, 1);
            case "HEAT_UNMET", "RECOVER" -> .68f + .32f * Mth.clamp(ticks / 100, 0, 1);
            case "ALICE_WATCH" -> .3f * Mth.clamp(ticks / 60, 0, 1);
            case "ALICE_APPROACH" -> .3f + .24f * Mth.clamp(ticks / 160, 0, 1);
            // The act thrusts once per second (1.12 rubs every ~30 frames); the pinned victim
            // bobs on the same clock, pressed forward under each thrust.
            case "ALICE_ACT", "HELD" -> ticks % 20 / 20f;
            default -> 1;
        };
        float[][] keys = cues.get(phase.equals("HEAT") ? "heat" : phase.equals("HEAT_RELIEVED") ? "relief"
                : phase.equals("ALICE_ACT") ? "act" : phase.equals("HELD") ? "pinned" : "rapem");
        if (keys == null) { displayed.remove(entity); return; }
        int i = 0;
        while (i + 2 < keys.length && progress > keys[i+1][0]) i++;
        float[] a = keys[i], b = keys[i+1], value = new float[6];
        float mix = Mth.clamp((progress-a[0])/(b[0]-a[0]), 0, 1);
        mix = smooth(mix);
        for (int k = 0; k < 6; k++) value[k] = Mth.lerp(mix, a[k+1], b[k+1]);

        var previous = displayed.get(entity);
        // Entity equality may be based on a recycled numeric ID; a replacement has no visual history.
        if (previous != null && previous.owner.get() != entity) {
            displayed.remove(entity);
            previous = null;
        }
        if (previous == null || !previous.phase.equals(phase) || previous.start != start) {
            // Sample the current phase from the server's Start, but bridge from what was actually
            // seen. A late packet must not skip the bridge; a newly visible entity has no bridge.
            previous = new DisplayedPose(entity, phase, start, now, previous == null ? null : previous.value);
            displayed.put(entity, previous);
        }
        if (previous.from != null) {
            float blend = smooth(Mth.clamp((float) ((now-previous.transitionStart)/TRANSITION_TICKS), 0, 1));
            for (int k = 0; k < 6; k++) value[k] = Mth.lerp(blend, previous.from[k], value[k]);
            if (blend >= 1) previous.from = null;
        }
        // The fixed 'from' is never overwritten during a bridge. Repeated render passes at the
        // same time therefore produce the same pose instead of advancing a frame-based filter.
        previous.value = value.clone();
        previous.lastSeen = gameTick;
        // All attached presentation shares the transform. Only grounding can translate it;
        // actual approach and retreat remain exclusively server navigation.
        var model = YukkuriModel.get(entity.assetId());
        pose.translate(0, model.presentationLift(value, entity.hasVisibleAccessory(), entity.visibleHairLoss()) * entity.growthScale(), 0);
        pose.mulPose(Axis.ZP.rotationDegrees(value[2]));
        pose.mulPose(Axis.YP.rotationDegrees(value[1]));
        pose.mulPose(Axis.XP.rotationDegrees(value[0]));
        pose.scale(value[3], value[4], value[5]);
    }

    static float mamuRearOffset() { return mamuRearOffset; }

    static float[] sample(String branch,float progress) {
        float[][] keys=cues.get(branch);if(keys==null)return null;
        int i=0;while(i+2<keys.length&&progress>keys[i+1][0])i++;
        float[] a=keys[i],b=keys[i+1],value=new float[6];
        float mix=Mth.clamp((progress-a[0])/(b[0]-a[0]),0,1);mix=smooth(mix);
        for(int k=0;k<6;k++)value[k]=Mth.lerp(mix,a[k+1],b[k+1]);
        return value;
    }

    private static float smooth(float mix) { return mix*mix*(3-2*mix); }

    private static void prune(YukkuriView entity, long tick) {
        if (displayLevel.get() != entity.level()) {
            displayed.clear();
            displayLevel = new WeakReference<>(entity.level());
            lastPruneTick = Long.MIN_VALUE;
        }
        if (lastPruneTick == tick) return;
        // Render callbacks cannot observe culled entities. Expire their history by game time
        // before any later reuse, and discard interrupted states even while they are offscreen.
        displayed.entrySet().removeIf(entry -> {
            var other = entry.getKey();
            long seen = entry.getValue().lastSeen;
            return other.level() != entity.level() || !matchesPresentation(other)
                    || tick < seen || (double) tick - seen > HISTORY_TICKS;
        });
        lastPruneTick = tick;
    }
}
