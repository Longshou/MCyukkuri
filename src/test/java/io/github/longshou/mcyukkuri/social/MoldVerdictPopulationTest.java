package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.settlement.MoldVerdict;
import io.github.longshou.mcyukkuri.settlement.MoldVerdict.Inputs;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.settlement.MoldVerdict.Verdict.KEEP;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoldVerdictPopulationTest {
    @Test void strongerSignsAndKnowledgeRaiseResolveAcrossWildFamilies() {
        int aware = 0, unaware = 0;
        var random = new Random(19112026);
        for (int i = 0; i < 10_000; i++) {
            long seed = random.nextLong();
            var t = new Temperament(TraitDraw.wild(seed,0,50), TraitDraw.wild(seed,1,50), TraitDraw.wild(seed,2,50),
                    TraitDraw.wild(seed,3,50), TraitDraw.wild(seed,4,50));
            for (boolean knows : new boolean[]{false, true}) {
                double previous = Double.NEGATIVE_INFINITY;
                for (int tier = 1; tier <= 4; tier++) {
                    var in = new Inputs(t,tier,knows,30,true);
                    double resolve = MoldVerdict.resolve(in);
                    assertTrue(resolve > previous);
                    if (knows) assertTrue(resolve > MoldVerdict.resolve(new Inputs(t,tier,false,30,true)));
                    if (tier == 2 && MoldVerdict.decide(in) != KEEP) {
                        if (knows) aware++; else unaware++;
                    }
                    previous = resolve;
                }
            }
        }
        assertTrue(aware >= 5500 && aware <= 6100, "aware drivers " + aware);
        assertTrue(unaware >= 800 && unaware <= 1400, "unaware drivers " + unaware);
    }
}
