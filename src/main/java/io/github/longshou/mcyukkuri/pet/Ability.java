package io.github.longshou.mcyukkuri.pet;

import java.util.Locale;

public enum Ability {
    MANNERS, ACADEMICS, WITS, POISE;
    public String key() { return name().toLowerCase(Locale.ROOT); }
}
