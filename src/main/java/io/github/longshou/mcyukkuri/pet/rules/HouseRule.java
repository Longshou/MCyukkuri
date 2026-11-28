package io.github.longshou.mcyukkuri.pet.rules;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public enum HouseRule {
    NO_SUKKIRI, NO_SINGING, NO_LEAVING_YARD, NO_EATING_UNASKED, NO_BULLYING, NO_TUB_WHEN_HEALTHY, NO_TUB_WHEN_HURT,
    GIVE_WAY;

    /** The rules every pet starts with: the breeding ban alone; the others are set per pet by its owner. */
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

    /**
     * One pet's own independent house rules and the revision it last heard from its owner. Pure data so per-pet independence,
     * sleeping/untold tracking and owner changes can be tested without a world.
     */
    public static final class Policy {
        private final EnumSet<HouseRule> rules = EnumSet.copyOf(DEFAULT);
        private UUID owner;
        private int version;
        private Instruction instruction = new Instruction(null, 0);

        public boolean enabled(HouseRule rule) { return rules.contains(rule); }

        public int mask() {
            int bits = 0;
            for (var rule : rules) bits |= rule.bit();
            return bits;
        }

        public int version() { return version; }

        public UUID owner() { return owner; }

        public Instruction instruction() { return instruction; }

        public boolean told(UUID currentOwner) { return instruction.known(currentOwner, version); }

        public boolean binds(UUID currentOwner, HouseRule rule, boolean permitted) {
            return instruction.forbids(currentOwner, version, enabled(rule), permitted);
        }

        public boolean hasRestrictions() { return !rules.isEmpty(); }

        /** Toggle one rule for this pet alone; returns true if the rule is now enabled (tightened). */
        public boolean toggle(UUID currentOwner, HouseRule rule, boolean awake) {
            boolean enabled = !rules.remove(rule);
            if (enabled) rules.add(rule);
            owner = currentOwner;
            version++;
            if (awake) instruction = new Instruction(currentOwner, version);
            return enabled;
        }

        /**
         * Copy {@code mask} onto this pet for {@code currentOwner}. Returns the bitmask of rules that changed (0 when already
         * identical and already told to this owner). When {@code perceived} is true the pet knows the new revision immediately;
         * otherwise it stays untold until it meets the owner.
         */
        public int sync(UUID currentOwner, int mask, boolean perceived) {
            int diff = mask() ^ mask;
            if (diff == 0 && told(currentOwner)) return 0;
            rules.clear();
            for (var rule : HouseRule.values()) if ((mask & rule.bit()) != 0) rules.add(rule);
            owner = currentOwner;
            version++;
            if (perceived) instruction = new Instruction(currentOwner, version);
            return diff;
        }

        /** Mark this pet as having heard its current revision; true when that changed its state. */
        public boolean markTold(UUID currentOwner) {
            if (currentOwner == null || told(currentOwner)) return false;
            instruction = new Instruction(currentOwner, version);
            return true;
        }

        public void reset() {
            rules.clear();
            rules.addAll(DEFAULT);
            owner = null;
            version = 0;
            instruction = new Instruction(null, 0);
        }

        public void restore(UUID savedOwner, int savedVersion, int savedMask, boolean hasRules, Instruction savedInstruction) {
            rules.clear();
            if (hasRules) {
                for (var rule : HouseRule.values()) if ((savedMask & rule.bit()) != 0) rules.add(rule);
            } else {
                rules.addAll(DEFAULT);
            }
            owner = savedOwner;
            version = Math.max(0, savedVersion);
            instruction = savedInstruction == null ? new Instruction(null, 0) : savedInstruction;
        }
    }
}
