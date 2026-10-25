package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.client.aquatic.BoatPose;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/** Preserve opaque passengers/cargo before the depth-only water screen is submitted, still before world water. */
public final class HatWaterMaskPass {
    private record Mask(Matrix4f transform,float unit,HatVessel.Mode mode,float sink) {}
    private static final Map<Integer,Mask> PENDING=new LinkedHashMap<>();
    private static ClientLevel frameLevel;
    private static boolean collecting;

    /** The pose stands in the boat's frame at the brim top (HatBoatRenderer); the mask follows that one frame. */
    static void enqueue(BoatPose frame,PoseStack pose) {
        var boat=frame.boat;
        if(!collecting||boat.level()!=frameLevel||!(boat.isAfloat()||boat.isMoored()))return;
        var mode=boat.aquaticHull()?HatVessel.Mode.AQUATIC_FLOAT:HatVessel.Mode.FLOAT;
        PENDING.put(boat.getId(),new Mask(new Matrix4f(pose.last().pose()),frame.unit,mode,frame.sink()));
    }

    public static void renderStage(RenderLevelStageEvent event) {
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
        var mask=buffers.getBuffer(RenderType.waterMask());
        for(var entry:PENDING.values()) {
            // A loaded hull rides lower with its seats, but the water screen stays at the true waterline.
            float unit=entry.unit(),y=-HatVessel.freeboard(entry.mode())*unit+.03f+entry.sink();var ring=HatVessel.mask(entry.mode());
            // Convex authored opening: triangulate its fan from vertex 0 in both windings for the depth-only pass.
            for(int face=0;face<2;face++)for(int i=1;i+1<ring.length;i++) {
                int a=0,b=face==0?i:i+1,c=face==0?i+1:i;
                // waterMask is emitted as QUADS.  Close this triangle with a duplicate corner rather than letting
                // adjacent fan triangles be joined into arbitrary quads.
                for(int index:new int[]{a,b,c,c}) { float[] point=ring[index];mask.addVertex(entry.transform(),point[0]*unit,y,point[1]*unit); }
            }
        }
        buffers.endBatch(RenderType.waterMask());
        clear();
    }
    public static void unload(LevelEvent.Unload event) { if(event.getLevel()==frameLevel)clear(); }
    private static void clear() { PENDING.clear();frameLevel=null;collecting=false; }
    private HatWaterMaskPass() {}
}
