package io.github.longshou.mcyukkuri.entity;

/**
 * Behaviour coefficients per species (worldview package 4, komicolle 40720 floor 11518905 "species profiles").
 * Looked up from {@link YukkuriVariant}; the shared AI reads these instead of branching on the variant. Values are
 * project design scaled from the lecture's adjectives. House forms and predators keep the neutral row so the house
 * ecology and the predator system stay as they were.
 *
 * <ul>
 *   <li>{@code cheek}: portions the mouth/cheeks can carry (only Reimu has cheek pouches: 2, everyone else 1).</li>
 *   <li>{@code song}: weight of solo singing in leisure; {@code affection}: growth of the need to be nuzzled.</li>
 *   <li>{@code forage}: multiplier on the wait between foraging trips (above 1 is lazier).</li>
 *   <li>{@code chore}: how often household chores are taken up (above 1 is more diligent).</li>
 *   <li>{@code provoke}: readiness to provoke and bully; {@code sumo}: readiness to challenge a bout;
 *       {@code bullied}: how much the others pick on it; {@code might}: strength in a bout.</li>
 *   <li>{@code speed}: movement speed multiplier; {@code fatigue}: energy drain multiplier (frailty).</li>
 *   <li>{@code vomit}: tendency to bring filling up when it eats too fast, is frightened or is under strain.</li>
 *   <li>{@code falseAlarm}: chance that a wrong alarm is cried when idle; {@code dote}: how much less hungry
 *       a child may be before the parent serves it.</li>
 * </ul>
 */
public record SpeciesTraits(int cheek, float song, float affection, float forage, float chore, float provoke, float sumo,
                            float bullied, float might, float speed, float fatigue, float vomit, float falseAlarm, int dote) {
    public static final int FEED_MARGIN = 15;
    private static final SpeciesTraits NEUTRAL = new SpeciesTraits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, FEED_MARGIN);
    private static final SpeciesTraits REIMU = new SpeciesTraits(2, 1.5f, 1.3f, 1.25f, .8f, 1, 1, 1, 1, 1, 1, 0, 0, 8);
    private static final SpeciesTraits MARISA = new SpeciesTraits(1, 1, 1, 1, .9f, 1.5f, 2, 1, 1.1f, 1, 1, 0, 0, FEED_MARGIN);
    private static final SpeciesTraits ALICE = new SpeciesTraits(1, 1, 1, 1, 1.5f, 1, 1, 1, 1, 1, 1, 0, 0, FEED_MARGIN);
    private static final SpeciesTraits PATCHOULI = new SpeciesTraits(1, 1, 1, 1, 1, .8f, .5f, 1.1f, .8f, 1, 1.25f, 1, .03f, FEED_MARGIN);
    private static final SpeciesTraits CHEN = new SpeciesTraits(1, 1, 1, 1, 1, 1, 1, 1.5f, .75f, 1.2f, 1, 0, 0, FEED_MARGIN);
    /** Myon (package 15): a hard-working forager, brave and strong ("身體素質僅次於雷普魔"); speed is in its attributes (rare/MyonLife). */
    private static final SpeciesTraits MYON = new SpeciesTraits(1, 1, 1, .7f, 1.2f, 1.2f, 1.8f, .8f, 1.4f, 1, .9f, 0, 0, FEED_MARGIN);

    public static SpeciesTraits of(YukkuriVariant variant) {
        return switch (variant) {
            case REIMU -> REIMU;
            case MARISA -> MARISA;
            case ALICE -> ALICE;
            case PATCHOULI -> PATCHOULI;
            case CHEN -> CHEN;
            case MYON -> MYON;
            default -> NEUTRAL;
        };
    }
}
