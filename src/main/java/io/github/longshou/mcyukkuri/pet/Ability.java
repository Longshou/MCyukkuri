package io.github.longshou.mcyukkuri.pet;

public enum Ability {
    MANNERS, ACADEMICS, WITS, POISE;
    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
}
