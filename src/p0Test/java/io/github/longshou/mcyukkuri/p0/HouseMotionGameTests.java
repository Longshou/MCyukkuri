package io.github.longshou.mcyukkuri.p0;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Opt-in focused suite; reuses the actual house workflows without unrelated injury checks. */
@PrefixGameTestTemplate(false)
public final class HouseMotionGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=7000,batch="house_motion")
    public static void houseMotion(GameTestHelper helper) {
        CoreGameTests.houseMotionAcceptance(helper);
    }
}
