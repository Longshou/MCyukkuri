package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.HatCarry;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class YukkuriRenderer extends EntityRenderer<YukkuriEntity> {
    public YukkuriRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = .32f;
    }

    @Override
    public boolean shouldRender(YukkuriEntity source, Frustum frustum, double x, double y, double z) {
        YukkuriView entity=source;
        if (super.shouldRender(source, frustum, x, y, z)) return true;
        // The body-sized collision box excludes tall hats and animated stretches.
        double margin = entity.broodCount()>0?3:entity.profile().houseBody() ? .4 : 1.2;
        return source.shouldRender(x, y, z) && frustum.isVisible(entity.getBoundingBoxForCulling().inflate(margin));
    }

    @Override
    public ResourceLocation getTextureLocation(YukkuriEntity source) {
        YukkuriView entity=source;
        return textureAt(entity, 0);
    }

    private ResourceLocation textureAt(YukkuriView entity, float partialTick) {
        float elapsed = Math.max(0,(entity.level().getGameTime()-entity.getAnimationStartTick()+partialTick)/20f);
        var distress=AquaticMotionPresentation.motion(entity,partialTick);
        String expression = YukkuriModel.get(entity.assetId())
                .expressionFor(distress!=null?distress.clip():entity.getAnimationId(),distress!=null?distress.seconds():elapsed,entity.getExpressionId());
        if (ToiletPresentation.authored(entity.getBehaviorId()) && distress == null)
            expression=YukkuriModel.get(entity.assetId()).expressionFor(entity.getBehaviorId(),
                    Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick)/20f,entity.getExpressionId());
        if(entity.getBehaviorId().equals(Behaviors.BOAT_RESCUE_APPROACH)&&entity.aquaticRescueTargetId()>=0)expression="teary";
        if(CabinDistressPresentation.alarmSelected(entity)&&(entity.aquaticAlarmActivity().equals(Behaviors.BOAT_FLEE)||
                entity.aquaticAlarmActivity().equals(Behaviors.BOAT_RETURN_CALLED)))expression="teary";
        // A calm seated clip must not replace the actual crew's frightened face during a shared tilt.
        HatBoatView boat;
        if(distress==null&&(boat=entity.vehicleHat())!=null
                &&(boat.rocking()||boat.capsizing()))expression=!entity.isBaby()?"surprise":"teary";
        expression=AgitationPresentation.expression(entity,expression);
        return MCYukkuri.id("textures/entity/" + entity.assetId() + "/" + expression + ".png");
    }

    @Override
    public void render(YukkuriEntity source, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        YukkuriView entity=source;
        if(entity.isDeadOrDying())return;
        shadowRadius = (entity.profile().shadowRadius())*entity.growthScale();
        if (!entity.isInvisible()) {
            pose.pushPose();
            var parent=entity.vehicleYukkuri();
            var hatParent=parent!=null&&HatCarry.visibleRiding(parent,entity)?parent:null;
            boolean hatCarrier=entity.isVehicle()&&HatCarry.hasHat(entity);
            float renderedBodyYaw=Mth.rotLerp(partialTick,entity.previousBodyYaw(),entity.bodyYaw());
            var boardingPose=entity.boardingState();var selfRescuePose=entity.aquaticSelfRescueState();
            boolean controlledContact=boardingPose.getBoolean("Active")||selfRescuePose.contains("BodyYaw")||BraidRescuePresentation.active(entity);
            if(boardingPose.getBoolean("Active")&&boardingPose.contains("BodyYaw"))
                renderedBodyYaw=Mth.rotLerp(partialTick,boardingPose.getFloat("PrevBodyYaw"),boardingPose.getFloat("BodyYaw"));
            else if(selfRescuePose.contains("BodyYaw"))
                renderedBodyYaw=Mth.rotLerp(partialTick,selfRescuePose.getFloat("PrevBodyYaw"),selfRescuePose.getFloat("BodyYaw"));
            var boat=entity.vehicleHat();
            var rockingHat=boat!=null?boat:hatParent!=null?hatParent.vehicleHat():null;
            if(rockingHat!=null)HatBoatMotion.rider(pose,entity,rockingHat,partialTick);
            if(hatParent!=null){
                var offset=HatCarry.seatWorld(hatParent,partialTick).subtract(entity.getPosition(partialTick));
                pose.translate(offset.x,offset.y,offset.z);
                pose.mulPose(HatCarry.riderRotation(hatParent,partialTick));
            }else {
                float bodyYaw=renderedBodyYaw;
                String state=entity.getBehaviorId(),waterPhase=entity.waterDistressPhase();
                if(!controlledContact&&(state.equals(Behaviors.BOAT_REASSURE)||state.equals(Behaviors.BOAT_CROWD_GUARD)
                        ||waterPhase.equals("IMPACT")||waterPhase.equals("STRUGGLE")||waterPhase.equals("EXHAUSTED"))){
                    float headYaw=Mth.rotLerp(partialTick,entity.previousHeadYaw(),entity.getYHeadRot());
                    bodyYaw+=Mth.clamp(Mth.wrapDegrees(headYaw-bodyYaw),-35,35);
                }
                pose.mulPose(Axis.YP.rotationDegrees(180-bodyYaw));
                renderedBodyYaw=bodyYaw;
            }
            if(rockingHat!=null&&hatParent==null)CabinDistressPresentation.balance(pose,entity,rockingHat,partialTick,renderedBodyYaw);
            if (entity.deathTicks() > 0) pose.mulPose(Axis.ZP.rotationDegrees(Math.min(90, (entity.deathTicks() + partialTick) * 4.5f)));
            pose.scale(entity.getScale(),entity.getScale(),entity.getScale());
            // Keep the body, badges, carried items and stem family under the same daily-motion transform.
            if(!hatCarrier&&hatParent==null)dailyMotion(entity,partialTick,pose);
            pose.pushPose();pose.scale(entity.growthScale(),entity.growthScale(),entity.growthScale());
            var model=YukkuriModel.get(entity.assetId());
            String contact=entity.contactPose();boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
            if(contact.equals("BOX"))PropModels.render("iron_enclosure_"+entity.assetId(),1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("FIXED")||contact.equals("WRAPPED")){
                PropModels.render((contact.equals("FIXED")?"restraint_frame_":"wrap_sling_")+entity.assetId()+(entity.hasVisibleAccessory()?"":
                        "_bare"),1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            }
            float contactAngle=switch(contact){case "SUPINE","FIXED","HOOK"->90;case "ROLL"->entity.contactTurn();case "RUMP","ASSIST"->-12;default->0;};
            float lift=contact.equals("FIXED")?PropModels.bodyLift("restraint_frame_"+entity.assetId()+(entity.hasVisibleAccessory()?"":"_bare")):
                    contactAngle==0?0:model.contactLift(contactAngle,entity.hasVisibleAccessory(),entity.visibleHairLoss());
            pose.pushPose();
            if(contactAngle!=0){
                pose.translate(0,lift,0);
                pose.mulPose(Axis.XP.rotationDegrees(contactAngle));
            }
            if(contact.equals("GRIP"))pose.scale(1.1f,.85f,1.1f);
            // Only a low-health body with open skin sags; WoundPlacement.transform applies the same curve on the server.
            boolean open=entity.visibleWounds().stream().anyMatch(BodySigns::open);
            float health=entity.getMaxHealth()<=0?1:entity.getHealth()/entity.getMaxHealth();
            pose.scale(BodySigns.sagWidth(health,open),BodySigns.sagHeight(health,open),BodySigns.sagWidth(health,open));
            if(contact.equals("VIBRATE"))pose.translate(Math.sin((entity.ageTicks()+partialTick)*2)*.008,0,0);
            float elapsed = Math.max(0, (entity.level().getGameTime() - entity.getAnimationStartTick() + partialTick) / 20f);
            int overlay = OverlayTexture.pack(0, entity.hurtTicks() > 0 || entity.deathTicks() > 0);
            var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(textureAt(entity, partialTick)));
            String behavior = hatCarrier||hatParent!=null?Behaviors.IDLE:entity.getBehaviorId();
            if(behavior.equals(Behaviors.SLIDE_DOWN)){
                var facility=PlayBlock.facility(entity.level(),entity.blockPosition());
                if(facility!=null&&facility.kind()==PlayBlock.Kind.SLIDE){
                    var relative=entity.getPosition(partialTick).subtract(Vec3.atBottomCenterOf(facility.getBlockPos()));
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
            model.setMouthHolding(HeldItemPresentation.closedGrip(entity)||!entity.getMouthBodyFood().isEmpty());
            configureCareContact(entity,behavior,partialTick,model);
            BraidRescuePresentation.configure(entity,model,partialTick,renderedBodyYaw);
            CabinDistressPresentation.configureBoardingContact(entity,model,partialTick,renderedBodyYaw);
            SelfRescueHatPresentation.configure(entity,model,partialTick,renderedBodyYaw);
            var delivery=LiveBirthRenderer.prepare(entity,model,partialTick);
            model.setPregnancy(entity.livePregnancyProgress(),LiveBirthRenderer.contraction(entity,partialTick));model.setDelivery(delivery);
            String action=hatParent!=null?"idle":hatCarrier?HatCarry.carryAction(entity):entity.getAnimationId();
            var distress=AquaticMotionPresentation.motion(entity,partialTick);
            if(rockingHat!=null&&(rockingHat.rocking()||rockingHat.capsizing())&&!operated)
                action=entity.getUUID().equals(rockingHat.ownerYukkuri())?"boat_steady":"hat_cradle";
            if(distress!=null&&!operated&&model.hasClip(distress.clip())){action=distress.clip();elapsed=distress.seconds();}
            model.renderLiving(operated||behavior.equals(Behaviors.SLIDE_DOWN)?"idle":action, operated||hatParent!=null?0:elapsed, pose, buffer, packedLight, overlay,
                    entity.hasVisibleAccessory(), operated?Behaviors.IDLE:distress!=null?distress.clip():behavior,
                            distress!=null?distress.seconds()*20:phaseTicks,entity.visibleHairLoss(),entity,buffers);
            // Captured before anything else poses this shared model (stem fruits reuse it).
            ToiletPresentation.renderTongue(entity,behavior,phaseTicks,partialTick,model,pose,buffers,packedLight,overlay);
            ToiletPresentation.renderStool(entity,behavior,phaseTicks,model,pose,buffers,packedLight);
            var root=model.rootPose();
            // The captain keeps holding the paddle while she offers the brim or nudges the child.
            HatBoatView paddleBoat;
            if ((action.equals("boat_paddle")||action.equals("hat_brim_offer")||action.equals("boat_nudge_young")) && (paddleBoat=entity.vehicleHat())!=null
                    && paddleBoat.visiblePaddle() && entity.getUUID().equals(paddleBoat.ownerYukkuri())) {
                var grip = model.paddlePose();
                if (grip != null) {
                    pose.pushPose(); pose.mulPose(grip);
                    if(action.equals("boat_paddle"))AquaticMotionPresentation.recoverPaddle(pose,paddleBoat,partialTick);
                    pose.translate(0,-.22,0);
                    pose.mulPose(Axis.ZP.rotationDegrees(45)); pose.scale(.85f,.85f,.85f);
                    Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(Items.STICK),
                            ItemDisplayContext.FIXED,packedLight,overlay,pose,buffers,entity.level(),entity.getId());
                    pose.popPose();
                }
            }
            if(entity.hasVisibleAccessory()||contact.equals("HAT_REMOVE")) model.renderBadge(entity.visibleMedal(),pose,buffers,packedLight,overlay);
            if(entity.visibleWear()!=0&&!hatCarrier&&hatParent==null)CareWearRenderer.render(model,entity.visibleWear(),pose,buffers,packedLight,overlay);
            var presentation=LivingPresentation.variants.get(entity.assetId());
            if(!entity.getMouthBodyFood().isEmpty()) {
                pose.pushPose();
                HeldItemPresentation.mouth(pose,model);
                BodyFoodMouthRenderer.render(entity.getMouthBodyFood(),pose,buffers,packedLight,overlay);
                pose.popPose();
            }
            for(int heldIndex=0;heldIndex<2;heldIndex++){
                // A prepared mash remains at the caregiver's mouth until the server atomically
                // places that same stack in the child's mouth. Boat care follows the same path;
                // food never travels on the braid or paddle as an independent projectile.
                boolean aquaticCare=heldIndex==1&&!entity.aquaticServing().isEmpty()
                        &&(behavior.equals(Behaviors.FAMILY_CHEW)||behavior.equals(Behaviors.BOAT_FEED_YOUNG));
                var held=heldIndex==0?entity.getMouthItem():aquaticCare?entity.aquaticServing():entity.getCarriedItem();
                if(held.isEmpty())continue;
                boolean mash=InfantFood.isMash(held);
                // The parent's real pending twig is mouth-held until the server hands that same item to the child.
                boolean givingPaddle=heldIndex==1&&behavior.equals(Behaviors.BOAT_GIVE_PADDLE);
                var grip=heldIndex==1&&!behavior.equals(Behaviors.FAMILY_CHEW)&&!aquaticCare&&!mash&&!givingPaddle?model.gripPoint():null;
                pose.pushPose();
                if(grip!=null)pose.translate(grip.x,grip.y,grip.z);
                else HeldItemPresentation.mouth(pose,model);
                if(mash){
                    float portionScale=entity.profile().portionScale();
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
                        &&(held.is(ItemTags.LEAVES)||held.is(Items.SHORT_GRASS)||held.is(Items.WHEAT))){
                    HeldItemPresentation.bedding(pose);
                    PropModels.render("bedding_bundle",0,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                if(heldIndex==0&&Consumables.oral(held)){
                    String serving=Consumables.water(held)?"water_serving":switch(Consumables.kind(held)){
                        case YOGURT->"yogurt_serving";case STIMULANT->"stimulant_serving";default->"sitataran_serving";
                    };
                    float portionScale=entity.profile().portionScale();pose.scale(portionScale,portionScale,portionScale);
                    PropModels.render(serving,1,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                float itemScale=grip!=null&&held.is(ModItems.FOOD_SCRAP.get())?HouseScrapRenderer.HELD_SCALE:presentation.foodScale();
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
            var follow=new Matrix4f(root);float units=entity.growthScale()/16f;
            follow.m30(follow.m30()*units);follow.m31(follow.m31()*units);follow.m32(follow.m32()*units);
            pose.last().pose().mul(follow);pose.last().normal().mul(new Matrix3f(follow));
            FamilyRenderer.render(entity,pose,buffers,packedLight);
            pose.popPose();
            pose.popPose();
        }
        super.render(source, yaw, partialTick, pose, buffers, packedLight);
        RapemOverlayRenderer.render(entity, partialTick, pose, buffers, packedLight);
        SpeechBubble.render(entity, partialTick, pose, buffers, packedLight);
    }

    /**
     * Follow the server's care partner: feeding reaches the child's mouth, while first-toilet
     * assistance reaches its rear. Model-space anchors differ with age, so close the rendered
     * gap with a bounded root lean while keeping the caregiver's body supported.
     */
    private static void configureCareContact(YukkuriView parent,String behavior,float partialTick,YukkuriModel model) {
        boolean firstToilet=behavior.equals(Behaviors.FIRST_TOILET_ASSIST);
        YukkuriView child;
        if (!(behavior.equals(Behaviors.FEED_CHILD) || behavior.equals(Behaviors.BOAT_FEED_YOUNG) || firstToilet) || parent.carePartnerId() < 0
                || (child=parent.yukkuriView(parent.carePartnerId()))==null) {
            model.setCareContact(0,Float.NaN,0,0);
            return;
        }
        var parentPresentation=LivingPresentation.variants.get(parent.assetId());
        var childPresentation=LivingPresentation.variants.get(child.assetId());
        if (parentPresentation == null || childPresentation == null) { model.setCareContact(0,Float.NaN,0,0); return; }
        float parentScale=parent.getScale()*parent.growthScale();
        float childScale=child.getScale()*child.growthScale();
        var parentAt=parent.getPosition(partialTick); var childAt=child.getPosition(partialTick);
        float parentYaw=(180-Mth.rotLerp(partialTick,parent.previousBodyYaw(),parent.bodyYaw()))*Mth.DEG_TO_RAD;
        float childYaw=(180-Mth.rotLerp(partialTick,child.previousBodyYaw(),child.bodyYaw()))*Mth.DEG_TO_RAD;
        // The first-toilet rear uses the same body-width offset and height as its physical
        // land drop. In a hat this is the assistance point, before the child moves to the brim.
        var target=firstToilet?new Vector3f(0,.04f,child.getBbWidth()*.55f)
                :new Vector3f(childPresentation.x(),childPresentation.y(),childPresentation.z()).mul(childScale);
        target.rotateY(childYaw)
                .add((float)(childAt.x-parentAt.x),(float)(childAt.y-parentAt.y),(float)(childAt.z-parentAt.z))
                .rotateY(-parentYaw).mul(16/Math.max(.001f,parentScale));
        float blend=.35f+.65f*Mth.clamp(parent.careContactProgress(),0,1);
        model.setCareContact(target.x,target.y,target.z,blend);
    }

    private static void dailyMotion(YukkuriView entity,float partialTick,PoseStack pose) {
        AgitationPresentation.pose(entity,partialTick,pose);
        String behavior=entity.getBehaviorId(),contact=entity.contactPose();
        boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
        float phaseTicks=Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick);
        float wave=(float)Math.sin(phaseTicks*Math.PI/20);
        YukkuriView young;
        if (!operated && (behavior.equals(Behaviors.BOAT_FEED_YOUNG) || behavior.equals(Behaviors.BOAT_NUZZLE))
                && (young=entity.yukkuriView(entity.aquaticChildId()))!=null
                && entity.vehicleId() >= 0 && entity.vehicleId() == young.vehicleId()) {
            // Turn only the seated model toward this child. Changing the captain's body yaw would steer the whole boat.
            var delta = young.getPosition(partialTick).subtract(entity.getPosition(partialTick));
            float yaw = (float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            float blend = Math.min(1,phaseTicks/8)*Math.min(1,Math.max(0,(48-phaseTicks)/8));
            pose.mulPose(Axis.YP.rotationDegrees(Mth.wrapDegrees(Mth.rotLerp(partialTick,entity.previousBodyYaw(),entity.bodyYaw())-yaw)*blend));
        }
        // The nuzzle, sing and sway clips carry the rubbing and swaying; only the approach stays here.
        if(YukkuriModel.get(entity.assetId()).hasClip(entity.getAnimationId())&&!contact.equals("RUB"))wave=0;
        if(!operated&&behavior.equals(Behaviors.NUZZLE)||contact.equals("RUB")) {
            float lean=0;
            YukkuriView peer;
            if(behavior.equals(Behaviors.NUZZLE)&&(peer=entity.yukkuriView(entity.nuzzlePartnerId()))!=null
                    &&peer.nuzzlePartnerId()==entity.getId()&&peer.getBehaviorId().equals(Behaviors.NUZZLE)) {
                float scale=entity.getScale()*entity.growthScale(),peerScale=peer.getScale()*peer.growthScale();
                double distance=entity.getPosition(partialTick).subtract(peer.getPosition(partialTick)).horizontalDistance();
                double gap=distance-YukkuriModel.get(entity.assetId()).faceDepth()*scale
                        -YukkuriModel.get(peer.assetId()).faceDepth()*peerScale;
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
