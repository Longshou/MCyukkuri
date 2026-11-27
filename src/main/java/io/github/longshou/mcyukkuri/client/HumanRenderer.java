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
    /** The body is drawn eased between the spot in front of the bench and the seat ({@link SeatPose}); the legs bend by the same progress. */
    @Override public void render(HumanNpc npc, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        shadowRadius = .35f;
        pose.pushPose();
        model.seat = (float) npc.seatBlend(partial);
        if (model.seat > 0) {
            var offset = npc.seatOffset(partial);
            pose.translate(offset.x, offset.y, offset.z);
        }
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
        /** How far the sit pose is blended in (0 standing, 1 seated), set by the renderer each frame. */
        float seat;
        @Override public void setupAnim(HumanNpc n, float swing, float amount, float age, float yaw, float pitch) {
            super.setupAnim(n, swing, amount * (1 - seat), age, yaw, pitch);
            if (seat > 0) {
                // The vanilla riding pose, blended in.
                rightArm.xRot -= Mth.PI / 5 * seat;
                leftArm.xRot -= Mth.PI / 5 * seat;
                rightLeg.xRot = Mth.lerp(seat, rightLeg.xRot, -1.4137167f);
                rightLeg.yRot = Mth.lerp(seat, rightLeg.yRot, Mth.PI / 10);
                rightLeg.zRot = Mth.lerp(seat, rightLeg.zRot, .07853982f);
                leftLeg.xRot = Mth.lerp(seat, leftLeg.xRot, -1.4137167f);
                leftLeg.yRot = Mth.lerp(seat, leftLeg.yRot, -Mth.PI / 10);
                leftLeg.zRot = Mth.lerp(seat, leftLeg.zRot, -.07853982f);
            }
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
        }
        public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) { e.registerLayerDefinition(LAYER, Model::layer); }
        public static void screens(RegisterMenuScreensEvent e) {
                e.register(HumanWorld.BADGE_MENU.get(), BadgeScreen::new); e.register(HumanWorld.SHOP_MENU.get(), ShopScreen::new);
                e.register(HumanWorld.KEEPER_MENU.get(), KeeperScreen::new); e.register(HumanWorld.EXAM_MENU.get(), ExamScreen::new); }
    }
}
