package io.github.longshou.mcyukkuri.client;
import io.github.longshou.mcyukkuri.body.DetachedItems;

import io.github.longshou.mcyukkuri.client.capture.PoseTap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.ParentCarry.Hold;
import io.github.longshou.mcyukkuri.motor.TimelineClock;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.play.PlayBlock;
import io.github.longshou.mcyukkuri.registry.ModItems;
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
        // Her stick lying in her set-down hat is drawn with her: seen whenever that hat is.
        var hat=entity.getHeldItem().isEmpty()||entity.heldPlace().hat()<0?null:entity.level().getEntity(entity.heldPlace().hat());
        if(hat!=null&&frustum.isVisible(hat.getBoundingBoxForCulling().inflate(1)))return true;
        // The body-sized collision box excludes tall hats and animated stretches.
        double margin = entity.broodCount()>0?3:entity.profile().houseBody() ? .4 : 1.2;
        return source.shouldRender(x, y, z) && frustum.isVisible(entity.getBoundingBoxForCulling().inflate(margin));
    }

    @Override
    public ResourceLocation getTextureLocation(YukkuriEntity source) {
        YukkuriView entity=source;
        return textureAt(entity, expression(entity));
    }

    private static String expression(YukkuriView entity) {
        String face = ParentCarryPresentation.expression(entity, entity.getExpressionId());
        if (switch (face) { case "pin_cry", "pressed_cry", "horrified", "cut_cry", "sukkiri_squeeze", "flushed_smile" -> true; default -> false; }
                && (!entity.getMouthItem().isEmpty() || !entity.getMouthBodyFood().isEmpty()
                    || !entity.getCarriedItem().isEmpty() || ParentCarry.mouthCarrying(entity)
                    || HeldItemPresentation.hasMouthKit(entity))) face = "teary_frown";
        return FaceLibrary.expression(entity, face);
    }

    private ResourceLocation textureAt(YukkuriView entity, String expression) {
        int dry=Math.max(entity.aftermathDrain(),HairWoundAssets.wholeBurn(entity.visibleWounds(),entity.profile().houseBody()));
        return HairWoundAssets.texture(entity.assetId(),expression,dry);
    }

    @Override
    public void render(YukkuriEntity source, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        render(source, yaw, partialTick, pose, buffers, packedLight, false);
    }

    void renderPreview(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        PoseTap.inGui(() -> render(entity, entity.getYRot(), partialTick, pose, buffers, packedLight, true));
    }

    private void render(YukkuriEntity source, float yaw, float partialTick, PoseStack pose,
                        MultiBufferSource buffers, int packedLight, boolean preview) {
        YukkuriView entity=source;
        if(entity.isDeadOrDying())return;
        var holder=entity.vehicleYukkuri();
        // A carried child (any hold) rides on its carrier above the ground: only the carrier casts a shadow.
        if(!preview)shadowRadius=holder!=null&&holder.hasPassengerId(entity.getUUID())?0:entity.profile().shadowRadius()*entity.growthScale();
        // A baby inside its parent's closed mouth is not seen.
        if(holder!=null&&ParentCarryPresentation.swallowed(entity,holder,partialTick))return;
        if (!entity.isInvisible()) {
            var start=new Matrix4f(pose.last().pose());
            pose.pushPose();
            var parent=holder;
            boolean carried=parent!=null&&parent.hasPassengerId(entity.getUUID());
            boolean hatCarrier=ParentCarry.brimCarrying(entity);
            var reproduction=ReproductionPresentation.frame(entity,partialTick);
            if(!preview&&!hatCarrier&&!carried) {
                var offset=reproduction.offset();
                pose.translate(offset.x,0,offset.z);
            }
            float renderedBodyYaw=Mth.rotLerp(partialTick,entity.previousBodyYaw(),entity.bodyYaw());
            // A carried child is drawn on the carrier's live lips, brim, tail or braid; a child just set down eases to the ground.
            if(carried&&!preview)renderedBodyYaw=ParentCarryPresentation.place(entity,parent,pose,partialTick);
            else {
                if(!preview)ParentCarryPresentation.settle(entity,pose,partialTick);
                float bodyYaw=renderedBodyYaw;
                String waterPhase=entity.waterDistressPhase();
                if(waterPhase.equals("IMPACT")||waterPhase.equals("STRUGGLE")||waterPhase.equals("EXHAUSTED")){
                    float headYaw=Mth.rotLerp(partialTick,entity.previousHeadYaw(),entity.getYHeadRot());
                    bodyYaw+=Mth.clamp(Mth.wrapDegrees(headYaw-bodyYaw),-35,35);
                }
                // A rider stands on the hat's floor: it leans with the hull's synced tilt, about its own feet.
                var host=entity.vehicleHat();
                if(host!=null&&!preview){
                    float hostYaw=180-Mth.rotLerp(partialTick,host.previousYaw(),host.getYRot());
                    pose.mulPose(Axis.YP.rotationDegrees(hostYaw));pose.mulPose(Axis.ZP.rotationDegrees(host.roll(partialTick)));
                    pose.mulPose(Axis.XP.rotationDegrees(host.pitch(partialTick)));pose.mulPose(Axis.YP.rotationDegrees(-hostYaw));
                }
                pose.mulPose(Axis.YP.rotationDegrees(180-bodyYaw));
                renderedBodyYaw=bodyYaw;
            }
            if (entity.deathTicks() > 0) pose.mulPose(Axis.ZP.rotationDegrees(Math.min(90, (entity.deathTicks() + partialTick) * 4.5f)));
            if(entity.stemDepletionWarning()&&!entity.getAnimationId().equals("tremble"))
                pose.mulPose(Axis.ZP.rotationDegrees(2*Mth.sin((entity.ageTicks()+partialTick)*1.7f)));
            pose.scale(entity.getScale(),entity.getScale(),entity.getScale());
            // Keep the body, badges, carried items and stem family under the same daily-motion transform.
            if(!hatCarrier&&!carried)dailyMotion(entity,partialTick,pose);
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
            // House bodies retain wound mechanics but never show the wounded-skin silhouette.
            boolean open = false;
            if (!entity.profile().houseBody()) {
                for (var wound : entity.visibleWounds()) {
                    if (BodySigns.open(wound)) { open = true; break; }
                }
            }
            float health=entity.getMaxHealth()<=0?1:entity.getHealth()/entity.getMaxHealth();
            pose.scale(BodySigns.sagWidth(health,open),BodySigns.sagHeight(health,open),BodySigns.sagWidth(health,open));
            pose.scale(HairWoundAssets.drainWidth(entity.aftermathDrain()),1,1);
            if(contact.equals("VIBRATE"))pose.translate(Math.sin((entity.ageTicks()+partialTick)*2)*.008,0,0);
            float animationTicks = entity.getAnimationId().equals(ParentCarry.BRIM_LIFT)
                    ? (float) TimelineClock.elapsed(entity.level(), entity.getAnimationStartTick(), partialTick)
                    : entity.level().getGameTime() - entity.getAnimationStartTick() + partialTick;
            float elapsed = Math.max(0, animationTicks / 20f);
            elapsed = WaterMotionClock.seconds(entity, partialTick, elapsed);
            int overlay = OverlayTexture.pack(0, entity.hurtTicks() > 0 || entity.deathTicks() > 0);
            String expression = expression(entity);
            var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(textureAt(entity, expression)));
            String behavior = hatCarrier||carried?Behaviors.IDLE:entity.getBehaviorId();
            if(!preview&&behavior.equals(Behaviors.SLIDE_DOWN)){
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
            if (entity.isCrawling()&&!hatCarrier&&!carried) {
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
            // Her held stack is drawn where the server's record puts it: the hold pose carries it only while her grip has it.
            var heldNow=HeldItemPresentation.now(entity,partialTick);
            model.setHeldItem(heldNow!=null&&heldNow.overlay(),heldNow!=null&&heldNow.attach(),entity.vehicleHat()!=null);
            model.setMouthHolding(HeldItemPresentation.closedGrip(entity)||!entity.getMouthBodyFood().isEmpty()||ParentCarry.mouthCarrying(entity)
                    ||operated&&!entity.getMouthItem().isEmpty()
                    ||heldNow!=null&&heldNow.mouth());
            configureCareContact(entity,behavior,partialTick,model);
            var delivery=LiveBirthRenderer.prepare(entity,model,partialTick);
            model.setPregnancy(entity.livePregnancyProgress(),LiveBirthRenderer.contraction(entity,partialTick));model.setDelivery(delivery);
            // A braid-held child is cradled; a carrier whose braid reaches plays the braid clip on its Grab's clock.
            boolean cradled=carried&&ParentCarry.hold(parent,entity)==Hold.BRAID;
            // Held through its carrier's hop it tucks in; one on her head sits on it as it does at rest.
            boolean carriedHop=carried&&parent.motorAction().equals(Hop.ID)&&ParentCarry.hold(parent,entity)!=Hold.HEAD;
            String braidClip=carried?null:ParentCarryPresentation.liftClip(entity);
            // A brim young steps along the brim between the braid at its edge and its spot on its own feet.
            float brimWalkClock=carried?ParentCarry.brimWalkClock(parent,entity,partialTick):-1;
            boolean stepping=brimWalkClock>=0;
            boolean brimHopping=carried&&ParentCarry.brimHopping(parent,entity);
            boolean leavingBrim=carried&&ParentCarry.hold(parent,entity)==Hold.BRIM&&entity.motorAction().equals(Hop.ID);
            String action=braidClip!=null?braidClip:brimHopping?"hop_leg":carriedHop?"boat_be_carried":cradled?"be_cradled":stepping?"waddle":carried&&!leavingBrim?"idle":hatCarrier?ParentCarry.carryAction(entity):entity.getAnimationId();
            float clock=braidClip!=null?Math.max(0,(entity.level().getGameTime()-entity.motorStart()+partialTick)/20f)
                    :brimHopping?ParentCarry.brimHopClock(parent,entity,partialTick)
                    :carriedHop?(entity.level().getGameTime()-parent.motorStart()+partialTick)/20f
                    :stepping?brimWalkClock:cradled?(entity.level().getGameTime()+partialTick)/20f:carried&&!leavingBrim?0:elapsed;
            var anatomy=reproduction.anatomy();
            model.setReproductiveAnatomy(anatomy);
            if(!carried)ParentCarryPresentation.configure(entity,model,heldNow,partialTick);
            // The worn wrap lies under the hair; the model lifts the hair over it as it draws (CareWearRenderer.clearHair).
            int wear=preview||!hatCarrier&&!carried?entity.visibleWear():0;
            model.setWear(wear);
            var toolContact=model.renderLiving(operated||behavior.equals(Behaviors.SLIDE_DOWN)?"idle":action, operated?0:clock, pose, buffer, packedLight, overlay,
                    entity.hasVisibleAccessory(), operated?Behaviors.IDLE:behavior,phaseTicks,entity.visibleHairLoss(),entity,expression,buffers);
            // Captured before anything else poses this shared model (stem fruits reuse it).
            PoseTap.body(entity,start,pose.last().pose(),partialTick);
            if(!preview&&!carried)ParentCarryPresentation.capture(entity,model,start,pose.last().pose(),renderedBodyYaw,partialTick);
            ToiletPresentation.renderTongue(entity,behavior,phaseTicks,partialTick,model,pose,buffers,packedLight,overlay);
            // The stool keeps the clock its body clip is sampled on (YukkuriModel: the action's own clock when the action is that clip), so
            // the server's release at clip tick DROP_TICKS is the tick this prop hands over to the item.
            ToiletPresentation.renderStool(entity,behavior,behavior.equals(action)?clock*20:phaseTicks,pose,buffers,packedLight);
            var stemRoot=FamilyRenderer.visible(entity)?model.rootPose():null;
            if(entity.hasVisibleAccessory()||contact.equals("HAT_REMOVE")) model.renderBadge(entity.visibleMedal(),pose,buffers,packedLight,overlay);
            if(wear!=0)CareWearRenderer.render(model,wear,pose,buffers,packedLight,overlay);
            var presentation=LivingPresentation.variants.get(entity.assetId());
            if(!entity.getMouthBodyFood().isEmpty()) {
                pose.pushPose();
                HeldItemPresentation.mouth(pose,model);
                BodyFoodMouthRenderer.render(entity.getMouthBodyFood(),pose,buffers,packedLight,overlay);
                pose.popPose();
            }
            for(int heldIndex=0;heldIndex<2;heldIndex++){
                // A prepared mash remains at the caregiver's mouth until the server atomically
                // places that same stack in the child's mouth; food never travels as an independent projectile.
                var held=heldIndex==0?entity.getMouthItem():entity.getCarriedItem();
                if(held.isEmpty())continue;
                boolean mash=InfantFood.isMash(held);
                // The parent's real pending twig is mouth-held until the server hands that same item to the child.
                var grip=heldIndex==1&&!behavior.equals(Behaviors.FAMILY_CHEW)&&!mash?model.gripPoint():null;
                pose.pushPose();
                if(grip!=null)pose.translate(grip.x,grip.y,grip.z);
                else HeldItemPresentation.mouth(pose,model);
                if(mash){
                    float portionScale=entity.profile().portionScale();
                    pose.scale(portionScale,portionScale,portionScale);
                    PropModels.render("food_mash_serving",1,pose,buffers,packedLight,overlay);pose.popPose();continue;
                }
                if(held.is(ModItems.WASTE.get())){
                    float inverseBody=1/(entity.growthScale()*entity.getScale());pose.scale(inverseBody,inverseBody,inverseBody);
                    WastePresentation.render(DetachedItems.volume(held),false,pose,buffers,packedLight,overlay);
                    pose.popPose();continue;
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
            if(heldNow!=null)HeldItemPresentation.held(entity.getHeldItem(),entity,model,heldNow,start,pose,buffers,packedLight,overlay,partialTick);
            if(!operated&&HeldItemPresentation.kanroken(entity,behavior)){
                // A Myon's stick sword rides crosswise in the free mouth, on the same mouth pose as food (rare/MyonLife).
                pose.pushPose();HeldItemPresentation.mouth(pose,model);
                PoseTap.mouthProp(entity,"kanroken",start,pose.last().pose(),partialTick);
                PropModels.render("kanroken",1,pose,buffers,packedLight,overlay);pose.popPose();
            }
            if(contact.equals("PIN")&&toolContact!=null)OperationAttachments.pin(entity,toolContact,pose,buffers,packedLight);
            if(contact.equals("VIBRATE"))model.renderSurfaceProp("care_vibrator",1,1,pose,buffers,packedLight,OverlayTexture.NO_OVERLAY);
            if(contact.equals("ASSIST"))LiveBirthRenderer.renderDose(entity,pose,buffers,packedLight);
            if(contact.equals("HOOK")||contact.equals("WRAPPED"))OperationAttachments.render(entity,toolContact,start,pose,buffers,packedLight,partialTick);
            if(delivery!=null)delivery.render(pose,buffers,packedLight,start,partialTick);
            model.setDelivery(null);model.setPregnancy(0,0);
            pose.popPose();
            pose.popPose();
            // The stem grows from the forehead: carry it with the body's root motion, in this block-scaled space.
            if (stemRoot!=null) {
                pose.pushPose();
                float units=entity.growthScale()/16f;
                stemRoot.m30(stemRoot.m30()*units);stemRoot.m31(stemRoot.m31()*units);stemRoot.m32(stemRoot.m32()*units);
                pose.last().pose().mul(stemRoot);pose.last().normal().mul(new Matrix3f(stemRoot));
                FamilyRenderer.render(entity,partialTick,pose,buffers,packedLight);
                pose.popPose();
            }
            pose.popPose();
        }
        if(!preview){
            super.render(source, yaw, partialTick, pose, buffers, packedLight);
            RapemOverlayRenderer.render(entity, partialTick, pose, buffers, packedLight);
            SpeechBubble.render(entity, partialTick, pose, buffers, packedLight);
            MomentEmote.render(entity, partialTick, pose, buffers, packedLight);
        }
    }

    /**
     * Follow the server's care partner: feeding reaches the child's mouth, while first-toilet
     * assistance reaches its rear. Model-space anchors differ with age, so close the rendered
     * gap with a bounded root lean while keeping the caregiver's body supported.
     */
    private static void configureCareContact(YukkuriView parent,String behavior,float partialTick,YukkuriModel model) {
        boolean firstToilet=ToiletPresentation.rearCare(behavior);
        YukkuriView child;
        if (!(behavior.equals(Behaviors.FEED_CHILD) || firstToilet) || parent.carePartnerId() < 0
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
        ReproductionPresentation.pose(entity,partialTick,pose);
        String behavior=entity.getBehaviorId(),contact=entity.contactPose();
        boolean operated=!contact.equals("NONE")&&!contact.equals("SLIDE");
        float phaseTicks=Math.max(0,entity.level().getGameTime()-entity.getBehaviorStartTick()+partialTick);
        float wave=(float)Math.sin(phaseTicks*Math.PI/20);
        YukkuriView young;
        // The nuzzle, sing and sway clips carry the rubbing and swaying; only the approach stays here.
        if(YukkuriModel.get(entity.assetId()).hasClip(entity.getAnimationId())&&!contact.equals("RUB"))wave=0;
        if(!operated&&behavior.equals(Behaviors.NUZZLE)||contact.equals("RUB")) {
            float lean=0;
            YukkuriView peer;
            // Only face-to-face rubbing closes a gap along the face. The greeting and the shared side press
            // already have their own spacing, including while the behavior and clip updates arrive separately.
            if(behavior.equals(Behaviors.NUZZLE)&&entity.getAnimationId().equals("nuzzle")
                    &&(peer=entity.yukkuriView(entity.nuzzlePartnerId()))!=null
                    &&peer.nuzzlePartnerId()==entity.getId()&&peer.getBehaviorId().equals(Behaviors.NUZZLE)
                    &&peer.getAnimationId().equals("nuzzle")
                    &&Math.abs(Mth.wrapDegrees(entity.bodyYaw()-peer.bodyYaw()))>90) {
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
