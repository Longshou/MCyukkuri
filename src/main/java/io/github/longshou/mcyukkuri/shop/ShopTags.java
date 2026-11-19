package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.social.CharacterWords;
import java.util.List;

/** Two four-bit character tags. IDs 0–6 retain their original meanings. */
public final class ShopTags {
    public static final int EMPTY = 15;

    private static int id(CharacterWords word) {
        return switch (word) {
            case RESTLESS -> 3;
            case PATIENT -> 4;
            case TIMID -> 5;
            case CURIOUS -> 6;
            case BOLD -> 7;
            case CARING -> 8;
            case COCKY -> 9;
            case EVEN_TEMPERED -> 10;
            case SELFISH -> 11;
        };
    }

    public static int pack(List<CharacterWords> words) {
        return id(words.getFirst()) | (words.size() == 2 ? id(words.getLast()) : EMPTY) << 4;
    }

    private ShopTags() {}
}
