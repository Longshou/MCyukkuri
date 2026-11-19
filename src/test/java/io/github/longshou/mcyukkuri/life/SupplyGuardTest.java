package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplyGuardTest {
    private final Temperament harsh = new Temperament(60, 25, 80, 50, 35);
    private final Temperament kindly = new Temperament(65, 85, 60, 80, 75);
    private final Temperament wary = new Temperament(60, 60, 20, 65, 65);

    @Test void aNoviceCanGuardAgainstSiblingsButNeverACareParent() {
        assertTrue(SupplyGuard.WATER.guards(harsh, 1, false, true, 40));
        assertTrue(SupplyGuard.BOWL.guards(harsh, 0, false, true, 60));
        assertFalse(SupplyGuard.BOWL.guards(harsh, 0, true, true, 10));
        assertFalse(SupplyGuard.WATER.guards(harsh, 2, false, false, 90));
    }

    @Test void urgentNeedMattersToBoldAnimalsAndKindOnesAdmitKin() {
        assertFalse(SupplyGuard.WATER.guards(kindly, 0, false, false, 69));
        assertTrue(SupplyGuard.WATER.guards(kindly, 0, false, false, 70));
        assertFalse(SupplyGuard.WATER.guards(kindly, 0, false, true, 70));
        assertFalse(SupplyGuard.BOWL.guards(kindly, 0, false, false, 25));
        assertTrue(SupplyGuard.BOWL.guards(kindly, 0, false, false, 24));
        assertFalse(SupplyGuard.BOWL.guards(wary, 0, false, false, 10));
    }
}
