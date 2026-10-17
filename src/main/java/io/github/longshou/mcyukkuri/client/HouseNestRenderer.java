package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.HouseEggGeometry;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Only accumulated bedding, food and eggs are visible; shelter comes from the actual room. */
public final class HouseNestRenderer implements BlockEntityRenderer<HouseNestBlockEntity> {
    public HouseNestRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(HouseNestBlockEntity nest,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(.5,0,.5);
        pose.mulPose(Axis.YP.rotationDegrees(180-nest.entranceDirection().toYRot()));pose.translate(-.5,0,-.5);
        float built=Math.min(1,nest.materials()/(float)HouseNestBlockEntity.MATERIAL_COST);
        if(built>0) {
            // Lining grows in place as real lint arrives; a single strand never creates a full bed.
            float spread=.45f+.55f*built;
            mesh("house_lint_bedding",.32,.01,.55,.90f*spread,built,1.15f*spread,pose,buffers,light,overlay);
            var bed=HouseEggGeometry.beddingCenter();
            if(nest.materials()>=3)mesh("house_lint_bedding",bed.x,bed.y,bed.z,HouseEggGeometry.beddingScale(0)*spread,HouseEggGeometry.beddingScale(1)*built,HouseEggGeometry.beddingScale(2)*spread,pose,buffers,light,overlay);
        }
        // One visible cluster per four stored portions, never an invented meal at an empty nest.
        for(int i=0;i<(nest.food()+3)/4;i++)
            draw(new ItemStack(ModItems.FOOD_SCRAP.get()),.24+(i%3)*.065,.045+(i/3)*.012,.24+(i/3)*.065,.18f,nest,pose,buffers,light,overlay);
        for(int i=0;i<(nest.dirt()+2)/3;i++)
            draw(new ItemStack(ModItems.WASTE.get()),.78+(i%2)*.045,.035,.27+(i/2)*.06,.16f,nest,pose,buffers,light,overlay);
        double clock=nest.getLevel()==null?0:nest.getLevel().getGameTime()+(double)partial;
        boolean incubating=nest.built()&&nest.careTicks()>0&&nest.disturbedTicks()==0&&nest.food()>0&&nest.dirt()<6;
        for(int slot=0;slot<4;slot++) {
            var point=nest.eggLocalPosition(slot);
            if(nest.eggPresent(slot)) {
                float progress=nest.eggProgress(slot);
                // Short efforts separated by a pause, staggered by egg slot; all clients use world time.
                float phase=(float)((clock+slot*11)%46);
                float effort=incubating&&progress>.75f&&phase<12?(float)Math.sin(phase*Math.PI/12):0;
                float tilt=(float)Math.sin(phase*Math.PI/3)*effort*(2+5*Math.clamp((progress-.75f)/.25f,0,1));
                pose.pushPose();pose.translate(point.x,point.y,point.z);
                pose.translate(0,.025,0);pose.mulPose(Axis.ZP.rotationDegrees(tilt));pose.translate(0,-.025,0);
                pose.scale(HouseEggGeometry.MODEL_SCALE,HouseEggGeometry.MODEL_SCALE,HouseEggGeometry.MODEL_SCALE);
                PropModels.render(progress>.88f?"house_egg_cracked":"house_egg",8,pose,buffers,light,overlay);
                pose.popPose();
            }
            int age=nest.hatchAgeTicks(slot);
            if(age>=0) {
                float time=age+partial,burst=time<12?(float)Math.sin(time*Math.PI/12):0;
                pose.pushPose();pose.translate(point.x,point.y+burst*.025,point.z);
                pose.mulPose(Axis.YP.rotationDegrees(slot*67+burst*14));
                pose.scale(HouseEggGeometry.MODEL_SCALE,HouseEggGeometry.MODEL_SCALE,HouseEggGeometry.MODEL_SCALE);
                PropModels.render("house_eggshell",8,pose,buffers,light,overlay);pose.popPose();
            }
        }
        pose.popPose();
    }

    private static void mesh(String name,double x,double y,double z,float sx,float sy,float sz,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(x,y,z);pose.scale(sx,sy,sz);PropModels.render(name,8,pose,buffers,light,overlay);pose.popPose();
    }
    private static void draw(ItemStack item,double x,double y,double z,float scale,HouseNestBlockEntity nest,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(x,y,z);pose.scale(scale,scale,scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(item,ItemDisplayContext.FIXED,light,overlay,pose,buffers,nest.getLevel(),0);pose.popPose();
    }
}
