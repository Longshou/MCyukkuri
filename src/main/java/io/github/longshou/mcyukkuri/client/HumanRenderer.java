package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.human.*;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Vanilla humanoid rig, original pixel clothing, and head-bound hair. No player skin downloads. */
public final class HumanRenderer extends HumanoidMobRenderer<HumanNpc, HumanRenderer.Model> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(MCYukkuri.id("human"), "main");
    public HumanRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(LAYER)), .35f);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }
    @Override public ResourceLocation getTextureLocation(HumanNpc npc) {
        // EXAMINER/KEEPER are matched by name so this compiles before those Job constants land.
        String job = npc.job().name();
        String name = npc.job() == HumanNpc.Job.SHOPKEEPER ? "shopkeeper" : npc.job() == HumanNpc.Job.FACTORY ? "factory_worker"
                : job.equals("EXAMINER") ? "examiner" : job.equals("KEEPER") ? "keeper"
                : npc.look().name().toLowerCase(java.util.Locale.ROOT);
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
            super.setupAnim(n, swing, amount, age, yaw, pitch);
            longHair.visible = n.look() == HumanNpc.Look.SISTER && n.job() == HumanNpc.Job.RESIDENT;
            mohawk.visible = n.look() == HumanNpc.Look.ONIISAN && n.job() == HumanNpc.Job.RESIDENT;
            if (n.resting() && n.getDeltaMovement().horizontalDistanceSqr() < .002) {
                head.xRot = .15f; hat.copyFrom(head); rightArm.xRot = leftArm.xRot = -.25f;
            }
        }
    }
    @EventBusSubscriber(modid = MCYukkuri.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Events {
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(HumanWorld.TRAY.get(), FactoryTrayRenderer::new);
            e.registerEntityRenderer(HumanWorld.ONIISAN.get(), HumanRenderer::new);
            e.registerEntityRenderer(HumanWorld.BROTHER.get(), HumanRenderer::new);
            e.registerEntityRenderer(HumanWorld.SISTER.get(), HumanRenderer::new);
        }
        @SubscribeEvent public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) { e.registerLayerDefinition(LAYER, Model::layer); }
        @SubscribeEvent public static void screens(RegisterMenuScreensEvent e) { e.register(HumanWorld.MENU.get(), HumanScreen::new); e.register(HumanWorld.BADGE_MENU.get(), BadgeScreen::new); e.register(HumanWorld.SHOP_MENU.get(), ShopScreen::new); e.register(HumanWorld.KEEPER_MENU.get(), KeeperScreen::new); e.register(HumanWorld.EXAM_MENU.get(), ExamScreen::new); }
    }
}
