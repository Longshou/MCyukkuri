package io.github.longshou.mcyukkuri.social;

import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/** Salient character meanings, in tie order. Append IDs for the synced readout. */
public enum CharacterWords {
    TIMID(Temperament::timid),
    BOLD(t -> 1 - t.timid()),
    PATIENT(Temperament::patient),
    RESTLESS(t -> 1 - t.patient()),
    CARING(Temperament::caring),
    SELFISH(Temperament::selfish),
    COCKY(Temperament::cocky),
    CURIOUS(Temperament::curious),
    EVEN_TEMPERED(t -> 0);

    private static final CharacterWords[] VALUES = values();
    private final ToDoubleFunction<Temperament> score;

    CharacterWords(ToDoubleFunction<Temperament> score) { this.score = score; }

    public static CharacterWords byId(int id) { return VALUES[id]; }

    public String translationKey() { return "magnifier.mcyukkuri.character." + name().toLowerCase(Locale.ROOT); }

    public static List<CharacterWords> select(Temperament temperament) {
        CharacterWords first = EVEN_TEMPERED, second = EVEN_TEMPERED;
        double firstScore = 0, secondScore = 0;
        for (CharacterWords word : VALUES) {
            double score = word.score.applyAsDouble(temperament);
            if (score < Temperament.TOP16) continue;
            if (score > firstScore) {
                second = first;
                secondScore = firstScore;
                first = word;
                firstScore = score;
            } else if (score > secondScore) {
                second = word;
                secondScore = score;
            }
        }
        return second == EVEN_TEMPERED ? List.of(first) : List.of(first, second);
    }
}
