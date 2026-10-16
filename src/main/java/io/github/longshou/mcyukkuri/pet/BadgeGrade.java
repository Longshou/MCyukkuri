package io.github.longshou.mcyukkuri.pet;

/** Qualification level of an individual; the physical badge is only its token. */
public enum BadgeGrade {
    NONE, BRONZE, SILVER, GOLD;
    public int id() { return ordinal(); }
    public static BadgeGrade of(int id) { var all = values(); return all[Math.clamp(id, 0, all.length - 1)]; }
    public BadgeGrade lower() { return of(ordinal() - 1); }
    public BadgeGrade higher() { return of(ordinal() + 1); }
}
