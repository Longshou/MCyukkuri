package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
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
        var distress=AquaticMotionPresentation.motion(entity,partialTick);
        String expression = YukkuriModel.get(entity.subspecies().assetId())
                .expressionFor(distress!=null?distress.clip():entity.getAnimationId(),distress!=null?distress.seconds():elapsed,entity.getExpressionId());
        if (ToiletPresentation.authored(entity.getBehaviorId()) && distress == null)
            expression=YukkuriModel.get(entity.subspecies().assetId()).expressionFor(entity.getBehaviorId(),
                    Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick)/20f,entity.getExpressionId());
        if(entity.getBehaviorId().equals(Behaviors.BOAT_RESCUE_APPROACH)&&entity.aquaticRescueTargetId()>=0)expression="teary";
        if(CabinDistressPresentation.alarmSelected(entity)&&(entity.aquaticAlarmActivity().equals(Behaviors.BOAT_FLEE)||entity.aquaticAlarmActivity().equals(Behaviors.BOAT_RETURN_CALLED)))expression="teary";
        // A calm seated clip must not replace the actual crew's frightened face during a shared tilt.
        if(distress==null&&entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat
                &&(boat.rocking()||boat.capsizing()))expression=!entity.isBaby()?"surprise":"teary";
        expression=AgitationPresentation.expression(entity,expression);
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
            float renderedBodyYaw=Mth.rotLerp(partialTick,entity.yBodyRotO,entity.yBodyRot);
            var boardingPose=entity.boardingState();var selfRescuePose=entity.aquaticSelfRescueState();
            boolean controlledContact=boardingPose.getBoolean("Active")||selfRescuePose.contains("BodyYaw")||BraidRescuePresentation.active(entity);
            if(boardingPose.getBoolean("Active")&&boardingPose.contains("BodyYaw"))
                renderedBodyYaw=Mth.rotLerp(partialTick,boardingPose.getFloat("PrevBodyYaw"),boardingPose.getFloat("BodyYaw"));
            else if(selfRescuePose.contains("BodyYaw"))
                renderedBodyYaw=Mth.rotLerp(partialTick,selfRescuePose.getFloat("PrevBodyYaw"),selfRescuePose.getFloat("BodyYaw"));
            var rockingHat=entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat?boat
                    :hatParent!=null&&hatParent.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity carriedBoat?carriedBoat:null;
            if(rockingHat!=null)HatBoatMotion.rider(pose,entity,rockingHat,partialTick);
            if(hatParent!=null){
                var offset=io.github.longshou.mcyukkuri.family.HatCarry.seatWorld(hatParent,partialTick).subtract(entity.getPosition(partialTick));
                pose.translate(offset.x,offset.y,offset.z);
                pose.mulPose(io.github.longshou.mcyukkuri.family.HatCarry.riderRotation(hatParent,partialTick));
            }else {
                float bodyYaw=renderedBodyYaw;
                String state=entity.getBehaviorId(),waterPhase=entity.waterDistressPhase();
                if(!controlledContact&&(state.equals(Behaviors.BOAT_REASSURE)||state.equals(Behaviors.BOAT_CROWD_GUARD)
                        ||waterPhase.equals("IMPACT")||waterPhase.equals("STRUGGLE")||waterPhase.equals("EXHAUSTED"))){
                    float headYaw=Mth.rotLerp(partialTick,entity.yHeadRotO,entity.getYHeadRot());
                    bodyYaw+=Mth.clamp(Mth.wrapDegrees(headYaw-bodyYaw),-35,35);
                }
                pose.mulPose(Axis.YP.rotationDegrees(180-bodyYaw));
                renderedBodyYaw=bodyYaw;
            }
            if(rockingHat!=null&&hatParent==null)CabinDistressPresentation.balance(pose,entity,rockingHat,partialTick,renderedBodyYaw);
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
            // Little filling left: the body sags (squash only, no tilt); WoundPlacement.transform applies the same on the server.
            {int filling=entity.visibleFilling();pose.scale(io.github.longshou.mcyukkuri.body.BodySigns.sagWidth(filling),io.github.longshou.mcyukkuri.body.BodySigns.sagHeight(filling),io.github.longshou.mcyukkuri.body.BodySigns.sagWidth(filling));}
            if(contact.equals("VIBRATE"))pose.translate(Math.sin((entity.tickCount+partialTick)*2)*.008,0,0);
            float elapsed = Math.max(0, (entity.level().getGameTime() - entity.getAnimationStartTick() + partialTick) / 20f);
            int overlay = OverlayTexture.pack(0, entity.hurtTime > 0 || entity.deathTime > 0);
            var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(textureAt(entity, partialTick)));
            String behavior = hatCarrier||hatParent!=null?Behaviors.IDLE:entity.getBehaviorId();
            if(behavior.equals(Behaviors.SLIDE_DOWN)){
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
                behavior = Behaviors.HOUSE_CRAWL;
                phaseTicks = Math.max(0, entity.level().getGameTime()-entity.getCrawlStartTick()+partialTick);
            }
            // Models with the world-setting sleep clips breathe and wake in the clip itself.
            if (!model.hasClip("sleep")) {
                if (behavior.equals(Behaviors.SLEEP_ENTER)) pose.scale(1, 1 - Math.min(1, phaseTicks/20)*.04f, 1);
                else if (behavior.equals(Behaviors.SLEEP)) pose.scale(1, .96f, 1);
                else if (behavior.equals(Behaviors.WAKE)) pose.scale(1, .96f + Math.min(1, phaseTicks/20)*.04f, 1);
            }
            model.setCarrying(!entity.getCarriedItem().isEmpty());
            model.setMouthHolding(HeldItemPresentation.closedGrip(entity));
            configureCareContact(entity,behavior,partialTick,model);
            BraidRescuePresentation.configure(entity,model,partialTick,renderedBodyYaw);
            CabinDistressPresentation.configureBoardingContact(entity,model,partialTick,renderedBodyYaw);
            SelfRescueHatPresentation.configure(entity,model,partialTick,renderedBodyYaw);
            var delivery=LiveBirthRenderer.prepare(entity,model,partialTick);
            model.setPregnancy(entity.livePregnancyProgress(),LiveBirthRenderer.contraction(entity,partialTick));model.setDelivery(delivery);
            String action=hatParent!=null?"idle":hatCarrier?io.github.longshou.mcyukkuri.family.HatCarry.carryAction(entity):entity.getAnimationId();
            var distress=AquaticMotionPresentation.motion(entity,partialTick);
            if(rockingHat!=null&&(rockingHat.rocking()||rockingHat.capsizing())&&!operated)
                action=entity.getUUID().equals(rockingHat.ownerYukkuri())?"boat_steady":"hat_cradle";
            if(distress!=null&&!operated&&model.hasClip(distress.clip())){action=distress.clip();elapsed=distress.seconds();}
            model.renderLiving(operated||behavior.equals(Behaviors.SLIDE_DOWN)?"idle":action, operated||hatParent!=null?0:elapsed, pose, buffer, packedLight, overlay,
                    entity.hasVisibleAccessory(), operated?Behaviors.IDLE:distress!=null?distress.clip():behavior, distress!=null?distress.seconds()*20:phaseTicks,entity.visibleHairLoss(),entity,buffers);
            // Captured before anything else poses this shared model (stem fruits reuse it).
            ToiletPresentation.renderTongue(entity,behavior,phaseTicks,partialTick,model,pose,buffers,packedLight,overlay);
            ToiletPresentation.renderStool(entity,behavior,phaseTicks,model,pose,buffers,packedLight);
            var root=model.rootPose();
            // The captain keeps holding the paddle while she offers the brim or nudges the child.
            if ((action.equals("boat_paddle")||action.equals("hat_brim_offer")||action.equals("boat_nudge_young")) && entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat
                    && boat.cargo().paddle() && entity.getUUID().equals(boat.ownerYukkuri())) {
                var grip = model.paddlePose();
                if (grip != null) {
                    pose.pushPose(); pose.mulPose(grip);
                    if(action.equals("boat_paddle"))AquaticMotionPresentation.recoverPaddle(pose,boat,partialTick);
                    pose.translate(0,-.22,0);
                    pose.mulPose(Axis.ZP.rotationDegrees(45)); pose.scale(.85f,.85f,.85f);
                    Minecraft.getInstance().getItemRenderer().renderStatic(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK),
                            ItemDisplayContext.FIXED,packedLight,overlay,pose,buffers,entity.level(),entity.getId());
                    pose.popPose();
                }
            }
            if(entity.hasVisibleAccessory()||contact.equals("HAT_REMOVE")) model.renderBadge(entity.visibleMedal(),pose,buffers,packedLight,overlay);
            if(entity.visibleWear()!=0&&!hatCarrier&&hatParent==null)CareWearRenderer.render(model,entity.visibleWear(),pose,buffers,packedLight,overlay);
            var presentation=LivingPresentation.variants.get(entity.subspecies().assetId());
            for(int heldIndex=0;heldIndex<2;heldIndex++){
                // A prepared mash remains at the caregiver's mouth until the server atomically
                // places that same stack in the child's mouth. Boat care follows the same path;
                // food never travels on the braid or paddle as an independent projectile.
                boolean aquaticCare=heldIndex==1&&!entity.aquaticServing().isEmpty()
                        &&(behavior.equals(Behaviors.FAMILY_CHEW)||behavior.equals(Behaviors.BOAT_FEED_YOUNG));
                var held=heldIndex==0?entity.getMouthItem():aquaticCare?entity.aquaticServing():entity.getCarriedItem();
                if(held.isEmpty())continue;
                boolean mash=io.github.longshou.mcyukkuri.life.InfantFood.isMash(held);
                // The parent's real pending twig is mouth-held until the server hands that same item to the child.
                boolean givingPaddle=heldIndex==1&&behavior.equals(Behaviors.BOAT_GIVE_PADDLE);
                var grip=heldIndex==1&&!behavior.equals(Behaviors.FAMILY_CHEW)&&!aquaticCare&&!mash&&!givingPaddle?model.gripPoint():null;
                pose.pushPose();
                if(grip!=null)pose.translate(grip.x,grip.y,grip.z);
                else HeldItemPresentation.mouth(pose,model);
                if(mash){
                    float portionScale=entity.getVariant().isHouse()?.35f:1;
                    pose.scale(portionScale,portionScale,portionScale);
                    PropModels.render("food_mash_serving",1,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                String houseProp=grip==null?null:HouseScrapRenderer.smallProp(held);
                if(houseProp!=null){
                    // Bypass inventory display scaling: the same real lint/egg keeps its size
                    // from the floor to the authored hair grip and back into the nest.
                    float scale=HouseScrapRenderer.smallScale(houseProp)/Math.max(.01f,entity.growthScale()*entity.getScale());pose.scale(scale,scale,scale);
                    PropModels.center(houseProp,pose,false);
                    PropModels.render(houseProp,8,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                if(heldIndex==1&&(behavior.startsWith(Behaviors.NEST_PREFIX)||behavior.startsWith(Behaviors.INDEPENDENCE_PREFIX)||behavior.equals(Behaviors.SHELTER_CARRY))
                        &&(held.is(net.minecraft.tags.ItemTags.LEAVES)||held.is(net.minecraft.world.item.Items.SHORT_GRASS)||held.is(net.minecraft.world.item.Items.WHEAT))){
                    HeldItemPresentation.bedding(pose);
                    PropModels.render("bedding_bundle",0,pose,buffers,packedLight,overlay);pose.popPose();continue;
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
            if(!operated&&HeldItemPresentation.kanroken(entity,behavior)){
                // A Myon's stick sword rides crosswise in the free mouth, on the same mouth pose as food (rare/MyonLife).
                pose.pushPose();HeldItemPresentation.mouth(pose,model);
                PropModels.render("kanroken",1,pose,buffers,packedLight,overlay);pose.popPose();
            }
            if(contact.equals("PIN"))model.renderSurfaceProp("pin_tool",1,.6f,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("VIBRATE"))model.renderSurfaceProp("care_vibrator",1,1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("ASSIST"))LiveBirthRenderer.renderDose(entity,pose,buffers,packedLight);
            if(contact.equals("HOOK")||contact.equals("WRAPPED"))OperationAttachments.render(entity,model,pose,buffers,packedLight,contactAngle,lift,partialTick);
            if(delivery!=null)delivery.render(pose,buffers,packedLight);
            model.setDelivery(null);model.setPregnancy(0,0);
            pose.popPose();
            pose.popPose();
            // The stem grows from the forehead: carry it with the body's root motion, in this block-scaled space.
            pose.pushPose();
            var follow=new org.joml.Matrix4f(root);float units=entity.growthScale()/16f;
            follow.m30(follow.m30()*units);follow.m31(follow.m31()*units);follow.m32(follow.m32()*units);
            pose.last().pose().mul(follow);pose.last().normal().mul(new org.joml.Matrix3f(follow));
            FamilyRenderer.render(entity,pose,buffers,packedLight);
            pose.popPose();
            pose.popPose();
        }
        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
        RapemOverlayRenderer.render(entity, partialTick, pose, buffers, packedLight);
        SpeechBubble.render(entity, partialTick, pose, buffers, packedLight);
    }

    /**
     * Follow the server's care partner: feeding reaches the child's mouth, while first-toilet
     * assistance reaches its rear. Model-space anchors differ with age, so close the rendered
     * gap with a bounded root lean while keeping the caregiver's body supported.
     */
    private static void configureCareContact(YukkuriEntity parent,String behavior,float partialTick,YukkuriModel model) {
        boolean firstToilet=behavior.equals(Behaviors.FIRST_TOILET_ASSIST);
        if (!(behavior.equals(Behaviors.FEED_CHILD) || behavior.equals(Behaviors.BOAT_FEED_YOUNG) || firstToilet) || parent.carePartnerId() < 0
                || !(parent.level().getEntity(parent.carePartnerId()) instanceof YukkuriEntity child)) {
            model.setCareContact(0,Float.NaN,0,0);
            return;
        }
        var parentPresentation=LivingPresentation.variants.get(parent.subspecies().assetId());
        var childPresentation=LivingPresentation.variants.get(child.subspecies().assetId());
        if (parentPresentation == null || childPresentation == null) { model.setCareContact(0,Float.NaN,0,0); return; }
        float parentScale=parent.getScale()*parent.growthScale();
        float childScale=child.getScale()*child.growthScale();
        var parentAt=parent.getPosition(partialTick); var childAt=child.getPosition(partialTick);
        float parentYaw=(180-Mth.rotLerp(partialTick,parent.yBodyRotO,parent.yBodyRot))*Mth.DEG_TO_RAD;
        float childYaw=(180-Mth.rotLerp(partialTick,child.yBodyRotO,child.yBodyRot))*Mth.DEG_TO_RAD;
        // The first-toilet rear uses the same body-width offset and height as its physical
        // land drop. In a hat this is the assistance point, before the child moves to the brim.
        var target=firstToilet?new org.joml.Vector3f(0,.04f,child.getBbWidth()*.55f)
                :new org.joml.Vector3f(childPresentation.x(),childPresentation.y(),childPresentation.z()).mul(childScale);
        target.rotateY(childYaw)
                .add((float)(childAt.x-parentAt.x),(float)(childAt.y-parentAt.y),(float)(childAt.z-parentAt.z))
                .rotateY(-parentYaw).mul(16/Math.max(.001f,parentScale));
        float blend=.35f+.65f*Mth.clamp(parent.careContactProgress(),0,1);
        model.setCareContact(target.x,target.y,target.z,blend);
    }

    private static void dailyMotion(YukkuriEntity entity,float partialTick,PoseStack pose) {
        AgitationPresentation.pose(entity,partialTick,pose);
        String behavior=entity.getBehaviorId(),contact=entity.contactPose();
        boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
        float phaseTicks=Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick);
        float wave=(float)Math.sin(phaseTicks*Math.PI/20);
        if (!operated && (behavior.equals(Behaviors.BOAT_FEED_YOUNG) || behavior.equals(Behaviors.BOAT_NUZZLE))
                && entity.level().getEntity(entity.aquaticChildId()) instanceof YukkuriEntity young
                && entity.getVehicle() != null && entity.getVehicle() == young.getVehicle()) {
            // Turn only the seated model toward this child. Changing the captain's body yaw would steer the whole boat.
            var delta = young.getPosition(partialTick).subtract(entity.getPosition(partialTick));
            float yaw = (float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            float blend = Math.min(1,phaseTicks/8)*Math.min(1,Math.max(0,(48-phaseTicks)/8));
            pose.mulPose(Axis.YP.rotationDegrees(Mth.wrapDegrees(Mth.rotLerp(partialTick,entity.yBodyRotO,entity.yBodyRot)-yaw)*blend));
        }
        // The nuzzle, sing and sway clips carry the rubbing and swaying; only the approach stays here.
        if(YukkuriModel.get(entity.subspecies().assetId()).hasClip(entity.getAnimationId())&&!contact.equals("RUB"))wave=0;
        if(!operated&&behavior.equals(Behaviors.NUZZLE)||contact.equals("RUB")) {
            float lean=0;
            if(behavior.equals(Behaviors.NUZZLE)&&entity.level().getEntity(entity.nuzzlePartnerId()) instanceof YukkuriEntity peer
                    &&peer.nuzzlePartnerId()==entity.getId()&&peer.getBehaviorId().equals(Behaviors.NUZZLE)) {
                float scale=entity.getScale()*entity.growthScale(),peerScale=peer.getScale()*peer.growthScale();
                double distance=entity.getPosition(partialTick).subtract(peer.getPosition(partialTick)).horizontalDistance();
                double gap=distance-YukkuriModel.get(entity.subspecies().assetId()).faceDepth()*scale
                        -YukkuriModel.get(peer.subspecies().assetId()).faceDepth()*peerScale;
                float blend=Math.min(1,phaseTicks/8)*Math.min(1,Math.max(0,(60-phaseTicks)/8));
                if(entity.getScale()>0)lean=(float)Math.clamp((gap-.006)/2,0,.2)/entity.getScale()*blend;
            }
            pose.translate(wave*.015*entity.growthScale(),0,-lean);
            pose.mulPose(Axis.YP.rotationDegrees(wave*6));
        } else if(!operated&&(behavior.equals(Behaviors.SING)||behavior.equals(Behaviors.EXERCISE_WATCH))) {
            pose.mulPose(Axis.YP.rotationDegrees(wave*4));
        }
    }

}
