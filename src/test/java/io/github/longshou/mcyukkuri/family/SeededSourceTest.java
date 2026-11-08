package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.random.SeededSource;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeededSourceTest {
    @Test void seededSequenceAndOrdinaryIdentity() {
        var source = new SeededSource<Random>(Random::new);
        var ordinary = new Random(91);
        assertSame(ordinary, source.source(ordinary));
        source.seed(42);
        var expected = new Random(42);
        for (int i = 0; i < 100; i++) assertEquals(expected.nextLong(), source.source(ordinary).nextLong());
        source.seed(42);
        assertEquals(new Random(42).nextLong(), source.source(ordinary).nextLong());
        source.clear();
        assertSame(ordinary, source.source(ordinary));
        assertEquals(new Random(91).nextLong(), source.source(ordinary).nextLong());
    }
}
