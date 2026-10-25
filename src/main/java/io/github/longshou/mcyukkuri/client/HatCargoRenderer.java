package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.subspecies.HatCargo;
import io.github.longshou.mcyukkuri.client.aquatic.BoatPose;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;

/**
 * The crown larder: the actual stored items, drawn with their own item models, pressed into the bottom of the turned hat
 * on a leaf bed (hat_cargo_layout.json larder, authored and settled offline by drafts/care-props/cargo.py; the server's
 * occupancy reads the same file). Nothing is drawn on the brim. Pose: the hull frame at the brim top in blocks.
 */
final class HatCargoRenderer {
    private static final String BED="hat_provisions";
    private record Item(int cluster,float x,float y,float z,Quaternionf turn,float size){}
    private record Tier(int max,int bedUnit,List<Item> items){}
    private static final float BOWL_SQUASH;
    private static final List<Tier> TIERS;
    static {
        JsonObject data;
        try(var stream=HatCargoRenderer.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_cargo_layout.json")){
            if(stream==null)throw new IllegalStateException("Missing hat cargo layout");
            data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException error){throw new UncheckedIOException(error);}
        BOWL_SQUASH=data.get("bowl_squash").getAsFloat();
        var tiers=new ArrayList<Tier>();
        for(var raw:data.getAsJsonArray("larder")){
            var tier=raw.getAsJsonObject();var items=new ArrayList<Item>();
            for(var entry:tier.getAsJsonArray("items")){
                var item=entry.getAsJsonObject();JsonArray p=item.getAsJsonArray("pos"),q=item.getAsJsonArray("quaternion");
                items.add(new Item(item.get("cluster").getAsInt(),p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat(),
                        new Quaternionf(q.get(0).getAsFloat(),q.get(1).getAsFloat(),q.get(2).getAsFloat(),q.get(3).getAsFloat()),item.get("size").getAsFloat()));
            }
            tiers.add(new Tier(tier.get("max_count").getAsInt(),tier.get("bed_unit").getAsInt(),List.copyOf(items)));
        }
        TIERS=List.copyOf(tiers);
    }

    /** How a stored kind lies: flat items as they are, stems folded once and tucked, berries a small handful, block models small. */
    private enum Form{FLAT,STEM,SMALL,BLOCK}
    private static Form form(ItemStack stack,boolean blockModel){
        if(blockModel)return Form.BLOCK;
        if(stack.is(Items.SWEET_BERRIES)||stack.is(Items.GLOW_BERRIES))return Form.SMALL;
        if(stack.is(Items.SHORT_GRASS)||stack.is(Items.TALL_GRASS)||stack.is(Items.FERN)||stack.is(Items.LARGE_FERN)||stack.is(Items.WHEAT)
                ||stack.is(Items.SEAGRASS)||stack.is(Items.KELP)||stack.is(Items.DRIED_KELP))return Form.STEM;
        return Form.FLAT;
    }

    static void render(BoatPose frame,PoseStack pose,MultiBufferSource buffers,int light) {
        var boat=frame.boat;
        // The stored kinds and portions exactly as synchronized; at most HatCargo.SLOTS kinds.
        var kinds=new ArrayList<ItemStack>();int total=0;
        CompoundTag cargo=boat.cargoSnapshot();
        for(var raw:cargo.getList("Food",Tag.TAG_COMPOUND)){
            var stack=ItemStack.parseOptional(boat.level().registryAccess(),(CompoundTag)raw);
            if(stack.isEmpty()||total>=HatCargo.LARDER_MAX)continue;
            int n=Math.min(stack.getCount(),HatCargo.LARDER_MAX-total);total+=n;kinds.add(stack.copyWithCount(n));
        }
        int tierIndex=HatCargo.tierFor(total);
        if(tierIndex<=0||tierIndex>=TIERS.size())return;
        var tier=TIERS.get(tierIndex);
        // Cluster 0 is the most numerous kind (ties keep the stored order).
        kinds.sort((a,b)->Integer.compare(b.getCount(),a.getCount()));
        int[] left=new int[kinds.size()];for(int i=0;i<left.length;i++)left[i]=kinds.get(i).getCount();
        float scale=frame.scale,r=frame.squash/BOWL_SQUASH;
        pose.pushPose();
        if(PropModels.has(BED)){
            pose.pushPose();pose.scale(scale,scale*r,scale);
            PropModels.renderLayers(BED,1<<(tier.bedUnit()-1),pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        var renderer=Minecraft.getInstance().getItemRenderer();
        int shown=Math.min(total,tier.max());
        pose.scale(scale/16,scale/16,scale/16);
        for(int i=0;i<shown&&i<tier.items().size();i++){
            var place=tier.items().get(i);
            int kind=place.cluster()<left.length&&left[place.cluster()]>0?place.cluster():-1;
            if(kind<0)for(int k=0;k<left.length;k++)if(left[k]>0&&(kind<0||left[k]>left[kind]))kind=k;
            if(kind<0)break;
            left[kind]--;
            var stack=kinds.get(kind);
            var model=renderer.getModel(stack,boat.level(),null,0);
            var shape=form(stack,model.isGui3d());
            pose.pushPose();
            pose.translate(place.x(),place.y()*r,place.z());
            pose.mulPose(place.turn());
            float size=place.size()*(shape==Form.SMALL?.8f:shape==Form.BLOCK?.5f:1f);
            if(shape==Form.BLOCK)pose.translate(0,0,size*.5f);  // a block model's cube sits on the place, not through it
            pose.scale(size,size,size);
            int seed=boat.getId()*31+i;
            if(shape==Form.STEM){
                // A stem bunch folded once and pushed into the gap: two halves opening from the fold at the picture's foot.
                for(int side=-1;side<=1;side+=2){
                    pose.pushPose();pose.translate(0,-.5f,0);pose.mulPose(Axis.XP.rotationDegrees(side*22));pose.mulPose(Axis.YP.rotationDegrees(side*14));pose.translate(0,.5f,0);
                    renderer.renderStatic(stack,ItemDisplayContext.NONE,light,OverlayTexture.NO_OVERLAY,pose,buffers,boat.level(),seed+side);
                    pose.popPose();
                }
            }else renderer.renderStatic(stack,ItemDisplayContext.NONE,light,OverlayTexture.NO_OVERLAY,pose,buffers,boat.level(),seed);
            pose.popPose();
        }
        pose.popPose();
    }
    private HatCargoRenderer() {}
}
