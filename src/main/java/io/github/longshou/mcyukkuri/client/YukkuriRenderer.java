package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemDisplayContext;

public final class YukkuriRenderer extends EntityRenderer<YukkuriEntity> {
    public YukkuriRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = .32f;
    }

    @Override
    public boolean shouldRender(YukkuriEntity entity, Frustum frustum, double x, double y, double z) {
        if (super.shouldRender(entity, frustum, x, y, z)) return true;
        // The body-sized collision box excludes tall hats and animated stretches.
        double margin = entity.broodCount()>0?3:entity.getVariant().isHouse() ? .4 : 1.2;
        return entity.shouldRender(x, y, z) && frustum.isVisible(entity.getBoundingBoxForCulling().inflate(margin));
    }

    @Override
    public ResourceLocation getTextureLocation(YukkuriEntity entity) {
        return textureAt(entity, 0);
    }

    private ResourceLocation textureAt(YukkuriEntity entity, float partialTick) {
        float elapsed = Math.max(0,(entity.level().getGameTime()-entity.getAnimationStartTick()+partialTick)/20f);
        String expression = YukkuriModel.get(entity.subspecies().assetId())
                .expressionFor(entity.getAnimationId(),elapsed,entity.getExpressionId());
        return MCYukkuri.id("textures/entity/" + entity.subspecies().assetId() + "/" + expression + ".png");
    }

    @Override
    public void render(YukkuriEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        if(entity.isDeadOrDying())return;
        shadowRadius = (entity.getVariant().isHouse() ? .12f : .32f)*entity.growthScale();
        if (!entity.isInvisible()) {
            pose.pushPose();
            var hatParent=entity.getVehicle() instanceof YukkuriEntity parent&&io.github.longshou.mcyukkuri.family.HatCarry.riding(parent,entity)?parent:null;
            boolean hatCarrier=entity.isVehicle()&&io.github.longshou.mcyukkuri.family.HatCarry.hasHat(entity);
            if(hatParent!=null){
                var offset=io.github.longshou.mcyukkuri.family.HatCarry.seatWorld(hatParent,partialTick).subtract(entity.getPosition(partialTick));
                pose.translate(offset.x,offset.y,offset.z);
                pose.mulPose(io.github.longshou.mcyukkuri.family.HatCarry.riderRotation(hatParent,partialTick));
            }else pose.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)));
            if (entity.deathTime > 0) pose.mulPose(Axis.ZP.rotationDegrees(Math.min(90, (entity.deathTime + partialTick) * 4.5f)));
            pose.scale(entity.getScale(),entity.getScale(),entity.getScale());
            // Keep the body, badges, carried items and stem family under the same daily-motion transform.
            if(!hatCarrier&&hatParent==null)dailyMotion(entity,partialTick,pose);
            pose.pushPose();pose.scale(entity.growthScale(),entity.growthScale(),entity.growthScale());
            var model=YukkuriModel.get(entity.subspecies().assetId());
            String contact=entity.contactPose();boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
            if(contact.equals("BOX"))PropModels.render("iron_enclosure_"+entity.subspecies().assetId(),1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("FIXED")||contact.equals("WRAPPED")){
                PropModels.render((contact.equals("FIXED")?"restraint_frame_":"wrap_sling_")+entity.subspecies().assetId()+(entity.hasVisibleAccessory()?"":"_bare"),1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            }
            float contactAngle=switch(contact){case "SUPINE","FIXED","HOOK"->90;case "ROLL"->entity.contactTurn();case "RUMP","ASSIST"->-12;default->0;};
            float lift=contact.equals("FIXED")?PropModels.bodyLift("restraint_frame_"+entity.subspecies().assetId()+(entity.hasVisibleAccessory()?"":"_bare")):
                    contactAngle==0?0:model.contactLift(contactAngle,entity.hasVisibleAccessory(),entity.visibleHairLoss());
            pose.pushPose();
            if(contactAngle!=0){
                pose.translate(0,lift,0);
                pose.mulPose(Axis.XP.rotationDegrees(contactAngle));
            }
            if(contact.equals("GRIP"))pose.scale(1.1f,.85f,1.1f);
            if(contact.equals("VIBRATE"))pose.translate(Math.sin((entity.tickCount+partialTick)*2)*.008,0,0);
            float elapsed = Math.max(0, (entity.level().getGameTime() - entity.getAnimationStartTick() + partialTick) / 20f);
            int overlay = OverlayTexture.pack(0, entity.hurtTime > 0 || entity.deathTime > 0);
            var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(textureAt(entity, partialTick)));
            String behavior = hatCarrier||hatParent!=null?"idle":entity.getBehaviorId();
            if(behavior.equals("slide_down")){
                var facility=io.github.longshou.mcyukkuri.play.PlayBlock.facility(entity.level(),entity.blockPosition());
                if(facility!=null&&facility.kind()==io.github.longshou.mcyukkuri.play.PlayBlock.Kind.SLIDE){
                    var relative=entity.getPosition(partialTick).subtract(net.minecraft.world.phys.Vec3.atBottomCenterOf(facility.getBlockPos()));
                    double along=relative.x*facility.facing().getStepX()+relative.z*facility.facing().getStepZ();
                    float blend=(float)(Mth.clamp((along+.1)/.5,0,1)*Mth.clamp((2.7-along)/.4,0,1));
                    pose.translate(0,-.375*(15.0/34)*blend,0);
                    pose.mulPose(Axis.XP.rotationDegrees((float)(-Math.toDegrees(Math.atan(15.0/34))*blend)));
                }
            }
            float phaseTicks = Math.max(0, entity.level().getGameTime() - entity.getBehaviorStartTick() + partialTick);
            if (entity.isCrawling()&&!hatCarrier&&hatParent==null) {
                behavior = "house_crawl";
                phaseTicks = Math.max(0, entity.level().getGameTime()-entity.getCrawlStartTick()+partialTick);
            }
            // Models with the world-setting sleep clips breathe and wake in the clip itself.
            if (!model.hasClip("sleep")) {
                if (behavior.equals("sleep_enter")) pose.scale(1, 1 - Math.min(1, phaseTicks/20)*.04f, 1);
                else if (behavior.equals("sleep")) pose.scale(1, .96f, 1);
                else if (behavior.equals("wake")) pose.scale(1, .96f + Math.min(1, phaseTicks/20)*.04f, 1);
            }
            model.setCarrying(!entity.getCarriedItem().isEmpty());
            var delivery=LiveBirthRenderer.prepare(entity,model,partialTick);
            model.setPregnancy(entity.livePregnancyProgress(),LiveBirthRenderer.contraction(entity,partialTick));model.setDelivery(delivery);
            String action=hatParent!=null?"idle":hatCarrier?io.github.longshou.mcyukkuri.family.HatCarry.carryAction(entity):entity.getAnimationId();
            model.renderLiving(operated||behavior.equals("slide_down")?"idle":action, operated||hatParent!=null?0:elapsed, pose, buffer, packedLight, overlay,
                    entity.hasVisibleAccessory(), operated?"idle":behavior, phaseTicks,entity.visibleHairLoss(),entity,buffers);
            if(entity.hasVisibleAccessory()||contact.equals("HAT_REMOVE")) model.renderBadge(entity.visibleMedal(),pose,buffers,packedLight,overlay);
            var presentation=LivingPresentation.variants.get(entity.subspecies().assetId());
            for(int heldIndex=0;heldIndex<2;heldIndex++){
                var held=heldIndex==0?entity.getMouthItem():entity.getCarriedItem();
                if(held.isEmpty())continue;
                var grip=heldIndex==1?model.gripPoint():null;
                pose.pushPose();
                if(grip!=null)pose.translate(grip.x,grip.y,grip.z);
                else {
                    var mouth=new org.joml.Vector3f(presentation.x()*16,presentation.y()*16,presentation.z()*16);
                    io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(mouth,entity.livePregnancyProgress(),0);
                    pose.translate(mouth.x/16,mouth.y/16,mouth.z/16);
                }
                if(heldIndex==0&&io.github.longshou.mcyukkuri.life.Consumables.oral(held)){
                    String serving=io.github.longshou.mcyukkuri.life.Consumables.water(held)?"water_serving":switch(io.github.longshou.mcyukkuri.life.Consumables.kind(held)){
                        case YOGURT->"yogurt_serving";case STIMULANT->"stimulant_serving";default->"sitataran_serving";
                    };
                    float portionScale=entity.getVariant().isHouse()?.35f:1;pose.scale(portionScale,portionScale,portionScale);
                    PropModels.render(serving,1,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                float itemScale=grip!=null&&held.is(io.github.longshou.mcyukkuri.registry.ModItems.FOOD_SCRAP.get())?HouseScrapRenderer.HELD_SCALE:presentation.foodScale();
                pose.scale(itemScale,itemScale,itemScale);
                Minecraft.getInstance().getItemRenderer().renderStatic(held,ItemDisplayContext.FIXED,packedLight,overlay,
                        pose,buffers,entity.level(),entity.getId());
                pose.popPose();
            }
            if(contact.equals("PIN"))model.renderSurfaceProp("pin_tool",1,.6f,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("VIBRATE"))model.renderSurfaceProp("care_vibrator",1,1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("ASSIST"))LiveBirthRenderer.renderDose(entity,pose,buffers,packedLight);
            if(contact.equals("HOOK")||contact.equals("WRAPPED"))OperationAttachments.render(entity,model,pose,buffers,packedLight,contactAngle,lift,partialTick);
            if(delivery!=null)delivery.render(pose,buffers,packedLight);
            model.setDelivery(null);model.setPregnancy(0,0);
            pose.popPose();
            pose.popPose();
            FamilyRenderer.render(entity,pose,buffers,packedLight);
            pose.popPose();
        }
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
        SpeechBubble.render(entity, partialTick, pose, buffers, packedLight);
    }

    private static void dailyMotion(YukkuriEntity entity,float partialTick,PoseStack pose) {
        String behavior=entity.getBehaviorId(),contact=entity.contactPose();
        boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
        float phaseTicks=Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick);
        float wave=(float)Math.sin(phaseTicks*Math.PI/20);
        // The nuzzle, sing and sway clips carry the rubbing and swaying; only the approach stays here.
        if(YukkuriModel.get(entity.subspecies().assetId()).hasClip(entity.getAnimationId())&&!contact.equals("RUB"))wave=0;
        if(!operated&&behavior.equals("nuzzle")||contact.equals("RUB")) {
            float lean=0;
            if(behavior.equals("nuzzle")&&entity.level().getEntity(entity.nuzzlePartnerId()) instanceof YukkuriEntity peer
                    &&peer.nuzzlePartnerId()==entity.getId()&&peer.getBehaviorId().equals("nuzzle")) {
                float scale=entity.getScale()*entity.growthScale(),peerScale=peer.getScale()*peer.growthScale();
                double distance=entity.getPosition(partialTick).subtract(peer.getPosition(partialTick)).horizontalDistance();
                double gap=distance-YukkuriModel.get(entity.subspecies().assetId()).faceDepth()*scale
                        -YukkuriModel.get(peer.subspecies().assetId()).faceDepth()*peerScale;
                float blend=Math.min(1,phaseTicks/8)*Math.min(1,Math.max(0,(60-phaseTicks)/8));
                if(entity.getScale()>0)lean=(float)Math.clamp((gap-.006)/2,0,.2)/entity.getScale()*blend;
            }
            pose.translate(wave*.015*entity.growthScale(),0,-lean);
            pose.mulPose(Axis.YP.rotationDegrees(wave*6));
        } else if(!operated&&(behavior.equals("sing")||behavior.equals("exercise_watch"))) {
            pose.mulPose(Axis.YP.rotationDegrees(wave*4));
        }
    }
}
