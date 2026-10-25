package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.CareStyles;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CareRenderer implements BlockEntityRenderer<CareBlockEntity> {
    public CareRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(CareBlockEntity care, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        String model=switch(care.kind()){
            case BOWL -> "food_bowl";
            case NEST -> "yukkuri_nest";
            case TOILET -> care.waste()>0?"yukkuri_toilet_dirty":"yukkuri_toilet";
            case MAT -> ((CareBlock)care.getBlockState().getBlock()).style();
        };
        String style=((CareBlock)care.getBlockState().getBlock()).style();
        if(!style.isEmpty())model=style;
        // Dye-selected style (CareStyles): the sand toilet's dirty mesh follows its style; unknown names fall back to the default.
        boolean dirtySand=model.equals("yukkuri_toilet_dirty");
        String styled=CareStyles.model(dirtySand?"yukkuri_toilet":model,care.pattern())+(dirtySand?"_dirty":"");
        if(PropModels.has(styled))model=styled;
        var food=care.displayFood();
        boolean grain=food.is(ModItems.GRAIN_BUDGET.get())||food.is(ModItems.GRAIN_REGULAR.get())||food.is(ModItems.GRAIN_PREMIUM.get());
        boolean wheat=food.is(Items.WHEAT);
        boolean grass=food.is(Items.SHORT_GRASS)||food.is(Items.TALL_GRASS);
        // Fills are heap-only meshes drawn inside whichever bowl style is placed.
        String fill=care.kind()!=CareBlock.Kind.BOWL?null:grain?"food_bowl_regular":wheat?"food_bowl_wheat":grass?"food_bowl_forage":null;
        int units=Math.min(8,care.visibleFoodCount());
        pose.pushPose();pose.translate(.5,0,.5);
        PropModels.render(model,units,pose,buffers,light,overlay);
        if(fill!=null)PropModels.render(fill,units,pose,buffers,light,overlay);
        pose.popPose();
        if(!style.isEmpty()&&care.kind()==CareBlock.Kind.TOILET&&care.waste()>0){
            int shown=Math.min(8,care.waste());
            for(int i=0;i<shown;i++){pose.pushPose();pose.translate(.25+(i%4)*.16,style.equals("covered_toilet")?.094:.0625,.35+(i/4)*.23);pose.scale(.5f,.5f,.5f);
                PropModels.render("toilet_waste",0,pose,buffers,light,overlay);pose.popPose();}
        }
        if(care.kind()!=CareBlock.Kind.BOWL||grain||grass||wheat)return;
        ItemStack shown = care.kind() == CareBlock.Kind.BOWL ? care.displayFood()
                : care.kind() == CareBlock.Kind.TOILET && care.waste() > 0 ? new ItemStack(ModItems.WASTE.get()) : ItemStack.EMPTY;
        if (shown.isEmpty()) return;
        pose.pushPose(); pose.translate(.5,.12,.5); pose.scale(.45f,.45f,.45f);
        Minecraft.getInstance().getItemRenderer().renderStatic(shown, ItemDisplayContext.GROUND, light, overlay, pose, buffers, care.getLevel(), 0);
        pose.popPose();
    }
}
