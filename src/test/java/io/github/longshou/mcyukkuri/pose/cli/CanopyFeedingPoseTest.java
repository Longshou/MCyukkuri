package io.github.longshou.mcyukkuri.pose.cli;

import io.github.longshou.mcyukkuri.pose.Overlays;
import java.nio.file.Path;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanopyFeedingPoseTest {
    @Test void feedingLipReachesTheRaisedRimWithoutLiftingTheSole() throws Exception {
        for (var species : new String[]{"remilia","flandre"}) {
            var adult=new OfflineModel(Path.of("src/main/resources/assets/mcyukkuri"),species);
            for (float distance : new float[]{.488f,.508f}) for (int angle : new int[]{-2,0,2}) {
                // A .18-scale chick faces the parent from the .15-block-high pad.
                var target=new Vector3f(adult.mouth).mul(.18f).rotateY((float)Math.PI)
                        .add(0,.15f*16,-distance*16).rotateY((float)Math.toRadians(angle));
                for (int tick=0;tick<=20;tick++) {
                    adult.sample("nuzzle",tick/20f,null);
                    float floor=adult.minimumY();
                    Overlays.care(adult,adult.pose,adult.world,adult.root,true,true,target.x,target.y,target.z,1);
                    float gap=adult.mouth().distance(target)/16;
                    assertTrue(gap<.012,species+" spacing="+distance+" yaw="+angle+" tick="+tick+" lip gap="+gap);
                    assertEquals(floor,adult.minimumY(),.001);
                }
            }
        }
    }
}
