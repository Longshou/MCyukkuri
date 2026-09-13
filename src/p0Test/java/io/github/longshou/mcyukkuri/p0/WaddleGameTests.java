package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercise real travel/friction, including movement below the former threshold. */
@PrefixGameTestTemplate(false)
public final class WaddleGameTests {
    static void clearGoals(io.github.longshou.mcyukkuri.entity.YukkuriEntity entity) {
        try {
            for(String name:new String[]{"goalSelector","targetSelector"}) {
                var field=net.minecraft.world.entity.Mob.class.getDeclaredField(name);field.setAccessible(true);
                ((net.minecraft.world.entity.ai.goal.GoalSelector)field.get(entity)).removeAllGoals(goal->true);
            }
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=200, batch="waddle")
    public static void ordinaryMovement(GameTestHelper helper) {
        var level=helper.getLevel();
        var origin=helper.absolutePos(new BlockPos(1,2,1));
        for(int x=-2;x<=3;x++)for(int z=-2;z<=3;z++)
            level.setBlockAndUpdate(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState());
        var checks=new ArrayList<Object>();
        for(var variant:YukkuriVariant.values()) {
            var entity=ModEntities.forVariant(variant).create(level);
            clearGoals(entity); // Isolate travel from random goal acceleration, keep physics enabled.
            for(double speed:new double[]{.004,.04,0}) {
                entity.moveTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);
                entity.setOnGround(true);
                entity.setDeltaMovement(speed,-.08,0);
                double before=entity.getX();
                entity.aiStep();
                String expected=speed==0?"idle":"waddle";
                boolean passed=entity.getAnimationId().equals(expected);
                checks.add(Map.of("variant",variant.assetId(),"speed",speed,"distance",entity.getX()-before,
                        "action",entity.getAnimationId(),"pass",passed));
                P0Control.write("waddle-checks",checks);
                helper.assertTrue(passed,variant+" speed "+speed+" expected "+expected);
            }
            entity.moveTo(origin.getX()+.5,origin.getY()+2,origin.getZ()+.5,0,0);
            entity.setOnGround(false);entity.setDeltaMovement(new Vec3(.04,.1,0));entity.aiStep();
            helper.assertTrue(entity.getAnimationId().equals("idle"),variant+" airborne travel must not waddle");
            entity.discard();
        }
        helper.succeed();
    }
}
