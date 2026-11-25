package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.PoseBridge.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.pose.ClipBlend;
import io.github.longshou.mcyukkuri.pose.Rig;
import io.github.longshou.mcyukkuri.pose.Skin;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class PoseBridgeTest {
    private final float[][] rig = {new float[9]};
    private final ClipBlend fit = new ClipBlend(new Rig.Bone[]{new Rig.Bone("root", -1, 0, 0, 0)},
            new Skin.Binding[0], new JsonArray());
    private final PoseBridge.State state = new PoseBridge.State(null, rig);

    private void draw(String clip, boolean loop, double now, float position) {
        rig[0][0] = position;
        state.sample(BLEND, clip, loop, 0, 1, now, 0, fit, false);
    }

    private void window(String from, boolean fromLoop, String to, boolean toLoop, int ticks) {
        draw(from, fromLoop, 0, 0);
        assertEquals(1, state.weight);
        draw(to, toLoop, 20.25, 8);
        assertEquals(0, rig[0][0]);
        draw(to, toLoop, 20.25 + ticks * .25, 8);
        assertEquals(.15625f, state.weight);
        assertEquals(1.25f, rig[0][0]);
        draw(to, toLoop, 20.25 + ticks, 8);
        assertEquals(1, state.weight);
        assertEquals(8, rig[0][0]);
    }

    @Test void reactionStartsInThreeTicks() { window("idle", true, "bow", false, 3); }
    @Test void twoLoopsSettleInSixTicks() { window("idle", true, "waddle", true, 6); }
    @Test void actionReleasesToLoopInEightTicks() { window("bow", false, "idle", true, 8); }

    @Test void authoritativeContactCapsRemainingTimeAtTheDrawnTempo() {
        draw("idle", true, 0, 0);
        // boat_paddle first touches at clip tick 4. Joined at tick 2 at double tempo, it has one game tick left.
        state.sample(BLEND, "boat_paddle", true, 2, 2, 10, 0, fit, false);
        state.sample(BLEND, "boat_paddle", true, 3, 2, 10.5, 0, fit, false);
        assertEquals(.5f, state.weight);
        state.sample(BLEND, "boat_paddle", true, 4, 2, 11, 0, fit, false);
        assertEquals(1, state.weight);
        state.sample(BLEND, "mouth_bite_hold", true, 0, 1, 12, 0, fit, false);
        assertEquals(1, state.weight);
    }

    @Test void interruptedBridgeStartsFromTheLastBlendedClipLayer() {
        draw("idle", true, 0, 0);
        rig[0][3] = 170;
        state.sample(BLEND, "idle", true, 0, 1, 0, 1, fit, false);
        for (double now : new double[]{1, 4, 4}) {
            rig[0][3] = -170;
            state.sample(BLEND, "waddle", true, 0, 1, now, 0, fit, false);
        }
        assertEquals(180, rig[0][3]);
        assertEquals(.5f, state.puff);
        rig[0][3] = 10; // An overlay writes this after recording.
        state.sample(BLEND, "bow", false, 0, 1, 5, 1, fit, false);
        assertEquals("waddle", state.fromClip);
        assertEquals(180, rig[0][3]);
        assertEquals(.5f, state.puff);
    }

    @Test void mirroredPressIsAlreadyInContactOnTheFirstDraw() {
        for (String side : new String[]{"snuggle_left", "snuggle_right"}) {
            draw("greet", false, 0, 1);
            rig[0][0] = 4;
            state.sample(BLEND, side, true, 2.68f, 1, 1, 0, fit, false);
            assertEquals(1, state.weight);
            assertEquals(4, rig[0][0]);
            draw("idle", true, 2, 9);
            assertEquals(0, state.weight);
            assertEquals(4, rig[0][0]);
        }
    }

    @Test void mirroredPressHandsBackToTheLiveRub() {
        for (String side : new String[]{"snuggle_left", "snuggle_right"}) {
            draw(side, true, 0, 4);
            draw("nuzzle", true, 4, 9);
            assertEquals(1, state.weight);
            assertEquals(9, rig[0][0]);
        }
    }

    @Test void constrainedPoseCutsABridgeAndRemainsTheReleaseSource() {
        draw("idle", true, 0, 2);
        draw("waddle", true, 1, 8);
        rig[0][0] = 5;
        state.sample(RECORD, "waddle", true, 1, 1, 2, 0, fit, false);
        assertEquals(1, state.weight);
        draw("bow", false, 3, 9);
        assertEquals(5, rig[0][0]);
    }

    @Test void aFittedChainCannotResumeAnOldPositionIfThePeerDrawsLater() {
        var parts = JsonParser.parseString("""
                [{"name":"hat_brim","start":0,"count":1}]""").getAsJsonArray();
        var hat = new ClipBlend(new Rig.Bone[]{new Rig.Bone("root", -1, 0, 0, 0)},
                new Skin.Binding[]{new Skin.Binding(0, 0, 0)}, parts);
        draw("run", true, 0, 4);
        rig[0][0] = 1;
        state.sample(BLEND, "waddle", true, 0, 1, 1, 0, hat, false);
        assertEquals(4, rig[0][0]);
        state.takeContactFrame(hat);
        assertEquals(1, state.weight);
        assertEquals(1, rig[0][0]);
        state.sample(BLEND, "waddle", true, 1, 1, 2, 0, hat, false);
        assertEquals(1, rig[0][0]);
        state.sample(BLEND, "idle", true, 0, 1, 5, 0, hat, false);
        rig[0][0] = 5;
        state.sample(BLEND, "idle", true, 1, 1, 8, 0, hat, false);
        assertEquals(3, rig[0][0]);
    }

    @Test void lateJoinsCutAndAClockRestartDoesNotCreateAChange() {
        draw("idle", true, 0, 0);
        state.sample(BLEND, "waddle", true, 3, 1, 2, 0, fit, false);
        assertEquals(0, state.weight);
        state.sample(BLEND, "bow", false, 3.01f, 1, 3, 0, fit, false);
        assertEquals(1, state.weight);
        state.sample(BLEND, "bow", false, 0, 1, 4, 0, fit, false);
        assertEquals(1, state.weight);
        assertEquals("waddle", state.fromClip);
    }

    @Test void previewConstraintsAndRealInterruptionsOwnTheirFirstFrame() {
        assertEquals(OFF, PoseBridge.mode(true, true, 1, 0, "IDLE", Behaviors.IDLE));
        assertEquals(BLEND, PoseBridge.mode(false, false, 0, 0, "IDLE", Behaviors.IDLE));
        assertEquals(RECORD, PoseBridge.mode(false, true, 0, 0, "IDLE", Behaviors.IDLE));
        assertEquals(RECORD, PoseBridge.mode(false, false, 1, 0, "IDLE", Behaviors.IDLE));
        assertEquals(RECORD, PoseBridge.mode(false, false, 0, 1, "IDLE", Behaviors.IDLE));
        assertEquals(RECORD, PoseBridge.mode(false, false, 0, 0, "STRUGGLE", Behaviors.IDLE));
        for (String row : new String[]{Behaviors.FLEE, Behaviors.BOAT_ALARM_WAIT, Behaviors.THREAT_HIDE, Behaviors.STRUCK,
                Behaviors.SHELL_RETRACT, Behaviors.ESCAPE_WATER, Behaviors.HAT_SLIPPING}) {
            assertEquals(RECORD, PoseBridge.mode(false, false, 0, 0, "IDLE", row));
        }
    }
}
