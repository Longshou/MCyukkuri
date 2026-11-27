package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaitImpatienceRuleTest {
    @Test
    void triggerTickScalesMonotonicallyWithPatience() {
        int budget = 200;
        int impatient = WaitImpatienceRule.triggerTick(0.0, budget);
        int average = WaitImpatienceRule.triggerTick(0.5, budget);
        int patient = WaitImpatienceRule.triggerTick(1.0, budget);
        assertEquals(100, impatient);
        assertEquals(125, average);
        assertEquals(150, patient);
        assertTrue(impatient < average && average < patient);
        assertEquals(30, WaitImpatienceRule.triggerTick(0.0, 60));
        assertTrue(WaitImpatienceRule.triggerTick(0.0, 1) >= 1);
    }

    @Test
    void sharpnessVariesAcrossPatienceBands() {
        var impatient = WaitImpatienceRule.spec(0.0);
        var moderate = WaitImpatienceRule.spec(0.5);
        var patient = WaitImpatienceRule.spec(1.0);

        assertEquals("down_angry", impatient.face());
        assertEquals("wait_impatient", impatient.line());
        assertEquals(YukkuriAction.MOOD_FLICK, impatient.clip());

        assertEquals("hmph", moderate.face());
        assertEquals("wait_impatient", moderate.line());
        assertEquals(YukkuriAction.LOOK_AROUND, moderate.clip());

        assertEquals("pursed", patient.face());
        assertEquals("wait_quiet", patient.line());
        assertEquals(YukkuriAction.LOOK_AROUND, patient.clip());

        // Boundary checks at LOW35 / TOP35.
        assertEquals(moderate, WaitImpatienceRule.spec(Temperament.LOW35));
        assertEquals(patient, WaitImpatienceRule.spec(Temperament.TOP35));
    }

    @Test
    void declaredFacesExistInFaceLibrary() throws Exception {
        Set<String> faces = new HashSet<>();
        try (var stream = getClass().getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/face_library.json")) {
            assertNotNull(stream, "face_library.json missing");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var el : root.getAsJsonArray("faces")) faces.add(el.getAsString());
        }
        for (double p : new double[]{0.0, 0.39, 0.40, 0.50, 0.59, 0.60, 1.0}) {
            String face = WaitImpatienceRule.spec(p).face();
            assertTrue(faces.contains(face), "face not in face_library.json: " + face);
        }
    }
}
