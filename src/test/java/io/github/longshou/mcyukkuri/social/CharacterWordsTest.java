package io.github.longshou.mcyukkuri.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.shop.ShopHint;
import io.github.longshou.mcyukkuri.shop.ShopTags;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.BirthProfile;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.TraitBounds;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.longshou.mcyukkuri.social.CharacterWords.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterWordsTest {
    @Test
    void selectsOnlyScoresAtOrAboveTheCut() {
        assertEquals(List.of(EVEN_TEMPERED), select(new Temperament(50, 50, 36, 50, 64)));
        assertEquals(List.of(TIMID), select(new Temperament(50, 50, 35, 50, 50)));
        assertEquals(List.of(CARING), select(new Temperament(50, 63, 50, 58, 50)));
        assertEquals(List.of(CURIOUS), select(new Temperament(100, 50, 64, 50, 50)));
        assertEquals(List.of(PATIENT, TIMID), select(new Temperament(50, 50, 35, 50, 80)));
    }

    @Test
    void breaksTiesInOrderAndTakesAtMostOnePoleOfEachPair() {
        assertEquals(List.of(TIMID, RESTLESS), select(new Temperament(50, 60, 20, 50, 20)));
        assertEquals(List.of(BOLD, PATIENT), select(new Temperament(90, 80, 80, 70, 80)));
        assertEquals(List.of(SELFISH, COCKY), select(new Temperament(50, 0, 72, 50, 34)));
        for (int boldness = 0; boldness <= 100; boldness++) {
            for (int control = 0; control <= 100; control++) {
                var words = select(new Temperament(50, 50, boldness, 50, control));
                assertFalse(words.contains(TIMID) && words.contains(BOLD));
                assertFalse(words.contains(PATIENT) && words.contains(RESTLESS));
            }
        }
    }

    @Test
    void upbringingCanChangeTheWordsAndRestoringTheProfileKeepsThem() {
        var born = new BirthProfile(50, 50, 50, 50, 64);
        var form = TraitBounds.forForm(false, SubspeciesLife.Kind.NORMAL);
        var care = new Upbringing(0, 0).after(2, true, born.selfControl(), Trait.SELF_CONTROL, form);
        assertEquals(List.of(EVEN_TEMPERED), select(profile(born, 0)));
        assertEquals(List.of(PATIENT), select(profile(born, care.points())));
        var loadedBirth = BirthProfile.fromInnateValues(born.innateValues());
        var loadedCare = Upbringing.bounded(care.points(), care.remainder(), loadedBirth.selfControl(), Trait.SELF_CONTROL, form);
        assertEquals(select(profile(born, care.points())), select(profile(loadedBirth, loadedCare.points())));
    }

    private static Temperament profile(BirthProfile birth, int selfControlExperience) {
        return new Temperament(birth.intelligence(), birth.friendliness(), birth.boldness(), birth.attachment(),
                birth.selfControl() + selfControlExperience);
    }

    @Test
    void stockProfilesKeepTheSameMeaningsAndOrderAcrossReadouts() {
        var friendly = select(new Temperament(50, 85, 20, 50, 50));
        var bold = select(new Temperament(50, 20, 85, 50, 50));
        assertEquals(List.of(TIMID, CARING), friendly);
        assertEquals(List.of(BOLD, CURIOUS), bold);
        assertEquals(List.of(ShopHint.TIMID, ShopHint.CARING), friendly.stream().map(ShopHint::character).toList());
        assertEquals(List.of(ShopHint.BOLD, ShopHint.CURIOUS), bold.stream().map(ShopHint::character).toList());
        assertEquals(0x85, ShopTags.pack(friendly));
        assertEquals(0x67, ShopTags.pack(bold));
        assertEquals(0xFA, ShopTags.pack(List.of(EVEN_TEMPERED)));
        assertEquals(0xF4, ShopTags.pack(List.of(PATIENT)));
        assertEquals(friendly, friendly.stream().mapToInt(CharacterWords::ordinal).mapToObj(CharacterWords::byId).toList());
        assertEquals(bold, bold.stream().mapToInt(CharacterWords::ordinal).mapToObj(CharacterWords::byId).toList());
        assertEquals(34, ShopHint.SETTLED.ordinal());
        assertEquals(35, ShopHint.CARING.ordinal());
    }

    @Test
    void allWordsHaveTranslationsInBothShopVoicesAndTheCompactReadouts() throws IOException {
        for (String locale : List.of("zh_cn", "ja_jp", "en_us")) {
            JsonObject lang;
            try (var reader = new InputStreamReader(getClass().getResourceAsStream("/assets/mcyukkuri/lang/" + locale + ".json"),
                    StandardCharsets.UTF_8)) {
                lang = JsonParser.parseReader(reader).getAsJsonObject();
            }
            for (CharacterWords word : values()) {
                int tag = ShopTags.pack(List.of(word)) & ShopTags.EMPTY;
                assertTrue(tag < ShopTags.EMPTY);
                String pet = plainText(lang, ShopHint.character(word).translationKey(false));
                String oni = plainText(lang, ShopHint.character(word).translationKey(true));
                assertNotEquals(pet, oni);
                plainText(lang, "shop.mcyukkuri.tag." + tag);
                plainText(lang, word.translationKey());
            }
            assertEquals(4, formatArguments(lang, "magnifier.mcyukkuri.mind"));
            assertEquals(2, formatArguments(lang, "magnifier.mcyukkuri.fear_of"));
            assertEquals(1, formatArguments(lang, "magnifier.mcyukkuri.skills"));
        }
    }

    private static String plainText(JsonObject lang, String key) {
        assertTrue(lang.has(key), key);
        String text = lang.get(key).getAsString();
        assertFalse(text.isBlank(), key);
        assertFalse(text.contains("%"), key);
        return text;
    }

    private static long formatArguments(JsonObject lang, String key) {
        return lang.get(key).getAsString().split("%s", -1).length - 1;
    }
}
