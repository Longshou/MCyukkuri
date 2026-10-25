package io.github.longshou.mcyukkuri.subspecies.crew;

/**
 * The size classes of hat_deck_layout.json {@code classes}, smallest first. A body takes the first class whose maximum
 * growth ratio (relative to the captain's hat scale) is not below its own. Ordinals are compared: a conflict entry names the
 * smallest class on the other seat that touches.
 */
public enum BodyClass {
    NEWBORN("newborn"), BABY("baby"), SMALL_CHILD("small_child"), CHILD("child"), LARGE_CHILD("large_child"),
    JUVENILE("juvenile"), ADULT("adult"), GRAVID("gravid");

    public final String id;
    BodyClass(String id) { this.id = id; }
    public boolean captain() { return this == ADULT || this == GRAVID; }
    public int mask() { return 1 << ordinal(); }
    public static BodyClass of(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown hat deck body class " + id);
    }
}
