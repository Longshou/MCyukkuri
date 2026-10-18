package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/** Preserve opaque passengers/cargo before the depth-only water screen is submitted, still before world water. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID,value=Dist.CLIENT)
public final class HatWaterMaskPass {
    private record Mask(Matrix4f transform,float unit) {}
    private static final Map<Integer,Mask> PENDING=new LinkedHashMap<>();
    private static ClientLevel frameLevel;
    private static boolean collecting;

    static void enqueue(HatBoatView boat,PoseStack pose) {
        if(!collecting||boat.level()!=frameLevel||!(boat.isAfloat()||boat.isMoored()))return;
        PENDING.put(boat.getId(),new Mask(new Matrix4f(pose.last().pose()),boat.hullScale()/16));
    }

    @SubscribeEvent public static void renderStage(RenderLevelStageEvent event) {
        var minecraft=Minecraft.getInstance();
        // AFTER_SKY is always dispatched by 1.21.1, including dimensions without a visible sky.
        if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_SKY) {
            clear(); frameLevel=minecraft.level; collecting=frameLevel!=null; return;
        }
        if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_LEVEL) { clear(); return; }
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        collecting=false;
        if(frameLevel==null||frameLevel!=minecraft.level||PENDING.isEmpty()) { clear();return; }
        var buffers=minecraft.renderBuffers().bufferSource();
        // The normal shared entity/item buffers must reach the framebuffer before any hat's depth-only plane.
        buffers.endBatch();
        var mask=buffers.getBuffer(RenderType.waterMask());var ring=HatVessel.mask();
        for(var entry:PENDING.values()) {
            float unit=entry.unit(),y=-HatVessel.FREEBOARD*unit+.03f;
            for(int face=0;face<2;face++)for(int i=0;i<4;i++) {
                float[] point=ring[face==0?i:3-i];
                mask.addVertex(entry.transform(),point[0]*unit,y,point[1]*unit);
            }
        }
        buffers.endBatch(RenderType.waterMask());
        clear();
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if(event.getLevel()==frameLevel)clear(); }
    private static void clear() { PENDING.clear();frameLevel=null;collecting=false; }
    private HatWaterMaskPass() {}
}
