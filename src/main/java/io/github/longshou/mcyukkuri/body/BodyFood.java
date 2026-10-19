package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.nbt.CompoundTag;

/** Food from a real bite; no item, independent live-body balance or second health bar. */
public final class BodyFood {
    private BodyFood() {}

    public static float capacity(YukkuriEntity source) {
        float portions = switch (source.family().stage()) {
            case BABY -> 1;
            case JUVENILE -> 3;
            case ADULT -> 6;
        };
        return portions * source.profile().portionScale();
    }

    public static Bite live(YukkuriEntity source, float actualLoss) {
        if (!Float.isFinite(actualLoss) || actualLoss <= 0 || source.getMaxHealth() <= 0) return Bite.EMPTY;
        float portions = capacity(source) * Math.min(actualLoss / source.getMaxHealth(), 1);
        boolean foul = source.mind().foulFilling();
        float perPortion = foul ? 3 : source.mind().fillingSweetness() >= 85 ? 6 : 5;
        return new Bite(portions, portions * perPortion, foul ? 90 : 0, foul ? "foul" : "fresh");
    }

    /** Nutrition is the total delayed reward for this mouthful, not a per-portion rate. */
    public record Bite(float portions, float nutrition, int odor, String taste) {
        public static final Bite EMPTY = new Bite(0, 0, 0, "");

        public Bite {
            portions = Float.isFinite(portions) ? Math.max(0, portions) : 0;
            nutrition = Float.isFinite(nutrition) ? Math.max(0, nutrition) : 0;
            odor = Math.clamp(odor, 0, 100);
            taste = taste == null ? "" : taste;
            if (portions == 0) nutrition = 0;
        }

        public boolean isEmpty() { return portions <= 0; }

        public CompoundTag save() {
            var tag = new CompoundTag();
            tag.putFloat("Portions", portions);
            tag.putFloat("Nutrition", nutrition);
            tag.putInt("Odor", odor);
            tag.putString("Taste", taste);
            return tag;
        }

        public static Bite load(CompoundTag tag) {
            return new Bite(tag.getFloat("Portions"), tag.getFloat("Nutrition"),
                    tag.getInt("Odor"), tag.getString("Taste"));
        }
    }
}
