package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Locale;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.*;

/** Vanilla humanoid rig, original pixel clothing, and head-bound hair. No player skin downloads. */
public final class HumanRenderer extends HumanoidMobRenderer<HumanNpc, HumanRenderer.Model> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(MCYukkuri.id("human"), "main");
    public HumanRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(LAYER)), .35f);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new ItemInHandLayer<HumanNpc, Model>(this, context.getItemInHandRenderer()) {
            @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, HumanNpc npc,
                    float swing, float amount, float partial, float age, float yaw, float pitch) {
                if (!npc.showsStreetGrain() || !npc.getOffhandItem().isEmpty()) return;
                var arm = npc.getMainArm().getOpposite();
                renderArmWithItem(npc, new ItemStack(ModItems.GRAIN_REGULAR.get()),
                        arm == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        arm, pose, buffers, light);
            }
        });
    }
    /** A bench board sits 0.5 above the cell floor; the vanilla riding legs lie flat when the hips are lowered this far, so the thighs meet the board. */
    private static final double SIT_DROP = .6;
    /** A child is the adult rig at 0.62 with a larger head (Model.setupAnim); its shadow shrinks with it. */
    private static final float CHILD_SCALE = .62f, CHILD_HEAD = 1.15f;
    @Override protected void scale(HumanNpc npc, PoseStack pose, float partial) {
        if (npc.look() == HumanNpc.Look.CHILD) pose.scale(CHILD_SCALE, CHILD_SCALE, CHILD_SCALE);
    }
    @Override public void render(HumanNpc npc, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        shadowRadius = npc.look() == HumanNpc.Look.CHILD ? .35f * CHILD_SCALE : .35f;
        pose.pushPose();
        if (npc.sitsNow()) pose.translate(0, -SIT_DROP, 0);
        super.render(npc, yaw, partial, pose, buffers, light);
        pose.popPose();
    }
    @Override public ResourceLocation getTextureLocation(HumanNpc npc) {
        String name = npc.job() == HumanNpc.Job.SHOPKEEPER ? "shopkeeper" : npc.job() == HumanNpc.Job.FACTORY ? "factory_worker"
                : npc.job() == HumanNpc.Job.EXAMINER ? "examiner" : npc.look().name().toLowerCase(Locale.ROOT);
        return MCYukkuri.id("textures/entity/human/" + name + ".png");
    }
    public static final class Model extends HumanoidModel<HumanNpc> {
        private final ModelPart longHair, mohawk;
        Model(ModelPart root) { super(root); longHair = head.getChild("long_hair"); mohawk = head.getChild("mohawk"); }
        public static LayerDefinition layer() {
            var mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0);
            mesh.getRoot().getChild("head").addOrReplaceChild("long_hair", CubeListBuilder.create().texOffs(0, 36)
                    .addBox(-3, -5, 3.6f, 6, 11, 2), PartPose.ZERO);
            mesh.getRoot().getChild("head").addOrReplaceChild("mohawk", CubeListBuilder.create().texOffs(18, 36)
                    .addBox(-1, -11, -3.5f, 2, 3, 7)
                    .texOffs(18, 47).addBox(-1, -12, -1.5f, 2, 1, 3), PartPose.ZERO);
            return LayerDefinition.create(mesh, 64, 64);
        }
        @Override public void setupAnim(HumanNpc n, float swing, float amount, float age, float yaw, float pitch) {
            if (n.sitsNow()) riding = true;
            // The vanilla crouch (body pitched, hips and legs shifted) is the child's down-on-the-heels pose; it must be set before the base pass.
            crouching = n.childPose() == ChildPlay.Pose.CROUCH || n.childPose() == ChildPlay.Pose.POKE;
            super.setupAnim(n, swing, amount, age, yaw, pitch);
            longHair.visible = n.look() == HumanNpc.Look.SISTER && n.job() == HumanNpc.Job.RESIDENT;
            mohawk.visible = n.look() == HumanNpc.Look.ONIISAN && n.job() == HumanNpc.Job.RESIDENT;
            if (n.resting() && n.getDeltaMovement().horizontalDistanceSqr() < .002) {
                head.xRot = .15f; hat.copyFrom(head); rightArm.xRot = leftArm.xRot = -.25f;
            }
            if (n.showsStreetGrain() && n.getOffhandItem().isEmpty())
                (n.getMainArm() == HumanoidArm.RIGHT ? leftArm : rightArm).xRot -= .5f;
            if (n.job() == HumanNpc.Job.SHOPKEEPER && n.swingingArm == InteractionHand.OFF_HAND && attackTime > 0
                    && CarrierContents.isBasket(n.getMainHandItem())) {
                float reach = Mth.sin(attackTime * Mth.PI);
                var grasp = n.getMainArm() == HumanoidArm.RIGHT ? leftArm : rightArm;
                var basket = n.getMainArm() == HumanoidArm.RIGHT ? rightArm : leftArm;
                grasp.xRot = (head.xRot - 1.6f) * reach;
                grasp.yRot = head.yRot;
                basket.xRot -= .85f * reach;
                body.xRot += .22f * reach;
            }
            if (n.isUsingItem()) {
                var arm = n.getUsedItemHand() == InteractionHand.MAIN_HAND ? n.getMainArm() : n.getMainArm().getOpposite();
                var used = arm == HumanoidArm.RIGHT ? rightArm : leftArm;
                used.xRot = -1.3f;
                used.yRot = arm == HumanoidArm.RIGHT ? -.35f : .35f;
            }
            float headScale = n.look() == HumanNpc.Look.CHILD ? CHILD_HEAD : 1f;
            head.xScale = head.yScale = head.zScale = headScale;
            hat.xScale = hat.yScale = hat.zScale = headScale;
            var gesture = n.childPose();
            if (gesture != ChildPlay.Pose.NONE) {
                var arm = n.getMainArm() == HumanoidArm.RIGHT ? rightArm : leftArm;
                var other = arm == rightArm ? leftArm : rightArm;
                float outward = n.getMainArm() == HumanoidArm.RIGHT ? -1 : 1;
                switch (gesture) {
                    case DRAW -> {
                        // The ball is drawn back beside the hip for the roll: arm swung behind, the body leaning into it.
                        arm.xRot = .9f;
                        body.xRot = .18f;
                    }
                    case WAVE -> {
                        // A friendly wave or a laugh after a run: the hand up and swinging from the shoulder.
                        arm.xRot = -2.5f;
                        arm.zRot = outward * (.2f + .35f * Mth.sin(age * .6f));
                    }
                    case CROUCH -> {
                        // Down on the heels, the free hand held out low with the crumb (or ready to).
                        other.xRot = -1.15f;
                        other.zRot = -outward * .12f;
                        head.xRot += .25f;
                    }
                    case POKE -> {
                        // Down on the heels, one finger out at arm's length and wiggling; no hand goes near the animal.
                        arm.xRot = -1.45f;
                        arm.zRot = outward * .1f * Mth.sin(age * .9f);
                        head.xRot += .2f;
                    }
                    case RUN -> {
                        // A full tilt run: arms thrown wide and pumping, the body leaning forward.
                        arm.xRot *= 1.6f; other.xRot *= 1.6f;
                        arm.zRot = outward * .75f; other.zRot = -outward * .75f;
                        body.xRot = .3f;
                    }
                    case SHRUG -> {
                        // Palms out and the shoulders going up and down once or twice.
                        float lift = .12f * Mth.sin(age * .5f);
                        arm.xRot = -.35f; other.xRot = -.35f;
                        arm.zRot = outward * (.75f + lift); other.zRot = -outward * (.75f + lift);
                        head.zRot = .12f;
                    }
                    case SORRY -> {
                        // Head down, both hands drawn together in front.
                        head.xRot += .4f;
                        arm.xRot = other.xRot = -1.0f;
                        arm.yRot = outward * .5f; other.yRot = -outward * .5f;
                    }
                    default -> {}
                }
                hat.copyFrom(head);
            }
            if (n.kicking()) rightLeg.xRot = -.8f;
            if (n.frowning()) { head.zRot = -.13f; head.xRot += .25f; hat.copyFrom(head); }
        }
    }
    public static final class Events {
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(HumanWorld.TRAY.get(), FactoryTrayRenderer::new);
            e.registerEntityRenderer(HumanWorld.ONIISAN.get(), HumanRenderer::new);
            e.registerEntityRenderer(HumanWorld.BROTHER.get(), HumanRenderer::new);
            e.registerEntityRenderer(HumanWorld.SISTER.get(), HumanRenderer::new);
            e.registerEntityRenderer(HumanWorld.CHILD.get(), HumanRenderer::new);
        }
        public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) { e.registerLayerDefinition(LAYER, Model::layer); }
        public static void screens(RegisterMenuScreensEvent e) {
                e.register(HumanWorld.BADGE_MENU.get(), BadgeScreen::new); e.register(HumanWorld.SHOP_MENU.get(), ShopScreen::new);
                e.register(HumanWorld.KEEPER_MENU.get(), KeeperScreen::new); e.register(HumanWorld.EXAM_MENU.get(), ExamScreen::new); }
    }
}
