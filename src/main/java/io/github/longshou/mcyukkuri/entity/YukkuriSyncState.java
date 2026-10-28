package io.github.longshou.mcyukkuri.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Immutable values decoded once per synchronized slot replacement. No components or client clocks.
 * Raw CompoundTag compatibility views stay in entityData; these values never merge old fields.
 * Phases remain open strings, and presence is separate from each NBT getter's default value.
 * Component-owned producers keep their original tags (including legacy/unknown fields); only
 * the three entity-owned writers below encode values. Pose fallbacks stay with their consumers.
 */
public final class YukkuriSyncState {
    private YukkuriSyncState() {}

    public static final WaterDistress NO_WATER_DISTRESS = WaterDistress.read(new CompoundTag());

    public record Field<T>(boolean present, T value) {
        public T orElse(T fallback) { return present ? value : fallback; }
        public static <T> Field<T> of(T value) { return new Field<>(true, value); }
    }

    /** Keep each coordinate's presence: legacy readers test X independently of Y/Z. */
    public record Point(Field<Double> x, Field<Double> y, Field<Double> z) {
        static Point read(CompoundTag tag, String prefix) {
            return new Point(decimal(tag, prefix + "X"), decimal(tag, prefix + "Y"), decimal(tag, prefix + "Z"));
        }
    }

    public record Agitation(
            Field<String> phase,
            Field<Long> start,
            Field<Integer> targetId) {
        public static Agitation read(CompoundTag tag) {
            return new Agitation(
                    text(tag, "Phase"),
                    longValue(tag, "Start"),
                    integer(tag, "TargetId"));
        }
    }

    public record WaterDistress(
            Field<String> phase,
            Field<Long> start,
            Field<Boolean> effort) {
        public static WaterDistress read(CompoundTag tag) {
            return new WaterDistress(
                    text(tag, "Phase"),
                    longValue(tag, "Start"),
                    bool(tag, "Effort"));
        }
        public CompoundTag encode() {
            var tag = new CompoundTag();
            if (phase.present()) tag.putString("Phase", phase.value());
            if (start.present()) tag.putLong("Start", start.value());
            if (effort.present()) tag.putBoolean("Effort", effort.value());
            return tag;
        }
    }

    public record LiveChildren(Field<Integer> count, Field<List<Integer>> morphs,
                               Field<Integer> born, Field<Integer> types, Field<List<Integer>> scales) {
        public LiveChildren {
            morphs = new Field<>(morphs.present(), List.copyOf(morphs.value()));
            scales = new Field<>(scales.present(), List.copyOf(scales.value()));
        }
        public static LiveChildren read(CompoundTag tag) {
            return new LiveChildren(integer(tag, "Count"),
                    new Field<>(tag.contains("Morphs"), Arrays.stream(tag.getIntArray("Morphs")).boxed().toList()),
                    integer(tag, "Born"), integer(tag, "Types"),
                    new Field<>(tag.contains("Scales"),Arrays.stream(tag.getIntArray("Scales")).boxed().toList()));
        }
        public static LiveChildren active(int count, List<Integer> morphs, int born, int types,List<Integer> scales) {
            return new LiveChildren(Field.of(count), Field.of(morphs), Field.of(born), Field.of(types),Field.of(scales));
        }
        public static LiveChildren inactive() {
            return new LiveChildren(new Field<>(false, 0), new Field<>(false, List.of()),
                    new Field<>(false, 0), new Field<>(false, 0),new Field<>(false,List.of()));
        }
        public float scaleAt(int index) {
            return index>=0&&index<scales.value().size()?scales.value().get(index)/10000f:0;
        }
        public int morphAt(int index) {
            return index >= 0 && index < morphs.value().size() ? morphs.value().get(index) : 0;
        }
        public CompoundTag encode() {
            var tag = new CompoundTag();
            if (count.present()) tag.putInt("Count", count.value());
            if (morphs.present()) tag.putIntArray("Morphs", morphs.value().stream().mapToInt(Integer::intValue).toArray());
            if (born.present()) tag.putInt("Born", born.value());
            if (types.present()) tag.putInt("Types", types.value());
            if(scales.present())tag.putIntArray("Scales",scales.value().stream().mapToInt(Integer::intValue).toArray());
            return tag;
        }
    }

    private static Field<String> text(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getString(key));
    }
    private static Field<Long> longValue(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getLong(key));
    }
    private static Field<Integer> integer(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getInt(key));
    }
    private static Field<Float> floating(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getFloat(key));
    }
    private static Field<Double> decimal(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getDouble(key));
    }
    private static Field<Boolean> bool(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.getBoolean(key));
    }
    private static Field<Optional<UUID>> uuid(CompoundTag tag, String key) {
        return new Field<>(tag.contains(key), tag.hasUUID(key) ? Optional.of(tag.getUUID(key)) : Optional.empty());
    }
}
