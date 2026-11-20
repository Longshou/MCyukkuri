package io.github.longshou.mcyukkuri.life;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperSheetsTest {
    private record Sheet(String name, int count) {}

    @Test void picksNamedAndPlainPortionsFromTheirOwnSources() {
        var named = new Sheet("A marked page", 1);
        var plain = new Sheet("", 4);
        var singlePlain = new Sheet("", 1);
        var other = new Sheet("Spare", 1);
        assertEquals(List.of(new PaperSheets.Portion<>(named, 1), new PaperSheets.Portion<>(plain, 1)),
                PaperSheets.select(List.of(named, plain, other), 2, Sheet::count));
        assertEquals(List.of(new PaperSheets.Portion<>(singlePlain, 1), new PaperSheets.Portion<>(named, 1)),
                PaperSheets.select(List.of(singlePlain, named, other), 2, Sheet::count));
    }

    @Test void refusedReleaseRetainsTheUnreleasedSheetsForLaterTransfer() {
        var named = new Sheet("A marked page", 1);
        var plain = new Sheet("", 2);
        var cover = new ArrayList<>(List.of(named, plain));
        var ground = new ArrayList<Sheet>();

        PaperSheets.release(cover, sheet -> sheet == named && ground.add(sheet));
        assertEquals(List.of(named), ground);
        assertEquals(List.of(plain), cover);

        PaperSheets.release(cover, ground::add);
        assertEquals(List.of(named, plain), ground);
        assertEquals(List.of(), cover);
    }
}
