package io.github.longshou.mcyukkuri.pet.rules;

import java.util.Locale;
import java.util.UUID;

public enum HouseRule {
    NO_SUKKIRI;

    public String key() { return name().toLowerCase(Locale.ROOT); }
    public int bit() { return 1 << ordinal(); }

    public record Instruction(UUID owner, int version) {
        public boolean known(UUID current, int revision) {
            return current != null && (revision == 0 || current.equals(owner) && version == revision);
        }
    }
}
