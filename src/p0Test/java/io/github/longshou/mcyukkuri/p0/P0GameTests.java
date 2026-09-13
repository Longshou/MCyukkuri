package io.github.longshou.mcyukkuri.p0;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Minecraft's standard headless GameTest path; separate from dedicated/network acceptance. */
@PrefixGameTestTemplate(false)
public final class P0GameTests {
    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 6000)
    public static void entityAcceptance(GameTestHelper helper) {
        P0Server.runGameTest(helper);
    }

    private P0GameTests() {}
}
