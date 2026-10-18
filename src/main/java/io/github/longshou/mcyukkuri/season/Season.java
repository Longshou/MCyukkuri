package io.github.longshou.mcyukkuri.season;

import java.util.Locale;

/** A region's seasonal character. Not a calendar: the biome decides, so snowy forests are winter all year. */
public enum Season {
    SPRING, SUMMER, AUTUMN, WINTER;
    public String id() { return name().toLowerCase(Locale.ROOT); }
}
