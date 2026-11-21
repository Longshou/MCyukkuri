package io.github.longshou.mcyukkuri.pet.rules;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public enum HouseRule {
    NO_SUKKIRI, NO_SINGING, NO_LEAVING_YARD, NO_EATING_UNASKED, NO_BULLYING, NO_TUB_WHEN_HEALTHY, NO_TUB_WHEN_HURT;

    /** The rules a household starts with: the sukkiri ban alone; the tub bans are the owner's choice. */
    public static final Set<HouseRule> DEFAULT = Set.of(NO_SUKKIRI);

    public String key() { return name().toLowerCase(Locale.ROOT); }
    public int bit() { return 1 << ordinal(); }

    public record Instruction(UUID owner, int version) {
        public boolean known(UUID current, int revision) {
            return current != null && (revision == 0 || current.equals(owner) && version == revision);
        }

        public boolean forbids(UUID current, int revision, boolean enabled, boolean permitted) {
            return enabled && !permitted && known(current, revision);
        }
    }
}
